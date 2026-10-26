package es.mrdino.perplayerprofiles.security;

import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test void newCredentialsUseRandomSaltAndMatchServerPasswordFormat() {
        String first = PasswordHasher.hash("123456");
        assertTrue(first.startsWith("pbkdf2-sha256$600000$"));
        assertNotEquals(first, PasswordHasher.hash("123456"));
        assertTrue(PasswordHasher.verify("123456", first));
        assertFalse(PasswordHasher.verify("654321", first));
        assertFalse(PasswordHasher.needsUpgrade(first));
    }

    @Test void legacyCredentialsStillAuthenticateAndAreMarkedForUpgrade() throws Exception {
        byte[] salt = new byte[16];
        byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec("4321".toCharArray(), salt, 120000, 256)).getEncoded();
        String legacy = "120000:" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        assertTrue(PasswordHasher.verify("4321", legacy));
        assertTrue(PasswordHasher.needsUpgrade(legacy));
        assertFalse(PasswordHasher.verify("1234", legacy));
    }

    @Test void malformedOrMissingCredentialsFailClosedWithoutExpensiveUnboundedWork() {
        for (String value : new String[]{null, "", " ", "1234", "abc:a:b", "2147483647:a:b", "-1:a:b", "pbkdf2-sha256$600000$?$?", "a".repeat(1024)}) {
            assertFalse(assertDoesNotThrow(() -> PasswordHasher.verify("1234", value)), String.valueOf(value));
        }
        assertFalse(PasswordHasher.verify(null, PasswordHasher.hash("1234")));
    }
}
