package es.mrdino.perplayerprofiles.gui;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Player-bound, single-use random action tokens cannot collide with ServerPassword. */
public final class PaperProfileScreen implements NativeProfileScreen, Listener {
    private static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacyAmpersand();
    private final JavaPlugin plugin;
    private final Map<UUID, Binding> bindings = new HashMap<>();

    public PaperProfileScreen(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override public boolean supports(Player player) {
        int protocol = player.getProtocolVersion();
        var via = plugin.getServer().getPluginManager().getPlugin("ViaVersion");
        if (via != null && via.isEnabled()) {
            try {
                ClassLoader loader = via.getClass().getClassLoader();
                Object api = Class.forName("com.viaversion.viaversion.api.Via", true, loader).getMethod("getAPI").invoke(null);
                Class<?> type = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader);
                protocol = (int) type.getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
            } catch (ReflectiveOperationException | LinkageError unknown) { return false; }
        }
        var geyser = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser != null && geyser.isEnabled()) {
            try {
                Class<?> apiType = Class.forName("org.geysermc.geyser.api.GeyserApi", true, geyser.getClass().getClassLoader());
                Object api = apiType.getMethod("api").invoke(null);
                if ((boolean) apiType.getMethod("isBedrockPlayer", UUID.class).invoke(api, player.getUniqueId())) return false;
            } catch (ReflectiveOperationException | LinkageError unknown) { return false; }
        }
        return protocol >= 771;
    }

    @Override public void show(Player player, View view, BiConsumer<String, String> handler) {
        Binding binding = new Binding(UUID.randomUUID().toString(), view, handler, new java.util.concurrent.atomic.AtomicBoolean());
        bindings.put(player.getUniqueId(), binding);
        render(player, binding);
    }

    private void render(Player player, Binding binding) {
        if (plugin instanceof es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin profiles && !profiles.isProfileReady(player)) {
            bindings.remove(player.getUniqueId(), binding);
            return;
        }
        player.showDialog(Dialog.create(builder -> builder.empty().base(createBase(binding.view(), binding.token())).type(createType(binding.view(), binding.token()))));
    }

    static DialogBase createBase(View view) {
        return createBase(view, "preview");
    }

    static DialogBase createBase(View view, String token) {
        var bodies = new ArrayList<DialogBody>();
        if (!view.lines().isEmpty()) bodies.add(DialogBody.plainMessage(TEXT.deserialize(String.join("\n", view.lines())), view.bodyWidth()));
        for (Card card : view.cards()) {
            // Width/height reserve the slot; the client renders the real 3D player-head item.
            bodies.add(DialogBody.item(displayItem(card), DialogBody.plainMessage(createDescription(view, card, token), Math.max(1, view.bodyWidth() - 26)), false, true, 24, 24));
        }
        var base = DialogBase.builder(TEXT.deserialize(view.title())).canCloseWithEscape(view.canClose())
                .pause(false).afterAction(DialogBase.DialogAfterAction.NONE).body(bodies);
        if (view.textEntry()) base.inputs(java.util.List.of(DialogInput.text("value", Component.empty()).labelVisible(false)
                .initial(view.initialText()).maxLength(view.maxLength()).width(view.bodyWidth()).build()));
        return base.build();
    }

    static Component createDescription(View view, Card card, String token) {
        Component description = Component.empty().append(clickable(TEXT.deserialize(String.join("\n", card.lines())), view, token, card.action()));
        if (!card.links().isEmpty()) {
            description = description.append(Component.newline());
            for (int index = 0; index < card.links().size(); index++) {
                if (index > 0) description = description.append(Component.text("    "));
                Button link = card.links().get(index);
                Component label = TEXT.deserialize("[" + link.label() + "&r]");
                label = clickable(label, view, token, link.action());
                if (link.tooltip() != null) label = label.hoverEvent(HoverEvent.showText(TEXT.deserialize(link.tooltip())));
                description = description.append(label);
            }
        }
        return description.hoverEvent(HoverEvent.showText(TEXT.deserialize(String.join("\n", card.tooltip()))));
    }

    private static Component clickable(Component component, View view, String token, String action) {
        int index = view.actionIndex(action);
        return index < 0 ? component : component.clickEvent(ClickEvent.custom(Key.key("perplayerprofiles", token + "/" + index), BinaryTagHolder.binaryTagHolder("{}")));
    }

    private static ItemStack displayItem(Card card) {
        ItemStack item = card.icon().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && !card.tooltip().isEmpty()) {
            meta.displayName(TEXT.deserialize(card.tooltip().getFirst()));
            meta.lore(card.tooltip().subList(1, card.tooltip().size()).stream().map(TEXT::deserialize).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    static DialogType createType(View view, String token) {
        var buttons = new ArrayList<ActionButton>();
        for (int index = 0; index < view.buttons().size(); index++)
            buttons.add(button(view.buttons().get(index), view.buttonWidth(), token, index));
        ActionButton footer = view.footer() == null ? null : button(view.footer(),
                Math.min(300, Math.max(100, view.columns() * view.buttonWidth() + (view.columns() - 1) * 2)), token, view.actions().size() - 1);
        if (buttons.isEmpty()) return footer == null ? DialogType.notice() : DialogType.notice(footer);
        return DialogType.multiAction(buttons, footer, view.columns());
    }

    private static ActionButton button(Button button, int width, String token, int index) {
        return ActionButton.create(TEXT.deserialize(button.label()), button.tooltip() == null ? null : TEXT.deserialize(button.tooltip()),
                width, "NONE".equals(button.action()) ? null : DialogAction.customClick(Key.key("perplayerprofiles", token + "/" + index), null));
    }

    @EventHandler public void onClick(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().namespace().equals("perplayerprofiles")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        Binding binding = bindings.get(player.getUniqueId());
        if (binding == null || !event.getIdentifier().value().startsWith(binding.token() + "/")) return;
        int index;
        try { index = Integer.parseInt(event.getIdentifier().value().substring(binding.token().length() + 1)); }
        catch (NumberFormatException invalid) { return; }
        if (index < 0 || index >= binding.view().actions().size()) return;
        Button selected = binding.view().actions().get(index);
        if (selected == null || selected.action().equals("NONE")) return;
        String value = binding.view().textEntry() && event.getDialogResponseView() != null
                ? event.getDialogResponseView().getText("value") : null;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && bindings.get(player.getUniqueId()) == binding && binding.used().compareAndSet(false, true))
                binding.handler().accept(selected.action(), value);
        });
    }

    @Override public void close(Player player) {
        if (bindings.remove(player.getUniqueId()) != null && player.isOnline()) player.closeInventory();
    }
    @Override public void forget(Player player) { bindings.remove(player.getUniqueId()); }
    @Override public void shutdown() {
        for (UUID id : new ArrayList<>(bindings.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) close(player);
        }
        bindings.clear();
    }
    private record Binding(String token, View view, BiConsumer<String, String> handler, java.util.concurrent.atomic.AtomicBoolean used) { }
}
