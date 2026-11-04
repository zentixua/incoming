package ua.zentix.incoming.gametest;

import com.mojang.serialization.JsonOps;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.StemDust;

/** Native detonation/save clock reaches the same pure dust model used by the client; no world mutation. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StemClockGameTests {
    private StemClockGameTests() {}

    @GameTest(template = "pad", batch = "nuke_stem_clock")
    public static void restoredFrontDelaysOnlyTheSupplyingDust(GameTestHelper h) {
        for (boolean ground : new boolean[]{false, true}) {
            double hob = ground ? 0 : 2400;
            Detonation original = new Detonation(71, new Vec3(0, 64 + 2.5 * hob, 0), 64, 1000, ground,
                    0, 0, 0, 20_000, 7, 2.5f, false, 0.25f);
            var encoded = Detonation.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
            Detonation d = Detonation.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            StemDust.Source source = new StemDust.Source(d.yieldKt(), d.hobMetres(), d.surface(), d.visibility(), d.front(), d.scale());
            StemDust.Source real = new StemDust.Source(d.yieldKt(), d.hobMetres(), d.surface(), d.visibility(), d.withWave(1).front(), d.scale());
            double radius = DustModel.STEM_M * Math.cbrt(d.yieldKt() / 15) / 48;
            double expected = d.arrivalTicks(d.slantOf(d.blocks(radius))) / (20 * d.scale());
            h.assertTrue(Math.abs(expected - source.arrivalSeconds(radius)) < 1e-10, "restored slant/scale clock changed");
            StemDust dust = StemDust.of(source);
            h.assertTrue(Math.abs(dust.lambda(90) - StemDust.of(real).lambda(90)) > 0.01, "GAME and REAL reused one dust table");
            if (!ground) {
                double delay = DustModel.column(radius, hob, d.yieldKt(), false, d.visibility(), DustModel.Surface.REFERENCE).stemFrom();
                double before = expected + delay - 0.001;
                h.assertTrue(dust.lambda(before) == 0, "dust supplied before restored front plus inflow delay");
                h.assertTrue(dust.lambda(before + 0.01) > 0, "positive source never entered after onset");
            }
        }
        h.succeed();
    }
}
