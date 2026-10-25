package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.GroundDust;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponType;
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

    /**
     * Опора пакета — контакт, найденный сервером: семейство, масса и пыль цели не зависят от высоты над рельефом, которую
     * клиент знает вдали грубо (ревью 2 фронта F: шахед по метру досок, плитка {@code FarTerrain} без дома — земля на 5 м
     * ниже, и пролом становился разрывом в воздухе: 0 кг вместо 3,6 т и 72 кг пыли). Настоящий разрыв без опоры — в воздухе.
     */
    @Test
    void serverContactOutranksCoarseHeight() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        S2C.Blast roof = blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 1, (float) r, S2C.Blast.DOWN, 0)));
        BlastTarget exact = FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, 0);
        assertEquals(BlastTarget.Family.SLAB, exact.family());
        assertTrue(exact.mass(SHAHED) > 3000, "пролом метра досок у шахеда — тонны: " + exact.mass(SHAHED));
        BlastSource src = BlastSource.of(WeaponType.DRONE, false);
        double hot = new CloudModel(src, CloudModel.Air.STANDARD, exact, 0, GroundDust.Ground.BARE).dust;
        for (double hob : new double[]{5, 30, Double.NaN}) {
            BlastTarget far = FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, hob);
            assertEquals(exact, far, "высота " + hob + " м: та же цель");
            if (!Double.isNaN(hob)) assertEquals(hot, new CloudModel(src, CloudModel.Air.STANDARD, far, hob, GroundDust.Ground.BARE).dust,
                    1e-9, "высота " + hob + " м: пыль пролома та же");
        }
        assertEquals(BlastTarget.Family.AIR, FarBlasts.target(blast(GroundMaterial.WOOD, Optional.empty()), GroundMaterial.WOOD, SHAHED, 30).family(),
                "без опоры высоко — разрыв в воздухе");
    }

    /** Средняя высота клубов слоя в возрасте t (по массе пыли), м от точки взрыва; масса — во втором элементе. */
    private static double[] meanHeight(GroundDust d, double t) {
        double[] sum = new double[2];
        d.blobs(t, 1, (x, y, z, sh, sv, dust) -> {
            sum[0] += y * dust;
            sum[1] += dust;
        });
        return new double[]{sum[1] > 0 ? sum[0] / sum[1] : Double.NaN, sum[1]};
    }

    /**
     * Пыль пролома переносит газ от точки удара, а не от грубой высоты земли вдали; пыль волны держит рельеф как есть.
     */
    @Test
    void freshDustFollowsGasFromTheFace() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        S2C.Blast roof = blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 1, (float) r, S2C.Blast.DOWN, 0)));
        BlastSource src = BlastSource.of(WeaponType.DRONE, false);
        CloudModel exact = new CloudModel(src, CloudModel.Air.STANDARD, FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, 0), 0,
                GroundDust.Ground.BARE);
        for (double t : new double[]{0.3, 1}) {
            assertTrue(exact.thermal.airborneDust(t) > 0);
            for (double hob : new double[]{5, 30}) {
                CloudModel far = new CloudModel(src, CloudModel.Air.STANDARD, FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, hob), hob,
                        GroundDust.Ground.BARE);
                assertEquals(exact.height(t), far.height(t), 1e-9, "газ — от точки удара на крыше");
                assertEquals(exact.thermal.airborneDust(t), far.thermal.airborneDust(t), 1e-9);
            }
        }
        // пыль волны — с поверхности вокруг: при земле на 5 м ниже она там, а не у крыши
        double[] wave0 = meanHeight(exact.ground, 1);
        double[] wave5 = meanHeight(new CloudModel(src, CloudModel.Air.STANDARD, FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, 5), 5,
                GroundDust.Ground.BARE).ground, 1);
        assertTrue(wave0[1] > 0 && wave5[1] > 0, "пыль волны есть");
        assertTrue(wave5[0] < wave0[0] - 3, "пыль волны держит рельеф: " + wave0[0] + " и " + wave5[0] + " м");
    }

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
