package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.ClientWeaponSpec.FarLook;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.util.Wind;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.render.FarModels;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Снаряд вдали: тень корпуса и след при любой дальности, факел днём и ночью, точки шлейфа по фазам и пакетам. */
class FarFlightViewTest {
    private static final double PIXEL = Math.toRadians(70) / 1080;
    private static final UUID ID = UUID.randomUUID();

    private static FarLook far(WeaponType weapon) {
        return ClientWeaponSpec.of(weapon).airframe(false).far();
    }

    @Test
    void bodyIsTrueSizeAndCoversTheSameSky() {
        double[] out = new double[2];
        for (WeaponType w : WeaponType.values()) {
            FarLook look = far(w);
            double r = Math.sqrt(look.area() / Math.PI);
            for (double d : new double[]{50, 300, 1000, 4000, 8000}) {
                FarFlightView.body(look.area(), d, PIXEL, out);
                assertEquals(Math.max(r, 0.5 * Sight.MIN_PIXELS * PIXEL * d), out[0], 1e-9, w + " на " + d + ": настоящий размер или пятно");
                assertEquals(r * r, out[1] * out[0] * out[0], 1e-9, w + " на " + d + ": закрыто то же небо");
            }
        }
    }

    @Test
    void droneFadesWithDistanceLikeACamera() {
        // силуэт шахеда (размах 2,5 м) — круг 1,07 м: за 1,5 км — 1,3 px, пятно в 1,5 px чуть бледнее; за 4 км — бледная точка
        double[] out = new double[2];
        FarFlightView.body(far(WeaponType.DRONE).area(), 1500, PIXEL, out);
        assertEquals(0.5 * Sight.MIN_PIXELS * PIXEL * 1500, out[0], 1e-9);
        assertTrue(out[1] > 0.6 && out[1] < 0.8, "за 1,5 км: " + out[1]);
        FarFlightView.body(far(WeaponType.DRONE).area(), 4000, PIXEL, out);
        assertTrue(out[1] > 0.05 && out[1] < 0.15, "за 4 км: " + out[1]);
        // B-2 (размах 52 м) за 8 км — больше пятна: настоящего размера и сплошной
        FarFlightView.body(far(WeaponType.BUNKER).area(), 8000, PIXEL, out);
        assertEquals(1, out[1], 1e-12);
    }

    @Test
    void thinTrailKeepsItsCoverage() {
        double[] out = new double[2];
        FarFlightView.ribbon(4, 100, PIXEL, out);
        assertEquals(2 * FarSprites.RIBBON, out[0], 1e-9, "вблизи — своей ширины");
        assertEquals(1, out[1], 1e-12);
        FarFlightView.ribbon(1, 5000, PIXEL, out);
        double drawn = out[0] / FarSprites.RIBBON;
        assertEquals(0.75 * PIXEL * 5000, drawn, 1e-9, "уже пикселя — полтора пикселя");
        assertEquals(1, 2 * drawn * out[1], 1e-9, "и бледнее во столько же раз");
    }

    @Test
    void rocketMotorIsSparkByDayAndGlareAtNight() {
        Flame flame = far(WeaponType.ROCKET).stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(flame);
        double d = 2000, t = Sight.transmittance(d, Sight.CLEAR);
        double[] day = new double[5], night = new double[5];
        Sight.light(flame.lightRadius(), Sight.adapted(flame.brightness(), 1) * t, d, PIXEL, day);
        Sight.light(flame.lightRadius(), Sight.adapted(flame.brightness(), 0.17) * t, d, PIXEL, night);
        assertTrue(day[2] < 1 && day[4] == 0, "днём — искра без вуали: " + day[2]);
        assertTrue(night[2] > 1 && night[4] / d > 0.01, "ночью — блик и вуаль: " + night[2] + ", " + Math.toDegrees(night[4] / d) + "°");
        // ночью видно и за 8 км
        double far = 8000;
        Sight.light(flame.lightRadius(), Sight.adapted(flame.brightness(), 0.17) * Sight.transmittance(far, Sight.CLEAR), far, PIXEL, night);
        assertTrue(night[2] > 1, "за 8 км ночью: " + night[2]);
    }

    /**
     * Ночной старт сзади (сцена шлагбаума launch-night-behind): камера в 8,4 м от середины факела ускорителя ракеты. Глаз
     * привыкает и к огню у себя ({@code HeatPass} собирает его в кадре, {@link Sight#exposure} — одно привыкание на кадр):
     * факел ярче белого в единицы раз, и блик — бледный ореол. С одним ночным ×1200 (жалоба 22.10: «кадр заливает
     * оранжевым») блик был во весь экран в полную силу. Вдали факел глаз не переучивает: блик и вуаль за 2 км — как были.
     */
    @Test
    void nearFlameAtNightAdaptsTheEye() {
        Flame flame = far(WeaponType.MISSILE).stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(flame);
        double night = 0.17, d = 8.4, r = flame.lightRadius();
        double[] out = new double[5];
        // по-старому: только свет неба
        double old = flame.brightness() * Sight.adapted(1, night);
        Sight.light(r, old, d, PIXEL, out);
        assertEquals(Sight.HALO, Math.min(Sight.HALO, Sight.SCATTER * old), 1e-12, "ночной ×1200: блик в полную силу");
        assertTrue(out[3] / d > Math.toRadians(70), "и шире экрана: " + Math.toDegrees(out[3] / d) + "°");
        double exposure = exposureNear(flame, d, night);
        double seen = flame.brightness() * exposure;
        Sight.light(r, seen, d, PIXEL, out);
        assertTrue(Math.min(Sight.HALO, Sight.SCATTER * seen) < 0.1, "блик факела у глаза — бледный: " + Sight.SCATTER * seen);
        assertTrue(seen > 1, "сам факел ярче белого: " + seen);
        // освещённость белой стены у глаза: по-старому — в сотни раз ярче белого (экран её не покажет)
        double wall = HeatPassAtEye.at(flame, d);
        assertTrue(wall * Sight.adapted(1, night) > 500, "по-старому — в сотни раз ярче белого: " + wall * Sight.adapted(1, night));
        // за 2 км огонь у глаза глаз почти не трогает
        assertTrue(exposureNear(flame, 2000, night) > 0.95 * Sight.adapted(1, night), "вдали — ночное привыкание");
    }

    /**
     * Привыкание глаза кадра с одним факелом в d блоках — тем путём, что в игре: факел кадра ({@link FarFlightView#lit}) —
     * в свет на мир этого кадра ({@link FarFlightView#heat}) и в свет у глаза следующего ({@link FarFlightView#flamesAtEye},
     * {@code HeatPass.Frame.eye}), привыкание — {@link Sight#exposure}.
     */
    private static double exposureNear(Flame flame, double d, double ambient) {
        try {
            FarFlightView.lit(d, 0, 0, flame, Vec3.ZERO);
            FarFlightView.heat(new ua.zentix.incoming.client.fx.layer.HeatPass.Frame(), 0);
            ua.zentix.incoming.client.fx.layer.HeatPass.Frame next = new ua.zentix.incoming.client.fx.layer.HeatPass.Frame();
            next.eye(FarFlightView.flamesAtEye());
            return Sight.exposure(ambient, next.atEye());
        } finally {
            FarFlightView.reset();
        }
    }

    /** Освещённость белой стены у глаза от факела в d блоках (линейная, полдень — 1): яркость × (r/d)². */
    private static final class HeatPassAtEye {
        static double at(Flame flame, double d) {
            double r = flame.lightRadius();
            return flame.brightness() * r * r / Math.max(d * d, r * r);
        }
    }

    /**
     * След ЗРК вблизи (жалоба 22.10: «плоская лента с прямыми краями, у камеры — плоскость через кадр»): трубка, крупная на
     * экране, рисуется клубами, как облака, а лента остаётся там, где трубка — линия в несколько пикселей. Ширина — у
     * ближней к глазу точки отрезка: отрезок, что проходит у самой камеры, — только клубы.
     */
    @Test
    void wideTubeOnScreenIsPuffsNotARibbon() {
        double width = 5;
        assertEquals(1, FarFlightView.puffShare(width, 30, PIXEL), 1e-12, "5 м в 30 блоках — клубы");
        assertEquals(1, FarFlightView.puffShare(width, 0.5, PIXEL), 1e-12, "глаз у трубки — клубы и вуаль, не плоскость");
        assertEquals(0, FarFlightView.puffShare(width, 2000, PIXEL), 1e-12, "за 2 км — линия: лента");
        // залп «Града» за 1,7 км (кадры VXC4, 22.10): след в 10 с — 11 м (5,7 px) — клубы, а не полоса из лент; в 1 с — 3 м
        // (1,6 px, сечение уже пикселя) — лента
        assertEquals(1, FarFlightView.puffShare(11, 1700, PIXEL), 1e-12, "след «Града» в 10 с за 1,7 км — клубы");
        assertEquals(0, FarFlightView.puffShare(3, 1700, PIXEL), 1e-12, "след в 1 с за 1,7 км — лента");
        double prev = 1;
        for (double gap = 10; gap < 3000; gap *= 1.05) {
            double s = FarFlightView.puffShare(width, gap, PIXEL);
            assertTrue(s <= prev + 1e-12, "дальше — меньше клубов, без скачков");
            assertTrue(prev - s < 0.2, "переход плавный: " + gap);
            prev = s;
        }
    }

    /**
     * Дым следа — с конца яркого пламени ({@link ua.zentix.incoming.client.fx.plume.TrailModel#emerged}, {@link Flame#flame}):
     * у сопла вещество — само пламя, за концом догорания проявляется дым, к полуторной длине — весь; без пламени — дым
     * сразу. Остывающий хвост до точки Дрейпера ({@link Flame#length}) на экране не светит: дым там уже есть, иначе
     * между огнём и дымом был бы разрыв в десятки метров (жалоба ревью кадров 22.10). Путь — тот, что снаряд пролетел
     * с рождения точки: у ракеты ЗРК (сход на 16 м/с, ~180 м/с²) точка, рождённая на сходе, через 0,5 с уже в ~30 м.
     */
    @Test
    void trailSmokeStartsWhereTheFlameEnds() {
        Flame f = far(WeaponType.MISSILE).stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        double flame = f.flame();
        assertTrue(f.tailLuminance() < 0.01 * f.flameLuminance(), "хвост за пламенем не светит: " + f.tailLuminance());
        assertEquals(0, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(0, flame), 1e-12, "у сопла — пламя");
        assertEquals(0, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(flame, flame), 1e-12);
        assertEquals(1, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(1.5 * flame, flame), 1e-12, "за пламенем — дым");
        assertTrue(1.5 * flame < 0.5 * f.length(), "дым — задолго до точки Дрейпера: " + flame + " против " + f.length());
        assertEquals(1, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(0, 0), 1e-12, "без пламени — дым сразу");
        double prev = 0;
        for (double x = 0; x <= 3 * flame; x += 0.05) {
            double e = ua.zentix.incoming.client.fx.plume.TrailModel.emerged(x, flame);
            assertTrue(e >= prev && e - prev < 0.05, "плавно: " + x);
            prev = e;
        }
        // путь разгоняющейся ракеты ЗРК за 0,5 с: v₀·t + a·t²/2
        Flame sam = ua.zentix.incoming.client.fx.plume.FlameModel.at(ClientWeaponSpec.SAM_BOOST, 0);
        double path = 16 * 0.5 + 0.5 * 180 * 0.25;
        assertEquals(1, ua.zentix.incoming.client.fx.plume.TrailModel.emerged(path, sam.flame()), 1e-12, "через 0,5 с после схода — дым");
    }

    @Test
    void cruiseMissileEngineDoesNotGlow() {
        // выхлоп ТРДД — ~700 K без частиц: ни днём, ни ночью
        assertNull(far(WeaponType.MISSILE).stage(FlightPhase.CRUISE).flame(Air.SEA));
    }

    // ---------------------------------------------------------------- точки шлейфа по пути

    private static S2C.FarFlight packet(WeaponType weapon, long tick, FlightPhase phase) {
        return new S2C.FarFlight(ID, weapon.id(), false, false, true, new Vec3(tick * 3.0, 80 + tick, 0), new Vec3(3, 1, 0), 0, 0, 0,
                phase.ordinal(), 0, new Vec3(9000, 80, 0));
    }

    /** Путь по пакетам раз в 2 тика: фаза — по тикам. */
    private static FlightTrack track(WeaponType weapon, long from, long to, java.util.function.LongFunction<FlightPhase> phase) {
        FlightTrack t = new FlightTrack(ID, weapon, false);
        for (long tick = from; tick <= to; tick += 2) t.record(tick, packet(weapon, tick, phase.apply(tick)));
        return t;
    }

    private record Point(long serial, long tick, Trail style, long prev, double x, boolean last) {}

    private static List<Point> points(TrailPoints p) {
        List<Point> out = new ArrayList<>();
        for (long s = p.first(); s < p.end(); s++) {
            int i = TrailPoints.index(s);
            out.add(new Point(s, p.birth[i], p.style[i], p.prev[i], p.x[i], p.last[i]));
        }
        return out;
    }

    @Test
    void rocketTrailRunsWhileMotorBurnsAndEndsAtBurnout() {
        FarLook look = far(WeaponType.ROCKET);
        Trail trail = look.stage(FlightPhase.BOOST).trail();
        FlightTrack t = track(WeaponType.ROCKET, 10, 90, tick -> tick <= 50 ? FlightPhase.BOOST : FlightPhase.CRUISE);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 90, p);
        List<Point> pts = points(p);
        // каждые 2 тика с 10 по 50, и точка выгорания в тике 51 (он уже в фазе следующего пакета)
        assertEquals(22, pts.size());
        for (int i = 0; i < pts.size(); i++) {
            Point pt = pts.get(i);
            assertSame(trail, pt.style);
            assertEquals(i == 21 ? 51 : 10 + 2 * i, pt.tick);
            assertEquals(pt.tick * 3.0, pt.x, 1e-9, "место — по пути");
            assertEquals(i == 0 ? TrailPoints.NONE : pts.get(i - 1).serial, pt.prev, "одна лента");
            assertEquals(i == 21, pt.last, "лента сходит на нет в точке выгорания, и только там");
        }
    }

    @Test
    void boosterTrailEndsWhereThePistonLeavesNone() {
        // дым ускорителя — лентой до его выгорания; выхлоп поршня на этой скорости не виден — после него точек нет
        FarLook look = far(WeaponType.DRONE);
        FlightTrack t = track(WeaponType.DRONE, 0, 40, tick -> tick <= 20 ? FlightPhase.BOOST : FlightPhase.CRUISE);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 40, p);
        List<Point> pts = points(p);
        Trail booster = look.stage(FlightPhase.BOOST).trail();
        assertFalse(pts.isEmpty());
        for (Point pt : pts) assertSame(booster, pt.style);
        assertTrue(pts.getLast().last, "лента ускорителя сходит на нет");
        assertTrue(pts.getLast().tick <= 22);
    }

    @Test
    void ribbonToneMatchesSmokeTextures() throws java.io.IOException {
        // клубы объёма лежат на ленте: с тем же средним тоном они её неровности, а не тёмные бусины
        double light = 0, cover = 0;
        for (int k = 0; k < 16; k++) {
            String path = String.format(java.util.Locale.ROOT, "/assets/incoming/textures/fx/particle/smoke_%02d.png", k);
            try (java.io.InputStream in = FarFlightViewTest.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int argb = img.getRGB(x, y);
                        double a = (argb >>> 24) / 255.0;
                        light += a * ((argb >> 16 & 255) + (argb >> 8 & 255) + (argb & 255)) / (3 * 255.0);
                        cover += a;
                    }
                }
            }
        }
        assertEquals(FarFlightView.TONE, light / cover, 0.03);
    }

    @Test
    void everyTrailNozzleFitsTheFrameCache() {
        for (WeaponType w : WeaponType.values()) {
            for (boolean payload : new boolean[]{false, true}) {
                ClientWeaponSpec.ClientAirframe a = ClientWeaponSpec.of(w).airframe(payload);
                for (boolean booster : new boolean[]{false, true}) {
                    assertTrue(a.trailNozzles(booster).size() <= FarFlightView.MAX_NOZZLES, w + " " + payload + " " + booster);
                }
            }
        }
    }

    @Test
    void rocketTrailStartsAtTheMuzzleNotInTheTube() {
        // поджиг — в трубе: огонь без следа (лента шире трубы торчала бы из неё), след — с первого тика разгона
        FlightTrack t = track(WeaponType.ROCKET, 0, 20, tick -> tick < 6 ? FlightPhase.IGNITION : FlightPhase.BOOST);
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 20, p);
        List<Point> pts = points(p);
        long boost = 0;
        while (t.phase(boost) != FlightPhase.BOOST.ordinal()) boost++;
        assertEquals(boost, pts.getFirst().tick);
        assertEquals(TrailPoints.NONE, pts.getFirst().prev);
    }

    /** Путь B-2 на высоте y мира. */
    private static FlightTrack bomber(double y) {
        FlightTrack t = new FlightTrack(ID, WeaponType.BUNKER, true);
        for (long tick = 0; tick <= 40; tick += 2) {
            t.record(tick, new S2C.FarFlight(ID, WeaponType.BUNKER.id(), true, false, true, new Vec3(0, y, tick * 12.0), new Vec3(0, 0, 12), 0, 0, 0,
                    FlightPhase.CRUISE.ordinal(), 0, new Vec3(0, 60, 9000)));
        }
        return t;
    }

    @Test
    void bomberLeavesNoContrailWhereAirIsWarm() {
        // сегодняшние 300 блоков — +14 °C: по Шмидту–Эпплману инверсии нет
        TrailPoints p = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(bomber(300), 1), 40, p);
        assertTrue(p.isEmpty());
    }

    @Test
    void bomberLeavesAContrailFromEachEngine() {
        // на 11 км во влажном воздухе инверсия встаёт у каждого из четырёх двигателей
        Air before = FarFlightView.air;
        FarFlightView.air = new Air(new Wind(0, 10), Stability.D, 1);
        TrailPoints p = new TrailPoints();
        try {
            FarFlightView.sample(new FarFlightView.Far(bomber(Air.SEA + 11_000), 1), 40, p);
        } finally {
            FarFlightView.air = before;
        }
        List<ClientWeaponSpec.At> engines = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(false).engines();
        List<Point> pts = points(p);
        assertEquals(engines.size() * 11, pts.size(), "у каждого сопла точка раз в 4 тика");
        for (int i = 0; i < pts.size(); i++) {
            Point pt = pts.get(i);
            // нос по +Z (рысканье 0): влево — по +X
            assertEquals(engines.get(i % engines.size()).x(), pt.x, 1e-4, "лента от своего сопла");
            assertEquals(i < engines.size() ? TrailPoints.NONE : pts.get(i - engines.size()).serial, pt.prev, "и своя");
        }
    }

    @Test
    void samplingInStepsGivesTheSameTrail() {
        FlightTrack t = track(WeaponType.NUKE, 0, 200, tick -> tick < 40 ? FlightPhase.IGNITION : FlightPhase.BOOST);
        TrailPoints once = new TrailPoints(), steps = new TrailPoints();
        FarFlightView.sample(new FarFlightView.Far(t, 1), 200, once);
        FarFlightView.Far f = new FarFlightView.Far(t, 1);
        for (long h = 0; h <= 200; h += 3) FarFlightView.sample(f, h, steps);
        FarFlightView.sample(f, 200, steps);
        assertEquals(points(once), points(steps));
    }

    @Test
    void modelReplacesTheDotBetweenTwoAndThreePixels() {
        assertEquals(0, FarFlightView.modelShare(0.5));
        assertEquals(0, FarFlightView.modelShare(FarModels.DOT_BELOW));
        assertEquals(0.5, FarFlightView.modelShare((FarModels.DOT_BELOW + FarModels.MODEL_FROM) / 2), 1e-12);
        assertEquals(1, FarFlightView.modelShare(FarModels.MODEL_FROM));
        assertEquals(1, FarFlightView.modelShare(300));
        double prev = 0;
        for (double px = FarModels.DOT_BELOW; px <= FarModels.MODEL_FROM; px += 0.01) {
            double k = FarFlightView.modelShare(px);
            assertTrue(k >= prev, "переход без скачков назад: " + px);
            prev = k;
        }
        // упрощённые копии — с запасом против дрожания на границе
        assertTrue(FarModels.COARSE_BELOW < FarModels.FULL_ABOVE);
        assertTrue(FarModels.MODEL_FROM < FarModels.COARSE_BELOW);
    }

    @Test
    void modelOutsideTheScreenIsNotDrawn() {
        // экран — конус в 30° от середины до угла, взгляд на север (−Z)
        FarView view = new FarView(Vec3.ZERO, new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0), 0, 1000, PIXEL,
                Math.toRadians(30), 1, 1, new Vector3f(1, 1, 1), 20000, 192, 192, 0, Sight.Air.at(0, 20000));
        assertTrue(FarFlightView.inFrame(view, 0, 0, -500, 500, 2));
        assertTrue(FarFlightView.inFrame(view, at(29)[0], 0, at(29)[1], 500, 2));
        assertFalse(FarFlightView.inFrame(view, at(35)[0], 0, at(35)[1], 500, 2), "в 35° мелкая модель за краем");
        assertTrue(FarFlightView.inFrame(view, at(35)[0], 0, at(35)[1], 500, 60), "крыло B-2 в 60 блоков заходит в кадр краем");
        assertFalse(FarFlightView.inFrame(view, 0, 0, 500, 500, 2), "за спиной");
    }

    @Test
    void flameIsOneAcrossTheRenderDistanceEdge() {
        // сущность появляется на краю прорисовки: ядро света факела там уже всё, ближе его сменяет струя той же долей,
        // путь по пакетам — всегда весь свет; блик и вуаль — у обоих (FarFlightView.collect)
        double world = 192;
        FarView view = new FarView(Vec3.ZERO, new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0), 0, 1000, PIXEL,
                Math.toRadians(30), 1, 1, new Vector3f(1, 1, 1), 20000, world, 192, 0, Sight.Air.at(0, 20000));
        for (double d = 0; d <= 2 * world; d += 4) {
            double jet = 1 - FarView.farShare(d, world), core = FarFlightView.flameCore(true, view, d);
            assertEquals(1, jet + core, 1e-12, "на " + d + ": струя и ядро вместе — один факел");
            assertEquals(1, FarFlightView.flameCore(false, view, d), 1e-12, "по пакетам — весь свет");
            if (d <= 0.8 * world) assertEquals(0, core, 1e-12, "вблизи ядро — струя");
            if (d >= world) assertEquals(1, core, 1e-12, "на краю — как по пакетам");
        }
    }

    /** Точка в 500 блоках под углом deg от взгляда на север: x и z. */
    private static double[] at(double deg) {
        return new double[]{500 * Math.sin(Math.toRadians(deg)), -500 * Math.cos(Math.toRadians(deg))};
    }
}
