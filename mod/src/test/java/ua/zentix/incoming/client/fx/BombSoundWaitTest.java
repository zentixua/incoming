package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BunkerImpact;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.Warheads;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Звук событий бомбы ждёт фронт столько, сколько его слышно, а не сколько живёт их картинка ({@code BlastEffects.Timeline}):
 * вход в грунт видно 30 тиков, обвал — 20, а слышно их дальше 515 и 343 м.
 */
class BombSoundWaitTest {
    /** Удар бомбы о грунт: от медленного входа до скорости сброса с высоты. */
    @Test
    void bunkerImpactIsWaitedForAsFarAsItIsHeard() {
        double mass = WeaponType.BUNKER.spec().striker().article().dryMass();
        for (double speed : new double[]{150, 250, 350}) {
            double range = BlastSounds.bunkerImpactRange(BunkerImpact.equivalentTnt(mass, speed));
            assertTrue(range > 30 * Warheads.FRONT_SPEED, speed + " м/с: удар слышно дальше картинки входа (" + range + " м)");
            assertTrue(BlastEffects.hearTicks(range) * Warheads.FRONT_SPEED >= range, speed + " м/с: фронт ждут на всю слышимость");
        }
    }

    /** Обвал свода над полостью: от малой полости до полости бомбы в скальном грунте. */
    @Test
    void collapseIsWaitedForAsFarAsItIsHeard() {
        for (double volume : new double[]{50, 500, 5000}) {
            double range = BlastSounds.collapseRange(BuriedBlast.collapseTnt(volume));
            assertTrue(BlastEffects.hearTicks(range) * Warheads.FRONT_SPEED >= range, volume + " м³: фронт ждут на всю слышимость (" + range + " м)");
        }
        assertTrue(BlastSounds.collapseRange(BuriedBlast.collapseTnt(500)) > 20 * Warheads.FRONT_SPEED, "обвал слышно дальше 20 тиков его картинки");
    }

    /** Рёв выброса газов — только ближняя модель: дальше {@link FarBlasts#NEAR} у выброса голос облака, и ждать его не дольше. */
    @Test
    void ventWaitsForTheNearModelOnly() {
        assertTrue(BlastEffects.hearTicks(FarBlasts.NEAR) * Warheads.FRONT_SPEED >= FarBlasts.NEAR);
        assertTrue((BlastEffects.hearTicks(FarBlasts.NEAR) - 1) * Warheads.FRONT_SPEED < FarBlasts.NEAR);
    }
}
