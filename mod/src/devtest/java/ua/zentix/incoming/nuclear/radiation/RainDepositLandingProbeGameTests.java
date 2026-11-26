package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;

import java.util.Locale;

/**
 * Black-rain dust lands with its rain ({@code docs/design/time.md} §9 change 4: "fallout and rain deposit arrive on M"): the
 * deposit is not on the ground before the motion age reaches the rain's onset there, though a vanilla day's strike clock
 * passes that value about a minute after the show. Regression test from the review of PR #785. Detached event list: no
 * world work.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RainDepositLandingProbeGameTests {
    private static final BlockPos SPOT = new BlockPos(44, 12, 44);

    private RainDepositLandingProbeGameTests() {}

    /** 15 kt airburst 600 m up over burning ground, wind 6.7 m/s toward +x, 30 real minutes ago on a vanilla day. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "probe_rain_landing", skyAccess = true)
    public static void rainDustLandsWhenTheRainGetsThere(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(SPOT));
        NuclearEvents events = new NuclearEvents();
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(), at.add(0, 600, 0), at.y, 15, false, level.getGameTime() - 36_000,
                0f, 6.7f, 20_000, 7, 1f, true, 0.35f, Detonation.NO_DAY_TIME, true, true);
        events.add(d, level);
        events.endShows(level);
        double m = events.motion(d, level), s = events.seconds(d, level), g = events.ground(d, level);
        GroundFallout.Source src = GroundFallout.source(events, level, d, g, g);
        // over the fires: the rain began at M = 20 min, before now (M = 30 min): its dust is down
        h.assertTrue(d.rainH1(at.x, at.z) > 0 && GroundFallout.at(events, level, src, at.x, at.z).rain() != null,
                "no rain dust over the fires at M " + m);
        // 15 km downwind: the rain gets there at M ≈ 52 min
        double x = at.x + 15_000, onset = d.rainStartSeconds(x, at.z);
        h.assertTrue(d.rainH1(x, at.z) > 0 && onset > m && onset < s,
                String.format(Locale.ROOT, "precondition: onset %.0f s, M %.0f s, S %.0f s", onset, m, s));
        GroundFallout.Point p = GroundFallout.at(events, level, src, x, at.z);
        h.assertTrue(p.rain() == null, String.format(Locale.ROOT,
                "15 km: rain dust is on the ground at M %.0f s, %.0f real s before the rain reaches it (M %.0f s); dose rate %.3g R/h",
                m, onset - m, onset, p.rate(g)));
        h.succeed();
    }
}
