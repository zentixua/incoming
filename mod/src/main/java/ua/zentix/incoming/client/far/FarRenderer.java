package ua.zentix.incoming.client.far;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.incoming.client.fx.layer.FxLayer;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.layer.FxQuads;
import ua.zentix.incoming.client.fx.layer.SourceProbes;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.client.nuclear.NukeRenderer;
import ua.zentix.incoming.client.render.FarDraw;

import java.util.Arrays;

/**
 * Снаряды и взрывы вдали: дальше прорисовки частиц и сущностей нет, а под шейдерами Iris виден только этап после мира.
 * Кадр: что видно из {@link FarBlasts} и {@link FarFlightView}, собирается в {@link FarSprites} — квадратами в общий
 * проход слоя эффектов ({@link FxLayer}), вместе с ближними частицами; модели снарядов рисуются в свой атлас
 * ({@code FarModels}), их плитки — квадратами того же прохода.
 */
public final class FarRenderer {
    private static final FarSprites SPRITES = new FarSprites();
    /** Сколько клубов, точек, света, лент и моделей было в последнем кадре (для строки сценария). */
    private static final int[] LAST_FRAME = new int[6];

    private FarRenderer() {}

    /** Тик клиента (не на паузе). */
    public static void tick() {
        FarBlasts.tick();
        FarFlightView.tick();
    }

    /**
     * Дальнее в кадр слоя эффектов; огненные шары — ещё и светом частиц кадра ({@code lights}), яркие источники —
     * пробами видимости для своего блика и вуали ({@code probes}).
     */
    public static void collect(FarView view, FxQuads out, FxLights lights, SourceProbes probes) {
        SPRITES.begin(view, out, probes);
        // бюджет клубов трубок дыма — общий у следов снарядов и шлейфов пожаров
        FarFlightView.budget();
        // облака (взрывов, старта, пожаров) — запечёнными клубами, вблизи и вдали
        SmokePuffs.collect(view, SPRITES);
        if (!FarBlasts.isEmpty() || !FarFlightView.isEmpty()) {
            FarBlasts.collect(view, SPRITES, lights);
            FarFlightView.collect(view, SPRITES, lights);
        }
        // клубы облаков, кучки трубок и вуали кадра — одним бюджетом, после следов снарядов
        SmokePuffs.flush();
        // пламя очагов топлива вдали, где его языков-частиц уже нет
        ua.zentix.incoming.client.fx.FireSmoke.far(view, SPRITES);
        // струи факелов сущностей кадра: после мира, а не путём сущностей — иначе их перекрашивает шейдерпак
        ua.zentix.incoming.client.render.PlumeRenderer.collect(view, SPRITES);
        NukeRenderer.fireballs(view, SPRITES, lights);
        SPRITES.counts(LAST_FRAME);
    }

    /**
     * Модели кадра — в атлас; после {@link #collect}, до отрисовки квадратов. Текстура атласа (0 — атласа ещё нет).
     */
    public static int renderModels() {
        SPRITES.models.render();
        return SPRITES.models.texture();
    }

    /**
     * Первый кадр в мире (и после перезагрузки ресурсов): атлас моделей, их сетки в видеопамяти и по одной отрисовке
     * каждой — до первого снаряда вдали ({@code FarModels#warmup}).
     */
    public static void warmup() {
        SPRITES.models.warmup();
    }

    /** Что нужно на кадр всем дальним картинкам и слою эффектов. */
    public static FarView view(RenderLevelStageEvent e, Minecraft mc, ClientLevel level) {
        Camera camera = e.getCamera();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        // проекция: m11 = 1 / tan(fovY / 2) — угол пикселя по вертикали
        double pixel = 2 / (e.getProjectionMatrix().m11() * Math.max(1, mc.getWindow().getHeight()));
        double edge = Math.atan(0.5 * pixel * Math.hypot(mc.getWindow().getWidth(), mc.getWindow().getHeight()));
        float ambient = FarView.ambient(level, partial);
        double exposure = FarView.exposure(level, partial);
        Vec3 eye = FarDraw.eye(e);
        double range = Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial));
        return new FarView(eye, camera.getLookVector(), camera.getLeftVector(), camera.getUpVector(), partial,
                FarView.depthFar(), pixel, edge, ambient, exposure, FarView.sky(mc.gameRenderer.lightTexture(), level, partial, new org.joml.Vector3f()), range, mc.options.getEffectiveRenderDistance() * 16.0,
                level.effects().getCloudHeight(), level.getRainLevel(partial), Sight.Air.at(eye.y - WaterDecal.SEA_LEVEL, range));
    }

    /**
     * Что сейчас вдали — одной строкой (сценарии клиента пишут её в лог): взрывы и их звук, снаряды, спрайты последнего
     * нарисованного кадра и время кадра всего слоя эффектов. Пусто — вдали ничего нет.
     */
    public static String describe() {
        if (FarBlasts.isEmpty() && FarFlightView.isEmpty()) return "";
        return "blasts: " + FarBlasts.describe() + "; projectiles: " + FarFlightView.describe() + "; frame: puffs " + LAST_FRAME[0] + ", points "
                + LAST_FRAME[1] + ", lights " + LAST_FRAME[2] + ", ribbons " + LAST_FRAME[3] + ", models " + LAST_FRAME[4] + ", veils " + LAST_FRAME[5] + "; layer: "
                + FxLayer.describeTiming();
    }

    /** Факелы снарядов кадра светят на мир ({@link HeatPass}): воздух air, 1/блок. */
    public static void heat(HeatPass.Frame heat, double air) {
        FarFlightView.heat(heat, air);
    }

    /** Свет факелов снарядов у глаза в прошлом кадре ({@link HeatPass.Frame#eye}). */
    public static double flamesAtEye() {
        return FarFlightView.flamesAtEye();
    }

    /** Выход из мира или смена измерения. */
    public static void reset() {
        HeatPass.forget();
        FarBlasts.reset();
        FarFlightView.reset();
        ua.zentix.incoming.client.render.PlumeRenderer.reset();
        SmokePuffs.reset();
        Arrays.fill(LAST_FRAME, 0);
    }

    /**
     * Заливка кадра квадратами облаков и трубок (экранов, с перекрытием), её пол до и после слияния и вуали, доля кучки, уровень
     * слияния разных источников, гауссов вуали и кучек ({@link SmokePuffs#FILL_BUDGET}).
     */
    public static String describeSmoke() {
        return String.format(java.util.Locale.ROOT,
                "fill %.2f screens (budget %.0f, one club per gaussian %.2f, before merging %.2f), cluster share %.2f, merge level %d, veil gaussians %d, clusters %d",
                SmokePuffs.lastFill(), SmokePuffs.FILL_BUDGET, SmokePuffs.lastFloor(), SmokePuffs.lastRaw(), SmokePuffs.lastShare(),
                SmokePuffs.lastLevel(), SmokePuffs.lastVeils(), SmokePuffs.lastItems());
    }
}
