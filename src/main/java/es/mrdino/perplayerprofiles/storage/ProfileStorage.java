package es.mrdino.perplayerprofiles.storage;

import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class ProfileStorage {
    private final File accountsFolder;

    public ProfileStorage(JavaPlugin plugin) {
        this.accountsFolder = new File(plugin.getDataFolder(), "accounts");
        if (!accountsFolder.exists() && !accountsFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create accounts folder.");
        }
    }

    public AccountData loadAccount(UUID uuid) {
        File file = accountFile(uuid);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        AccountData account = new AccountData();
        account.setMasterPasswordHash(yaml.getString("master-password-hash", null));
        account.setLastActiveProfileName(yaml.getString("last-active-profile", null));

        ConfigurationSection profiles = yaml.getConfigurationSection("profiles");
        if (profiles != null) {
            for (String profileName : profiles.getKeys(false)) {
                ConfigurationSection section = profiles.getConfigurationSection(profileName);
                if (section != null) {
                    account.putProfile(ProfileMeta.fromSection(profileName, section));
                }
            }
        }
        account.sortMasterFirst("master");
        return account;
    }

    public void saveAccount(UUID uuid, AccountData account) {
        File file = accountFile(uuid);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("master-password-hash", account.getMasterPasswordHash());
        yaml.set("last-active-profile", account.getLastActiveProfileName());
        for (ProfileMeta profile : account.getProfiles().values()) {
            ConfigurationSection section = yaml.createSection("profiles." + profile.getName());
            profile.saveTo(section);
        }
        saveYaml(file, yaml);
    }

    public YamlConfiguration loadProfileState(UUID uuid, String profileName) {
        return YamlConfiguration.loadConfiguration(profileFile(uuid, profileName));
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
            yaml.save(file);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file.getAbsolutePath(), e);
        }
    }
}
