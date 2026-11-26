package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.BlastTime;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.util.WorldClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit probe (release 3.14.0): the server's record of conventional clouds ages by the motion age M (real age plus counted
 * jumps), and so does a client that saw the strike live (its cloud's age is {@code BlastTime}, which a counted jump moves,
 * {@code CountedJump}). A player who joins, returns from another dimension or respawns, and a Flashback seek, get the clouds
 * everyone else still sees: before a night everyone slept through both keep a Shahed-136's cloud two minutes old, after it
 * neither does.
 */
class StrikeCloudsJumpProbeTest {
    @Test
    void joinerAfterACountedSleepSeesWhatLiveClientsSee() {
        long born = 0, now = 2 * 60 * 20; // two real minutes after a Shahed-136 hit
        double night = 8 * WorldClock.HOUR; // single-player sleep, or every player in the Overworld asleep
        double life = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD).life;
        long liveBorn = BlastTime.ticks();
        for (long t = born; t < now; t++) BlastTime.advance();
        for (double counted : new double[]{0, night}) {
            if (counted > 0) BlastTime.jump(Math.round(night * WorldClock.SECONDS_PER_TICK * 20));
            StrikeClouds all = new StrikeClouds();
            all.addForTest(StrikeCloudsTest.cloud(born, 0, StrikeCloudsTest.shahed(1)));
            all.prune(now, counted);
            boolean joinerGets = all.sizeForTest() == 1;
            boolean liveShows = BlastTime.seconds(BlastTime.ticks(), liveBorn, 0) < life;
            assertEquals(liveShows, joinerGets, "counted " + counted + ": the live client and a late joiner disagree");
            if (counted == 0) assertTrue(liveShows, "the cloud two minutes old");
            else assertFalse(liveShows, "the cloud after an 8-hour night");
        }
    }
}
