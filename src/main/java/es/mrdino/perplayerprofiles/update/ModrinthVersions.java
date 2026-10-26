package es.mrdino.perplayerprofiles.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class ModrinthVersions {
    record Release(String version, String url) { }
    private static final Pattern NUMBER = Pattern.compile("(?i)(?:^|[-_])v?(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-](.*))?$");

    static Optional<Release> newerCompatible(JsonArray versions, String current, String minecraft, Set<String> loaders) {
        VersionNumber installed = VersionNumber.parse(current);
        if (installed == null) return Optional.empty();
        Release newest = null;
        VersionNumber newestNumber = installed;
        for (JsonElement entry : versions) {
            if (!entry.isJsonObject()) continue;
            JsonObject version = entry.getAsJsonObject();
            try {
                if (version.has("status") && !version.get("status").getAsString().equals("listed")) continue;
                if (!contains(version.getAsJsonArray("game_versions"), Set.of(minecraft))
                        || !contains(version.getAsJsonArray("loaders"), loaders)) continue;
                String number = version.get("version_number").getAsString();
                VersionNumber parsed = VersionNumber.parse(number);
                if (parsed != null && parsed.compareTo(newestNumber) > 0) {
                    newest = new Release(number, "https://modrinth.com/plugin/ppps/version/" + version.get("id").getAsString());
                    newestNumber = parsed;
                }
            } catch (RuntimeException malformed) {
                // Skip an incomplete entry without discarding other compatible releases.
            }
        }
        return Optional.ofNullable(newest);
    }

    private static boolean contains(JsonArray values, Set<String> allowed) {
        if (values == null) return false;
        for (JsonElement value : values) if (allowed.contains(value.getAsString())) return true;
        return false;
    }

    static int compare(String left, String right) {
        VersionNumber first = VersionNumber.parse(left), second = VersionNumber.parse(right);
        if (first == null || second == null) throw new IllegalArgumentException("Unrecognised version number.");
        return first.compareTo(second);
    }

    private record VersionNumber(BigInteger[] parts, String qualifier) implements Comparable<VersionNumber> {
        static VersionNumber parse(String value) {
            var matcher = NUMBER.matcher(value.split("\\+", 2)[0]);
            if (!matcher.find()) return null;
            BigInteger[] parts = new BigInteger[4];
            for (int index = 0; index < 4; index++)
                parts[index] = new BigInteger(matcher.group(index + 1) == null ? "0" : matcher.group(index + 1));
            String qualifier = matcher.group(5) == null ? "" : matcher.group(5).toLowerCase(Locale.ROOT);
            qualifier = qualifier.replaceFirst("(?:^|[-_])(?:paper)?(?:26(?:\\.[123])?|1\\.(?:20|21))(?:\\.x|x)?$", "");
            return new VersionNumber(parts, qualifier);
        }

        @Override public int compareTo(VersionNumber other) {
            for (int index = 0; index < parts.length; index++) {
                int comparison = parts[index].compareTo(other.parts[index]);
                if (comparison != 0) return comparison;
            }
            if (qualifier.equals(other.qualifier)) return 0;
            if (qualifier.isEmpty()) return 1;
            if (other.qualifier.isEmpty()) return -1;
            String[] first = qualifier.split("[.-]"), second = other.qualifier.split("[.-]");
            for (int index = 0; index < Math.min(first.length, second.length); index++) {
                boolean leftNumeric = first[index].matches("[0-9]+"), rightNumeric = second[index].matches("[0-9]+");
                int comparison = leftNumeric && rightNumeric ? new BigInteger(first[index]).compareTo(new BigInteger(second[index]))
                        : leftNumeric != rightNumeric ? leftNumeric ? -1 : 1 : first[index].compareTo(second[index]);
                if (comparison != 0) return comparison;
            }
            return Integer.compare(first.length, second.length);
        }
    }
}
