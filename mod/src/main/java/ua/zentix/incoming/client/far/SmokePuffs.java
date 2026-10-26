package ua.zentix.incoming.client.far;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.plume.Smoke;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * Облака клиента квадратами слоя эффектов — на любой дальности, вблизи и вдали одинаково: клуб модели (гаусс массы
 * вещества от источника {@link SmokeGrid.Source}: облако взрыва, облако старта, дым пожара) — кучка запечённых клубов
 * ({@link FxAtlas#puff6}: симуляция газа в Blender, шестисторонний свет, векторы движения между кадрами).
 * <ul>
 * <li><b>Размер.</b> Видимый край гаусса — там, где толща падает до {@link #EDGE_TAU}: r = σ·√(2·ln(τ₀/{@value #EDGE_TAU}))
 * (τ₀ — толща поперёк середины, Σκ'm/(2πσhσv)); у густого облака это ~3,7σ, у тонкого — ближе к середине. Квадрат клуба
 * — этот край (карта клуба кадрирована по краю вещества, {@code tools/bake_fx/pack.py}).</li>
 * <li><b>Плотность.</b> Непрозрачность середины — 1 − e^(−τ₀); крупный на экране гаусс — кучка до {@value #CLUSTER}
 * клубов вокруг середины (чем крупнее, тем их больше и они мельче), в середине их перекрывается несколько: каждый берёт
 * свою долю, чтобы вместе вышло то же пропускание.</li>
 * <li><b>Цвет</b> — отражение толстого слоя смеси сажи, пыли и капель (два потока, {@link #thick}) при свете неба, каким
 * карта освещения светит блоки под открытым небом ({@link FarView#sky});
 * свет по сторонам (солнце, луна, небо, огненные шары кадра) — по картам кадра в шейдере ({@code fx.vsh}, {@code fx.fsh}).</li>
 * <li><b>Кадр</b> — тот, где симуляция выросла во столько же раз, во сколько вещество источника с выброса
 * ({@link SmokeGrid.Source#puffGrowth}, {@link FxAtlas#puffFrame}), у каждого клуба кучки со своим сдвигом.</li>
 * </ul>
 * Светящийся газ (горячее точки Дрейпера) у источника, что рисует тело огня ({@link SmokeGrid.Source#drawsGlow}: облако
 * взрыва — огненным шаром), здесь не рисуется: дым проступает той же долей, что тело тает. Отрезок облака
 * ({@link SmokeGrid.Sink#segment}) — клубы вдоль него через ~σ; кусок трубки ({@link SmokeGrid.Sink#tube}: шлейф пожара) —
 * как след снаряда ({@link FarFlightView#line}, {@link #tube}).
 */
final class SmokePuffs {
    /** Куда клубы, вуали и ленты идут квадратами (кадр слоя — {@link FarSprites}; проверки — свой приёмник). */
    interface Out {
        void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                   float b, float a);

        void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix, float shade,
                       float r, float g, float b, float a);

        /** Вуаль вещества у глаза по экрану ({@link Veil}) со светом мира light (упакованный). */
        void veil(Veil veil, int light);

        boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                       float alphaB, int lightB, float r, float g, float b, float[] joint);
    }

    /**
     * Чисел на ждущий клуб: место, σh, σv, сажа, пыль, капли, альбедо пыли (3), номер, источник, доля дыма (непрозрачность
     * клуба, что не тело огня, {@link #smokeShare}), клуб ли это отрезка (1 — свой кадр, отражение и блуждание, как у
     * клубов трубки), вид клуба ({@link SmokeGrid.Look}, номер) и его кадр (у вида не {@code SMOKE}).
     */
    static final int FIELDS = 17;
    /**
     * Клубы одного источника, чьи середины ближе этой доли меньшего σ (по горизонтали и по вертикали), — один гаусс с теми
     * же массой и моментами: их сумма от одного гаусса почти не отличается (у двух равных на расстоянии σ — на 1,3 % пика),
     * а рисуется вдвое-вдесятеро меньше квадратов. Так пуфовые модели держат число пуфов (SCIPUFF: Sykes и др., «PC-SCIPUFF
     * Version 1.2PD Technical Documentation», ARAP Report 718, 1998, слияние перекрывающихся пуфов). Пыль волны —
     * 32 клуба (4 кольца × 8 секторов), и когда расплывание шире расстояния между ними, они лежат друг на друге: в дыму
     * налёта из 16 ударов кадр заливал ~17 экранов (замер {@code SmokeFillTest}).
     */
    static final double MERGE = 1;

    /**
     * Слить близкие клубы ({@link #MERGE}) с {@code start} до {@code n} в {@code held} ({@link #FIELDS} чисел на клуб): вес —
     * ослабление (что видно), середина — средняя, σ² — средняя σ² плюс разброс середин (по горизонтали — половина:
     * σh общий для двух осей), массы — суммы; слитый помечается в {@code gone}. Только вещество одного альбедо.
     */
    static void merge(double[] held, int start, int n, boolean[] gone, double sootK, double dropK) {
        merge(held, start, n, gone, sootK, dropK, MERGE);
    }

    /** То же с долей σ {@code reach}; вид слитого (кадр клуба, зерно) — у того, чьего вещества больше. */
    static void merge(double[] held, int start, int n, boolean[] gone, double sootK, double dropK, double reach) {
        for (int i = start; i < n; i++) {
            if (gone[i]) continue;
            int a = i * FIELDS;
            boolean again = true;
            while (again) {
                again = false;
                for (int j = i + 1; j < n; j++) {
                    if (gone[j]) continue;
                    int b = j * FIELDS;
                    if (held[a + 8] != held[b + 8] || held[a + 9] != held[b + 9] || held[a + 10] != held[b + 10]) continue;
                    // клубы разного вида (дым, завеса выброса, пыль дробления) — разные кадры: в один гаусс не сливаются
                    if (held[a + 15] != held[b + 15]) continue;
                    // клубы одного отрезка разложены по нему нарочно: слитые, они собрались бы в один гаусс, и плоский ряд
                    // (пыль старта: шаг — по меньшей σ, соседи ближе σh) потерял бы длину
                    if (held[a + 14] > 0.5 && held[b + 14] > 0.5 && (long) held[a + 11] / SEGMENT_CLUBS == (long) held[b + 11] / SEGMENT_CLUBS) continue;
                    double dx = held[b] - held[a], dy = held[b + 1] - held[a + 1], dz = held[b + 2] - held[a + 2];
                    double h = reach * Math.min(held[a + 3], held[b + 3]), v = reach * Math.min(held[a + 4], held[b + 4]);
                    if (dx * dx + dz * dz > h * h || Math.abs(dy) > v) continue;
                    absorb(held, a, b, sootK, dropK);
                    gone[j] = true;
                    again = true;
                }
            }
        }
    }

    /**
     * Клуб с b ({@link #FIELDS} чисел) — в клуб с a: вес — ослабление (что видно), середина — средняя, σ² — средняя σ² плюс
     * разброс середин (по горизонтали — половина: σh общий для двух осей), массы — суммы; у пыли разного цвета альбедо —
     * среднее по её ослаблению (так считается ω' смеси — точно), масса — по ослаблению смеси. Доля дыма — средняя по
     * ослаблению; вид (кадр клуба, зерно, источник) — у того, чьего вещества больше.
     */
    static void absorb(double[] held, int a, int b, double sootK, double dropK) {
        boolean same = held[a + 8] == held[b + 8] && held[a + 9] == held[b + 9] && held[a + 10] == held[b + 10];
        double dk = SmokeGrid.dustK((float) held[a + 8], (float) held[a + 9], (float) held[a + 10]), ka = dk * held[a + 6];
        double kb = (same ? dk : SmokeGrid.dustK((float) held[b + 8], (float) held[b + 9], (float) held[b + 10])) * held[b + 6];
        double wa = sootK * held[a + 5] + ka + dropK * held[a + 7], wb = sootK * held[b + 5] + kb + dropK * held[b + 7];
        double w = wa + wb;
        if (!(w > 0)) return;
        double fa = wa / w, fb = wb / w;
        // доля дыма — средняя по тому, что видно (ослаблению)
        held[a + 13] = fa * held[a + 13] + fb * held[b + 13];
        // вид источника (кадр клуба, зерно, видимость, кадр своего вида) — у того, чьего вещества больше
        if (wb > wa) {
            held[a + 11] = held[b + 11];
            held[a + 12] = held[b + 12];
            held[a + 14] = held[b + 14];
            held[a + 16] = held[b + 16];
        }
        double dx = held[b] - held[a], dy = held[b + 1] - held[a + 1], dz = held[b + 2] - held[a + 2];
        double mx = held[a] + fb * dx, my = held[a + 1] + fb * dy, mz = held[a + 2] + fb * dz;
        // второй момент около новой середины
        double ax = held[a] - mx, ay = held[a + 1] - my, az = held[a + 2] - mz, bx = held[b] - mx, by = held[b + 1] - my, bz = held[b + 2] - mz;
        double sh2 = fa * (held[a + 3] * held[a + 3] + 0.5 * (ax * ax + az * az)) + fb * (held[b + 3] * held[b + 3] + 0.5 * (bx * bx + bz * bz));
        double sv2 = fa * (held[a + 4] * held[a + 4] + ay * ay) + fb * (held[b + 4] * held[b + 4] + by * by);
        held[a] = mx;
        held[a + 1] = my;
        held[a + 2] = mz;
        held[a + 3] = Math.sqrt(sh2);
        held[a + 4] = Math.sqrt(sv2);
        held[a + 5] += held[b + 5];
        held[a + 7] += held[b + 7];
        double kd = ka + kb;
        if (same || !(kd > 0)) held[a + 6] += held[b + 6];
        else {
            for (int c = 8; c <= 10; c++) held[a + c] = (float) ((ka * held[a + c] + kb * held[b + c]) / kd);
            held[a + 6] = kd / SmokeGrid.dustK((float) held[a + 8], (float) held[a + 9], (float) held[a + 10]);
        }
    }

    /**
     * Сколько экранов могут закрыть квадраты облаков и мест трубок за кадр (сумма их площадей на экране с перекрытием) при
     * любом числе ударов: что видно сквозь дым — толща по лучу взгляда, Στ, а не число слоёв. Сверх бюджета кучки берут
     * меньше клубов (крупнее) с той же непрозрачностью середины; не хватает и по одному клубу на гаусс (пол) — крупное на
     * экране тает в вуаль, а лежащие друг на друге клубы и места разных источников сливаются ({@link Draw#flush}). Жалоба
     * 23.10: в дыму налёта 8 к/с; стенд 25.10, налёт из 100 шахедов: пол 41/104/175 экранов снаружи/с края/внутри (бюджет не
     * действовал), ~0,3 мс видеокарты за экран (RTX 4060, 2560×1600). ⚠ Число — оценка: 16 экранов ≈ 4,8 мс; налёт из 16
     * ударов ложится в них без слияния ({@code SmokeFillTest}), из 100 — с ним ({@code SmokeRaidTest}). При 8 налёт из 40
     * в песочнице с края и изнутри уже терял тёмную растекающуюся массу и шлейфы в ровную мглу вуали (кадры PR #460).
     */
    static final double FILL_BUDGET = 16;
    /** Бюджет, по которому кадр строится ({@link #FILL_BUDGET}; проверки сравнивают с кадром без бюджета). */
    static double budget = FILL_BUDGET;
    /**
     * Уровней слияния разных источников ({@link Draw#mergeLevel}): ячейка уровня k — {@link #LOD_CELL}·2^k края клуба (на
     * последнем — 64 края: облака налёта сходятся в несколько).
     */
    static final int LEVELS = 3;
    /**
     * Толща вещества перед сердцевиной клуба, за которой его не видно (пропускание e^(−3) — 5 %): такой не рисуется
     * ({@code Draw.hide}).
     */
    static final double HIDDEN = 3;
    /** Сердцевина клуба на экране, доля полуразмера квадрата: там диск запечённого клуба почти ровный ({@link #PUFF_DISCS}). */
    static final double CORE = 0.6;
    static final double LOD_CELL = 0.5;
    /** Уровень слияния прошлого кадра держится, пока пол уровнем ниже не меньше этой доли бюджета. */
    static final double HYSTERESIS = 0.8;
    /** Отобранные квадратами ({@code Draw.fit}) с этой доли бюджета по накопленной заливке тают в вуаль (лента — гаснет). */
    static final double FIT_FADE = 0.75;
    /** Групп в ячейке слияния мест трубок: места одной трубки — в разных группах. */
    static final int SLOTS = 8;
    /**
     * Гауссов вуали — не больше (толща — по лучу каждой из 13×13 вершин через каждый: ~0,3 мс процессора на 48); больше —
     * близкие сливаются.
     */
    static final int VEIL_MAX = 48;
    /** Мягкость вуали к миру — не меньше, блоков ({@link Veil#soft}). */
    static final double VEIL_SOFT_MIN = 1;
    /**
     * Чисел на ждущее место трубки: середина от глаза (3), σ, толща клубов и всей трубки поперёк оси, кусок оси, цвет (3),
     * степень тени, кадр клуба, видимая доля, клубов в кучке, свой ли кадр, ось (3), вид, вещество, дымка воздуха до него.
     */
    static final int PLACE_FIELDS = 21, P_CONTEXT = 18, P_MATERIAL = 19, P_AIR = 20;
    /** Чисел на ждущий отрезок ленты ({@code Draw.holdRibbon}). */
    static final int RIBBON_FIELDS = 21;
    /** Веществ мест в кадре — не больше (больше — места не сливаются). */
    static final int MATERIALS = 32;
    /**
     * Клубов облаков в кадре — не больше (план, слияние и квадраты — процессор на каждый): налёт из 100 ударов — ~800 после
     * слияния внутри источников, сверх — сливаются до плана ({@code Draw.thin}).
     */
    static final int BLOBS = 2000;
    /**
     * Мест трубок в кадре — не больше (план, слияние и квадраты — процессор на каждое): сверх — у отрезка мест в этом кадре нет
     * ({@link #tube}); в среднем их держит {@code FarFlightView.CLUB_BUDGET} шагом мест по прошлым кадрам, а это — в каждом
     * кадре, и в первом кадре налёта тоже.
     */
    static final int PLACES = FarFlightView.CLUB_BUDGET;
    /** Номера трубок облаков кадра — с этого (у следов — номер снаряда). */
    static final long TUBES = 1L << 40;

    /**
     * Доля экрана под квадратом с полуразмерами {@code radius} поперёк и {@code tall} вверх (м, лицом к камере) с серединой
     * (px, py, pz) от глаза,
     * обрезанным кадром: кадр — 16:9 с полудиагональю {@link FarView#edge} (⚠ соотношение сторон — оценка; для бюджета).
     */
    static double cover(FarView view, double px, double py, double pz, double radius, double tall) {
        org.joml.Vector3f f = view.forward(), l = view.left(), u = view.up();
        double depth = px * f.x + py * f.y + pz * f.z;
        double diag = Math.tan(Math.min(view.edge(), 1.5)), w = diag * 16 / Math.sqrt(337), h = diag * 9 / Math.sqrt(337);
        // позади глаза — не на экране; у плоскости глаза — проекция с глубины в сантиметры (обрежется кадром)
        if (depth <= 0) return 0;
        double r = radius / Math.max(depth, 0.05), t = tall / Math.max(depth, 0.05);
        double cu = (px * l.x + py * l.y + pz * l.z) / depth, cv = (px * u.x + py * u.y + pz * u.z) / depth;
        double x0 = Math.max(-w, cu - r), x1 = Math.min(w, cu + r), y0 = Math.max(-h, cv - t), y1 = Math.min(h, cv + t);
        if (x1 <= x0 || y1 <= y0) return 0;
        // видеокарта закрашивает весь квадрат клуба (прозрачные углы — тоже), а не круг в нём
        return Math.min(1, (x1 - x0) * (y1 - y0) / (4 * w * h));
    }

    /**
     * Доля экрана под плоским многоугольником с вершинами xyz (от глаза, подряд, выпуклый): обрезан ближней плоскостью и
     * кадром ({@link #cover}: 16:9 с полудиагональю {@link FarView#edge}), площадь проекции — по самим вершинам. Квадрат лицом
     * к камере — тот же прямоугольник, что у {@link #cover}; лежащий клуб и полоса ленты на экране — трапеция, ближний край
     * которой у пыли под углом в разы крупнее, чем на глубине середины.
     */
    static double polygonFill(FarView view, double[] xyz) {
        org.joml.Vector3f f = view.forward(), l = view.left(), u = view.up();
        double diag = Math.tan(Math.min(view.edge(), 1.5)), w = diag * 16 / Math.sqrt(337), h = diag * 9 / Math.sqrt(337);
        int n = xyz.length / 3;
        double[] a = new double[3 * (n + 1)], b;
        // в осях глаза: (влево, вверх, глубина); обрезка ближней плоскостью
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ax = xyz[3 * i], ay = xyz[3 * i + 1], az = xyz[3 * i + 2], bx = xyz[3 * j], by = xyz[3 * j + 1], bz = xyz[3 * j + 2];
            double ua = ax * l.x + ay * l.y + az * l.z, va = ax * u.x + ay * u.y + az * u.z, za = ax * f.x + ay * f.y + az * f.z;
            double ub = bx * l.x + by * l.y + bz * l.z, vb = bx * u.x + by * u.y + bz * u.z, zb = bx * f.x + by * f.y + bz * f.z;
            if (za >= NEAR_CLIP) {
                a[3 * m] = ua / za;
                a[3 * m + 1] = va / za;
                m++;
            }
            if (za >= NEAR_CLIP != zb >= NEAR_CLIP) {
                double t = (NEAR_CLIP - za) / (zb - za);
                a[3 * m] = (ua + (ub - ua) * t) / NEAR_CLIP;
                a[3 * m + 1] = (va + (vb - va) * t) / NEAR_CLIP;
                m++;
            }
            if (m + 2 > a.length / 3) a = java.util.Arrays.copyOf(a, 2 * a.length);
        }
        // обрезка кадром: четыре полуплоскости
        for (int edge = 0; edge < 4 && m > 0; edge++) {
            b = new double[3 * (2 * m + 1)];
            int k = 0;
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                double pa = inside(edge, a[3 * i], a[3 * i + 1], w, h), pb = inside(edge, a[3 * j], a[3 * j + 1], w, h);
                if (pa >= 0) {
                    b[3 * k] = a[3 * i];
                    b[3 * k + 1] = a[3 * i + 1];
                    k++;
                }
                if (pa >= 0 != pb >= 0) {
                    double t = pa / (pa - pb);
                    b[3 * k] = a[3 * i] + (a[3 * j] - a[3 * i]) * t;
                    b[3 * k + 1] = a[3 * i + 1] + (a[3 * j + 1] - a[3 * i + 1]) * t;
                    k++;
                }
            }
            a = b;
            m = k;
        }
        double area = 0;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            area += a[3 * i] * a[3 * j + 1] - a[3 * j] * a[3 * i + 1];
        }
        return Math.min(1, Math.abs(area) * 0.5 / (4 * w * h));
    }

    /** Ближняя плоскость обрезки многоугольника, блоков (у квадратов слоя — 0,05 блока, {@link #cover}). */
    private static final double NEAR_CLIP = 0.05;

    /** Со знаком: внутри ли точка (u, v) полуплоскости edge кадра ±w, ±h (0 — правая, 1 — левая, 2 — верх, 3 — низ). */
    private static double inside(int edge, double u, double v, double w, double h) {
        return switch (edge) {
            case 0 -> w - u;
            case 1 -> u + w;
            case 2 -> h - v;
            default -> v + h;
        };
    }

    /** Толща видимого края гаусса (порог контраста — 0,02; край квадрата чуть внутри). */
    static final double EDGE_TAU = 0.05;
    /**
     * Клубов на гаусс — не больше; по одному на столько пикселей видимого радиуса на экране (кадр клуба — 128 пикселей:
     * крупнее он мылится), мельче — один клуб.
     */
    static final int CLUSTER = 12;
    static final double CLUSTER_PIXELS = 40;
    /** Клуб кучки — не больше этой доли видимого радиуса гаусса. */
    static final double MAX_SHARE = 0.8;
    /** Клубы отрезка — через столько σ (меньшей из двух). */
    static final double SEGMENT_STEP = 1.2;
    /** Клубов на отрезок — не больше (струя столба брызг — до ~17 σ своего сечения). */
    static final int SEGMENT_CLUBS = 16;
    /**
     * Кадры — с этого. Прежний клуб (своя Mantaflow) начинался плотным круглым выбросом и шёл с третьего кадра; кадры
     * шапки облака взрыва EmberGen сверху ({@code tools/bake_fx}, puff-vdb) клубятся с первого.
     */
    static final int FIRST_FRAME = 0;
    /**
     * Разброс кадра клубов кучки и трубки около кадра источника, кадров в обе стороны: соседние кадры — та же шапка в
     * другой миг, клубы другие. У конца листа разброс уходит назад: у старого облака кадр источника — последний, и все клубы
     * были бы одной картинкой (только отражённой) — «штамп».
     */
    static final double FRAME_SPREAD = 1;

    /**
     * Доля дыма в непрозрачности вещества источника, что рисует тело огня (glow, {@link SmokeGrid.Source#drawsGlow}): тело тает,
     * дым проступает той же долей ({@link CloudModel#glowShare}), sky — свет неба. Доля — у непрозрачности, а не у массы: у шара
     * сотни оптических толщ, и масса × доля давала непрозрачную сажу уже при доле 1 % — скачок за ~0,1 с ночью около 1200 K и
     * днём при остывании шара взрывчатки, тёмный ореол до ~2 радиусов перед шаром ракеты днём (live 24.10).
     */
    static double smokeShare(boolean glow, double kelvin, double sky) {
        return glow ? 1 - CloudModel.glowShare(kelvin, sky) : 1;
    }

    /**
     * Сколько клубов кучки трубки ({@link #club}) накрывают её середину — ось трубки (с долей последнего, не меньше 1): клуб
     * j — со сдвигом и размером из того же зерна, что у его квадрата, диск — {@link #puffDisc} его кадра; середина под ним,
     * если сдвиг поперёк взгляда (у лежащего — по горизонтали) меньше диска (плавно на ±20 %). У трубки закон — у оси (толща
     * поперёк оси, {@code PlumeContinuityTest}); прежде доля клуба шла от слоёв в среднем по площади кучки (много × размер²),
     * а ось накрывают почти все клубы кучки (их сдвиг ~0,1 края): тонкий след вблизи выходил у оси до 1,4 закона (ревью
     * #443). Кучка облака ({@code blob}) держит среднее по площади (SmokeFillTest: бюджет не меняет пропускание в среднем).
     */
    static double centreCover(double px, double py, double pz, double d, long h, int count, double many, double size, double spread, double rh,
                              double rv, double rw, double grow, boolean lying, double age, boolean vary) {
        if (count <= 1) return 1;
        double vx = px / d, vy = py / d, vz = pz / d;
        // оси квадрата лицом к камере: поперёк — горизонталь, перпендикулярная взгляду, вверх — поперёк обеих
        double hx = -vz, hz = vx, hn = Math.hypot(hx, hz);
        if (hn < 1e-6) {
            hx = 1;
            hz = 0;
            hn = 1;
        }
        hx /= hn;
        hz /= hn;
        double ux = -vy * hz, uy = vz * hx - vx * hz, uz = vy * hx;
        double sum = 0;
        for (int j = 0; j < count; j++) {
            long hj = mix(h + j * 0x632BE59BD9B4E019L);
            double ox = gauss(hj) * spread * rh * 0.5, oy = lying ? 0 : gauss(hj >>> 7) * spread * rw * 0.5, oz = gauss(hj >>> 14) * spread * rh * 0.5;
            double k = size * (SIZE_LOW + 2 * (1 - SIZE_LOW) * unit(hj >>> 21)) * grow;
            double disc = puffDisc(clubFrame(age, unit(hj >>> 28), vary));
            double rho;
            if (lying) rho = Math.hypot(ox, oz) / (disc * rh * k);
            else rho = Math.hypot((ox * hx + oz * hz) / (disc * rh * k), (ox * ux + oy * uy + oz * uz) / (disc * rv * k));
            double t = Math.clamp((1.2 - rho) / 0.4, 0, 1);
            sum += t * t * (3 - 2 * t) * (j == count - 1 ? many - (count - 1) : 1);
        }
        return Math.max(1, sum);
    }

    /** Кадр клуба (с дробью) у источника с кадром age; u — зерно клуба 0..1; vary — с разбросом. */
    static double clubFrame(double age, double u, boolean vary) {
        double last = FxAtlas.PUFF6_FRAMES - 1.001;
        if (!vary) return Math.clamp(age, FIRST_FRAME, last);
        return Math.clamp(age, FIRST_FRAME + FRAME_SPREAD, last - FRAME_SPREAD) + FRAME_SPREAD * (2 * u - 1);
    }

    private static final Draw DRAW = new Draw();
    private static final SmokeGrid.Look[] LOOKS = SmokeGrid.Look.values();

    private SmokePuffs() {}

    /** Облака, которых объём не рисует, — в кадр. */
    static void collect(FarView view, FarSprites out) {
        double seconds = SmokeGrid.seconds(view.partial());
        SmokeGrid.quads(seconds, view.camera().x, view.camera().y, view.camera().z,
                b -> SmokeVisibility.visible(view, b), (s, sampledAt) -> sink(view, out, s, sampledAt));
    }

    /**
     * Ждущие клубы всех источников кадра (слияние {@link #merge}, бюджет {@link #FILL_BUDGET}) и вуаль — в кадр. Зовёт тот,
     * кто брал {@link #sink}, когда все источники кадра отданы (и следы снарядов, {@code FarFlightView}: их кучки и вуали —
     * в том же кадре).
     */
    static void flush() {
        DRAW.flush();
    }

    /** Заливка последнего кадра квадратами облаков, экранов, и доля кучки, что ей дал бюджет (замер, строки описания). */
    static double lastFill() {
        return DRAW.lastFill;
    }

    static double lastShare() {
        return DRAW.lastShare;
    }

    /** Пол кадра — по одному клубу на гаусс и место трубки, что остались квадратами, экранов: не выше бюджета. */
    static double lastFloor() {
        return DRAW.lastFloor;
    }

    /** Пол до слияния разных источников и вуали ({@link #lastFloor} — после), экранов. */
    static double lastRaw() {
        return DRAW.lastRaw;
    }

    /** Уровень слияния разных источников ({@link #LEVELS}; 0 — без слияния), гауссов вуали, квадратов-клубов кадра (гауссов и мест). */
    static int lastLevel() {
        return DRAW.level;
    }

    static int lastVeils() {
        return DRAW.lastVeils;
    }

    static int lastItems() {
        return DRAW.lastItems;
    }

    /** Клубов облаков в плане последнего кадра (после слияния внутри источников и {@link #BLOBS}) и мест трубок ({@link #PLACES}). */
    static int lastBlobs() {
        return DRAW.lastBlobs;
    }

    static int lastPlaces() {
        return DRAW.lastPlaces;
    }

    /** Мест трубок, которых в последнем кадре не дали ({@link #PLACES}): у их отрезков доли клубов в кадре не было. */
    static int lastRefused() {
        return DRAW.lastRefused;
    }

    /** Вещество мест трубок последнего кадра: ослабление всей массы клубов, м² (толща поперёк оси × √(2π)σ × кусок оси). */
    static double lastPlaceMatter() {
        return DRAW.lastPlaceMatter;
    }

    /** Бюджет заново: план и уровень слияния (проверки, что строят кадры с разным бюджетом подряд). */
    static void resetBudget() {
        DRAW.plans = 0;
        DRAW.level = 0;
    }

    /** Выход из мира, смена измерения: ждущие клубы и места, план, вуаль — заново. */
    static void reset() {
        DRAW.forget();
        DRAW.veilClear();
        resetBudget();
    }

    /**
     * Оценка, сколько экранов закроет кучка с полным числом клубов {@code most} при доле {@code s} (кучка — между одним
     * клубом и полной): квадратов — ⌈1 + (most − 1)·s⌉ (последний, с долей альфы, заливает всю свою площадь), у каждого — доля
     * размера (одиночный — весь край) × доля экрана под краем кучки с её разбросом ({@link #cover}). Для подбора доли; что
     * заливают настоящие квадраты — {@code Draw.drawnFill}.
     */
    static double clusterFill(FarView view, double px, double py, double pz, double ra, double rb, double most, double s) {
        double many = 1 + (most - 1) * s;
        int count = (int) Math.ceil(many - 1e-9);
        double size = count == 1 ? 1 : Math.min(MAX_SHARE, 1.6 / Math.sqrt(many)), reach = 1 + (count == 1 ? 0 : 1 - size) * 0.5;
        return Math.max(1, count * size * size) * cover(view, px, py, pz, ra * reach, rb * reach);
    }

    /**
     * Отрезок ленты следа или шлейфа ({@code FarFlightView.line}) — в ожидание кадра, как места трубок: лента тоже заливает
     * экран ({@link FarSprites.RibbonShape#fill}: полоса по своим вершинам, круг торца, вуаль «глаз в трубке»), и её заливка
     * — в плане и бюджете ({@link Draw#fit}: не влезла — не рисуется, наименее видимая — первой). Прежде ленты шли мимо
     * плана, и 300 трубок сверх мест кадра заливали 29,5 экрана при бюджете 16. Возвращает, ляжет ли полоса; joint — как у
     * {@link Out#ribbon} (поперёк у b).
     */
    static boolean ribbon(FarView view, Out out, double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by,
                          double bz, double halfB, float alphaB, int lightB, float r, float g, float b, float[] joint) {
        return DRAW.holdRibbon(view, out, ax, ay, az, halfA, alphaA, lightA, bx, by, bz, halfB, alphaB, lightB, r, g, b, joint);
    }

    /** Приёмник клубов источника source в кадре view в миг seconds: клубы — квадратами в out (один на поток кадра). */
    static SmokeGrid.Sink sink(FarView view, Out out, SmokeGrid.Source source, double seconds) {
        DRAW.begin(view, out, source, seconds);
        return DRAW;
    }

    /** Приёмник клубов одного источника: клубы — квадратами. */
    private static final class Draw implements SmokeGrid.Sink {
        private final double[] point = new double[2];
        FarView view;
        Out out;
        private SmokeGrid.Source source;
        /** Кадр клуба по росту вещества (с дробью): дыма и брызг. */
        private double age, sprayAge;
        private long seed;
        private boolean glow, sprays;
        private int index;
        /** Вид следующих клубов источника и его кадр ({@link SmokeGrid.Sink#look}). */
        private SmokeGrid.Look look = SmokeGrid.Look.SMOKE;
        private double lookFrame;

        void begin(FarView view, Out out, SmokeGrid.Source source, double seconds) {
            // клубы прошлого источника сливаются только между собой
            close();
            this.view = view;
            this.out = out;
            this.source = source;
            double growth = source.puffGrowth(seconds);
            this.age = FxAtlas.puffFrame(growth);
            this.sprayAge = FxAtlas.sprayFrame(growth);
            this.seed = source.seed();
            this.glow = source.drawsGlow();
            this.sprays = source.sprayClubs(seconds);
            this.index = 0;
            this.look = SmokeGrid.Look.SMOKE;
            this.lookFrame = 0;
            // вид источника для второго прохода ({@link #flush})
            if (contexts == ctxSource.length) {
                int n = 2 * contexts;
                ctxSource = java.util.Arrays.copyOf(ctxSource, n);
                ctxOut = java.util.Arrays.copyOf(ctxOut, n);
                ctxView = java.util.Arrays.copyOf(ctxView, n);
                ctxAge = java.util.Arrays.copyOf(ctxAge, n);
                ctxSprayAge = java.util.Arrays.copyOf(ctxSprayAge, n);
                ctxSeed = java.util.Arrays.copyOf(ctxSeed, n);
                ctxSprays = java.util.Arrays.copyOf(ctxSprays, n);
            }
            ctxSource[contexts] = source;
            ctxOut[contexts] = out;
            ctxView[contexts] = view;
            ctxAge[contexts] = age;
            ctxSprayAge[contexts] = sprayAge;
            ctxSeed[contexts] = seed;
            ctxSprays[contexts] = sprays;
            contexts++;
            from = holding;
            joint[0] = Float.NaN;
        }

        @Override
        public void look(SmokeGrid.Look look, double frame) {
            this.look = look;
            this.lookFrame = frame;
        }

        /** Концы куска трубки и поперёк ленты у конца прошлого куска этого источника (NaN — ленты не было). */
        private final FarFlightView.Tube a = new FarFlightView.Tube(), b = new FarFlightView.Tube();
        private final float[] joint = new float[3];

        /**
         * Шлейф — трубка, как след снаряда ({@link FarFlightView#line}): гауссово сечение σ = √(σh·σv) (среднее
         * геометрическое, как у толщи шлейфа {@code FirePlume.tau}; ⚠ сечение шлейфа в σh/σv до 2 раз шире, чем выше),
         * ослабление сажи на метр у каждого конца; узкая на экране — лентой, крупная — клубами через ≤ 1,2σ. Куски одного
         * источника идут подряд — лента без щели на стыке.
         */
        @Override
        public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                     double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                     double sigma0) {
            int at = index++;
            // светящийся газ — тело огня у источника; остывая, он проступает дымом той же долей, что тело тает
            double smoke = smokeShare(glow, kelvin, view.skyLuminance());
            if (smoke <= 0) {
                joint[0] = Float.NaN;
                return;
            }
            // над рельефом, что закрывает место источника вдали (как у клуба, {@link SmokeGrid.Source#visible}): у каждого конца
            // по его высоте и размаху сечения, вдоль куска — между ними
            double visA = source.visible(ay, 3 * Math.max(sha, sva)), visB = source.visible(by, 3 * Math.max(shb, svb));
            sootA *= visA;
            dustA *= visA;
            sootB *= visB;
            dustB *= visB;
            if (sootA + sootB + dustA + dustB <= 0 || !(sha > 0) || !(sva > 0) || !(shb > 0) || !(svb > 0)) {
                joint[0] = Float.NaN;
                return;
            }
            // ослабление на метр: сажа — полное (как толща шлейфа и порог видимости, FirePlume.tau), пыль — как у клубов
            // облаков (SmokeGrid.dustK по её альбедо)
            double kd = SmokeGrid.dustK(albedoR, albedoG, albedoB);
            double ea = CloudModel.SOOT_K * sootA + kd * dustA, eb = CloudModel.SOOT_K * sootB + kd * dustB;
            double ks = CloudModel.SOOT_K * (sootA + sootB), kdust = kd * (dustA + dustB);
            Vec3 cam = view.camera();
            end(a, ax - cam.x, ay - cam.y, az - cam.z, Math.sqrt(sha * sva), ea, smoke);
            end(b, bx - cam.x, by - cam.y, bz - cam.z, Math.sqrt(shb * svb), eb, smoke);
            // цвет и тень — толстого слоя смеси, как у клубов облаков ({@link #thick}); лента — тем же цветом в тоне клуба
            float cr = thick(ks, kdust, 0, albedoR), cg = thick(ks, kdust, 0, albedoG), cb = thick(ks, kdust, 0, albedoB);
            float tone = FarFlightView.TONE;
            double shade = shade(ks, kdust, 0, albedoG) / BAKED_K;
            int drawn = FarFlightView.line(view, out, a, b, cr * tone, cg * tone, cb * tone, cr, cg, cb, shade, sigma0,
                    mix(seed * 0x9E3779B97F4A7C15L + at), TUBES + contexts - 1, joint);
            if (drawn != FarFlightView.RIBBON) joint[0] = Float.NaN;
        }

        /**
         * Конец трубки (от камеры): ширина на половине непрозрачности середины и лента — как у следа ({@code TrailModel.width});
         * smoke — доля дыма в непрозрачности ({@link #smokeShare}).
         */
        private void end(FarFlightView.Tube t, double dx, double dy, double dz, double sigma, double e, double smoke) {
            double tau = e / (Math.sqrt(2 * Math.PI) * sigma), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double width = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma);
            FarFlightView.ribbon(width, d, view.pixel(), point);
            float alpha = width > 0 ? (float) (smoke * (1 - Math.exp(-tau)) * point[1] * view.air(d, dy)) : 0;
            t.set(dx, dy, dz, sigma, e, width, point[0], alpha, OPEN_SKY).shown = smoke;
        }

        @Override
        public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                            double water, float dustR, float dustG, float dustB, double kelvin, double rise) {
            int at = index++;
            // светящийся газ — тело огня у источника; остывая, он проступает дымом той же долей, что тело тает
            double smoke = smokeShare(glow, kelvin, view.skyLuminance());
            if (smoke <= 0 || soot + dust + water <= 0 || !(sh > 0) || !(sv > 0)) return;
            double len = 2 * Math.sqrt(ax * ax + ay * ay + az * az);
            // клубы отрезка ждут, как клубы-точки: слияние и бюджет заливки кадра — у всех ({@link #flush}); идут рядом друг с
            // другом, поэтому каждый — со своим кадром, отражением и блужданием, как клубы трубки (одинаковые — стопка монет)
            int n = (int) Math.min(SEGMENT_CLUBS, Math.max(1, Math.ceil(len / (SEGMENT_STEP * Math.min(sh, sv)))));
            for (int k = 0; k < n; k++) {
                double f = n == 1 ? 0 : (k + 0.5) / n * 2 - 1;
                hold(x + ax * f, y + ay * f, z + az * f, sh, sv, soot / n, dust / n, water / n, dustR, dustG, dustB, at * SEGMENT_CLUBS + k, smoke,
                        n > 1);
            }
        }

        /** Клубы кадра, что ждут слияния и бюджета: по {@link #FIELDS} чисел на клуб. */
        private double[] held = new double[16 * FIELDS];
        private int holding, from;
        private boolean[] merged = new boolean[16];
        /** Источники кадра: вид, приёмник, кадр клуба, зерно, брызги. */
        private SmokeGrid.Source[] ctxSource = new SmokeGrid.Source[8];
        private Out[] ctxOut = new Out[8];
        private FarView[] ctxView = new FarView[8];
        private double[] ctxAge = new double[8], ctxSprayAge = new double[8];
        private long[] ctxSeed = new long[8];
        private boolean[] ctxSprays = new boolean[8];
        private int contexts;
        /** Места трубок кадра, что ждут плана: по {@link #PLACE_FIELDS} чисел; зерно клубов и трубка — отдельно. */
        private double[] places = new double[64 * PLACE_FIELDS];
        private long[] placeSeed = new long[64], placeTube = new long[64];
        private boolean[] placeGone = new boolean[64];
        private int placeCount;
        /** Виды и приёмники мест (в игре у всех мест кадра — одни). */
        private FarView[] placeView = new FarView[4];
        private Out[] placeOut = new Out[4];
        private int placeContexts;
        /**
         * Ленты кадра, что ждут плана: концы (6), полуширины (2), непрозрачности (2), свет мира (2), цвет (3), поперёк у a (3),
         * вид, заливка, заметность.
         */
        private double[] ribbons = new double[16 * RIBBON_FIELDS];
        private int ribbonCount;
        private int[] ribbonEntry = new int[16];
        private final FarSprites.RibbonShape shape = new FarSprites.RibbonShape();
        private final float[] ribbonJoint = new float[3];
        /** Вещества мест кадра — цвет и степень тени ({@link #material}). */
        private final float[] materials = new float[4 * MATERIALS];
        private int materialCount;
        /** Запись плана у клуба облака и у места (−1 — квадратов нет). */
        private int[] blobEntry = new int[16], placeEntry = new int[64];
        /** Выжившие слияния уровня — их план заново. */
        private int[] dirty = new int[64];
        private boolean[] isDirty = new boolean[64];
        private final Cells cells = new Cells();
        /** Трубки в группах слитых мест кадра ({@link #mergeLevel}). */
        private final TubeGroups groups = new TubeGroups();
        /** Вуаль кадра: гауссы вещества у глаза ({@link #VEIL_FIELDS} чисел на гаусс); толщу по лучам считает {@link #veilFlush}. */
        private double[] veils = new double[8 * VEIL_FIELDS];
        private boolean[] veilGone = new boolean[8];
        private int veilCount;
        @Nullable
        private Out veilOut;
        private FarView veilView;
        private final Veil veil = new Veil();
        /**
         * Замер прошлого кадра: заливка квадратами, экранов; пол — по клубу на гаусс и место, что остались квадратами (не выше
         * бюджета), и пол до слияния и вуали; доля кучек; уровень слияния; гауссов вуали; кучек-квадратов.
         */
        double lastFill, lastFloor, lastRaw, lastShare = 1;
        int level, lastVeils, lastItems, lastBlobs, lastPlaces, lastRefused, refused;
        double lastPlaceMatter, placeMatter;
        /** Режим прохода {@link #blob} и {@link #club}: план (только цена) или квадраты с долей кучки {@link #share}. */
        private boolean planning;
        private double share = 1;
        /** Чей план пишется ({@link #planCluster}): клуб облака i — i, место p — ~p. */
        private int current;
        /** Пол кадра — сумма записей плана, что остались квадратами (по клубу на гаусс и место), экранов. */
        private double floor;

        /** Клуб-точку источника — в ожидание: слияние с соседями и бюджет — в {@link #flush}. */
        private void hold(double x, double y, double z, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                          int id, double smoke, boolean vary) {
            if (holding * FIELDS + FIELDS > held.length) held = java.util.Arrays.copyOf(held, 2 * held.length);
            int o = holding++ * FIELDS;
            held[o] = x;
            held[o + 1] = y;
            held[o + 2] = z;
            held[o + 3] = sh;
            held[o + 4] = sv;
            held[o + 5] = soot;
            held[o + 6] = dust;
            held[o + 7] = water;
            held[o + 8] = dr;
            held[o + 9] = dg;
            held[o + 10] = db;
            held[o + 11] = id;
            held[o + 12] = contexts - 1;
            held[o + 13] = smoke;
            held[o + 14] = vary ? 1 : 0;
            held[o + 15] = look.ordinal();
            held[o + 16] = lookFrame;
        }

        /** Клубы источника, что кончился, — слиты между собой ({@link #merge}). */
        private void close() {
            int n = holding - from;
            if (n <= 0) return;
            if (merged.length < holding) merged = java.util.Arrays.copyOf(merged, 2 * holding);
            java.util.Arrays.fill(merged, from, holding, false);
            merge(held, from, holding, merged, CloudModel.SOOT_K, SmokeGrid.DROP_K);
            from = holding;
        }

        /**
         * Кадр: ждущие клубы облаков и места трубок — квадратами в пределе заливки ({@link #FILL_BUDGET}) при любом их числе.
         * План — сколько экранов закроет каждый клуб и место одним квадратом (пол) и кучкой. Пол выше бюджета —
         * <ol>
         * <li>клубы и места разных источников, что лежат друг на друге, сливаются уровнями тем же законом, что {@link #merge}
         * (масса и моменты), пока пол не уложится ({@link #mergeLevel});</li>
         * <li>не уложился и так — крупнейшие на экране квадраты тают в вуаль у глаза (та же толща — по лучам, {@link Veil}),
         * ровно столько, чтобы остальные легли в бюджет ({@link #fit}): крупный гаусс — ровное пятно в пол-экрана,
         * подробности клубов на нём мало, а заливка — вся его площадь; вуаль стоит экран на всё.</li>
         * </ol>
         * Потом кучки берут столько клубов, чтобы заливка уложилась (каждая — с той же непрозрачностью середины), и всё —
         * квадратами; вуаль — одной сеткой.
         */
        void flush() {
            close();
            thin();
            double budget = SmokePuffs.budget;
            if (blobEntry.length < holding) blobEntry = new int[2 * holding];
            if (placeEntry.length < placeCount) placeEntry = new int[2 * placeCount];
            java.util.Arrays.fill(blobEntry, 0, holding, -1);
            java.util.Arrays.fill(placeEntry, 0, placeCount, -1);
            groups.reset();
            for (int p = 0; p < placeCount; p++) groups.add(p, placeTube[p]);
            // план: у каждого клуба и места — его квадраты на экране и полное число клубов в кучке
            planning = true;
            floor = 0;
            plans = 0;
            for (int i = 0; i < holding; i++) if (!merged[i]) held(i);
            for (int p = 0; p < placeCount; p++) place(p);
            planRibbons();
            lastRaw = floor;
            lastBlobs = holding;
            lastPlaces = placeCount;
            lastRefused = refused;
            lastPlaceMatter = placeMatter;
            int was = level;
            level = 0;
            // уровень прошлого кадра держится и тогда, когда пол чуть под бюджетом (до {@link #HYSTERESIS} от него): иначе у
            // границы бюджета кадр за кадром слияние то шло, то нет, и кучки мигали
            if (floor > budget || was > 0 && floor > HYSTERESIS * budget) {
                hide();
                // уровень прошлого кадра держится, пока ниже него пол не ляжет заметно под бюджет: иначе на границе бюджета
                // слитые клубы расходились бы и сходились кадр за кадром
                while (level < LEVELS && (floor > budget || level < was && floor > HYSTERESIS * budget)) mergeLevel(level++);
                if (floor > budget) fit(budget);
            }
            // доля кучки s — одна у всех: заливка при s по настоящей кривой каждой кучки ({@link #clusterFill}), подбор делением
            double s = 1;
            if (planned(1) > budget) {
                double lo = 0, hi = 1;
                for (int it = 0; it < 16; it++) {
                    double mid = 0.5 * (lo + hi);
                    if (planned(mid) > budget) hi = mid;
                    else lo = mid;
                }
                s = lo;
            }
            // оценка — по краю кучки; настоящие квадраты (их разброс размера и места) — проверка: больше бюджета — доля меньше
            for (int it = 0; it < 24 && s > 0 && drawn(s) > budget; it++) s = it < 23 ? 0.8 * s : 0;
            planning = false;
            share = s;
            for (int i = 0; i < holding; i++) if (!merged[i]) held(i);
            for (int p = 0; p < placeCount; p++) if (!placeGone[p]) place(p);
            drawRibbons();
            lastFill = drawn(s);
            lastFloor = floor;
            lastShare = s;
            lastItems = 0;
            for (int e = 0; e < plans; e++) if (!planDead[e] && planVeil[e] < 1) lastItems++;
            share = 1;
            forget();
            veilFlush();
        }

        /**
         * Клубов облаков больше {@link #BLOBS} (их ~8 на удар: налёт в сотни ударов) — до плана: план, слияние и квадраты
         * стоят процессора на каждый клуб, и работа кадра росла бы с налётом. Сперва лежащие друг на друге сливаются тем же
         * законом ({@link #absorb}) в ячейках мира в 0,5/1/2 края (≈ 3σ), как {@link #mergeLevel}, — дальше слияние
         * размазало бы вещество по чистому небу; не хватило — остаются {@link #BLOBS} самых заметных (непрозрачность середины
         * × телесный угол), прочие не рисуются (отбор по вкладу: их на экране меньше всех). Ждущие — подряд, без слитых.
         */
        private void thin() {
            int alive = compact();
            for (int level = 0; alive > BLOBS && level < LEVELS; level++) {
                double cell = LOD_CELL * (1 << level);
                cells.clear();
                for (int i = 0; i < holding; i++) {
                    if (merged[i]) continue;
                    int o = i * FIELDS;
                    boolean wet = wet(o);
                    long key = cell(held[o], held[o + 1], held[o + 2], 3 * Math.max(held[o + 3], held[o + 4]), cell, kind(o, wet));
                    int first = cells.get(key);
                    if (first < 0) {
                        cells.put(key, i);
                        continue;
                    }
                    absorb(held, first * FIELDS, o, CloudModel.SOOT_K, SmokeGrid.DROP_K);
                    if (wet) round(first * FIELDS);
                    merged[i] = true;
                    alive--;
                }
            }
            if (alive > BLOBS) {
                int n = 0;
                for (int i = 0; i < holding; i++) {
                    if (merged[i]) continue;
                    int o = i * FIELDS;
                    Vec3 eye = ctxView[(int) held[o + 12]].camera();
                    double ext = CloudModel.SOOT_K * held[o + 5] + SmokeGrid.dustK((float) held[o + 8], (float) held[o + 9], (float) held[o + 10]) * held[o + 6]
                            + SmokeGrid.DROP_K * held[o + 7], sh = held[o + 3], area = sh * Math.max(sh, held[o + 4]);
                    double dx = held[o] - eye.x, dy = held[o + 1] - eye.y, dz = held[o + 2] - eye.z;
                    double seen = held[o + 13] * -Math.expm1(-ext / (2 * Math.PI * area)) * area / Math.max(dx * dx + dy * dy + dz * dz, area);
                    if (n == order.length) order = java.util.Arrays.copyOf(order, 2 * n);
                    // от заметного к незаметному: ключ — 2 − заметность (положительные float сортируются как целые)
                    order[n++] = (long) Float.floatToIntBits((float) (2 - Math.min(1, seen))) << 32 | i;
                }
                java.util.Arrays.sort(order, 0, n);
                for (int k = BLOBS; k < n; k++) merged[(int) order[k]] = true;
            }
            compact();
        }

        /** Ждущие клубы облаков — подряд, без слитых (проходы плана идут по живым); сколько их. */
        private int compact() {
            int k = 0;
            for (int i = 0; i < holding; i++) {
                if (merged[i]) continue;
                if (k != i) System.arraycopy(held, i * FIELDS, held, k * FIELDS, FIELDS);
                merged[k++] = false;
            }
            holding = from = k;
            return k;
        }

        /** Ждущие клубы, места, источники и план — заново (конец кадра, выход из мира). */
        void forget() {
            holding = from = contexts = placeCount = placeContexts = materialCount = plans = ribbonCount = refused = 0;
            placeMatter = 0;
            java.util.Arrays.fill(ctxSource, null);
            java.util.Arrays.fill(ctxOut, null);
            java.util.Arrays.fill(ctxView, null);
            java.util.Arrays.fill(planView, null);
            java.util.Arrays.fill(placeView, null);
            java.util.Arrays.fill(placeOut, null);
        }

        /**
         * Записи плана: вид, место от глаза, полуоси края на экране (м), полное число клубов; пол записи, непрозрачность
         * середины (у места — его доля у оси трубки: толща для {@link #hide}) и то, что видно глазу (у места — непрозрачность
         * трубки у оси: для {@link #fit}), видная доля (что пропускает вещество перед ней), доля в вуали, у глаза ли, слита
         * ли, чья (клуб облака i — i, место p — ~p).
         */
        private FarView[] planView = new FarView[64];
        private double[] plan = new double[64 * 6], planCover0 = new double[64], planOpacity = new double[64], planShown = new double[64],
                planSeen = new double[64], planVeil = new double[64];
        private boolean[] planNear = new boolean[64], planDead = new boolean[64];
        private int[] planOwner = new int[64];
        /**
         * Чем квадраты кучки записи лягут на экран ({@link #drawnFill}): зерно клубов, рост квадрата пикселем (полуразмер на
         * экране / край в мире), разброс середины по вертикали (м, у клуба облака — по толщине слоя), лежит ли клуб, место ли
         * трубки (у него и одиночный клуб — со своим размером 0,8–1,2).
         */
        private long[] planSeed = new long[64];
        private double[] planGrow = new double[64], planOffV = new double[64];
        private boolean[] planLying = new boolean[64], planPlace = new boolean[64];
        private int plans;

        /**
         * Кучка клуба или места {@link #current} в план кадра: место от глаза, полуоси края (м), полное число клубов; near —
         * глаз в его краю (часть вещества — в вуали; такие не сливаются и не прячутся); opacity — непрозрачность середины на
         * экране (у места — его доля непрозрачности трубки у оси), shown — сколько его видно (у места — вся трубка у оси).
         */
        private void planCluster(FarView view, double px, double py, double pz, double ra, double rb, double most, boolean near, double opacity,
                                 double shown, long seed, double grow, double offV, boolean lying, boolean place) {
            if (plans == planView.length) {
                int n = 2 * plans;
                planView = java.util.Arrays.copyOf(planView, n);
                plan = java.util.Arrays.copyOf(plan, 6 * n);
                planCover0 = java.util.Arrays.copyOf(planCover0, n);
                planOpacity = java.util.Arrays.copyOf(planOpacity, n);
                planShown = java.util.Arrays.copyOf(planShown, n);
                planSeen = java.util.Arrays.copyOf(planSeen, n);
                planVeil = java.util.Arrays.copyOf(planVeil, n);
                planNear = java.util.Arrays.copyOf(planNear, n);
                planDead = java.util.Arrays.copyOf(planDead, n);
                planOwner = java.util.Arrays.copyOf(planOwner, n);
                planSeed = java.util.Arrays.copyOf(planSeed, n);
                planGrow = java.util.Arrays.copyOf(planGrow, n);
                planOffV = java.util.Arrays.copyOf(planOffV, n);
                planLying = java.util.Arrays.copyOf(planLying, n);
                planPlace = java.util.Arrays.copyOf(planPlace, n);
            }
            planView[plans] = view;
            int o = 6 * plans;
            plan[o] = px;
            plan[o + 1] = py;
            plan[o + 2] = pz;
            plan[o + 3] = ra;
            plan[o + 4] = rb;
            plan[o + 5] = most;
            planSeed[plans] = seed;
            planGrow[plans] = grow;
            planOffV[plans] = offV;
            planLying[plans] = lying;
            planPlace[plans] = place;
            double c = drawnFill(plans, 0);
            planCover0[plans] = c;
            planOpacity[plans] = opacity;
            planShown[plans] = shown;
            planSeen[plans] = 1;
            planVeil[plans] = 0;
            planNear[plans] = near;
            planDead[plans] = false;
            planOwner[plans] = current;
            if (current >= 0) blobEntry[current] = plans;
            else if (current != Integer.MIN_VALUE) placeEntry[~current] = plans;
            floor += c;
            plans++;
        }

        /** Запись e больше не рисуется (её клуб или место слиты; план выжившего — заново). */
        private void drop(int e) {
            if (e < 0 || planDead[e]) return;
            planDead[e] = true;
            if (planVeil[e] < 1) floor -= planCover0[e];
        }

        /** Заливка всех записей плана, что остались квадратами, при доле кучки s, экранов. */
        private double planned(double s) {
            double f = 0;
            for (int e = 0; e < plans; e++) {
                if (planDead[e] || planVeil[e] >= 1) continue;
                int o = 6 * e;
                f += plan[o + 5] <= 1 || 1 + (plan[o + 5] - 1) * s <= 1 + 1e-9 ? planCover0[e]
                        : clusterFill(planView[e], plan[o], plan[o + 1], plan[o + 2], plan[o + 3], plan[o + 4], plan[o + 5], s);
            }
            return f;
        }

        /**
         * Сколько экранов зальют квадраты кучки записи e при доле s — те же квадраты, что нарисует {@link #blob} или
         * {@link #club}: их число ⌈1 + (most − 1)·s⌉, размер с разбросом 0,8–1,2 по зерну, сдвиг середины по зерну, у лежащего —
         * высота на экране по своему месту ({@link #cover} каждого, обрезан кадром).
         */
        private double drawnFill(int e, double s) {
            int o = 6 * e;
            FarView v = planView[e];
            double px = plan[o], py = plan[o + 1], pz = plan[o + 2], ra = plan[o + 3], rb = plan[o + 4], most = plan[o + 5], grow = planGrow[e];
            double many = 1 + (most - 1) * s;
            int count = (int) Math.ceil(many - 1e-9);
            double size = count == 1 ? 1 : Math.min(MAX_SHARE, 1.6 / Math.sqrt(many)), spread = count == 1 ? 0 : 1 - size;
            double rh = ra / grow, f = 0;
            for (int j = 0; j < count; j++) {
                long hj = mix(planSeed[e] + j * 0x632BE59BD9B4E019L);
                double k = count == 1 && !planPlace[e] ? 1 : size * (SIZE_LOW + 2 * (1 - SIZE_LOW) * unit(hj >>> 21));
                double ox = gauss(hj) * spread * rh * 0.5, oy = planLying[e] ? 0 : gauss(hj >>> 7) * spread * planOffV[e] * 0.5,
                        oz = gauss(hj >>> 14) * spread * rh * 0.5;
                double qx = px + ox, qy = py + oy, qz = pz + oz;
                f += planLying[e] ? flatFill(v, qx, qy, qz, ra * k, ra * k) : cover(v, qx, qy, qz, ra * k, rb * k);
            }
            return f;
        }

        /**
         * Лежащий клуб ({@code FxQuads.flat}: квадрат на горизонтали, оси — «влево» экрана на горизонтали и поперёк, полуразмеры
         * a, b) с серединой (x, y, z) от глаза — доля экрана по его четырём вершинам ({@link #polygonFill}).
         */
        private final double[] flat = new double[12];

        private double flatFill(FarView v, double x, double y, double z, double a, double b) {
            org.joml.Vector3f l = v.left();
            double lx = l.x, lz = l.z, len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1e-6) {
                lx = 1;
                lz = 0;
            } else {
                lx /= len;
                lz /= len;
            }
            double ax = lx * a, az = lz * a, bx = -lz * b, bz = lx * b;
            double[] q = flat;
            q[0] = x - ax - bx;
            q[1] = y;
            q[2] = z - az - bz;
            q[3] = x - ax + bx;
            q[4] = y;
            q[5] = z - az + bz;
            q[6] = x + ax + bx;
            q[7] = y;
            q[8] = z + az + bz;
            q[9] = x + ax - bx;
            q[10] = y;
            q[11] = z + az - bz;
            return polygonFill(v, q);
        }

        /** Что зальют квадраты всех записей плана, что остались квадратами, при доле s ({@link #drawnFill}), экранов. */
        private double drawn(double s) {
            double f = 0;
            for (int e = 0; e < plans; e++) if (!planDead[e] && planVeil[e] < 1) f += plan[6 * e + 5] <= 1 ? planCover0[e] : drawnFill(e, s);
            return f;
        }

        /** Клеток сетки скрытого по экрану (16:9). */
        private static final int HIDE_COLS = 32, HIDE_ROWS = 18;
        private final double[] hideTau = new double[HIDE_COLS * HIDE_ROWS];
        private long[] order = new long[64];

        /**
         * Скрытое не рисуется: записи — от ближних к дальним по сетке экрана {@link #HIDE_COLS}×{@link #HIDE_ROWS}, у клетки — толща
         * вещества, уже пройденного перед ней. Клуб или место, у которого вся сердцевина ({@link #CORE} полуразмера: там
         * запечённый клуб почти ровный диск) — в клетках толще {@link #HIDDEN} (пропускание меньше 5 %), за густым дымом не
         * виден, и он не рисуется вовсе; у остальных — видная доля середины ({@link #planSeen}, для {@link #fit}). Толща в
         * клетку — только из сердцевины и непрозрачностью середины: клетке не прибавляется больше, чем есть, и видное не
         * пропадает. Глаз в краю — такой (его вещество и в вуали перед всем) не прячется.
         */
        private void hide() {
            int n = 0;
            for (int e = 0; e < plans; e++) {
                if (planDead[e]) continue;
                org.joml.Vector3f f = planView[e].forward();
                int o = 6 * e;
                double z = plan[o] * f.x + plan[o + 1] * f.y + plan[o + 2] * f.z;
                if (!(z > 0)) continue;
                if (n == order.length) order = java.util.Arrays.copyOf(order, 2 * n);
                order[n++] = (long) Float.floatToIntBits((float) z) << 32 | e;
            }
            java.util.Arrays.sort(order, 0, n);
            java.util.Arrays.fill(hideTau, 0);
            for (int k = 0; k < n; k++) {
                int e = (int) order[k], o = 6 * e;
                FarView v = planView[e];
                org.joml.Vector3f f = v.forward(), l = v.left(), u = v.up();
                double px = plan[o], py = plan[o + 1], pz = plan[o + 2], z = px * f.x + py * f.y + pz * f.z;
                double diag = Math.tan(Math.min(v.edge(), 1.5)), w = diag * 16 / Math.sqrt(337), h = diag * 9 / Math.sqrt(337);
                double cu = (px * l.x + py * l.y + pz * l.z) / z, cv = (px * u.x + py * u.y + pz * u.z) / z;
                double hu = CORE * plan[o + 3] / z, hv = CORE * plan[o + 4] / z;
                int i0 = Math.max(0, (int) Math.floor((cu - hu + w) / (2 * w) * HIDE_COLS)), i1 = Math.min(HIDE_COLS - 1, (int) Math.floor((cu + hu + w) / (2 * w) * HIDE_COLS));
                int j0 = Math.max(0, (int) Math.floor((cv - hv + h) / (2 * h) * HIDE_ROWS)), j1 = Math.min(HIDE_ROWS - 1, (int) Math.floor((cv + hv + h) / (2 * h) * HIDE_ROWS));
                // клетка середины (у мелкого сердцевина не накрывает ни одной середины клетки)
                int ic = (int) Math.floor((cu + w) / (2 * w) * HIDE_COLS), jc = (int) Math.floor((cv + h) / (2 * h) * HIDE_ROWS);
                boolean centre = ic >= 0 && ic < HIDE_COLS && jc >= 0 && jc < HIDE_ROWS;
                double front = centre ? hideTau[jc * HIDE_COLS + ic] : 0, least = Double.POSITIVE_INFINITY;
                int cells = 0;
                for (int j = j0; j <= j1; j++) {
                    double dv = ((j + 0.5) / HIDE_ROWS * 2 * h - h - cv) / hv;
                    for (int i = i0; i <= i1; i++) {
                        double du = ((i + 0.5) / HIDE_COLS * 2 * w - w - cu) / hu;
                        if (du * du + dv * dv > 1) continue;
                        least = Math.min(least, hideTau[j * HIDE_COLS + i]);
                        cells++;
                    }
                }
                if (cells == 0) least = centre ? front : 0;
                if (!planNear[e] && least >= HIDDEN) {
                    drop(e);
                    int owner = planOwner[e];
                    if (owner >= 0) merged[owner] = true;
                    else placeGone[~owner] = true;
                    continue;
                }
                planSeen[e] = Math.exp(-front);
                double t = -Math.log1p(-Math.min(planOpacity[e], 0.999));
                if (!(t > 0)) continue;
                if (cells == 0 && centre) hideTau[jc * HIDE_COLS + ic] += t;
                for (int j = j0; j <= j1 && cells > 0; j++) {
                    double dv = ((j + 0.5) / HIDE_ROWS * 2 * h - h - cv) / hv;
                    for (int i = i0; i <= i1; i++) {
                        double du = ((i + 0.5) / HIDE_COLS * 2 * w - w - cu) / hu;
                        if (du * du + dv * dv <= 1) hideTau[j * HIDE_COLS + i] += t;
                    }
                }
            }
        }

        /**
         * Пол выше бюджета и после слияния: квадратами остаются самые видные — по непрозрачности, что дошла до глаза
         * ({@link #planShown} × {@link #planSeen}; у места трубки — вся трубка у оси: тонкий тёмный шлейф заметен, хотя доля
         * каждого его места мала), пока их пол ложится в бюджет; остальные (бледное, заслонённое) — в вуаль у глаза, той же толщей
         * по лучам (лента — не рисуется). Отобранные в последней четверти бюджета ({@link #FIT_FADE}) тают в вуаль плавно (без
         * скачка, когда клуб пересекает порог); прежде полоса шла по видности от порога вдвое, и у ровной сцены (1000 одинаковых
         * трубок) таяли все отобранные — бюджет уходил на невидимое. Передние густые клубы — вид облака — остаются клубами; прежде в вуаль уходили крупнейшие, и
         * стена дыма с края становилась ровной мглой. Так предел заливки держится по построению.
         */
        private void fit(double budget) {
            int n = 0;
            for (int e = 0; e < plans; e++) {
                if (planDead[e] || planVeil[e] >= 1) continue;
                if (n == order.length) order = java.util.Arrays.copyOf(order, 2 * n);
                // по видной непрозрачности от большей к меньшей: ключ — 2 − видная (положительные float сортируются как целые)
                order[n++] = (long) Float.floatToIntBits((float) (2 - planShown[e] * planSeen[e])) << 32 | e;
            }
            java.util.Arrays.sort(order, 0, n);
            double sum = 0;
            int cut = 0;
            while (cut < n && sum + planCover0[(int) order[cut]] <= budget) sum += planCover0[(int) order[cut++]];
            if (cut == n) return;
            double acc = 0;
            for (int k = 0; k < n; k++) {
                int e = (int) order[k];
                if (k >= cut) {
                    planVeil[e] = 1;
                    continue;
                }
                // последняя четверть бюджета (наименее видные из отобранных) тает плавно: клуб у порога не выпрыгивает
                acc += planCover0[e];
                planVeil[e] = smoothstep(FIT_FADE * budget, budget, acc);
            }
            floor = sum;
        }

        /** Доля вещества клуба или места {@link #current}, что идёт в вуаль, а не квадратами ({@link #fit}). */
        private double veilShare() {
            int e = current >= 0 ? blobEntry[current] : placeEntry[~current];
            return e < 0 ? 0 : planVeil[e];
        }

        /** Сливать ли клуб или место с записью e: квадрат есть, не слит, глаз не в его краю. */
        private boolean mergeable(int e) {
            return e >= 0 && !planDead[e] && !planNear[e] && planVeil[e] == 0;
        }

        /** Род клуба с o для ячеек слияния: вид ({@link SmokeGrid.Look}: свои кадры) и брызги ли — разные не сливаются. */
        private int kind(int o, boolean wet) {
            return 2 * (int) held[o + 15] + (wet ? 1 : 0);
        }

        /** Брызги ли клуб с o: у источника с брызгами капель больше дыма и пыли — свои кадры, с дымом не сливаются. */
        private boolean wet(int o) {
            return ctxSprays[(int) held[o + 12]] && SmokeGrid.DROP_K * held[o + 7] > CloudModel.SOOT_K * held[o + 5]
                    + SmokeGrid.dustK((float) held[o + 8], (float) held[o + 9], (float) held[o + 10]) * held[o + 6];
        }

        /**
         * Слитый клуб брызг с o — круглый (струя одного направления — круглого сечения): вытянутый гаусс слой рисует
         * растянутым кадром — полосой с прямыми боками, «простынёй» ({@code WaterSalvoTest}); σ — средняя по осям.
         */
        private void round(int o) {
            double r = Math.sqrt((2 * held[o + 3] * held[o + 3] + held[o + 4] * held[o + 4]) / 3);
            held[o + 3] = r;
            held[o + 4] = r;
        }

        /** Выживший слияния i — в список на план заново (n — сколько в нём уже). */
        private int dirty(int i, int n) {
            if (isDirty.length <= i) isDirty = java.util.Arrays.copyOf(isDirty, 2 * (i + 1));
            if (isDirty[i]) return n;
            if (n == dirty.length) dirty = java.util.Arrays.copyOf(dirty, 2 * n);
            isDirty[i] = true;
            dirty[n] = i;
            return n + 1;
        }

        /**
         * Уровень слияния разных источников: клубы облаков (и места трубок одного вещества), чьи середины — в одной ячейке
         * мира в {@link #LOD_CELL}·2^уровень своего края (ячейки — по октавам края: размер — с точностью до вдвое), сливаются
         * тем же законом, что {@link #merge} (масса, середина и моменты; места — {@link #absorbPlace}). Ячейки — в мире, а не на
         * экране: поворот взгляда их не трогает, и слитое не мигает.
         */
        private void mergeLevel(int level) {
            double cell = LOD_CELL * (1 << level);
            int dirt = 0;
            cells.clear();
            for (int i = 0; i < holding; i++) {
                if (merged[i]) continue;
                int e = blobEntry[i];
                if (!mergeable(e)) continue;
                int o = i * FIELDS;
                boolean wet = wet(o);
                long key = cell(held[o], held[o + 1], held[o + 2], Math.max(plan[6 * e + 3], plan[6 * e + 4]), cell, kind(o, wet));
                int first = cells.get(key);
                if (first < 0) {
                    cells.put(key, i);
                    continue;
                }
                absorb(held, first * FIELDS, o, CloudModel.SOOT_K, SmokeGrid.DROP_K);
                if (wet) round(first * FIELDS);
                merged[i] = true;
                drop(e);
                dirt = dirty(first, dirt);
            }
            for (int k = 0; k < dirt; k++) {
                int i = dirty[k];
                isDirty[i] = false;
                drop(blobEntry[i]);
                blobEntry[i] = -1;
                held(i);
            }
            dirt = 0;
            cells.clear();
            for (int p = 0; p < placeCount; p++) {
                if (placeGone[p]) continue;
                int e = placeEntry[p], o = p * PLACE_FIELDS;
                if (!mergeable(e) || places[o + P_MATERIAL] < 0) continue;
                Vec3 cam = placeView[(int) places[o + P_CONTEXT]].camera();
                long base = cell(cam.x + places[o], cam.y + places[o + 1], cam.z + places[o + 2], Math.max(plan[6 * e + 3], plan[6 * e + 4]), cell,
                        2 + (int) places[o + P_MATERIAL]);
                for (int slot = 0; slot < SLOTS; slot++) {
                    long key = mix(base + slot);
                    int first = cells.get(key);
                    if (first < 0) {
                        cells.put(key, p);
                        break;
                    }
                    // место трубки, что уже есть в группе (и после прошлых уровней), — в следующую: места трубки подряд не
                    // сливаются
                    if (!groups.disjoint(first, p)) continue;
                    absorbPlace(first, p);
                    groups.join(first, p);
                    placeGone[p] = true;
                    drop(e);
                    dirt = dirty(first, dirt);
                    break;
                }
            }
            for (int k = 0; k < dirt; k++) {
                int p = dirty[k];
                isDirty[p] = false;
                drop(placeEntry[p]);
                placeEntry[p] = -1;
                place(p);
            }
        }

        /**
         * Место b — в место a: соседние трубки одного вещества (шлейфы соседних пожаров, следы залпа) — одна трубка:
         * ослабление — суммой, середина — средняя по нему, ось — средняя; σ² — средняя σ² плюс половина разброса середин
         * поперёк оси (сечение круглое), кусок оси — от большего из кусков до их суммы по разносу вдоль оси, толща поперёк
         * — по ним; кадр клуба и видимая доля — средние по ослаблению; зерно, вид и трубка — у того, чьего вещества больше.
         */
        private void absorbPlace(int pa, int pb) {
            int a = pa * PLACE_FIELDS, b = pb * PLACE_FIELDS;
            double ea = placeExt(a), eb = placeExt(b), w = ea + eb;
            if (!(w > 0)) return;
            double fa = ea / w, fb = eb / w;
            double turn = places[a + 15] * places[b + 15] + places[a + 16] * places[b + 16] + places[a + 17] * places[b + 17] < 0 ? -fb : fb;
            double ax = fa * places[a + 15] + turn * places[b + 15], ay = fa * places[a + 16] + turn * places[b + 16],
                    az = fa * places[a + 17] + turn * places[b + 17], an = Math.sqrt(ax * ax + ay * ay + az * az);
            if (an > 0) {
                ax /= an;
                ay /= an;
                az /= an;
            }
            double dx = places[b] - places[a], dy = places[b + 1] - places[a + 1], dz = places[b + 2] - places[a + 2];
            double d2 = dx * dx + dy * dy + dz * dz, par = Math.abs(dx * ax + dy * ay + dz * az), perp2 = Math.max(0, d2 - par * par);
            double sa = places[a + 3], sb = places[b + 3], la = places[a + 6], lb = places[b + 6];
            // поперёк оси середины отстоят от общей на fb·d и fa·d
            double sigma = Math.sqrt(fa * (sa * sa + 0.5 * fb * fb * perp2) + fb * (sb * sb + 0.5 * fa * fa * perp2));
            double len = Math.clamp(par + 0.5 * (la + lb), Math.max(la, lb), la + lb);
            // доля клубов во всей толще трубки (остальное — лента) — средняя: край — по толще всей трубки
            double clubs = fa * places[a + 4] / places[a + 5] + fb * places[b + 4] / places[b + 5];
            double tau = w / (Math.sqrt(2 * Math.PI) * sigma * len);
            if (eb > ea) {
                placeSeed[pa] = placeSeed[pb];
                places[a + P_CONTEXT] = places[b + P_CONTEXT];
            }
            places[a] += fb * dx;
            places[a + 1] += fb * dy;
            places[a + 2] += fb * dz;
            places[a + 3] = sigma;
            places[a + 4] = tau;
            places[a + 5] = tau / clubs;
            places[a + 6] = len;
            places[a + 11] = fa * places[a + 11] + fb * places[b + 11];
            places[a + 12] = fa * places[a + 12] + fb * places[b + 12];
            places[a + 13] = Math.max(places[a + 13], places[b + 13]);
            places[a + 14] = Math.max(places[a + 14], places[b + 14]);
            places[a + 15] = ax;
            places[a + 16] = ay;
            places[a + 17] = az;
            double px = places[a], py = places[a + 1], pz = places[a + 2];
            places[a + P_AIR] = placeView[(int) places[a + P_CONTEXT]].air(Math.sqrt(px * px + py * py + pz * pz), py);
        }

        /** Ослабление вещества места с o, м²: толща поперёк оси × √(2π)σ × его кусок оси. */
        private double placeExt(int o) {
            return places[o + 4] * Math.sqrt(2 * Math.PI) * places[o + 3] * places[o + 6];
        }

        /** Ждущий клуб i — в {@link #blob} с видом его источника. */
        private void held(int i) {
            int o = i * FIELDS, c = (int) held[o + 12];
            source = ctxSource[c];
            out = ctxOut[c];
            view = ctxView[c];
            age = ctxAge[c];
            sprayAge = ctxSprayAge[c];
            seed = ctxSeed[c];
            sprays = ctxSprays[c];
            look = LOOKS[(int) held[o + 15]];
            lookFrame = held[o + 16];
            current = i;
            blob(held[o], held[o + 1], held[o + 2], held[o + 3], held[o + 4], held[o + 5], held[o + 6], held[o + 7], (float) held[o + 8],
                    (float) held[o + 9], (float) held[o + 10], (int) held[o + 11], held[o + 13], held[o + 14] > 0.5);
        }

        /**
         * Место трубки — в ожидание кадра: середина (px, py, pz) от глаза, σ сечения, толща поперёк оси, что рисуют клубы
         * (tau), и всей трубки (edgeTau), кусок оси along, цвет толстого слоя, степень тени, кадр клуба, зерно h, видимая доля
         * vis, клубов в кучке — не больше most, vary — свой кадр и отражение у одиночного; ось (ax, ay, az); tube — трубка (её
         * места между собой не сливаются). 0 — места не видно (бледное, в дымке, вне кадра и не у глаза), иначе 1 (и ещё 1,
         * если глаз в его краю: вуаль) — для бюджета клубов трубок ({@code FarFlightView.CLUB_BUDGET}).
         */
        int holdPlace(FarView view, Out out, double px, double py, double pz, double sigma, double tau, double edgeTau, double along, float cr,
                      float cg, float cb, double shade, double age, long h, double vis, int most, boolean vary, double ax, double ay, double az,
                      long tube) {
            if (1 - Math.exp(-tau) < 0.004) return 0;
            double r = edge(edgeTau) * sigma, d = Math.sqrt(px * px + py * py + pz * pz), air = view.air(d, py);
            if (air < Sight.THRESHOLD) return 0;
            boolean near = d < r;
            if (!near && !FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return 0;
            int ctx = context(view, out);
            if (placeCount == placeSeed.length) {
                int n = 2 * placeCount;
                places = java.util.Arrays.copyOf(places, n * PLACE_FIELDS);
                placeSeed = java.util.Arrays.copyOf(placeSeed, n);
                placeTube = java.util.Arrays.copyOf(placeTube, n);
                placeGone = java.util.Arrays.copyOf(placeGone, n);
            }
            int o = placeCount * PLACE_FIELDS;
            places[o] = px;
            places[o + 1] = py;
            places[o + 2] = pz;
            places[o + 3] = sigma;
            places[o + 4] = tau;
            places[o + 5] = edgeTau;
            places[o + 6] = along;
            places[o + 7] = cr;
            places[o + 8] = cg;
            places[o + 9] = cb;
            places[o + 10] = shade;
            places[o + 11] = age;
            places[o + 12] = vis;
            places[o + 13] = most;
            places[o + 14] = vary ? 1 : 0;
            places[o + 15] = ax;
            places[o + 16] = ay;
            places[o + 17] = az;
            places[o + P_CONTEXT] = ctx;
            places[o + P_MATERIAL] = material(cr, cg, cb, shade);
            places[o + P_AIR] = air;
            placeSeed[placeCount] = h;
            placeTube[placeCount] = tube;
            placeGone[placeCount] = false;
            placeCount++;
            placeMatter += tau * Math.sqrt(2 * Math.PI) * sigma * along;
            return near ? 2 : 1;
        }

        /** Номер вещества мест кадра с цветом (cr, cg, cb) и степенью тени shade; −1 — веществ больше {@link #MATERIALS} (не сливается). */
        private int material(float cr, float cg, float cb, double shade) {
            float s = (float) shade;
            for (int m = 0; m < materialCount; m++) {
                int o = 4 * m;
                if (materials[o] == cr && materials[o + 1] == cg && materials[o + 2] == cb && materials[o + 3] == s) return m;
            }
            if (materialCount == MATERIALS) return -1;
            int o = 4 * materialCount;
            materials[o] = cr;
            materials[o + 1] = cg;
            materials[o + 2] = cb;
            materials[o + 3] = s;
            return materialCount++;
        }

        /** Вид и приёмник мест и лент кадра (у всех в игре — одни): номер. */
        private int context(FarView view, Out out) {
            if (placeContexts == 0 || placeView[placeContexts - 1] != view || placeOut[placeContexts - 1] != out) {
                if (placeContexts == placeView.length) {
                    placeView = java.util.Arrays.copyOf(placeView, 2 * placeContexts);
                    placeOut = java.util.Arrays.copyOf(placeOut, 2 * placeContexts);
                }
                placeView[placeContexts] = view;
                placeOut[placeContexts] = out;
                placeContexts++;
            }
            return placeContexts - 1;
        }

        /** Отрезок ленты — в ожидание кадра ({@link SmokePuffs#ribbon}). */
        boolean holdRibbon(FarView view, Out out, double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by,
                           double bz, double halfB, float alphaB, int lightB, float r, float g, float b, float[] joint) {
            FarSprites.RibbonShape q = shape;
            org.joml.Vector3f f = view.forward();
            boolean strip = q.set(view.far(), f.x, f.y, f.z, ax, ay, az, halfA, alphaA, bx, by, bz, halfB, alphaB, joint);
            double fill = q.fill(view, strip);
            if (fill > 0) {
                if ((ribbonCount + 1) * RIBBON_FIELDS > ribbons.length) ribbons = java.util.Arrays.copyOf(ribbons, 2 * ribbons.length);
                int o = ribbonCount++ * RIBBON_FIELDS;
                double[] v = {ax, ay, az, bx, by, bz, halfA, halfB, alphaA, alphaB, lightA, lightB, r, g, b, joint[0], joint[1], joint[2],
                        context(view, out), fill, Math.max(Math.max(q.alphaA, q.alphaB), Math.max(q.capAlpha, q.veil))};
                System.arraycopy(v, 0, ribbons, o, RIBBON_FIELDS);
            }
            if (strip) {
                joint[0] = q.nx;
                joint[1] = q.ny;
                joint[2] = q.nz;
            } else if (q.cap) joint[0] = Float.NaN;
            return strip;
        }

        /** Ленты кадра — записями плана: заливка своя, не сливаются и не прячут; не влезли в бюджет — не рисуются ({@link #fit}). */
        private void planRibbons() {
            if (ribbonEntry.length < ribbonCount) ribbonEntry = new int[2 * ribbonCount];
            for (int k = 0; k < ribbonCount; k++) {
                int o = k * RIBBON_FIELDS;
                FarView v = placeView[(int) ribbons[o + 18]];
                current = Integer.MIN_VALUE;
                double mx = 0.5 * (ribbons[o] + ribbons[o + 3]), my = 0.5 * (ribbons[o + 1] + ribbons[o + 4]), mz = 0.5 * (ribbons[o + 2] + ribbons[o + 5]);
                ribbonEntry[k] = plans;
                planCluster(v, mx, my, mz, 0, 0, 1, true, 0, ribbons[o + 20], 0, 1, 0, false, false);
                floor -= planCover0[ribbonEntry[k]];
                planCover0[ribbonEntry[k]] = ribbons[o + 19];
                floor += ribbons[o + 19];
            }
        }

        /** Ленты, что остались в бюджете, — в кадр по порядку (поперёк у a — каким его дал прошлый отрезок той же ленты). */
        private void drawRibbons() {
            for (int k = 0; k < ribbonCount; k++) {
                int e = ribbonEntry[k], o = k * RIBBON_FIELDS;
                if (planVeil[e] >= 1) continue;
                float keep = (float) (1 - planVeil[e]);
                ribbonJoint[0] = (float) ribbons[o + 15];
                ribbonJoint[1] = (float) ribbons[o + 16];
                ribbonJoint[2] = (float) ribbons[o + 17];
                placeOut[(int) ribbons[o + 18]].ribbon(ribbons[o], ribbons[o + 1], ribbons[o + 2], ribbons[o + 6], (float) ribbons[o + 8] * keep,
                        (int) ribbons[o + 10], ribbons[o + 3], ribbons[o + 4], ribbons[o + 5], ribbons[o + 7], (float) ribbons[o + 9] * keep,
                        (int) ribbons[o + 11], (float) ribbons[o + 12], (float) ribbons[o + 13], (float) ribbons[o + 14], ribbonJoint);
            }
        }

        /** Ждущее место p — в {@link #club}. */
        private void place(int p) {
            int o = p * PLACE_FIELDS, c = (int) places[o + P_CONTEXT];
            current = ~p;
            club(placeView[c], placeOut[c], places[o], places[o + 1], places[o + 2], places[o + 3], places[o + 4], places[o + 5], places[o + 6],
                    (float) places[o + 7], (float) places[o + 8], (float) places[o + 9], places[o + 10], places[o + 11], placeSeed[p], places[o + 12],
                    (int) places[o + 13], places[o + 14] > 0.5, places[o + P_AIR]);
        }

        /**
         * Клуб трубки с центром (px, py, pz) от глаза — место на оси, что стоит за along метров оси: σ сечения, толща трубки
         * поперёк оси tau, что рисуют клубы (не одного гаусса: непрозрачность у оси — у трубки), видимый край — по толще всей
         * трубки edgeTau (часть её вещества может рисовать лента) — кучкой запечённых клубов: цвет толстого слоя (cr, cg, cb; до
         * света неба у глаза), степень тени карт shade, кадр клуба age (с дробью), зерно h, видимая доля vis, клубов в кучке —
         * не больше most; vary — и одиночный клуб берёт свой кадр и отражение по зерну (клубы трубки идут рядом друг с другом:
         * одинаковые лежали бы стопкой монет). Клуб на экране — почти ровный диск радиусом {@link #puffDisc} полуразмера квадрата
         * (не гаусс): у оси его перекрывают соседи (2·диск / along), и каждый берёт свою долю непрозрачности трубки — вместе у
         * оси выходит 1 − e^(−τ). Глаз в трубке — вуаль ({@link #veil}). air — дымка воздуха до места ({@link FarView#air}).
         * В проходе плана — только запись плана.
         */
        private void club(FarView view, Out out, double px, double py, double pz, double sigma, double tau, double edgeTau, double along,
                          float cr, float cg, float cb, double shade, double age, long h, double vis, int most, boolean vary, double air) {
            double sh = sigma, sv = sigma;
            double a = 1 - Math.exp(-tau);
            if (a < 0.004) return;
            double edge = edge(edgeTau);
            double rh = edge * sh, rv = edge * sv, r = Math.max(rh, rv);
            double d = Math.sqrt(px * px + py * py + pz * pz);
            if (air < Sight.THRESHOLD) return;
            // глаз входит в клуб — квадраты клуба тают (квадрат в десятки блоков у самой камеры закрыл бы экран краем), кроме
            // клуба, чья середина впереди ({@link #ahead}); вещество, что квадрат не показывает (позади глаза, сбоку, у самого
            // глаза), — вуаль ({@link Veil}: толща по лучам из глаза через гаусс); вне края у глаза его нет (у гаусса за спиной
            // толща по лучу вперёд за краем — тысячные)
            double gate = smoothstep(0.5 * r, r, d), outside = gate + (1 - gate) * ahead(view, px, py, pz, sh, sv);
            double veiled = gate < 1 ? 1 - front(view, px, py, pz) * outside : 0;
            // пол кадра выше бюджета — малозаметное в вуаль той долей, что ему отвёл план ({@link #fit})
            double whole = planning ? 0 : veilShare();
            if (!planning && (veiled > 0 || whole > 0)) {
                // вещество места — толща трубки поперёк оси × √(2π)σ × его кусок оси
                double ext = tau * Math.sqrt(2 * Math.PI * sh * sv) * along;
                veilAdd(out, view, px, py, pz, sh, sv, ext, cr, cg, cb, (veiled + (1 - veiled) * whole) * vis * air);
            }
            if (outside <= 0 || whole >= 1) return;
            // вне кадра — не нужен: шар всего квадрата кучки (клуб — до 1,2 радиуса, его место — до ~0,9 от середины)
            if (!FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return;
            Sight.point(rh, 1, 1, d, view.pixel(), point);
            double grow = point[0] / rh;
            // кучка: чем крупнее гаусс на экране, тем больше клубов и тем они мельче (кадр 128 пикселей не растягивается на
            // пол-экрана); дробная часть числа — доля последнего, без скачка
            double pixels = point[0] / Math.max(d * view.pixel(), 1e-12);
            // крупнее экрана — подробность за его краем не видна, а заливка дорога: клубов меньше во столько раз, во сколько
            // гаусс по площади больше экрана (полудиагональ кадра, пикселей)
            double screen = view.edge() / Math.max(view.pixel(), 1e-12);
            double full = Math.clamp(Math.min(pixels / CLUSTER_PIXELS, most * Math.min(1, screen * screen / (pixels * pixels))), 1, most);
            if (planning) {
                // доля места в непрозрачности трубки у оси: ось накрывают 2·диск/кусок оси соседних мест
                double shown = a * vis * point[1] * air * outside, along3 = Math.max(1, 2 * puffDisc(clubFrame(age, 0.5, vary)) * point[0] / along);
                planCluster(view, px, py, pz, rh * grow, rv * grow, full, veiled > 0, 1 - Math.pow(1 - Math.min(shown, 0.999), 1 / along3), shown, h,
                        grow, rv, false, true);
                return;
            }
            // свет неба — яркость FarView.sky с разбросом клуба; оттенок — в шейдере у света неба (FxSkyTint)
            float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
            float sr = cr * lit, sg = cg * lit, sb = cb * lit;
            float shown = (float) (a * vis * point[1] * air * outside * (1 - whole));
            // бюджет заливки кадра ({@link #FILL_BUDGET}): кучка — между одним клубом и полной
            double many = 1 + (full - 1) * share;
            int count = (int) Math.ceil(many - 1e-9);
            double size = count == 1 ? 1 : Math.min(MAX_SHARE, 1.6 / Math.sqrt(many));
            // в середине кучки перекрывается столько клубов, у оси — ещё соседние места трубки (диск клуба на экране — с полом в
            // пиксель, как он нарисован): каждый берёт свою долю, вместе — то же пропускание
            double disc = puffDisc(clubFrame(age, 0.5, count > 1 || vary));
            double spread = count == 1 ? 0 : 1 - size;
            double overlap = centreCover(px, py, pz, d, h, count, many, size, spread, rh, rv, rv, grow, false, age, count > 1 || vary)
                    * Math.max(1, 2 * disc * point[0] / along);
            float sub = (float) (1 - Math.pow(1 - Math.min(shown, 0.999), 1 / overlap));
            for (int j = 0; j < count; j++) {
                long hj = mix(h + j * 0x632BE59BD9B4E019L);
                double ox = gauss(hj) * spread * rh * 0.5, oy = gauss(hj >>> 7) * spread * rv * 0.5, oz = gauss(hj >>> 14) * spread * rh * 0.5;
                double k = size * (SIZE_LOW + 2 * (1 - SIZE_LOW) * unit(hj >>> 21));
                double frame = clubFrame(age, unit(hj >>> 28), count > 1 || vary);
                int f0 = (int) frame;
                float last = j == count - 1 ? (float) (many - (count - 1)) : 1;
                boolean mirror = vary ? (hj >>> 40 & 1) == 1 : (j & 1) == 1;
                out.puff6(px + ox, py + oy, pz + oz, rh * grow * k, rv * grow * k, FxAtlas.puff6(f0, mirror), (float) (frame - f0), (float) shade, sr,
                        sg, sb, sub * last);
            }
        }

        /**
         * Гаусс вещества у глаза — в вуаль кадра: середина (px, py, pz) от глаза, σ по горизонтали и вертикали, ослабление
         * всей массы ext (м²), цвет толстого слоя (r, g, b), доля share (сколько его клубов растаяло). Гаусс, который ни по
         * одному лучу не закроет и {@link #VEIL_MIN}, не идёт: толща по лучу из глаза — не больше толщи через середину вдоль
         * самой тонкой оси, ext/(2π·σh·min(σh, σv)).
         */
        boolean veilAdd(Out out, FarView view, double px, double py, double pz, double sh, double sv, double ext, float r, float g, float b,
                        double share) {
            if (!(share > 0) || !(ext > 0) || !(sh > 0) || !(sv > 0)) return false;
            if ((1 - Math.exp(-ext / (2 * Math.PI * sh * Math.min(sh, sv)))) * share < VEIL_MIN) return false;
            if ((veilCount + 1) * VEIL_FIELDS > veils.length) veils = java.util.Arrays.copyOf(veils, 2 * veils.length);
            int o = veilCount++ * VEIL_FIELDS;
            veils[o] = px;
            veils[o + 1] = py;
            veils[o + 2] = pz;
            veils[o + 3] = sh;
            veils[o + 4] = sv;
            veils[o + 5] = ext;
            veils[o + 6] = r;
            veils[o + 7] = g;
            veils[o + 8] = b;
            veils[o + 9] = Math.min(share, 1);
            veilOut = out;
            veilView = view;
            return true;
        }

        void veilClear() {
            veilCount = 0;
            veilOut = null;
            veilView = null;
        }

        /**
         * Вуаль кадра — одна сетка по экрану ({@link Veil}): у каждой вершины толща по её лучу через все гауссы вуали; их больше
         * {@link #VEIL_MAX} — близкие сливаются ({@link #veilMerge}).
         */
        private void veilFlush() {
            if (veilOut != null && veilCount > 0) {
                if (veilCount > VEIL_MAX) veilMerge();
                veil.fill(veilView, veils, veilCount);
                if (veil.any()) veilOut.veil(veil, OPEN_SKY);
            }
            lastVeils = veilOut != null ? veilCount : 0;
            veilClear();
        }

        /**
         * Гауссов вуали больше {@link #VEIL_MAX} (толща — по лучу каждой вершины через каждый: цена процессора) — близкие
         * сливаются тем же законом: ослабление — суммой, середина и моменты — по видимому (ослабление × доля), цвет — средний по
         * нему (так вуаль и мешает цвета по толще). Ячейка — направление от глаза (грань куба, n × n на грани), октава
         * расстояния и октава размера; с каждым уровнем крупнее, последний — по грани: их не больше семи.
         */
        private void veilMerge() {
            for (int level = 0; veilCount > VEIL_MAX; level++) {
                int n = Math.max(1, 8 >> level), coarse = Math.max(0, level - 1);
                boolean last = level >= 5;
                if (veilGone.length < veilCount) veilGone = new boolean[2 * veilCount];
                java.util.Arrays.fill(veilGone, 0, veilCount, false);
                cells.clear();
                for (int c = 0; c < veilCount; c++) {
                    int o = c * VEIL_FIELDS;
                    double px = veils[o], py = veils[o + 1], pz = veils[o + 2], s = Math.max(veils[o + 3], veils[o + 4]);
                    double ax = Math.abs(px), ay = Math.abs(py), az = Math.abs(pz), m = Math.max(ax, Math.max(ay, az));
                    int face;
                    double u, v;
                    if (!(m > 0)) {
                        face = 6;
                        u = v = 0;
                    } else if (m == ax) {
                        face = px > 0 ? 0 : 1;
                        u = py / m;
                        v = pz / m;
                    } else if (m == ay) {
                        face = py > 0 ? 2 : 3;
                        u = px / m;
                        v = pz / m;
                    } else {
                        face = pz > 0 ? 4 : 5;
                        u = px / m;
                        v = py / m;
                    }
                    long key = face;
                    if (!last) {
                        long iu = (long) Math.min(n - 1, Math.floor((u + 1) * 0.5 * n)), iv = (long) Math.min(n - 1, Math.floor((v + 1) * 0.5 * n));
                        int far = Math.getExponent(Math.max(Math.sqrt(px * px + py * py + pz * pz), Math.max(s, 1e-3))) >> coarse;
                        int size = Math.getExponent(Math.max(s, 1e-3)) >> coarse;
                        key = mix(mix(mix(mix(face) + iu) + iv) + far) + size;
                    }
                    int first = cells.get(key);
                    if (first < 0) {
                        cells.put(key, c);
                        continue;
                    }
                    absorbVeil(first * VEIL_FIELDS, o);
                    veilGone[c] = true;
                }
                int k = 0;
                for (int c = 0; c < veilCount; c++) {
                    if (veilGone[c]) continue;
                    if (k != c) System.arraycopy(veils, c * VEIL_FIELDS, veils, k * VEIL_FIELDS, VEIL_FIELDS);
                    k++;
                }
                veilCount = k;
            }
        }

        /** Гаусс вуали с b — в гаусс с a ({@link #veilMerge}). */
        private void absorbVeil(int a, int b) {
            double wa = veils[a + 5] * veils[a + 9], wb = veils[b + 5] * veils[b + 9], w = wa + wb, ext = veils[a + 5] + veils[b + 5];
            if (!(w > 0)) return;
            double fa = wa / w, fb = wb / w;
            double mx = fa * veils[a] + fb * veils[b], my = fa * veils[a + 1] + fb * veils[b + 1], mz = fa * veils[a + 2] + fb * veils[b + 2];
            double ax = veils[a] - mx, ay = veils[a + 1] - my, az = veils[a + 2] - mz, bx = veils[b] - mx, by = veils[b + 1] - my, bz = veils[b + 2] - mz;
            double sh2 = fa * (veils[a + 3] * veils[a + 3] + 0.5 * (ax * ax + az * az)) + fb * (veils[b + 3] * veils[b + 3] + 0.5 * (bx * bx + bz * bz));
            double sv2 = fa * (veils[a + 4] * veils[a + 4] + ay * ay) + fb * (veils[b + 4] * veils[b + 4] + by * by);
            veils[a] = mx;
            veils[a + 1] = my;
            veils[a + 2] = mz;
            veils[a + 3] = Math.sqrt(sh2);
            veils[a + 4] = Math.sqrt(sv2);
            veils[a + 5] = ext;
            for (int c = 6; c <= 8; c++) veils[a + c] = fa * veils[a + c] + fb * veils[b + c];
            veils[a + 9] = Math.min(1, w / ext);
        }

        /**
         * Клуб: в проходе плана ({@link #planning}) — только сколько экранов закроет кучка при доле {@link #share}; иначе —
         * квадраты и вуаль. vary — клуб отрезка: свой кадр, отражение и блуждание середины, как у клубов трубки ({@link Draw#club}).
         * Возвращает заливку, экранов.
         */
        private double blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                            int id, double smoke, boolean vary) {
            Vec3 eye = view.camera();
            // на экране — проекция гаусса по углу взгляда (толща по лучу и высота квадрата); в мире — его σ (разброс клубов
            // кучки, край для рельефа и для глаза внутри)
            double across = projected(sh, sv, x - eye.x, y - eye.y, z - eye.z);
            double ks = SmokeGrid.SOOT_K * soot, kd = SmokeGrid.dustK(dr, dg, db) * dust, kw = SmokeGrid.DROP_K * water;
            double tau = (ks + kd + kw) / (2 * Math.PI * sh * across);
            // доля дыма — у непрозрачности, а не у массы: у шара сотни толщ, и сажа доли 1 % была бы уже непрозрачной
            double a = smoke * (1 - Math.exp(-tau));
            if (a < 0.004) return 0;
            double edge = edge(tau);
            double rh = edge * sh, rv = edge * across, rw = edge * sv, r = Math.max(rh, rw);
            double vis = source.visible(y, r);
            if (vis <= 0) return 0;
            Vec3 cam = view.camera();
            long h = mix(seed * 0x9E3779B97F4A7C15L + id);
            double px = x - cam.x, py = y - cam.y, pz = z - cam.z;
            if (vary) {
                // клубы отрезка идут рядом: середина гуляет ({@link #meander}, ⚠ оценка), иначе — ровный ряд
                double m = meander(Math.min(sh, sv), tau);
                px += m * gauss(h >>> 3);
                py += m * gauss(h >>> 11);
                pz += m * gauss(h >>> 19);
            }
            double d = Math.sqrt(px * px + py * py + pz * pz);
            double air = view.air(d, py);
            if (air < Sight.THRESHOLD) return 0;
            // глаз входит в клуб — квадраты клуба тают (квадрат в десятки блоков у самой камеры закрыл бы экран краем), а
            // вещество вокруг глаза — вуалью по экрану: толща по лучам из глаза через гаусс ({@link Veil}). «Внутри» — по эллипсоиду
            // края (у плоского слоя пыли в сотню метров шириной и метр толщиной глаз над ним снаружи; по наибольшему
            // радиусу он был «внутри», и вуаль слоя закрывала весь кадр)
            double q = Math.sqrt((px * px + pz * pz) / (rh * rh) + py * py / (rw * rw));
            // гаусс, чья середина впереди по взгляду, — ещё клубы ({@link #ahead}); лежащий клуб — не проекция по взгляду: его
            // квадрат лежит на горизонтали и впереди глаза, даже когда середина за спиной
            double gate = smoothstep(0.5, 1, q), outside, veiled;
            if (lies(sh, sv, py, d)) {
                outside = gate;
                veiled = 1 - gate;
            } else {
                outside = gate + (1 - gate) * ahead(view, px, py, pz, sh, sv);
                veiled = gate < 1 ? 1 - front(view, px, py, pz) * outside : 0;
            }
            // пол кадра выше бюджета — малозаметное в вуаль той долей, что ему отвёл план ({@link #fit})
            double whole = planning ? 0 : veilShare();
            if (!planning && (veiled > 0 || whole > 0))
                veil(px, py, pz, sh, sv, ks + kd + kw, ks, kd, kw, dr, dg, db, (veiled + (1 - veiled) * whole) * vis * air * smoke);
            if (outside <= 0 || whole >= 1) return 0;
            // вне кадра — не нужен: шар всего квадрата кучки (клуб — до 1,2 радиуса, его место — до ~0,9 от середины)
            if (!FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return 0;
            Sight.point(rh, 1, 1, d, view.pixel(), point);
            double grow = point[0] / rh;
            // свет неба — яркость того, каким карта освещения светит блоки и частицы под открытым небом (FarView.sky), с
            // разбросом клуба; его оттенок (ночью — сиреневый) — в шейдере только у света неба, не у огня (FxSkyTint)
            float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
            float cr = thick(ks, kd, kw, dr) * lit, cg = thick(ks, kd, kw, dg) * lit, cb = thick(ks, kd, kw, db) * lit;
            double light = shade(ks, kd, kw, dg);
            float shade = (float) (light / BAKED_K), sprayShade = (float) (light / SPRAY_K);
            float shown = (float) (a * vis * point[1] * air * outside * (1 - whole));
            // кучка: чем крупнее гаусс на экране, тем больше клубов и тем они мельче (кадр 128 пикселей не растягивается на
            // пол-экрана); дробная часть числа — доля последнего, без скачка
            double pixels = point[0] / Math.max(d * view.pixel(), 1e-12);
            // крупнее экрана — подробность за его краем не видна, а заливка дорога: клубов меньше во столько раз, во сколько
            // гаусс по площади больше экрана (полудиагональ кадра, пикселей)
            double screen = view.edge() / Math.max(view.pixel(), 1e-12);
            double most = Math.clamp(Math.min(pixels / CLUSTER_PIXELS, CLUSTER * Math.min(1, screen * screen / (pixels * pixels))), 1, CLUSTER);
            // бюджет заливки кадра ({@link #flush}): кучка — между одним клубом и полной
            double many = 1 + (most - 1) * share;
            int count = (int) Math.ceil(many - 1e-9);
            double size = count == 1 ? 1 : Math.min(MAX_SHARE, 1.6 / Math.sqrt(many));
            // слоёв кучки в среднем по её площади (цена: заливка)
            double overlap = Math.max(1, many * size * size);
            // заливка: доля экрана под кругом края кучки (обрезан кадром) × перекрытие
            // плоский клуб сверху лежит: его высота на экране — проекция диска
            boolean lying = lies(sh, sv, py, d);
            double reach = 1 + (count == 1 ? 0 : 1 - size) * 0.5;
            double ra = rh * grow, rb = (lying ? rh * Math.abs(py) / d : rv) * grow;
            if (planning) {
                planCluster(view, px, py, pz, ra, rb, most, veiled > 0, shown, shown, h, grow, rw, lying, false);
                return 0;
            }
            double fill = overlap * cover(view, px, py, pz, ra * reach, rb * reach);
            double spread = count == 1 ? 0 : 1 - size;
            // у источника с брызгами (взрыв на воде) вещество — больше капли, чем дым и пыль: клубы брызг; клуб своего вида
            // (завеса выброса, пыль дробления, облако разрыва в воздухе) — своими кадрами и своим кадром источника
            boolean own = look != SmokeGrid.Look.SMOKE, spray = !own && sprays && kw > ks + kd;
            double clubAge = own ? lookFrame : spray ? sprayAge : age;
            // каждый клуб берёт свою долю: по площади кучки в среднем перекрывается столько слоёв — вместе то же пропускание
            // в среднем (SmokeFillTest; середину облака кучка закрывает гуще — прежнее приближение, ревью #443)
            float sub = (float) (1 - Math.pow(1 - Math.min(shown, 0.999), 1 / overlap));
            // плоский клуб, на который смотрят сверху (проекция диска шире его толщи), лежит на горизонтали ({@code lying}):
            // квадрат лицом к камере у слоя пыли у земли стоял бы стеной над ней (кадры F2a: «плита» под шаром в 0,1 с)
            for (int j = 0; j < count; j++) {
                long hj = mix(h + j * 0x632BE59BD9B4E019L);
                // лежащий клуб — на высоте середины слоя: ниже он ушёл бы под рельеф
                double ox = gauss(hj) * spread * rh * 0.5, oy = lying ? 0 : gauss(hj >>> 7) * spread * rw * 0.5, oz = gauss(hj >>> 14) * spread * rh * 0.5;
                // разброс размера — у клубов кучки (разнообразие); одиночный клуб — весь гаусс, без разброса: иначе его
                // площадь, а с ней и вещество на экране, гуляла бы на ±44 %
                double k = count == 1 ? 1 : size * (SIZE_LOW + 2 * (1 - SIZE_LOW) * unit(hj >>> 21));
                double frame = clubFrame(clubAge, unit(hj >>> 28), count > 1 || vary);
                int f0 = (int) frame;
                float last = j == count - 1 ? (float) (many - (count - 1)) : 1;
                boolean mirror = vary ? (hj >>> 40 & 1) == 1 : (j & 1) == 1;
                FxAtlas.Sprite sprite = spray ? FxAtlas.spray6(f0, mirror) : FxAtlas.club(look, f0, mirror);
                // лежащий клуб сверху — освещённый верх слоя: тени карт (они запечены для клуба лицом к камере, свет сбоку и
                // сзади) нет, свет солнца, неба и огня — весь (степень тени 0; свет шаров — в каждом пикселе, fx.fsh); снизу —
                // как обычно. Мягкость у рельефа — путь луча в слое до земли (rw / sin e), не полуразмер
                if (lying) out.puff6Flat(px + ox, py + oy, pz + oz, rh * grow * k, rh * grow * k,
                        Math.min(rh, rw * d / Math.max(Math.abs(py), 1e-3)) * ua.zentix.incoming.client.fx.layer.FxQuads.SOFT, sprite,
                        (float) (frame - f0), py < 0 ? 0 : (spray ? sprayShade : shade), cr, cg, cb, sub * last);
                else out.puff6(px + ox, py + oy, pz + oz, rh * grow * k, rv * grow * k, sprite, (float) (frame - f0), spray ? sprayShade : shade,
                        cr, cg, cb, sub * last);
            }
            return fill;
        }
    }

    /**
     * Лежит ли клуб с осями (σh, σh, σv) на горизонтали, когда глаз выше или ниже его середины на dy при расстоянии d:
     * плоский (σh &gt; 3σv) и видный сверху — проекция диска (σh·sin e) шире его толщи (σv·cos e ≈ σv).
     */
    static boolean lies(double sh, double sv, double dy, double d) {
        return sh > 3 * sv && sh * Math.abs(dy) > sv * d;
    }

    /**
     * σ по вертикали экрана у гаусса с осями (σh, σh, σv), на который смотрят из (dx, dy, dz) от глаза: проекция гаусса —
     * гаусс с осями σh (поперёк, по горизонтали) и √(σv²·cos²e + σh²·sin²e) (e — угол взгляда к горизонту), толща по лучу
     * через середину — масса / (2π·σ₁·σ₂). Плоский клуб (слой пыли у земли: σh в десятки раз больше σv) сверху — круг на
     * земле толщей слоя по лучу, сбоку — полоса; квадрат к камере высотой σv лёг бы тонкой чертой поперёк кадра (кадры
     * шлагбаума F2a: тёмные полосы над землёй вокруг взрыва).
     */
    static double projected(double sh, double sv, double dx, double dy, double dz) {
        double d2 = dx * dx + dy * dy + dz * dz;
        if (!(d2 > 0)) return sv;
        double sin2 = dy * dy / d2;
        return Math.sqrt(sv * sv * (1 - sin2) + sh * sh * sin2);
    }

    /** Уровень дробления отрезка длиной len с наименьшей σ сечения sigma: 2^уровень клубов кладут их через {@link #SEGMENT_STEP}·σ. */
    static double tubeLevel(double len, double sigma) {
        return Math.clamp(Math.log(Math.max(1, len / (SEGMENT_STEP * sigma))) / Math.log(2), 0, TUBE_LEVELS);
    }

    /** Клубов на отрезке уровня level — 2 в этой степени. */
    static int tubeDepth(double level) {
        return (int) Math.ceil(level - 1e-9);
    }

    /** Доля места j (из 2^{@link #tubeDepth}) на уровне level: чётные — целые, нечётные верхнего уровня проявляются с дробью уровня. */
    static double tubeWeight(int j, double level) {
        int k = tubeDepth(level);
        return k > 0 && (j & 1) == 1 ? level - (k - 1) : 1;
    }

    /** Сумма долей мест уровня level: вещество места — его доля от неё. */
    static double tubeTotal(double level) {
        int k = tubeDepth(level);
        return k == 0 ? 1 : (1 << (k - 1)) * (1 + level - (k - 1));
    }

    /** Клубов трубки на отрезок — не больше 2 в этой степени. */
    static final int TUBE_LEVELS = 7;
    /**
     * Запечённый клуб на экране — почти ровный диск: радиус ровного диска с тем же покрытием по хорде через середину
     * (∫ непрозрачности по радиусу / непрозрачность середины, в долях полуразмера квадрата), по кадрам
     * ({@code PlumeContinuityTest} мерит по картинкам): шапка облака сверху — 0,71–0,80. Перекрытие клубов у оси трубки — это
     * покрытие по хорде, а не радиус половины непрозрачности: у кадров с мягким хвостом края тот на 5–8 % меньше, и у оси
     * тонкого следа вблизи выходило 1,4 закона. Не гаусс: гауссы через 1,2σ складываются в ровную трубку, а диски радиусом
     * 0,5–0,9σ (край клуба тонкой трубки — 1,2σ) — в бусы.
     */
    static final double[] PUFF_DISCS = {0.71, 0.72, 0.72, 0.74, 0.75, 0.76, 0.76, 0.76, 0.77, 0.77, 0.77, 0.78, 0.78, 0.79, 0.79, 0.80};
    /** Диск самого узкого кадра. */
    static final double PUFF_DISC_MIN = 0.71;

    /** Диск клуба (доля полуразмера) у кадра frame с дробью. */
    static double puffDisc(double frame) {
        double f = Math.clamp(frame, 0, PUFF_DISCS.length - 1);
        int i = Math.min((int) f, PUFF_DISCS.length - 2);
        return PUFF_DISCS[i] + (PUFF_DISCS[i + 1] - PUFF_DISCS[i]) * (f - i);
    }
    /** Размах размера клубов кучки и трубки: полуразмер × 0,8–1,2. */
    static final double SIZE_LOW = 0.8;

    /**
     * Шаг мест трубки σ с толщей поперёк оси tau: не шире {@link #SEGMENT_STEP}·σ (гауссы вещества сливаются) и не шире
     * диска самого мелкого клуба — каждое место оси под двумя дисками ({@link #PUFF_DISC_MIN} × {@link #SIZE_LOW} × край).
     */
    static double tubeStep(double sigma, double tau) {
        double disc = PUFF_DISC_MIN * SIZE_LOW * edge(tau) * sigma, off = MEANDER_MAX * meander(sigma, tau);
        // ось пересекает диск клуба, сдвинутого блужданием на off, хордой 2√(диск² − off²); шаг — её половина
        return Math.min(SEGMENT_STEP * sigma, Math.sqrt(disc * disc - off * off));
    }

    /**
     * Блуждание середины клуба трубки, м: {@link #TUBE_MEANDER}·σ, но не дальше половины диска самого мелкого клуба от
     * оси (поперёк экрана — до {@link #MEANDER_MAX} блужданий): клубы гуляют каждый сам по себе, и сдвинутый дальше
     * открывал бы ось — тонкая трубка рвалась бы на бусы. ⚠ Блуждание — оценка (вихри следа порядка его ширины).
     */
    static double meander(double sigma, double tau) {
        return Math.min(TUBE_MEANDER * sigma, 0.5 * PUFF_DISC_MIN * SIZE_LOW * edge(tau) * sigma / MEANDER_MAX);
    }

    /** Сдвиг клуба поперёк оси на экране — не больше стольких блужданий: колокол {@link #gauss} до 1,725 по двум осям. */
    static final double MEANDER_MAX = 1.725 * Math.sqrt(2);
    /**
     * Середина клуба трубки гуляет на столько σ: крупные вихри следа — порядка его ширины, и сам след извивается на доли
     * ширины (⚠ оценка).
     */
    static final double TUBE_MEANDER = 0.3;
    /** Клубов в кучке у клуба трубки — не больше: подробность вдоль трубки дают соседние клубы. */
    static final int TUBE_CLUSTER = 3;

    /**
     * Отрезок трубки следа ({@code TrailModel}: гауссово сечение σ, ослабление на метр e — у концов a и b, от глаза) — клубами
     * вдоль оси, как облака: трубка из гауссов того же сечения через ≤ {@link #SEGMENT_STEP}·σ (до {@code 2^}{@link #TUBE_LEVELS}
     * на отрезок, дальше шаг шире), у каждого — вещество своего
     * куска оси (сумма гауссов вдоль оси даёт толщу трубки поперёк середины e/(√(2π)·σ)). Клубов на отрезок — 2^k
     * ({@link #TUBE_LEVELS}): места уровня k — часть мест уровня k + 1, и новые (нечётные) проявляются долей, пока трубка
     * сужается на экране, — без скачков, когда σ растёт.
     *
     * @param wa     ширина трубки у конца a ({@code FarFlightView.puffShare}: на половине непрозрачности), у b — wb: доля
     *               вещества, что рисуют клубы, — у каждого клуба по его ширине и расстоянию (остальное — лента, её доля у
     *               концов — та же); от бюджета мест доля не зависит
     * @param cr     цвет толстого слоя вещества на экране по каналам (cr, cg, cb; {@link #thick}; у следа — серый {@link #tubeColour})
     * @param shade  степень тени карт клуба ({@link #shade}; у следа — {@link #tubeShade})
     * @param sigma0 σ при рождении следа: кадр клуба — по росту σ с него ({@link FxAtlas#puffFrame})
     * @param stride шаг мест — во столько раз шире закона ({@link #tubeStep}; ≥ 1, бюджет мест {@code FarFlightView.CLUB_BUDGET}):
     *               мест на отрезок меньше, вещество отрезка у них то же — каждое стоит за свой, более длинный кусок оси
     * @param tube   трубка (след снаряда, шлейф источника): её места ждут плана кадра ({@link Draw#holdPlace}) и между собой
     *               не сливаются
     * @return мест клубов и вуалей (бюджет клубов трубок); мест кадра уже нет ({@link #PLACES}) — −(мест отрезка + 1): мест не
     *         взято, доли клубов у отрезка в этом кадре нет (бюджет на следующих кадрах растит шаг)
     */
    static int tube(FarView view, Out out, double ax, double ay, double az, double sa, double ea, double wa, double bx, double by, double bz,
                    double sb, double eb, double wb, float cr, float cg, float cb, double shade, double sigma0, long seed, double stride, double shown,
                    long tube) {
        if (ea + eb <= 0 || !(sa > 0) || !(sb > 0)) return 0;
        double tx = bx - ax, ty = by - ay, tz = bz - az, len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (!(len > 0)) return 0;
        // места — не реже, чем у обоих концов диски клубов перекрывают ось вдвое ({@link #tubeStep}), с бюджетом мест — в stride
        // раз реже; край клуба — у всей трубки (лента и клубы рисуют одно вещество, видимая ширина — его), доля клубов — только в
        // непрозрачности
        double step = Math.min(tubeStep(sa, ea / (Math.sqrt(2 * Math.PI) * sa)), tubeStep(sb, eb / (Math.sqrt(2 * Math.PI) * sb))) * stride;
        double level = tubeLevel(len, step / SEGMENT_STEP);
        int k = tubeDepth(level), n = 1 << k;
        // мест кадра больше не взять ({@link #PLACES}): мест у отрезка нет, а его места идут в бюджет клубов трубок, и на следующих
        // кадрах шаг мест растёт
        if (DRAW.placeCount + n > PLACES) {
            DRAW.refused += n;
            return -(n + 1);
        }
        double total = tubeTotal(level);
        int drawn = 0;
        for (int j = 0; j < n; j++) {
            double weight = tubeWeight(j, level);
            if (weight <= 0) continue;
            double u = (double) j / n, sigma = sa + (sb - sa) * u, e = ea + (eb - ea) * u;
            double cx = ax + tx * u, cy = ay + ty * u, cz = az + tz * u;
            double share = FarFlightView.puffShare(wa + (wb - wa) * u, Math.sqrt(cx * cx + cy * cy + cz * cz), view.pixel());
            if (share <= 0) continue;
            // толща трубки поперёк оси — e/(√(2π)σ) (доля клубов — share); место стоит за свой кусок оси len/total (с долей места)
            double full = e / (Math.sqrt(2 * Math.PI) * sigma), tau = share * full, along = len * weight / total;
            if (!(tau > 0) || !(along > 0)) continue;
            double age = FxAtlas.puffFrame(sigma / Math.max(sigma0, 1e-6));
            // зерно — по месту на самом мелком уровне: клуб остаётся тем же, когда уровень меняется
            long h = mix(seed * 0x9E3779B97F4A7C15L + ((long) j << (TUBE_LEVELS - k)));
            // вихри следа — порядка σ: середина клуба гуляет ({@link #meander}, ⚠ оценка), иначе ровный ряд
            double m = meander(sigma, tau);
            drawn += DRAW.holdPlace(view, out, cx + m * gauss(h >>> 3), cy + m * gauss(h >>> 11), cz + m * gauss(h >>> 19), sigma, tau, full, along,
                    cr, cg, cb, shade, age, h, shown, TUBE_CLUSTER, true, tx / len, ty / len, tz / len, tube);
        }
        return drawn;
    }

    /**
     * Цвет вещества следа с альбедо однократного рассеяния albedo (до дельта-Эддингтона, асимметрия {@link Smoke#ASYMMETRY}):
     * двумя потоками после дельта-Эддингтона (f = g²), как у облаков ({@link SmokeGrid}). Толща трубки — полное ослабление,
     * как у ленты и порога видимости следа (Кошмидер): рассеянное вперёд мелкими (~мкм) частицами уходит на десятки градусов
     * и картинку за дымом не несёт.
     */
    static float tubeColour(double albedo) {
        double g = Smoke.ASYMMETRY, f = g * g;
        return thick((1 - f) * albedo / (1 - f * albedo), g / (1 + g));
    }

    /** Степень тени карт клуба у вещества следа с альбедо albedo ({@link #tubeColour}). */
    static double tubeShade(double albedo) {
        double g = Smoke.ASYMMETRY, f = g * g;
        return twoStream((1 - f) * albedo / (1 - f * albedo), g / (1 + g)) / BAKED_K;
    }

    /** Середина гаусса впереди по взгляду, в σ вдоль взгляда: с этого клубы начинают сменять вуаль, с того — сменили. */
    static final double AHEAD_FROM = 1, AHEAD_FULL = 2.5;

    /**
     * Сколько клубы гаусса с серединой (px, py, pz) от глаза и σ (σh, σh, σv) ещё рисуют его, когда глаз внутри его края:
     * квадрат клуба — вещество, спроецированное по лучам из глаза, и он верен, пока вещество перед глазом. Середина впереди
     * на {@link #AHEAD_FULL} σ вдоль взгляда — перед глазом 99 % вещества: клубы; ближе {@link #AHEAD_FROM} σ (сбоку или
     * позади) — вуаль ({@link Veil}). Без этого клубы таяли, как только глаз входил в край гаусса (~3,7σ у густого) — у следа
     * ЗРК в 18 м: клубящаяся трубка, к которой несёт камеру, задолго до неё становилась вуалью.
     */
    static double ahead(FarView view, double px, double py, double pz, double sh, double sv) {
        org.joml.Vector3f f = view.forward();
        double z = px * f.x + py * f.y + pz * f.z, sf = Math.sqrt(sh * sh * (1 - f.y * f.y) + sv * sv * f.y * f.y);
        return smoothstep(AHEAD_FROM * sf, AHEAD_FULL * sf, z);
    }

    /**
     * Сколько видно квадрата клуба с серединой (px, py, pz) от глаза: он лицом к камере, весь на глубине своей середины —
     * позади глаза его нет вовсе, у самого глаза он тает, как в {@code fx.fsh} (с {@link #NEAR_FROM} блока на
     * {@link #NEAR_FADE}). Чего квадрат не показывает, когда глаз в краю клуба, — вуаль, а не ничто: клуб сбоку и сзади
     * иначе терял ту долю вещества, что держал за квадратом.
     */
    static double front(FarView view, double px, double py, double pz) {
        org.joml.Vector3f f = view.forward();
        return Math.clamp((px * f.x + py * f.y + pz * f.z - NEAR_FROM) / NEAR_FADE, 0, 1);
    }

    /** Квадрат слоя у самой камеры тает с этого расстояния, блоков, на столько (как {@code fx.fsh}: ближняя плоскость). */
    static final double NEAR_FROM = 0.3, NEAR_FADE = 1.2;

    /** Свет мира под открытым небом без света блоков (упакованный, для карты освещения). */
    private static final int OPEN_SKY = LightTexture.pack(0, 15);

    /** Вуаль облака вокруг глаза — на столько блоков перед ним (ближе квадраты слоя тают у ближней плоскости). */
    static final double VEIL_AT = 2;
    /** Гаусс, что ни по одному лучу вуали не закрывает больше этой доли, в вуаль не идёт. */
    static final double VEIL_MIN = 0.004;
    /** Чисел на гаусс вуали: середина от глаза (3), σh, σv, ослабление массы, цвет (3), доля. */
    static final int VEIL_FIELDS = 10;
    /**
     * Клеток вуали по каждой оси экрана. Край вещества у глаза на экране — ~σ/расстояние, у следа ЗРК, проходящего сквозь
     * камеру (σ ~5 м), — десятки градусов: клетка в ~13° (поле зрения 70°) его держит, а линейная смесь по клетке гладкая.
     */
    static final int VEIL_CELLS = 12;

    /**
     * Вещество вокруг глаза — вуалью по экрану (клубы его гауссов растаяли: квадрат вплотную к глазу закрыл бы экран краем).
     * Толща — по лучу каждой вершины сетки во весь экран ({@link #VEIL_CELLS}², квадрат в тангенсах ±tg угла до угла экрана,
     * на {@link #VEIL_AT} блока перед глазом) через каждый гаусс ({@link #sightDepth}), непрозрачность вершины —
     * 1 − e^(−Στ), цвет — средний по толще (рассеяние смеси веществ у глаза — доли по толще, как у {@link #thick}); между
     * вершинами — линейно. Прежде вуаль была одним квадратом с толщей по одному лучу — середине экрана: след, проходящий
     * сквозь камеру, ложился ровной белой пеленой на весь кадр (СКО яркости кадра 1,5–3 из 255), хотя вещество у глаза —
     * полоса трубки, гуще вдоль неё и чистая над и под ней (жалоба 24.10, финальный проход 3.7.0, сцена sam-burst).
     */
    static final class Veil {
        /** Вершин по оси. */
        static final int N = VEIL_CELLS + 1;
        /** Полуразмер сетки в тангенсах от середины экрана. */
        double extent;
        final double[] tau = new double[N * N];
        final float[] r = new float[N * N], g = new float[N * N], b = new float[N * N];
        /**
         * Мягкость вершины к миру, блоков: вещество по её лучу — не всё перед миром. Вершина тает, как квадрат слоя у мира
         * ({@code fx.fsh}: × (мир − место вершины) / мягкость), и мир там, где по лучу набирается половина её непрозрачности
         * ({@link #half}), берёт половину вуали: дом перед далёким облаком в вуали в него не тонет, а в густом дыме вокруг
         * глаза стена в нескольких метрах уже почти скрыта. ⚠ Линейный спуск вместо доли толщи перед миром — приближение.
         */
        final float[] soft = new float[N * N];

        /**
         * Где по лучу набирается половина непрозрачности вещества толщей tau, м: вещество — ровно по отрезку со средним по
         * толще расстоянием mean и разбросом spread (СКО, м; отрезок — ±√3·СКО, не ближе глаза), непрозрачность на отрезке
         * — 1 − e^(−tau·x): половина конечной — при x = −ln(1 − (1 − e^(−tau))/2)/tau (у тонкого — середина, у густого —
         * ближе к началу).
         */
        static double half(double tau, double mean, double spread) {
            double from = Math.max(0, mean - Math.sqrt(3) * spread), to = Math.max(from, mean + Math.sqrt(3) * spread);
            double x = tau > 1e-6 ? -Math.log(1 - 0.5 * -Math.expm1(-tau)) / tau : 0.5;
            return from + x * (to - from);
        }
        final org.joml.Vector3f forward = new org.joml.Vector3f(), left = new org.joml.Vector3f(), up = new org.joml.Vector3f();
        private final double[] moments = new double[2];

        /** Тангенс вершины i по оси (влево экрана — по {@code left}, вверх — по {@code up}). */
        double at(int i) {
            return extent * (2.0 * i / (N - 1) - 1);
        }

        /** Номер вершины (i — по {@code left}, j — по {@code up}). */
        static int index(int i, int j) {
            return j * N + i;
        }

        /** Непрозрачность вершины k. */
        float alpha(int k) {
            return (float) (1 - Math.exp(-tau[k]));
        }

        /** Копия (проверки: сетка у приёмника переживает следующий кадр). */
        Veil copy() {
            Veil c = new Veil();
            c.extent = extent;
            System.arraycopy(tau, 0, c.tau, 0, tau.length);
            System.arraycopy(r, 0, c.r, 0, r.length);
            System.arraycopy(g, 0, c.g, 0, g.length);
            System.arraycopy(b, 0, c.b, 0, b.length);
            System.arraycopy(soft, 0, c.soft, 0, soft.length);
            c.forward.set(forward);
            c.left.set(left);
            c.up.set(up);
            return c;
        }

        /** Есть ли что рисовать. */
        boolean any() {
            for (double t : tau) if (-Math.expm1(-t) >= 0.002) return true;
            return false;
        }

        /** Место вершины (i, j) от глаза, ось {@code axis} (0 — x, 1 — y, 2 — z): на {@link #VEIL_AT} блока по взгляду. */
        double place(int i, int j, int axis) {
            double u = at(i), v = at(j);
            return switch (axis) {
                case 0 -> VEIL_AT * (forward.x + u * left.x + v * up.x);
                case 1 -> VEIL_AT * (forward.y + u * left.y + v * up.y);
                default -> VEIL_AT * (forward.z + u * left.z + v * up.z);
            };
        }

        /**
         * Пропускание вуали в точке экрана (u, v) в тангенсах — как её рисует видеокарта: непрозрачность линейно между
         * вершинами клетки (проверки).
         */
        double through(double u, double v) {
            double fu = Math.clamp((u + extent) / (2 * extent) * (N - 1), 0, N - 1 - 1e-9), fv = Math.clamp((v + extent) / (2 * extent) * (N - 1), 0, N - 1 - 1e-9);
            int i = (int) fu, j = (int) fv;
            double du = fu - i, dv = fv - j;
            double a = (1 - du) * (1 - dv) * alpha(index(i, j)) + du * (1 - dv) * alpha(index(i + 1, j)) + (1 - du) * dv * alpha(index(i, j + 1))
                    + du * dv * alpha(index(i + 1, j + 1));
            return 1 - a;
        }

        /** Сетка кадра view по гауссам вуали (count штук по {@link #VEIL_FIELDS} чисел в g). */
        void fill(FarView view, double[] gs, int count) {
            forward.set(view.forward());
            left.set(view.left());
            up.set(view.up());
            // весь кадр при любом соотношении сторон (и у вертикального окна): круг до угла экрана, с запасом на кадр вывода
            // другого размера, чем окно (Flashback)
            extent = Math.min(Math.tan(Math.min(view.edge(), 1.45)) * 1.2, 8);
            java.util.Arrays.fill(tau, 0);
            java.util.Arrays.fill(r, 0);
            java.util.Arrays.fill(g, 0);
            java.util.Arrays.fill(b, 0);
            java.util.Arrays.fill(soft, 0);
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    double u = at(i), v = at(j);
                    double rx = forward.x + u * left.x + v * up.x, ry = forward.y + u * left.y + v * up.y, rz = forward.z + u * left.z + v * up.z;
                    double n = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    rx /= n;
                    ry /= n;
                    rz /= n;
                    int k = index(i, j);
                    double sr = 0, sg = 0, sb = 0, sum = 0, far = 0, far2 = 0;
                    for (int c = 0; c < count; c++) {
                        int o = c * VEIL_FIELDS;
                        double along = sightDepth(gs[o], gs[o + 1], gs[o + 2], gs[o + 3], gs[o + 4], gs[o + 5], rx, ry, rz);
                        // доля гаусса — у непрозрачности его вещества по лучу, как у растаявших клубов
                        double a = -Math.expm1(-along) * gs[o + 9];
                        if (!(a > 1e-6)) continue;
                        double t = -Math.log1p(-Math.min(a, 0.999));
                        sum += t;
                        sr += t * gs[o + 6];
                        sg += t * gs[o + 7];
                        sb += t * gs[o + 8];
                        sightMoments(gs[o], gs[o + 1], gs[o + 2], gs[o + 3], gs[o + 4], rx, ry, rz, moments);
                        far += t * moments[0];
                        far2 += t * (moments[1] + moments[0] * moments[0]);
                    }
                    tau[k] = sum;
                    if (sum > 0) {
                        r[k] = (float) (sr / sum);
                        g[k] = (float) (sg / sum);
                        b[k] = (float) (sb / sum);
                        double mean = far / sum, spread = Math.sqrt(Math.max(0, far2 / sum - mean * mean));
                        soft[k] = (float) Math.max(VEIL_SOFT_MIN, 2 * (half(sum, mean, spread) - VEIL_AT * n));
                    }
                }
            }
        }
    }

    /**
     * Вещество гаусса вокруг глаза (центр (px, py, pz) от глаза, σ по горизонтали и вертикали, ослабление всей массы ext,
     * м²): клубы его растаяли — оно в вуаль кадра ({@link Veil}: толща по лучу каждой её вершины, {@link #sightDepth}). Тот же
     * цвет вещества, что у клубов; свет — как у вещества под открытым небом (карта освещения: небо 15) и огонь кадра у глаза
     * (FxLights), как у клубов этого же вещества снаружи. Доля share — сколько клубов растаяло.
     */
    private static void veil(double px, double py, double pz, double sh, double sv, double ext, double ks, double kd, double kw, float dr,
                             float dg, float db, double share) {
        float cr = thick(ks, kd, kw, dr), cg = thick(ks, kd, kw, dg), cb = thick(ks, kd, kw, db);
        DRAW.veilAdd(DRAW.out, DRAW.view, px, py, pz, sh, sv, ext, cr, cg, cb, share);
    }

    /**
     * Толща по лучу взгляда из глаза (направление f, единичное) через гаусс с осями (σh, σh, σv), центр — (px, py, pz) от
     * глаза, ослабление всей массы ext (м²): в пространстве, где гаусс — шар единичного σ, луч идёт с шагом |f'| =
     * √((fx² + fz²)/σh² + fy²/σv²), и толща — ext/((2π)^{3/2}σh²σv)·√(π/2)/|f'|·e^(−b'²/2)·(1 + erf(a'/√2)), a' — путь до
     * ближайшей к середине точки луча, b' — её расстояние до середины (обе — в σ). У шара — прежняя формула вуали.
     */
    static double sightDepth(double px, double py, double pz, double sh, double sv, double ext, double fx, double fy, double fz) {
        // вуаль зовёт это на каждую вершину сетки и гаусс у глаза: постоянные — по разу
        double ux = fx / sh, uy = fy / sv, uz = fz / sh, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (!(len > 0)) return 0;
        ux /= len;
        uy /= len;
        uz /= len;
        // середина от глаза в σ: луч из глаза — t·u, ближайшая к середине точка — t = a'
        double cx = px / sh, cy = py / sv, cz = pz / sh;
        double a = cx * ux + cy * uy + cz * uz, b2 = Math.max(0, cx * cx + cy * cy + cz * cz - a * a);
        // луч дальше 10σ от середины — e^(−50): у самого густого клуба толща миллиардные, erf не нужен
        if (b2 > 100) return 0;
        return ext * SIGHT / (sh * sh * sv * len) * Math.exp(-0.5 * b2) * (1 + SmokeGrid.erf(a * SQRT_HALF));
    }

    /**
     * Где на луче из глаза f вещество гаусса (как у {@link #sightDepth}), м: вдоль луча оно — гаусс с серединой в ближайшей к
     * середине гаусса точке луча a' (в σ луча) и единичной σ, от глаза — усечённый: среднее — a' + λ, дисперсия — 1 − λ(λ + a'),
     * λ = φ(a')/Φ(a') (в метрах — делённые на |f'| и |f'|²). В out — среднее и дисперсия.
     */
    static void sightMoments(double px, double py, double pz, double sh, double sv, double fx, double fy, double fz, double[] out) {
        double ux = fx / sh, uy = fy / sv, uz = fz / sh, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        out[0] = out[1] = 0;
        if (!(len > 0)) return;
        double a = (px / sh * ux + py / sv * uy + pz / sh * uz) / len;
        double below = 0.5 * (1 + SmokeGrid.erf(a * SQRT_HALF));
        // далеко за спиной: λ → −a − 1/a, вещество — у самого глаза
        double lambda = below > 1e-12 ? Math.exp(-0.5 * a * a) / Math.sqrt(2 * Math.PI) / below : -a - 1 / a;
        out[0] = Math.max(0, a + lambda) / len;
        out[1] = Math.max(0, 1 - lambda * (lambda + a)) / (len * len);
    }

    /** Постоянная толщи по лучу: √(π/2)/(2π)^{3/2}; √½. */
    private static final double SIGHT = Math.sqrt(Math.PI / 2) / Math.pow(2 * Math.PI, 1.5), SQRT_HALF = Math.sqrt(0.5);

    /** Видимый край гаусса толщи τ₀ поперёк середины, в σ: где толща падает до {@link #EDGE_TAU}. */
    static double edge(double tau) {
        return Math.clamp(Math.sqrt(2 * Math.max(0, Math.log(Math.max(tau, 1e-6) / EDGE_TAU))), 1.2, 4.0);
    }

    /**
     * Цвет толстого слоя смеси на экране (один канал): альбедо ω' и асимметрия g' — по долям ослабления сажи, пыли
     * (альбедо {@code dustW}) и капель; отражение полубесконечного слоя двух потоков R∞ = (1 − s)/(1 + s),
     * s = √((1 − ω')/(1 − ω'g')) (замыкание по средним полусфер; у Эддингтона s больше в 2/√3 раза, и у сажи — ω' ≈ 0,2 —
     * отражение выходило бы отрицательным, сажа — чёрной дырой); на экран — степень 1/2,2. Обращение — {@link SmokeGrid#dustAlbedo}.
     */
    static float thick(double soot, double dust, double water, float dustW) {
        double sum = soot + dust + water;
        double w = (soot * SmokeGrid.SOOT_W + dust * dustW + water * SmokeGrid.DROP_W) / sum;
        double g = (soot * SmokeGrid.SOOT_G + dust * SmokeGrid.DUST_G + water * SmokeGrid.DROP_G) / sum;
        return thick(w, g);
    }

    /** Цвет толстого слоя вещества с альбедо ω' и асимметрией g' (после дельта-Эддингтона), один канал. */
    static float thick(double w, double g) {
        double u = Math.sqrt(Math.max((1 - w) / (1 - w * g), 0));
        return (float) Math.pow(Math.max(0, (1 - u) / (1 + u)), 1 / 2.2);
    }

    /**
     * Альбедо и асимметрия вещества, с которыми запечены карты клуба дыма и клуба брызг ({@code tools/bake_fx/bake.py}:
     * {@code PUFF_ALBEDO}, {@code PUFF_G}, {@code SPRAY_ALBEDO}, {@code SPRAY_G}).
     */
    static final double BAKED_W = 0.55, BAKED_G = 0.55, SPRAY_W = 0.99, SPRAY_G = 0.85;
    /** Затухание света в толще k двух потоков у вещества запекания дыма и брызг (после дельта-Эддингтона, как у {@link SmokeGrid}). */
    static final double BAKED_K = deltaK(BAKED_W, BAKED_G), SPRAY_K = deltaK(SPRAY_W, SPRAY_G);

    /** k двух потоков вещества с альбедо w и асимметрией g после дельта-Эддингтона (f = g²). */
    static double deltaK(double w, double g) {
        double f = g * g;
        return twoStream((1 - f) * w / (1 - f * w), g / (1 + g));
    }

    /** Затухание рассеянного света в толще, на единицу толщи: k = √(3(1 − ω')(1 − ω'g')) (два потока Эддингтона). */
    static double twoStream(double w, double g) {
        return Math.sqrt(Math.max(3 * (1 - w) * (1 - w * g), 0));
    }

    /**
     * Затухание света в толще смеси, k двух потоков; степень тени карт — k смеси / k вещества запекания этого клуба
     * ({@link #BAKED_K} у дыма, {@link #SPRAY_K} у брызг). Карты клуба — свет, прошедший его толщу τ (≈ e^(−kτ) против
     * среднего по клубу), поэтому у смеси с другим альбедо карта берётся в этой степени ({@code fx.fsh}): белый дым окиси алюминия и капли (ω' ≈ 0,999) почти не дают тени — светлые насквозь,
     * как облако; сажа (ω' ≈ 0,2) — темнее запечённого клуба. ⚠ Приближение: путь света в клубе один и тот же, меняется
     * только затухание на нём. Доли — по ослаблению, как у {@link #thick}.
     */
    static double shade(double soot, double dust, double water, float dustW) {
        double sum = soot + dust + water;
        if (!(sum > 0)) return BAKED_K;
        double w = (soot * SmokeGrid.SOOT_W + dust * dustW + water * SmokeGrid.DROP_W) / sum;
        double g = (soot * SmokeGrid.SOOT_G + dust * SmokeGrid.DUST_G + water * SmokeGrid.DROP_G) / sum;
        return twoStream(w, g);
    }

    /** Ячейка мира слияния у клуба в (x, y, z) с краем r (м): размер — cell·2^октава края; kind — вещество (не смешиваются). */
    static long cell(double x, double y, double z, double r, double cell, int kind) {
        int octave = Math.getExponent(Math.max(r, 1e-3));
        double size = Math.scalb(cell, octave);
        return mix(mix(mix(mix(kind * 4096L + octave) + (long) Math.floor(x / size)) + (long) Math.floor(y / size)) + (long) Math.floor(z / size));
    }

    /**
     * Группы слитых мест трубок: у группы (номер — место, что её держит) — все трубки её мест, и между уровнями слияния тоже.
     * Две группы сливаются, только если общих трубок нет: держатель группы хранит одну трубку, и без состава группы A+B и
     * группа B+A следующего уровня слились бы — места одной трубки в одном клубе.
     */
    static final class TubeGroups {
        private long[] tube = new long[64];
        private int[] next = new int[64], tail = new int[64];
        private final Cells owners = new Cells();

        void reset() {
            owners.clear();
        }

        /** Место p трубки t — группа из одного. */
        void add(int p, long t) {
            if (p >= tube.length) {
                int n = Math.max(2 * tube.length, p + 1);
                tube = java.util.Arrays.copyOf(tube, n);
                next = java.util.Arrays.copyOf(next, n);
                tail = java.util.Arrays.copyOf(tail, n);
            }
            tube[p] = t;
            next[p] = -1;
            tail[p] = p;
            owners.put(key(p, t), p);
        }

        /** Нет ли у групп a и b общей трубки. */
        boolean disjoint(int a, int b) {
            for (int m = b; m >= 0; m = next[m]) if (owners.get(key(a, tube[m])) >= 0) return false;
            return true;
        }

        /** Группа b — в группу a. */
        void join(int a, int b) {
            for (int m = b; m >= 0; m = next[m]) owners.put(key(a, tube[m]), a);
            next[tail[a]] = b;
            tail[a] = tail[b];
        }

        private static long key(int group, long t) {
            return mix(mix(group + 0x5851F42D4C957F2DL) ^ t);
        }
    }

    /** Ключ → номер, открытой адресацией (ячейки слияния без объектов на кадр). Номера — не меньше нуля. */
    private static final class Cells {
        private long[] keys = new long[1024];
        private int[] values = new int[1024];
        private int size;

        Cells() {
            java.util.Arrays.fill(values, -1);
        }

        void clear() {
            if (size == 0) return;
            java.util.Arrays.fill(values, -1);
            size = 0;
        }

        /** Номер у ключа; −1 — нет. */
        int get(long key) {
            int mask = keys.length - 1;
            for (int i = (int) mix(key) & mask; ; i = i + 1 & mask) {
                if (values[i] < 0) return -1;
                if (keys[i] == key) return values[i];
            }
        }

        void put(long key, int value) {
            if (2 * (size + 1) > keys.length) grow();
            int mask = keys.length - 1;
            for (int i = (int) mix(key) & mask; ; i = i + 1 & mask) {
                if (values[i] < 0) {
                    keys[i] = key;
                    values[i] = value;
                    size++;
                    return;
                }
                if (keys[i] == key) {
                    values[i] = value;
                    return;
                }
            }
        }

        private void grow() {
            long[] k = keys;
            int[] v = values;
            keys = new long[2 * k.length];
            values = new int[2 * k.length];
            java.util.Arrays.fill(values, -1);
            size = 0;
            for (int i = 0; i < k.length; i++) if (v[i] >= 0) put(k[i], v[i]);
        }
    }

    /** Перемешивание битов (splitmix64): своё случайное у каждого клуба без объектов на кадр. */
    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /** Доля 0..1 из младших бит. */
    private static double unit(long h) {
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /** Колокол без хвостов из трёх долей (клуб не улетает от середины). */
    private static double gauss(long h) {
        return (unit(h) + unit(h >>> 24) + unit(h >>> 40) - 1.5) * 1.15;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
