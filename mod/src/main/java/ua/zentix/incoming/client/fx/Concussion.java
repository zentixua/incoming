package ua.zentix.incoming.client.fx;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.ConcussionDose;
import ua.zentix.incoming.nuclear.model.ShockAtBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Контузия на экране — <b>подача</b> (принцип вида: картинка — подача, факты не меняются): волна, которая оглушает,
 * на секунду-другую мутит картинку — она размывается, теряет цвет, края темнеют. Включается, растёт и гаснет по модели
 * ({@link ConcussionDose}: доза по фронту у головы, сдвигу порога слуха и движению головы, ниже порога — 0; отклик во
 * времени с прихода фронта и его длина), а как это выглядит — размытие, серость, края — подача здесь.
 * <p>
 * Размытие — по кубу отклика, поэтому уходит раньше (к секунде), чем серость и тёмные края; серость не трогает яркий
 * цвет (огонь, небо): тускнеет то, что вокруг, а не сам взрыв. Удары залпа складываются как доли:
 * 1 − Π(1 − отклик), не больше полного. Сила — настройка {@code screen_effects} × ванильное «Искажения экрана»
 * (Distortion Effects).
 * <p>
 * Рисует слой интерфейса под HUD ({@link #render}) — ванильным проходом пост-обработки ({@link PostChain}, как размытие
 * фона меню) с одной своей программой {@code incoming:concussion}: два прохода Гаусса по осям, во втором — потеря цвета
 * и тёмные края. Как ванильные накладки (тыква, портал), с F1 его нет.
 */
public final class Concussion {
    private static final Logger LOG = LogUtils.getLogger();
    /** Проход пост-обработки. */
    private static final net.minecraft.resources.ResourceLocation CHAIN = Incoming.id("shaders/post/concussion.json");
    /** Размытие у полного уровня — σ Гаусса, пикселей при высоте кадра 1080 (растёт с высотой кадра). Подача. */
    static final double BLUR_PX = 5;
    /** Потеря цвета у полного уровня: доля пути к серому той же яркости (у ярких — меньше, шейдер). Подача. */
    static final double GREY = 0.7;
    /** Тёмные края у полного уровня: доля света, которую теряют углы кадра. Подача. */
    static final double EDGE = 0.55;
    /** Ниже этого уровня экран не трогаем: размытие меньше 0,01 пикселя, серость и края — меньше шага экрана. */
    static final double FLOOR = 2e-3;
    /** Больше стольких ударов сразу — самый старый уходит (уровень — сумма долей, предела ему это не меняет). */
    private static final int HITS_MAX = 16;

    /** Один удар: с мига {@code from} (тики клиента, с долей) — прихода фронта к телу, доза 0..1, длина отклика, с. */
    private record Hit(double from, double dose, double seconds) {}

    private static final List<Hit> HITS = new ArrayList<>();
    private static long ticks;
    /** Последний удар (строка сценария клиента). */
    private static String last = "-";
    @Nullable
    private static PostChain chain;
    /** Проход не собрался (нет шейдера): до перезагрузки ресурсов не пробовать каждый кадр заново. */
    private static boolean broken;
    private static int width, height;

    private Concussion() {}

    /**
     * Фронт дошёл до тела: доза контузии {@code dose} (0..1, {@link ConcussionDose#dose}), {@code late} — сколько тиков
     * назад (0..1) он прошёл тело. Возвращает длину отклика, с (0 — дозы нет).
     */
    public static double hit(double dose, double late) {
        double d = Mth.clamp(dose, 0, 1);
        if (!(d > 0)) return 0;
        double seconds = ConcussionDose.durationSeconds(d);
        if (!(seconds > 0)) return 0;
        if (HITS.size() >= HITS_MAX) HITS.remove(0);
        HITS.add(new Hit(ticks - Mth.clamp(late, 0, 1), d, seconds));
        last = String.format(Locale.ROOT, "dose %.3f for %.2f s", d, seconds);
        return seconds;
    }

    /**
     * Фронт обычного взрыва у тела ({@link BlastHit}): доза по фронту у головы {@code shock}, сдвигу порога слуха
     * {@code shiftDb} и движению головы {@code head} (тело и шея, {@link BodySway}; null — тело не качнуло), {@code late} —
     * сколько тиков назад он прошёл тело. Возвращает дозу.
     */
    static double front(ShockAtBody shock, double shiftDb, @Nullable BodySway.Motion head, double late) {
        double dose = ConcussionDose.dose(shock, shiftDb, head == null ? 0 : headPeak(head));
        hit(dose, late);
        return dose;
    }

    /** Наибольший поворот головы, рад: наклон тела и голова на шее поверх него в одни и те же отсчёты. */
    static double headPeak(BodySway.Motion m) {
        double peak = 0;
        double[] body = m.body(), neck = m.neck();
        for (int i = 0; i < body.length; i++) peak = Math.max(peak, Math.abs(body[i] + (neck != null && i < neck.length ? neck[i] : 0)));
        return peak;
    }

    /** Уровень контузии 0..1 в миг {@code t} тиков: отклики всех ударов вместе, 1 − Π(1 − отклик). */
    static double level(double t) {
        double clear = 1;
        for (Hit h : HITS) clear *= 1 - Mth.clamp(ConcussionDose.strength(h.dose, (t - h.from) / 20), 0, 1);
        return 1 - clear;
    }

    /**
     * Что делает экран при уровне {@code level} (уже со множителем настроек) и высоте кадра {@code frameHeight} px:
     * {σ размытия, px; доля серого; доля темноты в углах}.
     */
    static double[] look(double level, int frameHeight) {
        double l = Mth.clamp(level, 0, 1);
        return new double[]{BLUR_PX * l * l * l * frameHeight / 1080.0, GREY * l, EDGE * l};
    }

    /** Множитель настроек: {@code screen_effects} × ванильное «Искажения экрана». */
    static double strength(double screenEffects, double distortion) {
        return Mth.clamp(screenEffects, 0, 1) * Mth.clamp(distortion, 0, 1);
    }

    /** Последний удар и уровень сейчас (строка сценария клиента). */
    public static String describe() {
        return String.format(Locale.ROOT, "%s level %.3f", last, level(ticks));
    }

    public static void tick() {
        ticks++;
        HITS.removeIf(h -> (ticks - h.from) / 20 >= h.seconds);
    }

    public static void reset() {
        HITS.clear();
        ticks = 0;
        last = "-";
    }

    /** Слой интерфейса под HUD. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        if (HITS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        double k = strength(IncomingConfig.CLIENT.screenEffects.get(), mc.options.screenEffectScale().get());
        double level = k * level(ticks + delta.getGameTimeDeltaPartialTick(false));
        if (level < FLOOR) return;
        RenderTarget main = mc.getMainRenderTarget();
        PostChain post = chain(mc, main);
        if (post == null) return;
        double[] look = look(level, main.height);
        // всё, что интерфейс уже нарисовал ниже слоя (накладки камеры), — в кадр до прохода: размывается вместе с ним
        g.flush();
        post.setUniform("Radius", (float) look[0]);
        post.setUniform("Grey", (float) look[1]);
        post.setUniform("Edge", (float) look[2]);
        post.process(delta.getGameTimeDeltaTicks());
        main.bindWrite(false);
    }

    /** Проход пост-обработки на кадр {@code main} (собирается при первом ударе, растёт с кадром); null — не собрался. */
    @Nullable
    private static PostChain chain(Minecraft mc, RenderTarget main) {
        if (chain == null && !broken) {
            try {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
                width = height = -1;
            } catch (IOException | JsonSyntaxException e) {
                broken = true;
                LOG.warn("Concussion on screen is off: {} did not load", CHAIN, e);
            }
        }
        if (chain != null && (main.width != width || main.height != height)) {
            width = main.width;
            height = main.height;
            chain.resize(width, height);
        }
        return chain;
    }

    /** Перезагрузка ресурсов (F3+T, смена пакета): проход соберётся заново из новых шейдеров. */
    public static final ResourceManagerReloadListener RELOAD = (ResourceManager manager) -> {
        if (chain != null) chain.close();
        chain = null;
        broken = false;
    };
}
