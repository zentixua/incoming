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
    /** Свет шара на земле под ним меньше этой доли света открытого неба — шар на мир не светит (как у ядерки). */
    static final double SEEN = 0.005;

    @Nullable
    static ShaderInstance shader;

    /** Источники кадра. */
    public static final class Frame {
        final Matrix4f lights = new Matrix4f().zero(), lightColor = new Matrix4f().zero(), hot = new Matrix4f().zero(),
                shape = new Matrix4f().zero();
        private final double[] lightWeight = new double[4], hotWeight = new double[4];
        private final Vector4f column = new Vector4f();
        /** Привыкание глаза кадра ({@code Sight.adapted}) и свет открытого неба (линейный, для порога света). */
        public double exposure = 1, open = 1;

        void begin() {
            lights.zero();
            lightColor.zero();
            hot.zero();
            shape.zero();
            Arrays.fill(lightWeight, 0);
            Arrays.fill(hotWeight, 0);
        }

        boolean lit() {
            return lights.m03() > 0 || lights.m13() > 0 || lights.m23() > 0 || lights.m33() > 0;
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
            double lum = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb;
            if (r <= 0 || lum <= 0 || lum * exposure * r * r / Math.max(h * h, r * r) < SEEN * Math.max(open, 1e-4)) return;
            double w = lum * r * r / Math.max(x * x + y * y + z * z, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (lightWeight[i] < lightWeight[slot]) slot = i;
            if (w <= lightWeight[slot]) return;
            lightWeight[slot] = w;
            lights.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
            lightColor.setColumn(slot, column.set((float) (cr * exposure), (float) (cg * exposure), (float) (cb * exposure), (float) air));
        }

        /**
         * Горячий газ: столб от низа (x, y, z от глаза) высотой h и радиусом r, температура kelvin, скорость подъёма rise
         * (м/с = блоки/с).
         */
        public void hot(double x, double y, double z, double h, double r, double kelvin, double rise) {
            if (r <= 0 || h <= 0 || kelvin < CloudModel.T_AIR_K + COOL) return;
            double t0 = CloudModel.T_AIR_K / kelvin, dn = N0 * TURBULENCE * t0 * (1 - t0);
            double cx = x, cy = y + 0.5 * h, cz = z;
            double w = dn * r * Math.max(r, h) / Math.max(cx * cx + cy * cy + cz * cz, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (hotWeight[i] < hotWeight[slot]) slot = i;
            if (w <= hotWeight[slot]) return;
            hotWeight[slot] = w;
            hot.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
            shape.setColumn(slot, column.set((float) h, (float) Math.max(0.05, EDDY * r), (float) Math.max(0.1, rise), (float) dn));
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
    }

    private static final Frame FRAME = new Frame();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f();
    private static final Vector3f FORWARD = new Vector3f();
    private static final Vector3f OPEN_SKY = new Vector3f();
    private static final Vector4f FACE_SHADE = new Vector4f();
    private static final int[] VIEWPORT = new int[4];
    private static int copy = -1, copyW, copyH;
    private static final long BORN = System.nanoTime();

    private HeatPass() {}

    /** Кадр источников: свет неба и привыкание глаза — до сбора (по ним — порог света). */
    public static Frame begin(ClientLevel level, double exposure) {
        Frame f = FRAME;
        f.begin();
        f.exposure = exposure;
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
        s.safeGetUniform("HeatLightColor").set(f.lightColor);
        s.safeGetUniform("HeatHot").set(f.hot);
        s.safeGetUniform("HeatShape").set(f.shape);
        float time = (float) ((System.nanoTime() - BORN) / 1e9 % 3600);
        s.safeGetUniform("HeatFrame").set(time, (float) pixel, f.lit() ? 1f : 0f, f.hotAny() ? 1f : 0f);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh == null) return false;
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        s.apply();
        com.mojang.blaze3d.vertex.BufferUploader.draw(mesh);
        s.clear();
        RenderSystem.enableDepthTest();
        return true;
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
        int l = 0, h = 0;
        Vector4f c = new Vector4f();
        for (int i = 0; i < 4; i++) {
            if (f.lights.getColumn(i, c).w > 0) l++;
            if (f.hot.getColumn(i, c).w > 0) h++;
        }
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "heat lights %d hot %d", l, h));
        for (int i = 0; i < 4; i++) {
            f.hot.getColumn(i, c);
            if (c.w <= 0) continue;
            Vector4f s = f.shape.getColumn(i, new Vector4f());
            sb.append(String.format(java.util.Locale.ROOT, " [r %.1f h %.0f dn %.2e]", c.w, s.x, s.w));
        }
        return sb.toString();
    }
}
