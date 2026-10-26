package es.mrdino.perplayerprofiles.state;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** Sparse typed statistics and recipe discovery belong to the selected profile. */
public final class ProfileProgress {
    private static final List<Material> BLOCKS = java.util.Arrays.stream(Material.values()).filter(material -> !material.isLegacy() && material.isBlock()).toList();
    private static final List<Material> ITEMS = java.util.Arrays.stream(Material.values()).filter(material -> !material.isLegacy() && material.isItem()).toList();
    private static final List<EntityType> ENTITIES = java.util.Arrays.stream(EntityType.values()).filter(type -> type != EntityType.UNKNOWN).toList();
    private ProfileProgress() { }

    public static void capture(Player player, YamlConfiguration yaml) {
        yaml.set("recipes", player.getDiscoveredRecipes().stream().map(NamespacedKey::toString).sorted().toList());
        captureStatistics(player, yaml.createSection("statistics-typed"));
    }

    public static void captureStatistics(Player player, ConfigurationSection target) {
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED) continue;
            if (statistic.getType() == Statistic.Type.ENTITY) {
                for (EntityType type : ENTITIES) {
                    try { int value = player.getStatistic(statistic, type); if (value > 0) target.set(statistic.name() + "." + type.name(), value); }
                    catch (IllegalArgumentException unsupported) { /* Unregistered or version-specific entity. */ }
                }
            } else {
                for (Material material : statistic.getType() == Statistic.Type.BLOCK ? BLOCKS : ITEMS) {
                    try { int value = player.getStatistic(statistic, material); if (value > 0) target.set(statistic.name() + "." + material.name(), value); }
                    catch (IllegalArgumentException unsupported) { /* Material has no counter for this statistic. */ }
                }
            }
        }
    }

    public static void applyStatistics(Player player, ConfigurationSection desired) {
        // Reset counters absent from the target too; sparse files must not inherit the old profile's values.
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED) continue;
            if (statistic.getType() == Statistic.Type.ENTITY) {
                for (EntityType type : ENTITIES) {
                    int value = desired == null ? 0 : Math.max(0, desired.getInt(statistic.name() + "." + type.name()));
                    try { if (player.getStatistic(statistic, type) != value) player.setStatistic(statistic, type, value); }
                    catch (IllegalArgumentException unsupported) { /* Removed or unsupported entity. */ }
                }
            } else {
                for (Material material : statistic.getType() == Statistic.Type.BLOCK ? BLOCKS : ITEMS) {
                    int value = desired == null ? 0 : Math.max(0, desired.getInt(statistic.name() + "." + material.name()));
                    try { if (player.getStatistic(statistic, material) != value) player.setStatistic(statistic, material, value); }
                    catch (IllegalArgumentException unsupported) { /* Removed or unsupported material. */ }
                }
            }
        }
    }

}
