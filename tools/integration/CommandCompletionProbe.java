import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

/** Build as a separate test plugin; never package it in PerPlayerProfiles. */
public final class CommandCompletionProbe extends JavaPlugin {
    @Override public void onEnable() {
        if (!"local-smoke".equals(System.getProperty("perplayerprofiles.integration"))
                || !getServer().getIp().equals("127.0.0.1")) {
            throw new IllegalStateException("This probe requires an isolated loopback fixture");
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                Object map = Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
                checkPaperEvents();
                for (int pass = 0; pass < 2; pass++) {
                    for (String name : List.of("tp", "teleport", "minecraft:tp", "give", "gamemode", "kill", "list", "msg", "tell", "w", "ban", "kick", "ppp")) {
                        Command command = (Command) map.getClass().getMethod("getCommand", String.class).invoke(map, name);
                        if (command == null) throw new AssertionError("Missing command " + name);
                        for (String[] args : List.of(new String[]{""}, new String[]{"@a", ""}))
                            command.tabComplete(Bukkit.getConsoleSender(), name, args, null);
                        if (!name.equals("ppp") && command.getClass().getName().contains("perplayerprofiles"))
                            throw new AssertionError("Native command replaced: " + name);
                    }
                    Command gamemode = (Command) map.getClass().getMethod("getCommand", String.class).invoke(map, "gamemode");
                    if (!gamemode.tabComplete(Bukkit.getConsoleSender(), "gamemode", new String[]{"c"}, null).contains("creative"))
                        throw new AssertionError("Native game mode suggestions were lost");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:list");
                    if (pass == 0) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ppp reload");
                }
                getLogger().info("COMMAND PROBE PASS: native completion, aliases, execution and reload");
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "COMMAND PROBE FAIL", failure);
            } finally {
                Bukkit.shutdown();
            }
        }, 80L);
    }

    private void checkPaperEvents() throws Exception {
        var treeType = Class.forName("com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent");
        var suggestionsType = Class.forName("com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent");
        UUID id = UUID.randomUUID();
        Player viewer = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName", "toString" -> "PPPEventProbe";
                    case "getServer" -> Bukkit.getServer();
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "isOnline" -> true;
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
        Object plugin = Bukkit.getPluginManager().getPlugin("PerPlayerProfiles");
        var field = plugin.getClass().getDeclaredField("profileCommandSuggestions"); field.setAccessible(true);
        Object profiles = field.get(plugin);
        profiles.getClass().getMethod("publish", Map.class, Map.class, List.class).invoke(profiles,
                Map.of("tp", List.of(0)), Map.of(id, List.of("AlphaHero")), List.of("AlphaHero"));
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object commands = server.getClass().getMethod("getCommands").invoke(server);
        var dispatcher = (com.mojang.brigadier.CommandDispatcher<?>) commands.getClass().getMethod("getDispatcher").invoke(commands);
        CommandNode<?> nativeRoot = dispatcher.getRoot().getChild("tp");
        ArgumentCommandNode<?, ?> nativeTarget = findEntity(nativeRoot, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        if (nativeTarget == null) throw new AssertionError("No native entity argument found");
        Object originalProvider = nativeTarget.getCustomSuggestions();
        RootCommandNode<Object> clientRoot = new RootCommandNode<>();
        clientRoot.addChild(LiteralArgumentBuilder.<Object>literal("tp")
                .then(RequiredArgumentBuilder.<Object, Object>argument("target", (ArgumentType<Object>) nativeTarget.getType())).build());
        Event tree = (Event) treeType.getConstructor(Player.class, RootCommandNode.class, boolean.class).newInstance(viewer, clientRoot, false);
        Bukkit.getPluginManager().callEvent(tree);
        var clientTarget = (ArgumentCommandNode<?, ?>) clientRoot.getChild("tp").getChild("target");
        if (clientTarget.getCustomSuggestions() == null || nativeTarget.getCustomSuggestions() != originalProvider)
            throw new AssertionError("Client suggestion provider changed the server tree or was missing");
        Event event = (Event) suggestionsType.getConstructor(Player.class, Suggestions.class, String.class)
                .newInstance(viewer, Suggestions.empty().join(), "/tp Al");
        ((Cancellable) event).setCancelled(true); // Older Paper pre-cancels empty native suggestions.
        Bukkit.getPluginManager().callEvent(event);
        Suggestions result = (Suggestions) suggestionsType.getMethod("getSuggestions").invoke(event);
        if (((Cancellable) event).isCancelled() || result.getList().stream().noneMatch(s -> s.getText().equals("AlphaHero")))
            throw new AssertionError("Paper did not populate the empty native result with the visible profile");
        var filter = new org.bukkit.event.Listener() {};
        Bukkit.getPluginManager().registerEvent(suggestionsType.asSubclass(Event.class), filter,
                org.bukkit.event.EventPriority.HIGHEST, (ignored, outgoing) -> ((Cancellable) outgoing).setCancelled(true), this, false);
        try {
            Event filtered = (Event) suggestionsType.getConstructor(Player.class, Suggestions.class, String.class)
                    .newInstance(viewer, Suggestions.empty().join(), "/tp Al");
            Bukkit.getPluginManager().callEvent(filtered);
            if (!((Cancellable) filtered).isCancelled()) throw new AssertionError("Another plugin's suggestion filter was bypassed");
        } finally { org.bukkit.event.HandlerList.unregisterAll(filter); }
        getLogger().info("COMMAND EVENT PROBE PASS: native client argument, unchanged server tree and empty-result profile completion");
    }

    private ArgumentCommandNode<?, ?> findEntity(CommandNode<?> node, java.util.Set<CommandNode<?>> visited) {
        if (!visited.add(node)) return null;
        if (node instanceof ArgumentCommandNode<?, ?> argument && argument.getType().getClass().getSimpleName().equals("EntityArgument")) return argument;
        for (CommandNode<?> child : node.getChildren()) {
            var found = findEntity(child, visited); if (found != null) return found;
        }
        return node.getRedirect() == null ? null : findEntity(node.getRedirect(), visited);
    }
}
