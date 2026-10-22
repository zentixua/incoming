package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;

import java.util.Arrays;

/**
 * Свет вспышки и огня обычного оружия на мир и дрожание воздуха над огнём и шаром — проход во весь кадр после пелены и
 * до квадратов слоя ({@code fx_heat}: законы там), без нового мода: копия кадра, расстояние до мира ({@link SceneDepth}).
 * Свет ядерного шара на мир — свой, в проходе пелены ({@code fx_pall_composite}); этот — у шаров и огня обычного оружия.
 * Огонь блоков Minecraft светит на мир сам (свет блоков), поэтому его свет здесь не считается, а его горячий газ дрожит.
 * <p>
 * <b>Дрожание.</b> Показатель преломления воздуха n − 1 = {@value #N0} при 288 K (Edlén 1966, 550 нм) и ∝ плотности,
 * т. е. 1/T; пульсации температуры в факеле — {@value #TURBULENCE} от избытка на оси (George, Alpert, Tamanini, Int. J.
 * Heat Mass Transfer 20, 1977: 0,38–0,40), δn = (n₀ − 1)·I·(T₀/T)·(1 − T₀/T); вихри — {@value #EDDY} радиуса столба
 * (интегральный масштаб турбулентности факела, ⚠ оценка), идут вверх со скоростью газа.
 * <p>
 * <b>Свет факела</b> двигателя — не шар, а струя от среза сопла до точки Дрейпера ({@link Frame#jet}): каждая часть
 * {@link Flame} (ядро, пламя догорания, остывающий хвост) — равномерный светящийся отрезок своей яркости и цвета, свет на
 * грань — точный интеграл по отрезку ({@link #segment}), только по части, что над гранью. Вдали он сходится к прежнему
 * шару той же площади сбоку.
 * <p>
 * <b>Тени.</b> Прямой свет на мир перекрывают блоки: от точки мира к источнику — по клеткам окна заслонов вокруг глаза
 * ({@link Solids}, {@link #blocked}): мир — блоки, заслон на пути известен точно, в кадре он или нет. По глубине кадра
 * этого не решить (за видимой гранью — толща дома или пустота за столбом). За окном ({@value Solids#SIZE_XZ} блоков
 * поперёк) теней нет (⚠ предел окна).
 * <p>
 * Источников — не больше четырёх каждого вида, самые заметные у глаза. Прохода нет, пока нет ни света, ни горячего газа.
 */
public final class HeatPass {
    /** n − 1 воздуха у земли (288 K, 1 атм, 550 нм), Edlén. */
    public static final double N0 = 2.77e-4;
    /** Пульсации температуры в факеле — доля избытка на оси. */
    public static final double TURBULENCE = 0.4;
    /** Вихри — эта доля радиуса столба (⚠ оценка). */
    public static final double EDDY = 0.2;
    /** Газ холоднее воздуха + столько, K, — без дрожания (δn меньше 1 % пика). */
    static final double COOL = 20;
    /** Дрожание меньше этой доли пикселя — не рисуется. */
    static final double SHIFT_MIN = 0.05;
    /** Свет шара на земле под ним меньше этой доли света открытого неба — шар на мир не светит (как у ядерки). */
    static final double SEEN = 0.005;
    /** Клеток луча тени не больше (дальше — свободно): луч через всё окно по диагонали — до 480. */
    static final int SHADOW_CELLS = 160;

    @Nullable
    static ShaderInstance shader;

    /** Источники кадра. */
    public static final class Frame {
        final Matrix4f lights = new Matrix4f().zero(), lightColor = new Matrix4f().zero(), hot = new Matrix4f().zero(),
                shape = new Matrix4f().zero();
        /**
         * Факелы: столбец i — срез сопла от глаза (xyz) и конец ядра (w; 0 — нет); ось струи от сопла (xyz) и конец пламени;
         * конец струи, полуширина у среза, её рост на метр, ослабление воздуха; свет ядра, пламени и хвоста на метр струи
         * (rgb, линейный, белая стена в полдень — 1 в метре сбоку от метра струи, без привыкания: его ставит кадр).
         */
        final Matrix4f jets = new Matrix4f().zero(), jetAxis = new Matrix4f().zero(), jetSize = new Matrix4f().zero(),
                jetCore = new Matrix4f().zero(), jetFlame = new Matrix4f().zero(), jetTail = new Matrix4f().zero();
        private final double[] lightWeight = new double[4], hotWeight = new double[4], jetWeight = new double[4];
        private final Vector4f column = new Vector4f();
        /**
         * Привыкание глаза кадра ({@code Sight.exposure}: к свету неба и к свету огня у глаза — одно на весь кадр) и свет
         * открытого неба (линейный, для порога света); угол пикселя, рад.
         */
        public double exposure = 1, open = 1, pixel = 1e-3;
        /**
         * Свет огня у глаза в этом кадре (освещённость белой стены у глаза, линейная, белая стена в полдень — 1, без
         * привыкания): по нему глаз привыкает в следующем ({@link #atEye()}).
         */
        double atEye;

        /**
         * Привыкание глаза с огнём: огонь освещает и место глаза, и глаз (экспозиция) привыкает к свету вокруг себя — к
         * свету неба (днём 1, ночью 1/1200, {@code Sight.adapted}) и свету огня у глаза вместе ({@code Sight.exposure}).
         * Без этого свет огня шёл бы в кадр с ночным ×1200 и заливал белым всё в сотнях блоков (залп «Града» ночью — секунды
         * белого кадра), а факел ускорителя в 8 м от камеры — бликом и вуалью весь кадр. Шары, лужи, пожары и обломки — этого
         * кадра (собраны до сбора квадратов), факелы снарядов — прошлого; привыкание — сразу, у глаза оно идёт за 0,1–1 с
         * (зрачок, колбочки; ⚠ оценка порядка).
         */
        double adapted() {
            return exposure;
        }

        void begin() {
            atEye = 0;
            lights.zero();
            lightColor.zero();
            hot.zero();
            shape.zero();
            jets.zero();
            jetAxis.zero();
            jetSize.zero();
            jetCore.zero();
            jetFlame.zero();
            jetTail.zero();
            Arrays.fill(lightWeight, 0);
            Arrays.fill(hotWeight, 0);
            Arrays.fill(jetWeight, 0);
        }

        /** Огонь кадра собран: свет у глаза — привыканию того, что рисуется до слоя в следующем кадре. */
        public void done() {
            lastAtEye = atEye;
        }

        /** Ещё свет у глаза (линейный, белая стена в полдень — 1) — от источников, которых нет в {@link #light} этого кадра. */
        public void eye(double light) {
            atEye += Math.max(0, light);
        }

        /** Свет огня у глаза в этом кадре (линейный, белая стена в полдень — 1). */
        public double atEye() {
            return atEye;
        }

        boolean lit() {
            return lights.m03() > 0 || lights.m13() > 0 || lights.m23() > 0 || lights.m33() > 0 || jetWeight[0] > 0 || jetWeight[1] > 0
                    || jetWeight[2] > 0 || jetWeight[3] > 0;
        }

        boolean hotAny() {
            return hot.m03() > 0 || hot.m13() > 0 || hot.m23() > 0 || hot.m33() > 0;
        }

        /**
         * Шар света: середина от глаза (x, y, z), радиус r; свет чёрного тела у поверхности — линейный, белая стена в
         * полдень — 1 (rgb, без привыкания: его ставит кадр); ослабление воздуха air, 1/блок; h — высота середины над
         * землёй (порог: свет на земле под шаром против неба).
         */
        public void light(double x, double y, double z, double r, double cr, double cg, double cb, double air, double h) {
            light(x, y, z, r, cr, cg, cb, air, h, true);
        }

        /** То же; eye — свет идёт и в глаз ({@link #atEye}; нет — его там уже учли). */
        public void light(double x, double y, double z, double r, double cr, double cg, double cb, double air, double h, boolean eye) {
            double lum = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb;
            if (r <= 0 || lum <= 0) return;
            double w = lum * r * r / Math.max(x * x + y * y + z * z, r * r);
            if (eye) atEye += w;
            if (lum * exposure * r * r / Math.max(h * h, r * r) < SEEN * Math.max(open, 1e-4)) return;
            int slot = 0;
            for (int i = 1; i < 4; i++) if (lightWeight[i] < lightWeight[slot]) slot = i;
            if (w <= lightWeight[slot]) return;
            lightWeight[slot] = w;
            lights.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
            lightColor.setColumn(slot, column.set((float) cr, (float) cg, (float) cb, (float) air));
        }

        /**
         * Горячий газ: столб от низа (x, y, z от глаза) высотой h и радиусом r, температура kelvin, скорость подъёма rise
         * (м/с = блоки/с).
         */
        public void hot(double x, double y, double z, double h, double r, double kelvin, double rise) {
            if (r <= 0 || h <= 0 || kelvin < CloudModel.T_AIR_K + COOL) return;
            double t0 = CloudModel.T_AIR_K / kelvin, dn = N0 * TURBULENCE * t0 * (1 - t0);
            // дрожание мельче {@link #SHIFT_MIN} пикселя и на самом длинном пути сквозь столб не видно: столб не нужен
            double eddy = Math.max(0.05, EDDY * r), chord = Math.max(2 * r, Math.min(h, 4 * r));
            if (dn * Math.sqrt(chord / eddy) < SHIFT_MIN * pixel) return;
            double cx = x, cy = y + 0.5 * h, cz = z;
            double w = dn * r * Math.max(r, h) / Math.max(cx * cx + cy * cy + cz * cz, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (hotWeight[i] < hotWeight[slot]) slot = i;
            if (w <= hotWeight[slot]) return;
            hotWeight[slot] = w;
            hot.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
            shape.setColumn(slot, column.set((float) h, (float) eddy, (float) Math.max(0.1, rise), (float) dn));
        }

        /**
         * Горячий газ над очагом пламени шлейфа {@code p} (низ — (x, y, z) от глаза): столб до высоты, где избыток на оси по
         * Хескестаду ({@link FirePlume#excess}) падает до {@link #COOL}, радиус — очаг или 0,12 высоты (полуширина факела,
         * Heskestad), температура — избыток на середине, скорость — на оси там ({@link FirePlume#axisSpeed}); доля share.
         */
        public void plume(double x, double y, double z, FirePlume p, double share) {
            if (share <= 0) return;
            double lo = p.flameHeight(), hi = 400;
            for (int i = 0; i < 30; i++) {
                double mid = 0.5 * (lo + hi);
                if (p.excess(mid) > COOL) lo = mid;
                else hi = mid;
            }
            double h = lo, r = Math.max(2 * p.size, 0.12 * h);
            hot(x, y, z, h, r, CloudModel.T_AIR_K + p.excess(0.5 * h) * share, p.axisSpeed(0.5 * h));
        }

        /**
         * Факел двигателя: середина струи (x, y, z) от глаза, единичная ось струи от сопла назад (jx, jy, jz), факел flame;
         * ослабление воздуха air, 1/блок; h — высота середины над землёй (порог, как у шара). Часть струи — равномерный
         * отрезок: ядро — [0, ядро] с площадью сбоку конуса ядра, пламя — [0, пламя] с площадью пламени без ядра (оно
         * обнимает ядро), хвост — [пламя, конец]; свет части на метр — яркость × площадь сбоку / длина / π (тело с яркостью
         * L и площадью сбоку S даёт белой стене лицом к нему в d блоках L·S/(π·d²): у шара S = πr², отсюда L·r²/d²).
         * Свет в глаз — уже у кадра ({@link #eye}); выбор четырёх и порог — как у шара той же площади ({@link Flame#lightRadius}).
         * cut — путь по оси от среза до земли: дальше струи нет (она растекается по земле — ⚠ свечения стенной струи
         * нет), и сквозь землю она не светит; свет на метр у частей — прежний, короче — только их концы.
         */
        public void jet(double x, double y, double z, double jx, double jy, double jz, Flame flame, double air, double h, double cut) {
            double lr = flame.lightRadius(), lum = flame.brightness();
            double mid = flame.middle(), core = flame.core(), fl = Math.max(flame.flame(), core), end = Math.max(flame.length(), fl);
            double c = Math.min(cut, end);
            if (lr <= 0 || lum <= 0 || !(c > 0)) return;
            if (lum * exposure * lr * lr / Math.max(h * h, lr * lr) < SEEN * Math.max(open, 1e-4)) return;
            double w = lum * lr * lr / Math.max(x * x + y * y + z * z, lr * lr);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (jetWeight[i] < jetWeight[slot]) slot = i;
            if (w <= jetWeight[slot]) return;
            jetWeight[slot] = w;
            double[] parts = flame.partAreas();
            jets.setColumn(slot, column.set((float) (x - jx * mid), (float) (y - jy * mid), (float) (z - jz * mid), (float) Math.max(1e-3, Math.min(core, c))));
            jetAxis.setColumn(slot, column.set((float) jx, (float) jy, (float) jz, (float) Math.min(fl, c)));
            jetSize.setColumn(slot, column.set((float) c, (float) flame.width(), (float) (flame.width() / Math.max(core, 1e-6)), (float) air));
            part(jetCore, slot, flame.coreColor(), flame.coreLuminance(), parts[0], core);
            part(jetFlame, slot, flame.flameColor(), flame.flameLuminance(), parts[1], fl);
            part(jetTail, slot, flame.tailColor(), flame.tailLuminance(), parts[2], end - fl);
        }

        private void part(Matrix4f m, int slot, int color, double luminance, double area, double length) {
            if (length <= 1e-6 || area <= 0 || luminance <= 0) {
                m.setColumn(slot, column.set(0, 0, 0, 0));
                return;
            }
            double[] rgb = rgb(color, luminance * area / (length * Math.PI));
            m.setColumn(slot, column.set((float) rgb[0], (float) rgb[1], (float) rgb[2], 0));
        }
    }

    /** Линейный rgb цвета color (sRGB, наибольший канал 255) с яркостью (luma) luminance. */
    static double[] rgb(int color, double luminance) {
        double r = FlameModel.linear(color >> 16 & 255), g = FlameModel.linear(color >> 8 & 255), b = FlameModel.linear(color & 255);
        double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b, k = luma > 0 ? luminance / luma : 0;
        return new double[]{r * k, g * k, b * k};
    }

    /**
     * Прямой свет равномерного светящегося отрезка на грань — ∫ cos θ / d² по отрезку, 1/м (свет на метр × это = свет на
     * белой стене): отрезок от точки a (от точки грани) по единичному u на [t0, t1], нормаль грани n; только часть над
     * гранью (под ней грань источника не видит). w2 — квадрат полуширины струи: грань в самой струе не видит её сингулярно
     * ярко. Первообразная (c₀ + c₁τ)/(τ² + h²)^{3/2} по τ = t + a·u — c₀τ/(h²√(τ² + h²)) − c₁/√(τ² + h²), c₀ = n·a − (a·u)(n·u),
     * c₁ = n·u, h — расстояние до прямой; разности — в виде без вычитания близких чисел (в шейдере float). Тот же расчёт —
     * {@code heatSegment} в {@code fx_heat}.
     */
    public static double segment(double ax, double ay, double az, double ux, double uy, double uz, double t0, double t1, double nx, double ny,
                                 double nz, double w2) {
        double[] t = above(ax, ay, az, ux, uy, uz, t0, t1, nx, ny, nz);
        if (t[1] <= t[0]) return 0;
        double na = nx * ax + ny * ay + nz * az, nu = nx * ux + ny * uy + nz * uz;
        double b = ax * ux + ay * uy + az * uz, h2 = Math.max(ax * ax + ay * ay + az * az - b * b, 0) + w2;
        double c0 = na - b * nu, s0 = t[0] + b, s1 = t[1] + b;
        double q0 = 1 / (s0 * s0 + h2), q1 = 1 / (s1 * s1 + h2), r0 = Math.sqrt(q0), r1 = Math.sqrt(q1);
        // (s1·r1 − s0·r0)/h² и r1 − r0 без вычитания близких: s²r² = 1 − h²r²
        double k = s0 * s1 > 0 ? (s1 - s0) * (s1 + s0) * q0 * q1 / (s1 * r1 + s0 * r0) : (s1 * r1 - s0 * r0) / h2;
        double dr = (s0 - s1) * (s0 + s1) * q0 * q1 / (r0 + r1);
        return Math.max(c0 * k - nu * dr, 0);
    }

    /** Часть [t0, t1] отрезка от a по единичному u, что над гранью с нормалью n ({@code heatAbove}); пусто — t1 ≤ t0. */
    public static double[] above(double ax, double ay, double az, double ux, double uy, double uz, double t0, double t1, double nx, double ny,
                                 double nz) {
        double na = nx * ax + ny * ay + nz * az, nu = nx * ux + ny * uy + nz * uz;
        if (Math.abs(nu) > 1e-6) {
            double ts = -na / nu;
            if (nu > 0) t0 = Math.max(t0, ts);
            else t1 = Math.min(t1, ts);
        } else if (na <= 0) {
            t1 = t0;
        }
        return new double[]{t0, t1};
    }

    /** Заслоны мира для {@link #blocked}: клетка (x, y, z) окна — заслон ли, или вне окна (тогда — свободно). */
    public interface Cells {
        boolean solid(int x, int y, int z);
    }

    /**
     * Закрыт ли путь от a до b заслоном (координаты окна заслонов, блоки) — зеркало {@code heatBlocked} в {@code fx_heat}
     * (там наоборот: 1 — путь свободен): клетки
     * вдоль отрезка по порядку (Amanatides, Woo, «A fast voxel traversal algorithm», Eurographics 1987), часть за окном
     * size — свободна, клетка самой точки a — её грань, не заслон; не больше {@link #SHADOW_CELLS} клеток.
     */
    public static boolean blocked(double ax, double ay, double az, double bx, double by, double bz, int[] size, Cells cells) {
        double[] a = {ax, ay, az}, d = {bx - ax, by - ay, bz - az};
        double t0 = 0, t1 = 1;
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-6) {
                if (a[k] < 0 || a[k] >= size[k]) return false;
            } else {
                double ta = -a[k] / d[k], tb = (size[k] - a[k]) / d[k];
                t0 = Math.max(t0, Math.min(ta, tb));
                t1 = Math.min(t1, Math.max(ta, tb));
            }
        }
        if (t1 <= t0) return false;
        int[] cell = new int[3], dir = new int[3];
        double[] inv = new double[3], next = {1e9, 1e9, 1e9};
        for (int k = 0; k < 3; k++) {
            cell[k] = Math.max(0, Math.min(size[k] - 1, (int) Math.floor(a[k] + d[k] * t0)));
            dir[k] = (int) Math.signum(d[k]);
            inv[k] = 1 / Math.max(Math.abs(d[k]), 1e-9);
            if (d[k] > 0) next[k] = (cell[k] + 1 - a[k]) * inv[k];
            else if (d[k] < 0) next[k] = (a[k] - cell[k]) * inv[k];
        }
        boolean own = t0 <= 0;
        for (int i = 0; i < SHADOW_CELLS; i++) {
            if (!own && cells.solid(cell[0], cell[1], cell[2])) return true;
            own = false;
            int k = next[0] <= next[1] && next[0] <= next[2] ? 0 : next[1] <= next[2] ? 1 : 2;
            double t = next[k];
            cell[k] += dir[k];
            next[k] += inv[k];
            if (t >= t1 || cell[k] < 0 || cell[k] >= size[k]) break;
        }
        return false;
    }

    private static final Frame FRAME = new Frame();
    /** Свет огня у глаза в последнем собранном кадре ({@link Frame#atEye}). */
    private static double lastAtEye;

    /**
     * Свет огня у глаза в последнем собранном кадре (линейный, белая стена в полдень — 1): к нему привыкает глаз кадра
     * ({@code Sight.exposure}, {@code FarView.exposure}).
     */
    public static double atEye() {
        return lastAtEye;
    }

    /** Выход из мира или смена измерения: огня у глаза нет. */
    public static void forget() {
        lastAtEye = 0;
        FRAME.atEye = 0;
    }
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f(), SHAPE = new Matrix4f(), LIGHT = new Matrix4f();
    /** Путь вихрей по модулю этого числа размеров вихря (шум не периодичен: раз в сотни вихрей узор перескакивает). */
    static final double NOISE_PERIOD = 256;
    private static final Vector3f FORWARD = new Vector3f();
    private static final Vector3f OPEN_SKY = new Vector3f();
    private static final Vector4f FACE_SHADE = new Vector4f();
    private static final int[] VIEWPORT = new int[4];
    private static int copy = -1, copyW, copyH;
    private static final long BORN = System.nanoTime();

    private HeatPass() {}

    /** Кадр источников: свет неба и привыкание глаза — до сбора (по ним — порог света). */
    public static Frame begin(ClientLevel level, double exposure, double pixel) {
        Frame f = FRAME;
        f.begin();
        f.exposure = exposure;
        f.pixel = pixel;
        int c = Minecraft.getInstance().gameRenderer.lightTexture().lightPixels.getPixelRGBA(0, 15);
        OPEN_SKY.set((c & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c >> 16 & 0xFF) / 255f);
        FACE_SHADE.set(level.getShade(Direction.UP, true), level.getShade(Direction.DOWN, true), level.getShade(Direction.NORTH, true),
                level.getShade(Direction.EAST, true));
        f.open = 0.2126 * linear(OPEN_SKY.x) + 0.7152 * linear(OPEN_SKY.y) + 0.0722 * linear(OPEN_SKY.z);
        return f;
    }

    private static double linear(double v) {
        return Math.pow(v, 2.2);
    }

    /**
     * Окно заслонов ({@link Solids}) к этому кадру — вокруг глаза eye (места источников — от него), секции у источников
     * света перечитываются сейчас. Зовёт слой перед проходом, если в кадре есть свет.
     */
    static void solids(Frame f, ClientLevel level, net.minecraft.world.phys.Vec3 eye) {
        if (!f.lit()) return;
        Vector4f c = new Vector4f();
        for (int i = 0; i < 4; i++) {
            if (f.lights.getColumn(i, c).w > 0) Solids.near(eye.x + c.x, eye.y + c.y, eye.z + c.z, c.w);
            // у струи — от среза сопла на её длину (до земли), не дальше 8 блоков: там она бьёт в землю
            if (f.jets.getColumn(i, c).w > 0) {
                double reach = Math.min(8, f.jetSize.getColumn(i, new Vector4f()).x);
                f.jets.getColumn(i, c);
                Solids.near(eye.x + c.x, eye.y + c.y, eye.z + c.z, reach);
            }
        }
        Solids.update(level, eye);
    }

    /** Нужен ли проход в этом кадре. */
    static boolean needed(Frame f) {
        return shader != null && (f.lit() || f.hotAny());
    }

    /**
     * Проход: копия кадра, вывод во весь кадр. {@code scene} — расстояние до мира, {@code view} — вид от глаза,
     * {@code pixel} — угол пикселя, рад; {@code haze} — дымка и туман кадра.
     */
    static boolean draw(Frame f, int scene, Matrix4f view, Matrix4f projection, Vector4f haze, double pixel) {
        ShaderInstance s = shader;
        if (s == null || scene < 0 || !copyFrame()) return false;
        VIEW_PROJECTION.set(projection).mul(view);
        VIEW_PROJECTION.invert(INVERSE);
        view.positiveZ(FORWARD).negate();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShaderTexture(0, copy);
        RenderSystem.setShaderTexture(1, scene);
        s.safeGetUniform("InvViewProj").set(INVERSE);
        s.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        s.safeGetUniform("PallOpenSky").set(OPEN_SKY.x, OPEN_SKY.y, OPEN_SKY.z);
        s.safeGetUniform("PallFaceShade").set(FACE_SHADE.x, FACE_SHADE.y, FACE_SHADE.z, FACE_SHADE.w);
        s.safeGetUniform("Haze").set(haze.x, haze.y, haze.z, haze.w);
        s.safeGetUniform("HeatLights").set(f.lights);
        double k = f.adapted();
        LIGHT.set(f.lightColor);
        Vector4f lc = new Vector4f();
        for (int i = 0; i < 4; i++) {
            LIGHT.getColumn(i, lc);
            LIGHT.setColumn(i, lc.set((float) (lc.x * k), (float) (lc.y * k), (float) (lc.z * k), lc.w));
        }
        s.safeGetUniform("HeatLightColor").set(LIGHT);
        Solids.bind(s);
        s.safeGetUniform("HeatJet").set(f.jets);
        s.safeGetUniform("HeatJetAxis").set(f.jetAxis);
        s.safeGetUniform("HeatJetSize").set(f.jetSize);
        s.safeGetUniform("HeatJetCore").set(exposed(f.jetCore, k));
        s.safeGetUniform("HeatJetFlame").set(exposed(f.jetFlame, k));
        s.safeGetUniform("HeatJetTail").set(exposed(f.jetTail, k));
        s.safeGetUniform("HeatHot").set(f.hot);
        // вихри идут вверх с газом: путь в размерах вихря — в double и по модулю периода шума, а не время в float
        double time = (System.nanoTime() - BORN) / 1e9;
        SHAPE.set(f.shape);
        Vector4f c = new Vector4f();
        for (int i = 0; i < 4; i++) {
            SHAPE.getColumn(i, c);
            if (c.y > 0) SHAPE.setColumn(i, c.set(c.x, c.y, (float) ((time * c.z / c.y) % NOISE_PERIOD), c.w));
        }
        s.safeGetUniform("HeatShape").set(SHAPE);
        s.safeGetUniform("HeatFrame").set(0f, (float) pixel, f.lit() ? 1f : 0f, f.hotAny() ? 1f : 0f);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh == null) {
            Solids.unbind();
            return false;
        }
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        s.apply();
        com.mojang.blaze3d.vertex.BufferUploader.draw(mesh);
        s.clear();
        Solids.unbind();
        RenderSystem.enableDepthTest();
        return true;
    }

    private static final Matrix4f EXPOSED = new Matrix4f();

    /** Свет m (rgb по столбцам) с привыканием глаза k — в общей матрице на один вызов (uniform копирует её сразу). */
    private static final Vector4f EXPOSED_COLUMN = new Vector4f();

    private static Matrix4f exposed(Matrix4f m, double k) {
        EXPOSED.set(m);
        Vector4f c = EXPOSED_COLUMN;
        for (int i = 0; i < 4; i++) {
            EXPOSED.getColumn(i, c);
            EXPOSED.setColumn(i, c.set((float) (c.x * k), (float) (c.y * k), (float) (c.z * k), c.w));
        }
        return EXPOSED;
    }

    /** Копия кадра, в который рисует проход (привязанного сейчас), своей текстурой с линейной выборкой. */
    private static boolean copyFrame() {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        if (w <= 0 || h <= 0) return false;
        if (copy < 0) {
            copy = GlStateManager._genTexture();
            GlStateManager._bindTexture(copy);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(copy);
        if (w != copyW || h != copyH) {
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
            copyW = w;
            copyH = h;
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        return true;
    }

    /** Для строки сценария: источники кадра. */
    public static String describe() {
        Frame f = FRAME;
        int l = 0, h = 0, j = 0;
        Vector4f c = new Vector4f();
        for (int i = 0; i < 4; i++) {
            if (f.lights.getColumn(i, c).w > 0) l++;
            if (f.hot.getColumn(i, c).w > 0) h++;
            if (f.jetWeight[i] > 0) j++;
        }
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "heat lights %d jets %d hot %d", l, j, h));
        for (int i = 0; i < 4; i++) {
            f.hot.getColumn(i, c);
            if (c.w <= 0) continue;
            Vector4f s = f.shape.getColumn(i, new Vector4f());
            sb.append(String.format(java.util.Locale.ROOT, " [r %.1f h %.0f dn %.2e]", c.w, s.x, s.w));
        }
        return sb.toString();
    }
}
