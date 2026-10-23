package ua.zentix.incoming.client.render;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Рамка цели — угловой размер цели в кадре (жалоба 23.10: при зуме Ok Zoomer метки «плывут» и велики относительно цели).
 * Ok Zoomer меняет поле зрения на выходе {@code GameRenderer.getFov}, из него строится матрица кадра — она же у меток.
 */
class ScreenProjectionTest {
    /** Матрица кадра: перспектива с полем зрения fov по вертикали (16:9), глаз в 0 смотрит вдоль −z. */
    private static Matrix4f frame(double fovDeg) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDeg), 16f / 9, 0.05f, 1000);
    }

    /**
     * Игрок (0,6 × 1,8 м) в 200 м: высота рамки — его угловая высота в долях экрана, 1,8/200 / (2·tg(fov/2)); при поле
     * зрения в k раз уже (зум ×k: tg(fov/2) меньше в k раз) рамка в k раз выше — тот же угловой размер, что у цели.
     */
    @Test
    void frameHasTheTargetsAngularSizeAtAnyZoom() {
        AABB player = new AABB(-0.3, -0.9, -200.3, 0.3, 0.9, -199.7);
        double tan70 = Math.tan(Math.toRadians(35));
        for (int k : new int[]{1, 4, 16, 64}) {
            double fov = 2 * Math.toDegrees(Math.atan(tan70 / k));
            float[] r = ScreenProjection.rect(frame(fov), Vec3.ZERO, player);
            assertNotNull(r);
            double tall = r[3] - r[1], expected = 1.8 / 199.7 / (2 * tan70 / k);
            assertEquals(expected, tall, expected * 0.01, "зум ×" + k);
            // середина рамки — середина цели: в середине кадра
            assertEquals(0.5, 0.5 * (r[1] + r[3]), 1e-4, "зум ×" + k);
        }
    }

    /** Угол коробки за спиной — рамки нет. */
    @Test
    void behindTheEyeNoFrame() {
        assertNull(ScreenProjection.rect(frame(70), Vec3.ZERO, new AABB(-1, -1, -1, 1, 1, 5)));
    }
}
