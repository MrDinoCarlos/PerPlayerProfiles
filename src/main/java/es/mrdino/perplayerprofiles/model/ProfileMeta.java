package es.mrdino.perplayerprofiles.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

public class ProfileMeta {
    private String name;
    private boolean master;
    private String passwordHash;
    private boolean passwordResetRequired;
    private String iconKey;
    private String customTexture;
    private String virtualUuid;
    private long maxPlayMillis;
    private long renewMillis;
    private long windowStartedAt;
    private long usedMillis;
    private long totalPlayedMillis;
    private boolean playtimeInitialized;
    private long lastPlayedAt;
    private long createdAt;
    private long lastConnectedAt;
    private long blocksMined;
    private long itemsCrafted;
    private long itemsUsed;
    private int deaths;
    private int mobKills;
    private int playerKills;
    private int level;

    public ProfileMeta(String name) {
        this.name = name;
        this.windowStartedAt = System.currentTimeMillis();
        this.createdAt = this.windowStartedAt;
    }

    public static ProfileMeta fromSection(String name, ConfigurationSection section) {
        ProfileMeta meta = new ProfileMeta(name);
        meta.master = section.getBoolean("master", false);
        meta.passwordHash = section.getString("password-hash", null);
        meta.passwordResetRequired = section.getBoolean("password-reset-required", false);
        meta.iconKey = section.getString("icon-key", null);
        meta.customTexture = section.getString("custom-texture", null);
        meta.virtualUuid = section.getString("virtual-uuid", null);
        meta.maxPlayMillis = section.getLong("time.max-play-millis", 0L);
        meta.renewMillis = section.getLong("time.renew-millis", 0L);
        meta.windowStartedAt = section.getLong("time.window-started-at", System.currentTimeMillis());
        meta.usedMillis = section.getLong("time.used-millis", 0L);
        meta.totalPlayedMillis = Math.max(0L, section.getLong("stats.total-played-millis", 0L));
        meta.playtimeInitialized = section.contains("stats.total-played-millis");
        meta.lastPlayedAt = section.getLong("stats.last-played-at", 0L);
        meta.createdAt = section.getLong("created-at", 0L);
        meta.lastConnectedAt = section.getLong("stats.last-connected-at", meta.lastPlayedAt);
        meta.blocksMined = section.getLong("stats.blocks-mined", 0L);
        meta.itemsCrafted = section.getLong("stats.items-crafted", 0L);
        meta.itemsUsed = section.getLong("stats.items-used", 0L);
        meta.deaths = section.getInt("stats.deaths", 0);
        meta.mobKills = section.getInt("stats.mob-kills", 0);
        meta.playerKills = section.getInt("stats.player-kills", 0);
        meta.level = section.getInt("stats.level", 0);
        meta.refreshWindow(System.currentTimeMillis());
        return meta;
    }

    public void saveTo(ConfigurationSection section) {
        section.set("master", master);
        section.set("password-hash", passwordHash);
        section.set("password-reset-required", passwordResetRequired);
        section.set("icon-key", iconKey);
        section.set("custom-texture", customTexture);
        section.set("virtual-uuid", virtualUuid);
        section.set("time.max-play-millis", maxPlayMillis);
        section.set("time.renew-millis", renewMillis);
        section.set("time.window-started-at", windowStartedAt);
        section.set("time.used-millis", usedMillis);
        if (playtimeInitialized) section.set("stats.total-played-millis", totalPlayedMillis);
        section.set("stats.last-played-at", lastPlayedAt);
        section.set("created-at", createdAt);
        section.set("stats.last-connected-at", lastConnectedAt);
        section.set("stats.blocks-mined", blocksMined);
        section.set("stats.items-crafted", itemsCrafted);
        section.set("stats.items-used", itemsUsed);
        section.set("stats.deaths", deaths);
        section.set("stats.mob-kills", mobKills);
        section.set("stats.player-kills", playerKills);
        section.set("stats.level", level);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isMaster() {
        return master;
    }

    public void setMaster(boolean master) {
        this.master = master;
    }

    public boolean hasPassword() {
        return passwordResetRequired || passwordHash != null && !passwordHash.isBlank();
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordResetRequired = false;
    }

    public boolean isPasswordResetRequired() { return passwordResetRequired; }
    public void requireNewPassword() { passwordHash = null; passwordResetRequired = true; }

    public String getIconKey() {
        return iconKey;
    }

    public void setIconKey(String iconKey) {
        this.iconKey = iconKey;
    }

    public String getCustomTexture() { return customTexture; }
    public void setCustomTexture(String customTexture) { this.customTexture = customTexture; }
    public long getTotalPlayedMillis() { return totalPlayedMillis; }
    public long getLastPlayedAt() { return lastPlayedAt; }
    public long getCreatedAt() { return createdAt; }
    public long getLastConnectedAt() { return lastConnectedAt; }
    public long getBlocksMined() { return blocksMined; }
    public long getItemsCrafted() { return itemsCrafted; }
    public long getItemsUsed() { return itemsUsed; }
    public void recordConnection(long now) { lastConnectedAt = now; }
    public void updateTypedStatistics(ConfigurationSection typed) {
        blocksMined = sum(typed, "MINE_BLOCK");
        itemsCrafted = sum(typed, "CRAFT_ITEM");
        itemsUsed = sum(typed, "USE_ITEM");
    }
    private static long sum(ConfigurationSection typed, String key) {
        if (typed == null) return 0L;
        ConfigurationSection values = typed.getConfigurationSection(key);
        return values == null ? 0L : values.getKeys(false).stream().mapToLong(name -> Math.max(0, values.getLong(name))).sum();
    }
    public boolean hasPlaytimeStatistics() { return playtimeInitialized; }
    public int getDeaths() { return deaths; }
    public int getMobKills() { return mobKills; }
    public int getPlayerKills() { return playerKills; }
    public int getLevel() { return level; }

    public void initializePlaytime(long millis) {
        if (!playtimeInitialized) {
            totalPlayedMillis = Math.max(0L, millis);
            playtimeInitialized = true;
        }
    }

    public void initializeStatistics(long millis, int level, int deaths, int mobKills, int playerKills) {
        if (playtimeInitialized) return;
        initializePlaytime(millis);
        this.level = level; this.deaths = deaths; this.mobKills = mobKills; this.playerKills = playerKills;
    }

    public void recordSession(long millis, int level, int deaths, int mobKills, int playerKills) {
        initializePlaytime(0L);
        totalPlayedMillis += Math.max(0L, millis);
        lastPlayedAt = System.currentTimeMillis();
        this.level = level;
        this.deaths = deaths;
        this.mobKills = mobKills;
        this.playerKills = playerKills;
    }

    public UUID getVirtualUuid() {
        try {
            return virtualUuid == null || virtualUuid.isBlank() ? null : UUID.fromString(virtualUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public UUID ensureVirtualUuid(UUID accountUuid) {
        UUID uuid = getVirtualUuid();
        if (uuid == null) {
            uuid = UUID.nameUUIDFromBytes(("perplayerprofiles:" + accountUuid + ":" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            virtualUuid = uuid.toString();
        }
        return uuid;
    }

    public long getMaxPlayMillis() {
        return maxPlayMillis;
    }

    public long getRenewMillis() {
        return renewMillis;
    }

    public long getUsedMillis() {
        return usedMillis;
    }

    public void setTimeLimit(long maxPlayMillis, long renewMillis) {
        this.maxPlayMillis = Math.max(0L, maxPlayMillis);
        this.renewMillis = Math.max(0L, renewMillis);
        this.windowStartedAt = System.currentTimeMillis();
        this.usedMillis = 0L;
    }

    public void resetTimeUsage() {
        this.windowStartedAt = System.currentTimeMillis();
        this.usedMillis = 0L;
    }

    public void addUsedMillis(long millis) {
        refreshWindow(System.currentTimeMillis());
        usedMillis += Math.max(0L, millis);
    }

    public boolean isTimeLimited() {
        return !master && maxPlayMillis > 0L && renewMillis > 0L;
    }

    public boolean isTimeExceeded(long now) {
        refreshWindow(now);
        return isTimeLimited() && usedMillis >= maxPlayMillis;
    }

    public long getRemainingMillis(long now) {
        refreshWindow(now);
        if (!isTimeLimited()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, maxPlayMillis - usedMillis);
    }

    /** Only charge the part of the unsaved session inside the current renewal window. */
    public long getRemainingMillis(long now, long sessionStartedAt) {
        long remaining = getRemainingMillis(now);
        if (!isTimeLimited()) return remaining;
        return Math.max(0L, remaining - Math.max(0L, now - Math.max(sessionStartedAt, windowStartedAt)));
    }

    public long getMillisUntilRenewal(long now) {
        refreshWindow(now);
        if (!isTimeLimited()) {
            return 0L;
        }
        return Math.max(0L, renewMillis - Math.max(0L, now - windowStartedAt));
    }

    private void refreshWindow(long now) {
        if (renewMillis <= 0L) {
            return;
        }
        if (windowStartedAt <= 0L) windowStartedAt = now;
        if (now - windowStartedAt >= renewMillis) {
            windowStartedAt += ((now - windowStartedAt) / renewMillis) * renewMillis;
            usedMillis = 0L;
        }
    }

    public void chargeSession(long startedAt, long now) {
        refreshWindow(now);
        usedMillis += Math.max(0L, now - Math.max(startedAt, windowStartedAt));
    }
}
