package ua.zentix.incoming.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.Keys;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.render.ScreenProjection;
import ua.zentix.incoming.entity.FlightPhase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Свои снаряды в полёте — у того, кто пустил: справа сверху список (что, фаза полёта, время до удара), а в мире —
 * метки: рамка вокруг снаряда с номером и временем и рамка вокруг его цели. Время знает сервер (маршрут в обход и полёт
 * вне загруженного мира), клиент лишь досчитывает его между пакетами.
 */
public final class StrikesHud {
    private static final int MAX_LINES = 6;

    private StrikesHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null || mc.player == null || !IncomingConfig.CLIENT.hud.get()) return;
        List<ClientFlights.Tracked> flights = ClientFlights.all();
        if (flights.isEmpty()) return;
        // в камере снаряда справа вверху — её телеметрия (и время до удара): список поверх неё слипался бы в кашу
        if (ProjectileCamera.isActive()) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        markers(g, mc.font, flights, pt);

        Font font = mc.font;
        int x = g.guiWidth() - 6, y = 6;
        Component title = Component.translatable("incoming.hud.in_flight", flights.size()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        g.drawString(font, title, x - font.width(title), y, 0xFFFFFFFF);
        y += 11;
        for (int i = 0; i < Math.min(MAX_LINES, flights.size()); i++) {
            Component t = line(flights.get(i), pt);
            g.drawString(font, t, x - font.width(t), y, 0xFFFFFFFF);
            y += 10;
        }
        if (flights.size() > MAX_LINES) {
            Component more = Component.translatable("incoming.hud.more", flights.size() - MAX_LINES).withStyle(ChatFormatting.GRAY);
            g.drawString(font, more, x - font.width(more), y, 0xFFFFFFFF);
            y += 10;
        }
        Component hint = Component.translatable("incoming.hud.camera_hint", Keys.CAMERA.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.DARK_GRAY);
        g.drawString(font, hint, x - font.width(hint), y + 2, 0xFFFFFFFF);
    }

    /** «Шахед №2 · маршрут · 0:41». */
    static Component line(ClientFlights.Tracked f, float pt) {
        MutableComponent name = Component.translatable("incoming.hud.flight", f.weapon().displayName(), f.number);
        if (f.nuclear()) name = Component.literal("☢ ").withStyle(ChatFormatting.YELLOW).append(name);
        Component phase = f.phase().displayName();
        ChatFormatting color = switch (f.phase()) {
            case READY, IGNITION, BOOST -> ChatFormatting.GOLD;
            case TERMINAL, POP_UP -> ChatFormatting.RED;
            default -> ChatFormatting.YELLOW;
        };
        MutableComponent line = Component.translatable("incoming.hud.flight_line", name, phase, clock(f.etaSeconds(pt))).withStyle(color);
        Component target = targetText(f);
        if (target != null) line.append(Component.literal(" → ").append(target));
        return line;
    }

    /** Подпись цели: кто или что, «цель потеряна», если она пропала. Для точки — null. */
    static Component targetText(ClientFlights.Tracked f) {
        if (f.targetLost()) return Component.translatable("incoming.hud.target_lost").withStyle(ChatFormatting.GRAY);
        return f.targetLabel();
    }

    public static String clock(double seconds) {
        int s = (int) Math.ceil(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * Метки в мире — рамки-уголки по угловому размеру тела в этом кадре (матрица кадра, с зумом): у снаряда — шар его
     * корпуса там, где клиент его рисует, с номером и временем над рамкой; у цели — её коробка, с номерами снарядов,
     * которые на неё идут, и подписью, кто это, под рамкой; от рамки снаряда к рамке цели — пунктир. С зумом рамка растёт
     * ровно с телом, а подпись и наименьшая рамка ({@link #MIN_FRAME}) — пиксели интерфейса при любом поле зрения, и
     * подпись всегда вне рамки: тело она не закрывает (жалобы 23.10: метки велики при зуме, подпись «#1 0:16» сидела
     * поверх шахеда). Сущность цели, которую клиент видит, — по её сглаженному месту (точка из пакета сервера — раз в
     * 5 тиков и отстаёт); точка и блок — коробкой блока вокруг точки. Цели ближе 4 блоков друг к другу — одна рамка
     * (залп по игроку не рисует десять одинаковых). Потерянная цель — серая. За спиной — не рисуются.
     */
    private static void markers(GuiGraphics g, Font font, List<ClientFlights.Tracked> flights, float pt) {
        int w = g.guiWidth(), h = g.guiHeight();
        List<TargetMark> marks = new ArrayList<>();
        List<ClientFlights.Tracked> shown = new ArrayList<>();
        List<TargetMark> aims = new ArrayList<>();
        for (ClientFlights.Tracked f : flights) {
            if (f.phase() == FlightPhase.READY) continue;
            AABB box = targetBox(f, pt);
            Vec3 at = box.getCenter();
            TargetMark mark = null;
            for (TargetMark m : marks) {
                if (m.lost == f.targetLost() && m.pos.distanceToSqr(at) < 16) {
                    mark = m;
                    break;
                }
            }
            if (mark == null) marks.add(mark = new TargetMark(at, box, f.targetLost(), targetText(f)));
            mark.numbers.add(f.number);
            shown.add(f);
            aims.add(mark);
        }
        for (TargetMark m : marks) {
            float[] r = ScreenProjection.rect(m.box);
            // угол коробки за глазом (цель вплотную, глаз в ней) — наименьшая рамка у середины
            if (r == null) r = point(ScreenProjection.project(m.pos));
            m.frame = r == null ? null : frame(r, w, h);
        }
        for (int i = 0; i < shown.size(); i++) {
            ClientFlights.Tracked f = shown.get(i);
            Vec3 at = f.position(pt);
            float[] r = ScreenProjection.rect(at, 0.5 * f.bodySize());
            if (r == null) r = point(ScreenProjection.project(at));
            if (r == null) continue;
            float[] fr = frame(r, w, h);
            int c = f.phase() == FlightPhase.TERMINAL ? 0xFFFF4030 : 0xFFFFC040;
            float[] t = aims.get(i).frame;
            if (t != null) {
                float[] from = edge(fr, 0.5f * (t[0] + t[2]), 0.5f * (t[1] + t[3])), to = edge(t, 0.5f * (fr[0] + fr[2]), 0.5f * (fr[1] + fr[3]));
                if (from != null && to != null) {
                    HudDraw.dotted(g, Math.round(from[0]), Math.round(from[1]), Math.round(to[0]), Math.round(to[1]), (c & 0x00FFFFFF) | 0x70000000);
                }
            }
            if (!onScreen(fr, w, h)) continue;
            HudDraw.corners(g, Math.round(fr[0]), Math.round(fr[1]), Math.round(fr[2]), Math.round(fr[3]), c);
            String label = Component.translatable("incoming.hud.number", f.number).getString() + " " + clock(f.etaSeconds(pt));
            g.drawString(font, label, Math.round(0.5f * (fr[0] + fr[2])) - font.width(label) / 2, labelAbove(fr), c);
        }
        List<int[]> placed = new ArrayList<>();
        // подписи раскладываются по номерам снарядов, а не по месту на экране: порядок не меняется, когда метки движутся
        // (при зуме и повороте подписи не перескакивают со строки на строку)
        marks.sort(Comparator.comparingInt((TargetMark m) -> m.numbers.getFirst()));
        for (TargetMark m : marks) {
            float[] fr = m.frame;
            if (fr == null || !onScreen(fr, w, h)) continue;
            int x0 = Math.round(fr[0]), y0 = Math.round(fr[1]), x1 = Math.round(fr[2]);
            int c = m.lost ? 0xC0A0A0A0 : 0xC0FF3030;
            HudDraw.corners(g, x0, y0, x1, Math.round(fr[3]), c);
            MutableComponent text = Component.translatable("incoming.hud.number", numbers(m.numbers));
            if (m.label != null) text.append(" · ").append(m.label);
            int tw = font.width(text), x = (x0 + x1) / 2 - tw / 2, y = labelY(placed, x, tw, labelBelow(fr));
            placed.add(new int[] {x, y, tw});
            g.drawString(font, text, x, y, c | 0xFF000000);
        }
    }

    /** Наименьшая рамка на экране, пикселей интерфейса: цель вдали меньше пикселя, а рамку должно быть видно. */
    static final int MIN_FRAME = 7;
    /** Зазор между рамкой и подписью и высота строки подписи, пикселей интерфейса (шрифт Minecraft — 9 на строку). */
    static final int LABEL_GAP = 3, LABEL_LINE = 9;

    /**
     * Рамка тела в пикселях интерфейса шириной {@code w} и высотой {@code h} по прямоугольнику {@code r} в долях экрана
     * ({@link ScreenProjection#rect}): сам угловой размер тела, не меньше {@link #MIN_FRAME} вокруг его середины.
     */
    static float[] frame(float[] r, int w, int h) {
        float x0 = r[0] * w, y0 = r[1] * h, x1 = r[2] * w, y1 = r[3] * h;
        float cx = 0.5f * (x0 + x1), cy = 0.5f * (y0 + y1), half = 0.5f * MIN_FRAME;
        return new float[]{Math.min(x0, cx - half), Math.min(y0, cy - half), Math.max(x1, cx + half), Math.max(y1, cy + half)};
    }

    /** Строка подписи над рамкой {@code fr} (снаряд): низ строки — над верхом рамки, тело подпись не закрывает. */
    static int labelAbove(float[] fr) {
        return (int) Math.floor(fr[1]) - LABEL_GAP - LABEL_LINE;
    }

    /** Строка подписи под рамкой {@code fr} (цель). */
    static int labelBelow(float[] fr) {
        return (int) Math.ceil(fr[3]) + LABEL_GAP;
    }

    /** Точка на экране (доли) — прямоугольник нулевого размера, из него {@link #frame} делает наименьшую рамку. */
    @Nullable
    private static float[] point(@Nullable float[] c) {
        return c == null ? null : new float[]{c[0], c[1], c[0], c[1]};
    }

    private static boolean onScreen(float[] fr, int w, int h) {
        return fr[2] > 0 && fr[0] < w && fr[3] > 0 && fr[1] < h;
    }

    /**
     * Где прямая от середины рамки {@code fr} к точке ({@code tx}, {@code ty}) выходит из рамки: пунктир от снаряда к цели
     * идёт между рамками и тел не перечёркивает. Null — точка внутри рамки (рамки слились, пунктира нет).
     */
    @Nullable
    static float[] edge(float[] fr, float tx, float ty) {
        float cx = 0.5f * (fr[0] + fr[2]), cy = 0.5f * (fr[1] + fr[3]), dx = tx - cx, dy = ty - cy;
        float s = Math.min(dx == 0 ? Float.MAX_VALUE : 0.5f * (fr[2] - fr[0]) / Math.abs(dx),
                dy == 0 ? Float.MAX_VALUE : 0.5f * (fr[3] - fr[1]) / Math.abs(dy));
        return s >= 1 ? null : new float[]{cx + dx * s, cy + dy * s};
    }

    /**
     * Коробка цели в мире в долю тика {@code pt}: видимая клиенту сущность — её коробка на её сглаженном месте; иначе —
     * блок вокруг точки цели.
     */
    static AABB targetBox(ClientFlights.Tracked f, float pt) {
        net.minecraft.world.entity.Entity e = f.targetEntity();
        if (e != null) return e.getBoundingBox().move(e.getPosition(pt).subtract(e.position()));
        return AABB.ofSize(f.target(), 1, 1, 1);
    }

    /** Строка подписи шириной {@code width} от {@code x}: первая с {@code y} вниз, где она ни на что не наезжает. */
    static int labelY(List<int[]> placed, int x, int width, int y) {
        for (int n = 0; n < 32; n++) {
            boolean free = true;
            for (int[] r : placed) {
                if (x < r[0] + r[2] + 4 && r[0] < x + width + 4 && Math.abs(y - r[1]) < 10) {
                    free = false;
                    break;
                }
            }
            if (free) return y;
            y += 10;
        }
        return y;
    }

    /** Рамка цели: середина и коробка цели в мире, потеряна ли она, подпись и номера снарядов. */
    private static final class TargetMark {
        final Vec3 pos;
        final AABB box;
        final boolean lost;
        @Nullable
        final Component label;
        final List<Integer> numbers = new ArrayList<>();
        /** Рамка в этом кадре, пикселей интерфейса; null — цель за спиной. */
        @Nullable
        float[] frame;

        TargetMark(Vec3 pos, AABB box, boolean lost, @Nullable Component label) {
            this.pos = pos;
            this.box = box;
            this.lost = lost;
            this.label = label;
        }
    }

    /** «1–3, 5»: номера по возрастанию, подряд идущие — диапазоном (знак номера — у строки {@code incoming.hud.number}). */
    static String numbers(List<Integer> list) {
        List<Integer> n = new ArrayList<>(list);
        Collections.sort(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.size(); ) {
            int j = i;
            while (j + 1 < n.size() && n.get(j + 1) == n.get(j) + 1) j++;
            if (i > 0) sb.append(", ");
            sb.append(n.get(i));
            if (j > i) sb.append(j == i + 1 ? ", " : "–").append(n.get(j));
            i = j + 1;
        }
        return sb.toString();
    }
}
