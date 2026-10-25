package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.Flash;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.render.ScreenProjection;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.EyeInjury;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;

import java.util.List;

/**
 * Вспышка ядерного взрыва на экране (DESIGN-nuke §6): кадр, выставленный на то, что камера видела до вспышки, пересвечен
 * её светом и догоняет его ({@link NukeExposure#overexposure}, подача): до второго максимума и в нём — белый, потом
 * ярче и ярче проступают тёмные силуэты, у 15 кт в 1,4 км через полсекунды кадр пересвечен в ~3 раза, через секунду —
 * как был ({@link #overexposed}: усиление кадра и рассеянный в объективе свет). Свет и время импульса — физика;
 * прежде белый экран стоял весь импульс (~10·t_max: у 15 кт 1,2 с) и гас ещё 2–4 с. Затем — тёмно-оранжевое
 * «пятно» послеобраза там, где шар был на сетчатке, если игрок на него смотрел (ходит со взглядом). Весь мир в это время освещён, как молнией: ночь
 * на миг становится днём. Сила — по световому импульсу у игрока: прямая видимость и шар в поле зрения — полная, иначе
 * треть (засветка неба).
 *
 * <p>Глаза (G&amp;D §12.83–12.88): засветка всего поля — вуаль по {@link EyeInjury#dazzleSeconds} от импульса у глаза
 * (видел шар — весь, не видел, но под открытым небом — рассеянный небом, под крышей — нет) и того, привык ли глаз к
 * темноте: сперва светлый послеобраз, через секунды — серая днём, чёрная ночью, гаснет к концу срока; чем короче
 * засветка, тем вуаль слабее. Ожоги сетчатки — постоянные тёмные пятна там, где на сетчатке был шар (с сервера,
 * {@link FlashEyes}); ходят со взглядом. Творческий режим и наблюдатель — не глаза, а камера (ранений у них нет): ни
 * засветки, ни пятен, пока игрок в этом режиме (пятна остаются за ним), белый экран и послеобраз у них те же; в повторе
 * Flashback зритель — наблюдатель. Спящий глаза закрыл. Смерть засветку снимает.
 */
public final class NukeFlash {
    @Nullable
    private static ClientNuclear.Active current;
    /** Сила вспышки 0..1 для этого игрока. */
    private static float strength;
    /**
     * Во сколько раз свет вспышки у камеры во втором максимуме ярче того, на что выставлен кадр (≥ 1): по нему кадр
     * догоняет вспышку ({@link NukeExposure#overexposure}).
     */
    private static double peak = 1;
    /** Послеобраз: сколько тиков ещё и сколько было всего. */
    private static int afterLeft, afterTotal;
    /**
     * Где послеобраз на сетчатке: место на экране в миг вспышки (доли ширины и высоты) и полуразмер пятна (доля высоты).
     * Пятно — ожог сетчатки, поэтому оно ходит вместе со взглядом, а не стоит в мире.
     */
    private static float afterX, afterY, afterSize;
    private static long ticks, endTick;
    /**
     * Пересвет кадра, при котором рассеянный в объективе свет белит весь кадр (белое — доля пересвета / VEIL_X: в
     * объективе рассеивается порядка процента света кадра, ⚠ оценка), и наибольшее усиление кадра на экране (больше — кадр
     * и так белый). Подача.
     */
    private static final double VEIL_X = 100, GAIN_MAX = 16;
    /** Пересвет, ниже которого кадра не трогаем: усиление меньше шага экрана. */
    private static final double SETTLED = 1.004;
    /**
     * Засветка неба при полном пропускании воздуха, доля белого экрана, и засветка, с которой мир освещён вспышкой
     * ({@link #lightUpWorld}). <b>Оценки</b>, не выведены: обе — доли экрана, а не свет; засветка — без мощности и без
     * 1/d², предел ей — только воздух (Glasstone, рис. 7.104; дальше половины видимости — продолжение подбора). Поэтому
     * мир вспыхивает до ~5,3 видимости при любой мощности, а свет шара во втором максимуме там ({@code MushroomModel.light}
     * сквозь тот же воздух) — у 1 кт 0,5 % полуденного, у 15 кт 4,5 %, у 1 Мт 90 %: ночью (глаз привык к темноте,
     * {@code Sight.adapted}) это вспышка, днём у малых мощностей вдали засветка сильнее, чем физика.
     */
    private static final double SKY_GLARE = 0.6, WORLD_FLASH = 0.05;
    /** Доля импульса, которую рассеивает небо к тому, кто шар не видит (оценка). */
    private static final double SCATTERED = 0.15;
    /** Вуаль засветки в начале (полю не разглядеть почти ничего) и время, за которое светлый послеобраз темнеет, с. */
    private static final float VEIL = 0.9f, AFTERGLOW_S = 2;
    /** Вуаль после послеобраза днём — серая (засвеченные колбочки), ночью — чёрная. */
    private static final float DAY_GREY = 0.45f;
    /** Засветка поля: начало (свои тики клиента, конец импульса), срок, с, сила 0..1, насколько глаз был ночным. */
    private static double veilStart, veilSeconds;
    private static float veilStrength, veilDark;
    /** Ожоги сетчатки — пятна в поле зрения ({@link RetinaScars.Scar}). */
    private static List<RetinaScars.Scar> scars = List.of();

    private NukeFlash() {}

    static void detonation(ClientNuclear.Active a) {
        Detonation d = a.d;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Vec3 eye = p.getEyePosition();
        boolean sees = NuclearWarhead.sees(mc.level, d, p);
        double q = d.fluence(eye);
        double distM = d.metres(eye.distanceTo(d.burst()));
        // кроме настоящего светового импульса — ослеплённое небо (SKY_GLARE): свет шара, рассеянный воздухом, — пропускание
        // прямого и рассеянного света вместе (ThermalModel.transmittance, как у светового импульса), а не пучка: блик шара
        // (NukeRenderer.fireballs) — пучок, засвеченное небо — рассеянный свет
        double sky = Math.min(1, q / 2 + SKY_GLARE * ThermalModel.transmittance(distM, d.visibility()));
        lightUpWorld(mc.level, d, sky);
        double s = sky;
        Vec3 look = p.getViewVector(1);
        double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(d.burst().subtract(eye).normalize()), -1, 1)));
        boolean inView = sees && angle < 60;
        s *= inView ? 1 : sees ? 0.45 : 0.3;
        s *= IncomingConfig.CLIENT.flash.get();
        if (eyes(p) && !p.isSleeping()) dazzle(mc.level, d, eye, sees ? q : Detonation.underOpenSky(mc.level, eye) ? q * SCATTERED : 0);
        // «без вспышек молний»: без засветки мира, белый экран — неполный
        if (Flash.calm()) s = Math.min(s, Flash.CALM);
        if (s <= strength && current != null) return;
        current = a;
        strength = (float) s;
        peak = 1 + flashOnCamera(mc.level, d, distM) * (inView ? 1 : sees ? 0.45 : 0.3);
        endTick = ticks + (long) Math.ceil((FireballModel.secondMaximumSeconds(d.yieldKt()) * d.scale() + NukeExposure.settled(peak, SETTLED)) * 20);
        // пятно больше видимого шара: глаз «размазывает» засветку
        float[] spot = inView && q >= 0.05 ? retina(mc, p, d.burst(), Math.max(d.fireballRadius(), 30) * 2.2) : null;
        if (spot != null) {
            // 5–30 с по световому импульсу (кал/см²)
            afterTotal = afterLeft = (int) (20 * Mth.clamp(5 + q * 2.5, 5, 30));
            afterX = spot[0];
            afterY = spot[1];
            afterSize = spot[2];
        }
    }

    /**
     * Свет шара у камеры во втором максимуме — против того, на что выставлен кадр (свет неба: {@link Sight#adapted}):
     * ламбертов шар наибольшего радиуса, чёрное тело {@link FireballModel#PEAK_K}, L·R²/max(d², R²) сквозь воздух
     * (прямой и рассеянный вместе, {@link ThermalModel#transmittance}, как у засветки неба). У 15 кт в 1,4 км днём ~2 000,
     * в 5 км ~200; ночью — в ~1 200 раз больше.
     */
    static double flashOnCamera(ClientLevel level, Detonation d, double distM) {
        double r = FireballModel.maxRadius(d.yieldKt(), d.surface());
        double light = BlackBody.luminance(FireballModel.PEAK_K) * r * r / Math.max(distM * distM, r * r) * ThermalModel.transmittance(distM, d.visibility());
        return Sight.adapted(light, FarView.ambient(level, 0));
    }

    /** Засветка поля импульсом {@code q} кал/см² у глаза; уже идущая, которая кончится позже, остаётся. */
    private static void dazzle(ClientLevel level, Detonation d, Vec3 eye, double q) {
        double dark = FlashEyes.darkness(level, eye);
        double seconds = EyeInjury.dazzleSeconds(q, d.yieldKt(), dark);
        float k = IncomingConfig.CLIENT.flash.get().floatValue();
        if (Flash.calm()) k = Math.min(k, Flash.CALM);
        if (seconds <= 0 || k <= 0) return;
        // с максимума импульса: прежде — с его конца, когда кончался белый экран; кадр теперь догоняет вспышку раньше
        // (overexposed), и глаз между ними видел бы ясно
        double start = ticks + FireballModel.secondMaximumSeconds(d.yieldKt()) * 20 * d.scale();
        if (start + seconds * 20 <= veilStart + veilSeconds * 20) return;
        veilStart = start;
        veilSeconds = seconds;
        veilStrength = k * (float) Math.min(1, seconds / EyeInjury.EDGE_S);
        veilDark = (float) dark;
    }

    /**
     * Где на экране глаз самого игрока видит шар радиуса {@code radius} в точке {@code at}: по его глазу и взгляду, а не по
     * камере кадра (камера снаряда, вид от третьего лица смотрят не его глазами). Масштаб — проекция кадра, если кадр —
     * его глаза (поле зрения с подзорной трубой, бегом), иначе — его поле зрения из настроек.
     *
     * @return {x, y — доли ширины и высоты экрана; полуразмер — доля высоты} или null, если шар у него за спиной
     */
    @Nullable
    private static float[] retina(Minecraft mc, LocalPlayer p, Vec3 at, double radius) {
        Vec3 f = p.getViewVector(1), to = at.subtract(p.getEyePosition());
        double depth = to.dot(f);
        if (depth <= 1e-3) return null;
        double yaw = Math.toRadians(p.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)), up = right.cross(f);
        double sx, sy;
        if (mc.getCameraEntity() == p && mc.options.getCameraType().isFirstPerson()) {
            sx = ScreenProjection.horizontalScale();
            sy = ScreenProjection.verticalScale();
        } else {
            sy = 0.5 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
            sx = sy * mc.getWindow().getHeight() / Math.max(1, mc.getWindow().getWidth());
        }
        return new float[]{(float) (0.5 + to.dot(right) / depth * sx), (float) (0.5 - to.dot(up) / depth * sy), (float) (radius / depth * sy)};
    }

    /**
     * Мир освещён вспышкой, как молнией: ванильная вспышка неба (свет неба у всех блоков — полный, как днём) на
     * 1.5–3 с × ∛(Y/15), у всех, кому видно засвеченное небо (засветка не меньше {@link #WORLD_FLASH}: у воздушного
     * подрыва на 240·∛Y м в ясную погоду 1 и 15 кт — до ~107 км, 1 Мт — до ~119 км; в дождь — 27, 28 и 44 км; в грозу — 6,
     * 7 и 17 км). Выключенная вспышка в настройках или ванильное «без вспышек молний» ({@link Flash#calm}) — без неё.
     */
    private static void lightUpWorld(ClientLevel level, Detonation d, double sky) {
        if (sky < WORLD_FLASH || IncomingConfig.CLIENT.flash.get() <= 0 || Flash.calm()) return;
        int flash = (int) (20 * (1.5 + 1.5 * sky) * Math.cbrt(d.yieldKt() / 15));
        level.setSkyFlashTime(Math.max(level.getSkyFlashTime(), flash));
    }

    static void tick() {
        ticks++;
        if (afterLeft > 0) afterLeft--;
        if (current != null && ticks > endTick && afterLeft <= 0) {
            current = null;
            strength = 0;
        }
    }

    /** Глаза, а не камера: не творческий режим и не наблюдатель (и не зритель повтора). */
    private static boolean eyes(@Nullable LocalPlayer p) {
        return p != null && !p.isCreative() && !p.isSpectator();
    }

    /** Возрождение после смерти: засветку и послеобраз умерших глаз новое тело не несёт (пятна сетчатки шлёт сервер). */
    public static void respawned() {
        afterLeft = 0;
        veilSeconds = 0;
    }

    static void reset() {
        current = null;
        strength = 0;
        afterLeft = 0;
        veilSeconds = 0;
    }

    /** Ожоги сетчатки с сервера — все пятна целиком (состояние, не событие). */
    public static void scars(RetinaScars s) {
        scars = s.scars();
    }

    /**
     * Насколько экран сейчас белый (0..1): для неба и тумана тоже — доля, на которую пересвет {@link #overexposure}
     * поднимает середину тонов к белому, 1 − 1/X.
     */
    public static float whiteness(float partialTick) {
        ClientNuclear.Active a = current;
        if (a == null || strength <= 0) return 0;
        return (float) (strength * (1 - 1 / overexposure(a, partialTick)));
    }

    /**
     * Пересвет кадра вспышкой подрыва {@code a} (≥ 1; 1 — кадр догнал её или вспышка не та, что на экране): по нему и блик
     * шара ({@code NukeRenderer.fireballs}).
     */
    static double overexposure(ClientNuclear.Active a, float partialTick) {
        if (a != current || strength <= 0) return 1;
        double after = (a.seconds(partialTick) - FireballModel.secondMaximumSeconds(a.d.yieldKt())) * a.d.scale();
        return NukeExposure.overexposure(peak, after);
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int w = g.guiWidth(), h = g.guiHeight();
        NukeDust.render(g);
        overexposed(g, partial, w, h);
        if (eyes(Minecraft.getInstance().player)) {
            if (!scars.isEmpty()) scars(g, w, h);
            veil(g, partial, w, h);
        }
        if (afterLeft > 0 && current != null) afterimage(g, partial, w, h);
    }

    /**
     * Кадр, пересвеченный вспышкой ({@link #overexposure}, подача): усиление кадра — пересвет X в значениях экрана
     * (X^(1/2,2), не больше {@link #GAIN_MAX}: светлое уходит в белое, тёмное остаётся тёмным — силуэты против вспышки, как
     * на плёнке), поверх — рассеянный в объективе свет, белый в доле X / {@link #VEIL_X} (сначала бело-голубой, потом
     * тёплый). Белая заливка во весь экран одним цветом прятала кадр целиком, усиление — только то, что в нём светлое.
     * Усиление — смешиванием «цвет кадра × (1 + c)» за проходы по ×2.
     */
    private static void overexposed(GuiGraphics g, float partial, int w, int h) {
        ClientNuclear.Active a = current;
        if (a == null || strength <= 0) return;
        double x = overexposure(a, partial);
        if (x < SETTLED) return;
        double gain = 1 + strength * (Math.min(Math.pow(x, 1 / 2.2), GAIN_MAX) - 1);
        if (Flash.calm()) gain = Math.min(gain, 1 + Flash.CALM);
        g.flush();
        if (gain > 1.002) {
            int passes = Math.max(1, (int) Math.ceil(Math.log(gain) / Math.log(2) - 1e-9));
            float c = (float) (Math.pow(gain, 1.0 / passes) - 1);
            Matrix4f m = g.pose().last().pose();
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
            for (int i = 0; i < passes; i++) {
                BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                b.addVertex(m, 0, 0, 0).setColor(c, c, c, 1f);
                b.addVertex(m, 0, h, 0).setColor(c, c, c, 1f);
                b.addVertex(m, w, h, 0).setColor(c, c, c, 1f);
                b.addVertex(m, w, 0, 0).setColor(c, c, c, 1f);
                BufferUploader.drawWithShader(b.buildOrThrow());
            }
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
        float white = (float) (strength * Math.min(1, x / VEIL_X));
        if (white > 0.004f) {
            int alpha = Math.min(255, (int) (white * 255));
            // сначала бело-голубой, потом тёплый
            int rgb = a.seconds(partial) < FireballModel.firstMinimumSeconds(a.d.yieldKt()) * 4 ? 0xEEF4FF : 0xFFF8E8;
            g.fill(0, 0, w, h, (alpha << 24) | rgb);
        }
    }

    /**
     * Послеобраз — оранжевое пятно там, где шар был на сетчатке в миг вспышки, медленно гаснет; двигается вместе со
     * взглядом, как настоящий: повернул голову — пятно в том же месте экрана, а шар уходит. Место и размер на экране —
     * из мига вспышки: смена поля зрения (подзорная труба, бег) его не двигает, как и сетчатку.
     * Складывается со светом: обычным смешиванием тёмное ядро пятна ложилось бы поверх ещё светящегося шара тёмной
     * точкой (шар всплывает, а пятно остаётся там, где была вспышка).
     */
    private static void afterimage(GuiGraphics g, float partial, int w, int h) {
        float px = Mth.clamp(afterSize * h, 14, 400);
        float life = (afterLeft - partial) / afterTotal;
        float a = Mth.clamp(life * 1.3f, 0, 0.6f) * Math.min(1, strength * 1.5f);
        if (a <= 0.01f) return;
        ShaderInstance shader = NukeRenderer.flare;
        if (shader == null) return;
        float x = afterX * w, y = afterY * h;
        RenderSystem.setShader(() -> shader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        // пятно до 800 пикселей — свечением по формуле (из картинки — квадратами по её пикселю)
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Matrix4f m = g.pose().last().pose();
        spot(b, m, x, y, px, 0.55f, 0.24f, 0.08f, a);
        spot(b, m, x, y, px / 3, 0.6f, 0.4f, 0.2f, a * 0.5f);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /**
     * Вуаль засветки: с максимума импульса (до него — белый кадр {@link #overexposed}) гаснет к концу срока как
     * (1 − t/T)²; цвет — светлый послеобраз, который за секунды темнеет (днём к серому, ночью к чёрному: привыкший к
     * темноте глаз не видит тусклого).
     */
    private static void veil(GuiGraphics g, float partial, int w, int h) {
        if (veilSeconds <= 0) return;
        double t = (ticks + partial - veilStart) / 20;
        if (t < 0 || t >= veilSeconds) return;
        double life = 1 - t / veilSeconds;
        float a = (float) (VEIL * life * life * veilStrength);
        if (a <= 0.004f) return;
        float base = DAY_GREY * (1 - veilDark), glow = (float) Math.exp(-t / AFTERGLOW_S);
        float c = base + (1 - base) * glow;
        int r = (int) (255 * c), gr = (int) (255 * c * 0.96f), b = (int) (255 * c * 0.88f);
        g.fill(0, 0, w, h, (Math.min(255, (int) (a * 255)) << 24) | (r << 16) | (gr << 8) | b);
    }

    /**
     * Ожоги сетчатки — тёмные пятна с мягким краем там, где на сетчатке был шар, в поле зрения игрока из настроек (как
     * сетчатка: подзорная труба и бег их не двигают). У центра взгляда пятно заметно, сбоку мозг его почти заполняет
     * (§12.85: вне центра ожог обычно не замечают) — бледнее.
     */
    private static void scars(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        double sy = 0.5 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2), sx = sy * h / Math.max(1, w);
        Matrix4f m = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (RetinaScars.Scar s : scars) {
            float x = (float) ((0.5 + s.x() * sx) * w), y = (float) ((0.5 - s.y() * sy) * h), r = (float) Math.max(2, s.r() * sy * h);
            // сбоку на 20° — втрое бледнее, чем у центра
            double off = Math.toDegrees(Math.atan(Math.hypot(s.x(), s.y())));
            float a = (float) (0.85 / (1 + Math.pow(off / 14, 2)));
            any |= disc(b, m, x, y, r, a, w, h);
        }
        if (any) BufferUploader.drawWithShader(b.buildOrThrow());
        else b.build();
        RenderSystem.disableBlend();
    }

    /** Тёмный круг радиуса r с краем ещё в полрадиуса до прозрачного; за экраном — не рисуется. */
    private static boolean disc(BufferBuilder b, Matrix4f m, float x, float y, float r, float a, int w, int h) {
        float outer = r * 1.5f;
        if (x + outer < 0 || y + outer < 0 || x - outer > w || y - outer > h) return false;
        int n = 32;
        for (int i = 0; i < n; i++) {
            double u = 2 * Math.PI * i / n, v = 2 * Math.PI * (i + 1) / n;
            float c0 = (float) Math.cos(u), s0 = (float) Math.sin(u), c1 = (float) Math.cos(v), s1 = (float) Math.sin(v);
            b.addVertex(m, x, y, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * r, y + s1 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c0 * r, y + s0 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c0 * r, y + s0 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * r, y + s1 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * outer, y + s1 * outer, 0).setColor(0.05f, 0.05f, 0.05f, 0);
            b.addVertex(m, x + c0 * r, y + s0 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * outer, y + s1 * outer, 0).setColor(0.05f, 0.05f, 0.05f, 0);
            b.addVertex(m, x + c0 * outer, y + s0 * outer, 0).setColor(0.05f, 0.05f, 0.05f, 0);
        }
        return true;
    }

    /** Квадрат свечения с серединой (x, y) и полуразмером half, в пикселях интерфейса. */
    private static void spot(BufferBuilder b, Matrix4f m, float x, float y, float half, float r, float g, float bl, float a) {
        b.addVertex(m, x - half, y - half, 0).setUv(0, 0).setColor(r, g, bl, a);
        b.addVertex(m, x - half, y + half, 0).setUv(0, 1).setColor(r, g, bl, a);
        b.addVertex(m, x + half, y + half, 0).setUv(1, 1).setColor(r, g, bl, a);
        b.addVertex(m, x + half, y - half, 0).setUv(1, 0).setColor(r, g, bl, a);
    }
}
