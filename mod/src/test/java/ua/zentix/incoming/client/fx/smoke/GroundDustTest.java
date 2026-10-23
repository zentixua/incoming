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
        double[][] loose = new double[GroundDust.SECTORS][GroundDust.BANDS.length], covered = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS / 2; k++) java.util.Arrays.fill(loose[k], 1);
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
     * Узкий пляж: голый грунт только у воронки (до 6 м), дальше — вода. Пыль у земли — лишь там, где пляж: ни один клуб не
     * дальше 6 м·2 по середине (пыль с гальки у воронки не выносится на воду дальше; кадры F2a: ковёр на озере).
     */
    @Test
    void narrowBeachKeepsTheDustOnIt() {
        double[][] loose = new double[GroundDust.SECTORS][GroundDust.BANDS.length], covered = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            loose[k][0] = 1;
            loose[k][1] = 1;
        }
        CloudModel m = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), DAY, false, 0, new GroundDust.Ground(loose, covered));
        CloudModel open = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), DAY, false, 0, GroundDust.Ground.BARE);
        assertTrue(m.ground.total < 0.3 * open.ground.total, "пляж " + m.ground.total + " против голого поля " + open.ground.total);
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

    /** Дом: крыша радиуса {@code roof} м на высоте взрыва, вокруг — улица на 15 м ниже; везде голый грунт. */
    private static GroundDust.Ground house(double roof) {
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (double[] row : h) for (int b = 0; b < row.length; b++) row[b] = GroundDust.BANDS[b] < roof ? 0 : -15;
        return new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h,
                (x, z) -> Math.hypot(x, z) < roof ? 0 : -15);
    }

    /** Клубы слоя пыли в возрасте t: {x, y, z, σh, σv, пыль (ослабление в кг смеси), масса пыли в воздухе}. */
    private static List<double[]> layer(GroundDust g, double t) {
        List<double[]> out = new ArrayList<>();
        g.probe(t, (x, y, z, sh, sv, d, m) -> out.add(new double[]{x, y, z, sh, sv, d, m}));
        return out;
    }

    /**
     * Тяжёлый клуб над пустотой падает по закону термика MTT: замкнутая формула {@code GroundDust.fall} — та же, что
     * численный термик {@link Thermal} (РК4 тех же уравнений) с той же плавучестью g·M/ρ и объёмом, в нейтральном воздухе.
     */
    @Test
    void denseDustFallsLikeAThermal() {
        CloudModel.Air neutral = new CloudModel.Air(1e-3, 0, 0, 0.1, (int) Stability.D, 0.5);
        for (double[] c : new double[][]{{15, 5, 1.5}, {2, 1, 0.3}, {40, 12, 4}}) {
            double dust = c[0], sh = c[1], sv = c[2], volume = 2 * Math.PI * sh * sh * 1.25 * sv;
            double b = CloudModel.G * dust / CloudModel.RHO;
            Thermal th = new Thermal(b, b / volume, 0, neutral);
            for (double t = 1; t <= 60; t += 1) {
                double numeric = th.height(t) - 0.5 * th.r0, closed = GroundDust.fall(dust, sh, sv, t);
                assertEquals(numeric, closed, 0.03 * numeric + 0.02, dust + " кг, σ " + sh + "/" + sv + ", " + t + " с");
            }
        }
    }

    /**
     * Удар в крышу и удар в землю — одна физика: у крыши во все стороны (поверхность на высоте взрыва) слой тот же, что у
     * удара в голый грунт, а ровная поверхность на 20 м ниже даёт то же, что разрыв на 20 м над землёй — та же пыль на
     * той же высоте над своей поверхностью.
     */
    @Test
    void roofAndGroundAreOneLaw() {
        BlastSource s = BlastSource.of(WeaponType.MISSILE, false);
        for (double drop : new double[]{0, 20}) {
            GroundDust.Ground flat = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                    GroundDust.Ground.filled(-drop), (x, z) -> -drop);
            GroundDust a = new CloudModel(s, DAY, false, 0, flat).ground, b = new CloudModel(s, DAY, false, drop, GroundDust.Ground.BARE).ground;
            assertEquals(b.total, a.total, 1e-9 * b.total);
            for (double t : new double[]{0.1, 1, 10, 60}) {
                List<double[]> la = layer(a, t), lb = layer(b, t);
                assertEquals(lb.size(), la.size(), drop + " м, " + t + " с");
                for (int i = 0; i < la.size(); i++) {
                    for (int j = 0; j < 6; j++) assertEquals(lb.get(i)[j], la.get(i)[j], 1e-9, drop + " м, " + t + " с, клуб " + i);
                    assertTrue(la.get(i)[1] + drop <= 2 * la.get(i)[4] + 0.5, "слой — на своей поверхности");
                }
            }
        }
    }

    /**
     * Пыль не висит над пустотой (жалоба 23.10: бурый пласт на высоте крыши): у удара в крышу дома (10 и 30 м) с улицей на
     * 15 м ниже, в ветер и в штиль, каждый клуб слоя, чей путь вышел за край, опускается по закону тяжёлого термика
     * (численный {@link Thermal}: масса пыли в воздухе и размер клуба в миг выхода — независимо от модели): не медленнее и
     * не быстрее его (±10 %), а когда закон довёл бы его до улицы, он на ней; выше поверхности, с которой его подняло,
     * клуб не бывает, под поверхностью, на которую мог бы лечь, — тоже.
     */
    @Test
    void dustNeverHangsOverTheVoid() {
        CloudModel.Air neutral = new CloudModel.Air(1e-3, 0, 0, 0.1, (int) Stability.D, 0.5);
        for (double roof : new double[]{10, 30}) {
            for (double wind : new double[]{4, 0}) {
                CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, wind, 0, 0.1, (int) Stability.C, 0.5);
                GroundDust.Ground g = house(roof);
                GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
                int n = layer(dust, 0.3).size();
                double[] lifted = new double[n], crossed = new double[n];
                Thermal[] law = new Thermal[n];
                java.util.Arrays.fill(crossed, -1);
                int fell = 0;
                for (double t = 0.3; t <= 180; t += 0.25) {
                    List<double[]> now = layer(dust, t);
                    assertEquals(n, now.size(), "клубы те же");
                    for (int i = 0; i < n; i++) {
                        double[] b = now.get(i);
                        double base = b[1] - 0.8 * b[4], below = g.relief().at(b[0], b[2]);
                        if (t == 0.3) lifted[i] = base;
                        String at = "дом " + roof + " м, ветер " + wind + ", " + t + " с, клуб " + i;
                        assertTrue(base <= lifted[i] + 1e-6, at + ": выше своей поверхности");
                        // ниже поверхности под ним не уходит; выше себя больше чем на блок (стена) — не взбирается (⚠ обтекания нет)
                        assertTrue(base >= below - 1e-6 || below > base + GroundDust.DROP, at + ": под поверхностью " + below);
                        if (crossed[i] < 0 && below < lifted[i] - GroundDust.DROP) {
                            crossed[i] = t;
                            double volume = 2 * Math.PI * b[3] * b[3] * 1.25 * b[4], buoyancy = CloudModel.G * b[6] / CloudModel.RHO;
                            law[i] = new Thermal(buoyancy, buoyancy / volume, 0, neutral);
                        }
                        if (crossed[i] < 0) continue;
                        double dt = t - crossed[i], z = law[i].height(dt) - 0.5 * law[i].r0, step = law[i].height(dt + 0.5) - law[i].height(Math.max(0, dt - 0.5));
                        double target = Math.max(below, lifted[i] - z);
                        assertTrue(base <= Math.max(below, lifted[i] - 0.9 * z + step) + 0.3, at + ": висит над пустотой, " + base + " против закона " + target);
                        assertTrue(base >= Math.max(below, lifted[i] - 1.1 * z - step) - 0.3, at + ": падает быстрее закона, " + base + " против " + target);
                        if (base <= below + 0.01 && lifted[i] > below + 5) fell++;
                    }
                }
                if (roof >= 30) assertTrue(fell > 0, "дом " + roof + ", ветер " + wind + ": пыль с крыши дошла до улицы");
            }
        }
    }

    /**
     * Сколько пыли — по материалу, а не «земля или постройка»: на твёрдом (камень, кирпич) — только осевшая пыль
     * ({@code Surface.HARD}, 0,6 г/м²), в десятки раз меньше, чем на голом грунте.
     */
    @Test
    void hardSurfaceHoldsOnlySettledDust() {
        BlastSource s = BlastSource.of(WeaponType.MISSILE, false);
        GroundDust.Ground stone = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), GroundDust.Ground.filled(1),
                GroundDust.Ground.filled(Double.NaN), GroundDust.Relief.UNKNOWN);
        GroundDust hard = new CloudModel(s, DAY, false, 0, stone).ground, bare = new CloudModel(s, DAY, false, 0, GroundDust.Ground.BARE).ground;
        double outer = HobBlast.rangeFor(GroundDust.OUTER_PSI, 0, CloudModel.equivalentKt(s.chargeKg()));
        assertTrue(hard.total + hard.underBall(1e3) <= DustModel.Surface.HARD.supply() * Math.PI * outer * outer * 1.0001, "не больше осевшей пыли");
        assertTrue(hard.total < 0.05 * bare.total, "камень " + hard.total + " против голого грунта " + bare.total);
    }

    /**
     * Пласт тяжелее воздуха растекается гравитационным течением (Huppert &amp; Simpson 1980): в штиль d(R²)/dt =
     * 2·Fr·√(g·M/(πρ)) по пыли, что ещё в воздухе (в первые секунды — почти вся).
     */
    @Test
    void dustSheetSlumpsLikeAGravityCurrent() {
        GroundDust g = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD).ground;
        double r2 = (Math.pow(g.stretch(1.5), 2) - Math.pow(g.stretch(1), 2)) * g.slumpFrom / 0.5;
        double law = 2 * GroundDust.FR * Math.sqrt(CloudModel.G * g.total / (Math.PI * CloudModel.RHO));
        assertTrue(r2 <= law * 1.02 && r2 > 0.6 * law, "d(R²)/dt " + r2 + " против закона по всей поднятой пыли " + law);
        assertTrue(g.stretch(60) > g.stretch(10) && g.stretch(10) > 1, "растекается");
    }

    /**
     * Упавшая пыль обратно не взлетает (ревью: без памяти пути клуб, сошедший с крыши, прыгал на крышу следующего дома и
     * потом разом падал): два дома (крыша рыхлой земли до 10 м и дом в 40–70 м по ветру), улица на 15 м ниже, ветер 4 м/с
     * — уровень клуба за шаг 0,25 с поднимается не больше чем на блок, а лёгший на улицу клуб остаётся у улицы и у стены
     * второго дома; где рельеф пропал (нет чанков), уровень держится.
     */
    @Test
    void fallenDustStaysDown() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (double[] row : h) for (int b = 0; b < row.length; b++) row[b] = GroundDust.BANDS[b] < 10 ? 0 : -15;
        for (boolean lost : new boolean[]{false, true}) {
            GroundDust.Relief relief = (x, z) -> {
                if (Math.hypot(x, z) < 10) return 0;
                if (lost && Math.hypot(x, z) > 30) return Double.NaN;
                return x > 40 && x < 70 && Math.abs(z) < 15 ? 0 : -15;
            };
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            int n = layer(dust, 0.3).size();
            double[] last = new double[n];
            boolean[] down = new boolean[n];
            int landed = 0;
            for (double t = 0.3; t <= 120; t += 0.25) {
                List<double[]> now = layer(dust, t);
                for (int i = 0; i < n; i++) {
                    double base = now.get(i)[1] - 0.8 * now.get(i)[4];
                    String at = (lost ? "рельеф пропал, " : "") + t + " с, клуб " + i;
                    if (t > 0.3) assertTrue(base <= last[i] + GroundDust.DROP + 1e-6, at + ": уровень прыгнул " + last[i] + " → " + base);
                    if (down[i]) assertTrue(base <= -15 + GroundDust.DROP + 1e-6, at + ": лёг на улицу и снова поднялся до " + base);
                    if (!down[i] && base <= -15 + 1e-6 && last[i] > -10) {
                        down[i] = true;
                        landed++;
                    }
                    last[i] = base;
                }
            }
            if (!lost) assertTrue(landed > 0, "пыль с крыши дошла до улицы");
        }
    }
}
