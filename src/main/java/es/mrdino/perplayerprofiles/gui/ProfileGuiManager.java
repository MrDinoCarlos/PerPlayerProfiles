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
import org.bukkit.event.inventory.InventoryDragEvent;
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
    private final Map<UUID, PendingInput> pendingInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, TimeLimitDraft> timeLimitDrafts = new HashMap<>();
    private final Set<UUID> requiredSelections = new HashSet<>();
    private final Map<UUID, Integer> mainPages = new HashMap<>();
    private final Map<UUID, Integer> iconPages = new HashMap<>();
    private final Map<UUID, String> iconQueries = new HashMap<>();
    private final Set<UUID> legacyPlayers = new HashSet<>();
    private final Map<UUID, Presentation> presentations = new HashMap<>();
    private final Map<UUID, String> notices = new HashMap<>();
    private final Set<UUID> handlingActions = new HashSet<>();
    private final Map<UUID, UUID> transitions = new HashMap<>();
    private final NativeProfileScreen screens;

    public ProfileGuiManager(PerPlayerProfilesPlugin plugin) {
        this.plugin = plugin;
        this.screens = loadScreens();
    }

    ProfileGuiManager(PerPlayerProfilesPlugin plugin, NativeProfileScreen screens) {
        this.plugin = plugin;
        this.screens = screens;
    }

    public void openMain(Player player) {
        transitions.remove(player.getUniqueId());
        if (!ready(player)) return;
        pendingInputs.remove(player.getUniqueId());
        if (modern(player, () -> openNativeMain(player))) return;
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.MAIN, null), 27, title(player, "gui-main-title"));
        renderMain(player, inventory);
        player.openInventory(inventory);
    }

    public void requireProfileSelection(Player player) {
        requiredSelections.add(player.getUniqueId());
    }

    public void clearRequiredProfileSelection(Player player) {
        requiredSelections.remove(player.getUniqueId());
    }

    public void finishProfileSelection(Player player) {
        UUID uuid = player.getUniqueId();
        requiredSelections.remove(uuid);
        pendingInputs.remove(uuid);
        presentations.remove(uuid); notices.remove(uuid); transitions.remove(uuid);
        if (screens != null) screens.close(player);
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
            player.closeInventory();
        }
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
        int index = -mainPages.getOrDefault(player.getUniqueId(), 0) * PROFILE_SLOTS.length;
        for (ProfileMeta profile : account.getProfiles().values()) {
            if (index < 0) { index++; continue; }
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
        int page = page(mainPages, player, account.getProfiles().size(), PROFILE_SLOTS.length);
        int index = -page * PROFILE_SLOTS.length;
        for (ProfileMeta profile : account.getProfiles().values()) {
            if (index < 0) { index++; continue; }
            if (index >= PROFILE_SLOTS.length) {
                break;
            }
            inventory.setItem(PROFILE_SLOTS[index++], profileItem(player, profile, active));
        }
        inventory.setItem(21, head("create", text(player, "gui-create-name"), lore(player, "gui-create-lore")));
        inventory.setItem(23, ItemFactory.item(Material.BOOK, text(player, "screen-language"), List.of()));
        inventory.setItem(4, ItemFactory.item(Material.BOOK, text(player, "screen-account-summary", "count", account.getProfiles().size(), "max", plugin.getMaxProfilesPerAccount()), List.of()));
        if (page > 0) inventory.setItem(18, ItemFactory.item(Material.ARROW, text(player, "screen-previous"), List.of()));
        if ((page + 1) * PROFILE_SLOTS.length < account.getProfiles().size()) inventory.setItem(26, ItemFactory.item(Material.ARROW, text(player, "screen-next"), List.of()));
    }

    public void openProfileSettings(Player player, String profileName) {
        transitions.remove(player.getUniqueId());
        if (!ready(player)) return;
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        if (modern(player, () -> openNativeSettings(player, profile))) return;
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.PROFILE_SETTINGS, profile.getName()), 27, title(player, "gui-profile-title", "profile", profile.getName()));
        fill(inventory);
        inventory.setItem(10, ItemFactory.item(Material.ENDER_PEARL, text(player, "gui-use-name"), lore(player, "gui-use-lore")));
        if (profile.hasPassword()) {
            inventory.setItem(11, head("locked", text(player, "gui-change-password-name"), lore(player, "gui-change-password-lore")));
            inventory.setItem(12, head("unlocked", text(player, "gui-unlock-name"), lore(player, "gui-unlock-lore")));
        } else {
            inventory.setItem(12, head("locked", text(player, "gui-lock-name"), lore(player, "gui-lock-lore")));
        }
        inventory.setItem(13, profileIcon(profile, text(player, "gui-icon-name"), lore(player, "gui-icon-lore")));
        inventory.setItem(14, ItemFactory.item(Material.CLOCK, text(player, "gui-limit-name"), profileLimitLore(player, profile)));
        inventory.setItem(15, ItemFactory.item(Material.NAME_TAG, text(player, "gui-rename-name"), lore(player, "gui-rename-lore")));
        if (plugin.isMasterProfile(player, profile.getName())) {
            inventory.setItem(16, head("settings", text(player, "gui-master-password-name"), lore(player, "gui-master-password-lore")));
        } else {
            inventory.setItem(16, head("delete", text(player, "gui-delete-name"), lore(player, "gui-delete-lore")));
        }
        inventory.setItem(22, ItemFactory.item(Material.ARROW, text(player, "gui-back-name"), lore(player, "gui-back-lore")));
        player.openInventory(inventory);
    }

    public void openIconPicker(Player player, String profileName) {
        transitions.remove(player.getUniqueId());
        if (!ready(player)) return;
        if (!canEditProfileBasics(player, profileName)) {
            return;
        }
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) {
            plugin.send(player, "profile-not-found");
            return;
        }
        if (modern(player, () -> openNativeIcons(player, profile))) return;
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.ICON_PICKER, profile.getName()), 27, title(player, "gui-icons-title", "profile", profile.getName()));
        fill(inventory);
        List<String> iconKeys = filteredIconKeys(player);
        int page = page(iconPages, player, iconKeys.size(), ICON_SLOTS.length);
        for (int i = 0; i < Math.min(ICON_SLOTS.length, iconKeys.size()); i++) {
            if (page * ICON_SLOTS.length + i >= iconKeys.size()) break;
            String iconKey = iconKeys.get(page * ICON_SLOTS.length + i);
            inventory.setItem(ICON_SLOTS[i], iconChoiceItem(player, iconKey, iconKey.equals(resolveIconKey(profile))));
        }
        inventory.setItem(22, ItemFactory.item(Material.ARROW, text(player, "gui-back-name"), lore(player, "gui-back-lore")));
        if (page > 0) inventory.setItem(18, ItemFactory.item(Material.ARROW, text(player, "screen-previous"), List.of()));
        if ((page + 1) * ICON_SLOTS.length < iconKeys.size()) inventory.setItem(26, ItemFactory.item(Material.ARROW, text(player, "screen-next"), List.of()));
        inventory.setItem(20, ItemFactory.item(Material.COMPASS, text(player, "screen-search"), List.of()));
        if (plugin.getConfig().getBoolean("gui.custom-heads.allow-import", true))
            inventory.setItem(24, ItemFactory.item(Material.PLAYER_HEAD, text(player, "screen-import-head"), lore(player, "screen-import-help")));
        inventory.setItem(4, profileIcon(profile, text(player, "gui-icon-name"), List.of(text(player, "screen-clear-search"))));
        player.openInventory(inventory);
    }

    public void openPasswordInput(Player player, PendingInput input, String messageKey, Object... replacements) {
        transitions.remove(player.getUniqueId());
        if (!ready(player)) return;
        pendingInputs.put(player.getUniqueId(), input);
        if (modern(player, () -> openNativePin(player, input, messageKey, null, replacements))) return;
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.PASSWORD_INPUT, input.getProfileName()), 54, title(player, messageKey, replacements));
        fill(inventory);
        for (int i = 0; i < PIN_DISPLAY_SLOTS.length; i++) {
            boolean filled = i < input.getValue().length();
            inventory.setItem(PIN_DISPLAY_SLOTS[i], ItemFactory.item(
                    filled ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE,
                    filled ? "&a*" : "&7-",
                    List.of()));
        }
        for (int digit = 0; digit <= 9; digit++) {
            inventory.setItem(PIN_DIGIT_SLOTS[digit], pinDigitItem(player, digit));
        }
        inventory.setItem(45, ItemFactory.item(Material.BARRIER, text(player, "gui-password-cancel-name"), lore(player, "gui-password-cancel-lore")));
        inventory.setItem(47, ItemFactory.item(Material.REDSTONE, text(player, "gui-password-backspace-name"), lore(player, "gui-password-backspace-lore")));
        inventory.setItem(51, ItemFactory.item(Material.NAME_TAG,
                text(player, "gui-password-display-name", "length", input.getValue().length(), "min", plugin.getMinPinLength(), "max", plugin.getMaxPinLength()),
                List.of(maskPassword(player, input.getValue()))));
        inventory.setItem(53, head("confirm", text(player, "gui-password-confirm-name"), lore(player, "gui-password-confirm-lore")));
        player.openInventory(inventory);
    }

    public void openTimeLimitEditor(Player player, String profileName) {
        if (!ready(player)) return;
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
        TimeLimitDraft currentDraft = draft;
        if (modern(player, () -> openNativeTime(player, profile, currentDraft))) return;
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.TIME_LIMIT, profileName), 27, title(player, "gui-time-title", "profile", profile.getName()));
        fill(inventory);
        inventory.setItem(9, head("minus", text(player, "gui-time-minus-30-name"), lore(player, "gui-time-minus-lore")));
        inventory.setItem(10, head("minus", text(player, "gui-time-minus-5-name"), lore(player, "gui-time-minus-lore")));
        inventory.setItem(11, ItemFactory.item(Material.CLOCK, text(player, "gui-time-max-name", "minutes", draft.getMaxMinutes()), lore(player, "gui-time-max-lore")));
        inventory.setItem(12, head("create", text(player, "gui-time-plus-5-name"), lore(player, "gui-time-plus-lore")));
        inventory.setItem(13, head("create", text(player, "gui-time-plus-30-name"), lore(player, "gui-time-plus-lore")));
        inventory.setItem(14, head("minus", text(player, "gui-time-renew-minus-1-name"), lore(player, "gui-time-minus-lore")));
        inventory.setItem(15, head("renew", text(player, "gui-time-renew-name", "hours", draft.getRenewHours()), lore(player, "gui-time-renew-lore")));
        inventory.setItem(16, head("create", text(player, "gui-time-renew-plus-1-name"), lore(player, "gui-time-plus-lore")));
        inventory.setItem(17, head("create", text(player, "gui-time-renew-plus-24-name"), lore(player, "gui-time-plus-lore")));
        inventory.setItem(4, ItemFactory.item(Material.REDSTONE_BLOCK, text(player, "gui-time-reset-name"), lore(player, "gui-time-reset-lore")));
        inventory.setItem(21, ItemFactory.item(Material.BARRIER, text(player, "gui-password-cancel-name"), lore(player, "gui-password-cancel-lore")));
        inventory.setItem(22, ItemFactory.item(Material.ARROW, text(player, "gui-back-name"), lore(player, "gui-back-lore")));
        inventory.setItem(23, head("confirm", text(player, "gui-time-confirm-name"), lore(player, "gui-time-confirm-lore")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!ready(player)) return;
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
        } else if (holder.getType() == GuiType.LANGUAGE) {
            int index = event.getSlot() - 10;
            if (event.getSlot() == 22) openMain(player);
            else if (index >= 0 && index < es.mrdino.perplayerprofiles.lang.LanguageService.languageOptions().size())
                setLanguage(player, es.mrdino.perplayerprofiles.lang.LanguageService.languageOptions().get(index));
        }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuiHolder) event.setCancelled(true);
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
            if (!plugin.isProfileReady(player) || pendingInputs.containsKey(player.getUniqueId())) return;
            Inventory openInventory = player.getOpenInventory().getTopInventory();
            if (openInventory.getHolder() instanceof GuiHolder) {
                return;
            }
            openMain(player);
        }, 1L);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        PendingInput input = pendingInputs.get(event.getPlayer().getUniqueId());
        if (input == null || !isTextInput(input)) {
            return;
        }
        if (!pendingInputs.remove(event.getPlayer().getUniqueId(), input)) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> { if (event.getPlayer().isOnline() && ready(event.getPlayer())) handleTextInput(event.getPlayer(), input, message); });
    }

    private void handleMainClick(Player player, int slot, ClickType clickType) {
        if (slot == 23) { openLanguagePicker(player); return; }
        if (slot == 18 || slot == 26) {
            mainPages.merge(player.getUniqueId(), slot == 18 ? -1 : 1, Integer::sum);
            openMain(player);
            return;
        }
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
        selectProfile(player, profileName);
    }

    private void handleSettingsClick(Player player, String profileName, int slot) {
        if (slot == 10) {
            selectProfile(player, profileName);
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
        if (slot == 18 || slot == 26) {
            iconPages.merge(player.getUniqueId(), slot == 18 ? -1 : 1, Integer::sum);
            openIconPicker(player, profileName);
            return;
        }
        if (slot == 20) { askText(player, new PendingInput(PendingInputType.SEARCH_ICONS, profileName), "screen-search-prompt"); return; }
        if (slot == 24) { startHeadImport(player, profileName); return; }
        if (slot == 4) { iconQueries.remove(player.getUniqueId()); iconPages.remove(player.getUniqueId()); openIconPicker(player, profileName); return; }
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
        List<String> keys = filteredIconKeys(player);
        index += iconPages.getOrDefault(player.getUniqueId(), 0) * ICON_SLOTS.length;
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
        profile.setCustomTexture(null);
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
            case IMPORT_HEAD -> importHead(player, input.getProfileName(), message);
            case SEARCH_ICONS -> {
                iconQueries.put(player.getUniqueId(), message);
                iconPages.remove(player.getUniqueId());
                openIconPicker(player, input.getProfileName());
            }
            default -> openMain(player);
        }
    }

    private void handlePasswordInput(Player player, PendingInput input, String password) {
        switch (input.getType()) {
            case SWITCH_PASSWORD -> switchWithPassword(player, input.getProfileName(), password);
            case RECOVER_PROFILE_PASSWORD -> finishPasswordRecovery(player, input.getProfileName(), password);
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
            retryText(player, new PendingInput(PendingInputType.CREATE_PROFILE, null, rawName), "invalid-profile-name");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (account.hasProfile(name)) {
            retryText(player, new PendingInput(PendingInputType.CREATE_PROFILE, null, rawName), "profile-exists");
            return;
        }
        if (account.getProfiles().size() >= plugin.getMaxProfilesPerAccount()) {
            retryText(player, new PendingInput(PendingInputType.CREATE_PROFILE, null, rawName), "max-profiles", "max", plugin.getMaxProfilesPerAccount());
            return;
        }
        ProfileMeta profile = new ProfileMeta(name);
        profile.ensureVirtualUuid(player.getUniqueId());
        account.putProfile(profile);
        account.getProfile(name).setIconKey(defaultIconKey());
        account.sortMasterFirst(plugin.getMasterProfileName());
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-created", "profile", name);
        openProfileSettings(player, name);
    }

    private void renameProfile(Player player, String oldName, String rawName) {
        String newName = normalizeProfileName(rawName);
        if (newName == null) {
            retryText(player, new PendingInput(PendingInputType.RENAME_PROFILE, oldName, rawName), "invalid-profile-name");
            return;
        }
        AccountData account = plugin.loadAccount(player.getUniqueId());
        if (!account.hasProfile(oldName)) {
            plugin.send(player, "profile-not-found");
            openMain(player);
            return;
        }
        if (!AccountData.normalizeName(oldName).equals(AccountData.normalizeName(newName)) && account.hasProfile(newName)) {
            retryText(player, new PendingInput(PendingInputType.RENAME_PROFILE, oldName, rawName), "profile-exists");
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

    public void beginPasswordRecovery(Player player, String profileName) {
        if (!ready(player)) return;
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null || !profile.isPasswordResetRequired()) { openMain(player); return; }
        askPassword(player, new PendingInput(PendingInputType.RECOVER_PROFILE_PASSWORD, profile.getName()),
                "prompt-recover-profile-password", "profile", profile.getName());
    }

    private void finishPasswordRecovery(Player player, String profileName, String password) {
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null || !profile.isPasswordResetRequired()) { openMain(player); return; }
        String hash = PasswordHasher.hash(password);
        profile.setPasswordHash(hash);
        try { plugin.saveAccount(player.getUniqueId()); }
        catch (RuntimeException failure) { profile.requireNewPassword(); throw failure; }
        pendingInputs.remove(player.getUniqueId());
        plugin.send(player, "profile-password-updated", "profile", profile.getName());
        plugin.switchProfile(player, profileName);
    }

    public void onPasswordRecovered(Player player, String profileName, boolean reset) {
        boolean menuOpen = presentations.containsKey(player.getUniqueId()) || pendingInputs.containsKey(player.getUniqueId());
        pendingInputs.remove(player.getUniqueId());
        if (screens != null) screens.forget(player);
        presentations.remove(player.getUniqueId()); notices.remove(player.getUniqueId()); transitions.remove(player.getUniqueId());
        if (!plugin.isProfileReady(player)) return;
        if (reset && plugin.getActiveProfile(player).equals(AccountData.normalizeName(profileName))) {
            requireProfileSelection(player);
            beginPasswordRecovery(player, profileName);
        } else if (menuOpen || requiredSelections.contains(player.getUniqueId())) openMain(player);
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
        if (!plugin.getPinSecurity().profile(player, profile, password, false)) {
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
        if (!plugin.getPinSecurity().profile(player, profile, password, false)) {
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
            if (maxMinutes < 0L || renewHours < 0L) throw new NumberFormatException();
            plugin.saveActiveProfile(player);
            profile.setTimeLimit(Math.multiplyExact(maxMinutes, 60_000L), Math.multiplyExact(renewHours, 3_600_000L));
            plugin.saveAccount(player.getUniqueId());
            plugin.send(player, "limit-applied", "profile", profile.getName(), "minutes", maxMinutes, "hours", renewHours);
            openProfileSettings(player, profileName);
        } catch (NumberFormatException | ArithmeticException e) {
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
        plugin.saveActiveProfile(player);
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
        if (!ready(player)) return;
        pendingInputs.put(player.getUniqueId(), input);
        if (modern(player, () -> openNativeText(player, input))) return;
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
        if (!plugin.getPinSecurity().master(player, password)) {
            showPinError(player, pendingInputs.get(player.getUniqueId()), "master-password-required");
            return;
        }
        pendingInputs.remove(player.getUniqueId());
        action.run();
    }

    private void showPinError(Player player, PendingInput input, String messageKey, Object... replacements) {
        long cooldown = plugin.getPinSecurity().cooldownSeconds(player);
        String key = cooldown > 0 ? "pin-cooldown" : messageKey;
        Object[] arguments = cooldown > 0 ? new Object[]{"seconds", cooldown} : replacements;
        plugin.send(player, key, arguments);
        String message = plugin.getLanguageService().get(player, key, arguments);
        if (input == null) {
            return;
        }
        notices.remove(player.getUniqueId());
        if (modern(player, () -> openNativePin(player, input.withValue(""), passwordTitleKey(input), message))) {
            return;
        }
        player.sendTitle(title(player, "pin-error-title"), ChatColor.translateAlternateColorCodes('&', message), 5, 40, 10);
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (!(inventory.getHolder() instanceof GuiHolder holder) || holder.getType() != GuiType.PASSWORD_INPUT) {
            return;
        }
        for (int slot : PIN_DISPLAY_SLOTS) {
            inventory.setItem(slot, ItemFactory.item(Material.RED_STAINED_GLASS_PANE, text(player, "pin-error-item-name"), lore(player, "pin-error-item-lore")));
        }
        inventory.setItem(51, ItemFactory.item(Material.REDSTONE_BLOCK, text(player, "pin-error-item-name"), lore(player, "pin-error-item-lore")));
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
        if (requiredSelections.contains(player.getUniqueId())) {
            plugin.send(player, "screen-selection-required");
            openMain(player);
            return false;
        }
        if (!plugin.isMasterProfile(player, plugin.getActiveProfile(player))) {
            plugin.send(player, "master-profile-required");
            openMain(player);
            return false;
        }
        return true;
    }

    private void requireProfileBasicEdit(Player player, String profileName, PendingInputType action) {
        if (requiredSelections.contains(player.getUniqueId())) { openMain(player); return; }
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
        if (requiredSelections.contains(player.getUniqueId())) { openMain(player); return false; }
        if (plugin.isMasterProfile(player, plugin.getActiveProfile(player)) || plugin.getActiveProfile(player).equals(profileName)) {
            return true;
        }
        plugin.send(player, "profile-self-edit-required");
        openMain(player);
        return false;
    }

    private boolean canEditProfileTime(Player player) {
        if (requiredSelections.contains(player.getUniqueId())) { openMain(player); return false; }
        if (plugin.isMasterProfile(player, plugin.getActiveProfile(player))) {
            return true;
        }
        plugin.send(player, "master-profile-time-required");
        openMain(player);
        return false;
    }

    private boolean canUseProfilePin(Player player, ProfileMeta profile, String pin) {
        return plugin.getPinSecurity().profile(player, profile, pin, true);
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
        index += mainPages.getOrDefault(player.getUniqueId(), 0) * PROFILE_SLOTS.length;
        return index < profiles.size() ? profiles.get(index) : null;
    }

    private ItemStack profileItem(Player player, ProfileMeta profile, String active) {
        return profileIcon(profile,
                text(player, AccountData.normalizeName(profile.getName()).equals(active) ? "gui-profile-active-name" : "gui-profile-name", "profile", profile.getName()),
                profileLore(player, profile, active));
    }

    private List<String> profileLore(Player player, ProfileMeta profile, String active) {
        List<String> profileLore = new java.util.ArrayList<>();
        if (profile.isMaster()) {
            profileLore.add(text(player, "gui-profile-lore-master"));
        } else profileLore.add(text(player, "screen-type-standard"));
        profileLore.add(text(player, profile.hasPassword() ? "gui-profile-lore-locked" : "gui-profile-lore-unlocked"));
        profileLore.add(text(player, "screen-created-at", "date", date(player, profile.getCreatedAt())));
        profileLore.add(text(player, "screen-last-connected", "date", date(player, profile.getLastConnectedAt())));
        profileLore.addAll(statsLore(player, profile));
        profileLore.addAll(timeLore(player, profile, active));
        profileLore.add(text(player, "gui-profile-lore-left"));
        profileLore.add(text(player, "gui-profile-lore-right"));
        return profileLore;
    }

    private List<String> profileLimitLore(Player player, ProfileMeta profile) {
        List<String> lines = new java.util.ArrayList<>(lore(player, "gui-limit-lore"));
        lines.add("");
        lines.addAll(timeLore(player, profile, plugin.getActiveProfile(player)));
        return lines;
    }

    private List<String> timeLore(Player player, ProfileMeta profile, String active) {
        long now = System.currentTimeMillis();
        if (!profile.isTimeLimited()) {
            return List.of(text(player, "gui-profile-lore-unlimited"));
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add(text(player, "gui-profile-lore-limit-set", "time", formatDuration(profile.getMaxPlayMillis())));
        lines.add(text(player, "gui-profile-lore-renew-every", "time", formatDuration(profile.getRenewMillis())));
        long remaining = plugin.getRemainingPlayMillis(player, profile);
        if (remaining > 0L) {
            lines.add(text(player, "gui-profile-lore-time-left", "time", formatDuration(remaining)));
            return lines;
        }
        lines.add(text(player, "gui-profile-lore-unlocks-in", "time", formatDuration(profile.getMillisUntilRenewal(now))));
        return lines;
    }


    private ItemStack profileIcon(ProfileMeta profile, String name, List<String> lore) {
        return ItemFactory.head(profile.getCustomTexture() == null ? iconTexture(resolveIconKey(profile)) : profile.getCustomTexture(), name, lore);
    }

    private ItemStack iconChoiceItem(Player player, String iconKey, boolean selected) {
        return ItemFactory.head(iconTexture(iconKey),
                text(player, selected ? "gui-icon-selected-name" : "gui-icon-choice-name", "icon", iconDisplayName(iconKey)),
                lore(player, selected ? "gui-icon-selected-lore" : "gui-icon-choice-lore"));
    }

    private ItemStack pinDigitItem(Player player, int digit) {
        String texture = plugin.getConfig().getString("gui.pin-heads.digits." + digit, "");
        if (texture == null || texture.isBlank()) {
            return ItemFactory.item(Material.PAPER, "&f&l" + digit, lore(player, "gui-pin-digit-lore"));
        }
        return ItemFactory.head(texture, "&f&l" + digit, lore(player, "gui-pin-digit-lore"));
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
        if (name.length() < 2 || name.length() > 16 || !name.matches("[A-Za-z0-9_]+")) {
            return null;
        }
        return name;
    }

    private String text(Player player, String key, Object... replacements) {
        return plugin.getLanguageService().get(player, key, replacements);
    }

    private String title(Player player, String key, Object... replacements) {
        return ChatColor.translateAlternateColorCodes('&', text(player, key, replacements));
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
            case RECOVER_PROFILE_PASSWORD -> "prompt-recover-profile-password";
            case LOCK_PROFILE, CHANGE_PROFILE_PASSWORD_NEW -> "prompt-new-profile-password";
            case CHANGE_PROFILE_PASSWORD_CURRENT, UNLOCK_PROFILE -> "prompt-current-profile-password";
            case SET_MASTER_PASSWORD -> "prompt-master-password";
            default -> "prompt-master-password-required";
        };
    }

    private String maskPassword(Player player, String value) {
        if (value == null || value.isEmpty()) {
            return text(player, "gui-password-empty");
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

    private List<String> lore(Player player, String key) {
        return plugin.getLanguageService().getStringList(player, key);
    }

    private NativeProfileScreen loadScreens() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog", false, plugin.getClass().getClassLoader());
            return (NativeProfileScreen) Class.forName("es.mrdino.perplayerprofiles.gui.PaperProfileScreen")
                    .getConstructor(org.bukkit.plugin.java.JavaPlugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError absent) { return null; }
    }

    private boolean ready(Player player) {
        if (plugin.isProfileReady(player)) return true;
        plugin.send(player, "waiting-for-auth");
        return false;
    }

    private boolean modern(Player player, Runnable render) {
        if (screens == null || legacyPlayers.contains(player.getUniqueId())
                || "LEGACY".equalsIgnoreCase(plugin.getConfig().getString("gui.mode", "AUTO"))
                || "INVENTORY".equalsIgnoreCase(plugin.getConfig().getString("gui.mode", "AUTO"))
                || "LEGACY".equalsIgnoreCase(plugin.loadAccount(player.getUniqueId()).getMenuMode())) return false;
        try {
            if (!screens.supports(player)) return false;
            render.run();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            legacyPlayers.add(player.getUniqueId());
            screens.close(player);
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Custom screen unavailable for this connection; using the inventory menu.", failure);
            return false;
        }
    }

    private void show(Player player, NativeProfileScreen.View view, java.util.function.BiConsumer<String, String> handler) {
        show(player, view, handler, false);
    }

    private void show(Player player, NativeProfileScreen.View view, java.util.function.BiConsumer<String, String> handler, boolean transition) {
        if (!transition) transitions.remove(player.getUniqueId());
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) player.closeInventory();
        Presentation presentation = new Presentation(view, handler);
        presentations.put(player.getUniqueId(), presentation);
        String notice = notices.remove(player.getUniqueId());
        if (notice != null) {
            var lines = new java.util.ArrayList<String>(); lines.add(notice); lines.addAll(view.lines());
            view = new NativeProfileScreen.View(view.title(), lines, view.cards(), view.buttons(), view.footer(), view.columns(), view.buttonWidth(),
                    view.bodyWidth(), view.canClose(), view.textEntry(), view.initialText(), view.maxLength());
        }
        screens.show(player, view, (action, value) -> {
            if (!ready(player)) return;
            handlingActions.add(player.getUniqueId());
            try { handler.accept(action, value); }
            catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not complete a profile menu action", failure);
                notices.put(player.getUniqueId(), "&c" + text(player, "screen-action-failed"));
            } finally { handlingActions.remove(player.getUniqueId()); }
            if (notices.containsKey(player.getUniqueId()) && presentations.get(player.getUniqueId()) == presentation)
                show(player, presentation.view(), presentation.handler());
        });
    }

    public void reportMessage(Player player, String key, Object... replacements) {
        if (handlingActions.contains(player.getUniqueId()) && presentations.containsKey(player.getUniqueId()))
            notices.put(player.getUniqueId(), plugin.getLanguageService().get(player, key, replacements));
    }

    private record Presentation(NativeProfileScreen.View view, java.util.function.BiConsumer<String, String> handler) { }

    private NativeProfileScreen.View view(Player player, String heading, List<String> lines, List<NativeProfileScreen.Card> cards,
                                          List<NativeProfileScreen.Button> buttons, NativeProfileScreen.Button footer, int columns, int width) {
        return new NativeProfileScreen.View(heading, lines, cards, buttons, footer, columns, width, 304,
                !requiredSelections.contains(player.getUniqueId()), false, "", 16);
    }

    private NativeProfileScreen.Button button(Player player, String action, String key, Object... replacements) {
        return new NativeProfileScreen.Button(action, text(player, key, replacements));
    }

    private void openNativeMain(Player player) {
        AccountData account = plugin.loadAccount(player.getUniqueId());
        List<ProfileMeta> profiles = new java.util.ArrayList<>(account.getProfiles().values());
        if (profiles.isEmpty()) return;
        int pageSize = nativePageSize("profiles-per-page");
        int page = page(mainPages, player, profiles.size(), pageSize);
        int pages = (profiles.size() + pageSize - 1) / pageSize;
        var cards = new java.util.ArrayList<NativeProfileScreen.Card>();
        for (ProfileMeta profile : profiles.subList(page * pageSize, Math.min(profiles.size(), (page + 1) * pageSize))) {
            boolean available = plugin.getRemainingPlayMillis(player, profile) > 0;
            String infoAction = "DETAILS:" + profile.getName();
            var links = List.of(new NativeProfileScreen.Button(available ? "USE:" + profile.getName() : "NONE",
                            (available ? "&a" : "&8") + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text(player, "screen-select-character"))),
                            available ? null : text(player, "profile-no-time")),
                    new NativeProfileScreen.Button(infoAction, text(player, "screen-details"), String.join("\n", profileDetails(player, profile))));
            NativeProfileScreen.Card card = characterCard(player, profile, infoAction);
            String badge = !available ? " &r&c[!]" : profile.hasPassword() ? " &r&c[PIN]"
                    : AccountData.normalizeName(profile.getName()).equals(plugin.getActiveProfile(player)) ? " &r" + text(player, "screen-active-badge") : "";
            cards.add(new NativeProfileScreen.Card(card.icon(), List.of(card.lines().get(0) + badge),
                    infoAction, card.tooltip(), links));
        }
        var buttons = new java.util.ArrayList<NativeProfileScreen.Button>();
        if (pages > 1) {
            buttons.add(button(player, page > 0 ? "PREVIOUS" : "NONE", "screen-previous"));
            buttons.add(button(player, page + 1 < pages ? "NEXT" : "NONE", "screen-next"));
        }
        boolean canCreate = plugin.isMasterProfile(player, plugin.getActiveProfile(player)) && !requiredSelections.contains(player.getUniqueId()) && profiles.size() < plugin.getMaxProfilesPerAccount();
        buttons.add(button(player, canCreate ? "CREATE" : "REFRESH", canCreate ? "gui-create-name" : "screen-refresh"));
        buttons.add(button(player, "LANGUAGE", "screen-language"));
        show(player, view(player, pagedTitle(text(player, "screen-selector-title"), page, pages), List.of(), cards, buttons,
                button(player, requiredSelections.contains(player.getUniqueId()) ? "DISCONNECT" : "CLOSE", requiredSelections.contains(player.getUniqueId()) ? "screen-disconnect" : "screen-close"), 2, 150), (action, value) -> {
            if (action.startsWith("USE:")) { selectProfile(player, action.substring(4)); return; }
            if (action.startsWith("DETAILS:")) { openProfileSettings(player, action.substring(8)); return; }
            switch (action) {
                case "PREVIOUS", "NEXT" -> { mainPages.put(player.getUniqueId(), page + (action.equals("NEXT") ? 1 : -1)); openMain(player); }
                case "CREATE" -> startCreateProfile(player);
                case "LANGUAGE" -> openLanguagePicker(player);
                case "CLOSE" -> closeNative(player);
                case "DISCONNECT" -> { closeNative(player); player.kickPlayer(title(player, "screen-selection-required")); }
                default -> openMain(player);
            }
        });
    }

    public void selectProfile(Player player, String name) {
        if (!ready(player)) return;
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(name);
        if (profile == null) { plugin.send(player, "profile-not-found"); return; }
        if (plugin.getRemainingPlayMillis(player, profile) <= 0) { plugin.send(player, "profile-no-time"); return; }
        if (profile.isPasswordResetRequired()) beginPasswordRecovery(player, profile.getName());
        else if (profile.hasPassword()) askPassword(player, new PendingInput(PendingInputType.SWITCH_PASSWORD, profile.getName()), "prompt-profile-password", "profile", profile.getName());
        else plugin.switchProfile(player, profile.getName());
    }

    private void openNativeSettings(Player player, ProfileMeta profile) {
        var buttons = new java.util.ArrayList<NativeProfileScreen.Button>();
        buttons.add(button(player, "10", profile.hasPassword() ? "screen-unlock-play" : "gui-use-name"));
        buttons.add(button(player, "INFO", "screen-profile-info"));
        buttons.add(button(player, "STATS", "screen-statistics-title"));
        boolean editable = !requiredSelections.contains(player.getUniqueId()) && (plugin.isMasterProfile(player, plugin.getActiveProfile(player))
                || plugin.getActiveProfile(player).equals(AccountData.normalizeName(profile.getName())));
        if (editable) {
            buttons.add(button(player, "APPEARANCE", "screen-appearance"));
            buttons.add(button(player, "SECURITY", "screen-security"));
            if (plugin.isMasterProfile(player, plugin.getActiveProfile(player)) && !profile.isMaster()) buttons.add(button(player, "14", "gui-limit-name"));
        }
        show(player, view(player, text(player, "screen-profile-title", "profile", profile.getName()), List.of(),
                List.of(characterCard(player, profile, "INFO")), buttons, button(player, "BACK", "gui-back-name"), 3, 100), (action, value) -> {
            switch (action) {
                case "BACK" -> openMain(player);
                case "INFO" -> openNativeInfo(player, profile);
                case "STATS" -> openNativeStatistics(player, profile);
                case "APPEARANCE", "SECURITY" -> openNativeEditSection(player, profile, action.equals("SECURITY"));
                default -> handleSettingsClick(player, profile.getName(), Integer.parseInt(action));
            }
        });
    }

    private void openNativeEditSection(Player player, ProfileMeta profile, boolean security) {
        if (!canEditProfileBasics(player, profile.getName())) return;
        var buttons = new java.util.ArrayList<NativeProfileScreen.Button>();
        if (security) {
            if (profile.hasPassword()) buttons.add(button(player, "11", "gui-change-password-name"));
            buttons.add(button(player, "12", profile.hasPassword() ? "gui-unlock-name" : "gui-lock-name"));
            if (plugin.isMasterProfile(player, plugin.getActiveProfile(player)))
                buttons.add(button(player, profile.isMaster() ? "16" : "DELETE", profile.isMaster() ? "gui-master-password-name" : "gui-delete-name"));
        } else {
            buttons.add(button(player, "13", "gui-icon-name"));
            buttons.add(button(player, "15", "gui-rename-name"));
        }
        show(player, view(player, text(player, security ? "screen-security" : "screen-appearance"), List.of(),
                List.of(characterCard(player, profile, "NONE")), buttons, button(player, "BACK", "gui-back-name"), 3, 100), (action, value) -> {
            if (action.equals("BACK")) openProfileSettings(player, profile.getName());
            else if (action.equals("DELETE")) confirmDelete(player, profile.getName());
            else handleSettingsClick(player, profile.getName(), Integer.parseInt(action));
        });
    }

    private void openNativeInfo(Player player, ProfileMeta profile) {
        var lines = new java.util.ArrayList<String>();
        lines.add(text(player, profile.isMaster() ? "gui-profile-lore-master" : "screen-type-standard"));
        lines.add(text(player, "screen-created-at", "date", date(player, profile.getCreatedAt())));
        lines.add(text(player, "screen-last-connected", "date", date(player, profile.getLastConnectedAt())));
        lines.addAll(timeLore(player, profile, plugin.getActiveProfile(player)));
        show(player, view(player, text(player, "screen-profile-info"), lines, List.of(), List.of(), button(player, "BACK", "gui-back-name"), 1, 200),
                (action, value) -> openProfileSettings(player, profile.getName()));
    }

    private void openNativeStatistics(Player player, ProfileMeta profile) {
        org.bukkit.configuration.file.YamlConfiguration progress = plugin.profileProgress(player, profile.getName());
        if (progress != null) profile.updateTypedStatistics(progress.getConfigurationSection("statistics-typed"));
        var lines = new java.util.ArrayList<>(statsLore(player, profile));
        lines.add(text(player, "screen-blocks-mined", "count", profile.getBlocksMined()));
        lines.add(text(player, "screen-items-crafted", "count", profile.getItemsCrafted()));
        lines.add(text(player, "screen-items-used", "count", profile.getItemsUsed()));
        lines.add(text(player, "screen-recipes", "count", progress == null ? 0 : progress.getStringList("recipes").size()));
        show(player, view(player, text(player, "screen-statistics-title"), lines, List.of(), List.of(button(player, "REFRESH", "screen-refresh")),
                button(player, "BACK", "gui-back-name"), 1, 200), (action, value) -> {
            if (action.equals("REFRESH")) openNativeStatistics(player, profile); else openProfileSettings(player, profile.getName());
        });
    }

    private NativeProfileScreen.Card characterCard(Player player, ProfileMeta profile, String action) {
        boolean active = AccountData.normalizeName(profile.getName()).equals(plugin.getActiveProfile(player));
        boolean unavailable = plugin.getRemainingPlayMillis(player, profile) <= 0;
        boolean locked = profile.hasPassword() && (!active || requiredSelections.contains(player.getUniqueId()));
        String color = unavailable || locked ? "&c" : active ? "&a" : "&f";
        String status = text(player, unavailable ? "screen-status-exhausted" : locked ? "screen-status-locked" : active ? "screen-status-active" : "screen-status-ready");
        List<String> details = new java.util.ArrayList<>(profileDetails(player, profile));
        details.set(0, color + "&l" + profile.getName());
        return new NativeProfileScreen.Card(profileIcon(profile, color + "&l" + profile.getName(), details.subList(1, details.size())),
                List.of(color + "&l" + profile.getName(), status), action, details);
    }

    private List<String> profileDetails(Player player, ProfileMeta profile) {
        var lines = new java.util.ArrayList<String>();
        lines.add("&f&l" + profile.getName());
        lines.add(text(player, profile.isMaster() ? "gui-profile-lore-master" : "screen-type-standard"));
        lines.add(text(player, profile.hasPassword() ? "gui-profile-lore-locked" : "gui-profile-lore-unlocked"));
        lines.add(text(player, "screen-created-at", "date", date(player, profile.getCreatedAt())));
        lines.add(text(player, "screen-last-connected", "date", date(player, profile.getLastConnectedAt())));
        lines.addAll(statsLore(player, profile));
        lines.addAll(timeLore(player, profile, plugin.getActiveProfile(player)));
        return lines;
    }

    private String date(Player player, long timestamp) {
        return timestamp <= 0 ? text(player, "screen-date-unknown") : java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(timestamp));
    }

    private int nativePageSize(String key) {
        return Math.max(1, Math.min(3, plugin.getConfig().getInt("gui.custom-screens." + key, 3)));
    }

    private String pagedTitle(String heading, int page, int pages) {
        return pages > 1 ? heading + " &r&7(" + (page + 1) + "/" + pages + ")" : heading;
    }

    private void confirmDelete(Player player, String profileName) {
        show(player, view(player, text(player, "gui-delete-name"), List.of(text(player, "screen-delete-confirm", "profile", profileName)), List.of(),
                List.of(button(player, "DELETE", "gui-delete-name")), button(player, "BACK", "gui-back-name"), 1, 200), (action, value) -> {
            if (action.equals("DELETE")) requireMasterEdit(player, profileName, PendingInputType.MASTER_DELETE_PROFILE);
            else openProfileSettings(player, profileName);
        });
    }

    private void openNativeIcons(Player player, ProfileMeta profile) {
        List<String> keys = filteredIconKeys(player);
        int pageSize = nativePageSize("icons-per-page");
        int page = page(iconPages, player, keys.size(), pageSize);
        int pages = Math.max(1, (keys.size() + pageSize - 1) / pageSize);
        var cards = new java.util.ArrayList<NativeProfileScreen.Card>();
        var buttons = new java.util.ArrayList<NativeProfileScreen.Button>();
        for (String key : keys.subList(page * pageSize, Math.min(keys.size(), (page + 1) * pageSize))) {
            boolean selected = key.equals(resolveIconKey(profile)) && profile.getCustomTexture() == null;
            String action = "APPLY:" + key;
            cards.add(new NativeProfileScreen.Card(iconChoiceItem(player, key, selected),
                    List.of((selected ? "&a" : "&f") + "&l" + iconDisplayName(key)), action,
                    List.of(iconDisplayName(key), text(player, selected ? "screen-icon-current" : "screen-icon-preview")),
                    List.of(button(player, action, "screen-icon-apply"))));
        }
        if (pages > 1) {
            buttons.add(button(player, page > 0 ? "PREVIOUS" : "NONE", "screen-previous"));
            buttons.add(new NativeProfileScreen.Button("NONE", "&7" + (page + 1) + " / " + pages));
            buttons.add(button(player, page + 1 < pages ? "NEXT" : "NONE", "screen-next"));
        }
        buttons.add(button(player, "SEARCH", "screen-search"));
        buttons.add(button(player, iconQueries.getOrDefault(player.getUniqueId(), "").isEmpty() ? "NONE" : "CLEAR", "screen-clear-search"));
        if (plugin.getConfig().getBoolean("gui.custom-heads.allow-import", true)) buttons.add(button(player, "IMPORT", "screen-import-head"));
        show(player, view(player, pagedTitle(text(player, "screen-icons-title", "profile", profile.getName()), page, pages),
                keys.isEmpty() ? List.of(text(player, "screen-no-icons")) : List.of(), cards,
                buttons, button(player, "BACK", "gui-back-name"), 3, 100), (action, value) -> {
            if (!canEditProfileBasics(player, profile.getName())) return;
            if (action.startsWith("APPLY:")) {
                String key = action.substring(6);
                if (!keys.contains(key)) { openIconPicker(player, profile.getName()); return; }
                profile.setIconKey(key);
                profile.setCustomTexture(null);
                plugin.saveAccount(player.getUniqueId());
                plugin.send(player, "profile-icon-updated", "profile", profile.getName(), "icon", iconDisplayName(key));
                openProfileSettings(player, profile.getName());
            } else switch (action) {
                case "SEARCH" -> askText(player, new PendingInput(PendingInputType.SEARCH_ICONS, profile.getName()), "screen-search-prompt");
                case "IMPORT" -> startHeadImport(player, profile.getName());
                case "CLEAR" -> { iconQueries.remove(player.getUniqueId()); iconPages.remove(player.getUniqueId()); openIconPicker(player, profile.getName()); }
                case "NEXT", "PREVIOUS" -> { iconPages.put(player.getUniqueId(), page + (action.equals("NEXT") ? 1 : -1)); openIconPicker(player, profile.getName()); }
                default -> openProfileSettings(player, profile.getName());
            }
        });
    }

    private void openNativePin(Player player, PendingInput input, String messageKey, String error, Object... replacements) {
        pendingInputs.put(player.getUniqueId(), input);
        var lines = new java.util.ArrayList<String>();
        lines.add(text(player, "screen-pin-help"));
        var slots = new StringBuilder(error == null ? "&f&l" : "&c&l");
        for (int index = 0; index < plugin.getMaxPinLength(); index++) {
            if (index > 0) slots.append(" ");
            slots.append(index < input.getValue().length() ? "[■]" : "[_]");
        }
        lines.add(slots + "\n&r&7──────────────────────");
        lines.add(text(player, "screen-pin-length", "length", input.getValue().length(), "min", plugin.getMinPinLength(), "max", plugin.getMaxPinLength()));
        lines.add(error == null ? " " : error);
        var buttons = new java.util.ArrayList<NativeProfileScreen.Button>();
        for (int digit = 1; digit <= 9; digit++) buttons.add(new NativeProfileScreen.Button("DIGIT:" + digit, "&f&l" + digit));
        buttons.add(new NativeProfileScreen.Button("BACKSPACE", "&e←", text(player, "gui-password-backspace-name")));
        buttons.add(new NativeProfileScreen.Button("DIGIT:0", "&f&l0"));
        buttons.add(button(player, "CLEAR", "screen-pin-clear"));
        buttons.add(button(player, "CANCEL", "gui-password-cancel-name"));
        String heading = text(player, messageKey, replacements.length == 0 ? new Object[]{"profile", input.getProfileName() == null ? "" : input.getProfileName()} : replacements)
                .replaceFirst("(?i)^&0", "&6&l");
        show(player, view(player, heading,
                lines, List.of(), buttons, button(player, "SUBMIT", "gui-password-confirm-name"), 3, 48), (action, value) -> {
            if (pendingInputs.get(player.getUniqueId()) != input) return;
            if (action.startsWith("DIGIT:")) {
                if (input.getValue().length() >= plugin.getMaxPinLength()) { openNativePin(player, input, messageKey, text(player, "pin-too-long", "max", plugin.getMaxPinLength()), replacements); return; }
                openPasswordInput(player, input.withValue(input.getValue() + action.substring(6)), messageKey, replacements);
            } else switch (action) {
                case "BACKSPACE" -> openPasswordInput(player, input.withValue(input.getValue().isEmpty() ? "" : input.getValue().substring(0, input.getValue().length() - 1)), messageKey, replacements);
                case "CLEAR" -> openPasswordInput(player, input.withValue(""), messageKey, replacements);
                case "CANCEL" -> { pendingInputs.remove(player.getUniqueId()); openMain(player); }
                case "SUBMIT" -> {
                    if (!isValidPasswordValue(input.getValue())) openNativePin(player, input, messageKey,
                            text(player, pinValidationErrorKey(input.getValue()), "min", plugin.getMinPinLength(), "max", plugin.getMaxPinLength()), replacements);
                    else handlePasswordInput(player, input, input.getValue());
                }
                default -> openPasswordInput(player, input, messageKey, replacements);
            }
        });
    }

    private void openNativeText(Player player, PendingInput input) {
        String key = switch (input.getType()) {
            case CREATE_PROFILE -> "screen-create-title";
            case RENAME_PROFILE -> "gui-rename-name";
            case SEARCH_ICONS -> "screen-search";
            default -> "screen-import-head";
        };
        List<String> help = input.getType() == PendingInputType.IMPORT_HEAD ? lore(player, "screen-import-help")
                : List.of(text(player, input.getType() == PendingInputType.SEARCH_ICONS ? "screen-search-help" : "screen-text-help"));
        int max = input.getType() == PendingInputType.IMPORT_HEAD ? 8192 : input.getType() == PendingInputType.SEARCH_ICONS ? 64 : 16;
        NativeProfileScreen.View view = new NativeProfileScreen.View(text(player, key), help, List.of(),
                List.of(button(player, "CANCEL", "gui-password-cancel-name")), button(player, "SAVE", "gui-password-confirm-name"), 1, 200, 304,
                !requiredSelections.contains(player.getUniqueId()), true, input.getValue().substring(0, Math.min(max, input.getValue().length())), max);
        show(player, view, (action, value) -> {
            if (!pendingInputs.remove(player.getUniqueId(), input)) return;
            if (action.equals("SAVE")) {
                if (value == null || value.isBlank()) retryText(player, input, "screen-input-required");
                else if (value.length() > max) retryText(player, input, "screen-input-too-long", "max", max);
                else handleTextInput(player, input, value.trim());
            } else openMain(player);
        });
    }

    private void retryText(Player player, PendingInput input, String key, Object... replacements) {
        pendingInputs.put(player.getUniqueId(), input);
        plugin.send(player, key, replacements);
        if (!modern(player, () -> openNativeText(player, input))) openMain(player);
    }

    private void openNativeTime(Player player, ProfileMeta profile, TimeLimitDraft draft) {
        var buttons = new java.util.ArrayList<NativeProfileScreen.Button>();
        int[] slots = {9, 10, 12, 13, 14, 16, 17, 4};
        String[] keys = {"gui-time-minus-30-name", "gui-time-minus-5-name", "gui-time-plus-5-name", "gui-time-plus-30-name",
                "gui-time-renew-minus-1-name", "gui-time-renew-plus-1-name", "gui-time-renew-plus-24-name", "gui-time-reset-name"};
        for (int index = 0; index < slots.length; index++) buttons.add(button(player, String.valueOf(slots[index]), keys[index]));
        buttons.add(button(player, "22", "gui-back-name"));
        show(player, view(player, text(player, "screen-time-title", "profile", profile.getName()), List.of(
                        text(player, "gui-time-max-name", "minutes", draft.getMaxMinutes()), text(player, "gui-time-renew-name", "hours", draft.getRenewHours())),
                List.of(), buttons, button(player, "23", "gui-time-confirm-name"), 2, 150),
                (action, value) -> handleTimeLimitClick(player, profile.getName(), Integer.parseInt(action)));
    }

    private List<String> statsLore(Player player, ProfileMeta profile) {
        if (!plugin.getConfig().getBoolean("gui.show-statistics", true)) return List.of();
        boolean active = AccountData.normalizeName(profile.getName()).equals(plugin.getActiveProfile(player));
        long played = profile.getTotalPlayedMillis() + (active ? plugin.getActiveSessionMillis(player) : 0L);
        return List.of(text(player, "screen-total-playtime", "time", formatDuration(played)),
                text(player, "screen-level-deaths", "level", active ? player.getLevel() : profile.getLevel(), "deaths", active ? player.getStatistic(org.bukkit.Statistic.DEATHS) : profile.getDeaths()),
                text(player, "screen-kills", "mobs", active ? player.getStatistic(org.bukkit.Statistic.MOB_KILLS) : profile.getMobKills(), "players", active ? player.getStatistic(org.bukkit.Statistic.PLAYER_KILLS) : profile.getPlayerKills()));
    }

    private void startHeadImport(Player player, String profileName) {
        if (!plugin.getConfig().getBoolean("gui.custom-heads.allow-import", true) || !canEditProfileBasics(player, profileName)) return;
        askText(player, new PendingInput(PendingInputType.IMPORT_HEAD, profileName), "screen-import-prompt");
    }

    public void importHead(Player player, String profileName, String value) {
        if (!ready(player) || !canEditProfileBasics(player, profileName) || !plugin.getConfig().getBoolean("gui.custom-heads.allow-import", true)) return;
        ProfileMeta profile = plugin.loadAccount(player.getUniqueId()).getProfile(profileName);
        if (profile == null) { plugin.send(player, "profile-not-found"); return; }
        String normalized;
        try { normalized = HeadTextures.normalize(value); }
        catch (IllegalArgumentException invalid) { retryText(player, new PendingInput(PendingInputType.IMPORT_HEAD, profileName, value), "screen-import-invalid"); return; }
        if (modern(player, () -> show(player, view(player, text(player, "screen-import-preview"), List.of(), List.of(
                        new NativeProfileScreen.Card(ItemFactory.head(normalized, profile.getName(), List.of()), List.of(text(player, "screen-import-confirm")), "SAVE", List.of(text(player, "screen-import-confirm")))),
                List.of(button(player, "SAVE", "gui-password-confirm-name")), button(player, "CANCEL", "gui-password-cancel-name"), 1, 200), (action, ignored) -> {
            if (action.equals("SAVE") && canEditProfileBasics(player, profileName)) saveImportedHead(player, profile, normalized);
            else openIconPicker(player, profileName);
        }))) return;
        saveImportedHead(player, profile, normalized);
    }

    private void saveImportedHead(Player player, ProfileMeta profile, String normalized) {
        profile.setCustomTexture(normalized);
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "profile-icon-updated", "profile", profile.getName(), "icon", text(player, "screen-custom-head"));
        openProfileSettings(player, profile.getName());
    }

    private List<String> filteredIconKeys(Player player) {
        String query = iconQueries.getOrDefault(player.getUniqueId(), "").toLowerCase(Locale.ROOT);
        return iconKeys().stream().filter(key -> key.toLowerCase(Locale.ROOT).contains(query)
                || iconDisplayName(key).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private int page(Map<UUID, Integer> pages, Player player, int size, int pageSize) {
        int page = Math.max(0, Math.min(pages.getOrDefault(player.getUniqueId(), 0), Math.max(0, (size - 1) / pageSize)));
        pages.put(player.getUniqueId(), page);
        return page;
    }

    private boolean isTextInput(PendingInput input) {
        return switch (input.getType()) { case CREATE_PROFILE, RENAME_PROFILE, IMPORT_HEAD, SEARCH_ICONS, SET_LIMIT -> true; default -> false; };
    }

    public boolean isSelectionRequired(Player player) { return requiredSelections.contains(player.getUniqueId()); }

    public void editIcon(Player player, String profileName) {
        requireProfileBasicEdit(player, profileName, PendingInputType.MASTER_PICK_ICON);
    }

    public void createFromMenu(Player player) { startCreateProfile(player); }

    public void setMenuMode(Player player, String mode) {
        if (screens != null) screens.close(player);
        plugin.loadAccount(player.getUniqueId()).setMenuMode(mode);
        plugin.saveAccount(player.getUniqueId());
        legacyPlayers.remove(player.getUniqueId());
        mainPages.remove(player.getUniqueId());
        openMain(player);
    }

    public void clearSession(Player player) {
        UUID id = player.getUniqueId();
        pendingInputs.remove(id); timeLimitDrafts.remove(id); requiredSelections.remove(id);
        mainPages.remove(id); iconPages.remove(id); iconQueries.remove(id); legacyPlayers.remove(id);
        presentations.remove(id); notices.remove(id); handlingActions.remove(id); transitions.remove(id);
        if (screens != null) screens.forget(player);
    }

    public void shutdown() {
        requiredSelections.clear(); pendingInputs.clear(); timeLimitDrafts.clear();
        presentations.clear(); notices.clear(); transitions.clear(); handlingActions.clear();
        if (screens != null) screens.shutdown();
    }

    private void closeNative(Player player) {
        presentations.remove(player.getUniqueId()); notices.remove(player.getUniqueId()); transitions.remove(player.getUniqueId());
        if (screens != null) screens.close(player);
    }

    public void openLanguagePicker(Player player) {
        if (!ready(player)) return;
        if (modern(player, () -> {
            var buttons = new java.util.ArrayList<NativeProfileScreen.Button>();
            String selected = plugin.loadAccount(player.getUniqueId()).getPreferredLanguage();
            for (String language : es.mrdino.perplayerprofiles.lang.LanguageService.languageOptions()) {
                String name = language.equals("auto") ? text(player, "screen-language-auto") : es.mrdino.perplayerprofiles.lang.LanguageService.languageName(language);
                buttons.add(new NativeProfileScreen.Button(language, (language.equals(selected) ? "&a✓ " : "&f") + name));
            }
            show(player, view(player, text(player, "screen-language"), List.of(text(player, "screen-language-help")), List.of(), buttons,
                    button(player, "BACK", "gui-back-name"), 3, 100), (action, value) -> {
                if (action.equals("BACK")) openMain(player); else setLanguage(player, action);
            });
        })) return;
        Inventory inventory = Bukkit.createInventory(new GuiHolder(GuiType.LANGUAGE, null), 27, title(player, "screen-language"));
        fill(inventory);
        int slot = 10;
        for (String language : es.mrdino.perplayerprofiles.lang.LanguageService.languageOptions())
            inventory.setItem(slot++, ItemFactory.item(Material.PAPER, language.equals("auto") ? text(player, "screen-language-auto") : es.mrdino.perplayerprofiles.lang.LanguageService.languageName(language), List.of()));
        inventory.setItem(22, ItemFactory.item(Material.ARROW, text(player, "gui-back-name"), List.of()));
        player.openInventory(inventory);
    }

    public void setLanguage(Player player, String language) {
        String selected = language.toLowerCase(Locale.ROOT);
        if (!es.mrdino.perplayerprofiles.lang.LanguageService.languageOptions().contains(selected)) { plugin.send(player, "usage-language"); return; }
        plugin.loadAccount(player.getUniqueId()).setPreferredLanguage(selected);
        plugin.saveAccount(player.getUniqueId());
        plugin.send(player, "language-updated", "language", selected.equals("auto") ? text(player, "screen-language-auto") : es.mrdino.perplayerprofiles.lang.LanguageService.languageName(selected));
        openMain(player);
    }

    public void playTransition(Player player, ProfileMeta previous, ProfileMeta current) {
        if (!plugin.getConfig().getBoolean("gui.transition.enabled", true)) return;
        int ticks = Math.max(4, Math.min(40, plugin.getConfig().getInt("gui.transition.duration-ticks", 12)));
        UUID token = UUID.randomUUID(); transitions.put(player.getUniqueId(), token);
        if (!modern(player, () -> renderTransition(player, previous, current, 0, token))) {
            transitions.remove(player.getUniqueId());
            player.sendTitle(title(player, "screen-transition-title"), title(player, "screen-transition-target", "profile", current.getName()), 2, ticks, 6);
            return;
        }
        for (int frame = 1; frame <= 3; frame++) {
            int step = frame;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || !token.equals(transitions.get(player.getUniqueId())) || !plugin.isProfileReady(player)) return;
                if (step == 3) closeNative(player); else renderTransition(player, previous, current, step, token);
            }, Math.max(1, ticks * frame / 3));
        }
    }

    private void renderTransition(Player player, ProfileMeta previous, ProfileMeta current, int frame, UUID token) {
        var cards = new java.util.ArrayList<NativeProfileScreen.Card>();
        if (previous != null) cards.add(new NativeProfileScreen.Card(profileIcon(previous, previous.getName(), List.of()), List.of("&7" + previous.getName())));
        cards.add(new NativeProfileScreen.Card(profileIcon(current, current.getName(), List.of()), List.of("&a&l" + current.getName())));
        String progress = "&a" + "━".repeat((frame + 1) * 4) + "&8" + "━".repeat((2 - frame) * 4);
        show(player, view(player, text(player, "screen-transition-title"), List.of(progress), cards, List.of(),
                button(player, "SKIP", "screen-transition-skip"), 1, 150), (action, value) -> { if (token.equals(transitions.get(player.getUniqueId()))) closeNative(player); }, true);
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
