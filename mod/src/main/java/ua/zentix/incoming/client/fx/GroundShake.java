package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.nuclear.model.GroundShock;

import java.util.Locale;

/**
 * Ground wave at the player: bomb ground impact and buried blast travel through the ground — body impulse and ground rumble on
 * arrival R/c ({@link GroundShock}: wave speed and peak ground velocity from the source medium), rather than with the packet. Where
 * ground is faster than the air front (rock, wet clay), the ground shakes and rumbles before the crack; in loose soil — after.
 * <p>
 * Camera impulse — {@link CameraShake#ground}: strength — human perceptibility ({@link GroundShock.Sample#perceptibility}:
 * perception threshold — 0) and logarithmic ground velocity ({@link #strength}); duration — ground impulse ({@link GroundShock.Sample#durationSeconds}) and
 * subsequent body damping ({@link #BODY_RING_S}). Rumble —
 * {@code BOMB_QUAKE}, volume from pressure radiated by ground moving at the ground velocity
 * ({@link BlastSounds#quake(double)}).
 */
public final class GroundShake {
    /**
     * Body sway duration after the impulse, s: a standing human vertically is a system with 4–6 Hz resonance and damping
     * of 0.3–0.5 critical (ISO 2631-1; Griffin, “Handbook of Human Vibration”, 1990); an impulse shorter than its period takes
     * 2–3 periods to settle — ⚠ estimate. Otherwise a 70 ms rock impulse would last less than a quarter camera nod.
     */
    static final double BODY_RING_S = 0.4;
    /**
     * Ground velocity where impulse starts growing, and its multiplier for full strength, m/s: 0.1 mm/s — “not
     * perceptible”, ×1000, 100 mm/s — “very strong, frightening” (peak-velocity perception scale from DIN 4150-2: 0.1 — not
     * perceptible, 0.3 — barely, 1 — perceptible, 3 — clear, 10 — strong). Impulse strength is a logarithmic fraction, like impact tremor by
     * pressure ({@link CameraShake#tremor}): model perceptibility (frequency-weighted KB) saturates near the threshold and does not
     * distinguish strengths. Presentation.
     */
    static final double QUIET_MPS = 1e-4, FULL_OVER_THRESHOLD = 1000;
    /** Last ground wave at the player (client scenario line). */
    private static String last = "-";

    private GroundShake() {}

    public static String describe() {
        return last;
    }

    /**
     * Ground wave source {@code source} at {@code pos} occurred at (server) game tick {@code sourceTick}: impulse and rumble —
     * when the wave reaches the player's feet; {@code seed} — impulse pattern.
     */
    public static void schedule(Vec3 pos, GroundShock.Source source, long sourceTick, long seed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;
        double range = player.position().distanceTo(pos);
        GroundShock.Sample s = GroundShock.sample(source, range);
        if (!(s.perceptibility() > 0) && !(s.peakVelocityMps() > 0)) return;
        double seconds = s.durationSeconds() + BODY_RING_S;
        // ticks already elapsed since the source on the server during packet travel (and server detonation work)
        double age = Math.max(0, level.getGameTime() - sourceTick);
        last = String.format(Locale.ROOT, "ground r %.0f m arrival %.2f s ppv %.2f mm/s %.2f Hz felt %.2f jolt %.2f for %.2f s, %.0f ticks after the source",
                range, s.arrivalSeconds(), 1000 * s.peakVelocityMps(), s.frequencyHz(), s.perceptibility(), strength(s), seconds, age);
        // rare event (bomb ground impact, buried blast): line for post-game log analysis
        Incoming.LOG.info("Ground wave at the player: {}", last);
        Effects.add(new Arrival(20 * s.arrivalSeconds(), 20 * seconds, age, BlastTime.ticks(), late -> arrive(s, seconds, late, seed)));
    }

    /**
     * Waiting for the ground wave at the feet. Age — ticks since the source on the monotonic client clock ({@link BlastTime}), rather than
     * the {@link Effects} call counter: it calls the effect twice on the birth tick (immediately and during queue ticking), so the impulse would arrive
     * a tick before the wave. Initial age is time already elapsed on the server ({@code ageAtBirth}). Wave arrived —
     * {@code arrive} with ticks since arrival (fractional: R/c arrival is fractional); packet delayed beyond the whole impulse
     * (late join, stalled network) — nothing: a past impulse is not replayed from the start.
     *
     * @param arrivalTicks wave arrival, ticks since source
     * @param lengthTicks  ground and body motion duration after arrival, ticks
     * @param born         {@link BlastTime#ticks()} when the packet arrived
     */
    record Arrival(double arrivalTicks, double lengthTicks, double ageAtBirth, long born, java.util.function.DoubleConsumer arrive)
            implements Effects.Effect {
        @Override
        public boolean tick(ClientLevel level, int t) {
            double age = ageAtBirth + (BlastTime.ticks() - born);
            if (age < arrivalTicks) return true;
            double late = age - arrivalTicks;
            if (late < lengthTicks) arrive.accept(late);
            return false;
        }
    }

    /**
     * Impulse strength 0..1 for ground wave {@code s}: model perceptibility × logarithmic peak ground velocity fraction from
     * {@link #QUIET_MPS} to {@link #FULL_OVER_THRESHOLD} times greater.
     */
    static double strength(GroundShock.Sample s) {
        double over = Math.log10(Math.max(s.peakVelocityMps(), 1e-12) / QUIET_MPS) / Math.log10(FULL_OVER_THRESHOLD);
        return s.perceptibility() * Math.clamp(over, 0, 1);
    }

    /** Ground wave reached the feet {@code late} ticks ago: impulse resumes at its expected point; rumble plays now. */
    private static void arrive(GroundShock.Sample s, double seconds, double late, long seed) {
        CameraShake.ground(strength(s), seconds, late, seed);
        BlastSounds.quake(s.peakVelocityMps());
    }
}
