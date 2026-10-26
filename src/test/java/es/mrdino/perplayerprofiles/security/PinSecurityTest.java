package es.mrdino.perplayerprofiles.security;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PinSecurityTest {
    @Test void administrativeResetCannotBeBypassedWithThePreviousOrMasterPin() {
        var plugin = mock(PerPlayerProfilesPlugin.class); var player = mock(Player.class);
        ProfileMeta profile = new ProfileMeta("recovered"); profile.setPasswordHash(PasswordHasher.hash("1234"));
        profile.requireNewPassword(); PinSecurity security = new PinSecurity(plugin);
        assertFalse(security.profile(player, profile, "1234", true));
        verify(plugin, never()).loadAccount(any()); verify(plugin, never()).saveAccount(any());
    }
    @Test void successfulLegacyAuthenticationUpgradesAndSavesTheSameProfile() throws Exception {
        var plugin = mock(PerPlayerProfilesPlugin.class); var player = mock(Player.class);
        UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        AccountData account = new AccountData(); ProfileMeta profile = new ProfileMeta("old"); account.putProfile(profile);
        when(plugin.loadAccount(id)).thenReturn(account);
        byte[] salt = new byte[16]; byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec("4321".toCharArray(), salt, 120000, 256)).getEncoded();
        profile.setPasswordHash("120000:" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(key));
        assertTrue(new PinSecurity(plugin).profile(player, profile, "4321", true));
        assertTrue(profile.getPasswordHash().startsWith("pbkdf2-sha256$600000$"));
        verify(plugin).saveAccount(id);
    }
    @Test void sharedAttemptBudgetBlocksEvenTheCorrectMasterPinDuringCooldownAndSurvivesPlayerReplacement() {
        var plugin = mock(PerPlayerProfilesPlugin.class); var player = mock(Player.class); UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id); var config = new YamlConfiguration(); config.set("security.pin.max-attempts", 2); config.set("security.pin.cooldown-seconds", 60);
        when(plugin.getConfig()).thenReturn(config); AccountData account = new AccountData(); account.setMasterPasswordHash(PasswordHasher.hash("1234"));
        when(plugin.loadAccount(id)).thenReturn(account); PinSecurity security = new PinSecurity(plugin);
        assertFalse(security.master(player, "0000")); assertFalse(security.master(player, "0000"));
        var reconnected = mock(Player.class); when(reconnected.getUniqueId()).thenReturn(id);
        assertFalse(security.master(reconnected, "1234"));
    }
}
