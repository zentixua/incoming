package ua.zentix.incoming;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Russian and English translations match: same keys and arguments in every line (code passes the same arguments
 * to both languages; an extra "%s" would show the template itself, a missing one would lose a number or key).
 * Keys are not hardcoded into strings: they can be rebound, so the tooltip receives the key as a
 * {@code Component.keybind} argument.
 */
class LangTest {
    /** Like {@code TranslatableContents.FORMAT_PATTERN}: vanilla translation string parsing. */
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    @Test
    void ruAndEnHaveSameKeysAndArguments() throws Exception {
        JsonObject ru = load("ru_ru"), en = load("en_us");
        assertEquals(new TreeSet<>(ru.keySet()), new TreeSet<>(en.keySet()), "keys ru_ru and en_us");
        Map<String, String> diff = new TreeMap<>();
        for (String key : ru.keySet()) {
            var a = args(ru.get(key).getAsString());
            var b = args(en.get(key).getAsString());
            if (!a.equals(b)) diff.put(key, a + " ≠ " + b);
        }
        assertEquals(Map.of(), diff, "string arguments ru_ru and en_us");
    }

    /** Vanilla only knows "%s", "%2$s" and "%%": on "%d" or "%" at the end of the line a key will appear instead of text. */
    @Test
    void onlyStringArguments() throws Exception {
        for (String lang : new String[]{"ru_ru", "en_us"}) {
            for (var e : load(lang).entrySet()) {
                Matcher m = ARG.matcher(e.getValue().getAsString());
                while (m.find()) {
                    assertTrue(m.group(2).equals("s") || m.group(2).equals("%"), lang + " " + e.getKey() + ": «" + m.group() + "»");
                }
            }
        }
    }

    /**
     * Each damage type of the mod has all three death messages that vanilla
     * ({@code DamageSource.getLocalizedDeathMessage}) can choose from: Basic, ".player" (without entities, but with whoever
     *  hit the victim last), and ".item" (the culprit is holding the renamed item). Without the key, the chat would only have the key itself.
     */
    @Test
    void everyDamageTypeHasAllDeathMessages() throws Exception {
        URL dir = LangTest.class.getResource("/data/incoming/damage_type");
        assertNotNull(dir, "data/incoming/damage_type");
        List<String> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                ids.add(JsonParser.parseString(Files.readString(f)).getAsJsonObject().get("message_id").getAsString());
            }
        }
        assertFalse(ids.isEmpty(), "no damage types");
        for (String lang : new String[]{"ru_ru", "en_us"}) {
            JsonObject strings = load(lang);
            for (String id : ids) {
                for (String suffix : new String[]{"", ".player", ".item"}) {
                    assertTrue(strings.has("death.attack." + id + suffix), lang + ": no death.attack." + id + suffix);
                }
                // ".player" - only for the killer, ".item" - for the culprit with the item: their arguments must be in the line
                assertTrue(args(strings.get("death.attack." + id + ".player").getAsString()).contains(2), lang + " " + id + ".player without killer");
                assertTrue(args(strings.get("death.attack." + id + ".item").getAsString()).contains(3), lang + " " + id + ".item without item");
            }
        }
    }

    @Test
    void cameraKeyIsNotHardcoded() throws Exception {
        for (String lang : new String[]{"ru_ru", "en_us"}) {
            for (var e : load(lang).entrySet()) {
                assertFalse(e.getValue().getAsString().matches(".*\\((?:V|N)[,)].*"), lang + " " + e.getKey());
            }
        }
    }

    /** Line argument numbers: “%s” are in order, “%2$s” is an explicit number. */
    private static TreeSet<Integer> args(String s) {
        TreeSet<Integer> out = new TreeSet<>();
        Matcher m = ARG.matcher(s);
        int next = 1;
        while (m.find()) if (!m.group(2).equals("%")) out.add(m.group(1) != null ? Integer.parseInt(m.group(1)) : next++);
        return out;
    }

    private static JsonObject load(String lang) throws Exception {
        String path = "/assets/incoming/lang/" + lang + ".json";
        try (InputStream in = LangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
