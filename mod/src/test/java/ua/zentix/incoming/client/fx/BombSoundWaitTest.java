package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BunkerImpact;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.Warheads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Звук событий бомбы — как у взрывов: ближе {@link FarBlasts#NEAR} его ждёт ближняя модель ({@code BlastEffects.Timeline})
 * до прихода к уху, сколько бы ни жила картинка (вход в грунт — 30 тиков, обвал — 20: звук обрывался за 515 и 343 м),
 * дальше — раскат облака события по дальней модели.
 */
class BombSoundWaitTest {
    @Test
    void nearModelWaitsForTheSoundUpToItsEdge() {
        int wait = BlastEffects.hearTicks(FarBlasts.NEAR);
        assertTrue(wait * Warheads.FRONT_SPEED >= FarBlasts.NEAR, "звук ждут до края ближней модели");
        assertTrue((wait - 1) * Warheads.FRONT_SPEED < FarBlasts.NEAR, "и не дольше");
        assertTrue(wait > 30, "дольше картинки входа бомбы (30 тиков) и обвала (20)");
    }

    /** Вдали у входа бомбы и обвала есть свой раскат: доля энергии удара в воздухе, глухим ударом из-под земли. */
    @Test
    void farVoiceCarriesBeyondTheNearEdge() {
        double mass = WeaponType.BUNKER.spec().striker().article().dryMass();
        for (double speed : new double[]{150, 250, 350}) {
            double tnt = BunkerImpact.equivalentTnt(mass, speed);
            var voice = BlastEffects.thud(tnt);
            assertTrue(voice.underground(), "из-под земли — глухая запись");
            assertEquals(BunkerImpact.airTnt(tnt), voice.chargeKg(), 1e-12);
            assertTrue(BlastSounds.range(voice) > FarBlasts.NEAR, speed + " м/с: удар слышно дальше ближней модели");
        }
        for (double volume : new double[]{50, 500, 5000}) {
            assertTrue(BlastSounds.range(BlastEffects.thud(BuriedBlast.collapseTnt(volume))) > FarBlasts.NEAR, volume + " м³: обвал слышно дальше ближней модели");
        }
    }
}
