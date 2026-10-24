package ua.zentix.incoming.client.far;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.PuffOptics;
import ua.zentix.incoming.client.fx.plume.Smoke;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * Облака клиента квадратами слоя эффектов — на любой дальности, вблизи и вдали одинаково: клуб модели (гаусс массы
 * вещества от источника {@link SmokeGrid.Source}: облако взрыва, облако старта, дым пожара) — кучка запечённых клубов
 * ({@link FxAtlas#puff6}: симуляция газа в Blender, шестисторонний свет, векторы движения между кадрами).
 * <ul>
 * <li><b>Вещество.</b> Квадраты складываются, как толщи: клуб рисуется толщей своего кадра × s ({@code fx.fsh}:
 * 1 − (1 − α)^s), и сколько бы клубов ни перекрывалось, сквозь них видно e^(−Στ). Клуб, что несёт гаусс (σ на экране,
 * толща середины τ₀ = Σκ'm/(2πσhσv)), — полуразмером c·σ и толщей s ({@link PuffOptics}): у него то же вещество
 * (Σ τ по экрану — ослабление всей массы) и одиночным он закрывает столько же (Σ (1 − e^(−τ))), сколько гаусс модели;
 * форма, край и клубы — кадра симуляции.</li>
 * <li><b>Кучка.</b> Крупный на экране гаусс — кучка до {@value #CLUSTER} клубов (чем крупнее, тем их больше): у каждого
 * σ·q и 1/many вещества, середины разбросаны с σ·√(1 − q²) — у кучки те же масса и вторые моменты (как у слияния пуфов,
 * {@link #merge}).</li>
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
        /**
         * Запечённый клуб: a — видимая доля квадрата (дымка, рельеф, пятно мельче пикселя), tau — множитель толщи его кадра
         * ({@link PuffOptics}).
         */
        void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                   float b, float a, double tau);

        void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix, float shade,
                       float r, float g, float b, float a, double tau);

        void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a, int light);

        void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a);

        default double veilAspect() { return 16.0 / 9; }

        /** Optional screen transport mesh; receivers without it keep the legacy central-ray diagnostic. */
        default boolean veilField(FarView view, double width, double height, float[] rgba) { return false; }

        boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                       float alphaB, int lightB, float r, float g, float b, float[] joint);
    }

    /** Чисел на ждущий клуб: место, σh, σv, сажа, пыль, капли, альбедо пыли (3), номер, источник. */
    static final int FIELDS = 13;
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
                    double dx = held[b] - held[a], dy = held[b + 1] - held[a + 1], dz = held[b + 2] - held[a + 2];
                    double h = reach * Math.min(held[a + 3], held[b + 3]), v = reach * Math.min(held[a + 4], held[b + 4]);
                    if (dx * dx + dz * dz > h * h || Math.abs(dy) > v) continue;
                    double kd = SmokeGrid.dustK((float) held[a + 8], (float) held[a + 9], (float) held[a + 10]);
                    double wa = sootK * held[a + 5] + kd * held[a + 6] + dropK * held[a + 7], wb = sootK * held[b + 5] + kd * held[b + 6] + dropK * held[b + 7];
                    double w = wa + wb;
                    if (!(w > 0)) {
                        gone[j] = true;
                        continue;
                    }
                    double fa = wa / w, fb = wb / w;
                    // вид источника (кадр клуба, зерно, видимость) — у того, чьего вещества больше
                    if (wb > wa) {
                        held[a + 11] = held[b + 11];
                        held[a + 12] = held[b + 12];
                    }
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
                    held[a + 6] += held[b + 6];
                    held[a + 7] += held[b + 7];
                    gone[j] = true;
                    again = true;
                }
            }
        }
    }

    /**
     * Бюджет дорогой заливки клубов — четыре экрана настоящих пикселей. Размеры и массы остаются моделями;
     * `PuffTarget` выбирает уменьшенную цель по рамкам готовых квадратов (GPU Gems 3 ch.23). Здесь непрерывно
     * ограничивается лишь лишняя подробность кучки, с запасом до CLUSTER·clubShare(CLUSTER)² площадей одиночных клубов (оценка подачи).
     */
    static final double FILL_BUDGET = ua.zentix.incoming.client.fx.layer.PuffTarget.BUDGET;
    /** Бюджет, по которому кадр строится ({@link #FILL_BUDGET}; проверки сравнивают с кадром без бюджета). */
    static double budget = FILL_BUDGET;

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
     * Клубов на гаусс — не больше; по одному на столько пикселей видимого радиуса на экране (кадр клуба — 128 пикселей:
     * крупнее он мылится), мельче — один клуб.
     */
    static final int CLUSTER = 12;
    static final double CLUSTER_PIXELS = 40;
    /** Клуб кучки — не больше этой доли видимого радиуса гаусса. */
    static final double MAX_SHARE = 0.8;
    /** Клубы отрезка — через столько σ (меньшей из двух). */
    static final double SEGMENT_STEP = 1.2;
    /** Первые кадры симуляции — плотный круглый выброс: на квадрате вышел бы шар. Кадры — с этого. */
    static final int FIRST_FRAME = 3;

    private static final Draw DRAW = new Draw();

    private SmokePuffs() {}

    /** Облака, которых объём не рисует, — в кадр. */
    static void collect(FarView view, FarSprites out) {
        double seconds = SmokeGrid.seconds(view.partial());
        SmokeGrid.quads(s -> s.emit(seconds, sink(view, out, s, seconds)));
    }

    /**
     * Ждущие клубы всех источников кадра (слияние {@link #merge}, бюджет {@link #FILL_BUDGET}) и вуаль — в кадр. Зовёт тот,
     * кто брал {@link #sink}, когда все источники кадра отданы (и следы снарядов, {@code FarFlightView}: их кучки и вуали —
     * в том же кадре).
     */
    static void flush() {
        DRAW.flush();
    }

    /** Заливка последнего кадра кучками облаков, экранов, и доля кучки, что ей дал бюджет (замер, строки описания). */
    static double lastFill() {
        return DRAW.lastFill;
    }

    static double lastShare() {
        return DRAW.lastShare;
    }

    static double lastFloor() {
        return DRAW.lastFloor;
    }

    /** Бюджет заново: доля кучек трубок — полная (проверки, что строят кадры с разным бюджетом подряд). */
    static void resetBudget() {
        DRAW.tubeShare = 1;
        DRAW.plans = 0;
    }

    /** Выход из мира, смена измерения: ждущие клубы, план, вуаль и доля трубок — заново. */
    static void reset() {
        DRAW.holding = DRAW.from = DRAW.contexts = 0;
        java.util.Arrays.fill(DRAW.ctxSource, null);
        java.util.Arrays.fill(DRAW.ctxOut, null);
        java.util.Arrays.fill(DRAW.ctxView, null);
        java.util.Arrays.fill(DRAW.planView, null);
        DRAW.veilClear();
        resetBudget();
    }

    /**
     * Доля σ гаусса у клуба кучки из many клубов (одиночный — весь гаусс): чем больше клубов, тем они мельче, их середины
     * разбросаны с σ·√(1 − q²) — у кучки та же σ.
     */
    static double clubShare(double many) {
        return Math.ceil(many - 1e-9) <= 1 ? 1 : Math.min(MAX_SHARE, 1.6 / Math.sqrt(many));
    }

    /**
     * Место клуба j кучки из many (с дробью) в долях σ по двум осям: «подсолнух» (Фогель 1979) — радиус 2·spread·√((j + ½)/many),
     * угол — золотой, повёрнутый на turn. Середины лежат ровно, без случайных скоплений и просветов (у случайных мест
     * той же дисперсии комья пропускали больше света, чем гаусс той же массы: неравенство Йенсена, части кадра налёта
     * светлее модели до 0,35), дисперсия по каждой оси — spread²; новый клуб проявляется снаружи, места сдвигаются плавно.
     */
    static double[] sunflower(int j, double many, double turn, double spread) {
        double r = 2 * spread * Math.sqrt(Math.min(1, (j + 0.5) / many)), a = turn + j * GOLDEN;
        SUN[0] = r * Math.cos(a);
        SUN[1] = r * Math.sin(a);
        return SUN;
    }

    private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));
    private static final double[] SUN = new double[2];

    /**
     * Во сколько раз квадрат клуба (в плоскости экрана) с серединой (px, py, pz) от глаза на расстоянии d шире гаусса на
     * его расстоянии: шар под углом θ к оси взгляда ложится на экран эллипсом, вытянутым по радиусу в 1/cos θ раз (площадь —
     * в 1/cos θ больше, чем у квадрата в плоскости экрана на той же глубине), — квадрат шире в 1/√cos θ: та же площадь, то
     * же вещество и та же тень на экране (⚠ форма — круг той же площади, не эллипс: повёрнутый квадрат сбил бы оси
     * шестистороннего света). У края кадра 70° (θ ~ 40°) без этого клубы закрывали на 20–25 % меньше модели.
     */
    static double offAxis(FarView view, double px, double py, double pz, double d) {
        double cos = (px * view.forward().x + py * view.forward().y + pz * view.forward().z) / Math.max(d, 1e-9);
        return 1 / Math.sqrt(Math.clamp(cos, 0.3, 1));
    }

    /**
     * Видны ли комья кучки у гаусса с толщей середины tau0 (0…1): ком против просвета рядом — разница непрозрачности до
     * 1 − e^(−τ₀); ниже порога контраста {@link Sight#THRESHOLD} её не видно, и кучка — один клуб (тот же вид, заливка
     * впятеро-вдесятеро меньше: старый тонкий дым налёта — десятки экранов почти прозрачных квадратов); видны вполне — с
     * {@link #CLUMPS_FULL} порогов (⚠ оценка), между — плавно.
     */
    static double clumpsSeen(double tau0) {
        double a = -Math.expm1(-tau0);
        return Math.clamp((a - Sight.THRESHOLD) / ((CLUMPS_FULL - 1) * Sight.THRESHOLD), 0, 1);
    }

    /** The half-density surface of the 3D Gaussian, exp(-q²/2)=1/2; not its projected, optically thin halo. */
    static final double CORE_EDGE = Math.sqrt(2 * Math.log(2));

    /** Veil only inside the Gaussian's concentration core; transparent bake padding never defines eye entry. */
    static double outside(double px, double py, double pz, double sh, double sv) {
        return smoothstep(0.5, 1, Math.sqrt((px * px + pz * pz) / (sh * sh) + py * py / (sv * sv)) / CORE_EDGE);
    }

    static final double CLUMPS_FULL = 5;

    /** σ колокола {@link #gauss}: три доли (√(3/12)·1,15). */
    static final double GAUSS_SD = 0.5 * 1.15;

    /**
     * Сколько экранов в полном разрешении закроет кучка гаусса с σ на экране (sa поперёк, sb вверх; м на его расстоянии) и толщей середины tau0
     * в кадре frame, с полным числом клубов {@code most} при доле {@code s} (кучка — между одним клубом и полной): клубов
     * many = 1 + (most − 1)·s, у каждого — σ·q ({@link #clubShare}) и 1/many вещества, полуразмер — по {@link PuffOptics};
     * сумма их квадратов — доля экрана под краем кучки ({@link #cover}: середины разбросаны на ±2σ·√(1 − q²)) × перекрытие.
     */
    static double clusterFill(FarView view, double px, double py, double pz, double sa, double sb, double tau0, double frame, double most,
                              double s, PuffOptics optics) {
        double many = 1 + (most - 1) * s, q = clubShare(many);
        double[] c = FILL_CLUB;
        optics.club(frame, tau0 / (many * q * q), c);
        double club = c[0] * q, reach = club + 2 * Math.sqrt(1 - q * q);
        return many * (club / reach) * (club / reach) * cover(view, px, py, pz, sa * reach, sb * reach);
    }

    private static final double[] FILL_CLUB = new double[2], CLUB = new double[2];

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
            double smoke = glow ? 1 - CloudModel.glowShare(kelvin, view.skyLuminance()) : 1;
            sootA *= smoke;
            sootB *= smoke;
            dustA *= smoke;
            dustB *= smoke;
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
            // World-source matter uses the same delta-scaled extinction as SmokeGrid and cloud Gaussians.
            // The full coefficient of the independent flight-trail model must not increase a fire's rendered density.
            double kd = SmokeGrid.dustK(albedoR, albedoG, albedoB);
            double ea = SmokeGrid.SOOT_K * sootA + kd * dustA, eb = SmokeGrid.SOOT_K * sootB + kd * dustB;
            double ks = SmokeGrid.SOOT_K * (sootA + sootB), kdust = kd * (dustA + dustB);
            Vec3 cam = view.camera();
            end(a, ax - cam.x, ay - cam.y, az - cam.z, Math.sqrt(sha * sva), ea);
            end(b, bx - cam.x, by - cam.y, bz - cam.z, Math.sqrt(shb * svb), eb);
            // цвет и тень — толстого слоя смеси, как у клубов облаков ({@link #thick}); лента — тем же цветом в тоне клуба
            float cr = thick(ks, kdust, 0, albedoR), cg = thick(ks, kdust, 0, albedoG), cb = thick(ks, kdust, 0, albedoB);
            float tone = FarFlightView.TONE;
            double shade = shade(ks, kdust, 0, albedoG) / BAKED_K;
            int drawn = FarFlightView.line(view, out, a, b, cr * tone, cg * tone, cb * tone, cr, cg, cb, shade, sigma0,
                    mix(seed * 0x9E3779B97F4A7C15L + at), joint);
            if (drawn != FarFlightView.RIBBON) joint[0] = Float.NaN;
        }

        /** Конец трубки (от камеры): ширина на половине непрозрачности середины и лента — как у следа ({@code TrailModel.width}). */
        private void end(FarFlightView.Tube t, double dx, double dy, double dz, double sigma, double e) {
            double tau = e / (Math.sqrt(2 * Math.PI) * sigma), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double width = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma);
            FarFlightView.ribbon(width, d, view.pixel(), point);
            float alpha = width > 0 ? (float) ((1 - Math.exp(-tau)) * point[1] * view.air(d, dy)) : 0;
            t.set(dx, dy, dz, sigma, e, width, point[0], alpha, OPEN_SKY);
        }

        @Override
        public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                            double water, float dustR, float dustG, float dustB, double kelvin, double rise) {
            int at = index++;
            // светящийся газ — тело огня у источника; остывая, он проступает дымом той же долей, что тело тает
            double smoke = glow ? 1 - CloudModel.glowShare(kelvin, view.skyLuminance()) : 1;
            if (smoke <= 0 || soot + dust + water <= 0 || !(sh > 0) || !(sv > 0)) return;
            soot *= smoke;
            dust *= smoke;
            water *= smoke;
            double len = 2 * Math.sqrt(ax * ax + ay * ay + az * az);
            if (len <= 0) {
                hold(x, y, z, sh, sv, soot, dust, water, dustR, dustG, dustB, at * 16);
                return;
            }
            int n = (int) Math.min(12, Math.max(1, Math.ceil(len / (SEGMENT_STEP * Math.min(sh, sv)))));
            for (int k = 0; k < n; k++) {
                double f = n == 1 ? 0 : (k + 0.5) / n * 2 - 1;
                blob(x + ax * f, y + ay * f, z + az * f, sh, sv, soot / n, dust / n, water / n, dustR, dustG, dustB, at * 16 + k);
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
        /** Вуаль кадра: толща и цвет, взвешенный толщей (все вуали — одним квадратом, {@link #veilAdd}). */
        private double veilTau, veilR, veilG, veilB;
        private VeilField veilField;
        @Nullable
        private Out veilOut;
        private FarView veilView;
        /**
         * Заливка прошлого кадра, экранов, её пол (по одному клубу на гаусс: меньше бюджет не берёт — это уже толща облаков,
         * а не подробность) и доля кучек, что ему досталась (для замера).
         */
        double lastFill, lastFloor, lastShare = 1;
        /** Доля кучки трубок ({@link #club}) — посчитанная в конце прошлого кадра. */
        double tubeShare = 1;
        /** Режим прохода {@link #blob}: план (только цена) или квадраты с долей кучки {@link #share}. */
        private boolean planning;
        private double share = 1;

        /** Клуб-точку источника — в ожидание: слияние с соседями и бюджет — в {@link #flush}. */
        private void hold(double x, double y, double z, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                          int id) {
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
         * Кадр: ждущие клубы всех источников — квадратами, с бюджетом заливки ({@link #FILL_BUDGET}): первый проход считает,
         * сколько экранов закроют кучки, второй рисует, уменьшив число клубов в кучках во столько, чтобы заливка уложилась
         * (каждая кучка — с той же непрозрачностью середины). Вуали — одним квадратом.
         */
        void flush() {
            close();
            int n = holding;
            // план: у каждой кучки — её вид, место и края на экране и полное число клубов (проход плана, {@link #planCluster});
            // кучки трубок уже в плане (их кладёт сам источник)
            planning = true;
            share = 1;
            for (int i = 0; i < n; i++) if (!merged[i]) held(i);
            planning = false;
            // доля кучки s — у облаков и трубок одна: заливка при s по настоящей кривой каждой кучки ({@link #clusterFill}),
            // подбор делением; трубки рисуются с долей прошлого кадра, их план — к следующему
            double s = 1, whole = planned(1), floor = planned(0);
            // Запас подробности над одиночным клубом — по сумме площадей полной кучки (подача, оценка). Непрерывный target:
            // ступени разрешения не должны переставлять комья. Четыре экрана пикселей удерживает PuffTarget.
            double target = Math.max(budget, CLUSTER * clubShare(CLUSTER) * clubShare(CLUSTER) * floor);
            if (whole > target) {
                double lo = 0, hi = 1;
                for (int it = 0; it < 10; it++) {
                    double mid = 0.5 * (lo + hi);
                    if (planned(mid) > target) hi = mid;
                    else lo = mid;
                }
                s = lo;
            }
            share = s;
            for (int i = 0; i < n; i++) if (!merged[i]) held(i);
            lastFill = planned(s) - plannedTubes(s) + plannedTubes(tubeShare);
            lastFloor = whole > budget ? floor : planned(0);
            lastShare = s;
            tubeShare = s;
            plans = 0;
            share = 1;
            holding = from = contexts = 0;
            java.util.Arrays.fill(ctxSource, null);
            java.util.Arrays.fill(ctxOut, null);
            java.util.Arrays.fill(ctxView, null);
            java.util.Arrays.fill(planView, null);
            veilFlush();
        }

        /**
         * Кучки кадра (облаков и трубок): вид, место от глаза, σ на экране (м), толща середины, кадр клуба, полное число клубов
         * (у трубки — со знаком минус), брызги ли.
         */
        private FarView[] planView = new FarView[64];
        private double[] plan = new double[64 * PLAN];
        private boolean[] planSpray = new boolean[64];
        private int plans;
        private static final int PLAN = 8;

        /** Кучка в план кадра. */
        void planCluster(FarView view, double px, double py, double pz, double sa, double sb, double tau0, double frame, double most, boolean spray,
                         boolean tube) {
            if (plans == planView.length) {
                planView = java.util.Arrays.copyOf(planView, 2 * plans);
                plan = java.util.Arrays.copyOf(plan, 2 * plans * PLAN);
                planSpray = java.util.Arrays.copyOf(planSpray, 2 * plans);
            }
            planView[plans] = view;
            planSpray[plans] = spray;
            int o = PLAN * plans++;
            plan[o] = px;
            plan[o + 1] = py;
            plan[o + 2] = pz;
            plan[o + 3] = sa;
            plan[o + 4] = sb;
            plan[o + 5] = tau0;
            plan[o + 6] = frame;
            plan[o + 7] = tube ? -most : most;
        }

        /** Заливка кучки плана i при доле s, экранов. */
        private double planned(int i, double s) {
            int o = PLAN * i;
            return clusterFill(planView[i], plan[o], plan[o + 1], plan[o + 2], plan[o + 3], plan[o + 4], plan[o + 5], plan[o + 6],
                    Math.abs(plan[o + 7]), s, planSpray[i] ? PuffOptics.spray() : PuffOptics.puff());
        }

        /** Заливка всех кучек плана при доле s, экранов. */
        private double planned(double s) {
            double f = 0;
            for (int i = 0; i < plans; i++) f += planned(i, s);
            return f;
        }

        /** То же — только кучек трубок. */
        private double plannedTubes(double s) {
            double f = 0;
            for (int i = 0; i < plans; i++) if (plan[PLAN * i + 7] < 0) f += planned(i, s);
            return f;
        }

        /** Ждущий клуб i — в {@link #blob} с видом его источника. */
        private double held(int i) {
            int o = i * FIELDS, c = (int) held[o + 12];
            source = ctxSource[c];
            out = ctxOut[c];
            view = ctxView[c];
            age = ctxAge[c];
            sprayAge = ctxSprayAge[c];
            seed = ctxSeed[c];
            sprays = ctxSprays[c];
            return blob(held[o], held[o + 1], held[o + 2], held[o + 3], held[o + 4], held[o + 5], held[o + 6], held[o + 7], (float) held[o + 8],
                    (float) held[o + 9], (float) held[o + 10], (int) held[o + 11]);
        }

        /** Вуаль вещества у глаза в кадр: толща −ln(1 − a) и её цвет копятся, квадрат — один ({@link #veilFlush}). */
        void veilAdd(Out out, FarView view, float r, float g, float b, float a) {
            if (a < 0.002f) return;
            double t = -Math.log(1 - Math.min(a, 0.999));
            veilTau += t;
            veilR += t * r;
            veilG += t * g;
            veilB += t * b;
            veilOut = out;
            veilView = view;
        }

        void veilSource(Out out, FarView view, double px, double py, double pz, double sh, double sv, double ext,
                        double share, float r, float g, float b) {
            if (veilField == null) veilField = new VeilField();
            veilField.begin(view, out.veilAspect());
            veilField.add(px, py, pz, sh, sv, ext, share, r, g, b);
            veilOut = out;
            veilView = view;
        }

        /**
         * Angular transport uses all the nearby Gaussians, not just the central ray. The scalar sums remain
         * a compatibility diagnostic for receivers that do not implement the mesh.
         */
        void veilClear() {
            if (veilField != null) veilField.clear();
            veilTau = veilR = veilG = veilB = 0;
            veilOut = null;
            veilView = null;
        }

        private void veilFlush() {
            if (veilOut != null && !veilOut.veilField(veilView, veilField.width, veilField.height, veilField.finish()) && veilTau > 0) {
                double fx = veilView.forward().x(), fy = veilView.forward().y(), fz = veilView.forward().z();
                veilOut.veil(fx * VEIL_AT, fy * VEIL_AT, fz * VEIL_AT, VEIL_AT * 4, (float) (veilR / veilTau), (float) (veilG / veilTau),
                        (float) (veilB / veilTau), (float) (1 - Math.exp(-veilTau)), OPEN_SKY);
            }
            veilTau = veilR = veilG = veilB = 0;
            veilOut = null;
            veilView = null;
            if (veilField != null) veilField.clear();
        }

        /**
         * Клуб: в проходе плана ({@link #planning}) — только сколько экранов закроет кучка при доле {@link #share}; иначе —
         * квадраты и вуаль. Возвращает заливку, экранов.
         */
        private double blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                            int id) {
            Vec3 eye = view.camera();
            // на экране — проекция гаусса по углу взгляда (толща по лучу и высота квадрата); в мире — его σ (разброс клубов
            // кучки, край для рельефа и для глаза внутри)
            double across = projected(sh, sv, x - eye.x, y - eye.y, z - eye.z);
            double ks = SmokeGrid.SOOT_K * soot, kd = SmokeGrid.dustK(dr, dg, db) * dust, kw = SmokeGrid.DROP_K * water;
            double tau = (ks + kd + kw) / (2 * Math.PI * sh * across);
            if (1 - Math.exp(-tau) < 0.004) return 0;
            // у источника с брызгами (взрыв на воде) вещество — больше капли, чем дым и пыль: клубы брызг
            boolean spray = sprays && kw > ks + kd;
            double clubAge = Math.max(FIRST_FRAME, spray ? sprayAge : age);
            PuffOptics optics = spray ? PuffOptics.spray() : PuffOptics.puff();
            // квадрат одиночного клуба, что несёт весь гаусс ({@link PuffOptics}): край для рельефа, глаза внутри и кадра
            optics.club(clubAge, tau, CLUB);
            double c1 = CLUB[0];
            double rh = c1 * sh, rw = c1 * sv, r = Math.max(rh, rw);
            double vis = source.visible(y, r);
            if (vis <= 0) return 0;
            Vec3 cam = view.camera();
            double px = x - cam.x, py = y - cam.y, pz = z - cam.z;
            double d = Math.sqrt(px * px + py * py + pz * pz);
            double air = view.air(d, py);
            if (air < Sight.THRESHOLD) return 0;
            // глаз входит в клуб — квадраты клуба тают (квадрат в десятки блоков у самой камеры закрыл бы экран краем), а
            // вещество вокруг глаза — вуалью по экрану: толща по лучу взгляда из глаза через гаусс. «Внутри» — по эллипсоиду
            // края (у плоского слоя пыли в сотню метров шириной и метр толщиной глаз над ним снаружи; по наибольшему
            // радиусу он был «внутри», и вуаль слоя закрывала весь кадр)
            double outside = outside(px, py, pz, sh, sv);
            if (outside < 1 && !planning) veil(px, py, pz, sh, sv, ks + kd + kw, ks, kd, kw, dr, dg, db, (1 - outside) * vis * air);
            if (outside <= 0) return 0;
            // вне кадра — не нужен: шар всего квадрата кучки (клубы с разбросом — до ~2 краёв одиночного)
            if (!FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return 0;
            Sight.point(rh, 1, 1, d, view.pixel(), point);
            double grow = point[0] / rh;
            long h = mix(seed * 0x9E3779B97F4A7C15L + id);
            // свет неба — яркость того, каким карта освещения светит блоки и частицы под открытым небом (FarView.sky), с
            // разбросом клуба; его оттенок (ночью — сиреневый) — в шейдере только у света неба, не у огня (FxSkyTint)
            float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
            float cr = thick(ks, kd, kw, dr) * lit, cg = thick(ks, kd, kw, dg) * lit, cb = thick(ks, kd, kw, db) * lit;
            double light = shade(ks, kd, kw, dg);
            float shade = (float) (light / BAKED_K), sprayShade = (float) (light / SPRAY_K);
            // какая доля клуба видна (рельеф у источника вдали, дымка воздуха, пятно мельче пикселя, глаз внутри) — закрытая
            // доля квадрата; сколько вещества за ней — толща клубов
            float shown = (float) (vis * point[1] * air * outside);
            // кучка: чем крупнее гаусс на экране, тем больше клубов и тем они мельче (кадр 128 пикселей не растягивается на
            // пол-экрана); дробная часть числа — доля последнего, без скачка
            double pixels = sh * grow / Math.max(d * view.pixel(), 1e-12);
            // A two-dimensional footprint needs quadratically more clubs; transparent bake padding is irrelevant.
            double most = 1 + (Math.clamp(Math.pow(pixels / CLUSTER_PIXELS, 2), 1, CLUSTER) - 1)
                    * clumpsSeen(tau);
            // плоский клуб, на который смотрят сверху (проекция диска шире его толщи), лежит на горизонтали: квадрат лицом к
            // камере у слоя пыли у земли стоял бы стеной над ней (кадры F2a: «плита» под шаром в 0,1 с)
            boolean lying = lies(sh, sv, py, d);
            // σ гаусса на экране (м на его расстоянии, с ростом мельче пикселя): поперёк и вверх; стоящий квадрат вне оси взгляда —
            // шире ({@link #offAxis}), лежащий — в мире, его проекция и так верна
            double wide = lying ? 1 : offAxis(view, px, py, pz, d);
            double sa = sh * grow * wide, sb = (lying ? sh * Math.abs(py) / d : across) * grow * wide;
            if (planning) {
                planCluster(view, px, py, pz, sa, sb, tau, clubAge, most, spray, false);
                return 0;
            }
            // бюджет заливки кадра ({@link #flush}): кучка — между одним клубом и полной
            double fill = clusterFill(view, px, py, pz, sa, sb, tau, clubAge, most, share, optics);
            double many = 1 + (most - 1) * share;
            int count = (int) Math.ceil(many - 1e-9);
            // клуб кучки — гаусс σ·q с 1/many вещества, середины разбросаны с σ·√(1 − q²): у кучки те же масса и вторые моменты
            double q = clubShare(many), spread = count == 1 ? 0 : Math.sqrt(1 - q * q);
            // оси раскладки: лежащий клуб — по горизонтали мира (на высоте середины слоя: ниже он ушёл бы под рельеф), стоящий —
            // по осям экрана (σh поперёк, проекция вверх)
            org.joml.Vector3f l = view.left(), u = view.up();
            double turn = 2 * Math.PI * unit(h >>> 5);
            for (int j = 0; j < count; j++) {
                long hj = mix(h + j * 0x632BE59BD9B4E019L);
                double[] at = sunflower(j, many, turn, spread);
                double ox, oy, oz;
                if (lying) {
                    ox = at[0] * sh;
                    oy = 0;
                    oz = at[1] * sh;
                } else {
                    // Projected Gaussian mixture: scattering centres in depth changes its perspective moments.
                    double a = at[0] * sh * wide, b = at[1] * across * wide;
                    ox = a * l.x + b * u.x;
                    oy = a * l.y + b * u.y;
                    oz = a * l.z + b * u.z;
                }
                // разброс размера — у клубов кучки (разнообразие); одиночный клуб — весь гаусс, без разброса
                double k = count == 1 ? 1 : SIZE_LOW + 2 * (1 - SIZE_LOW) * unit(hj >>> 21);
                double frame = Math.min(FxAtlas.PUFF6_FRAMES - 1.001, clubAge + (count > 1 ? 2 * unit(hj >>> 28) : 0));
                int f0 = (int) frame;
                // последний клуб проявляется долей своего вещества
                double last = j == count - 1 ? many - (count - 1) : 1;
                optics.club(frame, tau / (many * q * q * k * k), CLUB);
                double half = CLUB[0] * q * k, mass = CLUB[1] * last;
                FxAtlas.Sprite sprite = spray ? FxAtlas.spray6(f0, (j & 1) == 1) : FxAtlas.puff6(f0, (j & 1) == 1);
                // Six-way lighting follows the card's real tangent frame, including a horizontal dust layer.
                // Softness at terrain is the path through the layer (rw / sin e), not its large horizontal radius.
                if (lying) out.puff6Flat(px + ox, py + oy, pz + oz, sa * half, sa * half,
                        Math.min(rh, rw * d / Math.max(Math.abs(py), 1e-3)) * ua.zentix.incoming.client.fx.layer.FxQuads.SOFT, sprite,
                        (float) (frame - f0), spray ? sprayShade : shade, cr, cg, cb, shown, mass);
                else out.puff6(px + ox, py + oy, pz + oz, sa * half, sb * half, sprite, (float) (frame - f0), spray ? sprayShade : shade,
                        cr, cg, cb, shown, mass);
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

    /**
     * Клуб трубки с центром (px, py, pz) от глаза — место на оси, что стоит за along метров оси: σ по горизонтали и
     * вертикали, толща трубки поперёк оси tau, что рисуют клубы (часть её вещества может рисовать лента) — гаусс вещества
     * своего куска оси (толща середины τ·along/(√(2π)·σ)), кучкой запечённых клубов, как гаусс облака ({@link PuffOptics}:
     * клубы несут его вещество, и толщи соседних мест у оси складываются в толщу трубки): цвет толстого слоя (cr, cg, cb; до
     * света неба у глаза), степень тени карт shade, кадр клуба age (с дробью), зерно h, видимая доля vis, клубов в кучке —
     * не больше most; vary — и одиночный клуб берёт свой кадр и отражение по зерну (клубы трубки идут рядом друг с другом:
     * одинаковые лежали бы стопкой монет). Глаз в трубке — вуаль ({@link #veil}).
     */
    static int club(FarView view, Out out, double px, double py, double pz, double sh, double sv, double tau, double along,
                    float cr, float cg, float cb, double shade, boolean spray, double age, long h, double vis, int most, boolean vary) {
        // вещество места — толща трубки поперёк оси × √(2π)σ × его кусок оси; гаусс места — с толщей середины ext/(2πσhσv)
        double ext = tau * Math.sqrt(2 * Math.PI * sh * sv) * along, tau0 = ext / (2 * Math.PI * sh * sv);
        if (1 - Math.exp(-tau0) < 0.004) return 0;
        double clubAge = Math.max(FIRST_FRAME, age);
        PuffOptics optics = spray ? PuffOptics.spray() : PuffOptics.puff();
        double[] c = TUBE_CLUB;
        optics.club(clubAge, tau0, c);
        double rh = c[0] * sh, rv = c[0] * sv, r = Math.max(rh, rv);
        double d = Math.sqrt(px * px + py * py + pz * pz);
        double air = view.air(d, py);
        if (air < Sight.THRESHOLD) return 0;
        // глаз входит в клуб — квадраты клуба тают (квадрат в десятки блоков у самой камеры закрыл бы экран краем), а
        // вещество вокруг глаза — вуалью по экрану: толща по лучу взгляда из глаза через гаусс
        double outside = outside(px, py, pz, sh, sv);
        int veils = outside < 1 && veilAround(view, out, px, py, pz, sh, sv, ext, cr, cg, cb, (1 - outside) * vis * air) ? 1 : 0;
        if (outside <= 0) return veils;
        // вне кадра — не нужен: шар всего квадрата кучки (клубы с разбросом — до ~2 краёв одиночного)
        if (!FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return veils;
        double[] point = POINT;
        Sight.point(rh, 1, 1, d, view.pixel(), point);
        double grow = point[0] / rh;
        // свет неба — яркость FarView.sky с разбросом клуба; оттенок — в шейдере у света неба (FxSkyTint)
        float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
        float sr = cr * lit, sg = cg * lit, sb = cb * lit;
        float shown = (float) (vis * point[1] * air * outside);
        // кучка: чем крупнее гаусс на экране, тем больше клубов и тем они мельче (кадр 128 пикселей не растягивается на
        // пол-экрана); дробная часть числа — доля последнего, без скачка
        double pixels = sh * grow / Math.max(d * view.pixel(), 1e-12);
        // The physical footprint, not the padded bake rectangle, sets detail.
        double full = 1 + (Math.clamp(Math.pow(pixels / CLUSTER_PIXELS, 2), 1, most) - 1)
                * clumpsSeen(tau0);
        // бюджет заливки кадра ({@link #FILL_BUDGET}): доля кучки — из прошлого кадра (кучки трубок рисуются, пока источник
        // их кладёт, а подробность считается в конце кадра); окончательный бюджет пикселей — у PuffTarget
        double many = 1 + (full - 1) * DRAW.tubeShare;
        int count = (int) Math.ceil(many - 1e-9);
        double wide = offAxis(view, px, py, pz, d), sa = sh * grow * wide, sbv = sv * grow * wide;
        DRAW.planCluster(view, px, py, pz, sa, sbv, tau0, clubAge, full, spray, true);
        double q = clubShare(many), spread = count == 1 ? 0 : Math.sqrt(1 - q * q);
        // кучка — «подсолнухом» в плоскости экрана, как у облаков ({@link #sunflower}: случайные места оставляли просветы)
        org.joml.Vector3f l = view.left(), u = view.up();
        double turn = 2 * Math.PI * unit(h >>> 5);
        for (int j = 0; j < count; j++) {
            long hj = mix(h + j * 0x632BE59BD9B4E019L);
            double[] at = sunflower(j, many, turn, spread);
            double ea = at[0] * sh * wide, eb = at[1] * sv * wide;
            double ox = ea * l.x + eb * u.x, oy = ea * l.y + eb * u.y, oz = ea * l.z + eb * u.z;
            double k = SIZE_LOW + 2 * (1 - SIZE_LOW) * unit(hj >>> 21);
            double frame = Math.min(FxAtlas.PUFF6_FRAMES - 1.001, clubAge + (count > 1 || vary ? 2 * unit(hj >>> 28) : 0));
            int f0 = (int) frame;
            double last = j == count - 1 ? many - (count - 1) : 1;
            optics.club(frame, tau0 / (many * q * q * k * k), c);
            double half = c[0] * q * k;
            boolean mirror = vary ? (hj >>> 40 & 1) == 1 : (j & 1) == 1;
            out.puff6(px + ox, py + oy, pz + oz, sa * half, sbv * half, spray ? FxAtlas.spray6(f0, mirror) : FxAtlas.puff6(f0, mirror),
                    (float) (frame - f0), (float) shade, sr, sg, sb, shown, c[1] * last);
        }
        return count + veils;
    }

    private static final double[] TUBE_CLUB = new double[2];
    private static final double[] POINT = new double[2];

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
     * Запечённый клуб на экране — почти ровный диск: непрозрачность падает до половины середины не ближе этой доли
     * полуразмера квадрата ({@code PlumeContinuityTest} мерит по картинкам кадров с {@link #FIRST_FRAME}: молодой клуб —
     * 0,76, раскрытый — 0,48). Не гаусс: диски через шаг шире диска складываются в бусы.
     */
    static final double PUFF_DISC_MIN = 0.48;

    /** Размах размера клубов кучки и трубки: полуразмер × 0,8–1,2. */
    static final double SIZE_LOW = 0.8;

    /**
     * Шаг мест трубки σ: не шире {@link #SEGMENT_STEP}·σ (гауссы вещества сливаются) и не шире диска самого мелкого клуба —
     * каждое место оси под двумя дисками ({@link #PUFF_DISC_MIN} × {@link #SIZE_LOW} × полуразмер клуба тонкого гаусса,
     * {@link PuffOptics#cMin}).
     */
    static double tubeStep(double sigma) {
        double disc = disc(sigma), off = MEANDER_MAX * meander(sigma);
        // ось пересекает диск клуба, сдвинутого блужданием на off, хордой 2√(диск² − off²); шаг — её половина
        return Math.min(SEGMENT_STEP * sigma, Math.sqrt(disc * disc - off * off));
    }

    /** Диск самого мелкого клуба трубки σ, м. */
    private static double disc(double sigma) {
        return PUFF_DISC_MIN * SIZE_LOW * PuffOptics.puff().cMin() * sigma;
    }

    /**
     * Блуждание середины клуба трубки, м: {@link #TUBE_MEANDER}·σ, но не дальше половины диска самого мелкого клуба от
     * оси (поперёк экрана — до {@link #MEANDER_MAX} блужданий): клубы гуляют каждый сам по себе, и сдвинутый дальше
     * открывал бы ось — тонкая трубка рвалась бы на бусы. ⚠ Блуждание — оценка (вихри следа порядка его ширины).
     */
    static double meander(double sigma) {
        return Math.min(TUBE_MEANDER * sigma, 0.5 * disc(sigma) / MEANDER_MAX);
    }

    /** Сдвиг клуба поперёк оси на экране — не больше стольких блужданий: колокол {@link #gauss} до 1,725 по двум осям. */
    static final double MEANDER_MAX = 1.725 * Math.sqrt(2);
    /**
     * Середина клуба трубки гуляет на столько σ: крупные вихри следа — порядка его ширины, и сам след извивается на доли
     * ширины (⚠ оценка).
     */
    static final double TUBE_MEANDER = 0.3;
    /** Клубов в кучке у клуба трубки — не больше: подробность вдоль трубки дают соседние клубы. */
    static final int TUBE_CLUSTER = CLUSTER;

    /**
     * Отрезок трубки следа ({@code TrailModel}: гауссово сечение σ, ослабление на метр e — у концов a и b, от глаза) — клубами
     * вдоль оси, как облака: трубка из гауссов того же сечения через ≤ {@link #SEGMENT_STEP}·σ (до {@code 2^}{@link #TUBE_LEVELS}
     * на отрезок, дальше шаг шире), у каждого — вещество своего
     * куска оси (сумма гауссов вдоль оси даёт толщу трубки поперёк середины e/(√(2π)·σ)). Клубов на отрезок — 2^k
     * ({@link #TUBE_LEVELS}): места уровня k — часть мест уровня k + 1, и новые (нечётные) проявляются долей, пока трубка
     * сужается на экране, — без скачков, когда σ растёт.
     *
     * @param wa     ширина трубки у конца a для порога клубов ({@code FarFlightView.puffShare}: на половине непрозрачности, с
     *               множителем бюджета), у b — wb: доля вещества, что рисуют клубы, — у каждого клуба по его расстоянию
     *               (остальное — лента, её доля у концов — та же)
     * @param cr     цвет толстого слоя вещества на экране по каналам (cr, cg, cb; {@link #thick}; у следа — серый {@link #tubeColour})
     * @param shade  степень тени карт клуба ({@link #shade}; у следа — {@link #tubeShade})
     * @param sigma0 σ при рождении следа: кадр клуба — по росту σ с него ({@link FxAtlas#puffFrame})
     * @param whole  отрезок без ленты (проходит у самого глаза): клубы рисуют всё его вещество
     * @return квадратов клубов и вуалей
     */
    static int tube(FarView view, Out out, double ax, double ay, double az, double sa, double ea, double wa, double bx, double by, double bz,
                    double sb, double eb, double wb, float cr, float cg, float cb, double shade, double sigma0, long seed, boolean whole) {
        if (ea + eb <= 0 || !(sa > 0) || !(sb > 0)) return 0;
        double tx = bx - ax, ty = by - ay, tz = bz - az, len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double shareA = whole ? 1 : FarFlightView.puffShare(wa, Math.sqrt(ax * ax + ay * ay + az * az), view.pixel());
        double shareB = whole ? 1 : FarFlightView.puffShare(wb, Math.sqrt(bx * bx + by * by + bz * bz), view.pixel());
        // места — не реже, чем у обоих концов диски клубов перекрывают ось вдвое ({@link #tubeStep})
        double step = Math.min(tubeStep(sa), tubeStep(sb));
        double level = tubeLevel(len, step / SEGMENT_STEP);
        int k = tubeDepth(level), n = 1 << k;
        double total = tubeTotal(level);
        int drawn = 0;
        for (int j = 0; j < n; j++) {
            double weight = tubeWeight(j, level);
            if (weight <= 0) continue;
            double u = (double) j / n, sigma = sa + (sb - sa) * u, e = ea + (eb - ea) * u;
            double cx = ax + tx * u, cy = ay + ty * u, cz = az + tz * u;
            double share = whole ? 1 : FarFlightView.puffShare(wa + (wb - wa) * u, Math.sqrt(cx * cx + cy * cy + cz * cz), view.pixel());
            if (share <= 0) continue;
            // толща трубки поперёк оси — e/(√(2π)σ) (доля клубов — share); место стоит за свой кусок оси len/total (с долей места)
            double full = e / (Math.sqrt(2 * Math.PI) * sigma), tau = share * full, along = len * weight / total;
            if (!(tau > 0) || !(along > 0)) continue;
            double age = FxAtlas.puffFrame(sigma / Math.max(sigma0, 1e-6));
            // зерно — по месту на самом мелком уровне: клуб остаётся тем же, когда уровень меняется
            long h = mix(seed * 0x9E3779B97F4A7C15L + ((long) j << (TUBE_LEVELS - k)));
            // вихри следа — порядка σ: середина клуба гуляет ({@link #meander}, ⚠ оценка), иначе ровный ряд
            double m = meander(sigma);
            drawn += club(view, out, cx + m * gauss(h >>> 3), cy + m * gauss(h >>> 11), cz + m * gauss(h >>> 19), sigma, sigma, tau, along, cr,
                    cg, cb, shade, false, age, h, 1, TUBE_CLUSTER, true);
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

    /**
     * Вуаль вещества клуба трубки вокруг глаза (как {@link #veil}, но для {@link #club}): ослабление всей массы ext (м²),
     * цвет толстого слоя (cr, cg, cb; свет — карта освещения под открытым небом и огонь кадра), доля share.
     */
    private static boolean veilAround(FarView view, Out out, double px, double py, double pz, double sh, double sv, double ext, float cr,
                                      float cg, float cb, double share) {
        double fx = view.forward().x(), fy = view.forward().y(), fz = view.forward().z();
        float k = (float) (-Math.expm1(-sightDepth(px, py, pz, sh, sv, ext, fx, fy, fz)) * share);
        if (!(share > 0 && ext > 0)) return false;
        DRAW.veilAdd(out, view, cr, cg, cb, k);
        DRAW.veilSource(out, view, px, py, pz, sh, sv, ext, share, cr, cg, cb);
        return true;
    }

    /** Свет мира под открытым небом без света блоков (упакованный, для карты освещения). */
    private static final int OPEN_SKY = LightTexture.pack(0, 15);

    /** Вуаль облака вокруг глаза — на столько блоков перед ним (ближе квадраты слоя тают у ближней плоскости). */
    static final double VEIL_AT = 2;

    /**
     * Вещество гаусса вокруг глаза (центр (px, py, pz) от глаза, σ, толща поперёк середины tau): толща по лучу взгляда
     * из глаза — τ₀·e^(−b²/2σ²)·½·(1 + erf(a/(√2σ))) (a — середина гаусса вдоль взгляда, b — поперёк; σ — среднее
     * геометрическое), непрозрачность — 1 − e^(−τ), квадратом во весь экран на {@link #VEIL_AT} блока перед глазом. Тот
     * же цвет вещества, что у клубов. Доля share — сколько клубов растаяло.
     */
    private static void veil(double px, double py, double pz, double sh, double sv, double ext, double ks, double kd, double kw, float dr,
                             float dg, float db, double share) {
        FarView view = DRAW.view;
        double fx = view.forward().x(), fy = view.forward().y(), fz = view.forward().z();
        double along = sightDepth(px, py, pz, sh, sv, ext, fx, fy, fz);
        float k = (float) (-Math.expm1(-along) * share);
        if (!(share > 0 && ext > 0)) return;
        float cr = thick(ks, kd, kw, dr), cg = thick(ks, kd, kw, dg), cb = thick(ks, kd, kw, db);
        // во весь экран: полуразмер — с запасом на угол поля зрения до ~120°; свет — как у вещества под открытым небом
        // (карта освещения: небо 15) и огонь кадра у глаза (FxLights), как у клубов этого же вещества снаружи
        DRAW.veilAdd(DRAW.out, view, cr, cg, cb, k);
        DRAW.veilSource(DRAW.out, view, px, py, pz, sh, sv, ext, share, cr, cg, cb);
    }

    /**
     * Толща по лучу взгляда из глаза (направление f, единичное) через гаусс с осями (σh, σh, σv), центр — (px, py, pz) от
     * глаза, ослабление всей массы ext (м²): в пространстве, где гаусс — шар единичного σ, луч идёт с шагом |f'| =
     * √((fx² + fz²)/σh² + fy²/σv²), и толща — ext/((2π)^{3/2}σh²σv)·√(π/2)/|f'|·e^(−b'²/2)·(1 + erf(a'/√2)), a' — путь до
     * ближайшей к середине точки луча, b' — её расстояние до середины (обе — в σ). У шара — прежняя формула вуали.
     */
    static double sightDepth(double px, double py, double pz, double sh, double sv, double ext, double fx, double fy, double fz) {
        double ux = fx / sh, uy = fy / sv, uz = fz / sh, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (!(len > 0)) return 0;
        ux /= len;
        uy /= len;
        uz /= len;
        // середина от глаза в σ: луч из глаза — t·u, ближайшая к середине точка — t = a'
        double cx = px / sh, cy = py / sv, cz = pz / sh;
        double a = cx * ux + cy * uy + cz * uz, b2 = Math.max(0, cx * cx + cy * cy + cz * cz - a * a);
        return ext / (Math.pow(2 * Math.PI, 1.5) * sh * sh * sv) * Math.sqrt(Math.PI / 2) / len * Math.exp(-0.5 * b2)
                * (1 + SmokeGrid.erf(a / Math.sqrt(2)));
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
