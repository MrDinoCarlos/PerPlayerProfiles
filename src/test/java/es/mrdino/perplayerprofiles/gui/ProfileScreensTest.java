package es.mrdino.perplayerprofiles.gui;

import es.mrdino.perplayerprofiles.PerPlayerProfilesPlugin;
import es.mrdino.perplayerprofiles.lang.LanguageService;
import es.mrdino.perplayerprofiles.model.AccountData;
import es.mrdino.perplayerprofiles.model.ProfileMeta;
import es.mrdino.perplayerprofiles.security.PinSecurity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileScreensTest {
    @Test void nativeSelectorPaginatesAllProfilesAndDisablesUnavailableOnes() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture();
            for (int index = 1; index < 10; index++) fixture.account.putProfile(new ProfileMeta("Profile" + index));
            fixture.gui.openMain(fixture.player);
            assertEquals(3, fixture.screen.view.cards().size());
            assertEquals(4, fixture.screen.view.buttons().size());
            assertEquals(1, fixture.screen.view.cards().get(0).lines().size());
            assertEquals(2, fixture.screen.view.cards().get(0).links().size());
            assertTrue(fixture.screen.view.cards().get(0).tooltip().size() > 2);
            assertTrue(fixture.screen.actions().contains("NEXT"));
            List<String> seen = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                fixture.screen.view.cards().forEach(card -> seen.add(card.links().get(1).action()));
                fixture.screen.click("NEXT", null);
            }
            fixture.screen.view.cards().forEach(card -> seen.add(card.links().get(1).action()));
            assertEquals(10, seen.stream().distinct().count());
            assertTrue(fixture.screen.view.cards().get(0).lines().get(0).contains("Profile9"));
            fixture.screen.click("USE:Profile9", null);
            verify(fixture.plugin).switchProfile(fixture.player, "Profile9");
            assertFalse(fixture.screen.actions().contains("NEXT"));
        }
    }
    @Test void wrongPinCanBeRetriedAndMandatorySelectionCannotBeEscapedOrEdited() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture(); ProfileMeta locked = new ProfileMeta("Locked"); locked.setPasswordHash("stored"); fixture.account.putProfile(locked);
            fixture.account.getProfile("master").setPasswordHash("master-stored");
            when(fixture.security.profile(fixture.player, locked, "1234", true)).thenReturn(false, true);
            fixture.gui.requireProfileSelection(fixture.player); fixture.gui.openMain(fixture.player);
            assertFalse(fixture.screen.view.canClose()); assertFalse(fixture.screen.actions().contains("CREATE"));
            assertEquals(2, fixture.screen.view.cards().size());
            assertTrue(fixture.screen.view.cards().get(0).lines().get(0).startsWith("&c"), "The loaded fallback profile still needs its PIN during mandatory selection");
            assertFalse(fixture.screen.actions().contains("NEXT"));
            assertTrue(fixture.screen.view.cards().get(1).lines().get(0).startsWith("&c"));
            assertTrue(fixture.screen.view.cards().get(1).lines().get(0).contains("[PIN]"));
            fixture.screen.click("DETAILS:Locked", null);
            assertEquals(List.of("10", "INFO", "STATS"), fixture.screen.actions());
            fixture.screen.click("10", null);
            for (int attempt = 0; attempt < 2; attempt++) {
                for (int digit : new int[]{1, 2, 3, 4}) fixture.screen.click("DIGIT:" + digit, null);
                fixture.screen.click("SUBMIT", null);
            }
            verify(fixture.plugin).switchProfile(fixture.player, "Locked");
        }
    }
    @Test void nativeProfileCreationUsesAFormInsteadOfChat() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture(); fixture.gui.openMain(fixture.player);
            fixture.screen.click("CREATE", null);
            assertTrue(fixture.screen.view.textEntry()); assertEquals(16, fixture.screen.view.maxLength());
            fixture.screen.click("SAVE", "MyCharacter");
            assertTrue(fixture.account.hasProfile("mycharacter"));
            verify(fixture.plugin).saveAccount(fixture.id);
        }
    }

    @Test void invalidNameAndDuplicateStayInTheFormWithInputAndInlineError() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture(); fixture.gui.openMain(fixture.player); fixture.screen.click("CREATE", null);
            fixture.screen.click("SAVE", "Bad Name");
            assertTrue(fixture.screen.view.textEntry());
            assertEquals("Bad Name", fixture.screen.view.initialText());
            assertTrue(fixture.screen.view.lines().toString().contains("invalid-profile-name"));
            fixture.screen.click("SAVE", "master");
            assertEquals("master", fixture.screen.view.initialText());
            assertTrue(fixture.screen.view.lines().toString().contains("profile-exists"));
            fixture.screen.click("SAVE", "ValidName");
            assertTrue(fixture.account.hasProfile("ValidName"));
        }
    }

    @Test void recoveryRequiresANewPinAndInvalidatesAnAlreadyOpenPinEntry() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture(); ProfileMeta profile = fixture.account.getProfile("master");
            profile.setPasswordHash("previous-hash"); fixture.gui.selectProfile(fixture.player, "master");
            var staleHandler = fixture.screen.handler;
            profile.requireNewPassword(); fixture.gui.onPasswordRecovered(fixture.player, "master", true);
            assertTrue(fixture.gui.isSelectionRequired(fixture.player));
            assertFalse(fixture.screen.view.canClose());
            assertEquals("prompt-recover-profile-password", fixture.screen.view.title());
            staleHandler.accept("SUBMIT", null);
            assertTrue(profile.isPasswordResetRequired());
            for (int digit : new int[]{5, 6, 7, 8}) fixture.screen.click("DIGIT:" + digit, null);
            fixture.screen.click("SUBMIT", null);
            assertFalse(profile.isPasswordResetRequired());
            assertTrue(es.mrdino.perplayerprofiles.security.PasswordHasher.verify("5678", profile.getPasswordHash()));
            verify(fixture.plugin).switchProfile(fixture.player, "master");
        }
    }

    @Test void languageChoiceIsStoredForThisPlayer() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture(); fixture.gui.openLanguagePicker(fixture.player);
            assertEquals(List.of("auto", "en", "es", "de", "fr"), fixture.screen.actions());
            fixture.screen.click("es", null);
            assertEquals("es", fixture.account.getPreferredLanguage());
            verify(fixture.plugin).saveAccount(fixture.id);
        }
    }

    @Test void failedMenuActionsDisplayAnInlineErrorAndTheNextClickStillWorks() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture();
            when(fixture.plugin.switchProfile(fixture.player, "master")).thenThrow(new IllegalStateException("test failure")).thenReturn(true);
            fixture.gui.openMain(fixture.player); fixture.screen.click("USE:master", null);
            assertTrue(fixture.screen.view.lines().toString().contains("screen-action-failed"));
            fixture.screen.click("USE:master", null);
            verify(fixture.plugin, times(2)).switchProfile(fixture.player, "master");
        }
    }

    @Test void pageSizeIsConfigurableAndOnlyShowsNavigationAfterThePageIsFull() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture(); fixture.plugin.getConfig().set("gui.custom-screens.profiles-per-page", 2);
            fixture.account.putProfile(new ProfileMeta("Second"));
            fixture.gui.openMain(fixture.player);
            assertEquals(2, fixture.screen.view.cards().size()); assertFalse(fixture.screen.actions().contains("NEXT"));
            ProfileMeta exhausted = new ProfileMeta("Exhausted"); exhausted.setTimeLimit(60_000, 3_600_000); exhausted.addUsedMillis(60_000);
            fixture.account.putProfile(exhausted); fixture.gui.openMain(fixture.player);
            assertTrue(fixture.screen.actions().contains("NEXT")); fixture.screen.click("NEXT", null);
            assertEquals(1, fixture.screen.view.cards().size());
            NativeProfileScreen.Card card = fixture.screen.view.cards().get(0);
            assertTrue(card.lines().get(0).startsWith("&c")); assertTrue(card.lines().get(0).contains("[!]"));
            assertEquals("NONE", card.links().get(0).action());
            assertFalse(fixture.screen.actions().contains("USE:Exhausted"));
            assertTrue(fixture.screen.actions().contains("DETAILS:Exhausted"));
        }
    }

    @Test void iconCatalogShowsSeveralHeadsTogetherAndEveryIconCanBeSelected() {
        try (var items = mockStatic(ItemFactory.class)) {
            items.when(() -> ItemFactory.head(anyString(), anyString(), anyList())).thenReturn(mock(ItemStack.class));
            Fixture fixture = new Fixture();
            for (int index = 1; index < 8; index++) fixture.plugin.getConfig().set("gui.profile-icons.icons.icon" + index + ".texture", "texture");
            fixture.gui.openIconPicker(fixture.player, "master");
            assertEquals(3, fixture.screen.view.cards().size());
            fixture.screen.click("NEXT", null); fixture.screen.click("NEXT", null);
            assertEquals(2, fixture.screen.view.cards().size()); assertFalse(fixture.screen.actions().contains("NEXT"));
            fixture.screen.click("APPLY:icon7", null);
            assertEquals("icon7", fixture.account.getProfile("master").getIconKey());
            verify(fixture.plugin).saveAccount(fixture.id);
        }
    }

    private static final class Fixture {
        final PerPlayerProfilesPlugin plugin = mock(PerPlayerProfilesPlugin.class);
        final Player player = mock(Player.class);
        final AccountData account = new AccountData();
        final UUID id = UUID.randomUUID();
        final Screen screen = new Screen();
        final PinSecurity security = mock(PinSecurity.class);
        final ProfileGuiManager gui;
        Fixture() {
            var config = new YamlConfiguration(); config.set("gui.profile-icons.default", "default"); config.set("gui.profile-icons.icons.default.texture", "texture");
            when(plugin.getConfig()).thenReturn(config); when(plugin.loadAccount(id)).thenReturn(account);
            when(plugin.getPinSecurity()).thenReturn(security);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(player.getUniqueId()).thenReturn(id); when(plugin.isProfileReady(player)).thenReturn(true);
            when(plugin.getActiveProfile(player)).thenReturn("master"); when(plugin.getMasterProfileName()).thenReturn("master");
            when(plugin.isMasterProfile(player, "master")).thenReturn(true); when(plugin.getMaxProfilesPerAccount()).thenReturn(20);
            when(plugin.getMinPinLength()).thenReturn(4); when(plugin.getMaxPinLength()).thenReturn(9);
            when(plugin.getRemainingPlayMillis(eq(player), any(ProfileMeta.class))).thenAnswer(call -> ((ProfileMeta)call.getArgument(1)).getRemainingMillis(System.currentTimeMillis()));
            var master = new ProfileMeta("master"); master.setMaster(true); account.putProfile(master);
            var lang = mock(LanguageService.class); when(plugin.getLanguageService()).thenReturn(lang);
            when(lang.get(anyString(), any(Object[].class))).thenAnswer(call -> call.getArgument(0));
            when(lang.getStringList(anyString(), any(Object[].class))).thenReturn(List.of());
            when(lang.get(eq(player), anyString(), any(Object[].class))).thenAnswer(call -> call.getArgument(1));
            when(lang.getStringList(eq(player), anyString(), any(Object[].class))).thenReturn(List.of());
            var inventoryView = mock(InventoryView.class); when(player.getOpenInventory()).thenReturn(inventoryView);
            when(inventoryView.getTopInventory()).thenReturn(mock(Inventory.class));
            gui = new ProfileGuiManager(plugin, screen);
            doAnswer(call -> { gui.reportMessage(player, call.getArgument(1)); return null; })
                    .when(plugin).send(eq(player), anyString(), any(Object[].class));
        }
    }
    private static final class Screen implements NativeProfileScreen {
        View view; BiConsumer<String, String> handler;
        @Override public boolean supports(Player player) { return true; }
        @Override public void show(Player player, View view, BiConsumer<String, String> handler) { this.view = view; this.handler = handler; }
        List<String> actions() { return view.actions().subList(0, view.actions().size() - (view.footer() == null ? 0 : 1)).stream().map(Button::action).toList(); }
        void click(String action, String value) { handler.accept(action, value); }
        @Override public void close(Player player) { }
        @Override public void forget(Player player) { }
        @Override public void shutdown() { }
    }
}
