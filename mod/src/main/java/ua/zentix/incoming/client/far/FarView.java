package ua.zentix.incoming.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.incoming.entity.StrikeProjectile;

/**
 * Что нужно на кадр всем дальним картинкам: где камера и куда смотрит, докуда рисует мир, угол пикселя, свет неба,
 * дальность видимости по погоде. Цвета дымки нет: она — в непрозрачности ({@link Sight}).
 *
 * @param camera  глаз
 * @param forward куда смотрит
 * @param left    влево по экрану
 * @param up      вверх по экрану
 * @param partial доля тика
 * @param far     дальше — переносить ближе с тем же угловым размером ({@link ua.zentix.incoming.client.render.FarDraw#fold})
 * @param pixel   угол одного пикселя, рад
 * @param edge    угол от середины экрана до угла, рад: дальше — не в кадре
 * @param ambient свет неба 0.12..1 ({@link #ambient(ClientLevel, float)}): ночью дым и корпус темнее, свет вспышек ярче
 * @param range   дальность видимости, блоков ({@link Sight#range})
 * @param world   докуда мир рисует сам (дальность прорисовки, блоков): ближе дальняя картинка уступает частицам
 * @param clouds  высота облаков измерения, блоков ({@code NaN} — облаков нет: Незер, Энд)
 * @param rain    дождь 0..1: облака сплошные
 * @param air     воздух от глаза ({@link Sight.Air}: дымка редеет с высотой) — по дальности видимости и высоте глаза
 */
public record FarView(Vec3 camera, Vector3f forward, Vector3f left, Vector3f up, float partial, double far, double pixel, double edge,
                      float ambient, double exposure, Vector3f sky, double range, double world, double clouds, float rain, Sight.Air air) {
    /**
     * Свет неба в единицах белой стены в полдень (днём 1, ночью ~8·10⁻⁴): к нему глаз привыкает без огня
     * ({@link Sight#adapted}: ambient^{@value Sight#ADAPT}).
     */
    public double skyLuminance() {
        return Math.pow(ambient, Sight.ADAPT);
    }

    /** Тот же вид с привыканием глаза кадра exposure ({@link Sight#exposure}: с огнём у глаза этого кадра). */
    public FarView exposed(double exposure) {
        return new FarView(camera, forward, left, up, partial, far, pixel, edge, ambient, exposure, sky, range, world, clouds, rain, air);
    }

    /**
     * Свет неба на экране у вещества в воздухе под открытым небом (rgb, {@link #sky}). Яркость — та же, какой карта
     * освещения Minecraft светит блоки и частицы под открытым небом (блок 0, небо 15): клубы дыма слоя берут её, а не
     * {@link #ambient}, — с ним белый дым ночью выходил темнее освещённого луной камня рядом, хотя отражает больше.
     * Оттенок — как у облаков Minecraft ({@link ClientLevel#getCloudColor}: белое вещество в небе), а не как у карты:
     * карта синеет линейно с потемнением неба ({@code (s, s, 1)} к белому на 35 %), и на закате (время 13000, солнце на
     * 3,5° под горизонтом, небо ещё розовое) синий у неё в 1,47 раза больше красного — почти как ночью (1,58); облака
     * синеют, только когда свет ушёл (на закате 1,07, ночью 1,5). Дым и облака — одно и то же вещество в одном свете:
     * у шейдерпака на кадре заката облака серые (синий к красному 1,0–1,1), а след по карте был тёмно-синим.
     */
    public static Vector3f sky(net.minecraft.client.renderer.LightTexture light, ClientLevel level, float partial, Vector3f into) {
        Vec3 cloud = level.getCloudColor(partial);
        return open(lightmap(light, into), cloud.x, cloud.y, cloud.z, into);
    }

    /** Карта освещения у открытого неба (блок 0, небо 15), rgb на экране. */
    public static Vector3f lightmap(net.minecraft.client.renderer.LightTexture light, Vector3f into) {
        int c = light.lightPixels.getPixelRGBA(0, 15);
        return into.set((c & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c >> 16 & 0xFF) / 255f);
    }

    /**
     * Свет открытого неба на веществе в воздухе: яркость карты освещения {@code map} с оттенком облаков (r, g, b) — в into
     * (можно тот же вектор, что map). Облаков без света (яркость ноль) — карта как есть.
     */
    static Vector3f open(Vector3f map, double r, double g, double b, Vector3f into) {
        double luma = luma(map.x(), map.y(), map.z()), cloud = luma(r, g, b);
        if (cloud <= 1e-6) return into.set(map);
        double k = luma / cloud;
        return into.set((float) Math.min(1, r * k), (float) Math.min(1, g * k), (float) Math.min(1, b * k));
    }

    /**
     * Во сколько раз по каналам свет вещества в воздухе {@code sky} больше карты освещения у открытого неба {@code map}
     * ({@code FxSkyFix}: частицы и ленты слоя берут свет мира из карты, у них — его доля неба).
     */
    public static Vector3f fix(Vector3f sky, Vector3f map, Vector3f into) {
        return into.set(ratio(sky.x(), map.x()), ratio(sky.y(), map.y()), ratio(sky.z(), map.z()));
    }

    private static float ratio(float a, float b) {
        return b <= 1e-4f ? 1 : a / b;
    }

    private static double luma(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** Яркость {@link #sky} (на экране): ею нормирован свет огненных шаров на клубах ({@code fx.vsh}). */
    public float skyLight() {
        return 0.2126f * sky.x() + 0.7152f * sky.y() + 0.0722f * sky.z();
    }

    /**
     * Привыкание глаза ({@link Sight#exposure}) по свету неба и свету огня у глаза в прошлом кадре слоя эффектов
     * ({@code HeatPass.atEye}) — у того, что рисуется до слоя (струя факела у сущности), и как начало кадра слоя (он сам
     * берёт огонь этого кадра, {@link #exposed}).
     */
    public static double exposure(ClientLevel level, float partial) {
        return Sight.exposure(ambient(level, partial), ua.zentix.incoming.client.fx.layer.HeatPass.atEye());
    }

    /** Свет неба мира 0,12..1: днём 1, ночью 0,17, ночью в грозу 0,12. */
    public static float ambient(ClientLevel level, float partial) {
        return Mth.clamp(level.getSkyDarken(partial) * 1.1f - 0.05f, 0.12f, 1f);
    }

    /**
     * Свет, к которому привык глаз, 0,12..1: под открытым небом — свет неба ({@link #ambient}), в пещере, доме и полости
     * бункера — темнота, по свету неба у глаза; в измерении без неба — темнота.
     */
    public static float adaptation(ClientLevel level, Vec3 eye, float partial) {
        float sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye)) / 15f;
        return Mth.lerp(sky, 0.12f, ambient(level, partial));
    }

    /** Доля света точки в d блоках от глаза и на dy блоков выше него, дошедшая через воздух ({@link Sight.Air}). */
    public double air(double d, double dy) {
        return air.transmittance(d, dy);
    }

    /**
     * Доля дальней картинки взрыва (огненного шара и пожара; столб — на любой дальности) на дальности d: ближе 0,8
     * прорисовки её нет (там частицы), к краю прорисовки, где туман съедает частицы, — вся.
     */
    public double farShare(double d) {
        return farShare(d, world);
    }

    /** Дальняя плоскость кадра Minecraft с запасом, блоков: дальше дальнее переносится ближе ({@link #far}). */
    public static double depthFar() {
        return Minecraft.getInstance().gameRenderer.getDepthFar() * 0.97;
    }

    /**
     * Докуда модель сущности снаряда рисует она сама, блоков: до {@link StrikeProjectile#DRAW_RANGE} и до дальней
     * плоскости кадра {@code far}; дальше корпус рисует дальняя картинка ({@code FarFlightView}). Сервер отдаёт сущность
     * по горизонтали, поэтому высоко над игроком она у клиента есть, а модель её за дальней плоскостью (4 прорисовки)
     * отсекается: B-2 на настоящем эшелоне 12 км (шкала полёта 1) над головой иначе не виден вовсе.
     */
    public static double entityReach(double far) {
        return Math.min(StrikeProjectile.DRAW_RANGE, far);
    }

    /** {@link #farShare(double)} при дальности прорисовки world блоков. */
    public static double farShare(double d, double world) {
        double a = 0.8 * world, t = (d - a) / Math.max(1, world - a);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
