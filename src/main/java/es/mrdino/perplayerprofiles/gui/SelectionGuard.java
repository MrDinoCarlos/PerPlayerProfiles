package es.mrdino.perplayerprofiles.gui;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import java.util.Locale;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** A mandatory selector cannot be bypassed by closing a screen or sending a command. */
public final class SelectionGuard implements Listener {
    private static final Set<String> COMMANDS = Set.of("profiles", "ppp", "pprofile", "perfil", "perfiles");
    private final PerPlayerProfilesPlugin plugin;
    public SelectionGuard(PerPlayerProfilesPlugin plugin) { this.plugin = plugin; }
    private boolean selecting(Player player) { return plugin.getProfileGuiManager().isSelectionRequired(player); }

    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent event) {
        if (selecting(event.getPlayer()) && event.getTo() != null && (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY() || event.getFrom().getZ() != event.getTo().getZ()))
            event.setTo(event.getFrom());
    }
    @EventHandler(ignoreCancelled = true) public void interact(PlayerInteractEvent event) { if (selecting(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void interactEntity(PlayerInteractEntityEvent event) { if (selecting(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void drop(PlayerDropItemEvent event) { if (selecting(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void pickup(EntityPickupItemEvent event) { if (event.getEntity() instanceof Player player && selecting(player)) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void damage(EntityDamageEvent event) { if (event.getEntity() instanceof Player player && selecting(player)) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void attack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && selecting(player)) event.setCancelled(true);
        if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player && selecting(player)) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void breakBlock(BlockBreakEvent event) { if (selecting(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void placeBlock(BlockPlaceEvent event) { if (selecting(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void inventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && selecting(player) && !(event.getInventory().getHolder() instanceof GuiHolder)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true) public void command(PlayerCommandPreprocessEvent event) {
        if (!selecting(event.getPlayer())) return;
        String command = event.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String[] parts = command.split(":", 2);
        boolean ownNamespace = parts.length == 1 || parts[0].equals("perplayerprofiles");
        if (ownNamespace && COMMANDS.contains(parts[parts.length - 1])) return;
        event.setCancelled(true);
        plugin.send(event.getPlayer(), "screen-selection-required");
    }
}
