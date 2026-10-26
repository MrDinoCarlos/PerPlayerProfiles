package es.mrdino.perplayerprofiles.gui;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectionGuardTest {
    @Test void mandatorySelectionAllowsOnlyProfileCommandsAndRejectsFakeNamespaces() {
        var plugin = mock(PerPlayerProfilesPlugin.class); var gui = mock(ProfileGuiManager.class); var player = mock(Player.class);
        when(plugin.getProfileGuiManager()).thenReturn(gui); when(gui.isSelectionRequired(player)).thenReturn(true);
        var guard = new SelectionGuard(plugin);
        for (String command : new String[]{"/ppp use hero", "/profiles mode legacy", "/perplayerprofiles:profiles use hero"}) {
            var event = new PlayerCommandPreprocessEvent(player, command, java.util.Set.of()); guard.command(event); assertFalse(event.isCancelled(), command);
        }
        for (String command : new String[]{"/give @s diamond", "/minecraft:ppp", "/other:profiles use hero"}) {
            var event = new PlayerCommandPreprocessEvent(player, command, java.util.Set.of()); guard.command(event); assertTrue(event.isCancelled(), command);
        }
    }
}
