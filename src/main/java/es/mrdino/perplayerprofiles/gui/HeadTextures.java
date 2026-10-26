package es.mrdino.perplayerprofiles.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Accept only Minecraft's texture CDN. Importing never fetches arbitrary user URLs. */
public final class HeadTextures {
    private HeadTextures() { }

    public static String normalize(String input) {
        if (input == null || input.isBlank() || input.length() > 8192) throw new IllegalArgumentException("Invalid texture");
        String value = input.trim();
        if (value.matches("(?i)[a-f0-9]{32,64}")) value = "https://textures.minecraft.net/texture/" + value;
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            try {
                JsonObject json = JsonParser.parseString(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).getAsJsonObject();
                value = json.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid texture", invalid); }
        }
        URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid texture", invalid); }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().matches("/texture/[a-fA-F0-9]{32,64}")) throw new IllegalArgumentException("Invalid texture URL");
        String url = "https://textures.minecraft.net" + uri.getPath().toLowerCase(Locale.ROOT);
        return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(StandardCharsets.UTF_8));
    }
}
