package es.mrdino.perplayerprofiles.gui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeadTexturesTest {
    private final String hash = "a".repeat(64);
    @Test void urlHashAndMinecraftHeadsBase64ResolveToTheSameCanonicalTexture() {
        String expected = HeadTextures.normalize(hash);
        assertEquals(expected, HeadTextures.normalize("https://textures.minecraft.net/texture/" + hash));
        assertEquals(expected, HeadTextures.normalize("http://textures.minecraft.net/texture/" + hash));
        assertEquals(expected, HeadTextures.normalize(expected));
    }
    @Test void arbitraryUrlsUserInfoPortsAndMalformedBase64AreRejected() {
        for (String input : new String[]{"http://127.0.0.1/texture/" + hash, "https://textures.minecraft.net.attacker.example/texture/" + hash,
                "https://textures.minecraft.net@evil.example/texture/" + hash, "https://textures.minecraft.net:443/texture/" + hash,
                "https://textures.minecraft.net/texture/" + hash + "?x=1", "file:///etc/passwd", "garbage", "a".repeat(8193)})
            assertThrows(IllegalArgumentException.class, () -> HeadTextures.normalize(input));
        String encoded = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://localhost/secret\"}}}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> HeadTextures.normalize(encoded));
    }
}
