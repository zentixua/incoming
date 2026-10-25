package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мгла у глаза после прилёта: оптическая толща луча глаза (1,6 м над улицей) по ветру от удара — всего облака, пыли волны и
 * юбки пыли разрушенного. Эталон — записи камер после ударов шахедов по улицам Харькова: на 5–10 с улица в серо-белой
 * дымке, машины и дома в 30–60 м видны (τ луча в 40 м — около 1–2: дымка видна, сквозь неё видно), к минуте — чисто.
 * ⚠ Сам эталон — оценка τ по кадрам камеры, не замер.
 */
class HazeAtTheEyeTest {
    private static final CloudModel.Air CALM = new CloudModel.Air(CloudModel.N_STANDARD, 3, 0, 0.1, (int) Stability.C, 0.5);

    /** Шахед в улицу: на 6 и 10 с в 40 м — дымка (видна, но не стена), к минуте — чисто. */
    @Test
    void streetHitLeavesASeeThroughHaze() {
        CloudModel m = scene(WeaponType.DRONE, false, CALM);
        for (double t : new double[]{6, 10}) {
            double tau = tau(m, 0, t, 40);
            assertTrue(tau > 0.5 && tau < 3, t + " с: τ в 40 м " + tau);
        }
        assertTrue(tau(m, 0, 60, 40) < 0.2, "минута: τ в 40 м " + tau(m, 0, 60, 40));
    }

    /**
     * Шахед в бетонную крышу в метр над комнатой: со стороны удара выходит только пыль воронки
     * ({@link BlastTarget#SLAB_FRONT}) — на 10 с с улицы в 40 м гуще, чем у удара в улицу, но не стена (без этого — τ ≈ 10).
     */
    @Test
    void roofBreachIsThickerButNotAWall() {
        CloudModel roof = scene(WeaponType.DRONE, true, CALM), street = scene(WeaponType.DRONE, false, CALM);
        double r = tau(roof, 8, 10, 40);
        assertTrue(r > tau(street, 0, 10, 40) && r < 6, "τ в 40 м на 10 с " + r);
    }

    private static double tau(CloudModel m, double h, double t, double d) {
        double[] c = parts(m, h, t, d);
        return c[0] + c[1] + c[2];
    }

    static CloudModel scene(WeaponType weapon, boolean roof, CloudModel.Air air) {
        BlastSource source = BlastSource.of(weapon, false);
        double w = source.chargeKg(), h = roof ? 8 : 0;
        BlastTarget target = BlastTarget.of(GroundMaterial.STONE, false, roof ? 1 : 0,
                BreachModel.Material.ROCK.radius(w, BreachModel.UNTAMPED), Vec3.ZERO, new Vec3(0, -1, 0));
        GroundDust.Relief relief = (x, z) -> roof && Math.abs(x) <= 4 && Math.abs(z) <= 4 ? 0 : -h;
        double[][] heights = GroundDust.Ground.filled(0);
        for (int s = 0; s < GroundDust.SECTORS; s++) for (int b = 0; b < GroundDust.BANDS.length; b++) {
            double angle = 2 * Math.PI * (s + 0.5) / GroundDust.SECTORS, r = GroundDust.BANDS[b];
            heights[s][b] = relief.at(r * Math.cos(angle), r * Math.sin(angle));
        }
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1), heights, relief);
        return new CloudModel(source, air, target, 0, ground);
    }

    static double unitRay(double x, double y, double z, double sh, double sv, double distance) {
        double cdf = 0.5 * (SmokeGrid.erf((distance - x) / (Math.sqrt(2) * sh)) - SmokeGrid.erf(-x / (Math.sqrt(2) * sh)));
        return Math.max(0, cdf) * Math.exp(-0.5 * (Math.pow((1.6 - y) / sv, 2) + z * z / (sh * sh))) / (2 * Math.PI * sh * sv);
    }

    static double[] parts(CloudModel model, double h, double time, double distance) {
        double[] sum = new double[3];
        model.blobs(time, new CloudModel.Blobs() {
            public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double k, double rise) {
                sum[0] += unitRay(x, y + h, z, sh, sv, distance) * (CloudModel.SOOT_K * soot + DustModel.K_MIX * dust);
            }
            public void ground(double x, double y, double z, double sh, double sv, double dust, double k) {
                sum[1] += unitRay(x, y + h, z, sh, sv, distance) * DustModel.K_MIX * dust;
            }
            public void crushed(double x, double y, double z, double sh, double sv, double dust, double k) {
                sum[2] += unitRay(x, y + h, z, sh, sv, distance) * DustModel.K_MIX * dust;
            }
            public void tube(double ax, double ay, double az, double sa, double ma, double bx, double by, double bz, double sb, double mb, double k, double rise) {
                double length = Math.sqrt(Math.pow(bx - ax, 2) + Math.pow(by - ay, 2) + Math.pow(bz - az, 2));
                for (int i = 0; i < 64; i++) {
                    double f = (i + 0.5) / 64, s = sa + (sb - sa) * f;
                    blob(ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f, s, s, 0, (ma + (mb - ma) * f) * length / 64, 0, k, rise);
                }
            }
        });
        return sum;
    }
}
