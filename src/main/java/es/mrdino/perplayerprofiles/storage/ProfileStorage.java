package es.mrdino.perplayerprofiles.storage;

import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import es.mrdino.perplayerprofiles.config.ConfigFiles;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class ProfileStorage {
    private final File accountsFolder;
    private final java.util.Map<UUID, String> knownNames = new java.util.HashMap<>();
    private boolean namesIndexed;

    public ProfileStorage(JavaPlugin plugin) {
        this.accountsFolder = new File(plugin.getDataFolder(), "accounts");
        if (!accountsFolder.exists() && !accountsFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create accounts folder.");
        }
    }

    public AccountData loadAccount(UUID uuid) {
        File file = accountFile(uuid);
        YamlConfiguration yaml = readYaml(file);
        AccountData account = new AccountData();
        account.setPlayerName(yaml.getString("player-name"));
        account.setMasterPasswordHash(yaml.getString("master-password-hash", null));
        account.setLastActiveProfileName(yaml.getString("last-active-profile", null));
        account.setMenuMode(yaml.getString("menu-mode", "AUTO"));
        account.setPreferredLanguage(yaml.getString("language", "auto"));

        ConfigurationSection profiles = yaml.getConfigurationSection("profiles");
        if (profiles != null) {
            for (String profileName : profiles.getKeys(false)) {
                ConfigurationSection section = profiles.getConfigurationSection(profileName);
                if (section != null) {
                    ProfileMeta profile = ProfileMeta.fromSection(profileName, section);
                    if (!profile.hasPlaytimeStatistics()) {
                        YamlConfiguration state = loadProfileState(uuid, profileName);
                        if (state.contains("statistics")) profile.initializeStatistics(state.getLong("statistics.PLAY_ONE_MINUTE") * 50L,
                                state.getInt("level"), state.getInt("statistics.DEATHS"), state.getInt("statistics.MOB_KILLS"), state.getInt("statistics.PLAYER_KILLS"));
                    }
                    account.putProfile(profile);
                }
            }
        }
        account.sortMasterFirst("master");
        return account;
    }

    public void saveAccount(UUID uuid, AccountData account) {
        File file = accountFile(uuid);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("player-name", account.getPlayerName());
        if (account.getPlayerName() != null) knownNames.put(uuid, account.getPlayerName());
        yaml.set("master-password-hash", account.getMasterPasswordHash());
        yaml.set("last-active-profile", account.getLastActiveProfileName());
        yaml.set("menu-mode", account.getMenuMode());
        yaml.set("language", account.getPreferredLanguage());
        for (ProfileMeta profile : account.getProfiles().values()) {
            ConfigurationSection section = yaml.createSection("profiles." + profile.getName());
            profile.saveTo(section);
        }
        saveYaml(file, yaml);
    }

    public boolean hasAccount(UUID uuid) { return accountFile(uuid).isFile(); }

    public java.util.Map<UUID, String> knownPlayerNames() {
        if (!namesIndexed) {
            File[] files = accountsFolder.listFiles((directory, name) -> name.endsWith(".yml"));
            if (files != null) for (File file : files) {
                UUID uuid;
                try { uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4)); }
                catch (IllegalArgumentException invalid) { continue; }
                String name = readYaml(file).getString("player-name");
                if (name != null) knownNames.put(uuid, name);
            }
            namesIndexed = true;
        }
        return java.util.Map.copyOf(knownNames);
    }

    public YamlConfiguration loadProfileState(UUID uuid, String profileName) {
        return readYaml(profileFile(uuid, profileName));
    }

    public void saveProfileState(UUID uuid, String profileName, YamlConfiguration yaml) {
        saveYaml(profileFile(uuid, profileName), yaml);
    }

    public void deleteProfileState(UUID uuid, String profileName) {
        File file = profileFile(uuid, profileName);
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Could not delete profile state: " + file.getAbsolutePath());
        }
    }

    public void renameProfileState(UUID uuid, String oldProfileName, String newProfileName) {
        File oldFile = profileFile(uuid, oldProfileName);
        File newFile = profileFile(uuid, newProfileName);
        if (oldFile.equals(newFile)) {
            return;
        }
        if (!oldFile.exists()) {
            return;
        }
        if (newFile.exists()) {
            throw new IllegalStateException("Could not rename profile state because target exists: " + newFile.getAbsolutePath());
        }
        if (!oldFile.renameTo(newFile)) {
            throw new IllegalStateException("Could not rename profile state: " + oldFile.getAbsolutePath());
        }
    }

    private File accountFile(UUID uuid) {
        return new File(accountsFolder, uuid + ".yml");
    }

    private File profileFile(UUID uuid, String profileName) {
        File profilesFolder = new File(accountsFolder, uuid.toString());
        if (!profilesFolder.exists() && !profilesFolder.mkdirs()) {
            throw new IllegalStateException("Could not create profile folder: " + profilesFolder.getAbsolutePath());
        }
        return new File(profilesFolder, AccountData.normalizeName(profileName) + ".yml");
    }

    private void saveYaml(File file, YamlConfiguration yaml) {
        try {
            ConfigFiles.save(file.toPath(), yaml);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file.getAbsolutePath(), e);
        }
    }

    private YamlConfiguration readYaml(File file) {
        try { return ConfigFiles.load(file.toPath()); }
        catch (IOException failure) { throw new IllegalStateException("Could not safely read " + file.getAbsolutePath(), failure); }
    }
}
