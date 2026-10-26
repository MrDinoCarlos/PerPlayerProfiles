package es.mrdino.perplayerprofiles.command;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilesCommandTest {
    @Test void useWithAPinStillRequiresNewPinSetupAfterAnAdministrativeReset() {
        var plugin = mock(PerPlayerProfilesPlugin.class); var player = mock(Player.class); UUID id = UUID.randomUUID();
        var gui = mock(es.mrdino.perplayerprofiles.gui.ProfileGuiManager.class);
        when(player.getUniqueId()).thenReturn(id); when(plugin.isProfileReady(player)).thenReturn(true);
        when(plugin.getProfileGuiManager()).thenReturn(gui);
        AccountData account = new AccountData(); ProfileMeta profile = new ProfileMeta("Hero"); profile.requireNewPassword(); account.putProfile(profile);
        when(plugin.loadAccount(id)).thenReturn(account);
        new ProfilesCommand(plugin).onCommand(player, null, "profiles", new String[]{"use", "Hero", "1234"});
        verify(gui).selectProfile(player, "Hero"); verify(plugin, never()).getPinSecurity();
        verify(plugin, never()).switchProfile(any(), anyString());
    }
    @Test void allAliasesModesAndProfileNamesAreCompletedButPinsNeverAre() {
        PerPlayerProfilesPlugin plugin = mock(PerPlayerProfilesPlugin.class);
        Player player = mock(Player.class); UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission("perplayerprofiles.admin")).thenReturn(true);
        AccountData account = new AccountData(); account.putProfile(new ProfileMeta("MyCharacter"));
        when(plugin.loadAccount(id)).thenReturn(account);
        ProfilesCommand commands = new ProfilesCommand(plugin);
        var options = commands.onTabComplete(player, null, "ppp", new String[]{""});
        assertTrue(options.containsAll(java.util.List.of("switch", "remove", "settings", "info", "icon", "icons", "mode", "reload")));
        for (String command : java.util.List.of("use", "switch", "delete", "remove", "lock", "unlock", "setlimit", "settings", "info", "icon", "icons"))
            assertEquals(java.util.List.of("MyCharacter"), commands.onTabComplete(player, null, "ppp", new String[]{command, "my"}));
        assertEquals(java.util.List.of("modern"), commands.onTabComplete(player, null, "ppp", new String[]{"mode", "m"}));
        assertTrue(commands.onTabComplete(player, null, "ppp", new String[]{"use", "MyCharacter", ""}).isEmpty());
        assertTrue(commands.onTabComplete(player, null, "ppp", new String[]{"setmasterpassword", ""}).isEmpty());
        assertTrue(commands.onTabComplete(player, null, "ppp", new String[]{"setlimit", "MyCharacter", "6"}).contains("60"));
        assertTrue(commands.onTabComplete(player, null, "ppp", new String[]{"setlimit", "MyCharacter", "60", "2"}).contains("24"));
    }
    @Test void reloadIsSuggestedOnlyToAdministrators() {
        ProfilesCommand commands = new ProfilesCommand(mock(PerPlayerProfilesPlugin.class));
        Player player = mock(Player.class);
        assertFalse(commands.onTabComplete(player, null, "profiles", new String[]{""}).contains("reload"));
    }
}
