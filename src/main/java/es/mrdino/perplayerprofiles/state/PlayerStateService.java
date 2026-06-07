package es.mrdino.perplayerprofiles.state;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public class PlayerStateService {
    public YamlConfiguration capture(Player player) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("location", player.getLocation());
        yaml.set("bed-spawn", player.getBedSpawnLocation());
        yaml.set("respawn-location", player.getRespawnLocation());
        yaml.set("inventory.contents", player.getInventory().getContents());
        yaml.set("inventory.armor", player.getInventory().getArmorContents());
        yaml.set("inventory.extra", player.getInventory().getExtraContents());
        yaml.set("ender-chest", player.getEnderChest().getContents());
        yaml.set("health", player.getHealth());
        yaml.set("food-level", player.getFoodLevel());
        yaml.set("saturation", player.getSaturation());
        yaml.set("exhaustion", player.getExhaustion());
        yaml.set("level", player.getLevel());
        yaml.set("exp", player.getExp());
        yaml.set("total-experience", player.getTotalExperience());
        yaml.set("game-mode", player.getGameMode().name());
        yaml.set("allow-flight", player.getAllowFlight());
        yaml.set("flying", player.isFlying());
        yaml.set("fire-ticks", player.getFireTicks());
        yaml.set("remaining-air", player.getRemainingAir());
        yaml.set("potion-effects", new ArrayList<>(player.getActivePotionEffects()));
        saveUntypedStatistics(player, yaml.createSection("statistics"));
        saveAdvancements(player, yaml.createSection("advancements"));
        return yaml;
    }

    public void apply(Player player, YamlConfiguration yaml) {
        player.getInventory().setContents(readItemArray(yaml, "inventory.contents", player.getInventory().getSize()));
        player.getInventory().setArmorContents(readItemArray(yaml, "inventory.armor", 4));
        player.getInventory().setExtraContents(readItemArray(yaml, "inventory.extra", player.getInventory().getExtraContents().length));
        player.getEnderChest().setContents(readItemArray(yaml, "ender-chest", player.getEnderChest().getSize()));

        player.setFoodLevel(yaml.getInt("food-level", 20));
        player.setSaturation((float) yaml.getDouble("saturation", 5.0D));
        player.setExhaustion((float) yaml.getDouble("exhaustion", 0.0D));
        player.setLevel(yaml.getInt("level", 0));
        player.setExp((float) yaml.getDouble("exp", 0.0D));
        player.setTotalExperience(yaml.getInt("total-experience", 0));
        player.setGameMode(GameMode.valueOf(yaml.getString("game-mode", GameMode.SURVIVAL.name())));
        player.setAllowFlight(yaml.getBoolean("allow-flight", false));
        player.setFlying(yaml.getBoolean("flying", false) && player.getAllowFlight());
        player.setFireTicks(yaml.getInt("fire-ticks", 0));
        player.setRemainingAir(yaml.getInt("remaining-air", player.getMaximumAir()));

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        List<?> effects = yaml.getList("potion-effects", List.of());
        for (Object effect : effects) {
            if (effect instanceof PotionEffect potionEffect) {
                player.addPotionEffect(potionEffect);
            }
        }

        applyHealth(player, yaml.getDouble("health", getMaxHealth(player)));
        applyUntypedStatistics(player, yaml.getConfigurationSection("statistics"));
        applyAdvancements(player, yaml.getConfigurationSection("advancements"));

        Location location = yaml.getLocation("location");
        if (location != null && location.getWorld() != null) {
            player.teleport(location);
        }
        Location bedSpawn = yaml.getLocation("bed-spawn");
        if (bedSpawn != null) {
            player.setBedSpawnLocation(bedSpawn, true);
        }
        if (yaml.contains("respawn-location")) {
            player.setRespawnLocation(yaml.getLocation("respawn-location"), true);
        }
        player.updateInventory();
    }

    public YamlConfiguration createFreshProfileState(Player player) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("location", player.getWorld().getSpawnLocation());
        yaml.set("bed-spawn", null);
        yaml.set("respawn-location", null);
        yaml.set("inventory.contents", new ItemStack[player.getInventory().getSize()]);
        yaml.set("inventory.armor", new ItemStack[4]);
        yaml.set("inventory.extra", new ItemStack[player.getInventory().getExtraContents().length]);
        yaml.set("ender-chest", new ItemStack[player.getEnderChest().getSize()]);
        yaml.set("health", getMaxHealth(player));
        yaml.set("food-level", 20);
        yaml.set("saturation", 5.0D);
        yaml.set("exhaustion", 0.0D);
        yaml.set("level", 0);
        yaml.set("exp", 0.0D);
        yaml.set("total-experience", 0);
        yaml.set("game-mode", GameMode.SURVIVAL.name());
        yaml.set("allow-flight", false);
        yaml.set("flying", false);
        yaml.set("fire-ticks", 0);
        yaml.set("remaining-air", player.getMaximumAir());
        yaml.set("potion-effects", List.of());
        ConfigurationSection statistics = yaml.createSection("statistics");
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED) {
                statistics.set(statistic.name(), 0);
            }
        }
        yaml.createSection("advancements");
        return yaml;
    }

    public boolean hasState(YamlConfiguration yaml) {
        return yaml.contains("inventory.contents") || yaml.contains("location");
    }

    private void saveUntypedStatistics(Player player, ConfigurationSection section) {
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED) {
                section.set(statistic.name(), player.getStatistic(statistic));
            }
        }
    }

    private void applyUntypedStatistics(Player player, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                Statistic statistic = Statistic.valueOf(key);
                if (statistic.getType() == Statistic.Type.UNTYPED) {
                    player.setStatistic(statistic, section.getInt(key));
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore statistics removed or renamed by newer Minecraft versions.
            }
        }
    }

    private void saveAdvancements(Player player, ConfigurationSection section) {
        Iterator<Advancement> advancements = Bukkit.advancementIterator();
        while (advancements.hasNext()) {
            Advancement advancement = advancements.next();
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            List<String> awardedCriteria = new ArrayList<>();
            for (String criterion : progress.getAwardedCriteria()) {
                awardedCriteria.add(criterion);
            }
            if (!awardedCriteria.isEmpty()) {
                section.set(advancement.getKey().toString(), awardedCriteria);
            }
        }
    }

    private void applyAdvancements(Player player, ConfigurationSection section) {
        Iterator<Advancement> advancements = Bukkit.advancementIterator();
        while (advancements.hasNext()) {
            Advancement advancement = advancements.next();
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criterion);
            }
        }
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Advancement advancement = Bukkit.getAdvancement(NamespacedKey.fromString(key));
            if (advancement == null) {
                continue;
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : section.getStringList(key)) {
                try {
                    progress.awardCriteria(criterion);
                } catch (IllegalArgumentException ignored) {
                    // Ignore criteria removed or renamed by newer Minecraft versions.
                }
            }
        }
    }

    private ItemStack[] readItemArray(YamlConfiguration yaml, String path, int expectedSize) {
        List<?> list = yaml.getList(path, List.of());
        ItemStack[] result = new ItemStack[expectedSize];
        for (int i = 0; i < Math.min(result.length, list.size()); i++) {
            Object item = list.get(i);
            if (item instanceof ItemStack itemStack) {
                result[i] = itemStack;
            }
        }
        return result;
    }

    private void applyHealth(Player player, double health) {
        double maxHealth = getMaxHealth(player);
        player.setHealth(Math.max(0.1D, Math.min(maxHealth, health)));
    }

    private double getMaxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20.0D : attribute.getValue();
    }
}
