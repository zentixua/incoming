package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastWallTest {
    /** 15 кт у земли на высоте 64, без ветра, в масштабе 1 блок = 4 м. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 0.25f, false, Detonation.OLD_WAVE);

    /**
     * Голова — пыль сразу за фронтом в игре (сам фронт не виден), клин — до места, где положительная фаза на месте уже
     * кончилась (приход фронта в игре и фаза там — момент t); голова с переходом к пелене — не в огненном шаре на земле
     * (пока фронт в шаре — головы нет, у шара клин короче, и пыль в его конце моложе фазы); густая до ~1 psi, пока ветер
     * волны поднимает пыль, и сходит на нет там, где поднимать нечего (у 15 кт между 0,6 и 0,3 psi), — отрывается от фронта.
     */
    @Test
    void headRidesTheFront() {
        BlastWall w = new BlastWall(GROUND, false);
        double scale = 20 * GROUND.scale(), firstEnd = GROUND.arrivalTicks(0) / scale + column(0).positive();
        int ends = 0, inBall = 0;
        for (double t = 0.05; t <= 20; t += 0.05) {
            assertEquals(w.shock(t), w.head(t), 1e-9, t + " с");
            double tail = w.tail(t), ball = w.underBall(t);
            double r = FireballModel.radius(t, GROUND.yieldKt(), true), y = FireballModel.centreHeight(t, 0, GROUND.yieldKt());
            assertEquals(Math.sqrt(Math.max(0, r * r - y * y)), ball, 1e-9, t + " с: край шара на земле");
            double len = w.shock(t) - tail;
            if (ball > 0) len = Math.min(len, (w.shock(t) - ball) / BlastWall.HANDOFF_TO);
            assertEquals(Math.max(1e-3, len), w.length(t), 1e-9, t + " с: клин — от фронта до конца фазы, переход к пелене — до края шара");
            if (w.wall(t) != BlastWall.Wall.NONE) {
                // верх — слоя там, где кончается клин (у края шара — метры, а не слой у эпицентра в 18 км)
                assertTrue(GROUND.metres(w.wall(t).height()) < 120, t + " с: верх клина " + GROUND.metres(w.wall(t).height()) + " м");
                assertTrue(w.head(t) - BlastWall.HANDOFF_TO * w.length(t) >= ball - 1e-6, t + " с: переход головы к пелене — в шаре");
                if (ball > 0 && w.backAge(t) < 1) inBall++;
            }
            if (t < firstEnd) {
                assertEquals(0, tail, 1e-12, t + " с: фаза ещё нигде не кончилась");
                continue;
            }
            double end = GROUND.arrivalTicks(GROUND.slantOf(GROUND.blocks(tail))) / scale + column(tail).positive();
            assertEquals(t, end, 0.01, t + " с: в конце клина (" + tail + " м) фаза кончилась в " + end + " с");
            ends++;
        }
        assertTrue(ends > 300, "проверок конца клина " + ends);
        assertTrue(inBall > 3, "клин короче фазы у края шара — " + inBall + " проверок");
        // пока фронт в огненном шаре, головы нет; вышел — пыль сразу за ним
        assertEquals(BlastWall.Wall.NONE, w.wall(0.05), "голова внутри шара");
        assertTrue(w.shock(0.05) < w.underBall(0.05));
        assertEquals(0, w.reach(0.05));
        assertTrue(w.wall(0.3).density() > 0, "у самого фронта пыли нет");
        assertEquals(0, w.underBall(6), "шар всплыл — земли не касается");
        // густая (не прозрачнее самой густой пыли пелены) до ~1 psi: у 1,5 psi — с запасом, у 0,6 — уже реже; сходит на нет
        // между 0,6 и 0,3 psi
        double[] s = new double[3];
        double t = 0.3;
        t = until(w, t, BlastModel.kpa(1.5));
        double dense = w.sigmas(t, s) / PallField.SIGMA_DENSE;
        assertTrue(dense > 1.5, "у 1,5 psi голова лишь " + dense + " густоты пелены");
        assertEquals(1, w.wall(t).density(), 1e-9);
        double thin = t;
        while (w.wall(thin).density() >= 1) thin += 0.01;
        double atThin = BlastModel.psi(GROUND.groundOverpressureKpa(GROUND.blocks(w.head(thin))));
        assertTrue(atThin < 1.5 && atThin > 0.6, "густая до " + atThin + " psi");
        t = until(w, thin, BlastModel.kpa(0.6));
        assertTrue(w.wall(t).density() > 0 && w.wall(t).density() < 1, "у 0,6 psi голова " + w.wall(t).density());
        t = until(w, t, BlastModel.kpa(0.32));
        assertEquals(BlastWall.Wall.NONE, w.wall(t), "у 0,3 psi голова ещё есть");
        assertEquals(0, w.reach(t));
    }

    /** Первый миг с {@code from} (шаг 0,01 с), когда голова дошла до давления {@code kpa} на земле. */
    private static double until(BlastWall w, double from, double kpa) {
        double range = GROUND.metres(GROUND.groundRange(kpa)), t = from;
        while (w.head(t) < range) t += 0.01;
        return t;
    }

    /**
     * Верх головы — пыльный пограничный слой за свежим фронтом (Kuhl и др. 1992, ур. 17; Mirels): в конце клина
     * h = c·R^(1/6)·x^(5/6), x — путь фронта модели за этим местом, пока пыль там не старше своей фазы (у Kuhl и Mirels фронт
     * постоянной скорости, x = U_s·τ). За свежим фронтом слой начинается с нулевой толщины и растёт с путём: у эпицентра —
     * метры, а не километры. Логарифмическая сетка времени с 0,1 мс до 30 с, 1 кт, 15 кт и 1 Мт, наземные и низкие
     * воздушные: верх не выше закона, а где клин доходит до конца фазы — сам закон; всплесков нет.
     */
    @Test
    void headTopIsTheBoundaryLayer() {
        // мощность, кт; высота подрыва, м; масштаб
        double[][] bursts = {{1, 0, 1}, {1, 28, 1}, {15, 0, 1}, {15, 0, 0.25}, {15, 83, 1}, {1000, 0, 1}, {1000, 80, 1}, {1000, 333, 1}};
        for (double[] b : bursts) {
            double yield = b[0], hob = b[1];
            Detonation d = new Detonation(1, new Vec3(0, 64 + hob * b[2], 0), 64, yield, hob == 0, 0, 0, 0, 20_000, 7, (float) b[2], false, 1);
            BlastWall w = new BlastWall(d, false);
            String burst = yield + " кт на " + hob + " м, масштаб " + b[2] + ", ";
            double[] tops = new double[111];
            int heads = 0, ends = 0;
            for (int k = 0; k < tops.length; k++) {
                double t = 1e-4 * Math.pow(10, 0.05 * k);
                BlastWall.Wall wall = w.wall(t);
                tops[k] = Double.NaN;
                if (wall == BlastWall.Wall.NONE) continue;
                double top = d.metres(wall.height()), back = Math.max(0, w.head(t) - w.length(t));
                double age = Math.min(t - HobBlast.arrivalSeconds(back, hob, yield), HobBlast.positivePhaseSeconds(back, hob, yield));
                double law = DustModel.TOP_C * Math.pow(Math.max(1, Math.hypot(back, hob)), 1 / 6.0) * Math.pow(run(back, hob, yield, age), 5 / 6.0);
                assertTrue(top <= 1.02 * law + 0.01, burst + t + " с: верх " + top + " м выше пограничного слоя (" + law + " м)");
                if (w.backAge(t) >= 1) {
                    assertEquals(law, top, 0.02 * law, burst + t + " с: верх в конце фазы — не пограничный слой");
                    ends++;
                }
                tops[k] = top;
                heads++;
            }
            assertTrue(heads > 40 && ends > 10, burst + "голова на " + heads + " узлах сетки, клин до конца фазы — на " + ends);
            // всплеск — верх выше обоих соседей по сетке (×1,12 по времени) больше чем на четверть
            for (int k = 1; k + 1 < tops.length; k++) {
                if (Double.isNaN(tops[k - 1] + tops[k] + tops[k + 1])) continue;
                double t = 1e-4 * Math.pow(10, 0.05 * k);
                assertTrue(tops[k] <= 1.25 * Math.max(tops[k - 1], tops[k + 1]) + 0.05,
                        burst + t + " с: всплеск верха " + tops[k] + " м среди " + tops[k - 1] + " и " + tops[k + 1]);
            }
        }
    }

    /** Путь фронта модели по земле за {@code seconds} с после того, как он прошёл {@code groundM} м, — делением пополам. */
    private static double run(double groundM, double hob, double yield, double seconds) {
        if (seconds <= 0) return 0;
        double target = HobBlast.arrivalSeconds(groundM, hob, yield) + seconds, lo = 0, hi = 1;
        while (HobBlast.arrivalSeconds(groundM + hi, hob, yield) < target) hi *= 2;
        for (int i = 0; i < 60; i++) {
            double m = 0.5 * (lo + hi);
            if (HobBlast.arrivalSeconds(groundM + m, hob, yield) < target) lo = m;
            else hi = m;
        }
        return 0.5 * (lo + hi);
    }

    /**
     * В огненном шаре на земле пыли нет: голова с переходом к пелене и полосой брызг над водой — за краем шара
     * ({@link BlastWall#underBall}); средняя голова в шаре — ноль и над сушей, и над водой.
     */
    @Test
    void noHeadInTheFireball() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT];
        int checked = 0;
        for (double t = 0.1; t <= 6; t += 0.02) {
            double ball = GROUND.blocks(w.underBall(t));
            if (ball <= 0 || !w.slot(t, s) || s[3] <= 0) continue;
            assertTrue(s[0] - BlastWall.HANDOFF_TO * s[1] - s[5] >= ball - 0.01, t + " с: голова или брызги в шаре");
            // до самого края — без него: там конец перехода к пелене, и float слота даёт 1e-10
            for (double r = 0; r < 0.999 * ball; r += 0.05 * ball) {
                for (double wet : new double[]{0, 0.5, 1}) {
                    for (double up : new double[]{-0.3, 0.05, 0.5})
                        assertEquals(0, BlastWall.sigma(s, r, 0, up * s[2], wet), t + " с: голова в шаре в " + r + " блоках от эпицентра");
                }
            }
            checked++;
        }
        assertTrue(checked > 20, "проверок у шара " + checked);
    }

    /**
     * Клин — законы пыльного слоя за фронтом: верх u^(5/6) (Kuhl), поднятое — импульс скоростного напора
     * ({@link DustModel#lifted}); столб над землёй — густота у земли × верх — растёт назад как поднятое, а густота у земли
     * больше всего у края (у u ≈ 0,1 — в 2,6 раза гуще, чем в конце клина). За клином место за полдлины переходит к пелене.
     */
    @Test
    void wedgeLaws() {
        assertEquals(0, BlastWall.top(0));
        assertEquals(1, BlastWall.top(1), 1e-12);
        assertEquals(1, BlastWall.top(3), 1e-12);
        assertEquals(0, BlastWall.shape(0));
        assertEquals(1, BlastWall.shape(1), 1e-12);
        assertEquals(1, BlastWall.shape(2), 1e-12);
        double peak = 0, at = 0;
        for (double u = 0.001; u <= 1; u += 0.001) {
            assertEquals(DustModel.lifted(u), BlastWall.shape(u) * BlastWall.top(u), 1e-9, "столб не поднятое, u = " + u);
            if (BlastWall.shape(u) > peak) {
                peak = BlastWall.shape(u);
                at = u;
            }
        }
        assertEquals(2.6, peak, 0.15, "самая густая у края");
        assertTrue(at > 0.05 && at < 0.2, "самая густая на " + at);
        assertEquals(1, BlastWall.handoff(1), 1e-12);
        assertEquals(0, BlastWall.handoff(BlastWall.HANDOFF_TO), 1e-12);
        assertEquals(0.5, BlastWall.handoff(0.5 * (BlastWall.HANDOFF_FROM + BlastWall.HANDOFF_TO)), 1e-12);
    }

    /** Слой {@link DustModel} подрыва {@link #GROUND} на {@code groundM} м от эпицентра. */
    private static DustModel.Column column(double groundM) {
        return DustModel.column(groundM, GROUND.hobMetres(), GROUND.yieldKt(), GROUND.surface(), GROUND.visibility(), DustModel.Surface.REFERENCE);
    }

    /** Вал головы крутится в одну сторону и не быстрее {@link BlastWall#ROLL_MAX}. */
    @Test
    void headRollsSteadily() {
        BlastWall w = new BlastWall(GROUND, false);
        assertEquals(0, w.rollPhase(0));
        double prev = 0;
        for (double t = 0.05; t <= 600; t += 0.05) {
            double phase = w.rollPhase(t);
            assertTrue(phase >= prev - 1e-9, t + " с: вал крутится назад");
            assertTrue(phase - prev <= BlastWall.ROLL_MAX * 0.05 + 1e-6, t + " с: быстрее предела " + (phase - prev) / 0.05);
            prev = phase;
        }
        assertTrue(w.rollPhase(10) > Math.PI, "за 10 с вал не обернулся и наполовину: " + w.rollPhase(10));
    }

    /**
     * Клубы вдоль кольца — две фазы: у каждой окружность в плитках целая (плитка шума без шва на кольце) и постоянна, пока
     * фаза видна; номер и окружность фазы меняются только там, где её вес — ноль, а веса двух фаз в сумме — 1. Клуб вдоль
     * кольца в полном весе фазы — длиной в плитку слота (верх клина в тот же миг), а не растянут.
     */
    @Test
    void phasesChangeOnlyWhenHidden() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] prev = new float[BlastWall.SLOT], now = new float[BlastWall.SLOT];
        assertTrue(w.slot(0.4, prev));
        int changes = 0;
        for (double t = 0.41; t <= 60 && w.slot(t, now); t += 0.01) {
            // за шаг вес фазы меняется не больше чем на 2·Δψ (ψ — логарифм головы по основанию роста фазы)
            double step = 2 * Math.abs(Math.log(now[0] / prev[0])) / Math.log(BlastWall.PHASE_GROWTH) + 1e-4;
            for (int j = 0; j < 2; j++) {
                float weight = j == 0 ? now[10] : 1 - now[10];
                assertTrue(weight >= 0 && weight <= 1);
                assertEquals(Math.rint(now[8 + j]), now[8 + j], 0, "окружность не целая");
                assertTrue(now[8 + j] >= 1);
                // клуб вдоль кольца против плитки поперёк: в полном весе — один к одному, за фазу — не больше √1,25 и
                // поправки верха
                double stretch = 2 * Math.PI * now[0] / now[8 + j] / now[11];
                if (weight > 0.95) assertEquals(1, stretch, 0.03, t + " с: клубы фазы " + j + " вдоль кольца в " + stretch + " раза длиннее");
                // пока клин у шара короче фазы, верх растёт с возрастом пыли быстрее кольца — клубы вдоль него короче
                // (головы тогда — метры), окружность фазы та же
                if (weight > 0.05 && w.backAge(t) >= 1)
                    assertTrue(stretch > 0.85 && stretch < 1.18, t + " с: клубы фазы " + j + " вдоль кольца в " + stretch + " раза длиннее");
                if (now[12 + j] != prev[12 + j]) {
                    changes++;
                    assertTrue(weight <= step, t + " с: фаза " + j + " сменилась при весе " + weight);
                } else {
                    assertEquals(prev[8 + j], now[8 + j], 0, t + " с: окружность фазы " + j + " сменилась без смены фазы");
                }
            }
            System.arraycopy(now, 0, prev, 0, now.length);
        }
        assertTrue(changes > 4, "фазы почти не менялись: " + changes);
    }

    /**
     * Числа слота — те, что считает шейдер: край, длина и верх клина, ослабление-масштаб и доли пыли у края и в конце клина,
     * плитка; облако Вильсона — только во влажном воздухе.
     */
    @Test
    void slotCarriesTheHead() {
        BlastWall dry = new BlastWall(GROUND, false), humid = new BlastWall(GROUND, true);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(dry.slot(3, s));
        BlastWall.Wall wall = dry.wall(3);
        assertEquals(wall.head(), s[0], 1e-3);
        assertEquals(wall.height(), s[2], 1e-3);
        assertEquals(GROUND.blocks(dry.length(3)), s[1], 1e-3);
        double[] sigmas = new double[3];
        double ref = dry.sigmas(3, sigmas);
        assertEquals(ref / GROUND.scale(), s[3], 1e-6 * s[3]);
        assertEquals(sigmas[0] / ref, s[14], 1e-6);
        assertEquals(sigmas[1] / ref, s[15], 1e-6);
        assertEquals(sigmas[2] / GROUND.scale(), s[7], 1e-6);
        assertEquals(1, Math.max(Math.max(s[14], s[15]), s[7] / s[3]), 1e-6, "масштаб — наибольшее");
        assertEquals(BlastWall.TILE * s[2], s[11], 1e-3);
        // верх клина — десятки метров, как у пыльного слоя в конце фазы волны (Kuhl, GRABLE)
        assertTrue(GROUND.metres(s[2]) > 25 && GROUND.metres(s[2]) < 80, "верх клина " + GROUND.metres(s[2]) + " м");
        assertEquals(0, s[18], "облако Вильсона в сухом воздухе");
        assertEquals(0, dry.wilsonReach(1));
        assertTrue(humid.slot(1, s));
        double front = GROUND.frontRadius(1 * 20 * GROUND.scale());
        assertEquals(BlastWall.WILSON_AT * front, s[16], 1e-3 * front);
        assertTrue(s[18] > 0);
        assertTrue(humid.wilsonReach(1) >= s[16] + s[17]);
        // облако Вильсона тает за секунды
        humid.slot(5, s);
        assertEquals(0, s[18]);
    }

    /**
     * Средняя голова ({@link BlastWall#sigma}, копия {@code pallWallAt} и {@code pallWallMean}): впереди края пыли и выше
     * клина — ничего; у края — тонкий слой у земли, гуще, чем в конце клина; столб над землёй растёт назад; за полутора
     * длинами клина — ничего (там пелена); кольцо круглое.
     */
    @Test
    void meanHeadIsAWedge() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(w.slot(4, s));
        double head = s[0], len = s[1], top = s[2], k = s[3];
        assertEquals(0, BlastWall.sigma(s, head + 2, 0, 0.1, 0));
        assertEquals(0, BlastWall.sigma(s, head - len, 0, 1.01 * top, 0));
        assertEquals(0, BlastWall.sigma(s, head - 0.1 * len, 0, 0.3 * top, 0), "у края выше клина");
        assertTrue(BlastWall.sigma(s, head - 0.1 * len, 0, 0.02 * top, 0) > 1.5 * BlastWall.sigma(s, head - len, 0, 0.02 * top, 0), "у края не гуще");
        assertEquals(0, BlastWall.sigma(s, head - 1.6 * len, 0, 0.1 * top, 0));
        double prev = 0;
        for (double back = 0.05; back <= 1; back += 0.05) {
            double tau = BlastWall.tau(s, head - back * len, 0, 0, Double.POSITIVE_INFINITY, 0);
            assertTrue(tau > prev, back + " длины: столб не растёт назад");
            prev = tau;
        }
        for (double up = -0.6; up <= 1.2; up += 0.05) {
            for (double back = -0.5; back <= 2; back += 0.02) {
                double v = BlastWall.sigma(s, head - back * len, 0, up * top, 0);
                assertTrue(v >= 0 && v <= 2.7 * k, up + ", " + back + ": " + v);
                assertEquals(v, BlastWall.sigma(s, 0, head - back * len, up * top, 0), 1e-9 * k, "голова не круглая");
            }
        }
    }

    private static double luminance(float[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /**
     * Свет кадра, как его считает {@code NukePall.light}: в полдень — небо Minecraft (0,47, 0,65, 1 на экране) и солнце
     * над головой, ночью — свет неба на клубах ночью ({@link NukePall#nightSky}, на экране 0,17), солнца нет.
     */
    private static NukePall.Sky sky(boolean day) {
        NukePall.Sky s = new NukePall.Sky();
        float ambient = day ? 1 : 0.17f;
        s.sy = day ? 1 : -1;
        s.day = day ? 1 : 0;
        float floor = (float) PallLight.linear(NukePall.nightSky(ambient, 1 - s.day));
        double[] colour = day ? new double[]{0.47, 0.65, 1} : new double[3];
        for (int c = 0; c < 3; c++) {
            s.sky[c] = NukePall.skyLight(colour[c], floor);
            s.sun[c] = NukePall.sunLight(1, s.sky[c], s.day);
        }
        s.base = ambient;
        s.exposure = (float) Sight.adapted(1, ambient);
        s.glowOnSmoke = (float) CloudPuffs.glowOnSmoke(ambient);
        return s;
    }

    /**
     * Пыль у камеры — цвета головы там, как её светит кадр ({@link NukePall#dustLight}). Голова — густой слой (видно на
     * метры): свет входит в него по толще до верха клина, до его переднего края и снизу. Ночью у пожаров низ головы у
     * земли не тёмный (его светят пожары снизу; по ночной карте освещения пыль вышла бы почти чёрной) и цвета огня, а в
     * глубине слоя — тень его толщи. Днём верх клина — бурый (красный, за ним зелёный, синий) в свете солнца и неба, в
     * глубине — тень; ночью тоже, как только шар на голове перестаёт слепить привыкший к темноте глаз (его свет — порядка
     * белой стены; раньше пересвечено всё, что он освещает).
     */
    @Test
    void nearDustTakesWallLight() {
        CloudPuffs puffs = new CloudPuffs(GROUND);
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT], glow = new float[8], c = new float[3], top = new float[3];
        double t = 0.5;
        while (GROUND.metres(w.wall(t).head()) < 1500) t += 0.05;
        slot(puffs, w, t, s, glow);
        double head = s[0], len = s[1], h = s[2];
        assertTrue(head < glow[3], "голова в 1,5 км — не над пожарами");
        for (double u : new double[]{0.03, 0.1, 0.3}) {
            double here = h * BlastWall.top(u);
            for (double up : new double[]{0.005, 0.01}) {
                NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - u * len, up * here, 0, up * here, c);
                assertTrue(luminance(c) > 0.2, u + " клина, " + up + " верха: ночью у пожаров низ головы тёмный, " + luminance(c));
                assertTrue(c[0] > c[1] && c[1] > c[2], u + " клина, " + up + " верха: ночью у пожаров не цвета огня, " + Arrays.toString(c));
            }
        }
        NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - 0.3 * len, 0.2 * h * BlastWall.top(0.3), 0, 0.2 * h * BlastWall.top(0.3), c);
        assertTrue(luminance(c) < 0.01, "ночью в глубине головы над пожарами не тень: " + luminance(c));
        NukePall.dustLight(s, glow, sky(true), GROUND.windDir(), head - len, 0.9 * h, 0, 0.9 * h, top);
        assertTrue(top[0] > top[1] && top[1] > top[2], "днём верх головы не бурый: " + Arrays.toString(top));
        assertTrue(luminance(top) > 0.5 && luminance(top) < 0.9, "днём верх головы " + luminance(top));
        NukePall.dustLight(s, glow, sky(true), GROUND.windDir(), head - len, 0.1 * h, 0, 0.1 * h, c);
        assertTrue(luminance(top) > 3 * luminance(c), "днём в глубине головы не тень: " + luminance(c) + " при верхе " + luminance(top));
        // ночью: свет шара на голове, привыкший к темноте глаз, — уже порядка белой стены
        double night = sky(false).exposure;
        while (CloudPuffs.ball(0.2126 * glow[4] + 0.7152 * glow[5] + 0.0722 * glow[6], glow[0], Math.hypot(s[0], glow[7])) * night > 3) {
            t += 0.05;
            slot(puffs, w, t, s, glow);
        }
        assertTrue(t < 12, "шар слепит ночью до " + t + " с");
        head = s[0];
        len = s[1];
        h = s[2];
        NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - len, 0.9 * h, 0, 0.9 * h, top);
        NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - len, 0.1 * h, 0, 0.1 * h, c);
        assertTrue(luminance(top) > 2.5 * luminance(c), t + " с, ночью в глубине головы не тень: " + luminance(c) + " при верхе " + luminance(top));
    }

    /** Стена и свет шара и пожаров у 15 кт в момент t: в {@code wall} и {@code glow}. */
    private static void slot(CloudPuffs puffs, BlastWall w, double t, float[] wall, float[] glow) {
        assertTrue(w.slot(t, wall));
        assertTrue(puffs.pallLight(MushroomModel.shape(t, 15, 0, true, GROUND.metres(w.wall(t).head())), glow));
    }

    /** Толща головы по вертикали ({@link BlastWall#tau}) — интеграл её ослабления по высоте. */
    @Test
    void verticalDepthIsTheIntegral() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(w.slot(6, s) && s[3] > 0);
        double head = s[0], len = s[1], top = s[2];
        for (double back : new double[]{0.08, 0.3, 0.7, 1.2}) {
            double r = head - back * len, here = top * BlastWall.top(back);
            for (double eye : new double[]{-0.3 * top, 0, 0.4 * here}) {
                double up = 0, down = 0, ds = top / 20000;
                for (double z = eye + ds / 2; z < top; z += ds) up += BlastWall.sigma(s, r, 0, z, 0) * ds;
                for (double z = ds / 2 + Math.min(eye, 0); z < eye; z += ds) down += BlastWall.sigma(s, r, 0, z, 0) * ds;
                assertEquals(up, BlastWall.tau(s, r, 0, eye, Double.POSITIVE_INFINITY, 0), 0.02 * up + 1e-9, back + ", " + eye);
                if (eye > 0) assertEquals(down, BlastWall.tau(s, r, 0, 0, eye, 0), 0.02 * down + 1e-9, back + ", " + eye);
            }
        }
    }
}
