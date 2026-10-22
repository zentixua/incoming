package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Узор поверхности огненного шара ядерки ({@code textures/fx/nukeball.bin}, {@code tools/bake_fx}: запекание Blender
 * и {@code pack.py}): у каждого кадра — карта по направлениям от центра шара (октаэдрическая, ось y — вверх), в текселе
 * — во сколько раз перепад температуры поверхности там больше среднего; среднее p⁴ по сфере — 1 (Стефан–Больцман:
 * шар светит мощностью модели). Яркость и цвет — чёрное тело температуры {@code MushroomModel}, узор — только где
 * поверхность горячее и холоднее. Кадры — по пути всплытия шара в его радиусах ({@code FireballModel.surfaceRise}).
 * В видеопамять — трёхмерной текстурой R16 (u, v, кадр): между кадрами — линейно. Рисует проход пелены
 * ({@code fx_pall}: {@code PallBall}); не прочитан — шар без узора, как прежде ({@link #ready}).
 */
public final class NukeBallSurface extends SimplePreparableReloadListener<NukeBallSurface.Data> {
    public static final NukeBallSurface INSTANCE = new NukeBallSurface();
    private static final ResourceLocation FILE = Incoming.id("textures/fx/nukeball.bin");
    /** Узор в файле — p / {@link #P_MAX} в 16 битах ({@code pack.py}: BALL_P). */
    public static final double P_MAX = 2.0;
    /** Блок текстуры: трёхмерную ShaderInstance не привязывает (у него все — двумерные), свой блок, своя привязка. */
    static final int UNIT = 15;

    /** Прочитанный файл: сторона карты, путь всплытия кадров в радиусах шара, узор (кадр, v, u) в 16 битах. */
    public record Data(int side, float[] rises, short[] values) {
        /** Узор в текселе (u, v) кадра k. */
        public double at(int k, int u, int v) {
            return (values[(k * side + v) * side + u] & 0xFFFF) / 65535.0 * P_MAX;
        }
    }

    private static volatile Data data;
    private static int texture = -1;

    private NukeBallSurface() {
    }

    /** Разбор файла: int32 сторона, int32 кадров, float32 путь кадров, uint16 узор — всё little-endian. */
    public static Data parse(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        int side = b.getInt(), frames = b.getInt();
        if (side < 2 || side > 1024 || frames < 2 || frames > 1024) throw new IOException("bad header " + side + "×" + frames);
        float[] rises = new float[frames];
        for (int i = 0; i < frames; i++) rises[i] = b.getFloat();
        short[] values = new short[side * side * frames];
        b.asShortBuffer().get(values);
        return new Data(side, rises, values);
    }

    @Override
    protected Data prepare(ResourceManager resources, ProfilerFiller profiler) {
        Optional<Resource> res = resources.getResource(FILE);
        if (res.isEmpty()) {
            Incoming.LOG.warn("{} not found: the nuclear fireball has no baked surface", FILE);
            return null;
        }
        try (InputStream in = res.get().open()) {
            return parse(in);
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("{} could not be read: the nuclear fireball has no baked surface", FILE, e);
            return null;
        }
    }

    /** Поток отрисовки: в видеопамять (та же текстура при перезагрузке ресурсов). */
    @Override
    protected void apply(Data d, ResourceManager resources, ProfilerFiller profiler) {
        if (d == null) {
            data = null;
            return;
        }
        if (texture < 0) texture = GlStateManager._genTexture();
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        ByteBuffer buf = MemoryUtil.memAlloc(d.values.length * 2);
        try {
            buf.asShortBuffer().put(d.values);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R16, d.side, d.side, d.rises.length, 0, GL11.GL_RED,
                    GL11.GL_UNSIGNED_SHORT, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
        data = d;
    }

    /** Узор прочитан и в видеопамяти: шар берёт его ({@code MushroomField}: доля узора), иначе — прежние языки клубов. */
    public static boolean ready() {
        return data != null;
    }

    /**
     * Место кадра для пути всплытия {@code rise} (радиусы шара): номер с долей по пути кадров (между кадрами — по прямой,
     * за краями — крайний).
     */
    public static double frame(double rise) {
        Data d = data;
        return d == null ? 0 : frame(d.rises, rise);
    }

    public static double frame(float[] rises, double rise) {
        if (rise <= rises[0]) return 0;
        for (int i = 0; i < rises.length - 1; i++) {
            if (rise <= rises[i + 1]) return i + (rise - rises[i]) / Math.max(rises[i + 1] - rises[i], 1e-9);
        }
        return rises.length - 1;
    }

    /** Путь всплытия кадров в радиусах шара; null — узор не прочитан. */
    public static float[] rises() {
        Data d = data;
        return d == null ? null : d.rises;
    }

    /** Кадров в текстуре (0 — не прочитана). */
    public static int frames() {
        Data d = data;
        return d == null ? 0 : d.rises.length;
    }

    /** Привязка к проходу лучей пелены после {@code apply()} шейдера: блок {@link #UNIT}, сэмплер {@code PallBall}. */
    public static void bind(ShaderInstance s) {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, data != null ? texture : 0);
        GlStateManager._activeTexture(unit);
        com.mojang.blaze3d.shaders.Uniform.uploadInteger(com.mojang.blaze3d.shaders.Uniform.glGetUniformLocation(s.getId(), "PallBall"), UNIT);
    }

    /** Снять привязку после отрисовки. */
    public static void unbind() {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
    }

    /**
     * Октаэдрическая карта направления (x, y, z; y — вверх) → u 0..1 (как {@code mushOctU} в {@code mushroom.glsl} и
     * {@code octa_decode} в {@code pack.py}): верхняя полусфера — ромб в середине, нижняя — его углы.
     */
    public static double octU(double x, double y, double z) {
        double s = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (y < 0) return 0.5 * ((1 - Math.abs(z / s)) * Math.signum(x) + 1);
        return 0.5 * (x / s + 1);
    }

    /** То же — v ({@code mushOctV}). */
    public static double octV(double x, double y, double z) {
        double s = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (y < 0) return 0.5 * ((1 - Math.abs(x / s)) * Math.signum(z) + 1);
        return 0.5 * (z / s + 1);
    }
}
