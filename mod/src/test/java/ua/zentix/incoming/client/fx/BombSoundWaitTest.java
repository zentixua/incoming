package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BunkerImpact;
import ua.zentix.incoming.warhead.BuriedBlast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sound of bomb events is like explosions: closer to {@link FarBlasts#NEAR} the nearest model ({@code BlastEffects.Timeline})
 *  is waiting for it before it comes to the ear, no matter how long the picture lasts (entrance into the ground - 30 tick, collapse - 20: the sound stopped behind 515 and 343 m),
 *  further - the roll of the event cloud along the distant model.
 */
class BombSoundWaitTest {
    /**
     * A listener at the edge of a nearby model that is moving away from the event (running, on elytra, on a craft) still hears it: wait
     *  the sound to the edge in a straight line (38 tick) is not enough - going away to 640 m at a speed of 8 m/s did not hear the impact at all (review #465).
     * The same calculation as in {@code Timeline.tick}: the front is at the ear, where it is now, no later than {@link BlastEffects.Timeline#SOUND_WAIT}.
     */
    @Test
    void recedingListenerAtTheNearEdgeStillHearsIt() {
        for (double speed : new double[]{0, 8, 30, 100}) {
            int heard = -1;
            for (int t = 0; t <= BlastEffects.Timeline.SOUND_WAIT && heard < 0; t++) {
                double d = FarBlasts.NEAR + speed * t / 20;
                if (FarBlasts.arrivalTicks(null, d) <= t) heard = t;
            }
            assertTrue(heard >= 0, speed + " m/s: the sound reached the departing listener");
        }
        assertTrue(BlastEffects.Timeline.SOUND_WAIT > 30, "longer pictures of bomb entry (30 tick) and collapse (20)");
    }

    /** In the distance, at the entrance of the bomb and the collapse, there is a boom of its own: a share of the energy of the impact in the air, a dull blow from underground. */
    @Test
    void farVoiceCarriesBeyondTheNearEdge() {
        double mass = WeaponType.BUNKER.spec().striker().article().dryMass();
        for (double speed : new double[]{150, 250, 350}) {
            double tnt = BunkerImpact.equivalentTnt(mass, speed);
            var voice = BlastEffects.thud(tnt);
            assertTrue(voice.underground(), "from underground - dull recording");
            assertEquals(BunkerImpact.airTnt(tnt), voice.chargeKg(), 1e-12);
            assertTrue(BlastSounds.range(voice) > FarBlasts.NEAR, speed + " m/s: the impact is heard further than the nearest model");
        }
        for (double volume : new double[]{50, 500, 5000}) {
            assertTrue(BlastSounds.range(BlastEffects.thud(BuriedBlast.collapseTnt(volume))) > FarBlasts.NEAR, volume + " m³: the collapse can be heard further than the nearest model");
        }
    }
}
