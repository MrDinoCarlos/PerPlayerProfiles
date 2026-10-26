package es.mrdino.perplayerprofiles.command;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/** Adds to Paper's outgoing suggestions without replacing commands or the server dispatcher. */
public final class PaperProfileCompletions implements Listener {
    private static final String TREE_EVENT = "com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent";
    private static final String SUGGESTIONS_EVENT = "com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent";
    private final ProfileCommandSuggestions profiles;
    private final Map<UUID, Set<String>> visibleCommands = new ConcurrentHashMap<>();
    private final Field customSuggestions;

    PaperProfileCompletions(ProfileCommandSuggestions profiles) throws ReflectiveOperationException {
        this.profiles = profiles;
        customSuggestions = ArgumentCommandNode.class.getDeclaredField("customSuggestions");
        customSuggestions.setAccessible(true);
    }

    public static void install(JavaPlugin plugin, ProfileCommandSuggestions profiles) {
        try {
            Class<? extends Event> treeEvent = Class.forName(TREE_EVENT).asSubclass(Event.class);
            Class<? extends Event> suggestionsEvent = Class.forName(SUGGESTIONS_EVENT).asSubclass(Event.class);
            PaperProfileCompletions listener = new PaperProfileCompletions(profiles);
            Method root = treeEvent.getMethod("getCommandNode");
            Method buffer = suggestionsEvent.getMethod("getBuffer");
            Method suggestions = suggestionsEvent.getMethod("getSuggestions");
            Method setSuggestions = suggestionsEvent.getMethod("setSuggestions", Suggestions.class);
            EventExecutor tree = (ignored, event) -> {
                try {
                    CommandNode<?> node = (CommandNode<?>) root.invoke(event);
                    listener.prepareTree(((PlayerEvent) event).getPlayer().getUniqueId(), node);
                } catch (ReflectiveOperationException failure) { throw new org.bukkit.event.EventException(failure); }
            };
            EventExecutor outgoing = (ignored, event) -> {
                try {
                    String input = (String) buffer.invoke(event);
                    UUID viewer = ((PlayerEvent) event).getPlayer().getUniqueId();
                    if (!listener.visibleCommands.getOrDefault(viewer, Set.of()).contains(ProfileCommandSuggestions.command(input))) return;
                    Suggestions original = (Suggestions) suggestions.invoke(event);
                    Cancellable cancellation = (Cancellable) event;
                    if (cancellation.isCancelled() && !original.isEmpty()) return;
                    Suggestions merged = merge(input, original, profiles.complete(viewer, input));
                    if (merged != original) {
                        setSuggestions.invoke(event, merged);
                        // Older Paper pre-cancels empty native results. Populate them before other plugins filter them.
                        cancellation.setCancelled(false);
                    }
                } catch (ReflectiveOperationException failure) { throw new org.bukkit.event.EventException(failure); }
            };
            plugin.getServer().getPluginManager().registerEvent(treeEvent, listener, EventPriority.HIGH, tree, plugin, true);
            plugin.getServer().getPluginManager().registerEvent(suggestionsEvent, listener, EventPriority.LOWEST, outgoing, plugin, false);
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        } catch (ClassNotFoundException unavailable) {
            // Older servers and Spigot still use the regular Bukkit TabCompleteEvent.
        } catch (ReflectiveOperationException | LinkageError failure) {
            plugin.getLogger().warning("Could not enable Paper profile suggestions: " + failure.getClass().getSimpleName());
        }
    }

    void prepareTree(UUID viewer, CommandNode<?> root) throws IllegalAccessException {
        Set<String> allowed = ConcurrentHashMap.newKeySet();
        Set<CommandNode<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CommandNode<?> command : root.getChildren()) {
            allowed.add(command.getName());
            if (profiles.includesCommand(command.getName())) requestServerSuggestions(command, visited);
        }
        visibleCommands.put(viewer, Set.copyOf(allowed));
    }

    private void requestServerSuggestions(CommandNode<?> node, Set<CommandNode<?>> visited) throws IllegalAccessException {
        if (!visited.add(node)) return;
        if (node instanceof ArgumentCommandNode<?, ?> argument) {
            String type = argument.getType().getClass().getSimpleName();
            if (type.equals("EntityArgument") || type.equals("ArgumentEntity")
                    || type.equals("GameProfileArgument") || type.equals("ArgumentProfile")) {
                // This is the client tree. Paper serializes unregistered providers as minecraft:ask_server.
                customSuggestions.set(argument, (com.mojang.brigadier.suggestion.SuggestionProvider<Object>)
                        (context, builder) -> builder.buildFuture());
            }
        }
        for (CommandNode<?> child : node.getChildren()) requestServerSuggestions(child, visited);
        if (node.getRedirect() != null) requestServerSuggestions(node.getRedirect(), visited);
    }

    static Suggestions merge(String input, Suggestions original, List<String> names) {
        if (names.isEmpty()) return original;
        int start = input.length();
        while (start > 0 && !Character.isWhitespace(input.charAt(start - 1))) start--;
        StringRange range = StringRange.between(start, input.length());
        List<Suggestion> additional = new ArrayList<>();
        for (String name : names) additional.add(new Suggestion(range, name));
        return Suggestions.merge(input, List.of(original, Suggestions.create(input, additional)));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        visibleCommands.remove(event.getPlayer().getUniqueId());
    }
}
