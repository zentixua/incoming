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
 * Own projectiles in flight for their launcher: top-right list (weapon, flight phase, impact ETA) and world markers:
 * frame around the projectile with number and time, and frame around its target. Server knows the ETA (detour route
 * and flight outside the loaded world); client only counts it down between packets.
 */
public final class StrikesHud {
    private static final int MAX_LINES = 6;

    private StrikesHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null || mc.player == null || !IncomingConfig.CLIENT.hud.get()) return;
        List<ClientFlights.Tracked> flights = ClientFlights.all();
        if (flights.isEmpty()) return;
        // Projectile camera has telemetry (including impact ETA) at top right: a list over it would become unreadable.
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

    /** "Shahed No. 2 · route · 0:41". */
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

    /** Target label: who or what, "target lost" if gone. Null for a point. */
    static Component targetText(ClientFlights.Tracked f) {
        if (f.targetLost()) return Component.translatable("incoming.hud.target_lost").withStyle(ChatFormatting.GRAY);
        return f.targetLabel();
    }

    public static String clock(double seconds) {
        int s = (int) Math.ceil(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * World markers are corner frames sized by the body's angular size in this frame (frame matrix, including zoom):
     * projectile's body sphere where the client draws it, number and time above; target's box, incoming projectile
     * numbers and identity label below; dotted line from projectile frame to target frame. Zoom scales the frame
     * exactly with the body, while label and minimum frame ({@link #MIN_FRAME}) use GUI pixels at any field of view.
     * Label always stays outside the frame, clear of the body (complaints 23.10: markers too large with zoom,
     * "#1 0:16" label over the Shahed). Visible target entity uses its smoothed position (server point every 5 ticks
     * lags); point and block use a block box around the point. Targets within 4 blocks share one frame (a salvo at
     * a player does not draw ten identical ones). Lost target is grey. Nothing drawn behind the viewer.
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
            // Box corner behind the eye (target very close, eye inside): minimum frame at the centre.
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
            if (!onScreen(fr, w, h)) continue;
            int c = f.phase() == FlightPhase.TERMINAL ? 0xFFFF4030 : 0xFFFFC040;
            float[] t = aims.get(i).frame;
            if (t != null) {
                float[] from = edge(fr, 0.5f * (t[0] + t[2]), 0.5f * (t[1] + t[3])), to = edge(t, 0.5f * (fr[0] + fr[2]), 0.5f * (fr[1] + fr[3]));
                if (from != null && to != null) {
                    HudDraw.dotted(g, Math.round(from[0]), Math.round(from[1]), Math.round(to[0]), Math.round(to[1]), (c & 0x00FFFFFF) | 0x70000000);
                }
            }
            HudDraw.corners(g, Math.round(fr[0]), Math.round(fr[1]), Math.round(fr[2]), Math.round(fr[3]), c);
            String label = Component.translatable("incoming.hud.number", f.number).getString() + " " + clock(f.etaSeconds(pt));
            g.drawString(font, label, Math.round(0.5f * (fr[0] + fr[2])) - font.width(label) / 2, labelAbove(fr), c);
        }
        List<int[]> placed = new ArrayList<>();
        // Arrange labels by projectile number, not screen position: stable order as markers move.
        // (Zooming and turning do not make labels jump between rows.)
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

    /** Minimum on-screen frame, GUI pixels: distant target is smaller than a pixel, but its frame must remain visible. */
    static final int MIN_FRAME = 7;
    /** Frame-to-label gap and label line height, GUI pixels (Minecraft font is 9 per line). */
    static final int LABEL_GAP = 3, LABEL_LINE = 9;

    /**
     * Body frame in GUI pixels of width {@code w} and height {@code h}, from screen-fraction rectangle {@code r}
     * ({@link ScreenProjection#rect}): actual body angular size, at least {@link #MIN_FRAME} around its centre.
     */
    static float[] frame(float[] r, int w, int h) {
        float x0 = r[0] * w, y0 = r[1] * h, x1 = r[2] * w, y1 = r[3] * h;
        float cx = 0.5f * (x0 + x1), cy = 0.5f * (y0 + y1), half = 0.5f * MIN_FRAME;
        return new float[]{Math.min(x0, cx - half), Math.min(y0, cy - half), Math.max(x1, cx + half), Math.max(y1, cy + half)};
    }

    /** Label line above frame {@code fr} (projectile): line bottom above frame top, clear of the body. */
    static int labelAbove(float[] fr) {
        return (int) Math.floor(fr[1]) - LABEL_GAP - LABEL_LINE;
    }

    /** Label line below frame {@code fr} (target). */
    static int labelBelow(float[] fr) {
        return (int) Math.ceil(fr[3]) + LABEL_GAP;
    }

    /** On-screen point (fractions): zero-size rectangle, expanded by {@link #frame} to the minimum frame. */
    @Nullable
    private static float[] point(@Nullable float[] c) {
        return c == null ? null : new float[]{c[0], c[1], c[0], c[1]};
    }

    private static boolean onScreen(float[] fr, int w, int h) {
        return fr[2] > 0 && fr[0] < w && fr[3] > 0 && fr[1] < h;
    }

    /**
     * Where the line from frame {@code fr}'s centre to point ({@code tx}, {@code ty}) exits the frame: dotted projectile-
     * to-target line runs between frames, clear of bodies. Null if the point is inside (frames merged, no dotted line).
     */
    @Nullable
    static float[] edge(float[] fr, float tx, float ty) {
        float cx = 0.5f * (fr[0] + fr[2]), cy = 0.5f * (fr[1] + fr[3]), dx = tx - cx, dy = ty - cy;
        float s = Math.min(dx == 0 ? Float.MAX_VALUE : 0.5f * (fr[2] - fr[0]) / Math.abs(dx),
                dy == 0 ? Float.MAX_VALUE : 0.5f * (fr[3] - fr[1]) / Math.abs(dy));
        return s >= 1 ? null : new float[]{cx + dx * s, cy + dy * s};
    }

    /**
     * Target box in the world at partial tick {@code pt}: visible entity uses its box at its smoothed position;
     * otherwise a block around the target point.
     */
    static AABB targetBox(ClientFlights.Tracked f, float pt) {
        net.minecraft.world.entity.Entity e = f.targetEntity();
        if (e != null) return e.getBoundingBox().move(e.getPosition(pt).subtract(e.position()));
        return AABB.ofSize(f.target(), 1, 1, 1);
    }

    /** Label line of width {@code width} from {@code x}: first position downward from {@code y} without overlaps. */
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

    /** Target frame: world centre and box, whether lost, label and projectile numbers. */
    private static final class TargetMark {
        final Vec3 pos;
        final AABB box;
        final boolean lost;
        @Nullable
        final Component label;
        final List<Integer> numbers = new ArrayList<>();
        /** Frame for this render frame, GUI pixels; null if target is behind the viewer. */
        @Nullable
        float[] frame;

        TargetMark(Vec3 pos, AABB box, boolean lost, @Nullable Component label) {
            this.pos = pos;
            this.box = box;
            this.lost = lost;
            this.label = label;
        }
    }

    /** "1–3, 5": ascending numbers, consecutive ones as a range (number sign from {@code incoming.hud.number}). */
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
