package es.mrdino.perplayerprofiles.command;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileCommandRoutingTest {
    @Test void profileTeleportIsResolvedThenLeftToNativePermissionsAndCoordinateParsing() throws Exception {
        var config = new YamlConfiguration();
        config.set("profile-identity.commands.rewrite.tp", List.of(0, 1));
        var plugin = plugin(config);
        var sender = mock(Player.class);
        var target = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(id);
        when(target.getName()).thenReturn("RealPlayer");
        doReturn(new ProfileMeta("Hero")).when(plugin).getActiveProfileMeta(target);
        var loaded = PerPlayerProfilesPlugin.class.getDeclaredField("loadedProfiles");
        loaded.setAccessible(true); loaded.set(plugin, Set.of(id));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(target));
            for (String input : List.of("/tp Hero", "/minecraft:tp Hero ~ ~1 ~")) {
                var event = new PlayerCommandPreprocessEvent(sender, input, Set.of());
                plugin.onProfileCommand(event);
                assertEquals(input.replace("Hero", "RealPlayer"), event.getMessage());
                assertFalse(event.isCancelled());
            }
            verify(sender, never()).teleport(any(org.bukkit.entity.Entity.class));
            verify(target, never()).teleport(any(org.bukkit.Location.class));
            var console = new ServerCommandEvent(mock(ConsoleCommandSender.class), "minecraft:tp Hero ~ ~ ~");
            plugin.onConsoleProfileCommand(console);
            assertEquals("minecraft:tp RealPlayer ~ ~ ~", console.getCommand());
        }
    }

    @Test void disabledIntegrationLeavesConsoleCommandsAndPlayerListPermissionsAlone() {
        var config = new YamlConfiguration();
        var plugin = plugin(config);
        var player = mock(Player.class);
        var event = new PlayerCommandPreprocessEvent(player, "/list", Set.of());
        plugin.onProfileCommand(event);
        assertFalse(event.isCancelled());
        config.set("profile-identity.commands.enabled", false);
        var console = new ServerCommandEvent(mock(ConsoleCommandSender.class), "list");
        plugin.onConsoleProfileCommand(console);
        assertFalse(console.isCancelled());
        assertEquals("list", console.getCommand());
    }

    private PerPlayerProfilesPlugin plugin(YamlConfiguration config) {
        var plugin = mock(PerPlayerProfilesPlugin.class, CALLS_REAL_METHODS);
        doReturn(config).when(plugin).getConfig();
        return plugin;
    }
}
