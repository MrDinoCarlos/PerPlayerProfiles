package es.mrdino.perplayerprofiles.model;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileMetaTest {
    @Test void lifetimePlaytimeSurvivesTimeResetsRenamesAndPersistence() {
        ProfileMeta profile = new ProfileMeta("test");
        profile.initializePlaytime(5000L);
        long creation = profile.getCreatedAt(); profile.recordConnection(123456789L);
        profile.recordSession(7000L, 12, 3, 25, 2);
        profile.setTimeLimit(60000L, 3600000L);
        profile.addUsedMillis(1000L);
        profile.resetTimeUsage(); profile.setName("newname");
        var yaml = new YamlConfiguration(); profile.saveTo(yaml);
        ProfileMeta restored = ProfileMeta.fromSection("newname", yaml);
        assertEquals(12000L, restored.getTotalPlayedMillis());
        assertEquals(0L, restored.getUsedMillis());
        assertEquals(25, restored.getMobKills());
        assertEquals(12, restored.getLevel());
        assertEquals(creation, restored.getCreatedAt()); assertEquals(123456789L, restored.getLastConnectedAt());
    }
    @Test void legacyProfilesKeepAnUnknownCreationDateInsteadOfInventingOne() {
        var yaml = new YamlConfiguration(); yaml.set("stats.last-played-at", 987654321L);
        ProfileMeta restored = ProfileMeta.fromSection("old", yaml);
        assertEquals(0L, restored.getCreatedAt()); assertEquals(987654321L, restored.getLastConnectedAt());
    }
    @Test void sessionsCrossingRenewalOnlyConsumeTimeInTheNewWindow() {
        long now = System.currentTimeMillis();
        var yaml = new YamlConfiguration();
        yaml.set("time.max-play-millis", 60000L);
        yaml.set("time.renew-millis", 3600000L);
        yaml.set("time.window-started-at", now - 3610000L);
        yaml.set("time.used-millis", 60000L);
        ProfileMeta profile = ProfileMeta.fromSection("test", yaml);
        assertEquals(50000L, profile.getRemainingMillis(now, now - 60000L));
        profile.chargeSession(now - 60000L, now);
        assertEquals(10000L, profile.getUsedMillis());
        assertEquals(50000L, profile.getRemainingMillis(now));
    }
    @Test void masterProfilesRemainUnlimitedAndCustomIconsPersist() {
        ProfileMeta profile = new ProfileMeta("master");
        profile.setMaster(true); profile.setTimeLimit(1L, 1L); profile.setCustomTexture("texture");
        assertFalse(profile.isTimeLimited()); assertFalse(profile.isTimeExceeded(System.currentTimeMillis()));
        var yaml = new YamlConfiguration(); profile.saveTo(yaml);
        assertEquals("texture", ProfileMeta.fromSection("master", yaml).getCustomTexture());
    }
}
