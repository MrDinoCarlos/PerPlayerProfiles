package es.mrdino.perplayerprofiles.lang;

import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.storage.ProfileStorage;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LanguageServiceTest {
    @TempDir Path folder;
    @Test void simultaneousPlayersUseTheirOwnLanguageAndAutoUsesClientThenServerFallback() {
        JavaPlugin plugin = mock(JavaPlugin.class); var config = new YamlConfiguration(); config.set("language", "en");
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getConfig()).thenReturn(config);
        when(plugin.getResource(anyString())).thenAnswer(call -> getClass().getClassLoader().getResourceAsStream(call.getArgument(0)));
        Player spanish = mock(Player.class), german = mock(Player.class);
        when(spanish.getLocale()).thenReturn("fr_fr"); when(german.getLocale()).thenReturn("de_de");
        LanguageService service = new LanguageService(plugin); service.load();
        service.setPreferenceResolver(player -> player == spanish ? "es" : "auto");
        assertEquals("es", service.languageOf(spanish)); assertEquals("de", service.languageOf(german));
        assertTrue(service.get(spanish, "screen-selector-title").contains("personaje"));
        assertNotEquals(service.get(spanish, "screen-selector-title"), service.get(german, "screen-selector-title"));
        when(german.getLocale()).thenReturn("ja_jp"); assertEquals("en", service.languageOf(german));
        assertEquals("en", service.languageOf(null)); assertEquals("es", service.languageOf(spanish));
        ProfileStorage storage = new ProfileStorage(plugin); AccountData account = new AccountData();
        UUID uuid = UUID.randomUUID(); account.setPreferredLanguage("es"); account.setPlayerName("Owner");
        storage.saveAccount(uuid, account);
        assertEquals("es", storage.loadAccount(uuid).getPreferredLanguage());
        assertEquals("Owner", storage.knownPlayerNames().get(uuid));
    }
}
