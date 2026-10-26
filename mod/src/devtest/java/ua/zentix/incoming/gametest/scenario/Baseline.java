package ua.zentix.incoming.gametest.scenario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Trajectory reference ({@code scenario-baseline.json} in the devtest resources): for each reference scenario - how the
 *  flight ended, on what tick, where (from the middle of the site) and a hash of the projectile positions by tick. The script compares its flight
 *  with the reference exactly: any change in flight is visible. Change the reference only in PR, where the shift is explained for each
 *  scenario. Write again - {@code ./gradlew runScenarioRecord} (writes here, in the source code).
 */
final class Baseline {
    private static final String RESOURCE = "/scenario-baseline.json";
    /** Where to write the reference (task {@code runScenarioRecord}); without it - compare. */
    private static final String RECORD = System.getProperty("incoming.scenario.record");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Result flight.
     *
     * @param end how it ended: {@code blast} - scenario shell explosion, {@code entry} - bomb entered the ground,
     *             {@code gone} - the projectile was removed without explosion
     * @param tick tick from launch
     * @param x place from the middle of the site, accurate to 0,01 block
     * @param hash hash of positions and phases by tick ({@link FlightTrace})
     */
    record Entry(String end, int tick, double x, double y, double z, String hash) {}

    private static final Map<String, Entry> EXPECTED = load();
    private static final Map<String, Entry> RECORDED = new TreeMap<>();

    private Baseline() {}

    static boolean recording() {
        return RECORD != null;
    }

    @Nullable
    static Entry expected(String id) {
        return EXPECTED.get(id);
    }

    /** Write down the outcome of the scenario (the entire file again: which test will end last, you don’t know in advance). */
    static synchronized void record(String id, Entry entry) {
        RECORDED.put(id, entry);
        JsonObject root = new JsonObject();
        root.add("scenarios", GSON.toJsonTree(RECORDED));
        try {
            Files.writeString(Path.of(RECORD), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Entry> load() {
        Map<String, Entry> out = new TreeMap<>();
        try (InputStream in = Baseline.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Incoming.LOG.warn("There is no reference trajectories {}: the reference scenarios will fall - write ./gradlew runScenarioRecord", RESOURCE);
                return out;
            }
            JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            root.getAsJsonObject("scenarios").entrySet().forEach(e -> out.put(e.getKey(), GSON.fromJson(e.getValue(), Entry.class)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
