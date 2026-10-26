package es.mrdino.perplayerprofiles.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileProgressTest {
    @Test void capturesRecipesAndSwitchesTypedCountersWithoutLeakingSparseValues() {
        Player player = mock(Player.class);
        Map<String, Integer> counters = new HashMap<>();
        Set<NamespacedKey> recipes = new HashSet<>();
        counters.put("MINE_BLOCK.STONE", 42); counters.put("USE_ITEM.DIAMOND_PICKAXE", 17);
        counters.put("KILL_ENTITY.ZOMBIE", 8); recipes.add(NamespacedKey.minecraft("crafting_table"));
        when(player.getStatistic(any(Statistic.class), any(Material.class)))
                .thenAnswer(call -> counters.getOrDefault(call.getArgument(0) + "." + call.getArgument(1), 0));
        when(player.getStatistic(any(Statistic.class), any(EntityType.class)))
                .thenAnswer(call -> counters.getOrDefault(call.getArgument(0) + "." + call.getArgument(1), 0));
        doAnswer(call -> { counters.put(call.getArgument(0) + "." + call.getArgument(1), call.getArgument(2)); return null; })
                .when(player).setStatistic(any(Statistic.class), any(Material.class), anyInt());
        doAnswer(call -> { counters.put(call.getArgument(0) + "." + call.getArgument(1), call.getArgument(2)); return null; })
                .when(player).setStatistic(any(Statistic.class), any(EntityType.class), anyInt());
        when(player.getDiscoveredRecipes()).thenAnswer(call -> Set.copyOf(recipes));
        var first = new YamlConfiguration(); ProfileProgress.capture(player, first);
        assertEquals(42, first.getInt("statistics-typed.MINE_BLOCK.STONE"));
        assertFalse(first.contains("statistics-typed.MINE_BLOCK.DIRT"));
        assertEquals(8, first.getInt("statistics-typed.KILL_ENTITY.ZOMBIE"));
        assertEquals(java.util.List.of("minecraft:crafting_table"), first.getStringList("recipes"));
        var second = new YamlConfiguration(); second.set("MINE_BLOCK.DIRT", 3);
        ProfileProgress.applyStatistics(player, second);
        assertEquals(0, counters.get("MINE_BLOCK.STONE")); assertEquals(0, counters.get("USE_ITEM.DIAMOND_PICKAXE"));
        assertEquals(0, counters.get("KILL_ENTITY.ZOMBIE")); assertEquals(3, counters.get("MINE_BLOCK.DIRT"));
        ProfileProgress.applyStatistics(player, first.getConfigurationSection("statistics-typed"));
        assertEquals(42, counters.get("MINE_BLOCK.STONE")); assertEquals(17, counters.get("USE_ITEM.DIAMOND_PICKAXE"));
        assertEquals(8, counters.get("KILL_ENTITY.ZOMBIE")); assertEquals(0, counters.get("MINE_BLOCK.DIRT"));
        verify(player, never()).discoverRecipes(anyCollection());
        verify(player, never()).undiscoverRecipes(anyCollection());
    }
}
