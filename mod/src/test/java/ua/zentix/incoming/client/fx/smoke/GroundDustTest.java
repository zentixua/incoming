package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пыль за волной обычного взрыва (F3, «тарелка»): один закон с ядеркой, высота подрыва, пыль во времени, а не конечное
 * состояние с первого кадра.
 */
class GroundDustTest {
    private static final CloudModel.Air DAY = new CloudModel.Air(CloudModel.N_STANDARD, 4, -3, 0.1, (int) Stability.C, 0.5);

    /** Клубы облака в возрасте t: {x, y, z, σh, σv, сажа, пыль}. */
    private static List<double[]> blobs(CloudModel m, double t) {
        List<double[]> out = new ArrayList<>();
        m.blobs(t, (x, y, z, sh, sv, soot, dust, water, k, rise) -> out.add(new double[]{x, y, z, sh, sv, soot, dust}));
        return out;
    }

    /**
     * Мощность того же давления у земли: ядерный наземный W/0,5 кг (доля энергии в волне — половина, Glasstone и Dolan
     * §1.24) против заряда W кг ТНТ на земле по Кинни–Грэхему — давление в пределах трети от 1 до 50 psi у зарядов
     * игры (две разные подгонки: Броуд для ядерного, Кинни–Грэхем для ВВ).
     */
    @Test
    void equivalentYieldGivesTheSamePressure() {
        for (double kg : new double[]{6.4, 50, 150, 1000}) {
            double y = CloudModel.equivalentKt(kg);
            for (double r = 1; r < 1000; r *= 1.2) {
                double he = BlastModel.psi(BlastModel.surfaceOverpressureKpa(r, kg));
                if (he < 1 || he > 50) continue;
                double ratio = HobBlast.psi(r, 0, y) / he;
                assertTrue(ratio > 0.67 && ratio < 1.5, kg + " кг на " + r + " м: " + he + " psi ВВ против " + HobBlast.psi(r, 0, y));
            }
        }
    }

    /**
     * Один закон: слой волны обычного взрыва — тот же вынос и тот же слой Kuhl, что у ядерки ({@code DustModel.column}),
     * без света вспышки и пыли обломков, со временем развития сальтации (у ядерки фаза длиннее его); вся поднятая масса —
     * интеграл того же закона по земле.
     */
    @Test
    void oneDustLawBehindTheWave() {
        for (double kg : new double[]{6.4, 50, 150}) {
            double y = CloudModel.equivalentKt(kg), ball = 1.7 * Math.cbrt(kg);
            for (double r = 0.5; r < 200; r *= 1.5) {
                DustModel.Column he = DustModel.chemical(r, 0, y, ball), nuke = DustModel.column(r, 0, y, true, 20_000, DustModel.Surface.REFERENCE);
                // тот же вынос; у ВВ сальтация не успевает развиться за миллисекунды фазы (DustModel.SALTATION_S) — не больше
                // установившегося, а взвесь у заряда (u* от 10 порогов) — та же
                assertTrue(he.soil() <= nuke.soil() + 1e-12, kg + " кг на " + r + " м: вынос больше установившегося");
                if (r <= 1.5 * Math.cbrt(kg)) assertEquals(1, he.soil() / nuke.soil(), 0.02, kg + " кг на " + r + " м: взвесь");
                assertEquals(nuke.first(), he.first(), 1e-12, kg + " кг на " + r + " м: верх слоя Kuhl");
                assertEquals(nuke.positive(), he.positive(), 1e-12);
                assertEquals(0, he.buoyant(), "у ВВ вспышки, что греет землю, нет");
                assertEquals(0, he.debris(), "пыли обломков у поля нет");
            }
            // интеграл закона по земле (мелкий шаг) — то, что слой поднял (у земли и под шаром)
            double outer = HobBlast.rangeFor(GroundDust.OUTER_PSI, 0, y), sum = 0;
            int n = 4000;
            for (int i = 0; i < n; i++) {
                double r0 = outer * Math.pow((double) i / n, 2), r1 = outer * Math.pow((i + 1.0) / n, 2), r = 0.5 * (r0 + r1);
                sum += DustModel.chemical(r, 0, y, ball).mass() * Math.PI * (r1 * r1 - r0 * r0);
            }
            GroundDust g = new CloudModel(BlastSource.of(kg > 100 ? WeaponType.MISSILE : WeaponType.DRONE, false), DAY).ground;
            if (kg == 6.4) g = new CloudModel(BlastSource.of(WeaponType.ROCKET, false), DAY).ground;
            assertEquals(1, (g.total + g.underBall(1e3)) / sum, 0.05, kg + " кг: поднятое слоем против интеграла закона");
        }
    }

    /**
     * Пыль во времени: до фронта её нет, в первую секунду ни один клуб пыли у земли не шире фронта волны в этот миг, масса
     * растёт от нуля; верх слоя в 0,1 с — пыльный слой Kuhl (дециметры у шахеда), а не радиус облака Чёрча.
     */
    @Test
    void dustComesWithTheFront() {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), DAY);
        double before = -1;
        for (double t : new double[]{0, 0.002, 0.005, 0.01, 0.03, 0.06, 0.1, 0.2, 0.3, 1}) {
            double front = m.ground.front(t), mass = 0;
            for (double[] b : blobs(m, t)) {
                if (b[6] <= 0 || b[5] > 0 || b[1] > 2 * b[4] + 0.5) continue; // пыль у земли (облако и ножка — выше)
                mass += b[6];
                double r = Math.hypot(b[0] - m.ground.drift(t, b[1])[0], b[2] - m.ground.drift(t, b[1])[1]);
                // видимый край клуба (толща у края — 0,05: у густого ~3,7σ, не дальше 4σ) — не за фронтом
                assertTrue(r + GroundDust.EDGE_SIGMAS * b[3] <= front + 0.5, "t " + t + ": клуб на " + r + " м, σ " + b[3] + " — за фронтом " + front);
                if (t <= 0.1) assertTrue(b[4] < 1, "t " + t + ": σ слоя по вертикали " + b[4] + " — Kuhl, дециметры");
            }
            if (t == 0) assertEquals(0, mass, "до фронта пыли нет");
            // за фазу волны пыль только прибывает (потом крупная оседает)
            if (t <= 0.1) assertTrue(mass >= before - 1e-9, "t " + t + ": пыль у земли растёт от нуля, " + before + " → " + mass);
            before = mass;
        }
        assertTrue(before > 10, "через секунду у земли — десятки кг пыли: " + before);
    }

    /**
     * Земля — там, где она есть: выше порога воронки пыли воронки нет; у разрыва зенитной ракеты на высоте полёта шахеда
     * пыль волны — только у земли (ни одного клуба пыли на высоте разрыва), а с высоты 100 м её меньше полукилограмма.
     */
    @Test
    void airburstHasNoGroundDustInTheAir() {
        double kg = InterceptorSpec.SAM.passport().warhead().tnt();
        BlastSource sam = BlastSource.air(kg);
        double ball = 1.7 * Math.cbrt(kg);
        assertTrue(CloudModel.digs(kg, 0));
        assertFalse(CloudModel.digs(kg, CloudModel.CRATER_HOB * ball * 1.01), "выше порога воронки нет");
        for (double hob : new double[]{CloudModel.CRATER_HOB * ball * 1.5, 10, 45, 100, 300}) {
            CloudModel m = new CloudModel(sam, DAY, false, hob);
            assertEquals(0, m.dust, hob + " м: пыли воронки нет");
            if (hob > ball) assertEquals(0, m.ground.underBall(10), hob + " м: шар земли не касается");
            for (double t : new double[]{0.05, 0.1, 0.5, 1, 3, 10, 30}) {
                double aloft = 0;
                for (double[] b : blobs(m, t)) if (b[6] > 0 && b[1] + hob > 3 * b[4] + 10) aloft += b[6];
                // разрыв на высоте полёта шахеда и выше: пыли в воздухе (выше слоя у земли) — меньше 0,1 кг: ни облака пыли у
                // точки разрыва, ни «тарелки»; ниже — подсос ножки может поднять пыль слоя (закон ядерки)
                if (hob >= 45) assertTrue(aloft < 0.1, hob + " м, t " + t + ": пыли в воздухе " + aloft + " кг");
                if (hob >= 100) assertTrue(m.ground.total < 0.5, hob + " м: пыли у земли " + m.ground.total + " кг (у шахеда на земле — сотни)");
            }
        }
    }

    /**
     * Ослабление пыли по корзинам: крупная оседает первой и ослабляет мало — доля ослабления в воздухе не меньше доли
     * массы; облако взрыва держит пыль по ослаблению (в 3.5.0 — по массе: облако бледнело быстрее, чем осела его толща).
     */
    @Test
    void fineDustOutlastsCoarse() {
        for (double j = 0; j < 1e4; j = j * 2 + 0.1) {
            assertTrue(DustModel.extinctionAloft(j) >= DustModel.airborne(j) - 1e-12, "∫dt/h " + j);
        }
        assertEquals(1, DustModel.extinctionAloft(0), 1e-12);
        assertTrue(DustModel.extinctionAloft(60) > 0.7 && DustModel.airborne(60) < 0.35, "через минуту в слое в метр");
    }

    /**
     * Трава держит грунт: на лугу (рыхлого — ноль) пыль волны встаёт только у воронки — во много раз меньше, чем над голым
     * грунтом, и не дальше ~15 м у шахеда (порог срыва 3 м/с против 0,34, Gaj &amp; Small).
     */
    @Test
    void grassHoldsTheSoil() {
        BlastSource s = BlastSource.of(WeaponType.DRONE, false);
        CloudModel bare = new CloudModel(s, DAY, false, 0, GroundDust.Ground.BARE), meadow = new CloudModel(s, DAY, false, 0, GroundDust.Ground.uniform(0, 1));
        assertTrue(meadow.ground.total < 0.25 * bare.ground.total, "луг " + meadow.ground.total + " против " + bare.ground.total);
        DustModel.Column far = DustModel.chemical(20, 0, CloudModel.equivalentKt(50), 6.3, DustModel.Surface.COVERED);
        assertTrue(far.soil() * DustModel.K_MIX < 0.01, "на лугу в 20 м пыли почти нет: толща " + far.soil() * DustModel.K_MIX);
    }

    /**
     * Вода не пылит: у берега (половина секторов — вода) пыль волны лежит только над сушей — ни одного клуба пыли у земли в
     * секторах воды (бомба у пляжа: ковёр пыли ложился и на озеро, кадры F2a).
     */
    @Test
    void waterGivesNoDust() {
        double[] loose = new double[GroundDust.SECTORS], covered = new double[GroundDust.SECTORS];
        for (int k = 0; k < GroundDust.SECTORS / 2; k++) loose[k] = 1;
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), DAY, false, 0, new GroundDust.Ground(loose, covered));
        int land = 0;
        for (double t : new double[]{0.1, 1, 3}) {
            for (double[] b : blobs(m, t)) {
                if (b[6] <= 0 || b[5] > 0 || b[1] > 2 * b[4] + 0.5) continue;
                double[] d = m.ground.drift(t, b[1]);
                double ang = Math.atan2(b[2] - d[1], b[0] - d[0]);
                if (ang < 0) ang += 2 * Math.PI;
                int sector = (int) (ang / (2 * Math.PI) * GroundDust.SECTORS);
                assertTrue(sector < GroundDust.SECTORS / 2, "t " + t + ": пыль над водой, сектор " + sector);
                land++;
            }
        }
        assertTrue(land > 0, "над сушей пыль есть");
    }

    /**
     * Пыль у земли живёт минутами и уходит с ветром у земли: через 30 с у шахеда днём слой ещё виден, его середина — на
     * пути ветра у земли, не выше десятков метров (а облако — выше).
     */
    @Test
    void dustStaysAtTheGround() {
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), DAY);
        assertTrue(m.groundTauForTest(30) > 0.5, "30 с: слой виден, " + m.groundTauForTest(30));
        assertTrue(m.ground.life > 120, "слой живёт минуты: " + m.ground.life);
        double low = Double.MAX_VALUE;
        for (double[] b : blobs(m, 30)) if (b[6] > 0 && b[5] == 0) low = Math.min(low, b[1]);
        assertTrue(low < 0.5 * m.height(30), "у земли — пыль, а не только облако: " + low + " против " + m.height(30));
    }
}
