package es.mrdino.perplayerprofiles;

import es.mrdino.perplayerprofiles.command.ProfilesCommand;
import es.mrdino.perplayerprofiles.compat.ServerPasswordCompatibility;
import es.mrdino.perplayerprofiles.gui.GuiHolder;
import es.mrdino.perplayerprofiles.gui.ProfileGuiManager;
import es.mrdino.perplayerprofiles.lang.LanguageService;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import es.mrdino.perplayerprofiles.state.PlayerStateService;
import es.mrdino.perplayerprofiles.storage.ProfileStorage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;

public class PerPlayerProfilesPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, AccountData> accounts = new HashMap<>();
    private final Map<UUID, String> activeProfiles = new HashMap<>();
    private final Map<UUID, Long> activeSince = new HashMap<>();
    private final Set<UUID> loadedProfiles = new HashSet<>();
    private final Map<UUID, java.util.Set<String>> sentProfileCompletions = new HashMap<>();
    private final Map<String, Command> wrappedProfileCommands = new HashMap<>();
    private ProfileStorage storage;
    private PlayerStateService stateService;
    private ServerPasswordCompatibility serverPasswordCompatibility;
    private LanguageService languageService;
    private ProfileGuiManager profileGuiManager;
    private String masterProfileName;

    @Override
    public void onEnable() {
        updateConfigDefaults();
        languageService = new LanguageService(this);
        languageService.load();
        masterProfileName = AccountData.normalizeName(getConfig().getString("profiles.master-profile-name", "master"));
        storage = new ProfileStorage(this);
        stateService = new PlayerStateService();
        serverPasswordCompatibility = new ServerPasswordCompatibility(this);
        profileGuiManager = new ProfileGuiManager(this);

        ProfilesCommand profilesCommand = new ProfilesCommand(this);
        getCommand("profiles").setExecutor(profilesCommand);
        getCommand("profiles").setTabCompleter(profilesCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(profileGuiManager, this);
        registerProfileCommandWrappers();
        Bukkit.getScheduler().runTask(this, this::refreshOnlinePlayerCommands);

        long autoSaveTicks = Math.max(1L, getConfig().getLong("profiles.auto-save-minutes", 5L)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> saveOnlinePlayers(), autoSaveTicks, autoSaveTicks);
        Bukkit.getScheduler().runTaskTimer(this, this::checkProfileTimeLimits, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::showProfileTimeWarnings, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> profileGuiManager.refreshOpenProfileMenus(), 20L, 20L);
    }

    private void updateConfigDefaults() {
        saveDefaultConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultConfigStream = getResource("config.yml");
        if (defaultConfigStream == null) {
            reloadConfig();
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            if (mergeMissingDefaults(current, defaults)) {
                current.save(configFile);
            }
        } catch (IOException e) {
            getLogger().warning("Could not update config.yml defaults: " + e.getMessage());
        }
        reloadConfig();
    }

    @SuppressWarnings("unchecked")
    private void registerProfileCommandWrappers() {
        if (!getConfig().getBoolean("profile-identity.commands.enabled", true)) {
            return;
        }
        ConfigurationSection rewriteSection = getConfig().getConfigurationSection("profile-identity.commands.rewrite");
        if (rewriteSection == null) {
            return;
        }
        try {
            Method getCommandMap = Bukkit.getServer().getClass().getMethod("getCommandMap");
            Object commandMap = getCommandMap.invoke(Bukkit.getServer());
            Field knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
            for (String commandName : rewriteSection.getKeys(false)) {
                String normalizedName = normalizeCommandName(commandName);
                java.util.List<String> matchingKeys = new java.util.ArrayList<>();
                for (String knownCommandName : new java.util.ArrayList<>(knownCommands.keySet())) {
                    if (normalizeCommandName(knownCommandName).equals(normalizedName)) {
                        matchingKeys.add(knownCommandName);
                    }
                }
                for (String matchingKey : matchingKeys) {
                    Command existing = knownCommands.get(matchingKey);
                    if (existing == null || existing instanceof ProfileTargetCommand) {
                        continue;
                    }
                    ProfileTargetCommand wrapper = normalizedName.equals("list")
                            ? new ProfileListCommand(normalizedName, existing)
                            : new ProfileTargetCommand(normalizedName, existing);
                    knownCommands.put(matchingKey, wrapper);
                    wrappedProfileCommands.put(matchingKey, existing);
                }
            }
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Could not register profile command wrappers. Profile names may not appear in vanilla command tab-complete.");
        }
    }

    private boolean mergeMissingDefaults(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (!target.contains(key)) {
                if (defaults.isConfigurationSection(key)) {
                    ConfigurationSection created = target.createSection(key);
                    mergeMissingDefaults(created, defaults.getConfigurationSection(key));
                } else {
                    target.set(key, defaults.get(key));
                }
                changed = true;
                continue;
            }
            if (defaults.isConfigurationSection(key) && target.isConfigurationSection(key)) {
                changed |= mergeMissingDefaults(target.getConfigurationSection(key), defaults.getConfigurationSection(key));
            }
        }
        return changed;
    }

    private void refreshOnlinePlayerCommands() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    @Override
    public void onDisable() {
        saveOnlinePlayers(true);
        for (Map.Entry<UUID, AccountData> entry : accounts.entrySet()) {
            storage.saveAccount(entry.getKey(), entry.getValue());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        AccountData account = loadAccount(player.getUniqueId());
        ensureMasterProfile(account, player.getName());
        for (ProfileMeta profile : account.getProfiles().values()) {
            profile.ensureVirtualUuid(player.getUniqueId());
        }
        storage.saveAccount(player.getUniqueId(), account);
        String joinProfileName = resolveJoinProfile(account);
        boolean requireSelection = shouldRequireJoinSelection(account, joinProfileName);
        boolean waitingForAuth = isWaitingForExternalAuth(player);

        activeProfiles.put(uuid, joinProfileName);
        activeSince.remove(uuid);
        loadedProfiles.remove(uuid);
        if (!waitingForAuth) {
            applyProfileIdentity(player, joinProfileName);
        }
        if (getConfig().getBoolean("profile-identity.join-leave.enabled", true) && !waitingForAuth) {
            event.setJoinMessage(formatIdentityMessage("profile-identity.join-leave.join-format", player, joinProfileName));
        } else if (waitingForAuth) {
            event.setJoinMessage(null);
        }
        updateProfileCommandCompletions();
        player.updateCommands();
        Bukkit.getScheduler().runTask(this, () -> waitAndLoadJoinProfile(uuid, joinProfileName, requireSelection, 0));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean profileLoaded = loadedProfiles.contains(player.getUniqueId());
        String profileName = getActiveProfile(player);
        if (getConfig().getBoolean("profile-identity.join-leave.enabled", true) && profileLoaded) {
            event.setQuitMessage(formatIdentityMessage("profile-identity.join-leave.leave-format", player, profileName));
        } else if (!profileLoaded) {
            event.setQuitMessage(null);
        }
        saveActiveProfileForTransition(player);
        accounts.remove(player.getUniqueId());
        activeProfiles.remove(player.getUniqueId());
        activeSince.remove(player.getUniqueId());
        loadedProfiles.remove(player.getUniqueId());
        sentProfileCompletions.remove(player.getUniqueId());
        updateProfileCommandCompletions();
        profileGuiManager.clearRequiredProfileSelection(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        saveActiveProfileForTransition(event.getPlayer());
    }

    public AccountData loadAccount(UUID uuid) {
        return accounts.computeIfAbsent(uuid, storage::loadAccount);
    }

    public void saveAccount(UUID uuid) {
        AccountData account = accounts.get(uuid);
        if (account != null) {
            storage.saveAccount(uuid, account);
        }
    }

    public void ensureMasterProfile(AccountData account) {
        ensureMasterProfile(account, masterProfileName);
    }

    public void ensureMasterProfile(AccountData account, String defaultMasterName) {
        String fallbackName = AccountData.normalizeName(defaultMasterName).isBlank() ? masterProfileName : defaultMasterName;
        ProfileMeta master = account.getMasterProfile(fallbackName);
        if (master == null) {
            master = new ProfileMeta(fallbackName);
            account.putProfile(master);
        }
        account.markOnlyMaster(master.getName());
        account.sortMasterFirst(fallbackName);
    }

    public boolean switchProfile(Player player, String targetProfile) {
        String profileName = AccountData.normalizeName(targetProfile);
        AccountData account = loadAccount(player.getUniqueId());
        ProfileMeta profile = account.getProfile(profileName);
        if (profile == null) {
            send(player, "profile-not-found");
            return false;
        }
        if (profile.isTimeExceeded(System.currentTimeMillis())) {
            send(player, "profile-no-time");
            return false;
        }

        UUID uuid = player.getUniqueId();
        String oldProfileName = getActiveProfile(player);
        saveActiveProfileForTransition(player);
        boolean profileChanged = !AccountData.normalizeName(oldProfileName).equals(profileName);
        if (profileChanged) {
            broadcastVirtualQuit(player, oldProfileName);
        }
        activeProfiles.put(uuid, profileName);
        activeSince.remove(uuid);
        loadedProfiles.remove(uuid);
        loadProfile(player, profileName);
        applyProfileIdentity(player, profileName);
        account.setLastActiveProfileName(profileName);
        storage.saveAccount(player.getUniqueId(), account);
        if (profileChanged) {
            broadcastVirtualJoin(player, profileName);
            logProfileSwitch(player, oldProfileName, profileName);
        }
        updateProfileCommandCompletions();
        profileGuiManager.clearRequiredProfileSelection(player);
        send(player, "profile-active", "profile", profile.getName());
        return true;
    }

    public void saveActiveProfile(Player player) {
        saveActiveProfile(player, false);
    }

    public void saveActiveProfileForTransition(Player player) {
        saveActiveProfile(player, true);
    }

    private void saveActiveProfile(Player player, boolean criticalTransition) {
        if (!loadedProfiles.contains(player.getUniqueId())) {
            return;
        }
        String profileName = activeProfiles.get(player.getUniqueId());
        if (profileName == null) {
            return;
        }
        if (criticalTransition) {
            prepareInventoryForProfileSnapshot(player);
        }
        AccountData account = loadAccount(player.getUniqueId());
        ProfileMeta profile = account.getProfile(profileName);
        Long since = activeSince.put(player.getUniqueId(), System.currentTimeMillis());
        if (profile != null && since != null) {
            profile.addUsedMillis(System.currentTimeMillis() - since);
            account.setLastActiveProfileName(profileName);
        }
        storage.saveProfileState(player.getUniqueId(), profileName, stateService.capture(player));
        storage.saveAccount(player.getUniqueId(), account);
        if (criticalTransition) {
            player.saveData();
        }
    }

    private void prepareInventoryForProfileSnapshot(Player player) {
        if (!player.isOnline()) {
            return;
        }
        if (hasExternalNonProfileInventoryOpen(player)) {
            player.closeInventory();
        }
        player.updateInventory();
    }

    private boolean hasExternalNonProfileInventoryOpen(Player player) {
        return player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING
                && !(player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder);
    }

    public void deleteProfileState(UUID uuid, String profileName) {
        storage.deleteProfileState(uuid, profileName);
    }

    public void renameProfileState(UUID uuid, String oldProfileName, String newProfileName) {
        storage.renameProfileState(uuid, oldProfileName, newProfileName);
    }

    public String getActiveProfile(Player player) {
        return activeProfiles.getOrDefault(player.getUniqueId(), loadAccount(player.getUniqueId()).getMasterProfileName(masterProfileName));
    }

    public void updateActiveProfileName(Player player, String oldProfileName, String newProfileName) {
        if (getActiveProfile(player).equals(AccountData.normalizeName(oldProfileName))) {
            activeProfiles.put(player.getUniqueId(), AccountData.normalizeName(newProfileName));
            applyProfileIdentity(player, newProfileName);
        }
        AccountData account = loadAccount(player.getUniqueId());
        if (AccountData.normalizeName(oldProfileName).equals(account.getLastActiveProfileName())) {
            account.setLastActiveProfileName(newProfileName);
        }
        updateProfileCommandCompletions();
    }

    public UUID getActiveProfileVirtualUuid(Player player) {
        ProfileMeta profile = getActiveProfileMeta(player);
        return profile == null ? player.getUniqueId() : profile.ensureVirtualUuid(player.getUniqueId());
    }

    public ProfileMeta getActiveProfileMeta(Player player) {
        return loadAccount(player.getUniqueId()).getProfile(getActiveProfile(player));
    }

    public long getActiveSessionMillis(Player player) {
        Long since = activeSince.get(player.getUniqueId());
        return since == null ? 0L : Math.max(0L, System.currentTimeMillis() - since);
    }

    public String getMasterProfileName() {
        return masterProfileName;
    }

    public String getMasterProfileName(Player player) {
        return loadAccount(player.getUniqueId()).getMasterProfileName(masterProfileName);
    }

    public boolean isMasterProfile(Player player, String profileName) {
        return loadAccount(player.getUniqueId()).isMasterProfile(profileName, masterProfileName);
    }

    public int getMaxProfilesPerAccount() {
        return Math.max(1, getConfig().getInt("profiles.max-per-account", 3));
    }

    public boolean isMasterPinRequiredToCreateProfiles() {
        return getConfig().getBoolean("profiles.require-master-pin-to-create", false);
    }

    public int getMinPasswordLength() {
        return getMinPinLength();
    }

    public int getMinPinLength() {
        return clamp(getConfig().getInt("security.pin.min-length", 4), 4, 9);
    }

    public int getMaxPinLength() {
        int min = getMinPinLength();
        return Math.max(min, clamp(getConfig().getInt("security.pin.max-length", 9), 4, 9));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void send(Player player, String key, Object... replacements) {
        languageService.send(player, key, replacements);
    }

    public LanguageService getLanguageService() {
        return languageService;
    }

    public ProfileGuiManager getProfileGuiManager() {
        return profileGuiManager;
    }

    private void loadProfile(Player player, String profileName) {
        AccountData account = loadAccount(player.getUniqueId());
        ProfileMeta profile = account.getProfile(profileName);
        YamlConfiguration yaml = storage.loadProfileState(player.getUniqueId(), profileName);
        if (stateService.hasState(yaml)) {
            stateService.apply(player, yaml);
        } else if (profile != null && profile.isMaster()) {
            storage.saveProfileState(player.getUniqueId(), profileName, stateService.capture(player));
        } else {
            YamlConfiguration freshState = stateService.createFreshProfileState(player);
            stateService.apply(player, freshState);
            storage.saveProfileState(player.getUniqueId(), profileName, stateService.capture(player));
        }
        applyProfileIdentity(player, profileName);
        loadedProfiles.add(player.getUniqueId());
        activeSince.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void waitAndLoadJoinProfile(UUID uuid, String joinProfileName, boolean requireSelection, int elapsedSeconds) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        int maxWaitSeconds = Math.max(0, getConfig().getInt("profiles.selector-auth-wait-seconds", 60));
        if (isWaitingForExternalAuth(player)) {
            if (elapsedSeconds == maxWaitSeconds) {
                getLogger().warning("Still waiting for ServerPassword authentication before loading " + player.getName() + "'s profile state.");
            }
            Bukkit.getScheduler().runTaskLater(this, () -> waitAndLoadJoinProfile(uuid, joinProfileName, requireSelection, elapsedSeconds + 1), 20L);
            return;
        }
        if (elapsedSeconds < maxWaitSeconds && hasExternalGuiOpen(player)) {
            Bukkit.getScheduler().runTaskLater(this, () -> waitAndLoadJoinProfile(uuid, joinProfileName, requireSelection, elapsedSeconds + 1), 20L);
            return;
        }
        loadProfile(player, joinProfileName);
        updateProfileCommandCompletions();
        player.updateCommands();
        AccountData account = loadAccount(uuid);
        if (shouldOpenJoinSelector(account, joinProfileName, requireSelection)) {
            waitAndOpenJoinProfileSelector(player, 0);
        }
    }

    private String resolveJoinProfile(AccountData account) {
        String lastProfileName = account.getLastActiveProfileName();
        ProfileMeta lastProfile = account.getProfile(lastProfileName);
        if (lastProfile != null && !lastProfile.hasPassword() && !lastProfile.isTimeExceeded(System.currentTimeMillis())) {
            return AccountData.normalizeName(lastProfile.getName());
        }
        return account.getMasterProfileName(masterProfileName);
    }

    private boolean shouldRequireJoinSelection(AccountData account, String joinProfileName) {
        ProfileMeta lastProfile = account.getProfile(account.getLastActiveProfileName());
        if (lastProfile == null) {
            return false;
        }
        if (lastProfile.hasPassword() || lastProfile.isTimeExceeded(System.currentTimeMillis())) {
            return true;
        }
        return false;
    }

    private boolean isLastProfileAutoLoaded(AccountData account, String joinProfileName) {
        String lastProfileName = account.getLastActiveProfileName();
        ProfileMeta lastProfile = account.getProfile(lastProfileName);
        return lastProfile != null
                && AccountData.normalizeName(lastProfile.getName()).equals(AccountData.normalizeName(joinProfileName))
                && !lastProfile.hasPassword()
                && !lastProfile.isTimeExceeded(System.currentTimeMillis());
    }

    private boolean shouldOpenJoinSelector(AccountData account, String joinProfileName, boolean requireSelection) {
        if (requireSelection) {
            return true;
        }
        if (account.getProfiles().size() <= 1) {
            return false;
        }
        if (getConfig().getBoolean("profiles.require-selection-on-join", true)) {
            return true;
        }
        return getConfig().getBoolean("profiles.open-selector-on-join", true) && !isLastProfileAutoLoaded(account, joinProfileName);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProfileChat(AsyncPlayerChatEvent event) {
        if (!getConfig().getBoolean("profile-identity.chat.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        String profileName = getActiveProfile(player);
        String format = color(getConfig().getString("profile-identity.chat.format", "<{display_name}> {message}"));
        format = format
                .replace("{profile}", displayName(player, profileName))
                .replace("{display_name}", "%1$s")
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{virtual_uuid}", getActiveProfileVirtualUuid(player).toString())
                .replace("{message}", "%2$s");
        event.setFormat(format);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProfileCommand(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("profile-identity.commands.enabled", true)) {
            return;
        }
        String originalMessage = event.getMessage();
        if (handleBuiltInProfileCommand(event)) {
            logProfileCommand(event.getPlayer(), originalMessage, originalMessage, true);
            return;
        }
        String rewritten = rewriteProfileCommandTargets(event.getMessage());
        if (!rewritten.equals(event.getMessage())) {
            event.setMessage(rewritten);
            logProfileCommand(event.getPlayer(), originalMessage, rewritten, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsoleProfileCommand(ServerCommandEvent event) {
        String[] parts = event.getCommand().trim().split("\\s+");
        if (parts.length == 0 || !normalizeCommandName(parts[0]).equals("list")) {
            return;
        }
        event.setCancelled(true);
        sendProfileList(event.getSender());
    }

    private boolean handleBuiltInProfileCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length == 0) {
            return false;
        }
        String commandName = normalizeCommandName(parts[0]);
        if (commandName.equals("list")) {
            event.setCancelled(true);
            sendProfileList(event.getPlayer());
            return true;
        }
        if (commandName.equals("tp") || commandName.equals("teleport")) {
            return handleProfileTeleport(event, parts);
        }
        return false;
    }

    private boolean handleProfileTeleport(PlayerCommandPreprocessEvent event, String[] parts) {
        if (parts.length == 2) {
            Player target = resolveProfilePlayer(parts[1]);
            if (target == null) {
                return false;
            }
            event.setCancelled(true);
            event.getPlayer().teleport(target);
            event.getPlayer().sendMessage(color(languageService.get("teleport-to-player", "target", listDisplayName(target))));
            return true;
        }
        if (parts.length == 3) {
            Player source = resolveProfilePlayer(parts[1]);
            Player target = resolveProfilePlayer(parts[2]);
            if (source == null && target == null) {
                return false;
            }
            if (source == null) {
                source = Bukkit.getPlayerExact(parts[1]);
            }
            if (target == null) {
                target = Bukkit.getPlayerExact(parts[2]);
            }
            if (source == null || target == null) {
                return false;
            }
            event.setCancelled(true);
            source.teleport(target);
            event.getPlayer().sendMessage(color(languageService.get("teleport-player-to-player", "source", listDisplayName(source), "target", listDisplayName(target))));
            return true;
        }
        if (parts.length >= 5) {
            Player source = resolveProfilePlayer(parts[1]);
            if (source == null) {
                return false;
            }
            Location location = parseTeleportLocation(source, parts);
            if (location == null) {
                return false;
            }
            event.setCancelled(true);
            source.teleport(location);
            event.getPlayer().sendMessage(color(languageService.get("teleport-player-to-location",
                    "source", listDisplayName(source),
                    "x", formatCoordinate(location.getX()),
                    "y", formatCoordinate(location.getY()),
                    "z", formatCoordinate(location.getZ()))));
            return true;
        }
        return false;
    }

    private Location parseTeleportLocation(Player source, String[] parts) {
        try {
            Location base = source.getLocation();
            double x = parseCoordinate(parts[2], base.getX());
            double y = parseCoordinate(parts[3], base.getY());
            double z = parseCoordinate(parts[4], base.getZ());
            Location location = new Location(source.getWorld(), x, y, z, base.getYaw(), base.getPitch());
            if (parts.length >= 7) {
                location.setYaw((float) Double.parseDouble(parts[5]));
                location.setPitch((float) Double.parseDouble(parts[6]));
            }
            return location;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseCoordinate(String input, double baseValue) {
        if (input.equals("~")) {
            return baseValue;
        }
        if (input.startsWith("~")) {
            return baseValue + Double.parseDouble(input.substring(1));
        }
        return Double.parseDouble(input);
    }

    private String formatCoordinate(double value) {
        return String.format(java.util.Locale.US, "%.6f", value);
    }

    private void sendProfileList(CommandSender sender) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!loadedProfiles.contains(player.getUniqueId())) {
                continue;
            }
            names.add(listDisplayName(player));
        }
        sender.sendMessage(color(languageService.get("server-list",
                "online", names.size(),
                "max", Bukkit.getMaxPlayers(),
                "players", String.join("&7, &f", names))));
    }

    private void logProfileCommand(Player player, String originalCommand, String rewrittenCommand, boolean handledByPlugin) {
        if (!getConfig().getBoolean("profile-identity.command-log.enabled", true)) {
            return;
        }
        if (!getConfig().getBoolean("profile-identity.command-log.allow-duplicate-console-log", false)) {
            return;
        }
        if (!handledByPlugin && originalCommand.equals(rewrittenCommand)) {
            return;
        }
        ProfileMeta profile = getActiveProfileMeta(player);
        String profileName = profile == null ? getActiveProfile(player) : profile.getName();
        String command = redactSensitiveCommand(originalCommand);
        String rewritten = redactSensitiveCommand(rewrittenCommand);
        String message = getConfig().getString("profile-identity.command-log.format",
                "&f{player} &8(&b{profile}&8) &7used &e{command}");
        Bukkit.getConsoleSender().sendMessage(color("&8[&aPerPlayerProfiles&8] &r" + message
                .replace("{player}", player.getName())
                .replace("{profile}", profileName)
                .replace("{player_with_profile}", listDisplayName(player))
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{virtual_uuid}", getActiveProfileVirtualUuid(player).toString())
                .replace("{command}", command)
                .replace("{rewritten_command}", rewritten)
                .replace("{handled_by_plugin}", String.valueOf(handledByPlugin))));
    }

    private String redactSensitiveCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String[] parts = command.trim().split("\\s+");
        String commandName = normalizeCommandName(parts[0]);
        for (String sensitiveCommand : getConfig().getStringList("profile-identity.command-log.redact-commands")) {
            if (commandName.equalsIgnoreCase(sensitiveCommand)) {
                return parts[0] + " <redacted>";
            }
        }
        return command;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProfileCommandTab(TabCompleteEvent event) {
        if (!getConfig().getBoolean("profile-identity.commands.enabled", true) || !event.getBuffer().startsWith("/")) {
            return;
        }
        String buffer = event.getBuffer();
        String[] parts = buffer.trim().split("\\s+");
        if (parts.length == 0) {
            return;
        }
        String commandName = normalizeCommandName(parts[0]);
        int argIndex = buffer.endsWith(" ") ? parts.length - 1 : parts.length - 2;
        if (argIndex < 0 || !commandTargetIndexes(commandName).contains(argIndex)) {
            return;
        }
        String prefix = buffer.endsWith(" ") ? "" : parts[parts.length - 1];
        java.util.List<String> completions = new java.util.ArrayList<>(event.getCompletions());
        for (String profileName : onlineActiveProfileNames()) {
            if (profileName.toLowerCase().startsWith(prefix.toLowerCase()) && !completions.contains(profileName)) {
                completions.add(profileName);
            }
        }
        event.setCompletions(completions);
    }

    public void applyProfileIdentity(Player player, String profileName) {
        if (!getConfig().getBoolean("profile-identity.enabled", true)) {
            return;
        }
        String displayName = displayName(player, profileName);
        player.setDisplayName(displayName);
        if (getConfig().getBoolean("profile-identity.tab-list.enabled", true)) {
            player.setPlayerListName(limitLegacyName(displayName, 16));
        }
        if (getConfig().getBoolean("profile-identity.nametag.enabled", true)) {
            player.setCustomName(displayName);
            player.setCustomNameVisible(true);
        }
    }

    private String formatIdentityMessage(String path, Player player, String profileName) {
        String format = resolveLocalizedFormat(path);
        if (format == null || format.isBlank()) {
            return null;
        }
        return color(format
                .replace("{profile}", displayName(player, profileName))
                .replace("{display_name}", displayName(player, profileName))
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{virtual_uuid}", getActiveProfileVirtualUuid(player).toString()));
    }

    private String resolveLocalizedFormat(String path) {
        String format = getConfig().getString(path, "");
        if (format == null || format.isBlank()) {
            return format;
        }
        if (format.startsWith("lang:")) {
            return languageService.get(format.substring("lang:".length()));
        }
        if (path.endsWith("join-format") && format.equals("&e{display_name} joined the game")) {
            return languageService.get("profile-join-message");
        }
        if (path.endsWith("leave-format") && format.equals("&e{display_name} left the game")) {
            return languageService.get("profile-leave-message");
        }
        return format;
    }

    private void broadcastVirtualQuit(Player player, String profileName) {
        if (!getConfig().getBoolean("profile-identity.join-leave.enabled", true)) {
            return;
        }
        String message = formatIdentityMessage("profile-identity.join-leave.leave-format", player, profileName);
        if (message != null && !message.isBlank()) {
            Bukkit.broadcastMessage(message);
        }
    }

    private void broadcastVirtualJoin(Player player, String profileName) {
        if (!getConfig().getBoolean("profile-identity.join-leave.enabled", true)) {
            return;
        }
        String message = formatIdentityMessage("profile-identity.join-leave.join-format", player, profileName);
        if (message != null && !message.isBlank()) {
            Bukkit.broadcastMessage(message);
        }
    }

    private void logProfileSwitch(Player player, String oldProfileName, String newProfileName) {
        if (!getConfig().getBoolean("profile-identity.profile-switch-log.enabled", true)) {
            return;
        }
        String message = getConfig().getString("profile-identity.profile-switch-log.format",
                "{player} switched profile from {old_profile} to {new_profile}");
        Bukkit.getConsoleSender().sendMessage(color("&8[&aPerPlayerProfiles&8] &r" + message
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{old_profile}", "&c" + plainProfileName(player, oldProfileName) + "&r")
                .replace("{new_profile}", "&a" + plainProfileName(player, newProfileName) + "&r")
                .replace("{old_virtual_uuid}", virtualUuidForProfile(player, oldProfileName).toString())
                .replace("{new_virtual_uuid}", virtualUuidForProfile(player, newProfileName).toString())));
    }

    private String displayName(Player player, String profileName) {
        ProfileMeta profile = loadAccount(player.getUniqueId()).getProfile(profileName);
        String name = profile == null ? profileName : profile.getName();
        return color(getConfig().getString("profile-identity.display-name-format", "{profile}").replace("{profile}", name));
    }

    private String plainProfileName(Player player, String profileName) {
        ProfileMeta profile = loadAccount(player.getUniqueId()).getProfile(profileName);
        return profile == null ? profileName : profile.getName();
    }

    private UUID virtualUuidForProfile(Player player, String profileName) {
        ProfileMeta profile = loadAccount(player.getUniqueId()).getProfile(profileName);
        return profile == null ? player.getUniqueId() : profile.ensureVirtualUuid(player.getUniqueId());
    }

    private String limitLegacyName(String value, int maxVisibleChars) {
        StringBuilder result = new StringBuilder();
        int visible = 0;
        for (int i = 0; i < value.length() && visible < maxVisibleChars; i++) {
            char current = value.charAt(i);
            result.append(current);
            if (current == ChatColor.COLOR_CHAR && i + 1 < value.length()) {
                result.append(value.charAt(++i));
                continue;
            }
            visible++;
        }
        return result.toString();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private String rewriteProfileCommandTargets(String message) {
        if (message == null || !message.startsWith("/")) {
            return message == null ? "" : message;
        }
        String[] parts = message.trim().split("\\s+");
        if (parts.length <= 1) {
            return message;
        }
        String commandName = normalizeCommandName(parts[0]);
        java.util.List<Integer> targetIndexes = commandTargetIndexes(commandName);
        if (targetIndexes.isEmpty()) {
            return message;
        }
        boolean changed = false;
        for (int targetIndex : targetIndexes) {
            int partIndex = targetIndex + 1;
            if (partIndex >= parts.length) {
                continue;
            }
            String realPlayerName = resolveRealPlayerName(parts[partIndex]);
            if (realPlayerName != null) {
                parts[partIndex] = realPlayerName;
                changed = true;
            }
        }
        return changed ? String.join(" ", parts) : message;
    }

    private String[] rewriteProfileCommandArgs(String commandName, String[] args) {
        java.util.List<Integer> targetIndexes = commandTargetIndexes(commandName);
        if (targetIndexes.isEmpty()) {
            return args;
        }
        String[] rewritten = java.util.Arrays.copyOf(args, args.length);
        for (int targetIndex : targetIndexes) {
            if (targetIndex >= rewritten.length) {
                continue;
            }
            String realPlayerName = resolveRealPlayerName(rewritten[targetIndex]);
            if (realPlayerName != null) {
                rewritten[targetIndex] = realPlayerName;
            }
        }
        return rewritten;
    }

    private java.util.List<String> addProfileCompletions(String commandName, String[] args, java.util.List<String> completions) {
        java.util.List<String> merged = new java.util.ArrayList<>(completions == null ? java.util.List.of() : completions);
        int argIndex = Math.max(0, args.length - 1);
        if (!commandTargetIndexes(commandName).contains(argIndex)) {
            return merged;
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1];
        for (String profileName : onlineActiveProfileNames()) {
            if (profileName.toLowerCase().startsWith(prefix.toLowerCase()) && !merged.contains(profileName)) {
                merged.add(profileName);
            }
        }
        return merged;
    }

    private String normalizeCommandName(String commandLabel) {
        String name = commandLabel.startsWith("/") ? commandLabel.substring(1) : commandLabel;
        int namespaceIndex = name.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex + 1 < name.length()) {
            name = name.substring(namespaceIndex + 1);
        }
        return name.toLowerCase();
    }

    private java.util.List<Integer> commandTargetIndexes(String commandName) {
        return getConfig().getIntegerList("profile-identity.commands.rewrite." + commandName);
    }

    private String resolveRealPlayerName(String virtualName) {
        Player player = resolveProfilePlayer(virtualName);
        return player == null ? null : player.getName();
    }

    private Player resolveProfilePlayer(String virtualName) {
        String normalizedVirtualName = AccountData.normalizeName(virtualName);
        if (normalizedVirtualName.isBlank()) {
            return null;
        }
        Player match = null;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!loadedProfiles.contains(onlinePlayer.getUniqueId())) {
                continue;
            }
            ProfileMeta profile = getActiveProfileMeta(onlinePlayer);
            if (profile == null || !AccountData.normalizeName(profile.getName()).equals(normalizedVirtualName)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = onlinePlayer;
        }
        return match;
    }

    private java.util.List<String> onlineActiveProfileNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!loadedProfiles.contains(onlinePlayer.getUniqueId())) {
                continue;
            }
            ProfileMeta profile = getActiveProfileMeta(onlinePlayer);
            if (profile != null && !names.contains(profile.getName())) {
                names.add(profile.getName());
            }
        }
        return names;
    }

    private String listDisplayName(Player player) {
        ProfileMeta profile = getActiveProfileMeta(player);
        if (profile != null && !AccountData.normalizeName(profile.getName()).equals(AccountData.normalizeName(player.getName()))) {
            return player.getName() + " (" + profile.getName() + ")";
        }
        String displayName = ChatColor.stripColor(player.getDisplayName());
        if (displayName != null && !AccountData.normalizeName(displayName).equals(AccountData.normalizeName(player.getName()))) {
            return player.getName() + " (" + displayName + ")";
        }
        String listName = ChatColor.stripColor(player.getPlayerListName());
        if (listName != null && !AccountData.normalizeName(listName).equals(AccountData.normalizeName(player.getName()))) {
            return player.getName() + " (" + listName + ")";
        }
        if (profile == null || AccountData.normalizeName(profile.getName()).equals(AccountData.normalizeName(player.getName()))) {
            return player.getName();
        }
        return player.getName() + " (" + profile.getName() + ")";
    }

    private void updateProfileCommandCompletions() {
        if (!getConfig().getBoolean("profile-identity.commands.enabled", true)) {
            return;
        }
        java.util.Set<String> activeNames = new java.util.HashSet<>(onlineActiveProfileNames());
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            java.util.Set<String> previousNames = sentProfileCompletions.getOrDefault(onlinePlayer.getUniqueId(), java.util.Set.of());
            if (!previousNames.isEmpty()) {
                onlinePlayer.removeCustomChatCompletions(previousNames);
            }
            if (!activeNames.isEmpty()) {
                onlinePlayer.addCustomChatCompletions(activeNames);
            }
            sentProfileCompletions.put(onlinePlayer.getUniqueId(), activeNames);
            onlinePlayer.updateCommands();
        }
    }

    private class ProfileTargetCommand extends Command {
        private final Command delegate;

        private ProfileTargetCommand(String name, Command delegate) {
            super(name, delegate.getDescription(), delegate.getUsage(), delegate.getAliases());
            this.delegate = delegate;
            setPermission(delegate.getPermission());
            setPermissionMessage(delegate.getPermissionMessage());
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return delegate.execute(sender, commandLabel, rewriteProfileCommandArgs(getName(), args));
        }

        @Override
        public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
            return addProfileCompletions(getName(), args, delegate.tabComplete(sender, alias, args));
        }

        @Override
        public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) throws IllegalArgumentException {
            return addProfileCompletions(getName(), args, delegate.tabComplete(sender, alias, args, location));
        }
    }

    private final class ProfileListCommand extends ProfileTargetCommand {
        private ProfileListCommand(String name, Command delegate) {
            super(name, delegate);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            sendProfileList(sender);
            return true;
        }
    }

    private void waitAndOpenJoinProfileSelector(Player player, int elapsedSeconds) {
        if (!player.isOnline()) {
            return;
        }
        int maxWaitSeconds = Math.max(0, getConfig().getInt("profiles.selector-auth-wait-seconds", 60));
        if (elapsedSeconds < maxWaitSeconds && (isWaitingForExternalAuth(player) || hasExternalGuiOpen(player))) {
            Bukkit.getScheduler().runTaskLater(this, () -> waitAndOpenJoinProfileSelector(player, elapsedSeconds + 1), 20L);
            return;
        }
        profileGuiManager.requireProfileSelection(player);
        profileGuiManager.openMain(player);
    }

    private boolean hasExternalGuiOpen(Player player) {
        return player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING;
    }

    private boolean isWaitingForExternalAuth(Player player) {
        if (!getConfig().getBoolean("compatibility.server-password.wait-for-auth", true)) {
            return false;
        }
        return serverPasswordCompatibility.isWaitingForAuth(player);
    }

    private void saveOnlinePlayers() {
        saveOnlinePlayers(false);
    }

    private void saveOnlinePlayers(boolean criticalTransition) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            saveActiveProfile(player, criticalTransition);
        }
    }

    private void checkProfileTimeLimits() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String profileName = getActiveProfile(player);
            AccountData account = loadAccount(player.getUniqueId());
            ProfileMeta profile = account.getProfile(profileName);
            Long since = activeSince.get(player.getUniqueId());
            if (profile == null || since == null || !profile.isTimeLimited()) {
                continue;
            }
            long remaining = Math.max(0L, profile.getRemainingMillis(now) - Math.max(0L, now - since));
            if (remaining <= 0L && !profile.isMaster()) {
                String kickMessage = languageService.get("profile-time-expired-kick",
                        "profile", profile.getName(),
                        "renew", formatDuration(profile.getMillisUntilRenewal(now)));
                saveActiveProfile(player);
                player.kickPlayer(ChatColor.translateAlternateColorCodes('&', kickMessage));
            }
        }
    }

    private void showProfileTimeWarnings() {
        long now = System.currentTimeMillis();
        boolean blink = (now / 1000L) % 2L == 0L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            String profileName = getActiveProfile(player);
            AccountData account = loadAccount(player.getUniqueId());
            ProfileMeta profile = account.getProfile(profileName);
            Long since = activeSince.get(player.getUniqueId());
            if (profile == null || profile.isMaster() || since == null || !profile.isTimeLimited()) {
                continue;
            }
            long remaining = Math.max(0L, profile.getRemainingMillis(now) - Math.max(0L, now - since));
            if (remaining > 5 * 60_000L) {
                continue;
            }
            String color = remaining <= 30_000L
                    ? (blink ? "&c&l" : "&4&l")
                    : remaining <= 60_000L ? "&c" : "&6";
            String message = languageService.get("time-warning-actionbar", "time", formatDuration(remaining));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', color + message)));
        }
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes > 0L) {
            return String.format("%dm %02ds", minutes, remainingSeconds);
        }
        return remainingSeconds + "s";
    }
}
