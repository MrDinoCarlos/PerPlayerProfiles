package es.mrdino.perplayerprofiles.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ServerPasswordCompatibility {
    private static final String PLUGIN_NAME = "ServerPassword";
    private final JavaPlugin plugin;
    private boolean warnedUnreadableState;

    public ServerPasswordCompatibility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isWaitingForAuth(Player player) {
        Plugin serverPassword = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (serverPassword == null || !serverPassword.isEnabled()) {
            return false;
        }

        Boolean locked = findBooleanState(serverPassword, player,
                List.of("lockdownManager", "lockManager", "lockdown", "lock"),
                List.of("isLocked", "isLockedDown"));
        if (locked != null) {
            return locked;
        }

        Boolean authed = findBooleanState(serverPassword, player,
                List.of("authManager", "auth"),
                List.of("isAuthed", "isAuthenticated", "isLoggedIn", "isLogged"));
        if (authed != null) {
            return !authed;
        }

        warnUnreadableState(serverPassword);
        return true;
    }

    private Boolean findBooleanState(Plugin serverPassword, Player player, List<String> preferredFieldNames, List<String> methodNames) {
        Boolean direct = invokeBooleanMethod(serverPassword, player, methodNames);
        if (direct != null) {
            return direct;
        }

        for (String fieldName : preferredFieldNames) {
            Object fieldValue = readField(serverPassword, fieldName);
            Boolean value = invokeBooleanMethod(fieldValue, player, methodNames);
            if (value != null) {
                return value;
            }
        }

        for (Object fieldValue : readFields(serverPassword)) {
            Boolean value = invokeBooleanMethod(fieldValue, player, methodNames);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Boolean invokeBooleanMethod(Object target, Player player, List<String> methodNames) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (String methodName : methodNames) {
                try {
                    Method method = type.getDeclaredMethod(methodName, Player.class);
                    method.setAccessible(true);
                    Object result = method.invoke(target, player);
                    if (result instanceof Boolean value) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next known ServerPassword API shape.
                }
            }
        }
        return null;
    }

    private Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next superclass or field name.
            }
        }
        return null;
    }

    private List<Object> readFields(Object target) {
        List<Object> values = new ArrayList<>();
        if (target == null) {
            return values;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) {
                        values.add(value);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Ignore fields that cannot be inspected on this server.
                }
            }
        }
        return values;
    }

    private void warnUnreadableState(Plugin serverPassword) {
        if (warnedUnreadableState) {
            return;
        }
        warnedUnreadableState = true;
        plugin.getLogger().warning("ServerPassword is installed, but its authentication state could not be read. Profile loading will wait to avoid opening over authentication. ServerPassword version: " + serverPassword.getDescription().getVersion());
    }
}
