package ua.zentix.incoming.client.sound;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real one-time sound of an explosion ({@link ClientSounds.MixedShot}) without a sound engine: the dying down of the voice that has given way, its
 *  stopping and transitioning voice → hum in the order in which they are played by Minecraft - in the tick of the sound engine
 * ({@code SoundManager.tick}: the voice is ticking and giving up the volume, the hum loop counts its own in its tick), and the budget decisions count
 *  in the client's tick after it ({@code ClientTickEvent.Post}).
 */
class MixedShotTest {
    private static ClientSounds.MixedShot shot(Mix.Blast blast, int life) {
        return new ClientSounds.MixedShot(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("incoming", "test")), SoundSource.AMBIENT, 1, 1,
                RandomSource.create(1), Vec3.ZERO, 1, 1, blast::factor, life);
    }

    /** Voice of the engine without an engine: the channel is held until the sound is stopped, and another tick - the engine will return it in the next one. */
    private static final class Engine implements BlastVoices.Sound {
        final ClientSounds.MixedShot shot;
        final int[] clock;
        int stoppedAt = Integer.MAX_VALUE;

        Engine(ClientSounds.MixedShot shot, int[] clock) {
            this.shot = shot;
            this.clock = clock;
        }

        /** tick sound engine: the sound is ticking, stopped - marked. */
        void tick() {
            shot.tick();
            if (shot.isStopped() && stoppedAt == Integer.MAX_VALUE) stoppedAt = clock[0];
        }

        @Override
        public void fade() {
            shot.fade();
        }

        @Override
        public void cut() {
            shot.cut();
        }

        @Override
        public boolean over() {
            return shot.isStopped();
        }

        @Override
        public boolean held() {
            return clock[0] <= stoppedAt;
        }
    }

    private static Mix.Blast flat(Mix mix, double at, double p) {
        Mix.Blast b = mix.blast(at);
        b.add(new Mix.Layer(MixTest.flat(p, 600), 1, 1));
        return b;
    }

    /** The yielding voice: tick at full volume, then to zero for {@link BlastVoices#FADE} tick - and the sound is removed. */
    @Test
    void yieldedShotHoldsATickThenFadesAndStops() {
        Mix mix = new Mix();
        Mix.Blast b = flat(mix, 0, 0.25);
        mix.at(0);
        ClientSounds.MixedShot s = shot(b, 400);
        double k = b.factor();
        assertEquals(k, s.level(), 1e-6);
        s.fade();
        double[] want = {1, 2 / 3.0, 1 / 3.0};
        for (double f : want) {
            s.tick();
            assertFalse(s.isStopped());
            assertEquals(k * f, s.level(), 1e-6, "volume of the yielding voice");
        }
        s.tick();
        assertTrue(s.isStopped(), "verse - removed");
        // the recording ended - removed and without assignment
        ClientSounds.MixedShot t = shot(b, 2);
        t.tick();
        assertFalse(t.isStopped());
        t.tick();
        assertTrue(t.isStopped());
    }

    @Test
    void cutStopsTheShot() {
        Mix mix = new Mix();
        ClientSounds.MixedShot s = shot(flat(mix, 0, 0.1), 400);
        assertFalse(s.isStopped());
        s.cut();
        assertTrue(s.isStopped());
    }

    /**
     * Transition in order Minecraft (review of R4: there were 4/9, 6/9, 8/9 power): loud new one comes to the client tick after
     * tick engine, the tail yields; in each next tick of the engine, the voice ticks and gives off volume, and the hum loop counts its own
     *  in the same place, according to the same clock - the power of the voice and hum together - the share of the tail in the mixture in each tick.
     */
    @Test
    void minecraftTickOrderKeepsTheHandoverPower() {
        int[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, 1);
        Mix.Blast tail = flat(mix, 0, 0.1);
        Engine old = new Engine(shot(tail, 400), clock);
        BlastVoices.Voice v = voices.admit(tail, List.of(old), 0, 0, 1, 1, 1, Integer.MAX_VALUE);
        assertNotNull(v);
        voices.started(v, 1, 0);
        // client tick 20, after the engine tick 20: loud came, tail gives way
        clock[0] = 20;
        mix.at(20);
        Mix.Blast big = flat(mix, 20, 1);
        Engine loud = new Engine(shot(big, 400), clock);
        assertNotNull(voices.admit(big, List.of(loud), 20, 0, 1, 1, 1, Integer.MAX_VALUE), "loud - with a voice");
        assertTrue(v.fading(), "the tail gave way");
        double want = tail.factor() * tail.factor() * tail.voiced(20);
        for (int e = 21; e <= 20 + BlastVoices.FADE + 3; e++) {
            clock[0] = e;
            // tick engine: the voice ticks and gives volume, the loop counts its own in its tick
            old.tick();
            rumble.tick(e);
            double level = old.shot.isStopped() ? 0 : old.shot.level(), voice = level * level * tail.voiced(20);
            assertEquals(want, voice + rumble.total(), 1e-9, "tick engine " + e + ": voice " + voice / want + ", hum " + rumble.total() / want);
            // client tick after it: the budget sees what the engine is holding
            voices.prune(e);
        }
        assertTrue(old.shot.isStopped());
        assertEquals(0, voices.voices().stream().filter(x -> x == v).count(), "the tail returned the channel - no voice");
    }
}
