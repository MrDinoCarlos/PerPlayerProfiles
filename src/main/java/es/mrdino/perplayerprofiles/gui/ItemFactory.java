package es.mrdino.perplayerprofiles.gui;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

public final class ItemFactory {
    private static final Pattern TEXTURE_URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private ItemFactory() {
    }

    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(color(lore));
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack head(String textureValue, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(color(lore));
            applyTexture(meta, textureValue);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static void applyTexture(SkullMeta meta, String textureValue) {
        if (textureValue == null || textureValue.isBlank()) {
            return;
        }
        try {
            String json = new String(Base64.getDecoder().decode(HeadTextures.normalize(textureValue)), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURE_URL_PATTERN.matcher(json);
            if (!matcher.find()) {
                return;
            }
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(textureValue.getBytes(StandardCharsets.UTF_8)));
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL(matcher.group(1)));
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
        } catch (IllegalArgumentException | MalformedURLException ignored) {
            // Invalid configurable texture values fall back to the default player head.
        }
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private static List<String> color(List<String> lines) {
        List<String> colored = new ArrayList<>();
        for (String line : lines) {
            colored.add(color(line));
        }
        return colored;
    }
}
