package ua.zentix.incoming.client.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.Exhaust;
import ua.zentix.incoming.client.fx.plume.Flame;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Факел двигателя вблизи ({@link Flame}, из {@code FlameModel}): полосы вдоль оси сопла, развёрнутые к камере (с любой
 * стороны — струя, а не плоская картинка), — оболочка (пламя догорания и остывающий хвост) кусками от среза до точки
 * Дрейпера, свет и цвет каждого места — из закона модели ({@link Flame#sheath}: белое пламя, жёлтый и оранжевый хвост,
 * гаснущий к концу), и ядро до конца сверхзвуковой части. Ширина — струи в этом месте ({@link Flame#widthAt}). Струя —
 * цилиндр: сбоку видна её длина, с торца — сечение. Полоса вдоль оси с торца сходит в отрезок поперёк (вид строго сзади
 * — «палка»), поэтому у среза сопла — ещё сечение струи лицом к камере: круг пламени (полуширина в конце пламени) и ядра
 * (полуширина у среза), {@code far/disc} того же потока, что сечение ({@link #HALO}). Геометрия — {@link #layout} (её
 * площадь с торца и сбоку сверяет {@code PlumeRendererTest}).
 * <p>
 * Рисует струю слой эффектов после мира ({@code FxLayer}, через {@link FarSprites}), а не сущность в мире: под Iris всё,
 * что нарисовано до конца мира, проходит через шейдерпак (Complementary: свет «глаз» ×1,5 в степени 1,5, облака и
 * дымка неба поверх того, что не пишет глубину), и днём струя в 75 раз ярче белой стены выходила бледным обрывком у
 * сопла (кадры шлагбаума RV2: 53 тёплых пикселя против 494 у 3.4.1), а ночью — видна. Сущность при отрисовке только
 * оставляет струю кадра ({@link #render}); яркость — с привыканием глаза кадра слоя ({@link FarView#exposure}), тем же,
 * что у света этого факела вдали: что ярче белого экрана, рисуется во всю силу и белеет ({@code Sight.WHITE}). Блик и
 * вуаль — у света факела ({@code FarFlightView}) на любой дальности. Светит поверх того, что за ней (сложением), и
 * ложится поверх дыма своего следа у того же места.
 */
public final class PlumeRenderer {
    /**
     * Полуразмер квадрата сечения на его радиус: круг {@code far/disc} с мягким краем — того же потока, что круг радиуса r
     * ({@link FarSprites#DISC}). Не ореол: его хвост в три радиуса — свечение вокруг, а не струя, и с торца факел выходил
     * «солнцем» (свет в глазу вокруг — блик и вуаль у {@code FarFlightView}).
     */
    static final float HALO = (float) FarSprites.DISC;
    /** Слои {@link #layout}: куски оболочки, ядро, сечение пламени, сечение ядра. */
    static final int SHEATH = 0, CORE = 1, FLAME_DISC = 2, CORE_DISC = 3;

    /** Куда {@link #layout} кладёт квадраты: слой, кусок оболочки (у остальных 0), четыре угла (система снаряда). */
    interface Quads {
        /** У полос a, b — край ближе к соплу, c, d — дальше; у сечений — углы квадрата. */
        void quad(int layer, int piece, Vector3f a, Vector3f b, Vector3f c, Vector3f d);
    }

    /** Струя кадра: факел, поворот системы снаряда, место снаряда в мире. */
    private record Jet(Exhaust.Plume plume, Quaternionf rotation, Vec3 origin) {}

    /**
     * Струи этого кадра — по сущности: Iris рисует сущности ещё и в карту теней, и струя одной сущности легла бы дважды.
     * Только поток отрисовки.
     */
    private static final Map<net.minecraft.world.entity.Entity, Jet> FRAME = new IdentityHashMap<>();

    private PlumeRenderer() {}

    /**
     * Сущность key рисуется в этом кадре со струёй p: её нарисует слой эффектов ({@link #collect}).
     *
     * @param rotation поворот системы снаряда (нос по +Z)
     * @param origin   положение снаряда в мире в этот кадр
     */
    public static void render(Exhaust.Plume p, net.minecraft.world.entity.Entity key, Quaternionf rotation, Vec3 origin) {
        // с борта своего двигателя струи не видно (как и света факела вдали, FarFlightView.onboard): Iris рисует сущность
        // камеры в карту теней, и без этого слой нарисовал бы струю у самого глаза
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getCameraEntity() == key && mc.options.getCameraType().isFirstPerson()) return;
        FRAME.put(key, new Jet(p, new Quaternionf(rotation), origin));
    }

    /** Струи кадра — в квадраты слоя эффектов (от глаза кадра {@code view.camera()}). */
    public static void collect(FarView view, FarSprites out) {
        if (FRAME.isEmpty()) return;
        Vec3 eye = view.camera();
        double exposure = view.exposure();
        for (Jet jet : FRAME.values()) draw(jet, eye, exposure, view, out);
    }

    /**
     * Конец кадра ({@code FxLayer}, этап после мира, и тогда, когда слой ничего не рисовал): струи кадра забыты — иначе
     * без сбора (слой не готов, перезагрузка ресурсов) они держали бы сущности и легли бы потом струёй прошлого места.
     */
    public static void endFrame() {
        FRAME.clear();
    }

    /** Выход из мира. */
    public static void reset() {
        FRAME.clear();
    }

    private static void draw(Jet jet, Vec3 eye, double exposure, FarView view, FarSprites out) {
        Exhaust.Plume p = jet.plume();
        Flame f = p.flame();
        Quaternionf rot = jet.rotation(), inv = new Quaternionf(rot).conjugate();
        Vec3 o = jet.origin();
        // от среза сопла до глаза — в системе снаряда (сопло на (0, y, z)); оси экрана там же — копии (векторы вида живые)
        Vector3f cam = new Vector3f((float) (eye.x - o.x), (float) (eye.y - o.y), (float) (eye.z - o.z)).rotate(inv).sub(0, p.y(), 0);
        Vector3f left = new Vector3f(view.left()).rotate(inv), up = new Vector3f(view.up()).rotate(inv);
        float ox = (float) (o.x - eye.x), oy = (float) (o.y - eye.y), oz = (float) (o.z - eye.z);
        Flame.Sheath sheath = f.sheath();
        // дымка воздуха до струи гасит её свет, как у света этого факела вдали (FarFlightView: яркость × пропускание, потом
        // на экран): на 0,8 прорисовки без скачка
        double dist = Math.sqrt(ox * ox + oy * oy + oz * oz);
        float near = p.near();
        // свет струи — поверх дыма её же выхлопа: след и столб у струи — то же вещество, свечение и рассеянный им свет
        // складываются (⚠ самопоглощение не учитывается); по квадратам сортировка резала струю ступенями между клубами
        // (кадры F6 сзади). Запас — глубина струи вдоль взгляда и клубы следа у оси (до ~3,7σ₀, σ₀ — половина ширины струи
        // в её конце, TrailModel.Trail.of): дым дальше этого перед струёй, чужой и свой, её закрывает
        Vector3f axis = new Vector3f(0, 0, -1).rotate(rot);
        double along = Math.abs((axis.x * ox + axis.y * oy + axis.z * oz) / Math.max(1e-6, Math.sqrt(ox * ox + oy * oy + oz * oz)));
        float ahead = (float) (f.length() * along + 2 * f.spread());
        double lit = exposure * view.air(dist, oy);
        layout(f, p.z(), cam, left, up, (layer, piece, a, b, c, d) -> {
            for (Vector3f v : new Vector3f[]{a, b, c, d}) v.add(0, p.y(), 0).rotate(rot).add(ox, oy, oz);
            switch (layer) {
                case SHEATH -> {
                    float[] c0 = Sight.onScreen(sheath.color()[piece], sheath.luminance()[piece] * lit, near);
                    float[] c1 = Sight.onScreen(sheath.color()[piece + 1], sheath.luminance()[piece + 1] * lit, near);
                    out.glowStrip(a, b, c, d, c0[0], c0[1], c0[2], c1[0], c1[1], c1[2], (float) f.widthAt(sheath.x()[piece]), ahead);
                }
                case CORE -> {
                    float[] c0 = Sight.onScreen(f.coreColor(), f.coreLuminance() * lit, near);
                    out.glowStrip(a, b, c, d, c0[0], c0[1], c0[2], c0[0], c0[1], c0[2], (float) f.width(), ahead);
                }
                default -> {
                    boolean core = layer == CORE_DISC;
                    float[] c0 = core ? Sight.onScreen(f.coreColor(), f.coreLuminance() * lit, near) : Sight.onScreen(f.flameColor(), f.flameLuminance() * lit, near);
                    double r = new Vector3f(c).sub(a).length() / 2 / Math.sqrt(2) / HALO;
                    out.glowDisc((a.x + c.x) / 2, (a.y + c.y) / 2, (a.z + c.z) / 2, r, c0[0], c0[1], c0[2], (float) r);
                }
            }
        });
    }

    /**
     * Квадраты факела f со срезом сопла на z0 (система снаряда, нос по +Z) для камеры cam и осей экрана left, up там же:
     * куски оболочки по местам {@link Flame#sheath} и ядро — полосы вдоль оси, повёрнутые к камере, и сечения — круги
     * пламени и ядра у среза лицом к камере.
     */
    static void layout(Flame f, float z0, Vector3f cam, Vector3f left, Vector3f up, Quads out) {
        double[] xs = f.sheath().x();
        Vector3f prev = null;
        for (int i = 0; i < xs.length; i++) {
            Vector3f side = side(cam, z0 - (float) xs[i]).mul((float) f.widthAt(xs[i]));
            if (prev != null) {
                float za = z0 - (float) xs[i - 1], zb = z0 - (float) xs[i];
                out.quad(SHEATH, i - 1, new Vector3f(-prev.x, -prev.y, za), new Vector3f(prev.x, prev.y, za), new Vector3f(side.x, side.y, zb),
                        new Vector3f(-side.x, -side.y, zb));
            }
            prev = side;
        }
        Vector3f s0 = side(cam, z0 - (float) f.core() / 2).mul((float) f.width());
        float zc = z0 - (float) f.core();
        out.quad(CORE, 0, new Vector3f(-s0.x, -s0.y, z0), new Vector3f(s0.x, s0.y, z0), new Vector3f(s0.x, s0.y, zc), new Vector3f(-s0.x, -s0.y, zc));
        disc(out, FLAME_DISC, new Vector3f(0, 0, z0 - (float) f.width()), left, up, HALO * (float) f.widthAt(f.flame()));
        disc(out, CORE_DISC, new Vector3f(0, 0, z0 - 0.5f * (float) f.width()), left, up, HALO * (float) f.width());
    }

    /** Поперёк оси Z к камере в точке оси z: единичный вектор, перпендикулярный оси и лучу к камере. */
    private static Vector3f side(Vector3f cam, float z) {
        Vector3f toCam = new Vector3f(cam).sub(0, 0, z);
        Vector3f side = new Vector3f(0, 0, -1).cross(toCam);
        if (side.lengthSquared() < 1e-6f) side.set(1, 0, 0);
        return side.normalize();
    }

    /** Квадрат полуразмера s с центром at лицом к камере (оси экрана left, up). */
    private static void disc(Quads out, int layer, Vector3f at, Vector3f left, Vector3f up, float s) {
        Vector3f l = new Vector3f(left).mul(s), u = new Vector3f(up).mul(s);
        out.quad(layer, 0, new Vector3f(at).sub(l).sub(u), new Vector3f(at).sub(l).add(u), new Vector3f(at).add(l).add(u), new Vector3f(at).add(l).sub(u));
    }
}
