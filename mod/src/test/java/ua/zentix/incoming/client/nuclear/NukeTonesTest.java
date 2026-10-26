package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recordings of the nuke are played at their speed: tone within ±25% ({@code SHIFT_MAX} sound assemblies, sections {@code NATIVE}).
 * The magnitude of the nuke is given by the laws - a jump with a positive phase of the model, echo, wind, destruction, the roar of a storm - and not
 *  slow recording: first hit close There was a recording of a near explosion on the tone 0,55 and a distant boom on 0,75.
 */
class NukeTonesTest {
    /** From {@code tools/build_sounds.py}: largest record shift. */
    private static double shiftMax() throws Exception {
        Matcher m = Pattern.compile("(?m)^SHIFT_MAX = ([0-9.]+)").matcher(Files.readString(Path.of("../tools/build_sounds.py")));
        assertTrue(m.find(), "SHIFT_MAX to build_sounds.py");
        return Double.parseDouble(m.group(1));
    }

    @Test
    void nukeRecordingsAtNativeSpeed() throws Exception {
        double max = shiftMax();
        float[][] all = {NukeSounds.Schedule.CRACK_TONES, {NukeSounds.Schedule.BOOM_TONE}, NukeAfter.ROAR_TONES, {NukeAfter.WIND_TONE}};
        for (float[] tones : all) {
            for (float t : tones) assertTrue(t > 0 && Math.max(t, 1 / t) <= max + 1e-6, "tone " + t + " - more ±" + Math.round((max - 1) * 100) + " %");
        }
    }

    /** In the nuke sections, the build does not accept reasons for a larger shift (no "Sax by eye"). */
    @Test
    void buildRefusesReasonsInNukeSections() throws Exception {
        String build = Files.readString(Path.of("../tools/build_sounds.py"));
        Matcher m = Pattern.compile("(?m)^NATIVE = \\{([^}]*)}").matcher(build);
        assertTrue(m.find(), "NATIVE to build_sounds.py");
        for (String part : new String[]{"\"nuke\"", "\"after\"", "\"sirens\""}) assertTrue(m.group(1).contains(part), "NATIVE: " + part);
        assertTrue(build.contains("(not why or SECTION in NATIVE)"), "speed() - no reason in NATIVE");
    }
}
