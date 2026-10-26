package es.mrdino.perplayerprofiles;

import es.mrdino.perplayerprofiles.command.ProfilesCommand;
import es.mrdino.perplayerprofiles.command.ProfileCommandSuggestions;
import es.mrdino.perplayerprofiles.command.PaperProfileCompletions;
import es.mrdino.perplayerprofiles.compat.ServerPasswordCompatibility;
import es.mrdino.perplayerprofiles.gui.GuiHolder;
import es.mrdino.perplayerprofiles.gui.ProfileGuiManager;
import es.mrdino.perplayerprofiles.gui.SelectionGuard;
import es.mrdino.perplayerprofiles.lang.LanguageService;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import es.mrdino.perplayerprofiles.state.PlayerStateService;
import es.mrdino.perplayerprofiles.storage.ProfileStorage;
import es.mrdino.perplayerprofiles.config.ConfigFiles;
import es.mrdino.perplayerprofiles.security.PinSecurity;
import es.mrdino.perplayerprofiles.update.UpdateChecker;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Statistic;
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
    private final ProfileCommandSuggestions profileCommandSuggestions = new ProfileCommandSuggestions();
    private ProfileStorage storage;
    private PlayerStateService stateService;
    private ServerPasswordCompatibility serverPasswordCompatibility;
    private LanguageService languageService;
    private ProfileGuiManager profileGuiManager;
    private String masterProfileName;
    private PinSecurity pinSecurity;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        updateConfigDefaults();
        languageService = new LanguageService(this);
        languageService.load();
        masterProfileName = AccountData.normalizeName(getConfig().getString("profiles.master-profile-name", "master"));
        storage = new ProfileStorage(this);
        languageService.setPreferenceResolver(player -> loadAccount(player.getUniqueId()).getPreferredLanguage());
        stateService = new PlayerStateService();
        serverPasswordCompatibility = new ServerPasswordCompatibility(this);
        pinSecurity = new PinSecurity(this);
        profileGuiManager = new ProfileGuiManager(this);
        updateChecker = new UpdateChecker(this);

        ProfilesCommand profilesCommand = new ProfilesCommand(this);
        getCommand("profiles").setExecutor(profilesCommand);
        getCommand("profiles").setTabCompleter(profilesCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(profileGuiManager, this);
        Bukkit.getPluginManager().registerEvents(new SelectionGuard(this), this);
        PaperProfileCompletions.install(this, profileCommandSuggestions);
        Bukkit.getScheduler().runTask(this, this::refreshOnlinePlayerCommands);

        long autoSaveTicks = Math.max(1L, getConfig().getLong("profiles.auto-save-minutes", 5L)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> saveOnlinePlayers(), autoSaveTicks, autoSaveTicks);
        Bukkit.getScheduler().runTaskTimer(this, this::checkProfileTimeLimits, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::showProfileTimeWarnings, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> profileGuiManager.refreshOpenProfileMenus(), 20L, 20L);
    }

    private void updateConfigDefaults() {
        try { ConfigFiles.update(this, "config.yml"); }
        catch (IOException failure) { throw new IllegalStateException("Could not safely update config.yml", failure); }
        reloadConfig();
    }

    private void refreshOnlinePlayerCommands() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) updateChecker.shutdown();
        if (profileGuiManager != null) profileGuiManager.shutdown();
        if (storage == null) return;
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
        account.setPlayerName(player.getName());
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
        profileGuiManager.clearSession(player);
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

    public Map<UUID, String> knownPlayerAccounts() {
        Map<UUID, String> names = new HashMap<>(storage.knownPlayerNames());
        for (org.bukkit.OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && (accounts.containsKey(player.getUniqueId()) || storage.hasAccount(player.getUniqueId())))
                names.put(player.getUniqueId(), player.getName());
        }
        accounts.forEach((uuid, account) -> { if (account.getPlayerName() != null) names.put(uuid, account.getPlayerName()); });
        return names;
    }

    public UUID resolveKnownAccount(String input) {
        try {
            UUID uuid = UUID.fromString(input);
            if (accounts.containsKey(uuid) || storage.hasAccount(uuid)) return uuid;
        } catch (IllegalArgumentException ignored) { }
        return knownPlayerAccounts().entrySet().stream().filter(entry -> entry.getValue().equalsIgnoreCase(input))
                .map(Map.Entry::getKey).findFirst().orElse(null);
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
        if (!isProfileReady(player)) {
            send(player, "waiting-for-auth");
            return false;
        }
        String profileName = AccountData.normalizeName(targetProfile);
        AccountData account = loadAccount(player.getUniqueId());
        ProfileMeta profile = account.getProfile(profileName);
        if (profile == null) {
            send(player, "profile-not-found");
            return false;
        }
        if (profile.isPasswordResetRequired()) {
            profileGuiManager.beginPasswordRecovery(player, profile.getName());
            return false;
        }
        if (profile.isTimeExceeded(System.currentTimeMillis())) {
            send(player, "profile-no-time");
            return false;
        }

        profileGuiManager.finishProfileSelection(player);
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
        send(player, "profile-active", "profile", profile.getName());
        if (profileChanged) profileGuiManager.playTransition(player, account.getProfile(oldProfileName), profile);
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
            profile.chargeSession(since, System.currentTimeMillis());
            profile.recordSession(System.currentTimeMillis() - since, player.getLevel(), player.getStatistic(Statistic.DEATHS),
                    player.getStatistic(Statistic.MOB_KILLS), player.getStatistic(Statistic.PLAYER_KILLS));
            account.setLastActiveProfileName(profileName);
        }
        YamlConfiguration snapshot = stateService.capture(player);
        if (profile != null) profile.updateTypedStatistics(snapshot.getConfigurationSection("statistics-typed"));
        storage.saveProfileState(player.getUniqueId(), profileName, snapshot);
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
        if (profileGuiManager != null) profileGuiManager.reportMessage(player, key, replacements);
    }

    public LanguageService getLanguageService() {
        return languageService;
    }

    public ProfileGuiManager getProfileGuiManager() {
        return profileGuiManager;
    }

    public PinSecurity getPinSecurity() { return pinSecurity; }

    public boolean isProfileReady(Player player) {
        return loadedProfiles.contains(player.getUniqueId()) && !isWaitingForExternalAuth(player);
    }

    public long getRemainingPlayMillis(Player player, ProfileMeta profile) {
        long now = System.currentTimeMillis();
        long since = AccountData.normalizeName(profile.getName()).equals(getActiveProfile(player))
                ? activeSince.getOrDefault(player.getUniqueId(), now) : now;
        return profile.getRemainingMillis(now, since);
    }

    public void reloadPluginSettings() {
        updateConfigDefaults();
        languageService.load();
        masterProfileName = AccountData.normalizeName(getConfig().getString("profiles.master-profile-name", "master"));
        updateProfileCommandCompletions();
        refreshOnlinePlayerCommands();
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
        if (profile != null) profile.initializePlaytime(player.getStatistic(Statistic.PLAY_ONE_MINUTE) * 50L);
        if (profile != null) {
            profile.recordConnection(System.currentTimeMillis());
            profile.updateTypedStatistics(yaml.getConfigurationSection("statistics-typed"));
        }
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
        migrateLegacyProgress(player, loadAccount(uuid));
        loadProfile(player, joinProfileName);
        updateProfileCommandCompletions();
        player.updateCommands();
        AccountData account = loadAccount(uuid);
        ProfileMeta joined = account.getProfile(joinProfileName);
        if (joined != null && joined.isPasswordResetRequired()) {
            profileGuiManager.requireProfileSelection(player);
            profileGuiManager.beginPasswordRecovery(player, joined.getName());
            return;
        }
        if (shouldOpenJoinSelector(account, joinProfileName, requireSelection)) {
            waitAndOpenJoinProfileSelector(player, 0);
        }
    }

    private void migrateLegacyProgress(Player player, AccountData account) {
        String previous = account.hasProfile(account.getLastActiveProfileName()) ? account.getLastActiveProfileName() : account.getMasterProfileName(masterProfileName);
        YamlConfiguration state = storage.loadProfileState(player.getUniqueId(), previous);
        if (!stateService.hasState(state) || state.contains("recipes") && state.contains("statistics-typed")) return;
        YamlConfiguration progress = new YamlConfiguration();
        es.mrdino.perplayerprofiles.state.ProfileProgress.capture(player, progress);
        if (!state.contains("recipes")) state.set("recipes", progress.get("recipes"));
        if (!state.contains("statistics-typed")) {
            ConfigurationSection typed = state.createSection("statistics-typed");
            progress.getConfigurationSection("statistics-typed").getValues(true).forEach((path, value) -> {
                if (!(value instanceof ConfigurationSection)) typed.set(path, value);
            });
        }
        storage.saveProfileState(player.getUniqueId(), previous, state);
    }

    public YamlConfiguration profileProgress(Player player, String profileName) {
        if (AccountData.normalizeName(profileName).equals(getActiveProfile(player))) {
            YamlConfiguration progress = new YamlConfiguration();
            es.mrdino.perplayerprofiles.state.ProfileProgress.capture(player, progress);
            return progress;
        }
        return storage.loadProfileState(player.getUniqueId(), profileName);
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
        if (!getConfig().getBoolean("profile-identity.commands.enabled", true)) return;
        String[] parts = event.getCommand().trim().split("\\s+");
        if (parts.length > 0 && normalizeCommandName(parts[0]).equals("list")) {
            event.setCancelled(true);
            sendProfileList(event.getSender());
        } else {
            event.setCommand(rewriteProfileCommandTargets("/" + event.getCommand()).substring(1));
        }
    }

    private boolean handleBuiltInProfileCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length == 0) {
            return false;
        }
        String commandName = normalizeCommandName(parts[0]);
        if (commandName.equals("list") && event.getPlayer().hasPermission("minecraft.command.list")) {
            event.setCancelled(true);
            sendProfileList(event.getPlayer());
            return true;
        }
        return false;
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
        java.util.List<String> completions = new java.util.ArrayList<>(event.getCompletions());
        UUID viewer = event.getSender() instanceof Player player ? player.getUniqueId() : null;
        for (String name : profileCommandSuggestions.complete(viewer, event.getBuffer())) {
            if (!completions.contains(name)) completions.add(name);
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
        Map<String, java.util.List<Integer>> targets = new HashMap<>();
        ConfigurationSection rewrite = getConfig().getConfigurationSection("profile-identity.commands.rewrite");
        if (getConfig().getBoolean("profile-identity.commands.enabled", true) && rewrite != null) {
            for (String command : rewrite.getKeys(false)) targets.put(command, rewrite.getIntegerList(command));
        }
        Map<UUID, java.util.List<String>> visibleNames = new HashMap<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (loadedProfiles.contains(target.getUniqueId()) && viewer.canSee(target)) {
                    ProfileMeta profile = getActiveProfileMeta(target);
                    if (profile != null) names.add(profile.getName());
                }
            }
            visibleNames.put(viewer.getUniqueId(), names);
        }
        profileCommandSuggestions.publish(targets, visibleNames, onlineActiveProfileNames());
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            java.util.Set<String> activeNames = targets.isEmpty() ? java.util.Set.of()
                    : new java.util.HashSet<>(visibleNames.get(onlinePlayer.getUniqueId()));
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

    private void waitAndOpenJoinProfileSelector(Player player, int elapsedSeconds) {
        if (!player.isOnline()) {
            return;
        }
        int maxWaitSeconds = Math.max(0, getConfig().getInt("profiles.selector-auth-wait-seconds", 60));
        if (isWaitingForExternalAuth(player) || elapsedSeconds < maxWaitSeconds && hasExternalGuiOpen(player)) {
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
        pinSecurity.clearExpired();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String profileName = getActiveProfile(player);
            AccountData account = loadAccount(player.getUniqueId());
            ProfileMeta profile = account.getProfile(profileName);
            Long since = activeSince.get(player.getUniqueId());
            if (profile == null || since == null || !profile.isTimeLimited()) {
                continue;
            }
            long remaining = profile.getRemainingMillis(now, since);
            if (remaining <= 0L && !profile.isMaster()) {
                String kickMessage = languageService.get(player, "profile-time-expired-kick",
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
            long remaining = profile.getRemainingMillis(now, since);
            if (remaining > 5 * 60_000L) {
                continue;
            }
            String color = remaining <= 30_000L
                    ? (blink ? "&c&l" : "&4&l")
                    : remaining <= 60_000L ? "&c" : "&6";
            String message = languageService.get(player, "time-warning-actionbar", "time", formatDuration(remaining));
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
