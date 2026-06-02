package es.mrdino.perplayerprofiles.lang;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class LanguageService {
    private static final String DEFAULT_LANGUAGE = "en";
    private static final Map<String, String> SUPPORTED_LANGUAGES = Map.of(
            "en", "lang/en.yml",
            "es", "lang/es.yml",
            "de", "lang/de.yml",
            "fr", "lang/fr.yml"
    );

    private final JavaPlugin plugin;
    private YamlConfiguration messages;
    private YamlConfiguration fallback;

    public LanguageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        for (String resource : SUPPORTED_LANGUAGES.values()) {
            plugin.saveResource(resource, true);
        }

        fallback = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), SUPPORTED_LANGUAGES.get(DEFAULT_LANGUAGE)));
        String configured = plugin.getConfig().getString("language", DEFAULT_LANGUAGE).toLowerCase(Locale.ROOT);
        String resource = SUPPORTED_LANGUAGES.getOrDefault(configured, SUPPORTED_LANGUAGES.get(DEFAULT_LANGUAGE));
        if (!SUPPORTED_LANGUAGES.containsKey(configured)) {
            plugin.getLogger().warning("Unsupported language '" + configured + "'. Falling back to English.");
        }
        messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), resource));
    }

    public void send(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(color(getPrefix() + get(key, replacements)));
    }

    public String get(String key, Object... replacements) {
        String value = messages.getString(key);
        if (value == null) {
            value = fallback.getString(key, key);
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        return value;
    }

    public List<String> getStringList(String key, Object... replacements) {
        List<String> values = messages.getStringList(key);
        if (values.isEmpty()) {
            values = fallback.getStringList(key);
        }
        List<String> replaced = new ArrayList<>();
        for (String value : values) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                value = value.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
            }
            replaced.add(value);
        }
        return replaced;
    }

    private String getPrefix() {
        return messages.getString("prefix", fallback.getString("prefix", "&8[&aProfiles&8] &7"));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
