package es.mrdino.perplayerprofiles.gui;

import java.util.List;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** API boundary that keeps modern Paper classes out of legacy server class loading. */
public interface NativeProfileScreen {
    record Card(ItemStack icon, List<String> lines, String action, List<String> tooltip, List<Button> links) {
        public Card(ItemStack icon, List<String> lines) { this(icon, lines, "NONE", lines, List.of()); }
        public Card(ItemStack icon, List<String> lines, String action, List<String> tooltip) { this(icon, lines, action, tooltip, List.of()); }
    }
    record Button(String action, String label, String tooltip) {
        public Button(String action, String label) { this(action, label, null); }
    }
    record View(String title, List<String> lines, List<Card> cards, List<Button> buttons, Button footer,
                int columns, int buttonWidth, int bodyWidth, boolean canClose, boolean textEntry,
                String initialText, int maxLength) {
        /** Body links use the same player-bound token and single-use check as visible buttons. */
        public List<Button> actions() {
            var actions = new java.util.ArrayList<>(buttons);
            for (Card card : cards) actions.addAll(card.links());
            if (footer != null) actions.add(footer);
            return List.copyOf(actions);
        }
        public int actionIndex(String action) {
            if (action.equals("NONE")) return -1;
            List<Button> actions = actions();
            for (int index = 0; index < actions.size(); index++) if (actions.get(index).action().equals(action)) return index;
            return -1;
        }
    }
    boolean supports(Player player);
    void show(Player player, View view, BiConsumer<String, String> handler);
    void close(Player player);
    void forget(Player player);
    void shutdown();
}
