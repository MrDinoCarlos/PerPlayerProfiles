package es.mrdino.perplayerprofiles.command;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import es.mrdino.perplayerprofiles.security.PasswordHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class ProfilesCommand implements CommandExecutor, TabCompleter {
    private final PerPlayerProfilesPlugin plugin;

    public ProfilesCommand(PerPlayerProfilesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getLanguageService().send(sender, "only-players");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            plugin.getProfileGuiManager().openMain(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "list" -> listProfiles(player);
            case "create" -> createProfile(player, args);
            case "use", "switch" -> useProfile(player, args);
            case "delete", "remove" -> deleteProfile(player, args);
            case "lock" -> lockProfile(player, args);
            case "unlock" -> unlockProfile(player, args);
            case "setmasterpassword" -> setMasterPassword(player, args);
            case "setlimit" -> setLimit(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("gui", "help", "list", "create", "use", "delete", "lock", "unlock", "setmasterpassword", "setlimit"), args[0]);
        }
        if (args.length == 2 && List.of("use", "switch", "delete", "remove", "lock", "unlock", "setlimit").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(new ArrayList<>(plugin.loadAccount(player.getUniqueId()).getProfiles().keySet()), args[1]);
        }
        return List.of();
    }

    private void listProfiles(Player player) {
        AccountData account = plugin.loadAccount(player.getUniqueId());
        String active = plugin.getActiveProfile(player);
        String profiles = account.getProfiles().values().stream()
                .map(profile -> AccountData.normalizeName(profile.getName()).equals(active) ? "&a*" + profile.getName() : "&f" + profile.getName())
                .collect(Collectors.joining("&7, "));
        plugin.send(player, "profiles-list", "profiles", profiles);
    }

    private void createProfile(Player player, String[] args) {
        if (args.length < 2) {
            plugin.send(player, "usage-create");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!canUseMasterAction(player, account, args, account.hasMasterPassword() ? args.length - 1 : -1)) {
            return;
        }
        String name = normalizeProfileName(args[1]);
        if (name == null) {
            plugin.send(player, "invalid-profile-name");
            return;
        }
        if (account.hasProfile(name)) {
            plugin.send(player, "profile-exists");
            return;
        }
        if (account.getProfiles().size() >= plugin.getMaxProfilesPerAccount()) {
            plugin.send(player, "max-profiles", "max", plugin.getMaxProfilesPerAccount());
            return;
        }
        ProfileMeta profile = new ProfileMeta(name);
        profile.ensureVirtualUuid(player.getUniqueId());
        if ((!account.hasMasterPassword() && args.length >= 3) || (account.hasMasterPassword() && args.length >= 4)) {
            if (!validPassword(player, args[2])) {
                return;
            }
            profile.setPasswordHash(PasswordHasher.hash(args[2]));
        }
        account.putProfile(profile);
        account.sortMasterFirst(plugin.getMasterProfileName());
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-created", "profile", name);
    }

    private void useProfile(Player player, String[] args) {
        if (args.length < 2) {
            plugin.send(player, "usage-use");
            return;
        }
        String name = AccountData.normalizeName(args[1]);
        AccountData account = plugin.loadAccount(player.getUniqueId());
        ProfileMeta profile = account.getProfile(name);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        if (profile.hasPassword() && (args.length < 3 || !canUseProfilePin(account, profile, args[2]))) {
            plugin.send(player, "profile-password-wrong");
            return;
        }
        plugin.switchProfile(player, name);
    }

    private void deleteProfile(Player player, String[] args) {
        if (args.length < 2) {
            plugin.send(player, "usage-delete");
            return;
        }
        String name = AccountData.normalizeName(args[1]);
        if (plugin.isMasterProfile(player, name)) {
            plugin.send(player, "profile-master-delete");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!canUseMasterAction(player, account, args, 2)) {
            return;
        }
        ProfileMeta removed = account.removeProfile(name);
        if (removed == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        UUID uuid = player.getUniqueId();
        plugin.deleteProfileState(uuid, name);
        plugin.saveAccount(uuid);
        if (plugin.getActiveProfile(player).equals(name)) {
            plugin.switchProfile(player, plugin.getMasterProfileName(player));
        }
        plugin.send(player, "profile-deleted", "profile", name);
    }

    private void lockProfile(Player player, String[] args) {
        if (args.length < 3) {
            plugin.send(player, "usage-lock");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!canUseMasterAction(player, account, args, 3)) {
            return;
        }
        if (!validPassword(player, args[2])) {
            return;
        }
        ProfileMeta profile = account.getProfile(args[1]);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        profile.setPasswordHash(PasswordHasher.hash(args[2]));
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-locked", "profile", profile.getName());
    }

    private void unlockProfile(Player player, String[] args) {
        if (args.length < 3) {
            plugin.send(player, "usage-unlock");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!canUseMasterAction(player, account, args, 2)) {
            return;
        }
        ProfileMeta profile = account.getProfile(args[1]);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        if (!account.hasMasterPassword() && !PasswordHasher.verify(args[2], profile.getPasswordHash())) {
            plugin.send(player, "password-wrong");
            return;
        }
        profile.setPasswordHash(null);
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-unlocked", "profile", profile.getName());
    }

    private void setMasterPassword(Player player, String[] args) {
        if (args.length < 2) {
            plugin.send(player, "usage-master-password");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!canUseMasterAction(player, account, args, 2)) {
            return;
        }
        if (!validPassword(player, args[1])) {
            return;
        }
        account.setMasterPasswordHash(PasswordHasher.hash(args[1]));
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "master-password-updated");
    }

    private void setLimit(Player player, String[] args) {
        if (args.length < 4) {
            plugin.send(player, "usage-limit");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!canUseMasterAction(player, account, args, 4)) {
            return;
        }
        ProfileMeta profile = account.getProfile(args[1]);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        try {
            long maxMinutes = Long.parseLong(args[2]);
            long renewHours = Long.parseLong(args[3]);
            profile.setTimeLimit(maxMinutes * 60_000L, renewHours * 3_600_000L);
            plugin.saveAccount(player.getUniqueId());
            plugin.send(player, "limit-applied", "profile", profile.getName(), "minutes", maxMinutes, "hours", renewHours);
        } catch (NumberFormatException e) {
            plugin.send(player, "time-must-be-numbers");
        }
    }

    private boolean canUseMasterAction(Player player, AccountData account, String[] args, int passwordIndex) {
        if (!plugin.isMasterProfile(player, plugin.getActiveProfile(player))) {
            plugin.send(player, "master-profile-required");
            return false;
        }
        if (!account.hasMasterPassword()) {
            return true;
        }
        if (passwordIndex < 0 || args.length <= passwordIndex || !PasswordHasher.verify(args[passwordIndex], account.getMasterPasswordHash())) {
            plugin.send(player, "master-password-required");
            return false;
        }
        return true;
    }

    private boolean validPassword(Player player, String password) {
        if (!password.matches("\\d+")) {
            plugin.send(player, "pin-only-numbers");
            return false;
        }
        if (password.length() < plugin.getMinPinLength()) {
            plugin.send(player, "password-too-short", "min", plugin.getMinPinLength());
            return false;
        }
        if (password.length() > plugin.getMaxPinLength()) {
            plugin.send(player, "pin-too-long", "max", plugin.getMaxPinLength());
            return false;
        }
        return true;
    }

    private boolean canUseProfilePin(AccountData account, ProfileMeta profile, String pin) {
        return PasswordHasher.verify(pin, profile.getPasswordHash())
                || (account.hasMasterPassword() && PasswordHasher.verify(pin, account.getMasterPasswordHash()));
    }

    private String normalizeProfileName(String input) {
        String name = input == null ? "" : input.trim();
        if (name.length() < 2 || name.length() > 16 || !name.matches("[A-Za-z0-9_]+")) {
            return null;
        }
        return name;
    }

    private void sendHelp(Player player) {
        plugin.send(player, "help-list");
        plugin.send(player, "help-create");
        plugin.send(player, "help-use");
        plugin.send(player, "help-delete");
        plugin.send(player, "help-lock");
        plugin.send(player, "help-master-password");
        plugin.send(player, "help-limit");
    }

    private List<String> filter(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
