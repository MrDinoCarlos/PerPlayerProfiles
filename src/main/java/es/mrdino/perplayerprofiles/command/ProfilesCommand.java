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
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            handleAdmin(sender, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("perplayerprofiles.admin")) { plugin.getLanguageService().send(sender, "no-permission"); return true; }
            try { plugin.reloadPluginSettings(); plugin.getLanguageService().send(sender, "settings-reloaded"); }
            catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not reload plugin settings", failure);
                plugin.getLanguageService().send(sender, "settings-reload-failed");
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.getLanguageService().send(sender, "only-players");
            return true;
        }
        if (!plugin.isProfileReady(player)) { plugin.send(player, "waiting-for-auth"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            plugin.getProfileGuiManager().openMain(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        if (plugin.getProfileGuiManager().isSelectionRequired(player)
                && !List.of("use", "switch", "list", "mode", "language", "lang", "settings", "info", "help").contains(subCommand)) {
            plugin.send(player, "screen-selection-required");
            plugin.getProfileGuiManager().openMain(player);
            return true;
        }
        switch (subCommand) {
            case "list" -> listProfiles(player);
            case "create" -> createProfile(player, args);
            case "use", "switch" -> useProfile(player, args);
            case "delete", "remove" -> deleteProfile(player, args);
            case "lock" -> lockProfile(player, args);
            case "unlock" -> unlockProfile(player, args);
            case "setmasterpassword" -> setMasterPassword(player, args);
            case "setlimit" -> setLimit(player, args);
            case "settings", "info" -> plugin.getProfileGuiManager().openProfileSettings(player, args.length > 1 ? args[1] : plugin.getActiveProfile(player));
            case "icon", "icons" -> plugin.getProfileGuiManager().editIcon(player, args.length > 1 ? args[1] : plugin.getActiveProfile(player));
            case "language", "lang" -> {
                if (args.length == 1) plugin.getProfileGuiManager().openLanguagePicker(player);
                else plugin.getProfileGuiManager().setLanguage(player, args[1]);
            }
            case "mode" -> {
                if (args.length == 2 && List.of("auto", "modern", "legacy").contains(args[1].toLowerCase(Locale.ROOT)))
                    plugin.getProfileGuiManager().setMenuMode(player, args[1]);
                else plugin.send(player, "usage-mode");
            }
            default -> sendHelp(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender instanceof Player) options.addAll(List.of("gui", "help", "list", "create", "use", "switch", "delete", "remove", "lock", "unlock", "setmasterpassword", "setlimit", "settings", "info", "icon", "icons", "mode", "language", "lang"));
            if (sender.hasPermission("perplayerprofiles.admin")) options.addAll(List.of("reload", "admin"));
            return filter(options, args[0]);
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("perplayerprofiles.admin")) return List.of();
            if (args.length == 2) return filter(List.of("resetpin", "removepin"), args[1]);
            if (!List.of("resetpin", "removepin").contains(args[1].toLowerCase(Locale.ROOT))) return List.of();
            if (args.length == 3) return filter(plugin.knownPlayerAccounts().values().stream().distinct().sorted().toList(), args[2]);
            if (args.length == 4) {
                UUID owner = plugin.resolveKnownAccount(args[2]);
                return owner == null ? List.of() : filter(plugin.loadAccount(owner).getProfiles().values().stream().map(ProfileMeta::getName).toList(), args[3]);
            }
            return List.of();
        }
        if (!(sender instanceof Player player)) return List.of();
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && subcommand.equals("mode")) return filter(List.of("auto", "modern", "legacy"), args[1]);
        if (args.length == 2 && List.of("language", "lang").contains(subcommand)) return filter(es.mrdino.perplayerprofiles.lang.LanguageService.languageOptions(), args[1]);
        if (args.length == 2 && List.of("use", "switch", "delete", "remove", "lock", "unlock", "setlimit", "settings", "info", "icon", "icons").contains(subcommand)) {
            return filter(plugin.loadAccount(player.getUniqueId()).getProfiles().values().stream().map(ProfileMeta::getName).toList(), args[1]);
        }
        if (subcommand.equals("setlimit") && args.length == 3) return filter(List.of("0", "15", "30", "60", "90", "120", "180"), args[2]);
        if (subcommand.equals("setlimit") && args.length == 4) return filter(List.of("0", "1", "6", "12", "24", "48", "168"), args[3]);
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

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("perplayerprofiles.admin")) { plugin.getLanguageService().send(sender, "no-permission"); return; }
        if (sender instanceof Player player && !plugin.isProfileReady(player)) { plugin.send(player, "waiting-for-auth"); return; }
        if (args.length != 4 || !List.of("resetpin", "removepin").contains(args[1].toLowerCase(Locale.ROOT))) {
            plugin.getLanguageService().send(sender, "usage-admin-pin"); return;
        }
        UUID owner = plugin.resolveKnownAccount(args[2]);
        if (owner == null) { plugin.getLanguageService().send(sender, "admin-account-not-found"); return; }
        try { new es.mrdino.perplayerprofiles.security.ProfileRecovery(plugin).recover(sender, owner, args[3], args[1].equalsIgnoreCase("resetpin")); }
        catch (RuntimeException failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not save administrative PIN recovery", failure);
            plugin.getLanguageService().send(sender, "admin-pin-failed");
        }
    }

    private void createProfile(Player player, String[] args) {
        if (args.length < 2) {
            plugin.getProfileGuiManager().createFromMenu(player);
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!plugin.isMasterProfile(player, plugin.getActiveProfile(player))) { plugin.send(player, "master-profile-required"); return; }
        if (plugin.isMasterPinRequiredToCreateProfiles() && !canUseMasterAction(player, account, args, args.length >= 3 ? args.length - 1 : -1)) {
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
        if (args.length >= 3 && (!plugin.isMasterPinRequiredToCreateProfiles() || !account.hasMasterPassword() || args.length >= 4)) {
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
        if (profile.isPasswordResetRequired() || profile.hasPassword() && args.length < 3) { plugin.getProfileGuiManager().selectProfile(player, profile.getName()); return; }
        if (profile.hasPassword() && !plugin.getPinSecurity().profile(player, profile, args[2], true)) {
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
        if (!account.hasMasterPassword() && profile.hasPassword() && !plugin.getPinSecurity().profile(player, profile, args[2], false)) {
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
            if (maxMinutes < 0 || renewHours < 0) throw new NumberFormatException();
            plugin.saveActiveProfile(player);
            profile.setTimeLimit(Math.multiplyExact(maxMinutes, 60_000L), Math.multiplyExact(renewHours, 3_600_000L));
            plugin.saveAccount(player.getUniqueId());
            plugin.send(player, "limit-applied", "profile", profile.getName(), "minutes", maxMinutes, "hours", renewHours);
        } catch (NumberFormatException | ArithmeticException e) {
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
        if (passwordIndex < 0 || args.length <= passwordIndex || !plugin.getPinSecurity().master(player, args[passwordIndex])) {
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
        plugin.send(player, "help-settings");
        plugin.send(player, "help-icon");
        plugin.send(player, "help-mode");
        plugin.send(player, "help-language");
        if (player.hasPermission("perplayerprofiles.admin")) { plugin.send(player, "help-reload"); plugin.send(player, "help-admin-pin"); }
    }

    private List<String> filter(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
