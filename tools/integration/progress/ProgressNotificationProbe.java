import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses real server progress, events and outbound packets; never included in runtime JARs. */
public final class ProgressNotificationProbe extends JavaPlugin implements Listener {
    private Object channel;
    private Player player;
    private Object service;
    private Method apply;
    private int completed;
    private int discovered;

    @Override public void onEnable() {
        if (!"127.0.0.1".equals(getServer().getIp()) || !"local-smoke".equals(System.getProperty("perplayerprofiles.integration")))
            throw new IllegalStateException("Only an isolated loopback fixture may run this probe");
        Bukkit.getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskLater(this, () -> {
            try { runProbe(); }
            catch (Throwable failure) { getLogger().log(java.util.logging.Level.SEVERE, "PROGRESS PROBE FAIL", failure); }
            finally { if (!Boolean.getBoolean("progress.probe.keepRunning")) Bukkit.shutdown(); }
        }, 80L);
    }

    @EventHandler public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (event.getPlayer() == player) completed++;
    }

    @EventHandler public void onRecipe(PlayerRecipeDiscoverEvent event) {
        if (event.getPlayer() == player) discovered++;
    }

    private void runProbe() throws Exception {
        player = createPlayer();
        Object plugin = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("PerPlayerProfiles"));
        Class<?> state = Class.forName("es.mrdino.perplayerprofiles.state.PlayerStateService", true, plugin.getClass().getClassLoader());
        service = state.getConstructor().newInstance();
        apply = state.getMethod("apply", Player.class, YamlConfiguration.class);
        YamlConfiguration empty = (YamlConfiguration) state.getMethod("createFreshProfileState", Player.class).invoke(service, player);
        YamlConfiguration complete = new YamlConfiguration(); complete.loadFromString(empty.saveToString());
        for (String id : List.of("story/mine_stone", "story/mine_diamond", "adventure/kill_all_mobs")) {
            Advancement advancement = advancement(id);
            complete.set("advancements." + advancement.getKey(), new ArrayList<>(advancement.getCriteria()));
        }
        complete.set("advancements.minecraft:removed_datapack/old", List.of("removed_criterion"));
        complete.set("recipes", List.of("minecraft:stick", "minecraft:crafting_table", "minecraft:removed_recipe", "INVALID KEY"));
        complete.set("inventory.contents", Arrays.asList(new ItemStack(Material.DIAMOND, 3)));
        complete.set("statistics-typed.MINE_BLOCK.STONE", 42);

        YamlConfiguration partial = new YamlConfiguration(); partial.loadFromString(empty.saveToString());
        String criterion = advancement("adventure/kill_all_mobs").getCriteria().iterator().next();
        partial.set("advancements.minecraft:adventure/kill_all_mobs", List.of(criterion, "removed_criterion"));
        partial.set("recipes", List.of("minecraft:oak_planks"));
        partial.set("statistics-typed.KILL_ENTITY.ZOMBIE", 8);

        // First load, repeated switches, partial criteria, and a character with no tree.
        checkRestore(empty, Set.of(), "initial empty load");
        for (int pass = 0; pass < 3; pass++) {
            checkRestore(complete, Set.of(NamespacedKey.minecraft("stick"), NamespacedKey.minecraft("crafting_table")), "completed character " + pass);
            check(player.getInventory().getItem(0).getAmount() == 3, "Wrong character inventory");
            check(player.getStatistic(Statistic.MINE_BLOCK, Material.STONE) == 42, "Wrong block statistic");
            checkRestore(partial, Set.of(NamespacedKey.minecraft("oak_planks")), "partial character " + pass);
            check(!player.getAdvancementProgress(advancement("adventure/kill_all_mobs")).isDone(), "Partial advancement became complete");
            check(player.getStatistic(Statistic.MINE_BLOCK, Material.STONE) == 0, "Block statistic leaked");
            checkRestore(empty, Set.of(), "empty character " + pass);
        }
        checkRestore(complete, Set.of(NamespacedKey.minecraft("stick"), NamespacedKey.minecraft("crafting_table")), "before reconnect");
        YamlConfiguration saved = (YamlConfiguration) state.getMethod("capture", Player.class).invoke(service, player);
        YamlConfiguration persisted = new YamlConfiguration(); persisted.loadFromString(saved.saveToString());
        player = createPlayer();
        checkRestore(persisted, Set.of(NamespacedKey.minecraft("stick"), NamespacedKey.minecraft("crafting_table")), "reconnect with saved YAML");

        // Restore the empty character, then really earn progress: ordinary alerts/rewards
        // must work again, including the native advancement trigger listeners.
        checkRestore(empty, Set.of(), "before genuine unlocks");
        Advancement challenge = advancement("adventure/kill_all_mobs");
        for (String criterionKey : challenge.getCriteria()) player.getAdvancementProgress(challenge).awardCriteria(criterionKey);
        player.discoverRecipe(NamespacedKey.minecraft("stick"));
        flush();
        List<Object> earned = drain();
        check(advancementNotifications(earned) > 0 && recipeNotifications(earned) > 0, "Genuine unlock notifications were suppressed");
        check(completed > 0 && discovered > 0 && player.getTotalExperience() > 0, "Genuine unlock events/rewards were suppressed");

        checkRestore(empty, Set.of(), "before genuine inventory trigger");
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Class<?> triggers = load("net.minecraft.advancements.triggers.CriteriaTriggers", "net.minecraft.advancements.CriteriaTriggers", "net.minecraft.advancements.CriterionTriggers");
        Object trigger = null;
        for (Field f : triggers.getFields()) if (f.getType().getSimpleName().contains("InventoryChange")) { trigger = f.get(null); break; }
        check(trigger != null, "Inventory criterion trigger missing");
        Method inventoryTrigger = Arrays.stream(trigger.getClass().getMethods()).filter(m -> m.getParameterCount() == 3
                && m.getParameterTypes()[0].isInstance(handle) && m.getParameterTypes()[2].getSimpleName().equals("ItemStack")
                && m.getParameterTypes()[1].getSimpleName().matches("Inventory|PlayerInventory")).findFirst().orElseThrow();
        Method inventory = Arrays.stream(handle.getClass().getMethods()).filter(m -> m.getParameterCount() == 0
                && m.getReturnType() == inventoryTrigger.getParameterTypes()[1]).findFirst().orElseThrow();
        Class<?> craftItem = Class.forName(player.getClass().getPackageName().replaceFirst("\\.entity$", "") + ".inventory.CraftItemStack");
        Object diamond = craftItem.getMethod("asNMSCopy", ItemStack.class).invoke(null, new ItemStack(Material.DIAMOND));
        inventoryTrigger.invoke(trigger, handle, inventory.invoke(handle), diamond);
        check(player.getAdvancementProgress(advancement("story/mine_diamond")).isDone(), "Advancement trigger listeners were not restored");
        flush(); check(advancementNotifications(drain()) > 0, "A genuine inventory advancement did not notify");
        getLogger().info("PROGRESS PROBE PASS: 1st load, 3 round trips, partial/empty progress, inventory/statistics, YAML reconnect, no repeated toasts/XP/events, normal future unlocks and triggers");
    }

    private void checkRestore(YamlConfiguration target, Set<NamespacedKey> recipes, String scenario) throws Exception {
        drain(); completed = 0; discovered = 0;
        apply.invoke(service, player, target);
        flush(); // Catch updates left behind for the next normal server tick too.
        List<Object> packets = drain();
        check(advancementNotifications(packets) == 0 && recipeNotifications(packets) == 0, scenario + ": repeated notifications");
        check(completed == 0 && discovered == 0, scenario + ": repeated completion/discovery events");
        check(player.getTotalExperience() == target.getInt("total-experience", 0), scenario + ": repeated XP rewards");
        check(player.getDiscoveredRecipes().equals(recipes), scenario + ": wrong recipes " + player.getDiscoveredRecipes());
        for (String id : List.of("story/mine_stone", "story/mine_diamond", "adventure/kill_all_mobs")) {
            Advancement advancement = advancement(id);
            Set<String> expected = new HashSet<>(target.getStringList("advancements." + advancement.getKey())); expected.retainAll(advancement.getCriteria());
            check(Set.copyOf(player.getAdvancementProgress(advancement).getAwardedCriteria()).equals(expected), scenario + ": wrong criteria " + id);
        }
        check(packets.stream().anyMatch(this::isAdvancementReset), scenario + ": previous client tree was not reset");
        getLogger().info("SILENT RESTORE PASS: " + scenario + ", advancementPackets=" + packets.stream().filter(this::isAdvancements).count() + ", recipes=" + recipes.size());
    }

    private boolean isAdvancements(Object packet) {
        return Set.of("ClientboundUpdateAdvancementsPacket", "PacketPlayOutAdvancements").contains(packet.getClass().getSimpleName());
    }

    private boolean isAdvancementReset(Object packet) {
        if (!isAdvancements(packet)) return false;
        try {
            for (String method : List.of("shouldReset", "e"))
                try { return (boolean) packet.getClass().getMethod(method).invoke(packet); }
                catch (NoSuchMethodException ignored) { }
            return (boolean) field(packet, "reset", "a");
        }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private int advancementNotifications(List<Object> packets) throws Exception {
        int result = 0;
        for (Object packet : packets) if (isAdvancements(packet) && !isAdvancementReset(packet)) {
            boolean show = true;
            try { show = (boolean) packet.getClass().getMethod("showAdvancements").invoke(packet); } catch (NoSuchMethodException ignored) { }
            if (show) result++;
        }
        return result;
    }

    private int recipeNotifications(List<Object> packets) throws Exception {
        int result = 0;
        for (Object packet : packets) {
            if (packet.getClass().getSimpleName().equals("ClientboundRecipeBookAddPacket")) {
                for (Object entry : (List<?>) packet.getClass().getMethod("entries").invoke(packet))
                    if ((boolean) entry.getClass().getMethod("notification").invoke(entry)) result++;
            } else if (Set.of("ClientboundRecipePacket", "PacketPlayOutRecipes").contains(packet.getClass().getSimpleName())) {
                Object action = field(packet, "state", "a");
                if (((Enum<?>) action).ordinal() == 1) result += ((Collection<?>) field(packet, "recipes", "b")).size();
            }
        }
        return result;
    }

    private void flush() throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object progress = player.getAdvancementProgress(advancement("story/mine_stone"));
        Object data = field(progress, "playerData");
        for (Method method : data.getClass().getMethods()) if ((method.getName().equals("flushDirty") || method.getName().equals("b"))
                && method.getParameterCount() >= 1 && method.getParameterTypes()[0].isInstance(handle)) {
            if (method.getParameterCount() == 1) method.invoke(data, handle);
            else if (method.getParameterCount() == 2 && method.getParameterTypes()[1] == boolean.class) method.invoke(data, handle, true);
        }
    }

    private List<Object> drain() throws Exception {
        channel.getClass().getMethod("runPendingTasks").invoke(channel);
        List<Object> result = new ArrayList<>(); Object packet;
        while ((packet = channel.getClass().getMethod("readOutbound").invoke(channel)) != null) result.add(packet);
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) private Player createPlayer() throws Exception {
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object world = Bukkit.getWorlds().get(0).getClass().getMethod("getHandle").invoke(Bukkit.getWorlds().get(0));
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "ProgressProbe");
        String craft = Bukkit.getServer().getClass().getPackageName();
        Class<?> playerType = Class.forName(craft + ".entity.CraftPlayer").getMethod("getHandle").getReturnType();
        Constructor<?> playerConstructor = Arrays.stream(playerType.getConstructors()).filter(c -> c.getParameterCount() == 3 || c.getParameterCount() == 4).findFirst().orElseThrow();
        Object handle;
        if (playerConstructor.getParameterCount() == 4) {
            Class<?> information = playerConstructor.getParameterTypes()[3];
            Object defaults = Arrays.stream(information.getMethods()).filter(m -> Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 0 && m.getReturnType() == information).findFirst().orElseThrow().invoke(null);
            handle = playerConstructor.newInstance(server, world, profile, defaults);
        } else handle = playerConstructor.newInstance(server, world, profile);
        Class<?> connectionType = load("net.minecraft.network.Connection", "net.minecraft.network.NetworkManager");
        Class<?> flow = connectionType.getConstructors()[0].getParameterTypes()[0];
        Object direction; try { direction = Enum.valueOf((Class) flow, "SERVERBOUND"); } catch (IllegalArgumentException old) { direction = flow.getEnumConstants()[0]; }
        Object connection = connectionType.getConstructor(flow).newInstance(direction);
        Class<?> handlers = Array.newInstance(Class.forName("io.netty.channel.ChannelHandler"), 0).getClass();
        channel = Class.forName("io.netty.channel.embedded.EmbeddedChannel").getConstructor(handlers).newInstance(Array.newInstance(handlers.getComponentType(), 0));
        Arrays.stream(connectionType.getFields()).filter(f -> f.getType().getName().equals("io.netty.channel.Channel")).findFirst().orElseThrow().set(connection, channel);
        connectionType.getField("queueImmunity").setBoolean(connection, true);
        Field connectionField = Arrays.stream(playerType.getFields()).filter(f -> Set.of("ServerGamePacketListenerImpl", "PlayerConnection").contains(f.getType().getSimpleName())).findFirst().orElseThrow();
        Class<?> listenerType = connectionField.getType();
        Constructor<?> listenerConstructor = listenerType.getConstructors()[0];
        Object listener;
        if (listenerConstructor.getParameterCount() == 4) {
            Class<?> cookie = listenerConstructor.getParameterTypes()[3];
            listener = listenerConstructor.newInstance(server, connection, handle, cookie.getMethod("createInitial", profileType, boolean.class).invoke(null, profile, false));
        } else listener = listenerConstructor.newInstance(server, connection, handle);
        connectionField.set(handle, listener);
        return (Player) handle.getClass().getMethod("getBukkitEntity").invoke(handle);
    }

    private Advancement advancement(String id) { return Objects.requireNonNull(Bukkit.getAdvancement(NamespacedKey.minecraft(id)), id); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static Class<?> load(String... names) throws ClassNotFoundException {
        for (String name : names) try { return Class.forName(name); } catch (ClassNotFoundException ignored) { }
        throw new ClassNotFoundException(String.join("/", names));
    }
    private static Object field(Object object, String... names) throws ReflectiveOperationException {
        for (String name : names) for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass())
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(object.getClass().getName() + ": " + String.join("/", names));
    }
}
