package ua.zentix.incoming.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The A3 contract's numbers and texts: what "close in" and "survived" mean, and every name a player sees. */
class ProgressTest {
    private static final Path ADVANCEMENTS = Path.of("src/main/resources/data/incoming/advancement/strikes");
    private static final Path LANG = Path.of("src/main/resources/assets/incoming/lang");
    /** 15 kt air burst at real scale, the largest yield the game allows. */
    private static final Detonation AIR = new Detonation(1, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0, 0, 20_000, 9, 1, false, 1);

    /** Still Standing asks for the pressure that destroys the grid for good: houses collapse there (Glasstone ch. V). */
    @Test
    void survivorThresholdIsTheGridDestructionPressure() throws Exception {
        JsonObject a = read(ADVANCEMENTS.resolve("nuclear_survivor.json"));
        double min = a.getAsJsonObject("criteria").getAsJsonObject("survived").getAsJsonObject("conditions")
                .getAsJsonObject("overpressure_psi").get("min").getAsDouble();
        assertEquals(Blackouts.DEAD_PSI, min);
        assertEquals(0.3, Progress.WATCH_PSI, 1e-9, "watched from where the wave hits bodies");
    }

    /**
     * Survival is judged after the wave has passed the body: later than front arrival plus the positive phase there, later
     * for a body farther out, and by {@link Progress#LANDING_TICKS} only.
     */
    @Test
    void survivalIsDueOnceTheWaveHasPassed() {
        double ring = AIR.groundRange(BlastModel.kpa(5));
        assertTrue(ring > 1000 && ring < 3000, "15 kt air burst 5 psi ring: " + ring + " m");
        long last = 0;
        for (double f : new double[]{0.2, 0.5, 0.9, 1.5}) {
            Vec3 at = new Vec3(ring * f, 64, 0);
            double slant = at.distanceTo(AIR.burst());
            long due = Progress.survivalDue(AIR, at);
            double passed = AIR.arrivalTicks(slant) + AIR.positivePhaseTicks(slant);
            assertTrue(due >= passed + Progress.LANDING_TICKS && due <= passed + Progress.LANDING_TICKS + 1, "due " + due + " passed " + passed);
            assertTrue(due > last, "survival due earlier farther out");
            last = due;
        }
    }

    /** Witnessed reaches exactly the damage reach, horizontally. */
    @Test
    void witnessedIsTheDamageReach() {
        double r = AIR.radiusMax();
        assertTrue(Progress.witnessed(AIR, new Vec3(r - 1, 64, 0)));
        assertTrue(Progress.witnessed(AIR, new Vec3(0, 3000, r - 1)), "height does not matter");
        assertTrue(!Progress.witnessed(AIR, new Vec3(r + 1, 64, 0)));
    }

    /** Every advancement's title and description and every statistic's name exist in both languages. */
    @Test
    void everyNameIsTranslated() throws Exception {
        JsonObject en = read(LANG.resolve("en_us.json")), ru = read(LANG.resolve("ru_ru.json"));
        TreeSet<String> keys = new TreeSet<>(), missing = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(ADVANCEMENTS)) {
            files = s.filter(f -> f.toString().endsWith(".json")).toList();
        }
        assertEquals(6, files.size(), "advancements");
        for (Path f : files) {
            JsonObject display = read(f).getAsJsonObject("display");
            keys.add(display.getAsJsonObject("title").get("translate").getAsString());
            keys.add(display.getAsJsonObject("description").get("translate").getAsString());
        }
        for (ResourceLocation id : ModStats.all()) keys.add("stat." + id.getNamespace() + "." + id.getPath());
        for (String k : keys) if (!en.has(k) || !ru.has(k)) missing.add(k);
        assertEquals(new TreeSet<>(), missing);
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    private static JsonObject read(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }
}
