package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.LocalWind;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * Шахед в мостовую у газона (жалоба: у удара рядом с травой в 40 м на 6 с стояла бурая стена — дёрн отдавал сантиметр
     * грунта, как голая земля): вокруг воронки трава. На 6 и 10 с в 40 м — та же дымка, что в улице, к минуте — чисто.
     */
    @Test
    void streetHitByALawnIsNotABrownWall() {
        CloudModel m = scene(WeaponType.DRONE, false, CALM, GroundDust.Ground.filled(1), GroundDust.Ground.filled(0));
        for (double t : new double[]{6, 10}) {
            double tau = tau(m, 0, t, 40);
            assertTrue(tau < 3, t + " с: τ в 40 м " + tau + " " + java.util.Arrays.toString(parts(m, 0, t, 40)));
        }
        assertTrue(tau(m, 0, 60, 40) < 0.2, "минута: τ в 40 м " + tau(m, 0, 60, 40));
    }

    /**
     * Шахед на перекрёстке квартала (дома 10×10×12 м, улицы по 6 м; ветер у земли места — {@link LocalWind}): в улице ветер
     * в разы слабее поля, и мгла держится дольше — на 30 с в 40 м по улице ещё видна (в поле к этому времени чисто), к
     * 5 минутам уходит. ⚠ Эталон — оценка: в улице-каньоне воздух сменяется через верх за минуты (Oke 1988), камеры
     * Харькова — мгла на 11–13 с.
     */
    @Test
    void streetCanyonHoldsTheHazeLonger() {
        GroundDust.Relief town = (x, z) -> Math.floorMod((int) Math.floor(x + 13), 16) < 10 && Math.floorMod((int) Math.floor(z + 13), 16) < 10 ? 12 : 0;
        LocalWind site = LocalWind.of((x, z) -> 64 + town.at(x - 13, z - 13), 13, 13, 1, 0);
        CloudModel.Air air = new CloudModel.Air(CALM.n(), CALM.windX(), CALM.windZ(), CALM.shear(), CALM.spread(), CALM.humidity(), site);
        CloudModel street = scene(WeaponType.DRONE, town, air), field = scene(WeaponType.DRONE, town, CALM);
        double held = tau(street, 0, 30, 40);
        assertTrue(held > 0.4 && held > 3 * tau(field, 0, 30, 40), "30 с: τ в 40 м " + held + ", в поле " + tau(field, 0, 30, 40));
        assertTrue(tau(street, 0, 300, 40) < 0.2, "5 минут: τ в 40 м " + tau(street, 0, 300, 40));
    }

    /**
     * Разрыв в 100 м над перекрёстком (ревью R468): термик меряет высоту от точки разрыва, и его ветер — ветер его высоты,
     * как над полем, а не затишье улицы; у удара в мостовую тот же термик в начале — в затишье, его сносит меньше.
     */
    @Test
    void airBurstOverTheStreetIsNotInTheStreetCalm() {
        GroundDust.Relief town = (x, z) -> Math.floorMod((int) Math.floor(x + 13), 16) < 10 && Math.floorMod((int) Math.floor(z + 13), 16) < 10 ? 12 : 0;
        LocalWind site = LocalWind.of((x, z) -> 64 + town.at(x - 13, z - 13), 13, 13, 1, 0);
        CloudModel.Air air = new CloudModel.Air(CALM.n(), CALM.windX(), CALM.windZ(), CALM.shear(), CALM.spread(), CALM.humidity(), site);
        BlastSource drone = BlastSource.of(WeaponType.DRONE, false);
        GroundDust.Ground flat = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1),
                GroundDust.Ground.filled(0), (x, z) -> 0);
        CloudModel high = new CloudModel(drone, air, false, 100, flat), highField = new CloudModel(drone, CALM, false, 100, flat);
        CloudModel low = new CloudModel(drone, air, false, 0, flat), lowField = new CloudModel(drone, CALM, false, 0, flat);
        for (double t : new double[]{1, 3}) {
            assertEquals(highField.thermal.driftX(t), high.thermal.driftX(t), 0.05 * highField.thermal.driftX(t), t + " с: в 100 м");
            assertTrue(low.thermal.driftX(t) < 0.8 * lowField.thermal.driftX(t), t + " с: у земли " + low.thermal.driftX(t) + " против " + lowField.thermal.driftX(t));
        }
    }

    private static double tau(CloudModel m, double h, double t, double d) {
        double[] c = parts(m, h, t, d);
        return c[0] + c[1] + c[2];
    }

    static CloudModel scene(WeaponType weapon, boolean roof, CloudModel.Air air) {
        return scene(weapon, roof, air, GroundDust.Ground.filled(0), GroundDust.Ground.filled(1));
    }

    /** Шахед в мостовую среди рельефа {@code relief} (м от точки удара). */
    static CloudModel scene(WeaponType weapon, GroundDust.Relief relief, CloudModel.Air air) {
        BlastSource source = BlastSource.of(weapon, false);
        BlastTarget target = BlastTarget.of(GroundMaterial.STONE, false, 0,
                BreachModel.Material.ROCK.radius(source.chargeKg(), BreachModel.UNTAMPED), Vec3.ZERO, new Vec3(0, -1, 0));
        double[][] heights = GroundDust.Ground.filled(0);
        for (int s = 0; s < GroundDust.SECTORS; s++) for (int b = 0; b < GroundDust.BANDS.length; b++) {
            double angle = 2 * Math.PI * (s + 0.5) / GroundDust.SECTORS, r = GroundDust.BANDS[b];
            heights[s][b] = relief.at(r * Math.cos(angle), r * Math.sin(angle));
        }
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1), heights, relief);
        return new CloudModel(source, air, target, 0, ground);
    }

    /** Сцена с поверхностями вокруг: доля дёрна {@code covered} и твёрдого {@code hard} по секторам и полосам. */
    static CloudModel scene(WeaponType weapon, boolean roof, CloudModel.Air air, double[][] covered, double[][] hard) {
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
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(0), covered, hard, heights, relief);
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
