package es.mrdino.perplayerprofiles.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

public class ProfileMeta {
    private String name;
    private boolean master;
    private String passwordHash;
    private String iconKey;
    private String virtualUuid;
    private long maxPlayMillis;
    private long renewMillis;
    private long windowStartedAt;
    private long usedMillis;

    public ProfileMeta(String name) {
        this.name = name;
        this.windowStartedAt = System.currentTimeMillis();
    }

    public static ProfileMeta fromSection(String name, ConfigurationSection section) {
        ProfileMeta meta = new ProfileMeta(name);
        meta.master = section.getBoolean("master", false);
        meta.passwordHash = section.getString("password-hash", null);
        meta.iconKey = section.getString("icon-key", null);
        meta.virtualUuid = section.getString("virtual-uuid", null);
        meta.maxPlayMillis = section.getLong("time.max-play-millis", 0L);
        meta.renewMillis = section.getLong("time.renew-millis", 0L);
        meta.windowStartedAt = section.getLong("time.window-started-at", System.currentTimeMillis());
        meta.usedMillis = section.getLong("time.used-millis", 0L);
        meta.refreshWindow(System.currentTimeMillis());
        return meta;
    }

    public void saveTo(ConfigurationSection section) {
        section.set("master", master);
        section.set("password-hash", passwordHash);
        section.set("icon-key", iconKey);
        section.set("virtual-uuid", virtualUuid);
        section.set("time.max-play-millis", maxPlayMillis);
        section.set("time.renew-millis", renewMillis);
        section.set("time.window-started-at", windowStartedAt);
        section.set("time.used-millis", usedMillis);
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
        return passwordHash != null && !passwordHash.isBlank();
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getIconKey() {
        return iconKey;
    }

    public void setIconKey(String iconKey) {
        this.iconKey = iconKey;
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
        return maxPlayMillis > 0L && renewMillis > 0L;
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
        if (windowStartedAt <= 0L || now - windowStartedAt >= renewMillis) {
            windowStartedAt = now;
            usedMillis = 0L;
        }
    }
}
