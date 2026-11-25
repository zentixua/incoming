package ua.zentix.incoming.compat.create;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Create names a Display Link source in its screen by
 * {@code DisplaySource.getName()} = {@code <namespace>.display_source.<registry path>}, a key assembled from the
 * registry name, so {@code LangTest.everyKeyInCodeIsTranslated} never sees it. Every source {@link DisplaySources}
 * registers needs that key in both languages, or the Display Link screen lists the raw key.
 */
class DisplaySourceLangTest {
    @Test
    void everySourceHasItsScreenName() throws Exception {
        String code = Files.readString(Path.of("src/main/java/ua/zentix/incoming/compat/create/DisplaySources.java"));
        Matcher m = Pattern.compile("REGISTER\\.register\\(\"([a-z0-9_]+)\"").matcher(code);
        List<String> names = new ArrayList<>();
        while (m.find()) names.add(m.group(1));
        assertFalse(names.isEmpty(), "no sources found in DisplaySources");
        TreeSet<String> missing = new TreeSet<>();
        for (String lang : new String[]{"en_us", "ru_ru"}) {
            JsonObject strings = load(lang);
            for (String name : names) {
                String key = "incoming.display_source." + name;
                if (!strings.has(key)) missing.add(lang + ": " + key);
            }
        }
        assertEquals(new TreeSet<>(), missing, "Display Link source names absent from a lang file");
    }

    private static JsonObject load(String lang) throws Exception {
        String path = "/assets/incoming/lang/" + lang + ".json";
        try (InputStream in = DisplaySourceLangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
