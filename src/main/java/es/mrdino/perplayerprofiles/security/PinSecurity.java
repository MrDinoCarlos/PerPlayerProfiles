package es.mrdino.perplayerprofiles.security;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** Shared attempt budget for every PIN entry path, including commands. */
public final class PinSecurity {
    private final PerPlayerProfilesPlugin plugin;
    private final Map<UUID, Attempts> attempts = new HashMap<>();

    public PinSecurity(PerPlayerProfilesPlugin plugin) { this.plugin = plugin; }

    public boolean profile(Player player, ProfileMeta profile, String pin, boolean allowMaster) {
        if (profile.isPasswordResetRequired()) return false;
        if (!available(player)) return false;
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (check(pin, profile.getPasswordHash(), profile::setPasswordHash)
                || allowMaster && account.hasMasterPassword() && check(pin, account.getMasterPasswordHash(), account::setMasterPasswordHash)) {
            success(player);
            return true;
        }
        failed(player);
        return false;
    }

    public boolean master(Player player, String pin) {
        if (!available(player)) return false;
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (account.hasMasterPassword() && check(pin, account.getMasterPasswordHash(), account::setMasterPasswordHash)) {
            success(player);
            return true;
        }
        failed(player);
        return false;
    }

    private boolean check(String pin, String hash, Consumer<String> upgrade) {
        if (!PasswordHasher.verify(pin, hash)) return false;
        if (PasswordHasher.needsUpgrade(hash)) upgrade.accept(PasswordHasher.hash(pin));
        return true;
    }

    public boolean available(Player player) {
        Attempts state = attempts.get(player.getUniqueId());
        if (state == null || state.until <= System.currentTimeMillis()) return true;
        plugin.send(player, "pin-cooldown", "seconds", (state.until - System.currentTimeMillis() + 999L) / 1000L);
        return false;
    }

    public long cooldownSeconds(Player player) {
        Attempts state = attempts.get(player.getUniqueId());
        return state == null ? 0 : Math.max(0L, (state.until - System.currentTimeMillis() + 999L) / 1000L);
    }

    private void success(Player player) {
        attempts.remove(player.getUniqueId());
        plugin.saveAccount(player.getUniqueId());
    }

    private void failed(Player player) {
        Attempts state = attempts.computeIfAbsent(player.getUniqueId(), ignored -> new Attempts());
        state.lastAttemptAt = System.currentTimeMillis();
        if (state.until > 0 && state.until <= System.currentTimeMillis()) { state.count = 0; state.until = 0; }
        if (++state.count >= Math.max(1, plugin.getConfig().getInt("security.pin.max-attempts", 5))) {
            state.until = System.currentTimeMillis() + Math.max(1, plugin.getConfig().getLong("security.pin.cooldown-seconds", 60)) * 1000L;
            state.count = 0;
        }
    }

    public void clearExpired() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> entry.getValue().until > 0 && entry.getValue().until <= now
                || entry.getValue().until == 0 && now - entry.getValue().lastAttemptAt > 900_000L);
    }
    public void clear(UUID uuid) { attempts.remove(uuid); }
    private static final class Attempts { private int count; private long until; private long lastAttemptAt; }
}
