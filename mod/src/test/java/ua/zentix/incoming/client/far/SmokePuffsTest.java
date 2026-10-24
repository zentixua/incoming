package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Цвет толстого слоя сажи — тёмно-серый, а не чёрная дыра; проекция плоского клуба; трубка из клубов. */
class SmokePuffsTest {
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
        org.junit.jupiter.api.Assertions.assertTrue(SmokePuffs.lies(15, 0.5, -18, 53));
        org.junit.jupiter.api.Assertions.assertFalse(SmokePuffs.lies(15, 0.5, -1, 53), "с уровня глаза — полоса");
        org.junit.jupiter.api.Assertions.assertFalse(SmokePuffs.lies(5, 5, -18, 53), "круглый");
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
    /** Transparent padding of a baked club must not turn nearby, visibly structured smoke into a uniform veil. */
    @Test
    void eyeEntryUsesTheVisibleGaussianRatherThanTheBakedQuad() {
        {
            double edge = SmokePuffs.CORE_EDGE, sh = 10, sv = 2;
            assertEquals(0.5, Math.exp(-edge * edge / 2), 1e-12);
            assertEquals(1, SmokePuffs.outside(1.01 * edge * sh, 0, 0, sh, sv), 1e-12);
            assertEquals(1, SmokePuffs.outside(0, 1.01 * edge * sv, 0, sh, sv), 1e-12);
            assertEquals(0, SmokePuffs.outside(0.49 * edge * sh, 0, 0, sh, sv), 1e-12);
        }
        assertEquals(1, SmokePuffs.outside(20, 0, 0, 10, 10), 1e-12,
                "eye at 2 sigma sees clubs: large transparent quad padding is irrelevant");
        assertEquals(1, SmokePuffs.outside(0, 5, 0, 100, 2), 1e-12,
                "eye above a thin ground layer is outside despite its large horizontal radius");
    }
}
