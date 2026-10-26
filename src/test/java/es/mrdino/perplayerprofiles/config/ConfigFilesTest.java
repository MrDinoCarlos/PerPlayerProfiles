package es.mrdino.perplayerprofiles.config;

import es.mrdino.perplayerprofiles.lang.LanguageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigFilesTest {
    @TempDir Path folder;

    @Test void missingKeysAreAddedWithoutReplacingFalseZeroListsUnknownKeysOrComments() throws Exception {
        var current = new YamlConfiguration();
        current.loadFromString("# My server\ngui:\n  mode: LEGACY\n  show-statistics: false\n  custom: keep\nlimit: 0\ntexts: []\n");
        var defaults = new YamlConfiguration();
        defaults.loadFromString("gui:\n  mode: AUTO\n  show-statistics: true\n  custom-heads:\n    allow-import: true\nlimit: 5\ntexts: [one]\nnew: value\n");
        assertTrue(ConfigFiles.mergeMissing(current, defaults));
        assertEquals("LEGACY", current.getString("gui.mode"));
        assertFalse(current.getBoolean("gui.show-statistics"));
        assertEquals(0, current.getInt("limit"));
        assertTrue(current.getStringList("texts").isEmpty());
        assertEquals("keep", current.getString("gui.custom"));
        assertTrue(current.getBoolean("gui.custom-heads.allow-import"));
        assertFalse(ConfigFiles.mergeMissing(current, defaults));
        Path file = folder.resolve("config.yml");
        ConfigFiles.save(file, current);
        assertTrue(Files.readString(file).contains("# My server"));
        assertEquals("value", ConfigFiles.load(file).getString("new"));
    }

    @Test void malformedUserYamlIsPreservedByteForByte() throws Exception {
        Path file = folder.resolve("config.yml");
        String broken = "gui: [unterminated\n";
        Files.writeString(file, broken);
        assertThrows(IOException.class, () -> ConfigFiles.load(file));
        assertEquals(broken, Files.readString(file));
    }

    @Test void allTranslationsUpgradeAndRetainCustomTextAndDeliberatelyEmptyLore() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        var config = new YamlConfiguration(); config.set("language", "es");
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getResource(anyString())).thenAnswer(call -> getClass().getClassLoader().getResourceAsStream(call.getArgument(0)));
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/es.yml"), "prefix: 'CUSTOM '\nprofile-not-found: 'MY TEXT'\ngui-create-lore: []\nmy-key: preserved\n");
        LanguageService service = new LanguageService(plugin);
        service.load(); service.load();
        assertEquals("MY TEXT", service.get("profile-not-found"));
        assertTrue(service.getStringList("gui-create-lore").isEmpty());
        assertTrue(service.get("screen-selector-title").contains("personaje"));
        assertEquals("preserved", ConfigFiles.load(folder.resolve("lang/es.yml")).getString("my-key"));
        var englishKeys = ConfigFiles.load(folder.resolve("lang/en.yml")).getKeys(true);
        for (String language : new String[]{"es", "de", "fr"}) {
            assertTrue(ConfigFiles.load(folder.resolve("lang/" + language + ".yml")).getKeys(true).containsAll(englishKeys), language);
        }
    }
}
