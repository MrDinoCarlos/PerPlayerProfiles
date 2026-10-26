package es.mrdino.perplayerprofiles.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class AccountData {
    private String masterPasswordHash;
    private String lastActiveProfileName;
    private String menuMode = "AUTO";
    private String preferredLanguage = "auto";
    private String playerName;
    private final Map<String, ProfileMeta> profiles = new LinkedHashMap<>();

    public String getMasterPasswordHash() {
        return masterPasswordHash;
    }

    public String getMenuMode() { return menuMode; }
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String name) { playerName = name; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String language) {
        preferredLanguage = language == null ? "auto" : language.toLowerCase(java.util.Locale.ROOT);
    }
    public void setMenuMode(String mode) { menuMode = mode == null ? "AUTO" : mode.toUpperCase(java.util.Locale.ROOT); }

    public void setMasterPasswordHash(String masterPasswordHash) {
        this.masterPasswordHash = masterPasswordHash;
    }

    public boolean hasMasterPassword() {
        return masterPasswordHash != null && !masterPasswordHash.isBlank();
    }

    public String getLastActiveProfileName() {
        return lastActiveProfileName;
    }

    public void setLastActiveProfileName(String lastActiveProfileName) {
        String normalizedName = normalizeName(lastActiveProfileName);
        this.lastActiveProfileName = normalizedName.isBlank() ? null : normalizedName;
    }

    public Map<String, ProfileMeta> getProfiles() {
        return profiles;
    }

    public ProfileMeta getProfile(String name) {
        return profiles.get(normalizeName(name));
    }

    public void putProfile(ProfileMeta profile) {
        profiles.put(normalizeName(profile.getName()), profile);
    }

    public boolean hasProfile(String name) {
        return profiles.containsKey(normalizeName(name));
    }

    public ProfileMeta removeProfile(String name) {
        String normalizedName = normalizeName(name);
        ProfileMeta removed = profiles.remove(normalizedName);
        if (normalizedName.equals(lastActiveProfileName)) {
            lastActiveProfileName = null;
        }
        return removed;
    }

    public ProfileMeta getMasterProfile(String fallbackName) {
        for (ProfileMeta profile : profiles.values()) {
            if (profile.isMaster()) {
                return profile;
            }
        }
        return getProfile(fallbackName);
    }

    public String getMasterProfileName(String fallbackName) {
        ProfileMeta master = getMasterProfile(fallbackName);
        return master == null ? normalizeName(fallbackName) : normalizeName(master.getName());
    }

    public boolean isMasterProfile(String name, String fallbackName) {
        return getMasterProfileName(fallbackName).equals(normalizeName(name));
    }

    public void markOnlyMaster(String name) {
        String normalizedName = normalizeName(name);
        for (ProfileMeta profile : profiles.values()) {
            profile.setMaster(normalizeName(profile.getName()).equals(normalizedName));
        }
    }

    public void sortMasterFirst(String fallbackName) {
        ProfileMeta master = getMasterProfile(fallbackName);
        if (master == null || profiles.size() <= 1) {
            return;
        }
        LinkedHashMap<String, ProfileMeta> sorted = new LinkedHashMap<>();
        sorted.put(normalizeName(master.getName()), master);
        for (ProfileMeta profile : profiles.values()) {
            String key = normalizeName(profile.getName());
            if (!sorted.containsKey(key)) {
                sorted.put(key, profile);
            }
        }
        profiles.clear();
        profiles.putAll(sorted);
    }

    public static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).trim();
    }
}
