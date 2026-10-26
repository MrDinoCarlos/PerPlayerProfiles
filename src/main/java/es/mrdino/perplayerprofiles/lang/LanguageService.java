package es.mrdino.perplayerprofiles.lang;

import es.mrdino.perplayerprofiles.config.ConfigFiles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
    private Map<String, YamlConfiguration> languages = Map.of();
    private String defaultLanguage = DEFAULT_LANGUAGE;
    private Function<Player, String> preferences = player -> "auto";

    public LanguageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        Map<String, YamlConfiguration> loaded = new LinkedHashMap<>();
        for (var entry : SUPPORTED_LANGUAGES.entrySet()) {
            try { loaded.put(entry.getKey(), ConfigFiles.update(plugin, entry.getValue())); }
            catch (IOException failure) { throw new IllegalStateException("Could not safely update " + entry.getValue(), failure); }
        }
        String configured = plugin.getConfig().getString("language", DEFAULT_LANGUAGE).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.containsKey(configured)) {
            plugin.getLogger().warning("Unsupported language '" + configured + "'. Falling back to English.");
        }
        languages = Map.copyOf(loaded);
        defaultLanguage = SUPPORTED_LANGUAGES.containsKey(configured) ? configured : DEFAULT_LANGUAGE;
        fallback = languages.get(DEFAULT_LANGUAGE);
        messages = languages.get(defaultLanguage);
    }

    public void send(CommandSender sender, String key, Object... replacements) {
        Player player = sender instanceof Player value ? value : null;
        YamlConfiguration selected = selected(player);
        sender.sendMessage(color(selected.getString("prefix", fallback.getString("prefix", "")) + get(player, key, replacements)));
    }

    public String get(String key, Object... replacements) {
        return get(null, key, replacements);
    }

    public String get(Player player, String key, Object... replacements) {
        String value = selected(player).getString(key);
        if (value == null) {
            value = fallback.getString(key, key);
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        return value;
    }

    public List<String> getStringList(String key, Object... replacements) {
        return getStringList(null, key, replacements);
    }

    public List<String> getStringList(Player player, String key, Object... replacements) {
        YamlConfiguration selected = selected(player);
        List<String> values = selected.getStringList(key);
        if (!selected.contains(key, true)) {
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

    public void setPreferenceResolver(Function<Player, String> preferences) { this.preferences = preferences; }

    public String languageOf(Player player) {
        if (player == null) return defaultLanguage;
        String preference = preferences.apply(player);
        if (preference != null && SUPPORTED_LANGUAGES.containsKey(preference.toLowerCase(Locale.ROOT))) return preference.toLowerCase(Locale.ROOT);
        String locale = player.getLocale();
        String code = locale == null ? "" : locale.toLowerCase(Locale.ROOT).split("[_-]", 2)[0];
        return SUPPORTED_LANGUAGES.containsKey(code) ? code : defaultLanguage;
    }

    private YamlConfiguration selected(Player player) {
        return player == null ? messages : languages.getOrDefault(languageOf(player), messages);
    }

    public static List<String> languageOptions() { return List.of("auto", "en", "es", "de", "fr"); }
    public static String languageName(String code) {
        return switch (code) { case "en" -> "English"; case "es" -> "Español"; case "de" -> "Deutsch"; case "fr" -> "Français"; default -> "Auto"; };
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
