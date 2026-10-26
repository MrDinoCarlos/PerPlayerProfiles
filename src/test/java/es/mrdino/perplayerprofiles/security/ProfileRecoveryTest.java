package es.mrdino.perplayerprofiles.security;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.command.ProfilesCommand;
import es.mrdino.perplayerprofiles.lang.LanguageService;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileRecoveryTest {
    @Test void recoveringAnUnprotectedProfileDoesNotIntroduceANewPasswordRequirement() {
        var plugin = mock(PerPlayerProfilesPlugin.class); var sender = mock(CommandSender.class); UUID owner = UUID.randomUUID();
        var language = mock(LanguageService.class); when(plugin.getLanguageService()).thenReturn(language);
        when(sender.hasPermission("perplayerprofiles.admin")).thenReturn(true);
        AccountData account = new AccountData(); ProfileMeta profile = new ProfileMeta("Open"); account.putProfile(profile);
        when(plugin.loadAccount(owner)).thenReturn(account);
        assertFalse(new ProfileRecovery(plugin).recover(sender, owner, "Open", true));
        assertFalse(profile.hasPassword()); assertFalse(profile.isPasswordResetRequired());
        verify(plugin, never()).saveAccount(any());
    }
    @Test void unauthorizedSendersCannotRecoverOrDiscoverOtherPlayersProfiles() {
        var plugin = mock(PerPlayerProfilesPlugin.class); var language = mock(LanguageService.class);
        var sender = mock(CommandSender.class); when(plugin.getLanguageService()).thenReturn(language);
        var command = new ProfilesCommand(plugin);
        command.onCommand(sender, null, "profiles", new String[]{"admin", "resetpin", "Owner", "Hero"});
        assertTrue(command.onTabComplete(sender, null, "profiles", new String[]{"admin", "resetpin", "Owner", ""}).isEmpty());
        verify(language).send(sender, "no-permission"); verify(plugin, never()).loadAccount(any());
        verify(plugin, never()).knownPlayerAccounts(); verify(plugin, never()).resolveKnownAccount(anyString());
    }

    @Test void consoleCanResetAndRemoveAnOfflineProfilesPinWithoutChangingMasterPin() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var plugin = mock(PerPlayerProfilesPlugin.class); var sender = mock(CommandSender.class);
            var language = mock(LanguageService.class); var security = mock(PinSecurity.class);
            var logger = mock(java.util.logging.Logger.class); UUID owner = UUID.randomUUID();
            AccountData account = new AccountData(); ProfileMeta profile = new ProfileMeta("Hero");
            profile.setPasswordHash("old-sensitive-hash"); account.putProfile(profile);
            account.setMasterPasswordHash("master-sensitive-hash"); account.setPlayerName("Owner");
            when(plugin.getLanguageService()).thenReturn(language); when(plugin.getPinSecurity()).thenReturn(security);
            when(plugin.getLogger()).thenReturn(logger); when(plugin.resolveKnownAccount("Owner")).thenReturn(owner);
            when(plugin.loadAccount(owner)).thenReturn(account); when(sender.hasPermission("perplayerprofiles.admin")).thenReturn(true);
            when(sender.getName()).thenReturn("CONSOLE"); when(plugin.knownPlayerAccounts()).thenReturn(Map.of(owner, "Owner"));
            var command = new ProfilesCommand(plugin);
            assertEquals(List.of("resetpin", "removepin"), command.onTabComplete(sender, null, "profiles", new String[]{"admin", ""}));
            assertEquals(List.of("Owner"), command.onTabComplete(sender, null, "profiles", new String[]{"admin", "resetpin", "O"}));
            assertEquals(List.of("Hero"), command.onTabComplete(sender, null, "profiles", new String[]{"admin", "resetpin", "Owner", "H"}));
            command.onCommand(sender, null, "profiles", new String[]{"admin", "resetpin", "Owner", "Hero"});
            assertNull(profile.getPasswordHash()); assertTrue(profile.hasPassword()); assertTrue(profile.isPasswordResetRequired());
            var saved = new org.bukkit.configuration.file.YamlConfiguration(); profile.saveTo(saved);
            assertTrue(ProfileMeta.fromSection("Hero", saved).isPasswordResetRequired());
            command.onCommand(sender, null, "profiles", new String[]{"admin", "removepin", "Owner", "Hero"});
            assertFalse(profile.hasPassword()); assertFalse(profile.isPasswordResetRequired());
            assertEquals("master-sensitive-hash", account.getMasterPasswordHash());
            verify(plugin, times(2)).saveAccount(owner); verify(security, times(2)).clear(owner);
            verify(logger, times(2)).info(argThat((String message) -> message.contains("CONSOLE") && message.contains(owner.toString())
                    && !message.contains("sensitive-hash")));
        }
    }

    @Test void failedSaveKeepsPreviousProtectionAndDoesNotNotifyOrAuditSuccess() {
        var plugin = mock(PerPlayerProfilesPlugin.class); var sender = mock(CommandSender.class); UUID owner = UUID.randomUUID();
        when(sender.hasPermission("perplayerprofiles.admin")).thenReturn(true);
        AccountData account = new AccountData(); ProfileMeta profile = new ProfileMeta("Hero");
        profile.setPasswordHash("old-hash"); account.putProfile(profile); when(plugin.loadAccount(owner)).thenReturn(account);
        doThrow(new IllegalStateException("disk full")).when(plugin).saveAccount(owner);
        assertThrows(IllegalStateException.class, () -> new ProfileRecovery(plugin).recover(sender, owner, "Hero", true));
        assertEquals("old-hash", profile.getPasswordHash()); assertFalse(profile.isPasswordResetRequired());
        verify(plugin, never()).getPinSecurity(); verify(plugin, never()).getLogger();
    }
}
