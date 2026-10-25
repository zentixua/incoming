package ua.zentix.incoming.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.zentix.incoming.nuclear.radiation.Shielding;


/**
 * Куски блоков, которые сорвала и несёт ударная волна: кувыркающиеся тела с текстурой своего блока (как частица
 * разрушения блока, только объёмные) летят, падают, бьются о землю и ложатся. Частицы ванильного движка на листе блоков:
 * свет мира, туман и шейдерпак — как у всего в мире, своё — только тело. Сколько их живёт сразу — не больше {@link #CAP}
 * (меньше по настройке «Частицы»): когда мест нет, новый кусок ближе к камере вытесняет самый дальний, иначе не рождается.
 * <p>
 * Кусок — тело своего вещества ({@link Kind}): обломок камня или дерева — куб, доска, осколок стекла, лист. Волна его не
 * бросает, а несёт ветер за фронтом ({@link Wind}): кусок срывается с места в покое, воздух разгоняет его квадратичным
 * сопротивлением dv/dt = g + (g/v_t²)·|u − v|·(u − v), где v_t — скорость падения тела в тихом воздухе
 * ({@link #terminal}) из его массы и площади: клок листвы (v_t ~1,7 м/с) уходит с ветром сразу и долго планирует, стекло и доска
 * (14–19 м/с) разгоняются за десятые секунды, камень (~100 м/с) почти не сдвигается и падает рядом. Тот же размер, что
 * видно, задаёт и сопротивление.
 */
public final class Fragments {
    /** Сколько кусков живёт сразу при «Частицы: все». */
    static final int CAP = 1200;
    /** Жизнь куска: обломок, доска, стекло — от {@link #LIFE_SHORT} − 40 тиков; лист планирует до {@link #LIFE_MAX}. */
    static final int LIFE_SHORT = 110, LIFE_MAX = 300;
    /** Самое долгое ожидание вылета, тиков. */
    public static final int DELAY_MAX = 40;
    /** Последние столько тиков жизни кусок уменьшается до нуля (ложится и крошится). */
    private static final int SHRINK = 15;
    /** Кусок, что пролежал на земле столько тиков, уходит. */
    private static final int REST = 20;
    /** Ускорение свободного падения, блоков за тик² (9,81 м/с²; блок — метр, тик — 1/20 с). */
    public static final double G = 9.81 / 400;
    /** Плотность воздуха у земли, кг/м³. */
    static final double AIR = 1.2;
    /**
     * Коэффициент сопротивления: квадратная пластина поперёк потока — 1,17, куб гранью — 1,05 (Hoerner, «Fluid-Dynamic
     * Drag», 1965, гл. 3). Кувыркаясь, пластина ловит поток то гранью, то ребром — у неё это верхняя оценка сопротивления.
     */
    static final double CD_PLATE = 1.17, CD_CUBE = 1.05;
    /**
     * Масса листвы на площадь, кг/м²: сухая — 0,03–0,15 у листвы деревьев (Poorter и др., New Phytologist 182, 2009), живой
     * лист — вдвое-втрое тяжелее с водой (⚠ оценка 0,2). Скорость падения выходит ~1,7 м/с — как у падающих листьев.
     */
    static final double LEAF_AREAL = 0.2;
    /**
     * Кусок кроны — клок листвы с веткой в 0,2–0,35 м, а не отдельный лист: волна рвёт и листья, и ветки, а рисуется
     * выборка — несколько кусков на блок кроны, где настоящих листьев сотни; лист в 10 см с 60 блоков мельче пикселя, и
     * выборка из листьев пропадала бы целиком. Масса на площадь у клока — та же, что у листьев (ветка тонкая, ⚠ оценка).
     */
    static final float SPRIG_MIN = 0.2f, SPRIG_MAX = 0.35f;
    /** Толщина доски, м (2,5 см — обычная доска обшивки и пола). */
    static final double BOARD_THICK = 0.025;
    /**
     * Ветер вверх — доля ветра вдоль земли у куска (Kuhl и др. 1992, §3.4: вертикальная скорость в пыльном слое за фронтом —
     * 0,02 ветра у дна и 0,04–0,12 у верха); у каждого куска своя.
     */
    public static final double LIFT_MIN = 0.02, LIFT_MAX = 0.12;
    /**
     * Ветер вверх у куска с постройки или кроны — до этой доли ветра: у кромки тупого тела поток срывается, и оторвавшийся
     * слой уходит вверх под 15–30° (обтекание куба на земле: Castro и Robins, J. Fluid Mech. 79, 1977; ⚠ угол по памяти,
     * взят 25°, tg 0,47); так кромка дома и крона подбрасывают сорванное. Доля держится весь полёт, хотя область срыва —
     * одна-две высоты постройки за ней, а путь воздуха за фазу — того же порядка (⚠ приближение).
     */
    public static final double LIFT_EDGE = Math.tan(Math.toRadians(25));
    /**
     * Живые куски — свой список, а не по смерти куска: движок выбрасывает частицы и без неё (переполнение очереди, смена
     * мира), поэтому кусок, который не тикал дольше {@link #LOST} тиков, считается ушедшим.
     */
    private static final FarthestPool<Fragment> LIVE = new FarthestPool<>(Particle::isAlive, Particle::remove);
    private static final int LOST = 2;
    private static long tick;
    private static final RandomSource RANDOM = RandomSource.create();

    private Fragments() {}

    /** Вещество куска — от него тело и сопротивление. */
    public enum Kind {
        /** Обломок камня, кирпича, земли, металла, бревна — куб. */
        CHUNK(1.5),
        /** Доска (доски, двери, заборы, ступени из дерева) — длинная тонкая пластина. */
        BOARD(2),
        /** Осколок стекла окна — тонкая пластина. */
        PANE(3),
        /** Клок листвы кроны ({@link #SPRIG_MIN}–{@link #SPRIG_MAX} м) — тонкая пластина. */
        LEAF(6);

        /**
         * Сколько кусков рисуется на снесённый блок — выборка для картинки, а не число осколков (у кроны в кубометре — сотни
         * листьев): клоки листвы и стекло мельче обломков, их нужно больше, чтобы было видно.
         */
        public final double perBlock;

        Kind(double perBlock) {
            this.perBlock = perBlock;
        }

        /** Вещество блока {@code s} (на месте {@code p}). */
        public static Kind of(LevelReader level, BlockPos p, BlockState s) {
            if (s.is(BlockTags.LEAVES)) return LEAF;
            if (Shielding.window(level, p, s)) return PANE;
            if (s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_FENCES)
                    || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS)) return BOARD;
            return CHUNK;
        }
    }

    /** Ветер, что несёт кусок. */
    public interface Wind {
        /**
         * Ветер сейчас в точке (x, y, z): {@code into[0]}, {@code into[2]} — вдоль земли, блоков за тик; {@code into[1]} —
         * скорость вдоль своего направления от источника волны (в фазе разрежения — меньше нуля).
         */
        void at(double x, double y, double z, double[] into);
    }

    /**
     * Скорость падения тела в тихом воздухе, м/с: v_t = √(2mg / (ρ·C·A)). Пластина (лист, стекло, доска) — масса на
     * площадь грани σ: √(2σg / (ρ·C_пл)); куб ребром L из вещества плотности ρ_в: √(2ρ_в·L·g / (ρ·C_куб)).
     *
     * @param density плотность вещества, кг/м³ ({@link Shielding#material}; у стекла — масса окна на квадратный метр)
     * @param size    ребро куба или длина пластины, м
     */
    public static double terminal(Kind kind, double density, double size) {
        double g = 9.81;
        return switch (kind) {
            case CHUNK -> Math.sqrt(2 * density * size * g / (AIR * CD_CUBE));
            case BOARD -> Math.sqrt(2 * density * BOARD_THICK * g / (AIR * CD_PLATE));
            case PANE -> Math.sqrt(2 * density * g / (AIR * CD_PLATE));
            case LEAF -> Math.sqrt(2 * LEAF_AREAL * g / (AIR * CD_PLATE));
        };
    }

    /** Сопротивление g/v_t², 1/блок, у тела со скоростью падения {@code terminalMps}, м/с, в тихом воздухе. */
    public static double drag(double terminalMps) {
        double vt = terminalMps / 20;
        return G / (vt * vt);
    }

    /**
     * Тик полёта тела со скоростью {@code v} (блоков за тик, меняется на месте) в ветре (ux, uy, uz) с сопротивлением
     * {@code drag} = g/v_t² (1/блок): квадратичное сопротивление за тик — точно (|v − u| спадает как 1/(1 + k·|v − u|·t):
     * у листа k·|v − u| в сотни раз больше единицы, и шаг Эйлера разошёлся бы), тяжесть — вокруг него. Возвращает |v − u|.
     */
    public static double step(double[] v, double ux, double uy, double uz, double drag) {
        // тяжесть — половиной до сопротивления и половиной после (у листа время установления — 3 тика, и тяжесть одним
        // шагом после сопротивления сдвинула бы скорость падения на 15 %)
        double rx = v[0] - ux, ry = v[1] - 0.5 * G - uy, rz = v[2] - uz, rel = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double keep = 1 / (1 + drag * rel);
        v[0] = ux + rx * keep;
        v[1] = uy + ry * keep - 0.5 * G;
        v[2] = uz + rz * keep;
        return rel;
    }

    /**
     * Кусок блока {@code state} (с места {@code from}: его цвет травы и листвы) вещества {@code kind} с центром (x, y, z) и
     * скоростью (vx, vy, vz), блоков за тик (сорванный на месте — покой): вылетает через {@code delay} тиков (до тех пор не
     * виден и стоит) и летит с ветром {@code wind}. {@code size} — ребро обломка или длина доски и осколка, блоки (клок листвы —
     * своего размера). {@code edge} — кусок с постройки или кроны: поток на её кромке подбрасывает его ({@link #LIFT_EDGE}).
     *
     * @return false — не родился (воздух, блок без модели, мест нет)
     */
    public static boolean torn(ClientLevel level, BlockState state, BlockPos from, Kind kind, double x, double y, double z, double vx, double vy, double vz,
                               float size, Wind wind, int delay, boolean edge) {
        return torn(level, state, from, kind, x, y, z, vx, vy, vz, size, Double.NaN, wind, delay, edge);
    }

    /**
     * То же, но тело летит со своей скоростью падения в тихом воздухе {@code terminalMps}, м/с, а рисуется размером
     * {@code size}: так рисуют крупнее то, что настоящего размера на экране не видно (сор у камеры — комочек почвы в 2 мм
     * гранью в 6–12 см, подача), а полёт, оседание и снос остаются у настоящего тела. NaN — скорость падения по размеру и
     * веществу ({@link #terminal}).
     */
    public static boolean torn(ClientLevel level, BlockState state, BlockPos from, Kind kind, double x, double y, double z, double vx, double vy, double vz,
                               float size, double terminalMps, Wind wind, int delay, boolean edge) {
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL || !state.shouldSpawnTerrainParticles()) return false;
        // мест нет — самый дальний от камеры уходит, если он дальше нового ({@link FarthestPool}): волна идёт к камере, и
        // ближние места снесённого приходят последними — иначе все места занимали бы куски дальних чанков, а у камеры,
        // где их и видно, не было бы ни одного
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = eye.distanceToSqr(x, y, z);
        if (!LIVE.admit(d, cap(), tick, f -> eye.distanceToSqr(f.x(), f.y(), f.z()))) return false;
        int wait = Mth.clamp(delay, 0, DELAY_MAX);
        int life = kind == Kind.LEAF ? LIFE_MAX : LIFE_SHORT - 40 + RANDOM.nextInt(41);
        Fragment f = new Fragment(level, state, from, kind, x, y, z, vx, vy, vz, size, terminalMps, wind, wait, life, edge);
        LIVE.add(f, d, tick);
        Minecraft.getInstance().particleEngine.add(f);
        return true;
    }

    private static int cap() {
        ParticleStatus s = Minecraft.getInstance().options.particles().get();
        return s == ParticleStatus.ALL ? CAP : s == ParticleStatus.DECREASED ? CAP / 2 : CAP / 4;
    }

    /**
     * Сколько кусков дать новому источнику, что хочет {@code wanted}: не больше свободных мест, но не меньше четверти
     * предела — их место освободят самые дальние ({@link #torn}). Залп из восьми взрывов в одном тике иначе рождал
     * 8 × 900 клочков ради 1200 мест, и почти все тут же вытеснялись (десятки мс в тике рождения).
     */
    public static int share(int wanted) {
        return Math.min(wanted, Math.max(cap() / 4, cap() - alive()));
    }

    /** Сколько кусков живёт. */
    public static int alive() {
        return LIVE.size();
    }

    /** Тик клиента (не на паузе): ушедшие куски — из счёта. */
    public static void tick() {
        tick++;
        LIVE.purge(f -> f.isAlive() && tick - f.ticked <= LOST);
    }

    /** Выход из мира: движок сам убирает частицы. */
    public static void reset() {
        LIVE.clear();
    }

    /** Кусок: прямоугольное тело с полуразмерами по своим осям, повёрнутое вокруг своей оси, с текстурой блока на гранях. */
    private static final class Fragment extends Particle {
        /** Оси тела по граням: нормаль, первая и вторая оси грани (номер оси тела), знак нормали; a × b = нормаль. */
        private static final int[][] FACES = {{0, 1, 2, 1}, {0, 2, 1, -1}, {1, 2, 0, 1}, {1, 0, 2, -1}, {2, 0, 1, 1}, {2, 1, 0, -1}};
        private static final Quaternionf TURN = new Quaternionf();
        private static final Vector3f[] AXES = {new Vector3f(), new Vector3f(), new Vector3f()};
        private static final double[] AIR_NOW = new double[3], VEL = new double[3];

        /** Полуразмеры по осям тела, блоки. */
        private final float[] half = new float[3];
        private final float lift, u0, v0, u1, v1;
        private final float ax, ay, az;
        /** Сопротивление g/v_t², 1/блок. */
        private final double drag;
        private final Wind wind;
        private float angle, angleO, spin;
        private int delay, rest;
        /** Первый тик полёта прошёл (рамка вынута из блоков). */
        private boolean out;
        /** Упёрся в блок не снизу (потолок, крона над ним): ваниль его больше не двигает, а onGround не ставит. */
        private boolean stuck;
        /** Тик {@link Fragments#tick}, в который кусок тикал в последний раз (движок выбросил его — перестаёт). */
        private long ticked = Fragments.tick;

        Fragment(ClientLevel level, BlockState state, BlockPos from, Kind kind, double x, double y, double z, double vx, double vy, double vz,
                 float size, double terminalMps, Wind wind, int delay, int life, boolean edge) {
            super(level, x, y, z);
            this.delay = delay;
            this.wind = wind;
            float len = switch (kind) {
                case CHUNK -> size;
                case BOARD -> Math.min(1, 1.6f * size);
                case PANE -> 0.6f * size;
                // клок своего размера (0 — клок кроны); скорость падения у пластины от размера не зависит
                case LEAF -> size > 0 ? size : SPRIG_MIN + (SPRIG_MAX - SPRIG_MIN) * random.nextFloat();
            };
            half[0] = 0.5f * len;
            half[1] = 0.5f * switch (kind) {
                case CHUNK -> size;
                case BOARD -> 0.15f;
                case PANE -> (0.5f + 0.5f * random.nextFloat()) * len;
                case LEAF -> 0.8f * len;
            };
            half[2] = 0.5f * switch (kind) {
                case CHUNK -> size;
                case BOARD -> (float) BOARD_THICK;
                case PANE -> 0.01f;
                case LEAF -> 0.01f;
            };
            // место частицы — низ её рамки; рамка — по наибольшему полуразмеру (тело кувыркается)
            float box = 2 * Math.max(half[0], Math.max(half[1], half[2]));
            setPos(x, y - 0.5 * box, z);
            xo = this.x;
            yo = this.y;
            zo = this.z;
            setSize(box, box);
            xd = vx;
            yd = vy;
            zd = vz;
            gravity = 0;
            friction = 1;
            lifetime = life;
            drag = drag(terminalMps > 0 ? terminalMps : terminal(kind, Shielding.material(level, from, state), len));
            lift = (float) (LIFT_MIN + ((edge ? LIFT_EDGE : LIFT_MAX) - LIFT_MIN) * random.nextDouble());
            TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getTexture(state, level, from);
            // на грани — кусок текстуры размером с тело (16 пикселей на блок); у стекла — от края: окно прозрачно, кроме рамки
            // и бликов по краю, а прозрачные пиксели частица не рисует
            float span = Mth.clamp(2 * Math.max(half[0], half[1]), 0.125f, 1);
            float ou = kind == Kind.PANE ? 0 : random.nextFloat() * (1 - span), ov = kind == Kind.PANE ? 0 : random.nextFloat() * (1 - span);
            u0 = sprite.getU(ou);
            u1 = sprite.getU(ou + span);
            v0 = sprite.getV(ov);
            v1 = sprite.getV(ov + span);
            if (IClientBlockExtensions.of(state).areBreakingParticlesTinted(state, level, from)) {
                int c = Minecraft.getInstance().getBlockColors().getColor(state, level, from, 0);
                rCol = (c >> 16 & 0xFF) / 255f;
                gCol = (c >> 8 & 0xFF) / 255f;
                bCol = (c & 0xFF) / 255f;
            }
            Vector3f axis = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f);
            if (axis.lengthSquared() < 1e-4f) axis.set(0, 1, 0);
            axis.normalize();
            ax = axis.x;
            ay = axis.y;
            az = axis.z;
            angle = angleO = random.nextFloat() * Mth.TWO_PI;
            spin = 0.1f * (random.nextBoolean() ? 1 : -1);
        }

        double x() {
            return x;
        }

        double y() {
            return y;
        }

        double z() {
            return z;
        }

        @Override
        public void tick() {
            ticked = Fragments.tick;
            if (delay > 0) {
                delay--;
                return;
            }
            if (!out) {
                // рамка — по наибольшему размеру тела: у длинной доски из пролома у пола она заходит в пол, а ваниль не
                // выталкивает частицу из блока, в котором та уже стоит, — доска ушла бы в пол; выше — на свободное место (руины
                // встают к вылету, поэтому проверка — в первый тик полёта, а не при рождении)
                // только пол под серединой: рамка у пролома в стене задевает и соседние блоки по бокам, и их проверка
                // подняла бы кусок на блок, в стену над проломом
                out = true;
                for (int i = 0; i < 4 && !level.noCollision(new net.minecraft.world.phys.AABB(x - 0.05, getBoundingBox().minY, z - 0.05, x + 0.05,
                        getBoundingBox().minY + 0.5 * bbHeight, z + 0.05)); i++) {
                    setPos(x, y + 0.25, z);
                    yo = y;
                }
            }
            if (onGround || stuck) {
                // лёг или упёрся: ваниль после касания земли (или потолка, кроны снизу — тогда onGround не ставится) частицу
                // больше не двигает (stoppedByCollision) — кувырок гаснет, полежит и раскрошится
                xd = yd = zd = 0;
                super.tick();
                spin *= 0.5f;
                angleO = angle;
                angle += spin;
                if (++rest >= REST) lifetime = Math.min(lifetime, age + SHRINK);
                return;
            }
            // ветер: вдоль земли — у места, откуда сорвало, вверх — своя доля его (вихри пыльного слоя)
            wind.at(x, y + bbHeight * 0.5, z, AIR_NOW);
            VEL[0] = xd;
            VEL[1] = yd;
            VEL[2] = zd;
            double rel = step(VEL, AIR_NOW[0], lift * Math.max(0, AIR_NOW[1]), AIR_NOW[2], drag);
            xd = VEL[0];
            yd = VEL[1];
            zd = VEL[2];
            double x0 = x, y0 = y, z0 = z;
            super.tick();
            // скорость есть, а место то же: упёрся в блок (ваниль больше не двигает)
            stuck = x == x0 && y == y0 && z == z0 && xd * xd + yd * yd + zd * zd > 1e-8;
            angleO = angle;
            // кувыркается тем быстрее, чем быстрее его обтекает воздух
            spin = Math.copySign((float) Math.min(0.8, 0.1 + 0.25 * rel), spin);
            angle += spin;
        }

        @Override
        public void render(VertexConsumer buffer, Camera camera, float partial) {
            if (delay > 0) return;
            float shrink = Mth.clamp((lifetime - age - partial) / SHRINK, 0, 1);
            if (shrink <= 0) return;
            Vec3 cam = camera.getPosition();
            float box = bbHeight * 0.5f;
            float px = (float) (Mth.lerp(partial, xo, x) - cam.x), py = (float) (Mth.lerp(partial, yo, y) - cam.y + box),
                    pz = (float) (Mth.lerp(partial, zo, z) - cam.z);
            TURN.identity().rotateAxis(Mth.lerp(partial, angleO, angle), ax, ay, az);
            for (int i = 0; i < 3; i++) TURN.transform(AXES[i].set(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0));
            int light = getLightColor(partial);
            for (int[] f : FACES) {
                Vector3f n = AXES[f[0]], a = AXES[f[1]], b = AXES[f[2]];
                float hn = half[f[0]] * shrink, ha = half[f[1]] * shrink, hb = half[f[2]] * shrink;
                float s = f[3];
                float nx = n.x * s, ny = n.y * s, nz = n.z * s;
                // тень граней, как у блоков: верх светлее, низ темнее, бока между
                float shade = nx * nx * 0.6f + nz * nz * 0.8f + ny * ny * (ny > 0 ? 1 : 0.5f);
                float r = rCol * shade, g = gCol * shade, bl = bCol * shade;
                float cx = px + nx * hn, cy = py + ny * hn, cz = pz + nz * hn;
                float ax = a.x * ha, ay = a.y * ha, az = a.z * ha, bx = b.x * hb, by = b.y * hb, bz = b.z * hb;
                vertex(buffer, cx - ax - bx, cy - ay - by, cz - az - bz, u0, v1, r, g, bl, light);
                vertex(buffer, cx + ax - bx, cy + ay - by, cz + az - bz, u1, v1, r, g, bl, light);
                vertex(buffer, cx + ax + bx, cy + ay + by, cz + az + bz, u1, v0, r, g, bl, light);
                vertex(buffer, cx - ax + bx, cy - ay + by, cz - az + bz, u0, v0, r, g, bl, light);
            }
        }

        private static void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, float r, float g, float b, int light) {
            buffer.addVertex(x, y, z).setUv(u, v).setColor(r, g, b, 1).setLight(light);
        }

        @Override
        protected int getLightColor(float partial) {
            BlockPos pos = BlockPos.containing(x, y + bbHeight * 0.5, z);
            return level.isLoaded(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.TERRAIN_SHEET;
        }
    }
}
