package ua.zentix.incoming.client.render;

import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import ua.zentix.incoming.Incoming;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Источник {@link LodDepth} — глубина рельефа Voxy (на NeoForge 1.21.1 — только через Roxy, официальный jar Voxy
 * 0.2.16 для 1.21.11). API у Voxy нет, поэтому только чтение его классов отражением, без миксинов и без записи
 * (так же глубину Voxy берёт Roxy, MIT: {@code compat/RoxyLodEntityOcclusion}):
 * <ul>
 *   <li>{@code IGetVoxyRenderSystem.getNullable()} — рендерер Voxy этого мира или null;</li>
 *   <li>его поле {@code pipeline} (свой конвейер без шейдерпака, {@code IrisVoxyRenderPipeline} под шейдерпаком) →
 *       {@code fb} → {@code getDepthTex().id} — текстура глубины, в которую Voxy рисует непрозрачные LOD;</li>
 *   <li>{@code pipeline.properties} — диапазон глубины ({@code isZero2One}), её направление ({@code isReverseZ}),
 *       значение очистки ({@code clearDepth}) и ближней плоскости ({@code inverseClearDepth});</li>
 *   <li>{@code getViewport()} → {@code projection} — проекция, которой записана глубина, и {@code frameId} — рисовал
 *       ли Voxy с прошлого кадра.</li>
 * </ul>
 * Что в текстуре (Voxy 0.2.16, {@code AbstractRenderPipeline.initDepthStencil}): перед LOD Voxy ставит ближнюю
 * плоскость там, где рисовал Minecraft (так LOD не ложатся поверх мира), — это {@link LodDepth.View#world}, а не LOD
 * в 8–16 блоках; под шейдерпаком он потом возвращает там очистку. Небо — очистка. Остальное — LOD его проекцией: у
 * шейдерпака своя программа цвета, но вершины ставит Voxy той же проекцией ({@code vxProj} пака), поэтому чужих
 * плоскостей, как у DH под Iris, нет.
 * <p>
 * Без шейдерпака (и под шейдерпаком, который просит LOD в глубине Minecraft, {@code renderToVanillaDepth}) Voxy
 * переносит LOD и в глубину Minecraft, прижимая те, что дальше её дальней плоскости, к самой плоскости
 * ({@code blit_texture_depth_cutout.frag}): дальний взрыв прятался бы за любой LOD-землёй рядом с ним, поэтому
 * {@link LodDepth.View#pinsWorldDepth}. Классы или поля Voxy другой версии не нашлись — источника нет (строка в лог),
 * игра идёт как без Voxy; сломалось чтение на ходу — одно предупреждение, и источник выключен.
 */
final class VoxyDepth implements LodDepth.Source {
    private static final String CORE = "me.cortex.voxy.client.core.";

    private final Method renderSystem, viewport, depthTex, zeroToOne, reverseZ, clearDepth, nearDepth;
    private final Field pipeline, fb, properties, textureId, projection, frameId;
    /** {@code frameId} кадра Voxy, глубину которого слой уже взял; −1 — ещё ни одного. */
    private int taken = -1, last = -1;
    /** Как читалась глубина в прошлый раз, что попал в лог; −1 — ещё никак. */
    private int logged = -1;
    private String described = "";
    private boolean broken;
    private final Matrix4f projectionCopy = new Matrix4f();
    private final Vector4f point = new Vector4f();

    VoxyDepth(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> getter = Class.forName(CORE + "IGetVoxyRenderSystem", false, loader);
        Class<?> system = Class.forName(CORE + "VoxyRenderSystem", false, loader);
        Class<?> abstractPipeline = Class.forName(CORE + "AbstractRenderPipeline", false, loader);
        Class<?> depthBuffer = Class.forName(CORE + "rendering.util.DepthFramebuffer", false, loader);
        Class<?> texture = Class.forName(CORE + "gl.GlTexture", false, loader);
        Class<?> props = Class.forName(CORE + "RenderProperties", false, loader);
        Class<?> view = Class.forName(CORE + "rendering.Viewport", false, loader);
        renderSystem = getter.getMethod("getNullable");
        viewport = system.getMethod("getViewport");
        pipeline = system.getDeclaredField("pipeline");
        pipeline.setAccessible(true);
        fb = abstractPipeline.getField("fb");
        properties = abstractPipeline.getField("properties");
        depthTex = depthBuffer.getMethod("getDepthTex");
        textureId = texture.getField("id");
        zeroToOne = props.getMethod("isZero2One");
        reverseZ = props.getMethod("isReverseZ");
        clearDepth = props.getMethod("clearDepth");
        nearDepth = props.getMethod("inverseClearDepth");
        projection = view.getField("projection");
        frameId = view.getField("frameId");
    }

    /** При запуске клиента: стоит ли Voxy и нашлись ли все его классы и поля; null — нет. */
    @Nullable
    static LodDepth.Source init() {
        if (!ModList.get().isLoaded("voxy")) return null;
        // тот же кадр с глубиной Voxy и без неё — для сравнения кадров
        if (!Boolean.parseBoolean(System.getProperty("incoming.lodDepth.voxy", "true"))) {
            Incoming.LOG.info("Voxy depth for the effects layer: off (-Dincoming.lodDepth.voxy=false)");
            return null;
        }
        try {
            VoxyDepth d = new VoxyDepth(VoxyDepth.class.getClassLoader());
            Incoming.LOG.info("Voxy depth for the effects layer: classes found, reading its depth texture (read-only)");
            return d;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Incoming.LOG.warn("Voxy: its classes differ from Voxy 0.2.16 the mod reads ({}) — Voxy terrain does not hide distant smoke and blasts", e.toString());
            return null;
        }
    }

    @Override
    public boolean current(LodDepth.View out) {
        if (broken) return false;
        try {
            Object system = renderSystem.invoke(null);
            if (system == null) return false;
            // null — идёт проход теней шейдерпака: глубина не этого взгляда
            Object view = viewport.invoke(system);
            Object pipe = pipeline.get(system);
            if (view == null || pipe == null) return false;
            int frame = frameId.getInt(view);
            // Voxy этот кадр не рисовал (пауза, меню, LOD выключены): глубина в его текстуре — прошлого кадра
            if (frame == taken) return false;
            last = frame;
            Object buffer = fb.get(pipe);
            Object tex = buffer == null ? null : depthTex.invoke(buffer);
            int id = tex == null ? 0 : textureId.getInt(tex);
            if (id <= 0) return false;
            Object p = properties.get(pipe);
            boolean zero2one = (boolean) zeroToOne.invoke(p), reverse = (boolean) reverseZ.invoke(p);
            out.texture = id;
            out.negativeOneToOne = !zero2one;
            out.empty = (float) clearDepth.invoke(p);
            out.world = (float) nearDepth.invoke(p);
            out.pinsWorldDepth = true;
            projectionCopy.set((Matrix4fc) projection.get(view)).invert(out.inverseProjection);
            if (!out.inverseProjection.isFinite()) return false;
            int how = (reverse ? 1 : 0) | (zero2one ? 2 : 0);
            if (how != logged) {
                logged = how;
                described = LodDepth.describe(!reverse, out.negativeOneToOne, out.empty, nearest(out, reverse),
                        String.format(java.util.Locale.ROOT, ", Minecraft pixels marked %.1f", out.world));
                Incoming.LOG.info("Voxy depth for the effects layer: {}", described);
            }
            out.source = "Voxy";
            out.how = described;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Voxy поменялся так, что поля есть, а значения другие: слой дальше без LOD Voxy, игра идёт
            broken = true;
            Incoming.LOG.warn("Voxy depth for the effects layer: reading Voxy failed ({}), Voxy terrain no longer hides distant smoke and blasts", e.toString());
            return false;
        }
    }

    /**
     * Конец кадра: кадр Voxy, который идёт сейчас, — уже прошлый, даже если слой в нём глубину не брал (кадр без
     * эффектов): следующий кадр берёт её, только если Voxy нарисовал новый.
     */
    @Override
    public void endFrame() {
        if (broken) return;
        try {
            Object system = renderSystem.invoke(null);
            Object view = system == null ? null : viewport.invoke(system);
            taken = view == null ? last : frameId.getInt(view);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            taken = last;
        }
    }

    /** Расстояние до ближней плоскости проекции Voxy, блоков: для строки лога. */
    private float nearest(LodDepth.View v, boolean reverse) {
        float d = reverse ? 1 : 0;
        v.inverseProjection.transform(point.set(0, 0, v.negativeOneToOne ? d * 2 - 1 : d, 1));
        return -point.z / point.w;
    }
}
