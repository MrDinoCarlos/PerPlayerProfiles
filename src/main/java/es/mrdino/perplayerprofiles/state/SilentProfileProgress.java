package es.mrdino.perplayerprofiles.state;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** Restores saved progress as a login snapshot, without earning it again. */
final class SilentProfileProgress {
    private static final ClassValue<Bindings> BINDINGS = new ClassValue<>() {
        @Override protected Bindings computeValue(Class<?> playerType) {
            try {
                return new Bindings(playerType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported server implementation for silent profile progress: "
                        + playerType.getName(), failure);
            }
        }
    };

    private SilentProfileProgress() { }

    static Restoration prepare(Player player, ConfigurationSection advancements, List<String> recipes) {
        try {
            return BINDINGS.get(player.getClass()).prepare(player, advancements, recipes);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not prepare saved profile progress for " + player.getUniqueId(), failure);
        }
    }

    static final class Restoration {
        private final Bindings bindings;
        private final Object player;
        private final Object data;
        private final Object book;
        private final List<Change> changes;
        private final Set<Object> recipes;

        private Restoration(Bindings bindings, Object player, Object data, Object book,
                            List<Change> changes, Set<Object> recipes) {
            this.bindings = bindings;
            this.player = player;
            this.data = data;
            this.book = book;
            this.changes = changes;
            this.recipes = recipes;
        }

        void apply() {
            try {
                for (Change change : changes) {
                    // The awardCriteria API grants XP, loot, functions and recipes, and fires
                    // completion events. Loading a character must do none of those things.
                    Set<String> awarded = new HashSet<>(change.bukkitProgress().getAwardedCriteria());
                    for (String criterion : awarded) if (!change.desired().contains(criterion))
                        bindings.revoke.invoke(change.progress(), criterion);
                    for (String criterion : change.desired()) if (!awarded.contains(criterion))
                        bindings.grant.invoke(change.progress(), criterion);
                    bindings.unregister.invoke(data, change.advancement());
                    bindings.register.invoke(data, change.advancement());
                }

                // Rebuild the complete visible tree. A reset packet also silences clients
                // predating the separate showAdvancements flag (including 1.20 and 1.21.4).
                Set<Object> visible = bindings.set(bindings.visible, data);
                visible.clear();
                Set<Object> dirty = bindings.set(bindings.dirty, data);
                dirty.clear();
                for (Change change : changes) {
                    dirty.add(change.advancement());
                    bindings.markVisible.invoke(data, change.advancement());
                }
                bindings.firstPacket.setBoolean(data, true);
                if (bindings.flush.getParameterCount() == 2) bindings.flush.invoke(data, player, false);
                else bindings.flush.invoke(data, player);
                // Vanilla omits an empty update. Still clear the previous character's
                // client tree when the selected character has no visible advancements.
                if (visible.isEmpty()) {
                    Object reset = bindings.reset.getParameterCount() == 5
                            ? bindings.reset.newInstance(true, List.of(), Set.of(), Map.of(), false)
                            : bindings.reset.newInstance(true, List.of(), Set.of(), Map.of());
                    bindings.send.invoke(bindings.connection.get(player), reset);
                }

                bindings.set(bindings.knownRecipes, book).clear();
                bindings.set(bindings.knownRecipes, book).addAll(recipes);
                bindings.set(bindings.highlightRecipes, book).clear();
                // INIT on older servers; replace=true and notification=false on newer
                // servers. Keep the player's recipe book settings and genuine future alerts.
                bindings.sendRecipes.invoke(book, player);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not restore saved profile progress silently", failure);
            }
        }
    }

    private record Change(Object advancement, Object progress, AdvancementProgress bukkitProgress, Set<String> desired) { }

    /** Resolve all implementation details before changing any player state. */
    private static final class Bindings {
        private final Method handle;
        private final Method advancementHandle;
        private final Field progressHandle;
        private final Field progressData;
        private final Method grant;
        private final Method revoke;
        private final Method register;
        private final Method unregister;
        private final Method markVisible;
        private final Field visible;
        private final Field dirty;
        private final Field firstPacket;
        private final Method flush;
        private final Field connection;
        private final Constructor<?> reset;
        private final Method send;
        private final Method recipeBook;
        private final Field knownRecipes;
        private final Field highlightRecipes;
        private final Method sendRecipes;
        private final Method recipeKey;
        private final Method resourceKey;
        private final Object recipeRegistry;

        private Bindings(Class<?> craftPlayer) throws ReflectiveOperationException {
            String craftPackage = craftPlayer.getPackageName().replaceFirst("\\.entity$", "");
            ClassLoader loader = craftPlayer.getClassLoader();
            Class<?> craftProgress = Class.forName(craftPackage + ".advancement.CraftAdvancementProgress", true, loader);
            Class<?> craftAdvancement = Class.forName(craftPackage + ".advancement.CraftAdvancement", true, loader);
            handle = craftPlayer.getMethod("getHandle");
            Class<?> nativePlayer = handle.getReturnType();
            advancementHandle = craftAdvancement.getMethod("getHandle");
            Class<?> nativeAdvancement = advancementHandle.getReturnType();
            progressHandle = field(craftProgress, "handle");
            progressData = field(craftProgress, "playerData");
            Class<?> data = progressData.getType();
            grant = method(progressHandle.getType(), new String[]{"grantProgress", "a"}, String.class);
            revoke = method(progressHandle.getType(), new String[]{"revokeProgress", "b"}, String.class);
            markVisible = method(data, new String[]{"markForVisibilityUpdate", "c"}, nativeAdvancement);
            register = method(data, new String[]{"registerListeners", "d"}, nativeAdvancement);
            unregister = method(data, new String[]{"unregisterListeners", "e"}, nativeAdvancement);
            // The obfuscated names cover the pre-1.20.5 CraftBukkit implementations.
            firstPacket = fieldOfType(data, boolean.class, "isFirstPacket", "m", "l");
            boolean oldHolders = firstPacket.getName().equals("l"); // Obfuscated 1.20.2+ holder/tree layout.
            visible = fieldOfType(data, Set.class, "visible", oldHolders ? "g" : "h");
            dirty = fieldOfType(data, Set.class, "progressChanged", oldHolders ? "h" : "i");
            Method flushMethod;
            try { flushMethod = method(data, new String[]{"flushDirty"}, nativePlayer, boolean.class); }
            catch (NoSuchMethodException older) { flushMethod = method(data, new String[]{"flushDirty", "b"}, nativePlayer); }
            flush = flushMethod;
            connection = java.util.Arrays.stream(nativePlayer.getFields())
                    .filter(f -> Set.of("ServerGamePacketListenerImpl", "PlayerConnection").contains(f.getType().getSimpleName()))
                    .findFirst().orElseThrow(() -> new NoSuchFieldException("Player connection"));
            Class<?> update;
            try { update = Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket", true, loader); }
            catch (ClassNotFoundException older) { update = Class.forName("net.minecraft.network.protocol.game.PacketPlayOutAdvancements", true, loader); }
            reset = java.util.Arrays.stream(update.getConstructors()).filter(c -> {
                Class<?>[] p = c.getParameterTypes();
                return (p.length == 4 || p.length == 5) && p[0] == boolean.class
                        && Collection.class.isAssignableFrom(p[1]) && Set.class.isAssignableFrom(p[2])
                        && Map.class.isAssignableFrom(p[3]) && (p.length == 4 || p[4] == boolean.class);
            }).findFirst().orElseThrow(() -> new NoSuchMethodException("Advancement reset packet"));
            Class<?> packet = update;
            send = java.util.Arrays.stream(connection.getType().getMethods())
                    .filter(m -> (m.getName().equals("send") || m.getName().equals("a")) && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(packet) && m.getReturnType() == void.class)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("Send advancement reset"));
            recipeBook = java.util.Arrays.stream(nativePlayer.getMethods())
                    .filter(m -> m.getParameterCount() == 0
                            && Set.of("ServerRecipeBook", "RecipeBookServer").contains(m.getReturnType().getSimpleName()))
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("Player recipe book"));
            Class<?> book = recipeBook.getReturnType();
            // Known recipes are the public set; highlights are the protected set.
            // Their obfuscated letters moved when display recipes were added in 1.21.2.
            knownRecipes = recipeSet(book, true);
            highlightRecipes = recipeSet(book, false);
            sendRecipes = method(book, new String[]{"sendInitialRecipeBook", "a"}, nativePlayer);

            Method key = Class.forName(craftPackage + ".util.CraftNamespacedKey", true, loader).getMethod("toMinecraft", NamespacedKey.class);
            Method factory = null;
            Object registry = null;
            if (knownRecipes.getGenericType().getTypeName().contains(".resources.ResourceKey<")) {
                // 1.21.2 changed recipe IDs into registry keys. CraftRecipe supplies
                // this conversion until 26.2; newer implementations use ResourceKey.
                try { key = Class.forName(craftPackage + ".inventory.CraftRecipe", true, loader).getMethod("toMinecraft", NamespacedKey.class); }
                catch (NoSuchMethodException newer) {
                    Class<?> keys = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
                    Class<?> registries = Class.forName("net.minecraft.core.registries.Registries", true, loader);
                    Field recipe;
                    try { recipe = registries.getField("RECIPE"); }
                    catch (NoSuchFieldException obfuscated) {
                        recipe = java.util.Arrays.stream(registries.getFields()).filter(f -> {
                            String type = f.getGenericType().getTypeName();
                            return type.contains(".crafting.Recipe<") || type.contains(".crafting.IRecipe<");
                        }).findFirst().orElseThrow(() -> new NoSuchFieldException("Recipe registry key"));
                    }
                    registry = recipe.get(null);
                    factory = method(keys, new String[]{"create", "a"}, keys, key.getReturnType());
                }
            }
            recipeKey = key;
            resourceKey = factory;
            recipeRegistry = registry;
        }

        private Restoration prepare(Player player, ConfigurationSection section, List<String> recipes) throws ReflectiveOperationException {
            Object nativePlayer = handle.invoke(player);
            List<Change> changes = new ArrayList<>();
            Object data = null;
            Iterator<Advancement> iterator = Bukkit.advancementIterator();
            while (iterator.hasNext()) {
                Advancement advancement = iterator.next();
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                if (data == null) data = progressData.get(progress);
                Set<String> desired = new HashSet<>(section == null ? List.of() : section.getStringList(advancement.getKey().toString()));
                desired.retainAll(advancement.getCriteria()); // Ignore criteria removed by a game/datapack update.
                changes.add(new Change(advancementHandle.invoke(advancement), progressHandle.get(progress),
                        progress, desired));
            }
            // A server can disable every advancement. The backing tracker still exists.
            if (data == null) {
                Class<?> type = progressData.getType();
                Method getter = java.util.Arrays.stream(nativePlayer.getClass().getMethods())
                        .filter(m -> m.getParameterCount() == 0 && m.getReturnType() == type).findFirst()
                        .orElseThrow(() -> new NoSuchMethodException("Player advancements"));
                data = getter.invoke(nativePlayer);
            }
            Set<Object> desiredRecipes = new HashSet<>();
            for (String value : recipes) {
                NamespacedKey key = NamespacedKey.fromString(value);
                if (key == null || Bukkit.getRecipe(key) == null) continue;
                Object nativeKey = recipeKey.invoke(null, key);
                desiredRecipes.add(resourceKey == null ? nativeKey : resourceKey.invoke(null, recipeRegistry, nativeKey));
            }
            return new Restoration(this, nativePlayer, data, recipeBook.invoke(nativePlayer), changes, desiredRecipes);
        }

        @SuppressWarnings("unchecked") private Set<Object> set(Field field, Object instance) throws IllegalAccessException {
            return (Set<Object>) field.get(instance);
        }
    }

    private static Field recipeSet(Class<?> type, boolean known) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == Set.class && !Modifier.isStatic(field.getModifiers())
                        && (known ? Modifier.isPublic(field.getModifiers()) : Modifier.isProtected(field.getModifiers()))) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(type.getName() + ": " + (known ? "known recipes" : "highlighted recipes"));
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try {
                Field field = field(owner, name);
                if (field.getType() == type) return field;
            } catch (NoSuchFieldException ignored) { /* Try the old mapping. */ }
        }
        throw new NoSuchFieldException(owner.getName() + ": " + String.join("/", names));
    }

    private static Field field(Class<?> type, String... names) throws NoSuchFieldException {
        for (String name : names) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) { /* Try the superclass or old mapping. */ }
            }
        }
        throw new NoSuchFieldException(type.getName() + ": " + String.join("/", names));
    }

    private static Method method(Class<?> type, String[] names, Class<?>... parameters) throws NoSuchMethodException {
        for (String name : names) {
            try {
                Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { /* Try the old mapping. */ }
        }
        throw new NoSuchMethodException(type.getName() + ": " + String.join("/", names));
    }
}
