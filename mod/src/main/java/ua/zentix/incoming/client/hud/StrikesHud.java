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
 * метки: ромб на снаряде с номером и временем и крестик на его цели. Время знает сервер (маршрут в обход и полёт
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
     * Метки в мире: ромб на снаряде (номер и время) и пунктир от него к его цели; на цели — рамка-уголки по угловому
     * размеру цели в этом кадре (с зумом рамка растёт с целью, жалоба 23.10: метки «плывут» и велики при зуме), с
     * номерами снарядов, которые на неё идут, и подписью, кто это. Сущность цели, которую клиент видит, — по её
     * сглаженному месту (точка из пакета сервера — раз в 5 тиков и отстаёт); точка и блок — коробкой блока вокруг точки.
     * Цели ближе 4 блоков друг к другу — одна рамка (залп по игроку не рисует десять одинаковых). Потерянная цель — серая.
     * За спиной — не рисуются.
     */
    private static void markers(GuiGraphics g, Font font, List<ClientFlights.Tracked> flights, float pt) {
        int w = g.guiWidth(), h = g.guiHeight();
        List<TargetMark> marks = new ArrayList<>();
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

            float[] s = ScreenProjection.project(f.position(pt));
            float[] t = ScreenProjection.project(at);
            if (s == null) continue;
            int sx = (int) (s[0] * w), sy = (int) (s[1] * h);
            if (sx <= -20 || sx >= w + 20 || sy <= -20 || sy >= h + 20) continue;
            int c = f.phase() == FlightPhase.TERMINAL ? 0xFFFF4030 : 0xFFFFC040;
            if (t != null) HudDraw.dotted(g, sx, sy, (int) (t[0] * w), (int) (t[1] * h), (c & 0x00FFFFFF) | 0x70000000);
            HudDraw.diamond(g, sx, sy, 4, c);
            String label = Component.translatable("incoming.hud.number", f.number).getString() + " " + clock(f.etaSeconds(pt));
            g.drawString(font, label, sx - font.width(label) / 2, sy - 14, c);
        }
        List<int[]> placed = new ArrayList<>();
        // подписи раскладываются по номерам снарядов, а не по месту на экране: порядок не меняется, когда метки движутся
        // (при зуме и повороте подписи не перескакивают со строки на строку)
        marks.sort(Comparator.comparingInt((TargetMark m) -> m.numbers.getFirst()));
        for (TargetMark m : marks) {
            float[] r = ScreenProjection.rect(m.box);
            if (r == null) {
                // угол коробки за глазом (цель вплотную, глаз в ней) — наименьшая рамка у середины, как была метка
                float[] c = ScreenProjection.project(m.pos);
                if (c == null) continue;
                r = new float[]{c[0], c[1], c[0], c[1]};
            }
            float[] fr = frame(r, w, h);
            int x0 = Math.round(fr[0]), y0 = Math.round(fr[1]), x1 = Math.round(fr[2]), y1 = Math.round(fr[3]);
            if (x1 <= 0 || x0 >= w || y1 <= 0 || y0 >= h) continue;
            int c = m.lost ? 0xC0A0A0A0 : 0xC0FF3030;
            HudDraw.corners(g, x0, y0, x1, y1, c);
            MutableComponent text = Component.translatable("incoming.hud.number", numbers(m.numbers));
            if (m.label != null) text.append(" · ").append(m.label);
            int tw = font.width(text), x = (x0 + x1) / 2 - tw / 2, y = labelY(placed, x, tw, y1 + 3);
            placed.add(new int[] {x, y, tw});
            g.drawString(font, text, x, y, c | 0xFF000000);
        }
    }

    /** Наименьшая рамка на экране, пикселей интерфейса: цель вдали меньше пикселя, а рамку должно быть видно. */
    static final int MIN_FRAME = 7;

    /**
     * Рамка цели в пикселях интерфейса шириной {@code w} и высотой {@code h} по прямоугольнику {@code r} в долях экрана
     * ({@link ScreenProjection#rect}): сам угловой размер цели, не меньше {@link #MIN_FRAME} вокруг её середины.
     */
    static float[] frame(float[] r, int w, int h) {
        float x0 = r[0] * w, y0 = r[1] * h, x1 = r[2] * w, y1 = r[3] * h;
        float cx = 0.5f * (x0 + x1), cy = 0.5f * (y0 + y1), half = 0.5f * MIN_FRAME;
        return new float[]{Math.min(x0, cx - half), Math.min(y0, cy - half), Math.max(x1, cx + half), Math.max(y1, cy + half)};
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
