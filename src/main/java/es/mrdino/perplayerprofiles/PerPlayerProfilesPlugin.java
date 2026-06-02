package es.mrdino.perplayerprofiles;

import es.mrdino.perplayerprofiles.command.ProfilesCommand;
import es.mrdino.perplayerprofiles.gui.ProfileGuiManager;
import es.mrdino.perplayerprofiles.lang.LanguageService;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import es.mrdino.perplayerprofiles.state.PlayerStateService;
import es.mrdino.perplayerprofiles.storage.ProfileStorage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;

public class PerPlayerProfilesPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, AccountData> accounts = new HashMap<>();
    private final Map<UUID, String> activeProfiles = new HashMap<>();
    private final Map<UUID, Long> activeSince = new HashMap<>();
    private ProfileStorage storage;
    private PlayerStateService stateService;
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
        profileGuiManager = new ProfileGuiManager(this);

        ProfilesCommand profilesCommand = new ProfilesCommand(this);
        getCommand("profiles").setExecutor(profilesCommand);
        getCommand("profiles").setTabCompleter(profilesCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(profileGuiManager, this);

        long autoSaveTicks = Math.max(1L, getConfig().getLong("profiles.auto-save-minutes", 5L)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::saveOnlinePlayers, autoSaveTicks, autoSaveTicks);
        Bukkit.getScheduler().runTaskTimer(this, this::checkProfileTimeLimits, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::showProfileTimeWarnings, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> profileGuiManager.refreshOpenProfileMenus(), 20L, 20L);
    }

    private void updateConfigDefaults() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
    }

    @Override
    public void onDisable() {
        saveOnlinePlayers();
        for (Map.Entry<UUID, AccountData> entry : accounts.entrySet()) {
            storage.saveAccount(entry.getKey(), entry.getValue());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        AccountData account = loadAccount(player.getUniqueId());
        ensureMasterProfile(account);
        storage.saveAccount(player.getUniqueId(), account);
        String accountMasterProfileName = account.getMasterProfileName(masterProfileName);

        activeProfiles.put(player.getUniqueId(), accountMasterProfileName);
        activeSince.put(player.getUniqueId(), System.currentTimeMillis());
        Bukkit.getScheduler().runTask(this, () -> {
            loadProfile(player, accountMasterProfileName);
            if (getConfig().getBoolean("profiles.open-selector-on-join", true) && account.getProfiles().size() > 1) {
                waitAndOpenJoinProfileSelector(player, 0);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        saveActiveProfile(event.getPlayer());
        accounts.remove(event.getPlayer().getUniqueId());
        activeProfiles.remove(event.getPlayer().getUniqueId());
        activeSince.remove(event.getPlayer().getUniqueId());
        profileGuiManager.clearRequiredProfileSelection(event.getPlayer());
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
        ProfileMeta master = account.getMasterProfile(masterProfileName);
        if (master == null) {
            master = new ProfileMeta(masterProfileName);
            account.putProfile(master);
        }
        account.markOnlyMaster(master.getName());
        account.sortMasterFirst(masterProfileName);
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

        saveActiveProfile(player);
        activeProfiles.put(player.getUniqueId(), profileName);
        activeSince.put(player.getUniqueId(), System.currentTimeMillis());
        loadProfile(player, profileName);
        profileGuiManager.clearRequiredProfileSelection(player);
        send(player, "profile-active", "profile", profile.getName());
        return true;
    }

    public void saveActiveProfile(Player player) {
        String profileName = activeProfiles.get(player.getUniqueId());
        if (profileName == null) {
            return;
        }
        AccountData account = loadAccount(player.getUniqueId());
        ProfileMeta profile = account.getProfile(profileName);
        Long since = activeSince.put(player.getUniqueId(), System.currentTimeMillis());
        if (profile != null && since != null) {
            profile.addUsedMillis(System.currentTimeMillis() - since);
        }
        storage.saveProfileState(player.getUniqueId(), profileName, stateService.capture(player));
        storage.saveAccount(player.getUniqueId(), account);
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
        }
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
        Plugin serverPassword = Bukkit.getPluginManager().getPlugin("ServerPassword");
        if (serverPassword == null || !serverPassword.isEnabled()) {
            return false;
        }
        try {
            Field authField = serverPassword.getClass().getDeclaredField("authManager");
            authField.setAccessible(true);
            Object authManager = authField.get(serverPassword);
            Method isAuthed = authManager.getClass().getMethod("isAuthed", Player.class);
            Object result = isAuthed.invoke(authManager, player);
            return result instanceof Boolean authed && !authed;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private void saveOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            saveActiveProfile(player);
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
