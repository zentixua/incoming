package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.client.sound.BlastEchoes;
import ua.zentix.incoming.client.sound.TerrainEcho;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModSounds;

/**
 * Detonation echoes from terrain at this listener (DESIGN-nuke §5) — {@link BlastEchoes} with the detonation front: arrival follows
 * the game front, pressure is at ground zero; the grain is the start of a distant huge explosion recording ({@code nuke.boom_far},
 * variant from the detonation seed, as in {@link NukeSounds.Schedule#arrive}); time follows the client detonation clock. Live only.
 */
final class NukeEchoes extends BlastEchoes {
    NukeEchoes(ClientNuclear.Active a) {
        super(a::ticks, a.d.burst(), blast(a.d), ModSounds.NUKE_BOOM_FAR.get(), a.d.seed() ^ 0xEC40L);
    }

    /** Detonation front for echoes: game-front arrival, pressure at ground zero at the same slant range. */
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
