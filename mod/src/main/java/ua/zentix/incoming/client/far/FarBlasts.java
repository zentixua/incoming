package ua.zentix.incoming.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.sound.Acoustics;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.Outdoor;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.warhead.BunkerCover;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Взрывы вдали: вспышка, огненный шар, облако дыма и пыли и раскат с задержкой по скорости звука.
 * <p>
 * Пакет взрыва — одно событие: облако — модель взрыва ({@link CloudModel}: заряд, топливо, грунт и воздух) — одна и та же
 * вблизи и вдали. Событие — источник облаков клиента ({@link SmokeGrid.Source}): клубы модели, облако конденсации и шлейф
 * над лужей топлива; рисует их слой эффектов запечёнными клубами ({@link SmokePuffs}), объём сетки — нет (клетка
 * в 2–32 м облако в метры–десятки метров не разрешает). Огненный шар — кадры {@code fx/particle/fireball} (симуляция
 * горения в Blender, цвет — чёрное тело поля температуры) размера и времени шара модели (шар топлива по CCPS или шар
 * продуктов детонации, потом остывание подсосом воздуха), яркость — чёрное тело его температуры ({@link BlackBody}): днём
 * шар топлива (~1500 K) — порядка белой стены, вспышка продуктов детонации (~3000 K) — сотни.
 * <p>
 * Бомба под землёй ({@code kind} BUNKER) — прежняя картинка (её глубина и выход газов — физика полёта и боевой части, не
 * эта): шар прорыва и столб пыли по {@link Look}.
 * <p>
 * Рельеф между взрывом и глазом — один луч {@link Sightline} при приходе и не чаще раза в секунду потом: за холмом видна
 * только поднявшаяся над ним часть облака, а вспышка — слабым заревом над гребнем. Свет приходит сразу: блик и вуаль
 * вокруг шара — здесь на любой дальности, и рядом (они в глазу, а не в воздухе; ночью ещё зарево на облаках над местом —
 * {@link #cloudGlow}); звук — когда до уха дойдёт фронт; дальше {@link #NEAR} он здесь ({@link BlastSounds#far}), ближе —
 * ракурсы ближней модели ({@code BlastMix}).
 */
public final class FarBlasts {
    /** Край ближней картинки и ближнего звука, блоков. */
    public static final double NEAR = Outdoor.NEAR;
    /** Событий не больше: старые уходят первыми. */
    static final int CAP = 256;
    /** Луч по рельефу — не чаще раза в столько тиков на событие; лучей за тик — не больше (кроме лучей новых). */
    static final int RETRACE = 20, TRACES_PER_TICK = 16;
    /**
     * Шаров в прорисовке, к которым за тик идут лучи по блокам ({@link #clear}), сверх лучей по рельефу, не больше: пока
     * шар светит — каждый тик по очереди, потом — с лучом по рельефу.
     */
    static final int CLEARS_PER_TICK = 8;
    /** Лучей к шару по кругу вокруг середины ({@link #clear}) и радиус круга, радиусов шара. */
    static final int RING_RAYS = 6;
    static final double RING = 0.7;
    private static final double[] DISC = new double[3];
    /** Источник для луча и звука — над точкой удара, блоков (как вспышка ближней картинки). */
    static final double LIFT = 1.5;
    /** Плотность клуба; разброс клубов от оси столба, радиусов. */
    static final double OPACITY = 0.85, SPREAD = 0.4;
    /**
     * Столб бомбы проступает из шара, пока тот тает ({@link #smokeFrom}), но не быстрее стольких тиков: проступая с
     * первого тика, он за 8 тиков закрыл бы яркий шар десятком клубов.
     */
    static final double FADE_IN = 8;
    /**
     * Шар бомбы (прежняя картинка): яркость против белого экрана; доля света шара, что светит заревом, когда шар закрыт:
     * воздух рассеивает свет шара радиуса R на расстоянии r — яркость σ·ℓ·R²/(4r²) от яркости шара (σ ясного воздуха
     * ~2·10⁻⁴ на метр, путь ℓ ~100 м, r — 2–3 R), тысячная. Днём это едва видно, ночью зарево упирается в яркость вуали
     * ({@link #halo}).
     */
    static final double BALL = 8, BEHIND = 0.001;
    /** Радиус светящегося воздуха вокруг закрытого шара, радиусов шара: дальше рассеянный свет слабеет как 1/r. */
    static final double HALO_SIZE = 3;
    /**
     * Центр огненного шара бомбы над точкой взрыва (радиусов): наземный шар — полусфера, низ в земле; за жизнь он
     * всплывает ещё на {@link #BALL_RISE}.
     */
    public static final double BALL_LIFT = 0.45, BALL_RISE = 0.2;
    /** С какой доли жизни шар тает (дальше — облако). */
    public static final double BALL_FADE = 0.55;
    /**
     * Зарево на облаках: низ облаков над местом освещён шаром (освещённость E = I/h², яркость ≈ альбедо·E/π) — пятно
     * радиуса {@link #SKY_SPREAD} высот облаков над местом (на одной высоте — треть пика, на двух — десятая); пик против
     * белого — не больше SKY_PEAK (в дождь, облака сплошные), в ясную ночь — {@link #SKY_CLEAR} от него. Облака ниже
     * {@link #SKY_LOW} блоков над местом — считаем на этой высоте.
     */
    static final double CLOUD_ALBEDO = 0.7, SKY_SPREAD = 1, SKY_PEAK = 0.45, SKY_CLEAR = 0.45, SKY_LOW = 40;
    /** Столб бомбы: свежий дым и старый, цвет пыли тянет к серому (прежняя картинка). */
    private static final float[] DARK = {0.18f, 0.165f, 0.15f}, AGED = {0.48f, 0.455f, 0.43f}, DUST_GREY = {0.6f, 0.57f, 0.54f};
    /** Шар бомбы: раскалён → оранжевый → тёмно-красный; свет вспышки. */
    private static final float[] FLASH = {1, 0.95f, 0.82f}, BALL_HOT = {1, 0.9f, 0.7f}, BALL_MID = {1, 0.5f, 0.15f},
            BALL_COLD = {0.6f, 0.15f, 0.04f};
    /**
     * Бомба под землёй — прежняя картинка (прорыв — шар и столб пыли ниже наземного взрыва; глубже — только столб пыли): её
     * числа подобраны по замерам для бетонобойной бомбы и не из модели облака — глубина и выход газов принадлежат физике
     * боевой части (пакет локальных сессий).
     *
     * @param fireball   радиус огненного шара, блоков; 0 — ни шара, ни вспышки
     * @param flash      яркость вспышки против белого экрана
     * @param flashTicks сколько тиков вспышка
     * @param ballTicks  сколько тиков шар светит (ночью виден весь, днём — первую половину)
     * @param column     высота столба, блоков
     * @param riseTicks  за сколько тиков столб поднимается на 95 %
     * @param base       радиус клуба у основания, блоков
     * @param top        радиус клуба в шапке, блоков
     * @param puffs      клубов в столбе
     * @param life       сколько тиков столб виден
     * @param dust       доля пыли грунта: 1 — весь столб пыль, меньше — дым, пыль у основания
     */
    public record Look(float fireball, double flash, int flashTicks, int ballTicks, double column, int riseTicks, double base,
                       double top, int puffs, int life, float dust) {}

    public static final Look BUNKER_BREACH = new Look(9, 60, 3, 20, 210, 2400, 18, 55, 14, 4200, 0.5f);
    public static final Look BUNKER_DEEP = new Look(0, 0, 0, 0, 110, 500, 14, 32, 12, 3000, 1);

    /** Размер клуба столба бомбы — от и сколько сверху (доля номинального); разброс места клуба по высоте — доля шага. */
    static final double SIZE_MIN = 0.85, SIZE_SPAN = 0.3, JITTER = 0.3;
    /** Шлейф над лужей: отрезков (гуще у очага, где ось круче) и докуда по ветру, м (как дым пожаров). */
    static final int POOL_SEGMENTS = 8;
    static final double PLUME_REACH = 768;

    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    /** Числа на кадр без выделения: точка {@link Sight#point}, свет {@link Sight#light} и цвет шара. */
    private static final double[] POINT = new double[2], LIGHT = new double[5], BB = new double[4];
    /** Самое яркое тело последнего кадра (для лога): ядро, блик и вуаль в пикселях, пересвет, доля над рельефом. */
    private static final double[] BRIGHTEST = new double[5];
    private static final float[] TINT = new float[3];
    /** Тики клиента (не на паузе). */
    private static long clock;
    /** Последний дальний звук — для лога сценария. */
    private static Outdoor.Heard lastHeard;
    private static double lastDistance;
    private static long lastDelay;

    private FarBlasts() {}

    /** Один взрыв: где, какой, его облако (модель или прежняя картинка бомбы), случайное и последний луч по рельефу. */
    private static final class Event implements SmokeGrid.Source {
        final ClientLevel level;
        /** Что взорвалось: звук по заряду. */
        final BlastSource source;
        /** Бомба под землёй — прежняя картинка; у остальных null. */
        @Nullable
        final Look look;
        /** Облако по модели; у бомбы null. */
        @Nullable
        final CloudModel model;
        /** Основание: точка удара, у бомбы — поверхность над зарядом. */
        final double x, y, z;
        final GroundMaterial ground;
        /** Зерно взрыва: клубы и вариант записи звука. */
        final long seed;
        final long born;
        /** Часы сетки дыма в миг взрыва, с. */
        final double bornSeconds;
        /** Пакет пришёл ближе {@link #NEAR}: ближняя картинка и звук есть. */
        final boolean near;
        /** Альбедо пыли грунта по каналам (модель) и её цвет в воздухе (бомба). */
        final float dustR, dustG, dustB, phase;
        /** Клубы: доля высоты столба, разброс по x и z (в радиусах), размер, поворот, вращение, оттенок, доля пыли, кадр атласа. */
        final float[] f, ox, oz, size, rot, spin, shade, dust;
        final byte[] tex;
        boolean soundPending;
        /**
         * Дольше не ждём звука, до уха которого фронт так и не дошёл (слушатель уходит быстрее звука), тиков: путь звука
         * на всю дальность слышимости ({@link BlastSounds#range}).
         */
        final long soundWait;
        /** Шар светит (в прошлом кадре): лучи по блокам к шару ({@link #clear}) стоит обновлять. */
        boolean glows = true;
        long traced;
        /** По последнему лучу: на сколько блоков выше источника его видно; разность хода через кромку. */
        double hidden, path;
        /** Шар в прорисовке не закрыт блоками (доля лучей, {@link #clear}): блик и вуаль у глаза глубина не режет. */
        float open = 1;
        /** Источник облака ещё нужен (событие живо). */
        boolean alive = true;
        /** Шлейф над лужей топлива (null — лужи нет); направление ветра у земли. */
        @Nullable
        final ua.zentix.incoming.client.fx.smoke.FirePlume pool;
        final double poolWx, poolWz;

        Event(ClientLevel level, BlastSource source, @Nullable Look look, @Nullable CloudModel model, double x, double y, double z,
              GroundMaterial ground, long seed, boolean near) {
            this.level = level;
            this.source = source;
            this.soundWait = (long) Math.ceil(BlastSounds.range(source) / Acoustics.SPEED);
            this.look = look;
            this.model = model;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ground = ground;
            this.seed = seed;
            this.born = clock;
            this.bornSeconds = SmokeGrid.seconds(0);
            this.near = near;
            this.soundPending = !near;
            if (model != null && model.poolWatts > 0) {
                // ветер у земли — на высоте пламени (шлейф лужи — у земли, ниже облака)
                double ux = model.air.windX() * model.air.wind(2), uz = model.air.windZ() * model.air.wind(2), u = Math.hypot(ux, uz);
                pool = new ua.zentix.incoming.client.fx.smoke.FirePlume(model.poolWatts, model.poolRadius,
                        model.source.fuel().sootYield / model.source.fuel().heatJPerKg, u, ClientAir.now().stability());
                poolWx = u > 1e-6 ? ux / u : 1;
                poolWz = u > 1e-6 ? uz / u : 0;
            } else {
                pool = null;
                poolWx = 1;
                poolWz = 0;
            }
            if (model != null) {
                dustR = SmokeGrid.dustAlbedo(ground.r);
                dustG = SmokeGrid.dustAlbedo(ground.g);
                dustB = SmokeGrid.dustAlbedo(ground.b);
            } else {
                // пыль в воздухе светлее и серее грунта (прежняя картинка бомбы)
                dustR = ground.r + (DUST_GREY[0] - ground.r) * 0.25f;
                dustG = ground.g + (DUST_GREY[1] - ground.g) * 0.25f;
                dustB = ground.b + (DUST_GREY[2] - ground.b) * 0.25f;
            }
            SplittableRandom rnd = new SplittableRandom(seed);
            phase = (float) (rnd.nextDouble() * Math.PI * 2);
            int n = look != null ? look.puffs() : 0;
            f = new float[n];
            ox = new float[n];
            oz = new float[n];
            size = new float[n];
            rot = new float[n];
            spin = new float[n];
            shade = new float[n];
            dust = new float[n];
            tex = new byte[n];
            for (int i = 0; i < n; i++) {
                f[i] = (float) fraction(i, n, rnd.nextDouble() - 0.5);
                ox[i] = (float) gauss(rnd);
                oz[i] = (float) gauss(rnd);
                size[i] = (float) (SIZE_MIN + SIZE_SPAN * rnd.nextDouble());
                rot[i] = (float) (rnd.nextDouble() * Math.PI * 2);
                spin[i] = (float) ((rnd.nextDouble() - 0.5) * 0.004);
                shade[i] = (float) (0.88 + 0.24 * rnd.nextDouble());
                dust[i] = look == null ? 0 : (float) Math.pow(look.dust(), 0.5 + f[i]);
                tex[i] = (byte) rnd.nextInt(8);
            }
        }

        double distanceTo(Vec3 p) {
            double dx = x - p.x, dy = y + LIFT - p.y, dz = z - p.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** Сколько тиков событие живёт (облако видно). */
        double life() {
            return model != null ? model.life * 20 : look.life();
        }

        /** Сколько тиков светит огненный шар (0 — шара нет). */
        double ballTicks() {
            return model != null ? model.fireSeconds * 20 : look.ballTicks();
        }

        /** Наибольший радиус огненного шара, блоков (полуразмер его картинки). */
        double ballRadius() {
            return model != null ? Math.max(0.5 * model.fuelBall, model.heBall) : look.fireball();
        }

        /** Высота центра шара над основанием на age-м тике, блоков. */
        double ballHeight(double age) {
            if (model != null) return model.ballHeight(age / 20);
            return look.fireball() * (BALL_LIFT + BALL_RISE * age / Math.max(1, look.ballTicks()));
        }

        /**
         * Яркость шара на age-м тике против белого экрана днём (без привыкания глаза): модель — чёрное тело температуры
         * шара (продукты детонации — вспышка, потом шар топлива), бомба — прежние вспышка и остывание.
         */
        double brightness(double age) {
            if (model != null) {
                model.ballLight(age / 20, BB);
                return BB[3];
            }
            double u = age / Math.max(1, look.ballTicks());
            return flash(look, age) + ball(u);
        }

        /** Цвет света шара на age-м тике (наибольший канал — 1). */
        float[] colour(double age) {
            if (model != null) {
                model.ballLight(age / 20, BB);
                double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
                for (int i = 0; i < 3; i++) TINT[i] = (float) (BB[i] / Math.max(m, 1e-12));
                return TINT;
            }
            double u = age / Math.max(1, look.ballTicks());
            return glare(flash(look, age), u);
        }

        @Override
        public boolean ended() {
            return !alive || level != Minecraft.getInstance().level;
        }

        @Override
        public long seed() {
            return seed;
        }

        /** Огненный шар — тело огня ({@link #light}): горячие клубы модели — его, не дым. */
        @Override
        public boolean drawsGlow() {
            return true;
        }

        /** Во сколько раз облако выросло с начала (σ по горизонтали) — кадр запечённого клуба. */
        @Override
        public double puffGrowth(double seconds) {
            if (model == null) return 1;
            double t = Math.max(0, seconds - bornSeconds);
            return model.sigmaH(t) / Math.max(model.sigmaH(0), 1e-6);
        }

        /** Над рельефом, что закрывает место взрыва вдали (последний луч, {@link #trace}). */
        @Override
        public double visible(double wy, double r) {
            return FarBlasts.visible(wy - y, r, hidden > 0 ? LIFT + hidden : 0);
        }

        /** Источник облака: клубы модели в мире, облако конденсации и шлейф над лужей топлива. */
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            if (!alive || model == null || level != Minecraft.getInstance().level) return false;
            double t = seconds - bornSeconds;
            if (t >= Math.max(model.life, poolEnd())) return false;
            if (t < model.life) {
                model.blobs(t, (bx, by, bz, sh, sv, soot, dustKg, water, kelvin, rise) -> out.blob(x + bx, y + by, z + bz, sh, sv, soot, dustKg,
                        water, dustR, dustG, dustB, kelvin, rise));
            }
            if (t < model.condensationSeconds()) condensation(t, out);
            if (pool != null) pool(t, out);
            return true;
        }

        /** Когда последний дым лужи уходит за край видимого шлейфа, с. */
        double poolEnd() {
            return pool == null ? 0 : model.poolSeconds + pool.visible(PLUME_REACH) / pool.wind;
        }

        /**
         * Шлейф Бриггса над лужей ({@link ua.zentix.incoming.client.fx.smoke.FirePlume}, как дым пожаров): отрезки от
         * очага по ветру до пути, что дым прошёл с начала горения, — место на пути xₖ несёт силу лужи в миг выхода этого
         * дыма (xₖ/u секунд назад).
         */
        private void pool(double t, SmokeGrid.Sink out) {
            double u = pool.wind, far = Math.min(pool.visible(PLUME_REACH), u * t);
            if (far <= 0) return;
            double prevX = 0, prevH = pool.rise(0);
            for (int k = 1; k <= POOL_SEGMENTS; k++) {
                double f = (double) k / POOL_SEGMENTS, xk = far * f * f, mid = 0.5 * (xk + prevX), hk = pool.rise(xk);
                double x0 = prevX, h0 = prevH;
                prevX = xk;
                prevH = hk;
                double share = model.poolShare(t - mid / u);
                if (share <= 0 || pool.tau(mid) * share < CloudModel.SEEN) continue;
                double ax = 0.5 * (xk - x0);
                out.segment(x + poolWx * mid, y + 0.5 * (h0 + hk), z + poolWz * mid, poolWx * ax, 0.5 * (hk - h0), poolWz * ax, pool.sigmaY(mid),
                        pool.sigmaZ(mid), pool.sootPerMetre() * 2 * ax * share, 0, 0, dustR, dustG, dustB,
                        CloudModel.T_AIR_K + pool.excess(0.5 * (h0 + hk)), pool.riseSpeed(mid));
            }
        }

        /**
         * σ клуба оболочки конденсации, радиусов: клубы на полусфере стоят через 0,5–0,9 радиуса (кольцо на 30° — реже всего),
         * клуб шире половины шага — оболочка сплошная, а не бусы (оценка формы).
         */
        static final double SHELL = 0.45;

        /**
         * Облако конденсации ({@link CloudModel#condensation}): оболочка капель на полусфере радиуса r за фронтом волны —
         * кольцо клубов по полусфере ({@link #SHELL}), слой капель — пятая радиуса (оценка формы); капли — водность 0,5 г/м³
         * (оценка), тают к концу жизни.
         */
        private void condensation(double t, SmokeGrid.Sink out) {
            double r = model.condensation(), life = model.condensationSeconds();
            double shell = 2 * Math.PI * r * r * 0.2 * r;
            double water = 0.5e-3 * shell * (1 - t / life) / 13;
            for (int i = 0; i < 13; i++) {
                // 1 на вершине и 12 по кругам на 30° и 60° над землёй
                double el = i == 0 ? Math.PI / 2 : (i <= 6 ? Math.PI / 6 : Math.PI / 3), az = i * Math.PI / 3 + (i > 6 ? Math.PI / 6 : 0);
                double cx = r * Math.cos(el) * Math.cos(az), cy = r * Math.sin(el), cz = r * Math.cos(el) * Math.sin(az);
                // клуб оболочки — шире шага между клубами (~0,5 r): у́же — оболочка распадалась на бусы
                out.blob(x + cx, y + cy, z + cz, SHELL * r, SHELL * r, 0, 0, water, 0.9f, 0.9f, 0.9f, CloudModel.T_AIR_K, 0);
            }
        }
    }

    /** Пакет взрыва ближе {@link #NEAR} к камере: его рисуют частицы и озвучивает ближняя модель. */
    public static boolean near(Vec3 pos) {
        return camera().distanceTo(pos) <= NEAR;
    }

    /**
     * Пакет взрыва пришёл (свет — сразу, звук — когда дойдёт): облако по модели ({@link BlastSource}, воздух — по свету
     * дня, погоде и биому в месте взрыва) — источник сетки дыма у камеры и картинка здесь. Бомба под землёй — прежняя
     * картинка. Облако модели — тому, кто рисует взрыв вблизи (null — бомба или мира нет).
     */
    @Nullable
    public static CloudModel add(S2C.Blast p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Vec3 pos = p.pos();
        Look look = null;
        CloudModel model = null;
        double y = pos.y;
        if (p.kind() == S2C.Blast.BUNKER) {
            look = BunkerCover.breaches((int) p.surfaceY(), pos.y) ? BUNKER_BREACH : BUNKER_DEEP;
            y = p.surfaceY();
        } else {
            model = new CloudModel(BlastSource.of(p), air(level, pos));
        }
        Event e = new Event(level, BlastSource.of(p), look, model, pos.x, y, pos.z, GroundMaterial.byId(p.material()), p.seed(), near(pos));
        trace(e, FarTerrain.of(level), camera());
        clear(e, camera());
        if (EVENTS.size() >= CAP) EVENTS.pollFirst().alive = false;
        EVENTS.addLast(e);
        // облако — источник облаков клиента: его рисуют запечённые клубы слоя ({@link SmokePuffs})
        if (model != null) SmokeGrid.add(e);
        return model;
    }

    /**
     * Облако взрыва в месте {@code pos} ({@link CloudModel}) — для картинки вблизи (вспышка, частицы твёрдого); то же, что
     * у события.
     */
    public static CloudModel model(S2C.Blast p) {
        ClientLevel level = Minecraft.getInstance().level;
        return new CloudModel(BlastSource.of(p), level == null ? CloudModel.Air.STANDARD : air(level, p.pos()));
    }

    /**
     * Воздух в месте взрыва: день по высоте солнца (как свет пелены), дождь, ветер мира в 10 м над морем ({@link ClientAir}) и
     * влажность — у воды 1, иначе — осадки биома (как облако Вильсона ядерки).
     */
    public static CloudModel.Air air(ClientLevel level, Vec3 pos) {
        float sun = net.minecraft.util.Mth.cos(level.getTimeOfDay(1) * net.minecraft.util.Mth.TWO_PI);
        boolean sky = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        double day = sky ? Math.max(0, Math.min(1, (sun + 0.06) / 0.24)) : 0;
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(pos);
        double humidity = !level.getFluidState(at).isEmpty() || !level.getFluidState(at.below()).isEmpty() ? 1
                : level.getBiome(at).value().getModifiedClimateSettings().downfall();
        return CloudModel.Air.of(day, level.getRainLevel(1), wind10(true), wind10(false), humidity);
    }

    /** Ветер мира в 10 м над морем вдоль x (или z), м/с — опорный ветер модели облака ({@link CloudModel.Air}). */
    public static double wind10(boolean x) {
        double y = ua.zentix.incoming.client.fx.plume.Air.SEA + 10;
        return 20 * (x ? ClientAir.windX(y) : ClientAir.windZ(y));
    }

    /** Раз в тик: забыть прошедшее и чужое измерение, звук — когда дошёл фронт, лучи по рельефу — по очереди. */
    static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            reset();
            return;
        }
        clock++;
        if (EVENTS.isEmpty()) return;
        Vec3 ear = camera();
        Sightline.Heights heights = FarTerrain.of(level);
        int traces = 0, clears = 0;
        Iterator<Event> it = EVENTS.iterator();
        while (it.hasNext()) {
            Event e = it.next();
            long age = clock - e.born;
            if (e.level != level || age > e.life() && age > 20 * e.poolEnd() && (!e.soundPending || age > e.soundWait)) {
                e.alive = false;
                it.remove();
                continue;
            }
            if (e.soundPending) {
                double d = e.distanceTo(ear);
                if (d <= Acoustics.SPEED * age) {
                    e.soundPending = false;
                    trace(e, heights, ear);
                    hear(e, d, age);
                } else if (age > e.soundWait) {
                    e.soundPending = false;
                }
            }
            if (clock - e.traced >= RETRACE && traces < TRACES_PER_TICK) {
                trace(e, heights, ear);
                if (e.glows) clear(e, ear);
                traces++;
            } else if (age < e.ballTicks() && clears < CLEARS_PER_TICK) {
                clear(e, ear);
                clears++;
            }
        }
    }

    private static void hear(Event e, double d, long age) {
        Outdoor.Heard heard = BlastSounds.far(e.source, new Vec3(e.x, e.y + LIFT, e.z), Math.max(1, 0.4 * e.ballRadius()),
                e.ground, d, e.path, e.seed);
        if (heard == null) return;
        lastHeard = heard;
        lastDistance = d;
        lastDelay = age;
    }

    /**
     * Шар в прорисовке закрывают блоки (дом, стена, свод пещеры), а блик и вуаль у глаза глубина не режет: лучи по
     * блокам к середине шара и к кольцу вокруг неё ({@link #disc}), открытая доля — доля блика и зарева закрытого шара.
     * Два луча к оси шара у угла высотки оба упирались бы в угол, и видимая половина шара светила бы без блика. Часть
     * диска в земле закрыта всем и в счёт не идёт. Дальше прорисовки шар закрывает рельеф ({@link #trace}). Шар в кадре
     * блик берёт ещё и по тому, что на экране ({@code SourceProbes}: и LOD Distant Horizons, которых лучи не видят).
     */
    private static void clear(Event e, Vec3 eye) {
        double r = e.ballRadius();
        if (r <= 0) return;
        if (e.distanceTo(eye) > Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0) {
            e.open = 1;
            return;
        }
        double mid = e.y + e.ballHeight(0.5 * e.ballTicks());
        int seen = 0, counted = 0;
        for (int i = 0; i <= RING_RAYS; i++) {
            disc(eye, e.x, mid, e.z, RING * r, i, DISC);
            if (i > 0 && DISC[1] < e.y + 0.5) continue;
            counted++;
            if (!FarFlightView.blocked(eye, DISC[0], DISC[1], DISC[2])) seen++;
        }
        e.open = seen / (float) counted;
    }

    /**
     * Точка i диска шара с центром (cx, cy, cz), каким его видит глаз: 0 — центр, 1…{@link #RING_RAYS} — по кругу
     * радиуса ring в плоскости поперёк луча зрения, первая — сбоку, по кругу вверх.
     */
    static void disc(Vec3 eye, double cx, double cy, double cz, double ring, int i, double[] out) {
        out[0] = cx;
        out[1] = cy;
        out[2] = cz;
        if (i == 0) return;
        double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return;
        dx /= len;
        dy /= len;
        dz /= len;
        // вбок — поперёк луча по горизонтали (луч отвесный — вдоль x), вверх по диску — бок × луч
        double sx = -dz, sz = dx, s = Math.sqrt(sx * sx + sz * sz);
        if (s < 1e-9) {
            sx = 1;
            sz = 0;
        } else {
            sx /= s;
            sz /= s;
        }
        double ux = -sz * dy, uy = sz * dx - sx * dz, uz = sx * dy;
        double a = 2 * Math.PI * (i - 1) / RING_RAYS, c = Math.cos(a) * ring, n = Math.sin(a) * ring;
        out[0] += sx * c + ux * n;
        out[1] += uy * n;
        out[2] += sz * c + uz * n;
    }

    private static void trace(Event e, Sightline.Heights heights, Vec3 eye) {
        Sightline.Result r = Sightline.trace(heights, e.x, e.y + LIFT, e.z, eye.x, eye.y, eye.z);
        e.hidden = r.hidden();
        e.path = r.pathDifference();
        e.traced = clock;
    }



    // ---------------------------------------------------------------- кадр

    static void collect(FarView view, FarSprites out, FxLights lights) {
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 cam = view.camera();
        double now = clock + view.partial();
        Arrays.fill(BRIGHTEST, 0);
        for (Event e : EVENTS) {
            if (e.level != level) continue;
            double age = Math.max(0, now - e.born);
            // основание относительно камеры
            double bx = e.x - cam.x, by = e.y - cam.y, bz = e.z - cam.z;
            double d = e.distanceTo(cam);
            // доля тела шара здесь: у облака модели — вся (вблизи и вдали — одни кадры); у бомбы — вблизи его рисуют частицы
            double w = e.model != null ? 1 : e.near ? view.farShare(d) : 1;
            e.glows = age < e.ballTicks();
            // линия, ниже которой место взрыва закрыто рельефом, — над основанием (тела в прорисовке закрывает и глубина)
            double line = e.hidden > 0 ? LIFT + e.hidden : 0;
            sky(e, view, out, age, bx, by, bz, d, line);
            light(e, view, out, lights, age, bx, by, bz, d, w, line);
            if (e.model != null) poolLight(e, view, lights, age, bx, by, bz);
            else column(e, view, out, age, bx, by, bz, line);
        }
    }

    /** Веса и столбец шаров {@link #fireballs} (каждый кадр — без новых объектов). */
    private static final double[] WEIGHT = new double[4];
    private static final org.joml.Vector4f COLUMN = new org.joml.Vector4f();

    /**
     * Огненные шары, что светят на дым сетки и на мир ({@code fx_pall}: {@code SmokeBalls}, вывод на кадр:
     * {@code PallBalls}): до четырёх самых заметных шаров облаков модели (свет у глаза — яркость × R²/d²) в свободные
     * столбцы {@code centres} (занятые — шары ядерки — не трогаются) — центр от глаза и радиус; в {@code light} — свет
     * чёрного тела у поверхности (линейный, белая стена в полдень — 1) × {@code exposure}, в w — {@code air} (ослабление
     * воздуха, 1/блок). true — хоть один шар добавлен.
     */
    public static boolean fireballs(Vec3 eye, float partial, double exposure, double air, Matrix4f centres, Matrix4f light) {
        double now = clock + partial;
        double[] weight = WEIGHT;
        boolean any = false;
        org.joml.Vector4f column = COLUMN;
        for (int i = 0; i < 4; i++) {
            centres.getColumn(i, column);
            weight[i] = column.w > 0 ? Double.MAX_VALUE : 0;
        }
        for (Event e : EVENTS) {
            if (e.model == null) continue;
            double age = Math.max(0, now - e.born), t = age / 20;
            double r = e.model.ballRadius(t);
            if (r <= 0) continue;
            double dx = e.x - eye.x, dy = e.y + e.model.ballHeight(t) - eye.y, dz = e.z - eye.z;
            e.model.ballLight(t, BB);
            double w = BB[3] * r * r / Math.max(dx * dx + dy * dy + dz * dz, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (weight[i] < weight[slot]) slot = i;
            if (w <= weight[slot]) continue;
            weight[slot] = w;
            centres.setColumn(slot, column.set((float) dx, (float) dy, (float) dz, (float) r));
            light.setColumn(slot, column.set((float) (BB[0] * exposure), (float) (BB[1] * exposure), (float) (BB[2] * exposure), (float) air));
            any = true;
        }
        return any;
    }

    /**
     * Огненный шар: тело — кадр анимации шара (от вспышки до сажи) поверх того, что за ним, мельче нескольких пикселей —
     * круг своего света ({@link FarSprites#shaped}); свет вокруг — бликом и вуалью на любой дальности. Тело — доля
     * {@code w} (у облака модели остальное — объём шара в сетке дыма).
     */
    private static void light(Event e, FarView view, FarSprites out, FxLights lights, double age, double bx, double by, double bz, double d, double w,
                              double line) {
        double t = view.air(d, by);
        double ticks = e.ballTicks();
        if (ticks <= 0 || age >= ticks || t < Sight.THRESHOLD) return;
        double u = age / ticks, r, half, body;
        FxAtlas.Sprite sprite;
        double fire;
        if (e.model != null) {
            // кадр — по состоянию шара: в горении — доля времени горения, остывая — температура (кадры запечённой симуляции
            // с её временем и температурой, FxAtlas.fireballFrame); тело тает в дым по той же температуре, что дым
            // проступает (CloudModel.glowShare)
            double s = age / 20, frame = FxAtlas.fireballFrame(s / Math.max(e.model.burnSeconds, 1e-6), e.model.ballKelvin(s));
            r = e.model.ballRadius(s);
            half = r * FarSprites.FIREBALL;
            body = CloudModel.glowShare(e.model.ballKelvin(s));
            sprite = FxAtlas.fireballAt(frame);
            fire = FxAtlas.fireLightAt(frame) * body;
        } else {
            r = e.ballRadius() * growth(u);
            half = e.ballRadius() * FarSprites.FIREBALL;
            body = fade(u);
            sprite = FxAtlas.ball((float) u);
            fire = FxAtlas.ballLight((float) u) * body;
        }
        if (r <= 0 || body <= 0) return;
        double h = e.ballHeight(age), b = e.brightness(age);
        double vis = visible(h, r, line), hidden = 1 - vis * e.open;
        float[] c = e.colour(age);
        // огонь шара на экране — в его кадре; свет вокруг (на дыме, блик, вуаль, зарево) не ярче: ночью свет ×1200
        // держал бы пыль под уже чёрным от сажи шаром оранжевым кольцом и небо над ним — заревом
        // шар светит и на дым, пыль и шлейфы вокруг — закрыт он от глаза или нет; тающий — своей светящейся долей
        lights.add(bx, by + h, bz, r, Sight.adapted(b, view.ambient()) * body, fire, c[0], c[1], c[2]);
        if (vis > 0) {
            out.shaped(bx, by + h, bz, r, sprite, half, e.phase, Sight.adapted(b, view.ambient()) * t, t, view.pixel(), c[0], c[1], c[2],
                    vis * w * body, vis * e.open, fire, LIGHT);
            brightest(view, bx, by + h, bz, vis);
        }
        // шар закрыт гребнем или постройкой: его свет рассеивает воздух над краем — зарево там, откуда место было бы
        // видно, пока шар светит; на любой дальности, как блик (вблизи за домом одной вспышки на экране не хватает)
        if (hidden > 0) halo(view, out, bx, by + Math.max(line, h), bz, HALO_SIZE * r, b * hidden, fire, t, c);
    }

    /**
     * Пламя лужи топлива светит на дым, пыль и шлейфы вокруг ({@link FxLights}): шар радиусом в полвысоты пламени по
     * Хескестаду ({@link CloudModel#poolFlame}) над воронкой, чёрное тело пламени
     * ({@link ua.zentix.incoming.client.fx.smoke.FirePlume#FLAME_K}), доля — сколько лужи ещё горит.
     */
    private static void poolLight(Event e, FarView view, FxLights lights, double age, double bx, double by, double bz) {
        double share = e.model.poolShare(age / 20);
        if (share <= 0) return;
        double r = 0.5 * e.model.poolFlame();
        BlackBody.light(ua.zentix.incoming.client.fx.smoke.FirePlume.FLAME_K, BB);
        double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
        lights.add(bx, by + r, bz, r, Sight.adapted(BB[3], view.ambient()) * share, POOL_FIRE * share, (float) (BB[0] / m), (float) (BB[1] / m),
                (float) (BB[2] / m));
    }

    /** Огонь языков пламени лужи на экране (доля белого): свет пламени вокруг не ярче (как у шара, {@link FxAtlas#fireLightAt}). */
    static final double POOL_FIRE = 0.8;

    /**
     * Радиус светящегося шара на доле u его жизни против полного — как в его кадре ({@code tools/gen_particles.py}:
     * 0,3 + 0,7·(1 − e^(−u/0,05)), к концу +6 %): вспышка — шар в 0,6 радиуса, за первые проценты жизни он почти весь.
     * Свет шара (блик, вуаль, точка вдали) — от этого радиуса.
     */
    static double growth(double u) {
        double f = (Math.floor(Math.min(u, 0.999) * FxAtlas.FIREBALL_FRAMES) + 0.5) / FxAtlas.FIREBALL_FRAMES;
        return (0.3 + 0.7 * (1 - Math.exp(-f / 0.05))) * (1 + 0.06 * f);
    }

    /** Доля шара на доле u его жизни: со второй половины тает (дальше — облако). */
    static double fade(double u) {
        return u <= BALL_FADE ? 1 : Math.max(0, 1 - (u - BALL_FADE) / (1 - BALL_FADE));
    }

    /**
     * Яркость вспышки бомбы на age-м тике: держится почти всю свою длину и гаснет к концу — глаз складывает свет 50–100 мс
     * (Блох, Брока–Зульцер), и вспышка в тик короче, чем кажется настоящая.
     */
    static double flash(Look k, double age) {
        double f = age / Math.max(1, k.flashTicks());
        return f >= 1 || k.flashTicks() == 0 ? 0 : k.flash() * (1 - f * f);
    }

    /** Яркость шара бомбы на доле u его жизни: остывает от ~2000 K; ярче дневного неба — первую половину жизни. */
    static double ball(double u) {
        double f = 1 - Math.min(1, u);
        return BALL * f * f * f;
    }

    /** Самое яркое тело кадра — для лога: числа {@link Sight#light} последнего нарисованного (в {@link #LIGHT}). */
    private static void brightest(FarView view, double x, double y, double z, double share) {
        if (LIGHT[2] * share <= BRIGHTEST[3] * BRIGHTEST[4]) return;
        double px = Math.sqrt(x * x + y * y + z * z) * view.pixel();
        BRIGHTEST[0] = LIGHT[0] / px;
        BRIGHTEST[1] = LIGHT[3] / px;
        BRIGHTEST[2] = LIGHT[4] / px;
        BRIGHTEST[3] = LIGHT[2];
        BRIGHTEST[4] = share;
    }

    /**
     * Мягкий свет без тела (зарево закрытого шара) с центром (gx, gy, gz): ореол радиуса rad — светящийся воздух вокруг
     * шара; что ближе него, зарево закрывает ({@link FarSprites#glow}); мельче точки — бледнее (поток тот же). Яркость —
     * {@link #behind}.
     *
     * @param light яркость шара против белого экрана, без привыкания глаза
     * @param fire  огонь шара на экране ({@link FxAtlas#fireLightAt}): зарево не ярче
     */
    private static void halo(FarView view, FarSprites out, double gx, double gy, double gz, double rad, double light, double fire, double t, float[] c) {
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double floor = Math.max(rad, 0.5 * Sight.MIN_PIXELS * view.pixel() * d), k = rad / floor;
        out.glow(gx, gy, gz, floor, rad, c[0], c[1], c[2], (float) (Math.min(behind(light, view.ambient(), t), fire) * k * k));
    }

    /**
     * Яркость зарева закрытого шара яркостью light против белого (с воздухом t и привыканием глаза к свету неба ambient):
     * доля {@link #BEHIND} его света, но не ярче вуали видимого шара ({@link Sight#VEIL_PEAK}) — это тот же рассеянный
     * свет. Ночью свет ×1200 ({@link Sight#adapted}), и зарево в полную силу заливало бы кремовым пол-кадра шире вуали
     * самого шара: закрытый шар светил бы ярче открытого.
     */
    static double behind(double light, double ambient, double t) {
        return Math.min(Sight.VEIL_PEAK, Sight.adapted(light * BEHIND, ambient) * t);
    }

    /** Цвет света шара и вспышки бомбы: пока вспышка ярче остывающего шара — её почти белый, потом — шара ({@link #tint}). */
    private static float[] glare(double flash, double u) {
        float[] c = tint(u);
        float f = (float) (flash / Math.max(1e-9, flash + ball(u)));
        for (int i = 0; i < 3; i++) c[i] += (FLASH[i] - c[i]) * f;
        return c;
    }

    /** Цвет остывающего шара бомбы на доле u его жизни: раскалён → оранжевый → тёмно-красный. */
    private static float[] tint(double u) {
        float[] from = u < 0.35 ? BALL_HOT : BALL_MID, to = u < 0.35 ? BALL_MID : BALL_COLD;
        float s = (float) (u < 0.35 ? u / 0.35 : (u - 0.35) / 0.65);
        TINT[0] = from[0] + (to[0] - from[0]) * s;
        TINT[1] = from[1] + (to[1] - from[1]) * s;
        TINT[2] = from[2] + (to[2] - from[2]) * s;
        return TINT;
    }

    /**
     * Зарево на облаках над местом, пока светит шар ({@link #cloudGlow}): днём его не видно, ночью низ облаков над взрывом
     * вспыхивает, в дождь (облака сплошные) — сильнее. Вблизи (ближе трёх радиусов пятна) его заменяет вспышка на экране;
     * облаков у измерения нет — нет и зарева.
     */
    private static void sky(Event e, FarView view, FarSprites out, double age, double bx, double by, double bz, double d, double line) {
        double r = e.ballRadius(), ticks = e.ballTicks();
        if (r <= 0 || age >= ticks || Double.isNaN(view.clouds())) return;
        double h = Math.max(SKY_LOW, view.clouds() - e.y), rs = SKY_SPREAD * h, share = e.near ? smoothstep(rs, 3 * rs, d) : 1;
        double gy = by + h, dc = Math.sqrt(bx * bx + gy * gy + bz * bz), t = view.air(dc, gy);
        if (share <= 0 || t < Sight.THRESHOLD) return;
        double vis = visible(h, rs, line);
        if (vis <= 0) return;
        double peak = SKY_PEAK * (SKY_CLEAR + (1 - SKY_CLEAR) * view.rain()) * t * vis * share;
        float[] c = e.colour(age);
        out.glow(bx, gy, bz, rs, rs, c[0], c[1], c[2], (float) (peak * cloudGlow(e.brightness(age), r, h, view.ambient())));
    }

    /**
     * Яркость низа облаков на высоте h над источником яркости b радиуса r против белого экрана, с привыканием глаза
     * ({@link Sight#adapted}), не ярче белого: шар светит силой b·r² (на единицу π), освещённость облаков — сила / h²,
     * их яркость — {@link #CLOUD_ALBEDO}·E/π. Днём это тысячные доли неба, ночью — в сотни раз ярче него.
     */
    static double cloudGlow(double b, double r, double h, double ambient) {
        double k = r / h;
        return Math.min(1, Sight.adapted(CLOUD_ALBEDO * b * k * k, ambient));
    }

    /**
     * Столб бомбы (прежняя картинка): поднимается, расплывается, уходит по ветру; сначала тает низ.
     */
    private static void column(Event e, FarView view, FarSprites out, double age, double bx, double by, double bz, double line) {
        Look k = e.look;
        double rise = rise(age, k.riseTicks());
        double grey = smoothstep(0, 0.5 * k.life(), age);
        float sr = (float) (DARK[0] + (AGED[0] - DARK[0]) * grey), sg = (float) (DARK[1] + (AGED[1] - DARK[1]) * grey),
                sb = (float) (DARK[2] + (AGED[2] - DARK[2]) * grey);
        double opacity = OPACITY * emerge(k, age);
        double spread = 1 + 0.5 * age / k.life(), grow = 0.35 + 0.65 * rise;
        for (int i = 0; i < e.f.length; i++) {
            double f = e.f[i];
            double a = opacity * (1 - smoothstep(k.life() * (0.35 + 0.45 * f), k.life(), age));
            if (a < 0.004) continue;
            double r = radius(k, f) * e.size[i] * grow * spread;
            double h = height(k, f, r, rise);
            double vis = visible(h, r, line);
            if (vis <= 0) continue;
            // ветер мира на высоте клуба (выше — сильнее: столб клонится); низ столба — дым, что подходит позже, и он
            // сносится меньше, как прежде (доля shear)
            double shear = 0.3 + 0.7 * f * rise, wy = view.camera().y + by + h;
            double px = bx + e.ox[i] * r * SPREAD + ClientAir.windX(wy) * age * shear, py = by + h,
                    pz = bz + e.oz[i] * r * SPREAD + ClientAir.windZ(wy) * age * shear;
            double d = Math.sqrt(px * px + py * py + pz * pz);
            double t = view.air(d, py);
            if (t < Sight.THRESHOLD) continue;
            double outside = smoothstep(0.5 * r, r, d);
            if (outside <= 0) continue;
            Sight.point(r, 1, 1, d, view.pixel(), POINT);
            float share = e.dust[i], lit = e.shade[i] * view.ambient();
            float cr = (sr + (e.dustR - sr) * share) * lit, cg = (sg + (e.dustG - sg) * share) * lit, cb = (sb + (e.dustB - sb) * share) * lit;
            out.puff(px, py, pz, POINT[0], e.rot[i] + (float) (age * e.spin[i]), e.tex[i], cr, cg, cb, (float) (a * vis * POINT[1] * t * outside));
        }
    }

    /**
     * С какого тика дым шара бомбы проступает: шар тает со второй половины жизни ({@link #BALL_FADE}), и дым — это он
     * же, остывший; раньше он закрыл бы яркий шар. Так же и вблизи ({@code Explosions}).
     */
    public static int smokeFrom(Look k) {
        return (int) Math.ceil(BALL_FADE * k.ballTicks());
    }

    /** Доля столба бомбы на age-м тике: проступает, пока тает шар ({@link #smokeFrom}), не быстрее {@link #FADE_IN} тиков. */
    static double emerge(Look k, double age) {
        double from = smokeFrom(k);
        return smoothstep(from, Math.max(from + FADE_IN, k.ballTicks()), age);
    }

    /** Доля высоты, на которую столб бомбы поднялся за age тиков: быстро вначале, к riseTicks — 95 %. */
    static double rise(double age, double riseTicks) {
        return 1 - Math.exp(-3 * age / riseTicks);
    }

    /**
     * Доля высоты столба i-го из n клубов; jitter −0,5..0,5 — разброс в долю {@link #JITTER} шага. Внизу гуще: там клубы
     * мельче, и столб не рвётся на бусины.
     */
    static double fraction(int i, int n, double jitter) {
        return Math.pow(Math.max(0, Math.min(1, (i + 0.5 + JITTER * jitter) / n)), 1.3);
    }

    /** Радиус клуба на доле высоты f поднявшегося столба (без разброса размера), блоков: от ножки к шапке шире. */
    static double radius(Look k, double f) {
        return k.base() + (k.top() - k.base()) * f;
    }

    /** Высота центра клуба радиуса r над основанием: нижний стоит на земле, остальные — на своей доле подъёма. */
    static double height(Look k, double f, double r, double rise) {
        return 0.6 * r + f * k.column() * rise;
    }

    /**
     * Какая доля клуба (центр на высоте h над основанием, радиус r) видна над линией рельефа {@code line} (0 — место
     * открыто); часть под основанием — в земле и не в счёт. Сглажено: клуб уходит за гребень плавно.
     */
    static double visible(double h, double r, double line) {
        if (line <= 0) return 1;
        double lo = Math.max(0, h - r), hi = h + r;
        if (hi <= lo) return line <= h ? 1 : 0;
        double v = Math.max(0, Math.min(1, (hi - Math.max(line, lo)) / (hi - lo)));
        return v * v * (3 - 2 * v);
    }

    static boolean isEmpty() {
        return EVENTS.isEmpty();
    }

    static void reset() {
        for (Event e : EVENTS) e.alive = false;
        EVENTS.clear();
        lastHeard = null;
    }

    /**
     * Для лога сценария (раз в секунду): сколько взрывов вдали живо и последний дальний звук — до уха, задержка от
     * пакета (и d / 17,15 — когда фронт должен был дойти), громкость, запас слышимости и потери на низах (преграда,
     * тень, дождь), насколько верха глуше низов и сколько из этого от земли. Ничего дальнего ещё не было — пустая строка.
     */
    public static String describe() {
        String light = BRIGHTEST[4] <= 0 ? "" : String.format(Locale.ROOT, "; brightest: core %.1f px, overexposure %.0f, glare %.1f px, veil %.1f px, "
                + "visible %.2f", BRIGHTEST[0], BRIGHTEST[3], BRIGHTEST[1], BRIGHTEST[2], BRIGHTEST[4]);
        if (lastHeard == null) return EVENTS.isEmpty() ? "" : "blasts " + EVENTS.size() + light;
        Outdoor.Heard h = lastHeard;
        return String.format(Locale.ROOT, "blasts %d; sound: %.0f blocks, delay %d ticks (front %.1f ticks), volume %.3f, margin %.1f dB, barrier %.1f dB, "
                        + "shadow %.1f dB, rain %.1f dB, highs %.1f dB, of which ground %.1f dB", EVENTS.size(), lastDistance, lastDelay,
                lastDistance / Acoustics.SPEED, h.volume(), h.margin(), h.barrier(), h.shadow(), h.masking(), 20 * Math.log10(Math.max(1e-6, h.highs())),
                -h.ground()) + light;
    }

    private static Vec3 camera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double gauss(SplittableRandom rnd) {
        // сумма трёх равномерных — колокол без хвостов (клуб не улетает от столба)
        return (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 1.15;
    }
}
