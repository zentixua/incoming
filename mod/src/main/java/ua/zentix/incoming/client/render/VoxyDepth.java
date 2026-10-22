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
 * 0.2.16 для 1.21.11). API у Voxy нет, поэтому только чтение его классов отражением, без миксинов и без записи:
 * <ul>
 *   <li>{@code IGetVoxyRenderSystem.getNullable()} — рендерер Voxy этого мира или null;</li>
 *   <li>его поле {@code pipeline} (конвейер: свой без шейдерпака, {@code IrisVoxyRenderPipeline} под шейдерпаком) →
 *       {@code fb} → {@code getDepthTex().id} — текстура глубины, в которую Voxy рисует LOD (и, под ними, глубину
 *       Minecraft, перенесённую в свою проекцию);</li>
 *   <li>{@code pipeline.properties} — диапазон глубины ({@code isZero2One}), её направление ({@code isReverseZ}) и
 *       значение очистки ({@code clearDepth});</li>
 *   <li>{@code getViewport()} → {@code projection} — проекция кадра, которой записана глубина, и {@code frameId} —
 *       рисовал ли Voxy с прошлого кадра.</li>
 * </ul>
 * Без шейдерпака Voxy переносит LOD и в глубину Minecraft, но прижатыми к её дальней плоскости
 * ({@code blit_texture_depth_cutout.frag}: {@code REDUCTION2(FAR…)}): дальний взрыв прятался бы за любой LOD-землёй
 * рядом с ним, поэтому {@link LodDepth.View#pinsWorldDepth}. Классы или поля Voxy другой версии не нашлись — источника
 * нет (строка в лог), игра идёт как без Voxy.
 */
final class VoxyDepth implements LodDepth.Source {
    private static final String CORE = "me.cortex.voxy.client.core.";

    private final Method renderSystem, viewport, depthTex, zeroToOne, reverseZ, clearDepth;
    private final Field pipeline, fb, properties, textureId, projection, frameId;
    /** {@code frameId} кадра Voxy, глубину которого слой уже взял; −1 — ещё ни одного. */
    private int taken = -1, last = -1;
    /** Как читалась глубина в прошлый раз, что попал в лог; −1 — ещё никак. */
    private int logged = -1;
    private String described = "";
    private boolean broken;
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Vector4f VIEW = new Vector4f();

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
        projection = view.getField("projection");
        frameId = view.getField("frameId");
    }

    /** При запуске клиента: стоит ли Voxy и нашлись ли все его классы и поля; null — нет. */
    @Nullable
    static LodDepth.Source init() {
        if (!ModList.get().isLoaded("voxy")) return null;
        // проба (VXT): тот же кадр с глубиной Voxy и без неё
        if (!Boolean.parseBoolean(System.getProperty("incoming.lodDepth.voxy", "true"))) {
            Incoming.LOG.info("Voxy depth for the effects layer: off (-Dincoming.lodDepth.voxy=false)");
            return null;
        }
        for (ClassLoader loader : new ClassLoader[]{VoxyDepth.class.getClassLoader(), Thread.currentThread().getContextClassLoader()}) {
            try {
                VoxyDepth d = new VoxyDepth(loader);
                Incoming.LOG.info("Voxy depth for the effects layer: classes found, reading its depth texture (read-only)");
                return d;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                Incoming.LOG.debug("Voxy depth: not through {}", loader, e);
            }
        }
        Incoming.LOG.warn("Voxy: its classes differ from Voxy 0.2.16 the mod reads — Voxy terrain does not hide distant smoke and blasts");
        return null;
    }

    @Override
    public boolean current(LodDepth.View out) {
        if (broken) return false;
        try {
            Object system = renderSystem.invoke(null);
            if (system == null) return false;
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
            out.pinsWorldDepth = true;
            PROJECTION.set((Matrix4fc) projection.get(view)).invert(out.inverseProjection);
            if (!out.inverseProjection.isFinite()) return false;
            int how = (reverse ? 1 : 0) | (zero2one ? 2 : 0);
            if (how != logged) {
                logged = how;
                described = LodDepth.describe("Voxy", !reverse, out.negativeOneToOne, out.empty, nearest(out, reverse), "");
                Incoming.LOG.info("Voxy depth for the effects layer: {}", described);
            }
            out.how = described;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Voxy поменялся так, что поля есть, а значения другие: слой дальше без LOD Voxy, игра идёт
            broken = true;
            Incoming.LOG.warn("Voxy depth for the effects layer: reading Voxy failed, Voxy terrain no longer hides distant smoke and blasts", e);
            return false;
        }
    }

    @Override
    public void endFrame() {
        taken = last;
    }

    /** Расстояние до ближней плоскости проекции Voxy, блоков: для строки лога. */
    private static float nearest(LodDepth.View v, boolean reverse) {
        float d = reverse ? 1 : 0;
        v.inverseProjection.transform(VIEW.set(0, 0, v.negativeOneToOne ? d * 2 - 1 : d, 1));
        return -VIEW.z / VIEW.w;
    }
}
