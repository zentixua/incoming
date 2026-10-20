package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.client.fx.layer.SmokePass;
import ua.zentix.incoming.client.nuclear.NukePall;

/**
 * Дым обычных взрывов в кадре слоя эффектов: сетка дыма ({@link SmokeGrid}) — в кадр прохода пелены ({@link PallPass}),
 * с ядеркой или без неё (тогда кадр — только свет неба и воздух, {@link NukePall#lightFrame}). Шум клубов идёт с ветром
 * облаков; огненные шары облаков светят на дым и на мир (свободные столбцы шаров ядерки, {@link FarBlasts#fireballs}).
 */
public final class SmokeFrame {
    /** Период двухфазного шума клубов, с ({@code fx_pall}: {@code smokeClumps}): за него клубы уходят на скорость × период. */
    static final float PERIOD = 2;
    /** Высота, на которой берётся ветер для шума клубов, блоков (облака — сотни блоков над землёй). */
    static final double NOISE_WIND_HEIGHT = 100;

    private SmokeFrame() {}

    /**
     * Кадр прохода пелены с дымом: {@code pall} — кадр ядерки ({@link NukePall#frame}, null — ядерки нет). Объём
     * невозможен (шейдеры не собрались) — облака взрывов целиком рисуют квадраты ({@link SmokeGrid#on}). null — ни пелены,
     * ни дыма.
     */
    @Nullable
    public static PallPass.Frame frame(ClientLevel level, Vec3 eye, float partial, @Nullable PallPass.Frame pall) {
        boolean on = PallPass.available() && SmokePass.available();
        SmokeGrid.enable(on);
        SmokeGrid.Frame grid = SmokeGrid.frame(eye.x, eye.y, eye.z, SmokeGrid.seconds(partial));
        if (!on || grid.empty()) {
            if (pall != null) pall.smoke = null;
            return pall;
        }
        PallPass.Frame f = pall != null ? pall : NukePall.lightFrame(level, eye, partial);
        f.smoke = grid;
        float tile = 32;
        double seconds = SmokeGrid.seconds(partial);
        f.smokeNoise.set((float) Math.floorMod((long) Math.floor(eye.x * 1024), (long) tile * 1024) / 1024f,
                (float) Math.floorMod((long) Math.floor(eye.y * 1024), (long) tile * 1024) / 1024f,
                (float) Math.floorMod((long) Math.floor(eye.z * 1024), (long) tile * 1024) / 1024f, (float) (seconds % (64 * PERIOD)));
        double wind = Math.pow(NOISE_WIND_HEIGHT / 10, CloudModel.Air.STANDARD.shear());
        f.smokeWind.set((float) (FarBlasts.WIND_X * wind), 0, (float) (FarBlasts.WIND_Z * wind), PERIOD);
        f.smokeBalls.zero();
        f.smokeBallLight.zero();
        FarBlasts.fireballs(eye, partial, f.exposure, 0, f.smokeBalls, f.smokeBallLight);
        double range = Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial));
        if (FarBlasts.fireballs(eye, partial, f.exposure, Sight.KOSCHMIEDER / range, f.balls, f.ballLight)) f.ballsLit = true;
        return f;
    }
}
