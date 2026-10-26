package es.mrdino.perplayerprofiles.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileCommandSuggestionsTest {
    private final UUID viewer = UUID.randomUUID();
    private final ProfileCommandSuggestions suggestions = new ProfileCommandSuggestions();

    private void publish() {
        suggestions.publish(Map.of("tp", List.of(0, 1), "teleport", List.of(0, 1), "give", List.of(0),
                "msg", List.of(0), "gamemode", List.of(1)),
                Map.of(viewer, List.of("Hero", "IceKnight")), List.of("Hero", "IceKnight", "HiddenHero"));
    }

    @Test void completesAliasesNamespacesAndBothTargetsWithTheCorrectPrefix() {
        publish();
        for (String command : List.of("/tp", "tp", "/minecraft:tp", "/teleport", "/minecraft:teleport")) {
            assertEquals(List.of("Hero", "IceKnight"), suggestions.complete(viewer, command + " "));
            assertEquals(List.of("Hero"), suggestions.complete(viewer, command + " RealPlayer h"));
            assertTrue(suggestions.complete(viewer, command + " RealPlayer Hero ").isEmpty());
        }
        assertTrue(suggestions.complete(viewer, "/tp").isEmpty());
        assertTrue(suggestions.complete(viewer, "/gamemode c").isEmpty());
        assertEquals(List.of("Hero"), suggestions.complete(viewer, "/gamemode creative H"));
        assertTrue(suggestions.complete(viewer, "/give @a minecraft:sto").isEmpty());
        assertTrue(suggestions.complete(viewer, "/msg RealPlayer message ").isEmpty());
    }

    @Test void doesNotSuggestProfilesForCoordinatesSelectorsOrPasswords() {
        publish();
        for (String first : List.of("~", "~10", "^", "^-2", "10", "-1.5", ".5"))
            assertTrue(suggestions.complete(viewer, "/tp " + first + " ").isEmpty());
        for (String buffer : List.of("/tp @", "/ppp setmasterpassword ", "/ppp use Hero ", "/unknown "))
            assertTrue(suggestions.complete(viewer, buffer).isEmpty());
    }

    @Test void usesImmutableSnapshotsAndHidesProfilesFromUnknownOrHiddenViewers() {
        var targets = new HashMap<>(Map.of("tp", List.of(0)));
        var names = new ArrayList<>(List.of("Hero"));
        Map<UUID, List<String>> viewers = new HashMap<>(Map.of(viewer, names));
        suggestions.publish(targets, viewers, List.of("Hero", "HiddenHero"));
        targets.clear(); names.clear(); viewers.clear();
        assertEquals(List.of("Hero"), suggestions.complete(viewer, "/tp "));
        assertTrue(suggestions.complete(UUID.randomUUID(), "/tp ").isEmpty());
        assertEquals(List.of("Hero", "HiddenHero"), suggestions.complete(null, "tp "));
        suggestions.publish(Map.of(), Map.of(viewer, List.of("Hero")), List.of("Hero"));
        assertFalse(suggestions.includesCommand("tp"));
        assertTrue(suggestions.complete(viewer, "/tp ").isEmpty());
    }

    @Test void prefixMatchingDoesNotDependOnServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); publish();
            assertEquals(List.of("IceKnight"), suggestions.complete(viewer, "/tp i"));
        } finally { Locale.setDefault(previous); }
    }
}
