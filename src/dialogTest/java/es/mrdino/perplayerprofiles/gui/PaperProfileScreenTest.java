package es.mrdino.perplayerprofiles.gui;

import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.papermc.paper.registry.data.dialog.type.NoticeType;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real dialog builders and Adventure components. Native item serialization is checked by the server smoke test. */
class PaperProfileScreenTest {
    @Test void eachCharacterHasIndividualInlineActionsAndHoverDetails() {
        var buttons = List.of(new NativeProfileScreen.Button("CREATE", "Create"), new NativeProfileScreen.Button("LANGUAGE", "Language"));
        var cards = new java.util.ArrayList<NativeProfileScreen.Card>();
        for (int index = 0; index < 3; index++) cards.add(new NativeProfileScreen.Card(null, List.of("&cCharacter" + index + " [PIN]"),
                "DETAILS:" + index, List.of("&cCharacter" + index, "Created yesterday", "Time remaining: 2h"),
                List.of(new NativeProfileScreen.Button("USE:" + index, "Select character"), new NativeProfileScreen.Button("DETAILS:" + index, "Details"))));
        var view = new NativeProfileScreen.View("Character", List.of(), cards, buttons,
                new NativeProfileScreen.Button("CLOSE", "Close"), 2, 150, 304, true, false, "", 16);
        for (int index = 0; index < 3; index++) {
            Component description = PaperProfileScreen.createDescription(view, cards.get(index), "random-token");
            List<Component> components = descendants(description);
            assertFalse(components.stream().anyMatch(component -> component instanceof TextComponent text && text.content().contains("█")));
            int playIndex = 2 + index * 2, infoIndex = playIndex + 1;
            assertTrue(components.stream().anyMatch(component -> component.clickEvent() != null && component.clickEvent().toString().contains("random-token/" + playIndex)));
            assertTrue(components.stream().anyMatch(component -> component.clickEvent() != null && component.clickEvent().toString().contains("random-token/" + infoIndex)));
            assertNotNull(description.hoverEvent());
        }
        var type = assertInstanceOf(MultiActionType.class, PaperProfileScreen.createType(view, "random-token"));
        assertEquals(2, type.columns()); assertEquals(2, type.actions().size());
        assertEquals(300, type.exitAction().width());
        assertTrue(type.exitAction().action().toString().contains("random-token/8"));
    }

    @Test void anUnavailableCharacterKeepsDetailsButItsSelectLinkHasNoAction() {
        var card = new NativeProfileScreen.Card(null, List.of("&cLocked [!]"), "DETAILS", List.of("Time exhausted"),
                List.of(new NativeProfileScreen.Button("NONE", "Select character"), new NativeProfileScreen.Button("DETAILS", "Details")));
        var view = new NativeProfileScreen.View("Character", List.of(), List.of(card), List.of(),
                new NativeProfileScreen.Button("CLOSE", "Close"), 2, 150, 304, true, false, "", 16);
        Component description = PaperProfileScreen.createDescription(view, card, "token");
        assertNull(description.clickEvent());
        assertTrue(descendants(description).stream().filter(component -> component instanceof TextComponent text && text.content().contains("Select character"))
                .allMatch(component -> component.clickEvent() == null));
        assertTrue(descendants(description).stream().anyMatch(component -> component.clickEvent() != null && component.clickEvent().toString().contains("token/1")));
    }

    private static List<Component> descendants(Component component) {
        var result = new java.util.ArrayList<Component>(); result.add(component);
        for (Component child : component.children()) result.addAll(descendants(child));
        return result;
    }
    @Test void keypadHasCompactServerPasswordLayoutAndCannotBeEscapedWhenSelectionIsRequired() {
        var view = new NativeProfileScreen.View("&6PIN", List.of("[■] [_] [_] [_]"), List.of(),
                List.of(new NativeProfileScreen.Button("DIGIT:0", "0")), new NativeProfileScreen.Button("SUBMIT", "Confirm"),
                3, 48, 304, false, false, "", 9);
        DialogBase base = assertDoesNotThrow(() -> PaperProfileScreen.createBase(view));
        assertFalse(base.pause()); assertFalse(base.canCloseWithEscape());
        assertEquals(DialogBase.DialogAfterAction.NONE, base.afterAction());
        assertEquals(304, ((PlainMessageDialogBody)base.body().getFirst()).width());
        var type = assertInstanceOf(MultiActionType.class, PaperProfileScreen.createType(view, "random-token"));
        assertEquals(3, type.columns()); assertEquals(48, type.actions().getFirst().width());
        assertNotNull(type.exitAction()); assertTrue(base.inputs().isEmpty());
    }
    @Test void headImportFieldAcceptsFullBase64WithoutTruncatingAndOptionalScreensCanBeClosed() {
        var view = new NativeProfileScreen.View("Import", List.of("Paste texture"), List.of(), List.of(),
                new NativeProfileScreen.Button("SAVE", "Confirm"), 1, 200, 304, true, true, "texture", 8192);
        DialogBase base = assertDoesNotThrow(() -> PaperProfileScreen.createBase(view));
        TextDialogInput input = assertInstanceOf(TextDialogInput.class, base.inputs().getFirst());
        assertEquals(8192, input.maxLength()); assertEquals("value", input.key());
        assertEquals("texture", input.initial()); assertTrue(base.canCloseWithEscape()); assertFalse(input.labelVisible());
        assertInstanceOf(NoticeType.class, PaperProfileScreen.createType(view, "random-token"));
    }
    @Test void emptySearchResultsUseAValidNoticeInsteadOfEmptyMultiAction() {
        var view = new NativeProfileScreen.View("No results", List.of("0"), List.of(), List.of(),
                new NativeProfileScreen.Button("BACK", "Back"), 2, 150, 304, true, false, "", 16);
        assertDoesNotThrow(() -> PaperProfileScreen.createBase(view));
        assertInstanceOf(NoticeType.class, PaperProfileScreen.createType(view, "token"));
    }
}
