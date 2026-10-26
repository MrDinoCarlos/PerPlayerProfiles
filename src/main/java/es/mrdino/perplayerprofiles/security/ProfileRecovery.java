package es.mrdino.perplayerprofiles.security;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Administrative recovery never needs to reveal or accept somebody else's PIN. */
public final class ProfileRecovery {
    private final PerPlayerProfilesPlugin plugin;
    public ProfileRecovery(PerPlayerProfilesPlugin plugin) { this.plugin = plugin; }

    public boolean recover(CommandSender sender, UUID owner, String profileName, boolean requireNewPin) {
        if (!sender.hasPermission("perplayerprofiles.admin")) {
            plugin.getLanguageService().send(sender, "no-permission");
            return false;
        }
        AccountData account = plugin.loadAccount(owner);
        ProfileMeta profile = account.getProfile(profileName);
        if (profile == null) {
            plugin.getLanguageService().send(sender, "profile-not-found");
            return false;
        }
        if (!profile.hasPassword()) {
            plugin.getLanguageService().send(sender, "admin-profile-no-pin", "profile", profile.getName());
            return false;
        }
        String oldHash = profile.getPasswordHash();
        boolean oldReset = profile.isPasswordResetRequired();
        if (requireNewPin) profile.requireNewPassword(); else profile.setPasswordHash(null);
        try { plugin.saveAccount(owner); }
        catch (RuntimeException failure) {
            profile.setPasswordHash(oldHash);
            if (oldReset) profile.requireNewPassword();
            throw failure;
        }
        plugin.getPinSecurity().clear(owner);
        plugin.getLogger().info("Profile PIN " + (requireNewPin ? "reset" : "removed") + " by " + sender.getName()
                + " for account " + owner + ", profile " + profile.getName());
        plugin.getLanguageService().send(sender, requireNewPin ? "admin-pin-reset" : "admin-pin-removed",
                "player", account.getPlayerName() == null ? owner : account.getPlayerName(), "profile", profile.getName());
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            plugin.send(player, requireNewPin ? "profile-pin-reset-notice" : "profile-pin-removed-notice", "profile", profile.getName());
            plugin.getProfileGuiManager().onPasswordRecovered(player, profile.getName(), requireNewPin);
        }
        return true;
    }
}
