package es.mrdino.perplayerprofiles.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperProfileCompletionsTest {
    @Test void mergesNativeSelectorsAndProfileNamesWithoutLosingRangesTooltipsOrDuplicates() {
        String input = "/tp Re";
        var tooltip = new com.mojang.brigadier.LiteralMessage("Native target");
        var original = Suggestions.create(input, List.of(new Suggestion(StringRange.between(4, 6), "RealPlayer", tooltip)));
        var merged = PaperProfileCompletions.merge(input, original, List.of("RedHero", "RedHero"));
        assertEquals(2, merged.getList().size());
        assertEquals(List.of("/tp RealPlayer", "/tp RedHero"), merged.getList().stream().map(s -> s.apply(input)).toList());
        assertSame(tooltip, merged.getList().stream().filter(s -> s.getText().equals("RealPlayer")).findFirst().orElseThrow().getTooltip());
        assertSame(original, PaperProfileCompletions.merge(input, original, List.of()));
        var afterSpace = PaperProfileCompletions.merge("tp RealPlayer ", Suggestions.empty().join(), List.of("Hero"));
        assertEquals("tp RealPlayer Hero", afterSpace.getList().get(0).apply("tp RealPlayer "));
    }

    @Test void requestsSuggestionsOnlyOnTheClientTreeAndPreservesTheNativeDispatcher() throws Exception {
        var profiles = new ProfileCommandSuggestions();
        profiles.publish(Map.of("tp", List.of(0)), Map.of(), List.of());
        var bridge = new PaperProfileCompletions(profiles);
        var nativeDispatcher = dispatcher();
        var clientDispatcher = dispatcher();
        var nativeArgument = (ArgumentCommandNode<?, ?>) nativeDispatcher.getRoot().getChild("tp").getChild("target");
        var clientArgument = (ArgumentCommandNode<?, ?>) clientDispatcher.getRoot().getChild("tp").getChild("target");
        bridge.prepareTree(UUID.randomUUID(), clientDispatcher.getRoot());
        assertNotNull(clientArgument.getCustomSuggestions());
        assertNull(nativeArgument.getCustomSuggestions());
        assertEquals(42, nativeDispatcher.execute("tp Hero", new Object()));
        assertSame(nativeDispatcher.getRoot().getChild("tp"), nativeDispatcher.getRoot().getChild("teleport").getRedirect());
        // Repeated async/sync command tree events remain safe, including redirects.
        bridge.prepareTree(UUID.randomUUID(), clientDispatcher.getRoot());
        assertEquals(42, nativeDispatcher.execute("teleport Hero", new Object()));
    }

    private CommandDispatcher<Object> dispatcher() {
        var dispatcher = new CommandDispatcher<Object>();
        var root = dispatcher.register(LiteralArgumentBuilder.<Object>literal("tp")
                .then(RequiredArgumentBuilder.<Object, String>argument("target", new EntityArgument()).executes(c -> 42)));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("teleport").redirect(root));
        return dispatcher;
    }

    private static final class EntityArgument implements ArgumentType<String> {
        @Override public String parse(com.mojang.brigadier.StringReader reader) { return reader.readUnquotedString(); }
    }
}
