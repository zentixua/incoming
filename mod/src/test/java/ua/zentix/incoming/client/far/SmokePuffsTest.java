package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Квадрат клуба — видимый край гаусса; цвет толстого слоя сажи — тёмно-серый, а не чёрная дыра. */
class SmokePuffsTest {
    /** Край — где толща падает до 0,05: у густого ~3,7σ; у тонкого (τ₀ меньше порога) — не NaN, а наименьший край. */
    @Test
    void edgeOfThinAndThickClouds() {
        assertEquals(Math.sqrt(2 * Math.log(42 / SmokePuffs.EDGE_TAU)), SmokePuffs.edge(42), 1e-9);
        for (double tau : new double[]{0, 0.004, 0.01, 0.049, 0.05}) {
            double e = SmokePuffs.edge(tau);
            assertTrue(Double.isFinite(e) && e == 1.2, "τ₀ " + tau + ": край " + e);
        }
    }

    /** Толстый слой сажи (ω' ≈ 0,2) отражает ~4 % — на экране ~0,24, а не 0: у Эддингтона отражение выходило отрицательным. */
    @Test
    void sootIsDarkGreyNotBlack() {
        float soot = SmokePuffs.thick(1, 0, 0, 0.9f);
        assertTrue(soot > 0.15 && soot < 0.35, "сажа " + soot);
    }

    /**
     * Тень карт клуба — в степени затухания света смеси против вещества запекания: белый дым окиси алюминия и капли почти
     * без тени (иначе белое облако старта ЗРК выходило тёмно-серым шаром), сажа — темнее запечённого, смесь как у
     * запекания — как есть; степень помещается в байт вершины ({@code FxQuads.SHADE_MAX}).
     */
    @Test
    void mapShadeFollowsAlbedo() {
        double white = SmokePuffs.shade(0, 0, 1, 0.9f) / SmokePuffs.BAKED_K, soot = SmokePuffs.shade(1, 0, 0, 0.9f) / SmokePuffs.BAKED_K;
        assertTrue(white < 0.05, "капли " + white);
        assertTrue(soot > 1 && soot < ua.zentix.incoming.client.fx.layer.FxQuads.SHADE_MAX, "сажа " + soot);
        assertEquals(1, SmokePuffs.twoStream(0.46, 0.355) / SmokePuffs.BAKED_K, 0.01, "вещество запекания");
        // брызги запечены веществом с альбедо 0,99, капли облака белее (0,9999): тень карт брызг у них слабее, у сажи — сильнее
        assertTrue(SmokePuffs.shade(0, 0, 1, 0.9f) / SmokePuffs.SPRAY_K < 1, "капли на картах брызг");
        assertTrue(SmokePuffs.shade(1, 0, 0, 0.9f) / SmokePuffs.SPRAY_K > 1, "сажа на картах брызг");
    }

    /** Альбедо и асимметрия запекания — те же, что в скрипте запекания. */
    @Test
    void bakedAlbedoMatchesBakeScript() throws java.io.IOException {
        String bake = java.nio.file.Files.readString(java.nio.file.Path.of("..", "tools", "bake_fx", "bake.py"));
        assertTrue(bake.contains("PUFF_ALBEDO, PUFF_G = " + SmokePuffs.BAKED_W + ", " + SmokePuffs.BAKED_G),
                "tools/bake_fx/bake.py: PUFF_ALBEDO, PUFF_G не " + SmokePuffs.BAKED_W + ", " + SmokePuffs.BAKED_G);
        assertTrue(bake.contains("SPRAY_ALBEDO, SPRAY_G = " + SmokePuffs.SPRAY_W + ", " + SmokePuffs.SPRAY_G),
                "tools/bake_fx/bake.py: SPRAY_ALBEDO, SPRAY_G не " + SmokePuffs.SPRAY_W + ", " + SmokePuffs.SPRAY_G);
    }

    /**
     * Плоский клуб (слой пыли у земли) проецируется на экран по углу взгляда: сверху — круг шириной σh, сбоку — полоса
     * высотой σv, между — √(σv²cos²e + σh²sin²e); у круглого — всегда σ. Квадрат к камере высотой σv при взгляде сверху
     * лёг бы чертой поперёк кадра (кадры F2a: тёмные полосы над землёй вокруг взрыва).
     */
    @Test
    void flatClubProjectsByViewAngle() {
        double sh = 20, sv = 0.5;
        org.junit.jupiter.api.Assertions.assertEquals(sh, SmokePuffs.projected(sh, sv, 0, -50, 0), 1e-9, "сверху");
        org.junit.jupiter.api.Assertions.assertEquals(sv, SmokePuffs.projected(sh, sv, 50, 0, 0), 1e-9, "сбоку");
        double e = Math.toRadians(20), d = 50;
        double mid = SmokePuffs.projected(sh, sv, d * Math.cos(e), -d * Math.sin(e), 0);
        org.junit.jupiter.api.Assertions.assertEquals(Math.sqrt(sv * sv * Math.pow(Math.cos(e), 2) + sh * sh * Math.pow(Math.sin(e), 2)), mid, 1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(mid > 10 * sv, "под 20° — уже не черта: " + mid);
        org.junit.jupiter.api.Assertions.assertEquals(3, SmokePuffs.projected(3, 3, 10, -7, 2), 1e-9, "круглый");
    }

    /**
     * Толща по лучу из глаза через гаусс: у шара — прежняя формула вуали (τ₀·e^(−b²/2σ²)·½(1 + erf(a/√2σ))); у плоского
     * слоя (σh 40, σv 1) глаз в 18 м над ним видит толщу сверху — тонкую, а не толщу вдоль слоя (финальные кадры F2a:
     * вуаль слоя пыли закрывала весь кадр у подстанции и у бомбы в 150 м).
     */
    @Test
    void sightDepthThroughAFlatLayer() {
        double ext = 1000, s = 5;
        double tau0 = ext / (2 * Math.PI * s * s);
        // глаз в середине шара, смотрит вбок: половина толщи поперёк
        org.junit.jupiter.api.Assertions.assertEquals(0.5 * tau0, SmokePuffs.sightDepth(0, 0, 0, s, s, ext, 1, 0, 0), 1e-9);
        // шар впереди на 10 (a = 10), смещён вбок на 3
        double a = 10, b = 3, old = tau0 * Math.exp(-0.5 * b * b / (s * s)) * 0.5 * (1 + ua.zentix.incoming.client.fx.smoke.SmokeGrid.erf(a / (Math.sqrt(2) * s)));
        org.junit.jupiter.api.Assertions.assertEquals(old, SmokePuffs.sightDepth(a, b, 0, s, s, ext, 1, 0, 0), 1e-9);
        // плоский слой под глазом: вниз — толща сверху ext/(2πσh²)·(вся, слой целиком под глазом), вдоль слоя — огромная
        double down = SmokePuffs.sightDepth(0, -18, 0, 40, 1, ext, 0, -1, 0);
        org.junit.jupiter.api.Assertions.assertEquals(ext / (2 * Math.PI * 40 * 40), down, 1e-6);
        double level = SmokePuffs.sightDepth(0, -18, 0, 40, 1, ext, 1, 0, 0);
        org.junit.jupiter.api.Assertions.assertTrue(level < 1e-6, "взгляд вдоль, на 18 σ выше слоя: " + level);
    }

    /**
     * Слой пыли у земли (σh 15, σv 0,5) из глаза в 18 м над ним и в 50 м — лежит на горизонтали (квадрат лицом к камере
     * стоял бы над землёй «плитой» в 0,1 с, кадры F2a); тот же слой с уровня глаза и круглый клуб — лицом к камере.
     */
    @Test
    void flatClubLiesWhenSeenFromAbove() {
        assertEquals(1, SmokePuffs.lyingShare(15, 0.5, -18, 53));
        assertEquals(0, SmokePuffs.lyingShare(15, 0.5, -1, 53), "с уровня глаза — полоса");
        assertEquals(0, SmokePuffs.lyingShare(5, 5, -18, 53), "круглый");
    }

    /**
     * Трубка следа клубами ({@link SmokePuffs#tube}): вещество отрезка — целиком (сумма долей мест — 1 на любом уровне),
     * клубы не дальше {@link SmokePuffs#SEGMENT_STEP}·σ, пока уровень не на пределе, и вещество каждого места меняется
     * без скачков, когда σ растёт и уровень меняется (клубы не перескакивают). Толща поперёк середины трубки из гауссов —
     * толща трубки по закону следа, e/(√(2π)·σ).
     */
    @Test
    void tubeKeepsItsMatterAndSpacing() {
        double len = 28;
        double[] last = null;
        for (double sigma = 0.3; sigma < 40; sigma *= 1.01) {
            double level = SmokePuffs.tubeLevel(len, sigma), total = SmokePuffs.tubeTotal(level);
            int n = 1 << SmokePuffs.tubeDepth(level);
            double sum = 0;
            for (int j = 0; j < n; j++) sum += SmokePuffs.tubeWeight(j, level);
            assertEquals(total, sum, 1e-9, "вещество целиком при σ " + sigma);
            if (level < SmokePuffs.TUBE_LEVELS) assertTrue(len / n <= SmokePuffs.SEGMENT_STEP * sigma * 1.0001, "шаг при σ " + sigma);
            // вещество мест на самом мелком уровне
            int fine = 1 << SmokePuffs.TUBE_LEVELS;
            double[] mass = new double[fine];
            for (int j = 0; j < n; j++) mass[j * (fine / n)] = SmokePuffs.tubeWeight(j, level) / total;
            if (last != null) {
                for (int j = 0; j < fine; j++) assertTrue(Math.abs(mass[j] - last[j]) < 0.05, "место " + j + " при σ " + sigma);
            }
            last = mass;
        }
        // толща поперёк середины: гауссы через шаг по оси
        double sigma = 0.5, e = 0.4, level = SmokePuffs.tubeLevel(len, sigma), total = SmokePuffs.tubeTotal(level);
        int n = 1 << SmokePuffs.tubeDepth(level);
        double tau = 0, at = len / 2;
        for (int j = 0; j < n; j++) {
            double x = len * j / n - at, k = e * len * SmokePuffs.tubeWeight(j, level) / total;
            tau += k / (2 * Math.PI * sigma * sigma) * Math.exp(-x * x / (2 * sigma * sigma));
        }
        assertEquals(e / (Math.sqrt(2 * Math.PI) * sigma), tau, 0.05 * tau, "толща трубки поперёк");
    }

    /**
     * Мягкость вуали к миру ({@link SmokePuffs.Veil#soft}): вершина тает к миру линейно ({@code fx.fsh}: (мир − 2 блока) /
     * мягкость), а доля её непрозрачности перед миром в S — (1 − e^(−τ(S)))/(1 − e^(−τ)), τ(S) — толща по лучу до S (здесь —
     * численно по плотности гауссов). На 3…120 м спуск не дальше 0,4 от доли и ближе к ней, чем прежняя вуаль без мягкости
     * (закрывала всё дальше 2 блоков целиком): глаз смотрит вдоль густого следа ЗРК (σ 5 м, толща поперёк 2,7: половина
     * непрозрачности — уже в 3 м), далёкий густой клуб в 60 м (дом перед ним в 20 м берёт меньше 0,2 вуали) и тонкая
     * дымка. ⚠ Спуск от 2 блоков прямой: у вещества от самого глаза и у узкого далёкого клуба точнее ~0,35 он не бывает.
     */
    @Test
    void veilFadesToTheWorldWhereItsMatterIs() {
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(0, 100, 0);
        FarView view = SmokeFillTest.view(eye, new org.joml.Vector3f(0, 0, 1));
        double sigma = 5, tube = 2.7 * Math.sqrt(2 * Math.PI) * sigma;
        java.util.List<double[]> cases = new java.util.ArrayList<>();
        // трубка вдоль z через глаз — гауссами через 2 м
        java.util.List<double[]> trail = new java.util.ArrayList<>();
        for (double z = -150; z <= 150; z += 2) trail.add(new double[]{0, 0, z, sigma, sigma, tube * 2});
        // далёкий густой клуб: толща через середину ~3
        java.util.List<double[]> far = java.util.List.of(new double[]{0, 0, 60, 8, 8, 3 * 2 * Math.PI * 64});
        // тонкая дымка: широкий гаусс вокруг глаза, толща через середину ~0,2
        java.util.List<double[]> thin = java.util.List.of(new double[]{0, 0, 30, 40, 20, 0.2 * 2 * Math.PI * 40 * 20});
        for (java.util.List<double[]> gs : java.util.List.of(trail, far, thin)) {
            double[] packed = new double[gs.size() * SmokePuffs.VEIL_FIELDS];
            for (int c = 0; c < gs.size(); c++) {
                double[] g = gs.get(c);
                int o = c * SmokePuffs.VEIL_FIELDS;
                System.arraycopy(g, 0, packed, o, 6);
                packed[o + 6] = packed[o + 7] = packed[o + 8] = 0.8;
                packed[o + 9] = 1;
            }
            SmokePuffs.Veil veil = new SmokePuffs.Veil();
            veil.fill(view, packed, gs.size());
            int k = SmokePuffs.Veil.index(SmokePuffs.Veil.N / 2, SmokePuffs.Veil.N / 2);
            double soft = veil.soft[k], total = veil.tau[k], worst = 0, at = 0, old = 0, house = 0;
            // толща по лучу вперёд (z) — по плотности гауссов шагом 0,05 м; сверка — на каждом метре с 3 до 120
            double tau = 0;
            for (int step = 0; step < 2400; step++) {
                double z = (step + 0.5) * 0.05;
                for (double[] g : gs) {
                    double dx = -g[0], dy = -g[1], dz = z - g[2];
                    tau += 0.05 * g[5] / (Math.pow(2 * Math.PI, 1.5) * g[3] * g[3] * g[4])
                            * Math.exp(-0.5 * ((dx * dx + dz * dz) / (g[3] * g[3]) + dy * dy / (g[4] * g[4])));
                }
                double s = (step + 1) * 0.05;
                if (s < 3 || (step + 1) % 20 != 0) continue;
                double share = -Math.expm1(-tau) / -Math.expm1(-total), ramp = Math.clamp((s - SmokePuffs.VEIL_AT) / soft, 0, 1);
                old = Math.max(old, 1 - share);
                if (Math.abs(s - 20) < 1e-6) house = ramp;
                if (Math.abs(share - ramp) > worst) {
                    worst = Math.abs(share - ramp);
                    at = s;
                }
            }
            String got = String.format(java.util.Locale.ROOT, "толща %.2f, мягкость %.1f: спуск дальше доли перед миром на %.2f в %.0f м (без мягкости — %.2f), в 20 м — %.2f",
                    total, soft, worst, at, old, house);
            System.out.println("VEIL " + got);
            assertTrue(worst <= 0.4 && worst < old, got);
            if (gs == far) assertTrue(house < 0.2, got);
        }
    }

    /**
     * Ревью #460: места одной трубки не сливаются ни на каком уровне. Группа хранит одну трубку держателя, и группы A1+B1
     * (держит A) и A2+B2 (держит B) на следующем уровне сливались — в одном клубе по два места каждой трубки. Состав групп
     * живёт между уровнями ({@link SmokePuffs.TubeGroups}).
     */
    @Test
    void tubeGroupsKeepEveryTubeAcrossLevels() {
        SmokePuffs.TubeGroups g = new SmokePuffs.TubeGroups();
        g.reset();
        long a = 1, b = 2, c = 3;
        g.add(0, a);
        g.add(1, b);
        g.add(2, a);
        g.add(3, b);
        g.add(4, c);
        assertTrue(g.disjoint(0, 1) && !g.disjoint(0, 2), "уровень 0: A1 с B1 — да, A1 с A2 — нет");
        g.join(0, 1);
        g.join(3, 2);
        assertTrue(!g.disjoint(0, 3) && !g.disjoint(3, 0), "уровень 1: A1+B1 и B2+A2 — одни и те же трубки");
        assertTrue(g.disjoint(0, 4), "уровень 1: A1+B1 и C — разные трубки");
        g.join(0, 4);
        assertTrue(!g.disjoint(3, 0), "уровень 2: группа A1+B1+C и B2+A2");
    }
}
