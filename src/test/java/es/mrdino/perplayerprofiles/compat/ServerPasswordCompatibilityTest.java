package es.mrdino.perplayerprofiles.compat;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerPasswordCompatibilityTest {
    @Test void nativeAuthenticationLockIsHonoredEvenWithoutAnInventoryGui() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var manager = mock(PluginManager.class); bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            var serverPassword = mock(PasswordPlugin.class); when(serverPassword.isEnabled()).thenReturn(true);
            when(manager.getPlugin("ServerPassword")).thenReturn(serverPassword); var player = mock(Player.class);
            ServerPasswordCompatibility compatibility = new ServerPasswordCompatibility(mock(JavaPlugin.class));
            when(serverPassword.isLocked(player)).thenReturn(true); assertTrue(compatibility.isWaitingForAuth(player));
            when(serverPassword.isLocked(player)).thenReturn(false); assertFalse(compatibility.isWaitingForAuth(player));
        }
    }
    @Test void missingPluginDoesNotBlockAndUnreadableEnabledPluginCannotExposeProfiles() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var manager = mock(PluginManager.class); bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            var plugin = mock(JavaPlugin.class); when(plugin.getLogger()).thenReturn(mock(Logger.class));
            var compatibility = new ServerPasswordCompatibility(plugin); var player = mock(Player.class);
            assertFalse(compatibility.isWaitingForAuth(player));
            var unknown = mock(JavaPlugin.class); when(unknown.isEnabled()).thenReturn(true);
            when(unknown.getDescription()).thenReturn(new PluginDescriptionFile("ServerPassword", "future", "example.Main"));
            when(manager.getPlugin("ServerPassword")).thenReturn(unknown);
            assertTrue(compatibility.isWaitingForAuth(player));
        }
    }
    public static class PasswordPlugin extends JavaPlugin {
        public boolean isLocked(Player player) { return true; }
    }
}
