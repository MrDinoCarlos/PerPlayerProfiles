package es.mrdino.perplayerprofiles.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/** Public metadata only; console checks always run, admin join notifications are optional. */
public final class UpdateChecker implements Listener {
    private final PerPlayerProfilesPlugin plugin;
    private final String installed;
    private final String minecraft;
    private final Set<String> loaders;
    private final Set<UUID> pending = new HashSet<>();
    private final BukkitTask checkTask;
    private final BukkitTask notifyTask;
    private ModrinthVersions.Release available;
    private boolean checked;
    private final VersionSource source;

    public UpdateChecker(PerPlayerProfilesPlugin plugin) {
        this(plugin, UpdateChecker::fetch);
    }

    UpdateChecker(PerPlayerProfilesPlugin plugin, VersionSource source) {
        this.plugin = plugin;
        this.source = source;
        installed = plugin.getDescription().getVersion();
        minecraft = minecraftVersion(plugin.getServer().getBukkitVersion());
        boolean paper;
        try { Class.forName("io.papermc.paper.ServerBuildInfo"); paper = true; }
        catch (ClassNotFoundException absent) { paper = plugin.getServer().getName().toLowerCase(java.util.Locale.ROOT).contains("paper"); }
        loaders = paper ? Set.of("paper", "spigot", "bukkit", "purpur") : Set.of("spigot", "bukkit");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        checkTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::check, 40L, 6L * 60 * 60 * 20);
        notifyTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::notifyPending, 60L, 20L);
    }

    private void check() {
        try {
            var result = ModrinthVersions.newerCompatible(source.read(installed), installed, minecraft, loaders).orElse(null);
            if (result == null) plugin.getLogger().info("Modrinth: no newer compatible version (installed " + installed + ", Minecraft " + minecraft + ").");
            else plugin.getLogger().info("New PerPlayerProfiles version: " + result.version() + " (installed " + installed + "). " + result.url());
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, () -> {
                available = result;
                checked = true;
                notifyPending();
            });
        } catch (Exception failure) {
            plugin.getLogger().warning("Could not check Modrinth updates: " + failure.getClass().getSimpleName());
        }
    }

    private static JsonArray fetch(String installed) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("https://api.modrinth.com/v2/project/ppps/version?include_changelog=false").toURL().openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "MrDinoCarlos/PerPlayerProfiles/" + installed + " (https://www.mrdino.es/ppp)");
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            byte[] data;
            try (var stream = connection.getInputStream()) { data = stream.readNBytes(2_000_001); }
            if (data.length > 2_000_000) throw new java.io.IOException("Version response too large");
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonArray();
        } finally { connection.disconnect(); }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event) {
        pending.add(event.getPlayer().getUniqueId());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }

    private void notifyPending() {
        pending.removeIf(id -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) return true;
            if (!plugin.getConfig().getBoolean("updates.notify-admins", true)) return true;
            if (!plugin.isProfileReady(player) || !checked) return false;
            if (available != null && player.hasPermission("perplayerprofiles.admin"))
                plugin.send(player, "update-available", "current", installed, "latest", available.version(), "url", available.url());
            return true;
        });
    }

    public void shutdown() { checkTask.cancel(); notifyTask.cancel(); pending.clear(); }
    static String minecraftVersion(String bukkitVersion) {
        return bukkitVersion.split("-", 2)[0].replaceFirst("\\.build\\..*$", "");
    }
    @FunctionalInterface interface VersionSource { JsonArray read(String installed) throws Exception; }
}
