package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.client.sound.BlastEchoes;
import ua.zentix.incoming.client.sound.TerrainEcho;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModSounds;

/**
 * Эхо подрыва от рельефа у этого слушателя (DESIGN-nuke §5) — {@link BlastEchoes} с фронтом подрыва: приход — как у
 * фронта игры, давление — на земле эпицентра; зерно — начало записи далёкого огромного взрыва ({@code nuke.boom_far},
 * вариант по зерну подрыва, как у {@link NukeSounds.Schedule#arrive}); часы — часы подрыва на клиенте. Только вживую.
 */
final class NukeEchoes extends BlastEchoes {
    NukeEchoes(ClientNuclear.Active a) {
        super(a::ticks, a.d.burst(), blast(a.d), ModSounds.NUKE_BOOM_FAR.get(), a.d.seed() ^ 0xEC40L);
    }

    /** Фронт подрыва для эха: приход — как у фронта игры, давление — на земле эпицентра на той же наклонной дальности. */
    static TerrainEcho.Blast blast(Detonation d) {
        return new TerrainEcho.Blast() {
            @Override
            public double arrivalTicks(double blocks) {
                return d.arrivalTicks(blocks);
            }

            @Override
            public double pressure(double blocks) {
                return d.groundOverpressureKpa(d.groundOf(blocks));
            }
        };
    }
}
