package es.mrdino.perplayerprofiles.command;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Immutable snapshots: Paper can request suggestions away from the server thread. */
public final class ProfileCommandSuggestions {
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), List.of());

    public void publish(Map<String, List<Integer>> targets, Map<UUID, List<String>> viewers, List<String> allNames) {
        Map<String, List<Integer>> commands = new HashMap<>();
        targets.forEach((name, indexes) -> commands.put(normalize(name), List.copyOf(indexes)));
        Map<UUID, List<String>> visible = new HashMap<>();
        viewers.forEach((id, names) -> visible.put(id, List.copyOf(names)));
        snapshot = new Snapshot(Map.copyOf(commands), Map.copyOf(visible), List.copyOf(allNames));
    }

    public boolean includesCommand(String name) {
        return snapshot.targets.containsKey(normalize(name));
    }

    public List<String> complete(UUID viewer, String buffer) {
        Snapshot current = snapshot;
        String[] parts = buffer.replaceFirst("^/", "").split("\\s+", -1);
        if (parts.length < 2) return List.of();
        String command = normalize(parts[0]);
        int argument = parts.length - 2;
        if (!current.targets.getOrDefault(command, List.of()).contains(argument)) return List.of();
        // The second coordinate in /tp <x> <y> <z> is not a second player target.
        if ((command.equals("tp") || command.equals("teleport")) && argument == 1
                && parts[1].matches("[~^].*|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) return List.of();
        String prefix = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        List<String> names = viewer == null ? current.allNames : current.viewers.getOrDefault(viewer, List.of());
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().toList();
    }

    static String command(String buffer) {
        String input = buffer.replaceFirst("^/", "");
        return input.split("\\s+", 2)[0];
    }

    private static String normalize(String label) {
        int colon = label.indexOf(':');
        return label.substring(colon + 1).toLowerCase(Locale.ROOT);
    }

    private record Snapshot(Map<String, List<Integer>> targets, Map<UUID, List<String>> viewers, List<String> allNames) {}
}
