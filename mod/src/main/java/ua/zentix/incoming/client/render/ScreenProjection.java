package ua.zentix.incoming.client.render;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Матрицы кадра (снимаются после неба): проекция точки мира на экран — метки HUD и камеры снаряда, послеобраз
 * ядерной вспышки.
 */
public final class ScreenProjection {
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static float projScale = 1, projScaleX = 1;
    private static boolean valid;

    private ScreenProjection() {}

    public static void capture(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        VIEW_PROJ.set(e.getProjectionMatrix()).mul(e.getModelViewMatrix());
        projScale = e.getProjectionMatrix().m11();
        projScaleX = e.getProjectionMatrix().m00();
        camera = e.getCamera().getPosition();
        valid = true;
    }

    /**
     * Точка на экране в долях (0..1 слева направо и сверху вниз) и угловой масштаб, или null, если точка за спиной.
     *
     * @return {x, y, w}: w — «глубина» для пересчёта размера (размер на экране ≈ радиус / w)
     */
    @Nullable
    public static float[] project(Vec3 world) {
        if (!valid) return null;
        Vector4f v = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1);
        VIEW_PROJ.transform(v);
        if (v.w <= 0.05f) return null;
        return new float[]{(v.x / v.w + 1) * 0.5f, (1 - v.y / v.w) * 0.5f, v.w};
    }

    /**
     * Прямоугольник на экране (доли 0..1, {x0, y0, x1, y1}) вокруг коробки мира {@code box} в матрицах кадра — её угловой
     * размер в этом кадре (с зумом: матрица кадра строится из поля зрения после модов зума); null — угол коробки за
     * спиной.
     */
    @Nullable
    public static float[] rect(AABB box) {
        return valid ? rect(VIEW_PROJ, camera, box) : null;
    }

    /** То же для матрицы вида и проекции {@code viewProj} и глаза {@code eye}. */
    @Nullable
    public static float[] rect(Matrix4f viewProj, Vec3 eye, AABB box) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        Vector4f v = new Vector4f();
        for (int i = 0; i < 8; i++) {
            v.set((float) (((i & 1) == 0 ? box.minX : box.maxX) - eye.x), (float) (((i & 2) == 0 ? box.minY : box.maxY) - eye.y),
                    (float) (((i & 4) == 0 ? box.minZ : box.maxZ) - eye.z), 1);
            viewProj.transform(v);
            if (v.w <= 0.05f) return null;
            float x = (v.x / v.w + 1) * 0.5f, y = (1 - v.y / v.w) * 0.5f;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        return new float[]{x0, y0, x1, y1};
    }

    /** Вертикальный масштаб проекции: доля высоты экрана на единицу (радиус / глубина). */
    public static float verticalScale() {
        return projScale * 0.5f;
    }

    /** Горизонтальный масштаб проекции: доля ширины экрана на единицу (сдвиг вбок / глубина). */
    public static float horizontalScale() {
        return projScaleX * 0.5f;
    }
}
