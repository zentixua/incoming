package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Цель по пакету взрыва ({@link FarBlasts#target}): плита над пустотой — только по опоре, которую шлёт пробой сервера
 * ({@code BreachGameTests}: опора пакета совпадает с тем, что лучи сносят), а не по своему правилу клиента; опоры нет —
 * по веществу материала.
 */
class BlastTargetSupportTest {
    private static final double SHAHED = 50;

    private static S2C.Blast blast(GroundMaterial m, Optional<S2C.Blast.Support> support) {
        return new S2C.Blast(S2C.Blast.DRONE, Vec3.ZERO, m.ordinal(), 0, 1, Optional.empty(), 0, S2C.Blast.DOWN, true, support);
    }

    @Test
    void slabComesFromTheServerBreach() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget t = FarBlasts.target(blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 1, (float) r))),
                GroundMaterial.WOOD, SHAHED, 0);
        assertEquals(BlastTarget.Family.SLAB, t.family());
        assertEquals(1, t.slab(), 1e-6);
        assertEquals(Math.sqrt(r * r - 1), t.hole(), 1e-5);
    }

    @Test
    void unbreachedSupportFollowsWhatWasHit() {
        // доски на земле (опора, не пробита) — твёрдое; грунт — рыхлое, хоть материал пакета и не грунт
        assertEquals(BlastTarget.Family.HARD, FarBlasts.target(blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 0, 0))),
                GroundMaterial.WOOD, SHAHED, 0).family());
        assertEquals(BlastTarget.Family.SOIL, FarBlasts.target(blast(GroundMaterial.DIRT, Optional.of(new S2C.Blast.Support(true, 0, 0))),
                GroundMaterial.DIRT, SHAHED, 0).family());
    }

    /** Без опоры в пакете (повтор до этого поля, взрыв без сервера) — как раньше: по веществу материала, опора сплошная. */
    @Test
    void noSupportFallsBackToTheMaterial() {
        assertEquals(BlastTarget.of(GroundMaterial.STONE, SHAHED, 0), FarBlasts.target(blast(GroundMaterial.STONE, Optional.empty()),
                GroundMaterial.STONE, SHAHED, 0));
        assertEquals(BlastTarget.Family.SOIL, FarBlasts.target(blast(GroundMaterial.DIRT, Optional.empty()), GroundMaterial.DIRT, SHAHED, 0).family());
    }

    /** Разрыв выше, чем заряд роет воронку, — в воздухе, даже если сервер нашёл грань (ось упёрлась в далёкую плиту). */
    @Test
    void airburstWinsOverSupport() {
        assertEquals(BlastTarget.Family.AIR, FarBlasts.target(blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 1, 2))),
                GroundMaterial.WOOD, SHAHED, 30).family());
    }
}
