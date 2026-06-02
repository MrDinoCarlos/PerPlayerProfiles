package es.mrdino.perplayerprofiles.gui;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import es.mrdino.perplayerprofiles.security.PasswordHasher;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ProfileGuiManager implements Listener {
    private static final int[] PROFILE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] ICON_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] PIN_DISPLAY_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private static final int[] PIN_DIGIT_SLOTS = {49, 21, 22, 23, 30, 31, 32, 39, 40, 41};

    private final PerPlayerProfilesPlugin plugin;
    private final Map<UUID, PendingInput> pendingInputs = new HashMap<>();
    private final Map<UUID, TimeLimitDraft> timeLimitDrafts = new HashMap<>();
    private final Set<UUID> requiredSelections = new HashSet<>();

    public ProfileGuiManager(PerPlayerProfilesPlugin plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player player) {
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.MAIN, null), 27, title("gui-main-title"));
        renderMain(player, inventory);
        player.openInventory(inventory);
    }

    public void requireProfileSelection(Player player) {
        requiredSelections.add(player.getUniqueId());
    }

    public void clearRequiredProfileSelection(Player player) {
        requiredSelections.remove(player.getUniqueId());
    }

    public void refreshOpenProfileMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder holder && holder.getType() == GuiType.MAIN) {
                refreshMainLore(player, player.getOpenInventory().getTopInventory());
            }
        }
    }

    private void refreshMainLore(Player player, Inventory inventory) {
        AccountData account = plugin.loadAccount(player.getUniqueId());
        String active = plugin.getActiveProfile(player);
        int index = 0;
        for (ProfileMeta profile : account.getProfiles().values()) {
            if (index >= PROFILE_SLOTS.length) {
                break;
            }
            ItemStack item = inventory.getItem(PROFILE_SLOTS[index++]);
            if (item == null) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            meta.setLore(color(profileLore(player, profile, active)));
            item.setItemMeta(meta);
        }
    }

    private void renderMain(Player player, Inventory inventory) {
        fill(inventory);
        AccountData account = plugin.loadAccount(player.getUniqueId());
        String active = plugin.getActiveProfile(player);
        int index = 0;
        for (ProfileMeta profile : account.getProfiles().values()) {
            if (index >= PROFILE_SLOTS.length) {
                break;
            }
            inventory.setItem(PROFILE_SLOTS[index++], profileItem(player, profile, active));
        }
        inventory.setItem(21, head("create", text("gui-create-name"), lore("gui-create-lore")));
    }

    public void openProfileSettings(Player player, String profileName) {
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.PROFILE_SETTINGS, profile.getName()), 27, title("gui-profile-title", "profile", profile.getName()));
        fill(inventory);
        inventory.setItem(10, ItemFactory.item(Material.ENDER_PEARL, text("gui-use-name"), lore("gui-use-lore")));
        if (profile.hasPassword()) {
            inventory.setItem(11, head("locked", text("gui-change-password-name"), lore("gui-change-password-lore")));
            inventory.setItem(12, head("unlocked", text("gui-unlock-name"), lore("gui-unlock-lore")));
        } else {
            inventory.setItem(12, head("locked", text("gui-lock-name"), lore("gui-lock-lore")));
        }
        inventory.setItem(13, profileIcon(profile, text("gui-icon-name"), lore("gui-icon-lore")));
        inventory.setItem(14, ItemFactory.item(Material.CLOCK, text("gui-limit-name"), profileLimitLore(player, profile)));
        inventory.setItem(15, ItemFactory.item(Material.NAME_TAG, text("gui-rename-name"), lore("gui-rename-lore")));
        if (plugin.isMasterProfile(player, profile.getName())) {
            inventory.setItem(16, head("settings", text("gui-master-password-name"), lore("gui-master-password-lore")));
        } else {
            inventory.setItem(16, head("delete", text("gui-delete-name"), lore("gui-delete-lore")));
        }
        inventory.setItem(22, ItemFactory.item(Material.ARROW, text("gui-back-name"), lore("gui-back-lore")));
        player.openInventory(inventory);
    }

    public void openIconPicker(Player player, String profileName) {
        if (!canEditProfileBasics(player, profileName)) {
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.ICON_PICKER, profile.getName()), 27, title("gui-icons-title", "profile", profile.getName()));
        fill(inventory);
        List<String> iconKeys = iconKeys();
        for (int i = 0; i < Math.min(ICON_SLOTS.length, iconKeys.size()); i++) {
            String iconKey = iconKeys.get(i);
            inventory.setItem(ICON_SLOTS[i], iconChoiceItem(iconKey, iconKey.equals(resolveIconKey(profile))));
        }
        inventory.setItem(22, ItemFactory.item(Material.ARROW, text("gui-back-name"), lore("gui-back-lore")));
        player.openInventory(inventory);
    }

    public void openPasswordInput(Player player, PendingInput input, String messageKey, Object... replacements) {
        pendingInputs.put(player.getUniqueId(), input);
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.PASSWORD_INPUT, input.getProfileName()), 54, title(messageKey, replacements));
        fill(inventory);
        for (int i = 0; i < PIN_DISPLAY_SLOTS.length; i++) {
            boolean filled = i < input.getValue().length();
            inventory.setItem(PIN_DISPLAY_SLOTS[i], ItemFactory.item(
                    filled ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE,
                    filled ? "&a*" : "&7-",
                    List.of()));
        }
        for (int digit = 0; digit <= 9; digit++) {
            inventory.setItem(PIN_DIGIT_SLOTS[digit], pinDigitItem(digit));
        }
        inventory.setItem(45, ItemFactory.item(Material.BARRIER, text("gui-password-cancel-name"), lore("gui-password-cancel-lore")));
        inventory.setItem(47, ItemFactory.item(Material.REDSTONE, text("gui-password-backspace-name"), lore("gui-password-backspace-lore")));
        inventory.setItem(51, ItemFactory.item(Material.NAME_TAG,
                text("gui-password-display-name", "length", input.getValue().length(), "min", plugin.getMinPinLength(), "max", plugin.getMaxPinLength()),
                List.of(maskPassword(input.getValue()))));
        inventory.setItem(53, head("confirm", text("gui-password-confirm-name"), lore("gui-password-confirm-lore")));
        player.openInventory(inventory);
    }

    public void openTimeLimitEditor(Player player, String profileName) {
        if (!canEditProfileTime(player)) {
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            pendingInputs.remove(player.getUniqueId());
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        TimeLimitDraft draft = timeLimitDrafts.get(player.getUniqueId());
        if (draft == null || !draft.getProfileName().equals(profileName)) {
            draft = new TimeLimitDraft(profileName, profile.getMaxPlayMillis() / 60_000L, profile.getRenewMillis() / 3_600_000L);
            timeLimitDrafts.put(player.getUniqueId(), draft);
        }
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.TIME_LIMIT, profileName), 27, title("gui-time-title", "profile", profile.getName()));
        fill(inventory);
        inventory.setItem(9, head("minus", text("gui-time-minus-30-name"), lore("gui-time-minus-lore")));
        inventory.setItem(10, head("minus", text("gui-time-minus-5-name"), lore("gui-time-minus-lore")));
        inventory.setItem(11, ItemFactory.item(Material.CLOCK, text("gui-time-max-name", "minutes", draft.getMaxMinutes()), lore("gui-time-max-lore")));
        inventory.setItem(12, head("create", text("gui-time-plus-5-name"), lore("gui-time-plus-lore")));
        inventory.setItem(13, head("create", text("gui-time-plus-30-name"), lore("gui-time-plus-lore")));
        inventory.setItem(14, head("minus", text("gui-time-renew-minus-1-name"), lore("gui-time-minus-lore")));
        inventory.setItem(15, head("renew", text("gui-time-renew-name", "hours", draft.getRenewHours()), lore("gui-time-renew-lore")));
        inventory.setItem(16, head("create", text("gui-time-renew-plus-1-name"), lore("gui-time-plus-lore")));
        inventory.setItem(17, head("create", text("gui-time-renew-plus-24-name"), lore("gui-time-plus-lore")));
        inventory.setItem(4, ItemFactory.item(Material.REDSTONE_BLOCK, text("gui-time-reset-name"), lore("gui-time-reset-lore")));
        inventory.setItem(21, ItemFactory.item(Material.BARRIER, text("gui-password-cancel-name"), lore("gui-password-cancel-lore")));
        inventory.setItem(22, ItemFactory.item(Material.ARROW, text("gui-back-name"), lore("gui-back-lore")));
        inventory.setItem(23, head("confirm", text("gui-time-confirm-name"), lore("gui-time-confirm-lore")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) {
            return;
        }

        if (holder.getType() == GuiType.MAIN) {
            handleMainClick(player, event.getSlot(), event.getClick());
        } else if (holder.getType() == GuiType.PROFILE_SETTINGS) {
            handleSettingsClick(player, holder.getProfileName(), event.getSlot());
        } else if (holder.getType() == GuiType.ICON_PICKER) {
            handleIconClick(player, holder.getProfileName(), event.getSlot());
        } else if (holder.getType() == GuiType.PASSWORD_INPUT) {
            handlePasswordClick(player, event.getSlot());
        } else if (holder.getType() == GuiType.TIME_LIMIT) {
            handleTimeLimitClick(player, holder.getProfileName(), event.getSlot());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !requiredSelections.contains(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !requiredSelections.contains(player.getUniqueId())) {
                return;
            }
            Inventory openInventory = player.getOpenInventory().getTopInventory();
            if (openInventory.getHolder() instanceof GuiHolder) {
                return;
            }
            openMain(player);
        }, 1L);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        PendingInput input = pendingInputs.remove(event.getPlayer().getUniqueId());
        if (input == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleTextInput(event.getPlayer(), input, message));
    }

    private void handleMainClick(Player player, int slot, ClickType clickType) {
        if (slot == 21) {
            startCreateProfile(player);
            return;
        }
        String profileName = profileAtSlot(player, slot);
        if (profileName == null) {
            return;
        }
        if (clickType.isRightClick()) {
            openProfileSettings(player, profileName);
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile != null && profile.hasPassword()) {
            askPassword(player, new PendingInput(PendingInputType.SWITCH_PASSWORD, profileName), "prompt-profile-password", "profile", profileName);
        } else {
            plugin.switchProfile(player, profileName);
        }
    }

    private void handleSettingsClick(Player player, String profileName, int slot) {
        if (slot == 10) {
            ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
            if (profile != null && profile.hasPassword()) {
                askPassword(player, new PendingInput(PendingInputType.SWITCH_PASSWORD, profileName), "prompt-profile-password", "profile", profileName);
            } else {
                plugin.switchProfile(player, profileName);
            }
            return;
        }
        if (slot == 11) {
            ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
            if (profile == null) {
                plugin.send(player, "profile-not-found");
            } else if (profile.hasPassword()) {
                requireProfileBasicEdit(player, profileName, PendingInputType.MASTER_CHANGE_PROFILE_PASSWORD);
            }
            return;
        }
        if (slot == 12) {
            ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
            if (profile == null) {
                plugin.send(player, "profile-not-found");
            } else if (!profile.hasPassword()) {
                requireProfileBasicEdit(player, profileName, PendingInputType.MASTER_LOCK_PROFILE);
            } else {
                requireProfileBasicEdit(player, profileName, PendingInputType.MASTER_UNLOCK_PROFILE);
            }
            return;
        }
        if (slot == 13) {
            requireProfileBasicEdit(player, profileName, PendingInputType.MASTER_PICK_ICON);
            return;
        }
        if (slot == 14) {
            requireMasterEdit(player, profileName, PendingInputType.MASTER_SET_LIMIT);
            return;
        }
        if (slot == 15) {
            requireProfileBasicEdit(player, profileName, PendingInputType.MASTER_RENAME_PROFILE);
            return;
        }
        if (slot == 16) {
            if (plugin.isMasterProfile(player, profileName)) {
                requireMasterEdit(player, null, PendingInputType.MASTER_SET_MASTER_PASSWORD);
            } else {
                requireMasterEdit(player, profileName, PendingInputType.MASTER_DELETE_PROFILE);
            }
            return;
        }
        if (slot == 22) {
            openMain(player);
        }
    }

    private void handleIconClick(Player player, String profileName, int slot) {
        if (slot == 22) {
            openProfileSettings(player, profileName);
            return;
        }
        int index = -1;
        for (int i = 0; i < ICON_SLOTS.length; i++) {
            if (ICON_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        if (!canEditProfileBasics(player, profileName)) {
            return;
        }
        List<String> keys = iconKeys();
        if (index >= keys.size()) {
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        profile.setIconKey(keys.get(index));
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-icon-updated", "profile", profile.getName(), "icon", iconDisplayName(keys.get(index)));
        openProfileSettings(player, profileName);
    }

    private void handleTimeLimitClick(Player player, String profileName, int slot) {
        TimeLimitDraft draft = timeLimitDrafts.get(player.getUniqueId());
        if (draft == null || !draft.getProfileName().equals(profileName)) {
            openTimeLimitEditor(player, profileName);
            return;
        }
        switch (slot) {
            case 9 -> draft.addMaxMinutes(-30L);
            case 10 -> draft.addMaxMinutes(-5L);
            case 12 -> draft.addMaxMinutes(5L);
            case 13 -> draft.addMaxMinutes(30L);
            case 14 -> draft.addRenewHours(-1L);
            case 16 -> draft.addRenewHours(1L);
            case 17 -> draft.addRenewHours(24L);
            case 4 -> {
                resetProfileTimeUsage(player, profileName);
                return;
            }
            case 21 -> {
                timeLimitDrafts.remove(player.getUniqueId());
                plugin.send(player, "prompt-cancelled");
                openProfileSettings(player, profileName);
                return;
            }
            case 22 -> {
                openProfileSettings(player, profileName);
                return;
            }
            case 23 -> {
                applyTimeLimit(player, profileName, draft);
                return;
            }
            default -> {
                return;
            }
        }
        openTimeLimitEditor(player, profileName);
    }

    private void resetProfileTimeUsage(Player player, String profileName) {
        if (!canEditProfileTime(player)) {
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        plugin.saveActiveProfile(player);
        profile.resetTimeUsage();
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "time-usage-reset", "profile", profile.getName());
        openTimeLimitEditor(player, profileName);
    }

    private void handlePasswordClick(Player player, int slot) {
        PendingInput input = pendingInputs.get(player.getUniqueId());
        if (input == null) {
            player.closeInventory();
            return;
        }
        int digit = digitFromSlot(slot);
        if (digit >= 0) {
            if (input.getValue().length() >= plugin.getMaxPinLength()) {
                showPinError(player, input, "pin-too-long", "max", plugin.getMaxPinLength());
                return;
            }
            openPasswordInput(player, input.withValue(input.getValue() + digit), passwordTitleKey(input), "profile", input.getProfileName() == null ? "" : input.getProfileName());
            return;
        }
        if (slot == 45) {
            pendingInputs.remove(player.getUniqueId());
            plugin.send(player, "prompt-cancelled");
            openMain(player);
            return;
        }
        if (slot == 47) {
            String value = input.getValue();
            if (!value.isEmpty()) {
                value = value.substring(0, value.length() - 1);
            }
            openPasswordInput(player, input.withValue(value), passwordTitleKey(input), "profile", input.getProfileName() == null ? "" : input.getProfileName());
            return;
        }
        if (slot == 53) {
            if (!isValidPasswordValue(input.getValue())) {
                showPinError(player, input, pinValidationErrorKey(input.getValue()), "min", plugin.getMinPinLength(), "max", plugin.getMaxPinLength());
                return;
            }
            handlePasswordInput(player, input, input.getValue());
        }
    }

    private void handleTextInput(Player player, PendingInput input, String message) {
        if (message.equalsIgnoreCase("cancel")) {
            plugin.send(player, "prompt-cancelled");
            openMain(player);
            return;
        }
        switch (input.getType()) {
            case CREATE_PROFILE -> createProfile(player, message);
            case RENAME_PROFILE -> renameProfile(player, input.getProfileName(), message);
            case SET_LIMIT -> setLimit(player, input.getProfileName(), message);
            default -> openMain(player);
        }
    }

    private void handlePasswordInput(Player player, PendingInput input, String password) {
        switch (input.getType()) {
            case SWITCH_PASSWORD -> switchWithPassword(player, input.getProfileName(), password);
            case LOCK_PROFILE -> lockProfile(player, input.getProfileName(), password);
            case CHANGE_PROFILE_PASSWORD_CURRENT -> changeProfilePasswordAfterCurrent(player, input.getProfileName(), password);
            case CHANGE_PROFILE_PASSWORD_NEW -> lockProfile(player, input.getProfileName(), password);
            case UNLOCK_PROFILE -> unlockProfile(player, input.getProfileName(), password);
            case SET_MASTER_PASSWORD -> setMasterPassword(player, password);
            case MASTER_CREATE_PROFILE -> afterMasterPassword(player, password, () -> askText(player, new PendingInput(PendingInputType.CREATE_PROFILE, null), "prompt-create-profile"));
            case MASTER_RENAME_PROFILE -> afterMasterPassword(player, password, () -> askText(player, new PendingInput(PendingInputType.RENAME_PROFILE, input.getProfileName()), "prompt-rename-profile", "profile", input.getProfileName()));
            case MASTER_LOCK_PROFILE -> afterMasterPassword(player, password, () -> askPassword(player, new PendingInput(PendingInputType.LOCK_PROFILE, input.getProfileName()), "prompt-new-profile-password", "profile", input.getProfileName()));
            case MASTER_CHANGE_PROFILE_PASSWORD -> afterMasterPassword(player, password, () -> askPassword(player, new PendingInput(PendingInputType.LOCK_PROFILE, input.getProfileName()), "prompt-new-profile-password", "profile", input.getProfileName()));
            case MASTER_UNLOCK_PROFILE -> afterMasterPassword(player, password, () -> unlockProfileByMaster(player, input.getProfileName()));
            case MASTER_DELETE_PROFILE -> afterMasterPassword(player, password, () -> deleteProfile(player, input.getProfileName()));
            case MASTER_SET_MASTER_PASSWORD -> afterMasterPassword(player, password, () -> askPassword(player, new PendingInput(PendingInputType.SET_MASTER_PASSWORD, null), "prompt-master-password"));
            case MASTER_SET_LIMIT -> afterMasterPassword(player, password, () -> openTimeLimitEditor(player, input.getProfileName()));
            case MASTER_PICK_ICON -> afterMasterPassword(player, password, () -> openIconPicker(player, input.getProfileName()));
            default -> openMain(player);
        }
    }

    private void createProfile(Player player, String rawName) {
        String name = normalizeProfileName(rawName);
        if (name == null) {
            plugin.send(player, "invalid-profile-name");
            openMain(player);
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (account.hasProfile(name)) {
            plugin.send(player, "profile-exists");
            openMain(player);
            return;
        }
        if (account.getProfiles().size() >= plugin.getMaxProfilesPerAccount()) {
            plugin.send(player, "max-profiles", "max", plugin.getMaxProfilesPerAccount());
            openMain(player);
            return;
        }
        account.putProfile(new ProfileMeta(name));
        account.getProfile(name).setIconKey(defaultIconKey());
        account.sortMasterFirst(plugin.getMasterProfileName());
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-created", "profile", name);
        openProfileSettings(player, name);
    }

    private void renameProfile(Player player, String oldName, String rawName) {
        String newName = normalizeProfileName(rawName);
        if (newName == null) {
            plugin.send(player, "invalid-profile-name");
            openProfileSettings(player, oldName);
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!account.hasProfile(oldName)) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        if (!AccountData.normalizeName(oldName).equals(AccountData.normalizeName(newName)) && account.hasProfile(newName)) {
            plugin.send(player, "profile-exists");
            openProfileSettings(player, oldName);
            return;
        }
        ProfileMeta profile = account.removeProfile(oldName);
        profile.setName(newName);
        account.putProfile(profile);
        account.sortMasterFirst(plugin.getMasterProfileName());
        plugin.renameProfileState(player.getUniqueId(), oldName, newName);
        plugin.updateActiveProfileName(player, oldName, newName);
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-renamed", "old", oldName, "new", newName);
        openProfileSettings(player, newName);
    }

    private void switchWithPassword(Player player, String profileName, String password) {
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        if (!canUseProfilePin(player, profile, password)) {
            showPinError(player, pendingInputs.get(player.getUniqueId()), "profile-password-wrong");
            return;
        }
        pendingInputs.remove(player.getUniqueId());
        plugin.switchProfile(player, profileName);
    }

    private void lockProfile(Player player, String profileName, String password) {
        if (!isValidPasswordValue(password)) {
            showPinError(player, pendingInputs.get(player.getUniqueId()), pinValidationErrorKey(password), "min", plugin.getMinPinLength(), "max", plugin.getMaxPinLength());
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            pendingInputs.remove(player.getUniqueId());
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        profile.setPasswordHash(PasswordHasher.hash(password));
        plugin.saveAccount(player.getUniqueId());
        pendingInputs.remove(player.getUniqueId());
        plugin.send(player, "profile-password-updated", "profile", profile.getName());
        openProfileSettings(player, profileName);
    }

    private void unlockProfile(Player player, String profileName, String password) {
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            pendingInputs.remove(player.getUniqueId());
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        if (!PasswordHasher.verify(password, profile.getPasswordHash())) {
            showPinError(player, pendingInputs.get(player.getUniqueId()), "password-wrong");
            return;
        }
        profile.setPasswordHash(null);
        plugin.saveAccount(player.getUniqueId());
        pendingInputs.remove(player.getUniqueId());
        plugin.send(player, "profile-unlocked", "profile", profile.getName());
        openProfileSettings(player, profileName);
    }

    private void changeProfilePasswordAfterCurrent(Player player, String profileName, String password) {
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            pendingInputs.remove(player.getUniqueId());
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        if (!PasswordHasher.verify(password, profile.getPasswordHash())) {
            showPinError(player, pendingInputs.get(player.getUniqueId()), "password-wrong");
            return;
        }
        pendingInputs.remove(player.getUniqueId());
        askPassword(player, new PendingInput(PendingInputType.CHANGE_PROFILE_PASSWORD_NEW, profileName), "prompt-new-profile-password", "profile", profileName);
    }

    private void unlockProfileByMaster(Player player, String profileName) {
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        profile.setPasswordHash(null);
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-unlocked", "profile", profile.getName());
        openProfileSettings(player, profileName);
    }

    private void deleteProfile(Player player, String profileName) {
        if (plugin.isMasterProfile(player, profileName)) {
            plugin.send(player, "profile-master-delete");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        ProfileMeta removed = account.removeProfile(profileName);
        if (removed == null) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        plugin.deleteProfileState(player.getUniqueId(), profileName);
        plugin.saveAccount(player.getUniqueId());
        if (plugin.getActiveProfile(player).equals(profileName)) {
            plugin.switchProfile(player, plugin.getMasterProfileName(player));
        }
        plugin.send(player, "profile-deleted", "profile", profileName);
        openMain(player);
    }

    private void setMasterPassword(Player player, String password) {
        if (!isValidPasswordValue(password)) {
            showPinError(player, pendingInputs.get(player.getUniqueId()), pinValidationErrorKey(password), "min", plugin.getMinPinLength(), "max", plugin.getMaxPinLength());
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        account.setMasterPasswordHash(PasswordHasher.hash(password));
        plugin.saveAccount(player.getUniqueId());
        pendingInputs.remove(player.getUniqueId());
        plugin.send(player, "master-password-updated");
        openMain(player);
    }

    private void setLimit(Player player, String profileName, String message) {
        String[] parts = message.split("\\s+");
        if (parts.length < 2) {
            plugin.send(player, "usage-limit");
            openProfileSettings(player, profileName);
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        ProfileMeta profile = account.getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        try {
            long maxMinutes = Long.parseLong(parts[0]);
            long renewHours = Long.parseLong(parts[1]);
            profile.setTimeLimit(maxMinutes * 60_000L, renewHours * 3_600_000L);
            plugin.saveAccount(player.getUniqueId());
            plugin.send(player, "limit-applied", "profile", profile.getName(), "minutes", maxMinutes, "hours", renewHours);
            openProfileSettings(player, profileName);
        } catch (NumberFormatException e) {
            plugin.send(player, "time-must-be-numbers");
            openProfileSettings(player, profileName);
        }
    }

    private void applyTimeLimit(Player player, String profileName, TimeLimitDraft draft) {
        if (!canEditProfileTime(player)) {
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        if (draft.getMaxMinutes() <= 0L || draft.getRenewHours() <= 0L) {
            profile.setTimeLimit(0L, 0L);
            plugin.saveAccount(player.getUniqueId());
            timeLimitDrafts.remove(player.getUniqueId());
            plugin.send(player, "time-limit-disabled", "profile", profile.getName());
            openProfileSettings(player, profileName);
            return;
        }
        profile.setTimeLimit(draft.getMaxMinutes() * 60_000L, draft.getRenewHours() * 3_600_000L);
        plugin.saveAccount(player.getUniqueId());
        timeLimitDrafts.remove(player.getUniqueId());
        plugin.send(player, "limit-applied", "profile", profile.getName(), "minutes", draft.getMaxMinutes(), "hours", draft.getRenewHours());
        openProfileSettings(player, profileName);
    }

    private void ask(Player player, PendingInput input, String messageKey, Object... replacements) {
        askText(player, input, messageKey, replacements);
    }

    private void askText(Player player, PendingInput input, String messageKey, Object... replacements) {
        pendingInputs.put(player.getUniqueId(), input);
        player.closeInventory();
        plugin.send(player, messageKey, replacements);
        plugin.send(player, "prompt-cancel");
    }

    private void askPassword(Player player, PendingInput input, String messageKey, Object... replacements) {
        openPasswordInput(player, input, messageKey, replacements);
    }

    private void requireMasterEdit(Player player, String profileName, PendingInputType action) {
        if (!canEditNow(player)) {
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (account.hasMasterPassword()) {
            askPassword(player, new PendingInput(action, profileName), "prompt-master-password-required");
            return;
        }
        runMasterActionWithoutPassword(player, profileName, action);
    }

    private void startCreateProfile(Player player) {
        if (!canEditNow(player)) {
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (plugin.isMasterPinRequiredToCreateProfiles() && account.hasMasterPassword()) {
            askPassword(player, new PendingInput(PendingInputType.MASTER_CREATE_PROFILE, null), "prompt-master-password-required");
            return;
        }
        askText(player, new PendingInput(PendingInputType.CREATE_PROFILE, null), "prompt-create-profile");
    }

    private void runMasterActionWithoutPassword(Player player, String profileName, PendingInputType action) {
        switch (action) {
            case MASTER_CREATE_PROFILE -> askText(player, new PendingInput(PendingInputType.CREATE_PROFILE, null), "prompt-create-profile");
            case MASTER_RENAME_PROFILE -> askText(player, new PendingInput(PendingInputType.RENAME_PROFILE, profileName), "prompt-rename-profile", "profile", profileName);
            case MASTER_LOCK_PROFILE -> askPassword(player, new PendingInput(PendingInputType.LOCK_PROFILE, profileName), "prompt-new-profile-password", "profile", profileName);
            case MASTER_CHANGE_PROFILE_PASSWORD -> askPassword(player, new PendingInput(PendingInputType.LOCK_PROFILE, profileName), "prompt-new-profile-password", "profile", profileName);
            case MASTER_UNLOCK_PROFILE -> unlockProfileByMaster(player, profileName);
            case MASTER_DELETE_PROFILE -> deleteProfile(player, profileName);
            case MASTER_SET_MASTER_PASSWORD -> askPassword(player, new PendingInput(PendingInputType.SET_MASTER_PASSWORD, null), "prompt-master-password");
            case MASTER_SET_LIMIT -> openTimeLimitEditor(player, profileName);
            case MASTER_PICK_ICON -> openIconPicker(player, profileName);
            default -> openMain(player);
        }
    }

    private void afterMasterPassword(Player player, String password, Runnable action) {
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!PasswordHasher.verify(password, account.getMasterPasswordHash())) {
            showPinError(player, pendingInputs.get(player.getUniqueId()), "master-password-required");
            return;
        }
        pendingInputs.remove(player.getUniqueId());
        action.run();
    }

    private void showPinError(Player player, PendingInput input, String messageKey, Object... replacements) {
        plugin.send(player, messageKey, replacements);
        String message = plugin.getLanguageService().get(messageKey, replacements);
        player.sendTitle(title("pin-error-title"), ChatColor.translateAlternateColorCodes('&', message), 5, 40, 10);
        if (input == null) {
            return;
        }
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (!(inventory.getHolder() instanceof GuiHolder holder) || holder.getType() != GuiType.PASSWORD_INPUT) {
            return;
        }
        for (int slot : PIN_DISPLAY_SLOTS) {
            inventory.setItem(slot, ItemFactory.item(Material.RED_STAINED_GLASS_PANE, text("pin-error-item-name"), lore("pin-error-item-lore")));
        }
        inventory.setItem(51, ItemFactory.item(Material.REDSTONE_BLOCK, text("pin-error-item-name"), lore("pin-error-item-lore")));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingInput current = pendingInputs.get(player.getUniqueId());
            if (current == input && player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder currentHolder
                    && currentHolder.getType() == GuiType.PASSWORD_INPUT) {
                openPasswordInput(player, current, passwordTitleKey(current), "profile", current.getProfileName() == null ? "" : current.getProfileName());
            }
        }, 40L);
    }

    private String pinValidationErrorKey(String password) {
        if (!password.matches("\\d+")) {
            return "pin-only-numbers";
        }
        if (password.length() < plugin.getMinPinLength()) {
            return "password-too-short";
        }
        return "pin-too-long";
    }

    private boolean canEditNow(Player player) {
        if (!plugin.isMasterProfile(player, plugin.getActiveProfile(player))) {
            plugin.send(player, "master-profile-required");
            openMain(player);
            return false;
        }
        return true;
    }

    private void requireProfileBasicEdit(Player player, String profileName, PendingInputType action) {
        if (plugin.isMasterProfile(player, plugin.getActiveProfile(player))) {
            requireMasterEdit(player, profileName, action);
            return;
        }
        if (!plugin.getActiveProfile(player).equals(profileName)) {
            plugin.send(player, "profile-self-edit-required");
            openMain(player);
            return;
        }
        runSelfBasicAction(player, profileName, action);
    }

    private void runSelfBasicAction(Player player, String profileName, PendingInputType action) {
        switch (action) {
            case MASTER_LOCK_PROFILE -> askPassword(player, new PendingInput(PendingInputType.LOCK_PROFILE, profileName), "prompt-new-profile-password", "profile", profileName);
            case MASTER_CHANGE_PROFILE_PASSWORD -> askPassword(player, new PendingInput(PendingInputType.CHANGE_PROFILE_PASSWORD_CURRENT, profileName), "prompt-current-profile-password", "profile", profileName);
            case MASTER_UNLOCK_PROFILE -> askPassword(player, new PendingInput(PendingInputType.UNLOCK_PROFILE, profileName), "prompt-current-profile-password", "profile", profileName);
            case MASTER_PICK_ICON -> openIconPicker(player, profileName);
            case MASTER_RENAME_PROFILE -> askText(player, new PendingInput(PendingInputType.RENAME_PROFILE, profileName), "prompt-rename-profile", "profile", profileName);
            default -> openMain(player);
        }
    }

    private boolean canEditProfileBasics(Player player, String profileName) {
        if (plugin.isMasterProfile(player, plugin.getActiveProfile(player)) || plugin.getActiveProfile(player).equals(profileName)) {
            return true;
        }
        plugin.send(player, "profile-self-edit-required");
        openMain(player);
        return false;
    }

    private boolean canEditProfileTime(Player player) {
        if (plugin.isMasterProfile(player, plugin.getActiveProfile(player))) {
            return true;
        }
        plugin.send(player, "master-profile-time-required");
        openMain(player);
        return false;
    }

    private boolean canUseProfilePin(Player player, ProfileMeta profile, String pin) {
        AccountData account = plugin.loadAccount(player.getUniqueId());
        return PasswordHasher.verify(pin, profile.getPasswordHash())
                || (account.hasMasterPassword() && PasswordHasher.verify(pin, account.getMasterPasswordHash()));
    }

    private String profileAtSlot(Player player, int slot) {
        int index = -1;
        for (int i = 0; i < PROFILE_SLOTS.length; i++) {
            if (PROFILE_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return null;
        }
        List<String> profiles = new java.util.ArrayList<>(plugin.loadAccount(player.getUniqueId()).getProfiles().keySet());
        return index < profiles.size() ? profiles.get(index) : null;
    }

    private ItemStack profileItem(Player player, ProfileMeta profile, String active) {
        return profileIcon(profile,
                text(AccountData.normalizeName(profile.getName()).equals(active) ? "gui-profile-active-name" : "gui-profile-name", "profile", profile.getName()),
                profileLore(player, profile, active));
    }

    private List<String> profileLore(Player player, ProfileMeta profile, String active) {
        List<String> profileLore = new java.util.ArrayList<>();
        if (profile.isMaster()) {
            profileLore.add(text("gui-profile-lore-master"));
        }
        profileLore.add(text(profile.hasPassword() ? "gui-profile-lore-locked" : "gui-profile-lore-unlocked"));
        profileLore.addAll(timeLore(player, profile, active));
        profileLore.add(text("gui-profile-lore-left"));
        profileLore.add(text("gui-profile-lore-right"));
        return profileLore;
    }

    private List<String> profileLimitLore(Player player, ProfileMeta profile) {
        List<String> lines = new java.util.ArrayList<>(lore("gui-limit-lore"));
        lines.add("");
        lines.addAll(timeLore(player, profile, plugin.getActiveProfile(player)));
        return lines;
    }

    private List<String> timeLore(Player player, ProfileMeta profile, String active) {
        long now = System.currentTimeMillis();
        if (!profile.isTimeLimited()) {
            return List.of(text("gui-profile-lore-unlimited"));
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add(text("gui-profile-lore-limit-set", "time", formatDuration(profile.getMaxPlayMillis())));
        lines.add(text("gui-profile-lore-renew-every", "time", formatDuration(profile.getRenewMillis())));
        long remaining = profile.getRemainingMillis(now);
        if (AccountData.normalizeName(profile.getName()).equals(active)) {
            remaining = Math.max(0L, remaining - plugin.getActiveSessionMillis(player));
        }
        if (remaining > 0L) {
            lines.add(text("gui-profile-lore-time-left", "time", formatDuration(remaining)));
            return lines;
        }
        lines.add(text("gui-profile-lore-unlocks-in", "time", formatDuration(profile.getMillisUntilRenewal(now))));
        return lines;
    }


    private ItemStack profileIcon(ProfileMeta profile, String name, List<String> lore) {
        return ItemFactory.head(iconTexture(resolveIconKey(profile)), name, lore);
    }

    private ItemStack iconChoiceItem(String iconKey, boolean selected) {
        return ItemFactory.head(iconTexture(iconKey),
                text(selected ? "gui-icon-selected-name" : "gui-icon-choice-name", "icon", iconDisplayName(iconKey)),
                lore(selected ? "gui-icon-selected-lore" : "gui-icon-choice-lore"));
    }

    private ItemStack pinDigitItem(int digit) {
        String texture = plugin.getConfig().getString("gui.pin-heads.digits." + digit, "");
        if (texture == null || texture.isBlank()) {
            return ItemFactory.item(Material.PAPER, "&f&l" + digit, lore("gui-pin-digit-lore"));
        }
        return ItemFactory.head(texture, "&f&l" + digit, lore("gui-pin-digit-lore"));
    }

    private ItemStack head(String key, String name, List<String> lore) {
        return ItemFactory.head(plugin.getConfig().getString("gui.heads." + key, ""), name, lore);
    }

    private void fill(Inventory inventory) {
        ItemStack filler = ItemFactory.item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private boolean validPassword(Player player, String password) {
        if (!isValidPasswordValue(password) && !password.matches("\\d+")) {
            plugin.send(player, "pin-only-numbers");
            return false;
        }
        if (!isValidPasswordValue(password) && password.length() < plugin.getMinPinLength()) {
            plugin.send(player, "password-too-short", "min", plugin.getMinPinLength());
            return false;
        }
        if (!isValidPasswordValue(password) && password.length() > plugin.getMaxPinLength()) {
            plugin.send(player, "pin-too-long", "max", plugin.getMaxPinLength());
            return false;
        }
        return true;
    }

    private boolean isValidPasswordValue(String password) {
        return password.matches("\\d+")
                && password.length() >= plugin.getMinPinLength()
                && password.length() <= plugin.getMaxPinLength();
    }

    private String normalizeProfileName(String input) {
        String name = input == null ? "" : input.trim();
        if (name.length() < 2 || name.length() > 24 || !name.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        return name;
    }

    private String text(String key, Object... replacements) {
        return plugin.getLanguageService().get(key, replacements);
    }

    private String title(String key, Object... replacements) {
        return ChatColor.translateAlternateColorCodes('&', text(key, replacements));
    }

    private List<String> color(List<String> lines) {
        List<String> colored = new java.util.ArrayList<>();
        for (String line : lines) {
            colored.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        return colored;
    }

    private String passwordTitleKey(PendingInput input) {
        return switch (input.getType()) {
            case SWITCH_PASSWORD -> "prompt-profile-password";
            case LOCK_PROFILE -> "prompt-new-profile-password";
            case SET_MASTER_PASSWORD -> "prompt-master-password";
            default -> "prompt-master-password-required";
        };
    }

    private String maskPassword(String value) {
        if (value == null || value.isEmpty()) {
            return text("gui-password-empty");
        }
        return "&7" + "*".repeat(value.length());
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0L) {
            return days + "d " + String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private int digitFromSlot(int slot) {
        for (int digit = 0; digit < PIN_DIGIT_SLOTS.length; digit++) {
            if (PIN_DIGIT_SLOTS[digit] == slot) {
                return digit;
            }
        }
        return -1;
    }

    private List<String> lore(String key) {
        return plugin.getLanguageService().getStringList(key);
    }

    private String resolveIconKey(ProfileMeta profile) {
        String key = profile.getIconKey();
        if (key != null && plugin.getConfig().contains("gui.profile-icons.icons." + key)) {
            return key;
        }
        return defaultIconKey();
    }

    private String defaultIconKey() {
        String key = plugin.getConfig().getString("gui.profile-icons.default", "baby_turtle");
        if (key != null && plugin.getConfig().contains("gui.profile-icons.icons." + key)) {
            return key;
        }
        List<String> keys = iconKeys();
        return keys.isEmpty() ? "profile" : keys.get(0);
    }

    private List<String> iconKeys() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("gui.profile-icons.icons");
        if (section == null) {
            return List.of();
        }
        return new java.util.ArrayList<>(section.getKeys(false));
    }

    private String iconTexture(String iconKey) {
        return plugin.getConfig().getString("gui.profile-icons.icons." + iconKey + ".texture", plugin.getConfig().getString("gui.heads.profile", ""));
    }

    private String iconDisplayName(String iconKey) {
        return plugin.getConfig().getString("gui.profile-icons.icons." + iconKey + ".name", iconKey);
    }
}
