package es.mrdino.perplayerprofiles.update;

import com.google.gson.JsonParser;
import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdateCheckerTest {
    @Test void paperBuildIdentifiersAreNotMistakenForMinecraftPatchVersions() {
        org.junit.jupiter.api.Assertions.assertEquals("26.3", UpdateChecker.minecraftVersion("26.3.build.223-stable"));
        org.junit.jupiter.api.Assertions.assertEquals("26.1.2", UpdateChecker.minecraftVersion("26.1.2.build.74-stable"));
        org.junit.jupiter.api.Assertions.assertEquals("1.21.7", UpdateChecker.minecraftVersion("1.21.7-R0.1-SNAPSHOT"));
    }
    @Test void consoleAlwaysReportsUpdatesEvenWhenAdminChatIsDisabled() {
        Fixture fixture = new Fixture(false, true);
        fixture.check.run(); fixture.notify.run();
        verify(fixture.logger).info(contains("1.1.0"));
        verify(fixture.plugin, never()).send(eq(fixture.player), eq("update-available"), any(Object[].class));
    }
    @Test void consoleReportsAnUpToDateInstall() {
        Fixture fixture = new Fixture(false, false); fixture.check.run();
        verify(fixture.logger).info(contains("no newer compatible version"));
    }
    @Test void administratorIsNotifiedOnlyOnceAndOnlyAfterExternalAuthentication() {
        Fixture fixture = new Fixture(true, true);
        fixture.check.run(); fixture.notify.run();
        verify(fixture.plugin, never()).send(eq(fixture.player), eq("update-available"), any(Object[].class));
        when(fixture.plugin.isProfileReady(fixture.player)).thenReturn(true);
        fixture.notify.run(); fixture.notify.run();
        verify(fixture.plugin, times(1)).send(eq(fixture.player), eq("update-available"), any(Object[].class));
        fixture.checker.shutdown();
    }
    private static class Fixture {
        final PerPlayerProfilesPlugin plugin = mock(PerPlayerProfilesPlugin.class);
        final Player player = mock(Player.class);
        final Logger logger = mock(Logger.class);
        final UpdateChecker checker;
        Runnable check, notify;
        Fixture(boolean notifyAdmins, boolean newer) {
            var config = new YamlConfiguration(); config.set("updates.notify-admins", notifyAdmins);
            var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
            when(player.hasPermission("perplayerprofiles.admin")).thenReturn(true);
            when(plugin.getConfig()).thenReturn(config); when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(logger); when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("PerPlayerProfiles", "1.0.0", "example.Main"));
            when(server.getBukkitVersion()).thenReturn("1.20.1-R0.1-SNAPSHOT"); when(server.getName()).thenReturn("Paper");
            when(server.getScheduler()).thenReturn(scheduler); when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(server.getPlayer(id)).thenReturn(player);
            when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> { check = call.getArgument(1); return mock(BukkitTask.class); });
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> { notify = call.getArgument(1); return mock(BukkitTask.class); });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { ((Runnable)call.getArgument(1)).run(); return mock(BukkitTask.class); });
            checker = new UpdateChecker(plugin, installed -> JsonParser.parseString(newer
                    ? "[{\"id\":\"new\",\"version_number\":\"1.1.0\",\"game_versions\":[\"1.20.1\"],\"loaders\":[\"paper\"]}]" : "[]").getAsJsonArray());
            var join = mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(player); checker.onJoin(join);
        }
    }
}
