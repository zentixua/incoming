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
            // This invariant belongs to wave entrainment/Kuhl, not to particles leaving the hot gas.
            for (double[] b : layer(m.ground, t)) {
                if (b[5] <= 0) continue;
                mass += b[5];
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
            // Test the wave's source, not land-origin crushed material that can be thrown/carried over water.
            for (double[] b : layer(m.ground, t)) {
                if (b[5] <= 0) continue;
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

    /**
     * Клубы слоя пыли в возрасте t: {x, y, z, σh, σv, пыль (ослабление в кг смеси), масса пыли в воздухе, поверхность, с
     * которой её подняло}.
     */
    private static List<double[]> layer(GroundDust g, double t) {
        List<double[]> out = new ArrayList<>();
        g.probe(t, (x, y, z, sh, sv, d, m, src) -> out.add(new double[]{x, y, z, sh, sv, d, m, src}));
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

    /** Узлы пути клубов от {@code from} до {@code to} с: в них уровень клуба — не середина шага. */
    private static List<Double> nodes(double from, double to) {
        List<Double> out = new ArrayList<>();
        for (double t : GroundDust.PATH) if (t >= from && t <= to) out.add(t);
        return out;
    }

    /** Поверхность под клубом {x, y, z, σh, …} — как её видит слой (по его следу, {@code GroundDust.under}). */
    private static double under(GroundDust g, double[] b) {
        return g.under(b[0], b[2], 0.5 * b[3]);
    }

    /**
     * Пыль не висит над пустотой (жалоба 23.10: бурый пласт на высоте крыши): у удара в крышу дома (10 и 30 м) с улицей на
     * 15 м ниже, в ветер и в штиль, каждый клуб, чей путь вышел за край, опускается быстрее из двух — тяжёлым термиком
     * (численный {@link Thermal}: масса пыли в воздухе и размер клуба в миг выхода — независимо от модели) или ветром за
     * кромкой ({@code GroundDust.LEE} на метр сноса): не медленнее и не быстрее (±10 %), а когда закон довёл бы его до
     * улицы, он на ней; под поверхность под собой не уходит.
     */
    @Test
    void dustNeverHangsOverTheVoid() {
        CloudModel.Air neutral = new CloudModel.Air(1e-3, 0, 0, 0.1, (int) Stability.D, 0.5);
        for (double roof : new double[]{10, 30}) {
            for (double wind : new double[]{4, 0}) {
                CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, wind, 0, 0.1, (int) Stability.C, 0.5);
                CloudModel model = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, house(roof));
                GroundDust dust = model.ground;
                List<Double> ts = nodes(1, 180);
                int n = layer(dust, ts.getFirst()).size();
                double[] lifted = new double[n], crossed = new double[n], last = new double[n];
                double[][] edge = new double[n][];
                Thermal[] law = new Thermal[n];
                java.util.Arrays.fill(crossed, -1);
                int fell = 0;
                for (double t : ts) {
                    List<double[]> now = layer(dust, t);
                    assertEquals(n, now.size(), "клубы те же");
                    for (int i = 0; i < n; i++) {
                        double[] b = now.get(i);
                        double base = b[1] - 0.8 * b[4], below = under(dust, b);
                        if (t == ts.getFirst()) lifted[i] = last[i] = base;
                        String at = "дом " + roof + " м, ветер " + wind + ", " + t + " с, клуб " + i;
                        assertTrue(base >= below - 1e-6 || base >= last[i] - 1e-6, at + ": уходит под поверхность " + below);
                        last[i] = base;
                        if (crossed[i] < 0 && below < lifted[i] - GroundDust.DROP) {
                            crossed[i] = t;
                            edge[i] = dust.drift(t, 0.8 * b[4]);
                            double volume = 2 * Math.PI * b[3] * b[3] * 1.25 * b[4], buoyancy = CloudModel.G * b[6] / CloudModel.RHO;
                            law[i] = new Thermal(buoyancy, buoyancy / volume, 0, neutral);
                        }
                        if (crossed[i] < 0) continue;
                        // опускание — быстрее из двух: тяжёлый термик или ветер за кромкой (LEE метра на метр сноса)
                        double dt = t - crossed[i], z = law[i].height(dt) - 0.5 * law[i].r0;
                        double[] d = dust.drift(t, 0.8 * b[4]);
                        double lee = GroundDust.LEE * Math.hypot(d[0] - edge[i][0], d[1] - edge[i][1]), down = Math.max(z, lee);
                        // допуск — на шаг узлов пути (миг выхода за край известен до узла): ±0,5 с закона и сноса
                        double[] da = dust.drift(t + 0.5, 0.8 * b[4]), db = dust.drift(Math.max(0, t - 0.5), 0.8 * b[4]);
                        double step = law[i].height(dt + 0.5) - law[i].height(Math.max(0, dt - 0.5)) + GroundDust.LEE * Math.hypot(da[0] - db[0], da[1] - db[1]);
                        assertTrue(base <= Math.max(below, lifted[i] - 0.9 * down + step) + 0.3, at + ": висит над пустотой, " + base + " против закона " + (lifted[i] - down));
                        assertTrue(base >= Math.min(below, lifted[i] - 1.1 * down - step) - 0.3, at + ": падает быстрее закона, " + base + " против " + (lifted[i] - down));
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
     * Упавшая пыль не прыгает на крышу следующего дома (ревью: без памяти пути клуб, сошедший с крыши, прыгал на крышу
     * соседнего и разом падал): два дома (крыша рыхлой земли до 10 м и дом 15 м в 40–70 м по ветру), улица на 15 м ниже,
     * ветер 4 м/с; и где рельеф дальше 30 м пропал — уровень клуба от узла к узлу пути поднимается не круче 45° по пути
     * сноса ветром (ветер несёт слой через дом), и пыль с крыши доходит до улицы.
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
            // с 3 с: в первые секунды место клуба на кадре зажато фронтом волны и не совпадает с путём
            List<Double> ts = nodes(3, 120);
            for (double t : nodes(0.25, 3)) layer(dust, t);
            List<double[]> prev = layer(dust, ts.getFirst());
            double before = ts.getFirst();
            int landed = 0;
            for (double t : ts.subList(1, ts.size())) {
                List<double[]> now = layer(dust, t);
                for (int i = 0; i < now.size(); i++) {
                    double[] a = prev.get(i), b = now.get(i);
                    // вверх слой несёт только ветер: путь — снос ветром на высоте середины слоя
                    double[] w0 = dust.drift(before, 0.8 * a[4]), w1 = dust.drift(t, 0.8 * b[4]);
                    double rise = (b[1] - 0.8 * b[4]) - (a[1] - 0.8 * a[4]), path = Math.hypot(w1[0] - w0[0], w1[1] - w0[1]);
                    assertTrue(rise <= GroundDust.DROP * path + 0.8 * Math.abs(b[4] - a[4]) + 1e-6,
                            (lost ? "рельеф пропал, " : "") + t + " с, клуб " + i + ": поднялся на " + rise + " за " + path + " м пути");
                    if (b[1] - 0.8 * b[4] <= -15 + 1e-6 && a[1] - 0.8 * a[4] > -15 + 1e-6) landed++;
                }
                prev = now;
                before = t;
            }
            if (!lost) assertTrue(landed > 0, "пыль с крыши дошла до улицы");
        }
    }

    /**
     * Слой идёт по склону, а не отрывается от него (второе ревью: порог края был на шаг сетки — над склоном вниз 17°
     * клуб висел на сотню метров, а вверх по склону уходил в него; восьмое — старт с точки полосы: на склоне 31° клуб сразу
     * «падал с края» и висел над склоном): удар в голый грунт на склоне 10°, 17°, 31° и 42° вниз и вверх по ветру 4 м/с —
     * с 3 до 300 с низ каждого клуба в узлах пути — на поверхности под ним (в пределах блока).
     */
    @Test
    void dustFollowsSlopes() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        for (double slope : new double[]{-0.9, -0.6, -0.3, -0.18, 0.18, 0.3, 0.6, 0.9}) {
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = Math.floor(slope * GroundDust.BANDS[b] * Math.cos(ang));
            }
            GroundDust.Relief relief = (x, z) -> Math.floor(slope * x);
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            // как в игре — с первого кадра (путь строится по тому, что видел первый вызов); проверка — с 3 с: в первую
            // секунду растущий след опускает низ под клубом быстрее, чем слой успевает лечь (по закону)
            for (double t = 1 / 60.0; t < 3; t += 1 / 60.0) layer(dust, t);
            for (double t : nodes(3, 300)) {
                for (double[] b : layer(dust, t)) {
                    double base = b[1] - 0.8 * b[4], below = under(dust, b);
                    String at = "склон " + slope + ", " + t + " с: низ " + base + " над рельефом " + below;
                    // у склона почти в 45° (предел подъёма) ступени рельефа дают отставание ещё на блок
                    assertTrue(Math.abs(base - below) <= (Math.abs(slope) > 0.7 ? 2 : 1) * GroundDust.DROP + 1e-6, at);
                }
            }
        }
    }

    /**
     * Столбы, стволы и фонари клуб не держат (третье ревью: поверхность в одной точке — столб в блок останавливал клуб
     * шириной в десятки метров): ракета на голом грунте, ветер 4 м/с, столбы 1×1 в 6 м в каждой сотой колонке — клубы идут
     * так же и на той же высоте, как без них (до 60 с — не дальше 2 м, низ — в пределах блока).
     */
    @Test
    void postsDoNotHoldTheLayer() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        // столбы и стволы — в каждой сотой колонке, вразброс
        GroundDust.Relief posts = (x, z) -> Math.floorMod(java.util.Objects.hash((int) Math.floor(x), (int) Math.floor(z)) * 0x9E3779B1, 100) == 0 ? 6 : 0;
        GroundDust.Ground withPosts = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), posts);
        GroundDust.Ground open = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), (x, z) -> 0);
        BlastSource s = BlastSource.of(WeaponType.MISSILE, false);
        GroundDust a = new CloudModel(s, air, false, 0, withPosts).ground, b = new CloudModel(s, air, false, 0, open).ground;
        for (double t = 0.3; t <= 60; t += 0.5) {
            List<double[]> la = layer(a, t), lb = layer(b, t);
            assertEquals(lb.size(), la.size());
            for (int i = 0; i < la.size(); i++) {
                double d = Math.hypot(la.get(i)[0] - lb.get(i)[0], la.get(i)[2] - lb.get(i)[2]);
                double up = (la.get(i)[1] - 0.8 * la.get(i)[4]) - (lb.get(i)[1] - 0.8 * lb.get(i)[4]);
                assertTrue(d <= 2, t + " с, клуб " + i + ": столбы задержали на " + d + " м");
                assertTrue(Math.abs(up) <= GroundDust.DROP, t + " с, клуб " + i + ": столбы подняли слой на " + up + " м");
            }
        }
    }

    /**
     * Падающий клуб над неизвестным рельефом (нет чанков и карты) падает дальше — до самой низкой поверхности, что видел
     * взрыв, а не висит (третье ревью): шахед в крышу 10 м, улица на 15 м ниже, рельеф известен только до 14 м — к 120 с
     * ни один клуб не выше 1 м над улицей.
     */
    @Test
    void fallingDustOverUnknownReliefStillFalls() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 4, 0, 0.1, (int) Stability.C, 0.5);
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (double[] row : h) for (int b = 0; b < row.length; b++) row[b] = GroundDust.BANDS[b] < 10 ? 0 : -15;
        GroundDust.Relief known = (x, z) -> Math.hypot(x, z) < 10 ? 0 : Math.hypot(x, z) < 14 ? -15 : Double.NaN;
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, known);
        GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
        for (double t = 0.3; t <= 120; t += 0.5) layer(dust, t);
        for (double[] b : layer(dust, 120)) assertTrue(b[1] - 0.8 * b[4] <= -15 + 1 + 1e-6, "120 с: клуб в " + b[0] + " м, низ " + (b[1] - 0.8 * b[4]));
    }

    /**
     * В штиль пыль улицы не забирается на крыши (четвёртое ревью: предел подъёма был блок на выборку, и неподвижный клуб,
     * чей растущий след задевал дома, вставал на высоту крыш над улицей): шахед на перекрёстке (дома 10×10 высотой 12 м,
     * улицы по 6 м), штиль — до 240 с низ клуба, поднятого с улицы, пока слой тоньше домов (2σv < 12), не выше 1 м над
     * улицей, а толще — накрывает квартал: низ не выше половины высоты домов или четверти своей толщи (2σv).
     */
    @Test
    void calmStreetDustStaysInTheStreet() {
        GroundDust.Relief town = GroundDustTest::town;
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
            for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = town.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
        }
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, town);
        GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD, false, 0, g).ground;
        List<Double> ts = nodes(1, 240);
        List<double[]> first = layer(dust, ts.getFirst());
        for (double t : ts) {
            List<double[]> now = layer(dust, t);
            for (int i = 0; i < now.size(); i++) {
                double[] b = now.get(i);
                // пыль, поднятая с улицы (по поверхности, с которой её подняло, а не по тому, где клуб уже стоит)
                if (b[7] > 0.5) continue;
                double base = b[1] - 0.8 * b[4];
                // слой тоньше домов (2σv < 12) — на улице; толще — переливается через крыши, но лежит не выше среднего верха
                // под своим следом
                if (2 * b[4] < 9) assertTrue(base <= 1 + 1e-6, t + " с, клуб " + i + ": тонкая пыль улицы на высоте " + base);
                else assertTrue(base <= Math.max(4.5, 0.5 * b[4]) + 1e-6, t + " с, клуб " + i + ": толстая пыль улицы на высоте " + base);
            }
        }
    }

    /**
     * В ветер пыль улицы тоже не встаёт на высоту крыш (пятое ревью: ветер поднимал слой на дом за метры, а за домом
     * опускал минутами — бурый пласт на высоте крыш над городом): шахед на перекрёстке с рыхлым грунтом, ветер 5 м/с — в
     * квартале (дома 10×10×12 м, улицы по 6 м) и перед башней 40 м шириной 20 м в 30–50 м по ветру до 120 с низ клуба,
     * поднятого с улицы, пока слой тоньше дома, не выше блока над улицей (на стены слой не взбирается, в застройке ветер
     * идёт поверху, skimming flow), а толще — накрывает дома: низ не выше половины их высоты или четверти своей толщи
     * (на миг, пока след весь над крышами, — не дольше 3 с подряд).
     */
    @Test
    void windyStreetDustStaysInTheStreet() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.C, 0.5);
        GroundDust.Relief town = GroundDustTest::town;
        GroundDust.Relief tower = (x, z) -> x > 30 && x < 50 && Math.abs(z) < 10 ? 40 : 0;
        for (GroundDust.Relief relief : List.of(town, tower)) {
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = relief.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
            }
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            String scene = relief == town ? "квартал" : "башня";
            List<Double> ts = nodes(1, 120);
            List<double[]> first = layer(dust, ts.getFirst());
            double[] high = new double[first.size()];
            double last = ts.getFirst();
            for (double t : ts) {
                List<double[]> now = layer(dust, t);
                for (int i = 0; i < now.size(); i++) {
                    double[] b = now.get(i);
                    if (b[7] > 0.5) continue;
                    double base = b[1] - 0.8 * b[4], wall = relief == town ? 9 : 40;
                    // слой тоньше домов — на улице; толще — переливается, но лежит не выше среднего верха под своим следом
                    if (2 * b[4] < wall) assertTrue(base <= 1 + 1e-6, scene + ", " + t + " с, клуб " + i + ": тонкая пыль улицы на высоте " + base);
                    // толще — накрывает: низ не выше половины дома или четверти своей толщи (пласта над пустой улицей нет)
                    else {
                        // след на миг весь на крышах — низ поднимается на узел-другой; дольше 3 с подряд — уже пласт
                        if (base > Math.max(0.5 * wall, 0.5 * b[4])) high[i] += t - last;
                        else high[i] = 0;
                        assertTrue(high[i] <= 3, scene + ", " + t + " с, клуб " + i + ": толстая пыль улицы на высоте " + base + " уже " + high[i] + " с");
                    }
                }
                last = t;
            }
        }
    }

    /**
     * Ступень и склон за ней (шестое и седьмое ревью: ступень, раз встреченная, оставалась стеной или «накрытой» навсегда,
     * и клуб шёл под террасой и под холмом): голый грунт, ветер 5 м/с — ступень в 2 блока (ниже слоя) и в 4 блока (выше
     * раннего слоя) в 10–20 м от удара и склон 17° за ней. Низ клуба нигде не глубже под поверхностью под ним, чем толща
     * слоя или ступень, у которой он ждёт, а клуб, прошедший ступень дальше чем на свою ширину (σh) и ещё 20 м, — снова на
     * поверхности (в пределах двух блоков).
     */
    @Test
    void dustClimbsStepsAndHillsBehindThem() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.C, 0.5);
        for (double[] c : new double[][]{{20, 2}, {10, 4}}) {
            double at = c[0], rise = c[1];
            GroundDust.Relief step = (x, z) -> x < at ? 0 : Math.floor(rise + 0.3 * Math.max(0, x - at - 10));
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = step.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
            }
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, step);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, false, 0, g).ground;
            for (double t : nodes(3, 90)) {
                for (double[] b : layer(dust, t)) {
                    double base = b[1] - 0.8 * b[4], below = under(dust, b);
                    String where = "ступень " + rise + " в " + at + " м, " + t + " с: клуб в " + b[0] + " м, низ " + base + ", поверхность " + below + ", σv " + b[4];
                    // тоньше ступени — ждёт у неё на своём уровне (не глубже самой ступени), толще — на поверхности
                    assertTrue(base >= below - Math.max(2 * b[4], rise) - GroundDust.DROP - 1e-6, where + " — глубже толщи и ступени");
                    if (b[0] > at + b[3] + 20) assertTrue(base >= below - 2 * GroundDust.DROP - 1e-6, where + " — не вышел на поверхность");
                }
            }
        }
    }

    /**
     * Квартал: участки 16×16 со сдвигом улиц, на участке дом 10×10 высотой 9–15 м (по хешу участка), между ними улицы по 6 м;
     * перекрёсток — в точке взрыва. Не по линейке: у настоящего города шаг домов не один.
     */
    private static double town(double x, double z) {
        int cx = Math.floorDiv((int) Math.floor(x) + 11, 16), cz = Math.floorDiv((int) Math.floor(z) + 11, 16);
        int hash = java.util.Objects.hash(cx, cz) * 0x9E3779B1;
        int ox = Math.floorMod(hash >> 4, 3), oz = Math.floorMod(hash >> 8, 3);
        boolean house = Math.floorMod((int) Math.floor(x) + 11, 16) - ox >= 0 && Math.floorMod((int) Math.floor(x) + 11, 16) - ox < 10
                && Math.floorMod((int) Math.floor(z) + 11, 16) - oz >= 0 && Math.floorMod((int) Math.floor(z) + 11, 16) - oz < 10;
        return house && !(cx == 0 && cz == 0 && Math.hypot(x, z) < 3) ? 9 + Math.floorMod(hash >> 12, 7) : 0;
    }

    /**
     * Обрыв, а за ним крутой склон (девятое ревью: падающий клуб опускался только законом падения, а склон 31° под ним
     * уходил быстрее — к минуте он висел на 50 м над склоном): ракета на голом грунте, ветер 5 м/с, обрыв 8 м в 15 м от
     * удара и склон 31° за ним; кадры с первого — с 60 с клубы за 60 м от удара — на поверхности под ними (в пределах
     * двух блоков).
     */
    @Test
    void dustFallingOffACliffLandsOnTheSlopeBelow() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.D, 0.5);
        GroundDust.Relief cliff = (x, z) -> x < 15 ? 0 : Math.floor(-8 - 0.6 * (x - 15));
        double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
        for (int k = 0; k < GroundDust.SECTORS; k++) {
            double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
            for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = cliff.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
        }
        GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, cliff);
        GroundDust dust = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air, false, 0, g).ground;
        for (double t = 1 / 60.0; t < 60; t += 1 / 60.0) layer(dust, t);
        for (double t : nodes(60, 240)) {
            for (double[] b : layer(dust, t)) {
                if (b[0] < 60) continue;
                double base = b[1] - 0.8 * b[4], below = under(dust, b);
                assertTrue(base <= below + 2 * GroundDust.DROP + 1e-6, t + " с: клуб в " + b[0] + " м висит на " + (base - below) + " над склоном");
            }
        }
    }

    /**
     * Овраг рядом и крутой склон вверх у ракеты (десятое ревью: минимум по следу утягивал широкий клуб на дно оврага в 6 м,
     * и пыль этой стороны минуту лежала под землёй; старт внешнего кольца с точки полосы в 48 м сажал клуб в склон): кадры
     * с первого, ветер 5 м/с. Овраг 6×25 м в 12 м от удара — с 3 с клуб, чей след ни разу не лёг на овраг хоть на ~20 %,
     * не ниже ровной земли больше чем на блок (стёкший в овраг в нём и скапливается); склон 39° вверх по ветру — с 3 с низ каждого клуба не ниже поверхности
     * под ним больше чем на два блока.
     */
    @Test
    void gullyAndSteepUpslopeKeepDustOnTheSurface() {
        CloudModel.Air air = new CloudModel.Air(CloudModel.N_STANDARD, 5, 0, 0.1, (int) Stability.D, 0.5);
        GroundDust.Relief gully = (x, z) -> x >= 12 && x < 18 ? -25 : 0;
        GroundDust.Relief slope = (x, z) -> Math.floor(0.8 * x);
        for (GroundDust.Relief relief : List.of(gully, slope)) {
            double[][] h = new double[GroundDust.SECTORS][GroundDust.BANDS.length];
            for (int k = 0; k < GroundDust.SECTORS; k++) {
                double ang = 2 * Math.PI * (k + 0.5) / GroundDust.SECTORS;
                for (int b = 0; b < GroundDust.BANDS.length; b++) h[k][b] = relief.at(GroundDust.BANDS[b] * Math.cos(ang), GroundDust.BANDS[b] * Math.sin(ang));
            }
            GroundDust.Ground g = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), h, relief);
            GroundDust dust = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air, false, 0, g).ground;
            String scene = relief == gully ? "овраг" : "склон";
            for (double t = 1 / 60.0; t < 3; t += 1 / 60.0) layer(dust, t);
            boolean[] inside = new boolean[layer(dust, 3).size()];
            for (double t : nodes(3, 120)) {
                List<double[]> now = layer(dust, t);
                for (int i = 0; i < now.size() && i < inside.length; i++) {
                    double[] b = now.get(i);
                    double base = b[1] - 0.8 * b[4], centre = relief.at(b[0], b[2]);
                    if (relief == gully) {
                        // клуб, чей след хоть раз заметно (от ~20 %: три выборки из 13) лёг на овраг, в него стёк и там
                        // скапливается (тяжёлый воздух стоит в низине) — не в счёт; остальные на дно не уходят
                        int over = 0;
                        for (double[] d : GroundDust.FOOTPRINT) if (gully.at(b[0] + 0.5 * b[3] * d[0], b[2] + 0.5 * b[3] * d[1]) < 0) over++;
                        if (over >= 3) inside[i] = true;
                        if (!inside[i]) assertTrue(base >= -GroundDust.DROP - 1e-6, scene + ", " + t + " с, клуб " + i + ": на дне оврага, хотя над ним " + over + " из 13 выборок следа, низ " + base);
                    } else {
                        assertTrue(base >= under(dust, b) - 2 * GroundDust.DROP - 1e-6, scene + ", " + t + " с, клуб " + i + ": в склоне, низ " + base + " под " + under(dust, b));
                    }
                }
            }
        }
    }
}
