package es.mrdino.perplayerprofiles.update;

import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModrinthVersionsTest {
    @Test void versionComparisonIsNumericAndIgnoresExportRangeSuffixes() {
        assertTrue(ModrinthVersions.compare("1.10.0", "1.9.9") > 0);
        assertTrue(ModrinthVersions.compare("1.0.0", "1.0.0-beta.2") > 0);
        assertEquals(0, ModrinthVersions.compare("1.0.0-26.3.X", "1.0.0"));
    }
    @Test void checkerSelectsOnlyNewerCompatibleListedVersions() {
        var versions = JsonParser.parseString("""
            [
              {"id":"wrong-game","version_number":"9.0.0","game_versions":["26.3"],"loaders":["paper"]},
              {"id":"wrong-loader","version_number":"8.0.0","game_versions":["1.20.1"],"loaders":["fabric"]},
              {"id":"hidden","status":"unlisted","version_number":"7.0.0","game_versions":["1.20.1"],"loaders":["paper"]},
              {"id":"valid","version_number":"1.1.0","game_versions":["1.20.1"],"loaders":["paper"]},
              {"id":"old","version_number":"1.0.0","game_versions":["1.20.1"],"loaders":["paper"]},
              {"broken":true}
            ]
            """).getAsJsonArray();
        var newest = ModrinthVersions.newerCompatible(versions, "1.0.0", "1.20.1", Set.of("paper")).orElseThrow();
        assertEquals("1.1.0", newest.version());
        assertEquals("https://modrinth.com/plugin/ppps/version/valid", newest.url());
    }
}
