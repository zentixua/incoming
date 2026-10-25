package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Тыльный откол — из дыры за плитой по её оси (нормаль пробитой грани из опоры пакета), а не вниз мира и не по курсу
     * (ревью 1 фронта F): у стены, в которую ударили сбоку, — вбок за стену; у косого удара по крыше — вниз, где сервер
     * пробил; заряд — на грани, а не в точке прицела полблока в блоке. Лицевая крошка — наружу с этой грани.
     */
    @Test
    void spallLeavesThroughTheServerHole() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        Object[][] cases = {
                // стена в метр, удар сбоку в середину блока: грань — x = 0, заряд на ней, нормаль +x
                {new Vec3(0.5, 66.5, 0.5), new Vec3(1, -0.5, 0.2), new Vec3(1, 0, 0), 0.5f},
                // крыша, удар под 37° от вертикали носом о верх: нормаль вниз
                {new Vec3(0.5, 70, 0.5), new Vec3(0.6, -0.8, 0), new Vec3(0, -1, 0), 0f},
        };
        java.util.Random random = new java.util.Random(7);
        for (Object[] c : cases) {
            Vec3 pos = (Vec3) c[0], axis = (Vec3) c[1], normal = (Vec3) c[2];
            S2C.Blast p = new S2C.Blast(S2C.Blast.DRONE, pos, GroundMaterial.WOOD.ordinal(), 0, 1, Optional.empty(), 0, axis, true,
                    Optional.of(new S2C.Blast.Support(false, 1, (float) r, normal, (float) c[3])));
            BlastTarget t = FarBlasts.target(p, GroundMaterial.WOOD, SHAHED, 0);
            assertEquals(BlastTarget.Family.SLAB, t.family());
            Vec3 face = t.face();
            assertEquals(0, pos.subtract(normal.scale((float) c[3])).distanceTo(face), 1e-6, "заряд — на грани");
            double cone = Math.cos(Math.toRadians(BlastTarget.SPALL_CONE_DEG)) - 1e-9;
            t.spall(SHAHED, 200, random::nextDouble, (at, v) -> {
                Vec3 off = at.subtract(face);
                double along = off.dot(normal);
                assertTrue(along >= t.slab() - 1e-9, "кусок не за плитой: " + along + " при толщине " + t.slab());
                assertTrue(off.subtract(normal.scale(along)).length() <= t.hole() + 1e-9, "кусок вне дыры");
                assertTrue(v.normalize().dot(normal) >= cone, "кусок вне конуса откола: " + v);
            });
            new ua.zentix.incoming.client.fx.smoke.Ejecta(t.hole(), 0, 0).pieces(face, normal.scale(-1), 200, random::nextDouble,
                    (at, v) -> assertTrue(v.dot(normal) < 0 && at.subtract(face).dot(normal) < 0, "лицевая крошка — наружу с грани"));
        }
    }
}
