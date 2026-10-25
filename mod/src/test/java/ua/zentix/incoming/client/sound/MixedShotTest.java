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
 * Настоящий разовый звук взрыва ({@link ClientSounds.MixedShot}) без звукового движка: стихание уступившего голоса, его
 * остановка и переход голос → гул в том порядке, в каком их ведёт Minecraft — в тике звукового движка
 * ({@code SoundManager.tick}: голос тикает и отдаёт громкость, петля гула считает свою в своём тике), а решения бюджета —
 * в тике клиента после него ({@code ClientTickEvent.Post}).
 */
class MixedShotTest {
    private static ClientSounds.MixedShot shot(Mix.Blast blast, int life) {
        return new ClientSounds.MixedShot(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("incoming", "test")), SoundSource.AMBIENT, 1, 1,
                RandomSource.create(1), Vec3.ZERO, 1, 1, blast::factor, life);
    }

    /** Голос движка без движка: канал держит, пока звук не остановлен, и ещё тик — движок вернёт его в следующем. */
    private static final class Engine implements BlastVoices.Sound {
        final ClientSounds.MixedShot shot;
        final int[] clock;
        int stoppedAt = Integer.MAX_VALUE;

        Engine(ClientSounds.MixedShot shot, int[] clock) {
            this.shot = shot;
            this.clock = clock;
        }

        /** Тик звукового движка: звук тикает, остановленный — помечается. */
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

    /** Уступивший голос: тик полной громкости, потом к нулю за {@link BlastVoices#FADE} тика — и звук снят. */
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
            assertEquals(k * f, s.level(), 1e-6, "громкость уступившего голоса");
        }
        s.tick();
        assertTrue(s.isStopped(), "стих — снят");
        // запись кончилась — снят и без уступки
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
     * Переход в порядке Minecraft (ревью R4: было 4/9, 6/9, 8/9 мощности): громкий новый приходит в тике клиента после
     * тика движка, хвост уступает; в каждом следующем тике движка голос тикает и отдаёт громкость, а петля гула считает
     * свою там же, по тем же часам — мощность голоса и гула вместе — доля хвоста в смеси в каждом тике.
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
        // тик клиента 20, после тика движка 20: пришёл громкий, хвост уступает
        clock[0] = 20;
        mix.at(20);
        Mix.Blast big = flat(mix, 20, 1);
        Engine loud = new Engine(shot(big, 400), clock);
        assertNotNull(voices.admit(big, List.of(loud), 20, 0, 1, 1, 1, Integer.MAX_VALUE), "громкий — с голосом");
        assertTrue(v.fading(), "хвост уступил");
        double want = tail.factor() * tail.factor() * tail.voiced(20);
        for (int e = 21; e <= 20 + BlastVoices.FADE + 3; e++) {
            clock[0] = e;
            // тик движка: голос тикает и отдаёт громкость, петля считает свою в своём тике
            old.tick();
            rumble.tick(e);
            double level = old.shot.isStopped() ? 0 : old.shot.level(), voice = level * level * tail.voiced(20);
            assertEquals(want, voice + rumble.total(), 1e-9, "тик движка " + e + ": голос " + voice / want + ", гул " + rumble.total() / want);
            // тик клиента после него: бюджет видит, что держит движок
            voices.prune(e);
        }
        assertTrue(old.shot.isStopped());
        assertEquals(0, voices.voices().stream().filter(x -> x == v).count(), "хвост вернул канал — голоса нет");
    }
}
