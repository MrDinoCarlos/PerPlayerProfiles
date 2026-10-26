package es.mrdino.perplayerprofiles.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Merges only missing keys; malformed user files are never overwritten. */
public final class ConfigFiles {
    private ConfigFiles() { }

    public static YamlConfiguration load(Path path) throws IOException {
        YamlConfiguration result = new YamlConfiguration();
        if (Files.exists(path)) {
            try { result.load(path.toFile()); }
            catch (InvalidConfigurationException invalid) { throw new IOException("Invalid YAML: " + path, invalid); }
        }
        return result;
    }

    public static YamlConfiguration update(JavaPlugin plugin, String resource) throws IOException {
        Path destination = plugin.getDataFolder().toPath().resolve(resource);
        YamlConfiguration current = load(destination);
        try (var stream = plugin.getResource(resource)) {
            if (stream == null) throw new IOException("Missing resource: " + resource);
            YamlConfiguration defaults = new YamlConfiguration();
            try { defaults.load(new InputStreamReader(stream, StandardCharsets.UTF_8)); }
            catch (InvalidConfigurationException invalid) { throw new IOException("Invalid bundled YAML: " + resource, invalid); }
            if (mergeMissing(current, defaults) || !Files.exists(destination)) save(destination, current);
        }
        return current;
    }

    public static boolean mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (!target.contains(key, true)) {
                if (defaults.isConfigurationSection(key)) target.createSection(key);
                else target.set(key, defaults.get(key));
                target.setComments(key, defaults.getComments(key));
                target.setInlineComments(key, defaults.getInlineComments(key));
                changed = true;
            }
            if (target.isConfigurationSection(key) && defaults.isConfigurationSection(key))
                changed |= mergeMissing(target.getConfigurationSection(key), defaults.getConfigurationSection(key));
            if (target.getComments(key).isEmpty() && !defaults.getComments(key).isEmpty()) {
                target.setComments(key, defaults.getComments(key));
                changed = true;
            }
        }
        return changed;
    }

    public static void save(Path destination, YamlConfiguration yaml) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), destination.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
