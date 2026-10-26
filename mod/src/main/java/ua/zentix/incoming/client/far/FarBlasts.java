package ua.zentix.incoming.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.BlastTime;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.Ejecta;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.fx.smoke.WaterBlast;
import ua.zentix.incoming.client.sound.Acoustics;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.Outdoor;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.ShockOptics;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/**
 * Взрывы вдали: вспышка, огненный шар, облако дыма и пыли и раскат с задержкой по скорости звука.
 * <p>
 * Пакет взрыва — одно событие: облако — модель взрыва ({@link CloudModel}: заряд, топливо, грунт и воздух) — одна и та же
 * вблизи и вдали. Событие — источник облаков клиента ({@link SmokeGrid.Source}): клубы модели и облако конденсации
 * (топливо в воронке горит огнём на блоках сервера, его дым — пожар среди пожаров клиента, {@code FireSmoke.pool}); рисует их слой эффектов запечёнными клубами ({@link SmokePuffs}), объём сетки — нет (клетка
 * в 2–32 м облако в метры–десятки метров не разрешает). Огненный шар — кадры {@code fx/particle/fireball} (симуляция
 * горения в Blender, цвет — чёрное тело поля температуры) размера и времени шара модели (шар топлива по CCPS или шар
 * продуктов детонации, потом остывание подсосом воздуха), яркость — чёрное тело его температуры ({@link BlackBody}): днём
 * шар топлива (~1500 K) — порядка белой стены, вспышка продуктов детонации (~3000 K) — сотни.
 * <p>
 * Бомба под землёй ({@code kind} BUNKER) — те же облака модели от той доли заряда, что вышла в воздух
 * ({@code warhead.BuriedBlast}): прорыв наружу — облако над воронкой, глубже — газы и пыль из хода, провал, пыль входа
 * ({@link #cloud}).
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
    /**
     * Доля света шара, что светит заревом, когда шар закрыт:
     * воздух рассеивает свет шара радиуса R на расстоянии r — яркость σ·ℓ·R²/(4r²) от яркости шара (σ ясного воздуха
     * ~2·10⁻⁴ на метр, путь ℓ ~100 м, r — 2–3 R), тысячная (⚠ оценка одного случая). Днём это едва видно, ночью зарево упирается в яркость вуали
     * ({@link #halo}).
     */
    static final double BEHIND = 0.001;
    /** Радиус светящегося воздуха вокруг закрытого шара, радиусов шара: дальше рассеянный свет слабеет как 1/r. */
    static final double HALO_SIZE = 3;
    /** Центр огненного шара над точкой взрыва (радиусов): наземный шар — полусфера, низ в земле. */
    public static final double BALL_LIFT = 0.45;
    /**
     * Зарево на облаках: низ облаков над местом освещён шаром (освещённость E = I/h², яркость ≈ альбедо·E/π) — пятно
     * радиуса {@link #SKY_SPREAD} высот облаков над местом (на одной высоте — треть пика, на двух — десятая); пик против
     * белого — не больше SKY_PEAK (в дождь, облака сплошные), в ясную ночь — {@link #SKY_CLEAR} от него. Облака ниже
     * {@link #SKY_LOW} блоков над местом — считаем на этой высоте. Альбедо — ⚠ оценка; пятно, пик и ясное небо — подача.
     */
    static final double CLOUD_ALBEDO = 0.7, SKY_SPREAD = 1, SKY_PEAK = 0.45, SKY_CLEAR = 0.45, SKY_LOW = 40;

    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    /**
     * Ушедшие из вида сверх {@link #CAP}, чей звук ещё идёт к уху: раскат живёт по времени пути звука
     * ({@link Event#soundWait}), а не по месту в списке картинки — в налёте больше 256 ударов ранний далёкий не молчит.
     */
    private static final ArrayDeque<Event> SOUNDS = new ArrayDeque<>();
    /** Числа на кадр без выделения: точка {@link Sight#point}, свет {@link Sight#light} и цвет шара. */
    private static final double[] POINT = new double[2], LIGHT = new double[5], BB = new double[4];
    /** Самое яркое тело последнего кадра (для лога): ядро, блик и вуаль в пикселях, пересвет, доля над рельефом. */
    private static final double[] BRIGHTEST = new double[5];
    private static final float[] TINT = new float[3];
    /** Последний клиентский pre-tick: возраст обычного эффекта не зависит от порядка post-tick слушателей. */
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
        /** Облако по модели. */
        final CloudModel model;
        /** Основание: точка удара; у бомбы под землёй — место, откуда вышло облако (поверхность, устье хода, провал). */
        final double x, y, z;
        final GroundMaterial ground;
        /** Взрыв на воде: столб брызг, корона и водяная пыль ({@link WaterBlast}); null — на суше. */
        @Nullable
        final WaterBlast water;
        /** Зерно взрыва: клубы и вариант записи звука. */
        final long seed;
        final long born;
        /** Пакет пришёл ближе {@link #NEAR}: ближняя картинка и звук есть. */
        final boolean near;
        /**
         * Чей раскат слышно вдали: обычно — сам взрыв ({@link #source}); у облака без своего заряда (вход бомбы в грунт,
         * обвал свода) — доля энергии удара, что ушла в воздух, глухим ударом из-под земли.
         */
        final BlastSource voice;
        /**
         * Альбедо ω' пыли по каналам: поверхностей вокруг (пыль волны у земли, ножка), вещества цели (пыль дробления, пыль
         * облака — смесью с первой долей {@link CloudModel#capMatterShare}) и комьев выброса (завеса — цвет самого материала:
         * влажные комья с гумусом темнее сухой пыли, physics.md фронта F).
         */
        final float dustR, dustG, dustB, matterR, matterG, matterB, clodR, clodG, clodB;
        /** Завеса выброса рыхлого грунта ({@link Ejecta}); null — у цели её нет. */
        @Nullable
        final Ejecta curtain;
        /** Ослабление зёрен завесы в кг пыли смеси ({@link SmokeGrid#dustK} по цвету комьев) на кг зёрен. */
        final double curtainK;
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
        /** Фронт ударной волны во времени ({@link #front}); null — ещё не считался. */
        @Nullable
        ChemicalFront front;

        Event(ClientLevel level, BlastSource source, CloudModel model, double x, double y, double z, GroundMaterial ground, long seed,
              boolean near, BlastSource voice) {
            this.level = level;
            this.source = source;
            this.voice = voice;
            this.soundWait = (long) Math.ceil(BlastSounds.range(voice) / Acoustics.SPEED);
            this.model = model;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ground = ground;
            this.water = ground.isWater() && source.chargeKg() > 0 ? water(level, new Vec3(x, y, z), source.chargeKg(), model.air) : null;
            this.seed = seed;
            this.born = BlastTime.ticks();
            this.near = near;
            this.soundPending = !near;
            // топливо в воронке горит огнём на блоках сервера: его дым и звук приходят с ним пакетом (BlastEffects.fuelFire)
            // огонь, что поджёг шар топлива, — в дым с первых секунд нынешним счётом, а не на проход обхода позже; без топлива
            // удар сам ничего не поджигает (`Craters.ignite`)
            if (source.fuelKg() > 0) ua.zentix.incoming.client.fx.FireWatch.hot(level, x, z);
            float[] dust = dustColour(ground);
            dustR = SmokeGrid.dustAlbedo(dust[0]);
            dustG = SmokeGrid.dustAlbedo(dust[1]);
            dustB = SmokeGrid.dustAlbedo(dust[2]);
            BlastTarget.Matter m = model.target.matter();
            matterR = SmokeGrid.dustAlbedo(m.r);
            matterG = SmokeGrid.dustAlbedo(m.g);
            matterB = SmokeGrid.dustAlbedo(m.b);
            GroundMaterial gm = model.target.material();
            clodR = SmokeGrid.dustAlbedo(gm.r);
            clodG = SmokeGrid.dustAlbedo(gm.g);
            clodB = SmokeGrid.dustAlbedo(gm.b);
            curtain = curtain(model, source);
            curtainK = curtain == null ? 0 : m.extinction() / SmokeGrid.dustK(clodR, clodG, clodB);
        }

        double distanceTo(Vec3 p) {
            double dx = x - p.x, dy = y + LIFT - p.y, dz = z - p.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** До точки взрыва, блоков: от неё идёт фронт, как у ближнего звука ({@code BlastEffects.Timeline}). */
        double blastDistanceTo(Vec3 p) {
            double dx = x - p.x, dy = y - p.y, dz = z - p.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** Фронт этого взрыва ({@link #arrivalTicks(ChemicalFront, double)}); у бомбы под землёй и без заряда — null. */
        @Nullable
        ChemicalFront front() {
            if (front == null && !source.underground() && source.chargeKg() > 0) front = ChemicalFront.cached(source.chargeKg(), source.surface());
            return front;
        }

        /** Фронт раската ({@link #voice}): у самого взрыва — его фронт, у доли из-под земли — со скоростью звука. */
        @Nullable
        ChemicalFront soundFront() {
            return voice == source ? front() : null;
        }

        /** Сколько тиков событие живёт (облако видно). */
        double life() {
            // водяная пыль, пена и волна взрыва на воде живут дольше облака без пыли воронки
            return Math.max(model.life, water == null ? 0 : Math.max(water.mistLife(), water.waterLife())) * 20;
        }

        /** Сколько тиков светит огненный шар (0 — шара нет). */
        double ballTicks() {
            return model.fireSeconds * 20;
        }

        /** Наибольший радиус огненного шара, блоков (полуразмер его картинки). */
        double ballRadius() {
            return Math.max(0.5 * model.fuelBall, model.heBall);
        }

        /** Высота центра шара над основанием на age-м тике, блоков. */
        double ballHeight(double age) {
            return model.ballHeight(age / 20);
        }

        /**
         * Яркость шара на age-м тике против белого экрана днём (без привыкания глаза): спектр температуры газа,
         * степень черноты — из энергетического бюджета модели.
         */
        double brightness(double age) {
            model.ballLight(age / 20, BB);
            return BB[3];
        }

        /**
         * Свет источника на age-м тике ({@link CloudModel#emittedLight}), без экспозиции и дневного/ночного фона.
         */
        double emitted(double age) {
            model.emittedLight(age / 20, BB);
            return BB[3];
        }

        /** Цвет света шара на age-м тике (наибольший канал — 1). */
        float[] colour(double age) {
            model.ballLight(age / 20, BB);
            double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
            for (int i = 0; i < 3; i++) TINT[i] = (float) (BB[i] / Math.max(m, 1e-12));
            return TINT;
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
            double t = sourceAge(seconds);
            return model.sigmaH(t) / Math.max(model.sigmaH(0), 1e-6);
        }

        /** Брызги — у взрыва на воде. */
        @Override
        public boolean sprayClubs() {
            return water != null;
        }

        @Override
        public boolean sprayClubs(double seconds) {
            // После возвращения последней струи — взвесь мелких капель, не падающие полосы брызг.
            return water != null && water.aloft(sourceAge(seconds)) > 0;
        }

        /** Над рельефом, что закрывает место взрыва вдали (последний луч, {@link #trace}). */
        @Override
        public double visible(double wy, double r) {
            return FarBlasts.visible(wy - y, r, hidden > 0 ? LIFT + hidden : 0);
        }

        private double sourceAge(double seconds) {
            // Only the fractional tick comes from SmokeGrid; its post-tick counter may already have advanced at birth.
            return BlastTime.seconds(BlastTime.ticks(), born, 20 * (seconds - SmokeGrid.seconds(0)));
        }

        /** Источник облака: клубы модели в мире, облако конденсации и брызги взрыва на воде. */
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            if (!alive || level != Minecraft.getInstance().level) return false;
            double t = sourceAge(seconds);
            if (t >= Math.max(model.life, water == null ? 0 : water.mistLife())) return false;
            if (t < model.life) {
                double growth = puffGrowth(seconds);
                // облако разрыва в воздухе — своими кадрами (по росту облака, как облако дыма); пыль раздробленной цели — кадрами
                // своего вещества: рыхлого — выброса, связного — дробления (по доле её жизни)
                boolean air = model.target.family() == BlastTarget.Family.AIR, loose = model.target.matter().loose;
                SmokeGrid.Look cap = air ? SmokeGrid.Look.AIRBURST : SmokeGrid.Look.SMOKE, crush = loose ? SmokeGrid.Look.EJECTA : SmokeGrid.Look.CRUSH;
                double capFrame = air ? FxAtlas.growthFrame(cap, growth) : 0;
                double crushFrame = FxAtlas.lifeFrame(crush, model.crushed.life > 0 ? t / model.crushed.life : 0);
                double share = model.capMatterShare(t);
                float cr = mix(matterR, dustR, share), cg = mix(matterG, dustG, share), cb = mix(matterB, dustB, share);
                model.blobs(t, new CloudModel.Blobs() {
                    @Override
                    public void blob(double bx, double by, double bz, double sh, double sv, double soot, double dustKg, double water, double kelvin,
                                     double rise) {
                        out.look(cap, capFrame);
                        out.blob(x + bx, y + by, z + bz, sh, sv, soot, dustKg, water, cr, cg, cb, kelvin, rise);
                    }

                    @Override
                    public void ground(double bx, double by, double bz, double sh, double sv, double dustKg, double kelvin) {
                        out.look(SmokeGrid.Look.SMOKE, 0);
                        out.blob(x + bx, y + by, z + bz, sh, sv, 0, dustKg, 0, dustR, dustG, dustB, kelvin, 0);
                    }

                    @Override
                    public void crushed(double bx, double by, double bz, double sh, double sv, double dustKg, double kelvin) {
                        out.look(crush, crushFrame);
                        out.blob(x + bx, y + by, z + bz, sh, sv, 0, dustKg, 0, matterR, matterG, matterB, kelvin, 0);
                    }

                    // ножка — трубкой, как шлейф пожара (вблизи и вдали без бус); кадр её клубов — по росту облака, как у шапки
                    @Override
                    public void tube(double ax, double ay, double az, double sa, double massA, double bx, double by, double bz, double sb,
                                     double massB, double kelvin, double rise) {
                        out.tube(x + ax, y + ay, z + az, sa, sa, 0, massA, x + bx, y + by, z + bz, sb, sb, 0, massB, dustR, dustG, dustB, kelvin, rise,
                                sa / Math.max(growth, 1));
                    }
                });
            }
            out.look(SmokeGrid.Look.SMOKE, 0);
            if (curtain != null && t < curtain.life) curtainClubs(curtain, t, seed, x, y, z, curtainK, clodR, clodG, clodB, out);
            if (t < model.condensationSeconds()) condensation(t, out);
            // Струи выходят из глади, даже когда сам заряд под ней.
            if (water != null) water.blobs(t, out, x, y + water.depth, z);
            return true;
        }

        /** Water follows the pressure history, rather than appearing at the final radius in the first frame. */
        private void condensation(double t, SmokeGrid.Sink out) {
            model.condensation(t, (bx, by, bz, sh, sv, soot, dust, water, kelvin, rise) ->
                    out.blob(x + bx, y + by, z + bz, sh, sv, 0, 0, water, 0.95f, 0.95f, 0.95f, kelvin, rise));
        }

    }

    /**
     * Цвет толстой пыли взрыва на экране: пыль грунта — сухие мелкие зёрна (мельче ~50 мкм), и толстый слой их светлее
     * самого грунта: цвет блока — влажная почва с гумусом и крупным зерном, у которых поглощение больше (у мелкой
     * минеральной пыли альбедо однократного рассеяния на 550 нм — 0,9–0,97, Dubovik и др., J. Atmos. Sci. 59, 2002).
     * Земля под ударом считается тем же сухим суглинистым песком, что у ядерки ({@code DustModel.Surface.REFERENCE}), —
     * и пыль того же цвета, что стена пыли ядерки ({@code NukePall.DUST}); песок и снег — своего (светлее). В 3.5.0 пыль
     * красилась цветом блока земли — тёмный, почти чёрный диск у земли (F3).
     */
    public static float[] dustColour(GroundMaterial ground) {
        if (ground == GroundMaterial.SAND || ground == GroundMaterial.SNOW) return new float[]{ground.r, ground.g, ground.b};
        double[] d = ua.zentix.incoming.client.nuclear.NukePall.DUST;
        return new float[]{(float) d[0], (float) d[1], (float) d[2]};
    }

    /**
     * Завеса выброса воронки у цели из рыхлого ({@link BlastTarget.Family#SOIL}): воронка закона по грунту материала, выброс —
     * её масса без пыли, что ушла в воздух ({@link CloudModel#DUST_LOFTED}), комьями — доля вещества ({@link Ejecta}). Нет
     * завесы — null.
     */
    @Nullable
    static Ejecta curtain(CloudModel model, BlastSource source) {
        BlastTarget t = model.target;
        if (t.family() != BlastTarget.Family.SOIL || source.underground() || source.chargeKg() <= 0) return null;
        double r = ua.zentix.incoming.nuclear.model.CraterModel.chargeRadius(source.chargeKg(), t.material().soil());
        Ejecta e = new Ejecta(r, t.ballistic(source.chargeKg()), t.matter().clods);
        return e.grains > 0 ? e : null;
    }

    /**
     * Клубы завесы выброса {@code c} в возрасте t с основанием (x, y, z): струи — отрезками, зёрна — пылью с тем же
     * ослаблением ({@code k} — кг пыли смеси на кг зёрен, альбедо ω' комьев), кадры удара в пыль по доле жизни завесы (до
     * девяти десятых на земле); потом у приёмника снова облако дыма.
     */
    static void curtainClubs(Ejecta c, double t, long seed, double x, double y, double z, double k, float r, float g, float b, SmokeGrid.Sink out) {
        out.look(SmokeGrid.Look.EJECTA, FxAtlas.lifeFrame(SmokeGrid.Look.EJECTA, t / Math.max(c.settled, 1e-3)));
        c.jets(t, seed, (jx, jy, jz, ax, ay, az, sigma, kg) -> out.segment(x + jx, y + jy, z + jz, ax, ay, az, sigma, sigma, 0, kg * k, 0, r, g, b,
                CloudModel.T_AIR_K, 0));
        out.look(SmokeGrid.Look.SMOKE, 0);
    }

    /** Альбедо смеси двух пылей: доля {@code share} первой (по ослаблению). */
    static float mix(float a, float b, double share) {
        return (float) (b + (a - b) * Math.clamp(share, 0, 1));
    }

    /**
     * Во что попал заряд ({@link BlastTarget}): материал пакета и опора у грани удара из пакета ({@link S2C.Blast.Support}) —
     * её считает пробой сервера тем же правилом, что сносит плиту, лучом по нормали грани ({@code warhead.SlabBreach#support}):
     * плита над пустотой — та и только та, которую сервер пробивает, на любой дистанции (у клиента вдали блоков нет, у сервера
     * — есть); место заряда на грани и нормаль — ось дыры и откола. Опоры в
     * пакете нет (повтор до этого поля, взрыв без сервера, заряд не приложен к преграде) — по веществу материала, опора
     * сплошная.
     */
    static BlastTarget target(S2C.Blast p, GroundMaterial material, double tnt, double hob) {
        // заряд лежит на грани: точка взрыва — за ней по нормали на lift (точка прицела — полблока в блок)
        return p.support().map(s -> BlastTarget.of(material, s.loose(), s.slab(), s.breach(), p.pos().subtract(s.normal().scale(s.lift())),
                        s.normal()))
                .orElseGet(() -> BlastTarget.of(material, tnt, hob));
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
        BlastSource source = BlastSource.of(p);
        GroundMaterial ground = GroundMaterial.byId(p.material());
        if (p.kind() == S2C.Blast.BUNKER) {
            // бомба под землёй: прорвалась наружу — облако той доли заряда, что вышла в воздух, над воронкой (пыль — грунт
            // воронки выброса); глубже — облако из хода приходит своим пакетом (S2C.Vent)
            ua.zentix.incoming.warhead.BuriedBlast law = BlastSource.law(pos.y, p.surfaceY());
            if (!law.breaches()) return null;
            Vec3 top = new Vec3(pos.x, p.surfaceY(), pos.z);
            CloudModel model = CloudModel.buried(source, air(level, pos), law, ground.isWater(), ground(level, top));
            return cloud(level, source, model, top, ground, p.seed());
        }
        // удар — в то, что под точкой (земля, крыша, стена — всё равно; над аппаратом в воздухе — рельеф далеко внизу): что
        // вокруг и на какой высоте, видно по верхним блокам (ground); во что попал и есть ли за ним пустота — пробой сервера по оси
        // удара (опора в пакете, target)
        double hob = overSurface(level, pos);
        BlastTarget target = target(p, ground, source.chargeKg(), hob);
        return cloud(level, source, new CloudModel(source, air(level, pos), target, hob, ground(level, pos)), pos, ground, p.seed());
    }

    /**
     * Облако модели {@code model} взрыва {@code source} с основанием в {@code at}: событие — источник облаков клиента (его
     * рисуют запечённые клубы слоя, {@link SmokePuffs}), его шар и свет, раскат вдали. Возвращает модель.
     */
    public static CloudModel cloud(ClientLevel level, BlastSource source, CloudModel model, Vec3 at, GroundMaterial ground, long seed) {
        return cloud(level, source, model, at, ground, seed, source);
    }

    /**
     * Как {@link #cloud(ClientLevel, BlastSource, CloudModel, Vec3, GroundMaterial, long)}, но раскат вдали — у
     * {@code voice} ({@link Event#voice}): у облака без своего заряда звук события — свой.
     */
    public static CloudModel cloud(ClientLevel level, BlastSource source, CloudModel model, Vec3 at, GroundMaterial ground, long seed,
                                   BlastSource voice) {
        Event e = new Event(level, source, model, at.x, at.y, at.z, ground, seed, near(at), voice);
        trace(e, FarTerrain.of(level), camera());
        clear(e, camera(), 0);
        admit(EVENTS, e, CAP, old -> {
            old.alive = false;
            if (old.soundPending) SOUNDS.addLast(old);
        });
        SmokeGrid.add(e);
        return model;
    }

    /** {@code e} — в конец {@code events}; сверх {@code cap} первое уходит в {@code gone} (картинка — да, звук — свой срок). */
    static <E> void admit(ArrayDeque<E> events, E e, int cap, java.util.function.Consumer<E> gone) {
        if (events.size() >= cap) gone.accept(events.pollFirst());
        events.addLast(e);
    }

    /**
     * Тики с взрыва до прихода фронта на {@code d} блоков от точки взрыва — один закон у ближнего звука, дальнего и
     * видимого кольца: {@link ChemicalFront} (Кинни–Грэхем и Ренкин–Гюгонио, вблизи быстрее звука); без своей волны
     * ({@code front} null) — со скоростью звука.
     */
    public static double arrivalTicks(@Nullable ChemicalFront front, double d) {
        return front == null ? d / Acoustics.SPEED : 20 * front.arrivalSeconds(d);
    }

    /**
     * Разрыв в воздухе ({@code S2C.Intercept}: боевая часть зенитной ракеты, заряд {@code source}) — то же облако модели,
     * что у взрыва на земле, на своей высоте над рельефом ({@link #heightAbove}): воронки нет, пыль волны — по давлению у
     * земли (на высоте полёта его не хватает сорвать грунт), топлива нет; вспышка, шар продуктов, облачко сажи, раскат
     * вдали. Облако модели — тому, кто рисует разрыв вблизи (null — мира нет).
     */
    @Nullable
    public static CloudModel airburst(Vec3 pos, BlastSource source, long seed) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        double hob = heightAbove(level, pos);
        return cloud(level, source, new CloudModel(source, air(level, pos), BlastTarget.of(GroundMaterial.STONE, source.chargeKg(), hob), hob,
                ground(level, pos)), pos, GroundMaterial.STONE, seed);
    }

    /**
     * Что лежит вокруг места {@code pos} по секторам ({@code GroundDust.SECTORS}) и радиусам ({@code GroundDust.BANDS}, до
     * 48 м): верхний блок карты высот {@code MOTION_BLOCKING} (трава без столкновения карту не держит, верх — дёрн) в
     * середине сектора на этом радиусе — его материал и высота его верха над {@code pos}, крыша это, улица или склон.
     * Рыхлое — земля без дёрна, песок, гравий, снег, пашня; дёрн и листва держат грунт под собой; остальное (камень,
     * кирпич, доски, металл) — твёрдое: на нём только осевшая пыль; вода — без пыли. Рельеф, куда слой стекает, — тот же
     * верх в любом месте ({@link FarTerrain}: чанки клиента, дальше плитки карты). Где у клиента нет чанка — как у
     * ядерки: голый грунт ({@code Surface.REFERENCE}) на высоте плитки карты, а нет и её — высота неизвестна (ровная
     * поверхность на высоте взрыва над тем, что под точкой, ниже; ⚠ удар в крышу дальше прорисовки без карты рельефа —
     * пыль на высоте крыши: клиент её не видит). Проницаемая листва не подменяет пол леса: в готовом чанке под кроной
     * ищется первая сплошная поверхность ({@code FarTerrain.dustY}); она даёт пыль по своему материалу. Брёвна, крыши,
     * скала и вода держат поток одинаково; неизвестный рельеф карты остаётся приближением.
     */
    static ua.zentix.incoming.client.fx.smoke.GroundDust.Ground ground(ClientLevel level, Vec3 pos) {
        int n = ua.zentix.incoming.client.fx.smoke.GroundDust.SECTORS;
        double[] bands = ua.zentix.incoming.client.fx.smoke.GroundDust.BANDS;
        double[][] loose = new double[n][bands.length], covered = new double[n][bands.length], hard = new double[n][bands.length],
                height = new double[n][bands.length];
        Sightline.Heights relief = FarTerrain.dust(level);
        for (int k = 0; k < n; k++) {
            double a = 2 * Math.PI * (k + 0.5) / n;
            for (int b = 0; b < bands.length; b++) {
                int x = (int) Math.floor(pos.x + bands[b] * Math.cos(a)), z = (int) Math.floor(pos.z + bands[b] * Math.sin(a));
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
                if (chunk == null) {
                    loose[k][b] = 1;
                    height[k][b] = relief.at(x, z) - pos.y;
                    continue;
                }
                int y = FarTerrain.dustY(chunk, x, z);
                height[k][b] = y + 1 - pos.y;
                net.minecraft.world.level.block.state.BlockState top = chunk.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                if (!top.getFluidState().isEmpty()) continue;
                if (loose(top)) loose[k][b] = 1;
                else if (sod(top)) covered[k][b] = 1;
                else hard[k][b] = 1;
            }
        }
        return new ua.zentix.incoming.client.fx.smoke.GroundDust.Ground(loose, covered, hard, height,
                (dx, dz) -> relief.at((int) Math.floor(pos.x + dx), (int) Math.floor(pos.z + dz)) - pos.y);
    }

    /** Грунт под дёрном сверху: пыль под ним есть, но держит её дёрн (срыв — от 3 м/с). */
    public static boolean sod(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)
                || s.is(net.minecraft.world.level.block.Blocks.MUD) || s.is(net.minecraft.world.level.block.Blocks.CLAY);
    }

    /** Рыхлый ли грунт сверху: пыль с него срывает слабый ветер. */
    public static boolean loose(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.DIRT) || s.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || s.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT) || s.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.tags.BlockTags.SAND)
                || s.is(net.minecraft.world.level.block.Blocks.GRAVEL) || s.is(net.minecraft.world.level.block.Blocks.SNOW)
                || s.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) || s.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                || s.is(net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND) || s.is(net.minecraft.world.level.block.Blocks.SUSPICIOUS_GRAVEL);
    }

    /**
     * Высота наземного взрыва над тем, что прямо под точкой, м: верх колонки ({@link FarTerrain}: чанк клиента — с кронами
     * и постройками, дальше плитки карты); удар в крышу, стену, склон — 0 (контакт: заряд дробит то, во что попал), над
     * аппаратом в небе — высота над рельефом. Точка удара о стену лежит на её грани — колонки по обе стороны грани (в
     * полублоке), берётся высшая. Не знаем ничего — 0: пакет называет взрыв наземным.
     */
    static double overSurface(ClientLevel level, Vec3 pos) {
        return overSurface(FarTerrain.dust(level), pos);
    }

    /** То же по высотам {@code heights} (первый воздух над верхом колонки). */
    static double overSurface(Sightline.Heights heights, Vec3 pos) {
        double ground = Double.NaN;
        for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            double h = heights.at((int) Math.floor(pos.x + 0.5 * d[0]), (int) Math.floor(pos.z + 0.5 * d[1]));
            if (!Double.isNaN(h) && !(h <= ground)) ground = h;
        }
        return Double.isNaN(ground) ? 0 : Math.max(0, pos.y - ground);
    }

    /**
     * Высота точки над рельефом под ней, м: чанк клиента (карта высот {@code MOTION_BLOCKING} — с кронами и постройками),
     * дальше — плитки рельефа карты ({@link FarTerrain}); не знаем ничего — над уровнем моря.
     */
    static double heightAbove(ClientLevel level, Vec3 pos) {
        double ground = FarTerrain.dust(level).at((int) Math.floor(pos.x), (int) Math.floor(pos.z));
        if (Double.isNaN(ground)) ground = ua.zentix.incoming.util.Air.SEA;
        return Math.max(0, pos.y - ground);
    }

    /**
     * Облако взрыва в месте {@code pos} ({@link CloudModel}) — для картинки вблизи (вспышка, частицы твёрдого); то же, что
     * у события.
     */
    public static CloudModel model(S2C.Blast p) {
        ClientLevel level = Minecraft.getInstance().level;
        BlastSource source = BlastSource.of(p);
        GroundMaterial mat = GroundMaterial.byId(p.material());
        if (level == null) return new CloudModel(source, CloudModel.Air.STANDARD, BlastTarget.of(mat, source.chargeKg(), 0), 0,
                ua.zentix.incoming.client.fx.smoke.GroundDust.Ground.BARE);
        double hob = overSurface(level, p.pos());
        return new CloudModel(source, air(level, p.pos()), target(p, mat, source.chargeKg(), hob), hob, ground(level, p.pos()));
    }

    /**
     * Воздух в месте взрыва: воздух мира ({@link ClientAir}) с ветром у земли места ({@link ClientAir#site}: улица, кроны,
     * холм); влажность — его, у воды 1 (облако конденсации), над водой — поле.
     */
    public static CloudModel.Air air(ClientLevel level, Vec3 pos) {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(pos);
        boolean water = !level.getFluidState(at).isEmpty() || !level.getFluidState(at.below()).isEmpty();
        ua.zentix.incoming.util.Air air = ClientAir.now();
        // поправку места меряют от поверхности под взрывом (под кроной — от земли под ней): от неё же высоты облака
        return CloudModel.Air.of(air, water ? 1 : air.humidity(),
                water ? ua.zentix.incoming.util.LocalWind.OPEN : ClientAir.site(pos.x, pos.y - overSurface(level, pos), pos.z));
    }

    /**
     * Взрыв на воде заряда {@code kg} кг ТНТ в месте {@code pos} ({@link WaterBlast}): глубина заряда под гладью и воды под
     * ним — по чанку клиента (вверх от точки, пока вода, — гладь; вниз от глади, пока вода, — дно; чанка нет — удар о гладь
     * глубокой воды). Один для облака события и для струй частицами вблизи.
     */
    public static WaterBlast water(ClientLevel level, Vec3 pos, double kg, CloudModel.Air air) {
        net.minecraft.core.BlockPos.MutableBlockPos m = net.minecraft.core.BlockPos.containing(pos).mutable();
        if (level.getChunkSource().getChunk(m.getX() >> 4, m.getZ() >> 4, false) == null) return new WaterBlast(kg, 0, Double.POSITIVE_INFINITY, air);
        // точка — в воде или над самой гладью (пакет ставит взрыв на гладь): верх воды — первый воздух над ней
        if (level.getFluidState(m).isEmpty() && level.getFluidState(m.move(0, -1, 0)).isEmpty()) return new WaterBlast(kg, 0, Double.POSITIVE_INFINITY, air);
        while (m.getY() < level.getMaxBuildHeight() && !level.getFluidState(m).isEmpty()) m.move(0, 1, 0);
        double surface = m.getY() - 1 + ua.zentix.incoming.client.fx.layer.WaterDecal.SURFACE;
        int bottom = m.getY() - 1, deepest = Math.max(level.getMinBuildHeight(), bottom - 64);
        m.setY(bottom);
        while (m.getY() > deepest && !level.getFluidState(m).isEmpty()) m.move(0, -1, 0);
        double floor = level.getFluidState(m).isEmpty() ? m.getY() + 1 : Double.NEGATIVE_INFINITY;
        return new WaterBlast(kg, surface - pos.y, surface - floor, air);
    }

    /** Профили пены и волны кольца взрыва на воде в кадр (каждый кадр — без новых объектов). */
    private static final float[] DARK = new float[ua.zentix.incoming.client.fx.layer.WaterDecal.SAMPLES],
            FOAM = new float[ua.zentix.incoming.client.fx.layer.WaterDecal.SAMPLES];

    /**
     * Наклейка взрыва на воде ({@link ua.zentix.incoming.client.fx.layer.WaterDecal}): фронт — гребень волны от полости
     * ({@link WaterBlast#waveRadius}), за ним — гребни через длину волны (рябь темнит воду; ⚠ подача: темнота — по
     * высоте волны, без своей оптики) и пена там, куда упала вода столба ({@link WaterBlast#foam}).
     */
    private static void decal(Event e, double t) {
        WaterBlast w = e.water;
        if (!ua.zentix.incoming.client.fx.layer.WaterDecal.wanted() || t >= w.waterLife()) return;
        double front = waterProfile(w, t, DARK, FOAM);
        float most = 0;
        for (int i = 0; i < DARK.length; i++) most = Math.max(most, Math.max(DARK[i], FOAM[i]));
        if (most > 0.004f) ua.zentix.incoming.client.fx.layer.WaterDecal.ring(e.x, e.z, front, front, DARK, FOAM, true);
    }

    /** Подача волны: фронт ровно из закона; гребень темнеет плавно за ним, без жёсткого обода. Пена — доля модели. */
    static double waterProfile(WaterBlast w, double t, float[] dark, float[] foam) {
        double front = w.waveRadius(t), lambda = w.waveLength();
        for (int i = 0; i < dark.length; i++) {
            double behind = ua.zentix.incoming.client.fx.layer.WaterDecal.sampleAt(i, front), r = Math.max(0, front - behind);
            // гребни: передний — у фронта, следующие — через длину волны внутрь, ниже на свою долю (пакет волн, ⚠ форма)
            double phase = behind / lambda, crest = 0.5 + 0.5 * Math.cos(2 * Math.PI * phase);
            double edge = Math.clamp(behind / (lambda / 16), 0, 1);
            edge = edge * edge * (3 - 2 * edge);
            double wave = w.waveAt(r, t) * crest * Math.exp(-phase / 1.5) * edge;
            // Подача, не изменение высоты волны: вода сохраняет отражения пака под мягким гребнем.
            dark[i] = (float) (0.35 * wave);
            foam[i] = (float) w.foam(r, t);
        }
        return front;
    }

    /** Раз в тик: забыть прошедшее и чужое измерение, звук — когда дошёл фронт, лучи по рельефу — по очереди. */
    static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            reset();
            return;
        }
        clock = BlastTime.ticks();
        if (!SOUNDS.isEmpty()) {
            Vec3 ear = camera();
            Sightline.Heights heights = FarTerrain.of(level);
            SOUNDS.removeIf(e -> {
                if (e.level != level) return true;
                listen(e, heights, ear, clock - e.born);
                return !e.soundPending;
            });
        }
        if (EVENTS.isEmpty()) return;
        Vec3 ear = camera();
        Sightline.Heights heights = FarTerrain.of(level);
        int traces = 0, clears = 0;
        Iterator<Event> it = EVENTS.iterator();
        while (it.hasNext()) {
            Event e = it.next();
            long age = clock - e.born;
            if (e.level != level || age > e.life() && (!e.soundPending || age > e.soundWait)) {
                e.alive = false;
                it.remove();
                continue;
            }
            if (e.soundPending) listen(e, heights, ear, age);
            if (clock - e.traced >= RETRACE && traces < TRACES_PER_TICK) {
                trace(e, heights, ear);
                if (e.glows) clear(e, ear, age);
                traces++;
            } else if (age < e.ballTicks() && clears < CLEARS_PER_TICK) {
                clear(e, ear, age);
                clears++;
            }
        }
    }

    /** Фронт дошёл до уха — раскат ({@link #hear}); не дошёл за весь путь звука на дальность слышимости — уже не дойдёт. */
    private static void listen(Event e, Sightline.Heights heights, Vec3 ear, long age) {
        double d = e.blastDistanceTo(ear);
        if (arrivalTicks(e.soundFront(), d) <= age) {
            e.soundPending = false;
            trace(e, heights, ear);
            hear(e, d, age);
        } else if (age > e.soundWait) {
            e.soundPending = false;
        }
    }

    private static void hear(Event e, double d, long age) {
        Outdoor.Heard heard = BlastSounds.far(e.voice, new Vec3(e.x, e.y + LIFT, e.z), Math.max(1, 0.4 * e.ballRadius()),
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
     * Шар — там, где он на {@code age}-м тике, как его клубы, спрайт и свет: место на середине жизни шара под ветром
     * уходило от нынешнего на метры (ракета, 5 м/с, первые тики — ~10 м), и шар у края стены считался закрытым.
     */
    private static void clear(Event e, Vec3 eye, double age) {
        double r = e.ballRadius();
        if (r <= 0) return;
        if (e.distanceTo(eye) > Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0) {
            e.open = 1;
            return;
        }
        double t = Math.min(age, e.ballTicks()) / 20, mid = e.y + e.model.ballHeight(t);
        double mx = e.x + e.model.ballX(t), mz = e.z + e.model.ballZ(t);
        int seen = 0, counted = 0;
        for (int i = 0; i <= RING_RAYS; i++) {
            disc(eye, mx, mid, mz, RING * r, i, DISC);
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
        double now = BlastTime.ticks() + view.partial();
        Arrays.fill(BRIGHTEST, 0);
        for (Event e : EVENTS) {
            if (e.level != level) continue;
            double age = Math.max(0, now - e.born);
            if (e.water != null) decal(e, age / 20);
            // основание относительно камеры
            double bx = e.x - cam.x, by = e.y - cam.y, bz = e.z - cam.z;
            double d = e.distanceTo(cam);
            e.glows = age < e.ballTicks();
            // линия, ниже которой место взрыва закрыто рельефом, — над основанием (тела в прорисовке закрывает и глубина)
            double line = e.hidden > 0 ? LIFT + e.hidden : 0;
            sky(e, view, out, age, bx, by, bz, d, line);
            light(e, view, out, lights, age, bx, by, bz, d, line);
        }
    }

    /** Веса и столбец шаров {@link #fireballs} (каждый кадр — без новых объектов). */
    private static final double[] WEIGHT = new double[4];
    private static final org.joml.Vector4f COLUMN = new org.joml.Vector4f();

    /**
     * Огненные шары, что светят на дым сетки и на мир ({@code fx_pall}: {@code SmokeBalls}, вывод на кадр:
     * {@code PallBalls}): до четырёх самых заметных шаров облаков модели (свет у глаза — яркость × R²/d²) в свободные
     * столбцы {@code centres} (занятые — шары ядерки — не трогаются) — центр от глаза и радиус; в {@code light} — свет
     * огня у поверхности ({@link CloudModel#emittedLight}: источник в своём энергетическом бюджете)
     * (линейный, белая стена в полдень — 1) × {@code exposure}, в w — {@code air} (ослабление
     * воздуха, 1/блок). true — хоть один шар добавлен.
     */
    public static boolean fireballs(Vec3 eye, float partial, double exposure, double air, Matrix4f centres, Matrix4f light) {
        double now = BlastTime.ticks() + partial;
        double[] weight = WEIGHT;
        boolean any = false;
        org.joml.Vector4f column = COLUMN;
        for (int i = 0; i < 4; i++) {
            centres.getColumn(i, column);
            weight[i] = column.w > 0 ? Double.MAX_VALUE : 0;
        }
        for (Event e : EVENTS) {
            double age = Math.max(0, now - e.born), t = age / 20;
            double r = e.model.ballRadius(t);
            if (r <= 0) continue;
            double dx = e.x + e.model.ballX(t) - eye.x, dy = e.y + e.model.ballHeight(t) - eye.y, dz = e.z + e.model.ballZ(t) - eye.z;
            e.model.emittedLight(t, BB);
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
     * Свет и горячий газ облаков модели на мир кадра ({@code HeatPass}): огненный шар — ламбертово серое тело в бюджете
     * модели ({@link CloudModel#emittedLight}, как у {@link #fireballs} и света на клубах); газ — термик облака
     * (середина, радиус, температура и подъём модели); фронт
     * ударной волны ({@link #front(ChemicalFront, double, double, double, double[], double[])}). Пламя и
     * столб над топливом в воронке — у пожаров ({@code FireSmoke.heat}).
     */
    public static void heat(Vec3 eye, float partial, ua.zentix.incoming.client.fx.layer.HeatPass.Frame f, double air) {
        ClientLevel level = Minecraft.getInstance().level;
        for (Event e : EVENTS) {
            if (e.level != level || !e.alive) continue;
            double t = BlastTime.seconds(BlastTime.ticks(), e.born, partial);
            double bx = e.x - eye.x, by = e.y - eye.y, bz = e.z - eye.z;
            double r = e.model.ballRadius(t);
            if (r > 0) {
                e.model.emittedLight(t, BB);
                double h = e.model.ballHeight(t);
                if (BB[3] > 0) f.light(bx + e.model.ballX(t), by + h, bz + e.model.ballZ(t), r, BB[0], BB[1], BB[2], air, h);
            }
            // горячий газ термика — там же, где его дым (снос ветром)
            double cr = e.model.radius(t), ch = e.model.height(t);
            if (t < e.model.life) {
                f.hot(bx + e.model.driftX(t), by + Math.max(0, ch - cr), bz + e.model.driftZ(t), 2 * cr, cr, e.model.kelvin(t), e.model.rise(t));
            }
            if (e.source.underground() || e.source.chargeKg() <= 0 || t > FRONT_SECONDS) continue;
            if (front(e.front(), t, r, e.model.hob, FRONT)) f.chemicalFront(bx, by, bz, FRONT[0], FRONT[1], FRONT[2], FRONT_DN);
        }
    }

    /** Фронт дальше этого возраста не ищется, с: в 2 км он уже звук (скачок у 450 кг там — сотые доли процента плотности). */
    static final double FRONT_SECONDS = 6;
    private static final double[] FRONT = new double[3], FRONT_DN = new double[4];

    /**
     * Видимый фронт ударной волны обычного взрыва — тот же закон и тот же проход, что у ядерки ({@code NukeHeat},
     * {@code HeatPass.Frame#front}, {@link ShockOptics}): сфера фронта радиуса {@link ChemicalFront#radiusAt} у места
     * взрыва (у воздушного разрыва — в воздухе, ниже земли её нет), скачок показателя преломления — по сжатию за фронтом
     * (Ренкин–Гюгонио) при давлении Кинни–Грэхема на ней, толща сжатия — {@link ShockOptics#DECAY_SHARE} длины
     * положительной фазы за фронтом. Пока фронт внутри огненного шара ({@code ball}), его не видно — видно шар. Порога
     * давления, кроме видимости, нет: скачок слабеет с давлением сам, и проход отбрасывает фронт, что ни одному
     * пикселю не даст сдвига в {@code HeatPass.SHIFT_MIN} (у шахеда в 1080p — примерно до 150–250 м от зрителя у
     * фронта; в бинокль — дальше). У обычного взрыва давление на сфере одно на всех высотах: на земле — отражённая
     * полусфера (W × 1,8), в воздухе — свободная сфера (⚠ отражённой от земли волны и стебля Маха у воздушного разрыва
     * модель не считает). В {@code out} — радиус, толща (м) и синус угла над серединой у земли; в {@code dn} — скачок
     * на четырёх высотах. false — фронта не видно.
     */
    static boolean front(ChemicalFront front, double t, double ball, double hob, double[] out, double[] dn) {
        double r = front.radiusAt(t);
        if (!(r > ball) || r <= 0) return false;
        double l = ShockOptics.DECAY_SHARE * front.phaseLength(r);
        double jump = ShockOptics.indexJump(front.kpa(r), ua.zentix.incoming.client.fx.layer.HeatPass.N0);
        if (!(l > 0) || !(jump > 0)) return false;
        out[0] = r;
        out[1] = l;
        out[2] = Math.max(-1, Math.min(1, -Math.max(0, hob) / r));
        Arrays.fill(dn, jump);
        return true;
    }

    private static boolean front(ChemicalFront front, double t, double ball, double hob, double[] out) {
        return front(front, t, ball, hob, out, FRONT_DN);
    }

    /**
     * Огненный шар: тело — кадр анимации шара (от вспышки до сажи) поверх того, что за ним, мельче нескольких пикселей —
     * круг своего света ({@link FarSprites#shaped}); свет вокруг — бликом и вуалью на любой дальности; вблизи и вдали — одни
     * кадры.
     */
    private static void light(Event e, FarView view, FarSprites out, FxLights lights, double age, double bx, double by, double bz, double d,
                              double line) {
        double t = view.air(d, by);
        double ticks = e.ballTicks();
        if (ticks <= 0 || age >= ticks || t < Sight.THRESHOLD) return;
        double r, half, body;
        FxAtlas.Sprite sprite;
        double fire;
        // кадр — по состоянию шара: в горении — доля времени горения, остывая — температура (кадры запечённой симуляции
        // с её временем и температурой, FxAtlas.fireballFrame); тело тает в дым той же долей, что дым проступает: по
        // свечению против света неба на его дыме (CloudModel.glowShare) — днём раньше, чем ночью
        double s = age / 20, frame = FxAtlas.fireballFrame(s / Math.max(e.model.burnSeconds, 1e-6), e.model.ballKelvin(s),
                e.model.ballKelvin(e.model.burnSeconds));
        r = e.model.ballRadius(s);
        half = r * FxAtlas.fireballHalf(frame);
        body = CloudModel.glowShare(e.model.ballKelvin(s), view.skyLuminance());
        sprite = FxAtlas.fireballAt(frame, (e.seed & 1) != 0);
        fire = FxAtlas.fireLightAt(frame) * body;
        if (r <= 0) return;
        // шар сносит ветром вместе с его дымом (CloudModel.ballX): картинка, свет и зарево — с того же места
        bx += e.model.ballX(s);
        bz += e.model.ballZ(s);
        double h = e.ballHeight(age), b = e.brightness(age);
        double vis = visible(h, r, line), hidden = 1 - vis * e.open;
        float[] c = e.colour(age);
        // Поток источника на дым — тот же, что на мир: дневной контраст тела с дымом не выключает его излучение.
        // Предел яркости огня на клубе — подача из кадра огня, без дневного/ночного множителя body.
        lights.add(bx, by + h, bz, r, e.emitted(age) * view.exposure(),
                FxAtlas.fireLightAt(frame) * CloudModel.glowShare(e.model.ballKelvin(s)), c[0], c[1], c[2]);
        if (body <= 0) return;
        if (vis > 0) {
            // свечение огня (FxBloom): шар модели ярче своего кадра на экране во столько раз (кадр показан своей экспозицией)
            double hdr = b * view.exposure() / Math.pow(Math.max(FxAtlas.fireLightAt(frame), 1e-3), 2.2);
            out.shaped(bx, by + h, bz, r, sprite, half, FxAtlas.fireballMix(frame), b * view.exposure() * t, t, view.pixel(), c[0], c[1], c[2],
                    vis * body, vis * e.open, fire, hdr, LIGHT);
            brightest(view, bx, by + h, bz, vis);
        }
        // шар закрыт гребнем или постройкой: его свет рассеивает воздух над краем — зарево там, откуда место было бы
        // видно, пока шар светит; на любой дальности, как блик (вблизи за домом одной вспышки на экране не хватает)
        if (hidden > 0) halo(view, out, bx, by + Math.max(line, h), bz, HALO_SIZE * r, b * body * hidden, fire, t, c);
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
        out.glow(gx, gy, gz, floor, rad, c[0], c[1], c[2], (float) (Math.min(behind(light, view.exposure(), t), fire) * k * k));
    }

    /**
     * Яркость зарева закрытого шара яркостью light против белого (с воздухом t и привыканием глаза кадра exposure,
     * {@link Sight#exposure}):
     * доля {@link #BEHIND} его света, но не ярче вуали видимого шара ({@link Sight#VEIL_PEAK}) — это тот же рассеянный
     * свет. Ночью свет ×1200 ({@link Sight#adapted}), и зарево в полную силу заливало бы кремовым пол-кадра шире вуали
     * самого шара: закрытый шар светил бы ярче открытого.
     */
    static double behind(double light, double exposure, double t) {
        return Math.min(Sight.VEIL_PEAK, light * BEHIND * exposure * t);
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
        out.glow(bx, gy, bz, rs, rs, c[0], c[1], c[2], (float) (peak * cloudGlow(e.emitted(age), r, h, view.exposure())));
    }

    /**
     * Яркость низа облаков на высоте h над источником яркости b радиуса r против белого экрана, с привыканием глаза
     * кадра exposure ({@link Sight#exposure}), не ярче белого: шар светит силой b·r² (на единицу π), освещённость облаков — сила / h²,
     * их яркость — {@link #CLOUD_ALBEDO}·E/π. Днём это тысячные доли неба, ночью — в сотни раз ярче него.
     */
    static double cloudGlow(double b, double r, double h, double exposure) {
        double k = r / h;
        return Math.min(1, CLOUD_ALBEDO * b * k * k * exposure);
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
        SOUNDS.clear();
        lastHeard = null;
    }

    /** Для строки {@code SCENARIO}: последний взрыв — место, материал под ним и столб воды (высота, м; нет — 0). */
    public static String describeLast() {
        Event e = EVENTS.peekLast();
        if (e == null) return "none";
        BlastTarget t = e.model.target;
        return String.format(Locale.ROOT, "at %.1f %.1f %.1f, %s, %s%s, charge %.1f kg, water column %.1f m", e.x, e.y, e.z, e.ground,
                t.family().name().toLowerCase(Locale.ROOT), t.slab() > 0 ? String.format(Locale.ROOT, " %.1f m, hole %.1f m", t.slab(), t.hole()) : "",
                e.source.chargeKg(), e.water == null ? 0 : e.water.height);
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

}
