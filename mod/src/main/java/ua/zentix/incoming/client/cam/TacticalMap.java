package ua.zentix.incoming.client.cam;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.hud.HudDraw;
import ua.zentix.incoming.client.hud.StrikesHud;
import ua.zentix.incoming.client.map.MapProjection;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.registry.ModEntities;

import java.util.Locale;

/**
 * Operator map: the camera display while the client has no projectile. The server sends the entity to the player
 * only within {@code min(clientTrackingRange, render distance)} horizontally and while its chunk is tracked by
 * the player ({@code ChunkMap.TrackedEntity.updatePlayer}); a projectile outside entity-ticking chunks flies outside
 * the world ({@code VirtualFlights}) and is never sent.
 * Vanilla allows distant viewing only through a spectator camera, which moves the player to the target, so this
 * shows what is available instead of video: {@code FlightStatus} telemetry (position, destination, phase, impact ETA).
 * Like a UAV ground control station without video: a north-up map with terrain ({@link TerrainTiles}), track,
 * heading, target, operator and the boundary where video becomes available. Scale and centre smoothly follow the
 * projectile, target and operator.
 */
final class TacticalMap {
    /** Fraction of the transition to the new scale and centre per tick. */
    private static final double FOLLOW = 0.15;

    private static final int BG = 0xF00A1410, GRID = 0x2860FF90, GRID_TEXT = 0x9060FF90, INK = 0xFFD8F0E0;
    private static final int TRAIL = 0xF060C8FF, ROUTE = 0xF0FF8040, TARGET = 0xFFFF3030, CRAFT = 0xFFFFD040, OTHER = 0xA0A0B0A8;
    private static final int OPERATOR = 0xFFFFFFFF, VIDEO_RING = 0x8080FFA0;
    /** Dim terrain under the map so track, target and telemetry remain readable over it. */
    private static final int TERRAIN_DIM = 0x900A1410;

    /** Map view: centre (world x, z) and pixels per block, current and previous tick (for smooth partial ticks). */
    private static double cx, cz, scale, cxO, czO, scaleO;
    private static boolean placed;

    private TacticalMap() {}

    /** Map opens again without zooming in from the old view. */
    static void reset() {
        placed = false;
    }

    /** Once per tick: map view that keeps projectile, target and operator in frame. */
    static void tick(ClientFlights.Tracked f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 craft = f.position(0), target = f.target(), me = mc.player.position();
        double minX = Math.min(craft.x, Math.min(target.x, me.x)), maxX = Math.max(craft.x, Math.max(target.x, me.x));
        double minZ = Math.min(craft.z, Math.min(target.z, me.z)), maxZ = Math.max(craft.z, Math.max(target.z, me.z));
        double w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        // Margins: telemetry above, hints below.
        double wantScale = Math.min((w - 80) / Math.max(60, maxX - minX), (h - 120) / Math.max(60, maxZ - minZ));
        double wantX = (minX + maxX) / 2, wantZ = (minZ + maxZ) / 2;
        cxO = cx;
        czO = cz;
        scaleO = scale;
        if (!placed) {
            cx = cxO = wantX;
            cz = czO = wantZ;
            scale = scaleO = wantScale;
            placed = true;
            return;
        }
        cx += (wantX - cx) * FOLLOW;
        cz += (wantZ - cz) * FOLLOW;
        // Logarithmic scale: zooming in and out are equally smooth.
        scale = Math.exp(Mth.lerp(FOLLOW, Math.log(scale), Math.log(wantScale)));
    }

    /**
     * Video range, blocks. Projectile is in the world only in entity-ticking chunks (server simulation distance;
     * otherwise it flies in {@code VirtualFlights}), and the server sends it within render distance and its type's
     * tracking range. The integrated server scales the latter by Entity Distance
     * ({@code IntegratedServer.getScaledTrackingDistance}); the dedicated server uses it as is.
     */
    static int videoRange(ClientFlights.Tracked f) {
        Minecraft mc = Minecraft.getInstance();
        double tracking = ModEntities.of(f.weapon()).clientTrackingRange() * 16;
        if (mc.hasSingleplayerServer()) tracking *= mc.options.entityDistanceScaling().get();
        int chunks = mc.options.getEffectiveRenderDistance();
        if (mc.level != null) chunks = Math.min(chunks, mc.level.getServerSimulationDistance());
        return (int) Math.min(tracking, chunks * 16);
    }

    static void render(GuiGraphics g, Font font, ClientFlights.Tracked f, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !placed) return;
        int w = g.guiWidth(), h = g.guiHeight();
        double viewX = Mth.lerp(pt, cxO, cx), viewZ = Mth.lerp(pt, czO, cz);
        double k = Math.exp(Mth.lerp(pt, Math.log(scaleO), Math.log(scale)));
        MapProjection map = new MapProjection(w / 2.0, h / 2.0 + 10, viewX, viewZ, k);

        g.fill(0, 0, w, h, BG);
        TerrainTiles.render(g, map, 0, 0, w, h);
        g.fill(0, 0, w, h, TERRAIN_DIM);
        grid(g, font, map, w, h);

        Vec3 me = mc.player.getPosition(pt);
        int range = videoRange(f);
        HudDraw.dottedCircle(g, map.x(me.x), map.y(me.z), range * k, VIDEO_RING);
        int[] op = map.at(me);
        g.fill(op[0] - 3, op[1] - 3, op[0] + 4, op[1] + 4, HudDraw.HALO);
        g.fill(op[0] - 2, op[1] - 2, op[0] + 3, op[1] + 3, OPERATOR);
        label(g, font, Component.translatable("incoming.map.you"), op[0], op[1] + 5, OPERATOR);

        for (ClientFlights.Tracked o : ClientFlights.all()) {
            if (o == f || o.phase() == FlightPhase.READY) continue;
            int[] p = map.at(o.position(pt));
            g.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, HudDraw.HALO);
            g.fill(p[0] - 1, p[1] - 1, p[0] + 2, p[1] + 2, OTHER);
        }

        Vec3 craft = f.position(pt);
        int[] c = map.at(craft);
        int[] t = map.at(f.target());
        // Travelled track is solid, remaining track dashed; thick and outlined to remain readable over varied terrain.
        int[] prev = null;
        for (Vec3 p : f.trail()) {
            int[] q = map.at(p);
            if (prev != null) HudDraw.haloLine(g, prev[0], prev[1], q[0], q[1], 2, TRAIL);
            prev = q;
        }
        if (prev != null) HudDraw.haloLine(g, prev[0], prev[1], c[0], c[1], 2, TRAIL);
        HudDraw.dashed(g, c[0], c[1], t[0], t[1], 2, ROUTE);

        HudDraw.box(g, t[0], t[1], 7, HudDraw.HALO);
        HudDraw.box(g, t[0], t[1], 6, f.targetLost() ? OTHER : TARGET);
        g.fill(t[0], t[1], t[0] + 1, t[1] + 1, TARGET);
        Component who = f.targetLabel();
        if (who != null) label(g, font, who, t[0], t[1] + 9, TARGET);

        Vec3 v = f.velocity();
        HudDraw.heading(g, c[0], c[1], (float) Math.toDegrees(Math.atan2(-v.x, v.z)), CRAFT);
        label(g, font, Component.translatable("incoming.hud.number", f.number).append(f.nuclear() ? " ☢" : ""), c[0], c[1] - 16, CRAFT);

        telemetry(g, font, f, craft, v, pt);
        status(g, font, f, craft, me, range, w, h);
    }

    private static void grid(GuiGraphics g, Font font, MapProjection map, int w, int h) {
        map.drawGrid(g, font, 0, 0, w, h, GRID, GRID_TEXT);
        g.drawString(font, "N ↑", w / 2 - font.width("N ↑") / 2, 26, GRID_TEXT);
    }

    /** Top left: screen and projectile identity, phase, altitude, speed, target range, impact ETA. */
    private static void telemetry(GuiGraphics g, Font font, ClientFlights.Tracked f, Vec3 craft, Vec3 v, float pt) {
        int x = 10, y = 8;
        g.drawString(font, Component.translatable("incoming.camera.no_link").withStyle(ChatFormatting.BOLD), x, y, 0xFFFFC040);
        y += 14;
        Component name = Component.translatable("incoming.hud.flight", f.weapon().displayName().getString().toUpperCase(Locale.ROOT), f.number);
        g.drawString(font, name, x, y, INK);
        y += 11;
        g.drawString(font, f.phase().displayName().getString().toUpperCase(Locale.ROOT),
                x, y, f.phase() == FlightPhase.TERMINAL ? 0xFFFF4040 : INK);
        y += 14;
        double kmh = ClientFlights.kmh(v.length());
        Component[] lines = {
                Component.translatable("incoming.map.height", String.format(Locale.ROOT, "%.0f", craft.y)),
                Component.translatable("incoming.camera.speed", String.format(Locale.ROOT, "%.0f", kmh)),
                Component.translatable("incoming.camera.range", String.format(Locale.ROOT, "%.0f", craft.distanceTo(f.target()))),
                Component.literal("T-" + StrikesHud.clock(f.etaSeconds(pt)))
        };
        for (int i = 0; i < lines.length; i++) {
            g.drawString(font, lines[i], x, y, i == lines.length - 1 ? 0xFFFFC040 : INK);
            y += 11;
        }
    }

    /** Bottom: when video will be available. */
    private static void status(GuiGraphics g, Font font, ClientFlights.Tracked f, Vec3 craft, Vec3 me, int range, int w, int h) {
        double dx = craft.x - me.x, dz = craft.z - me.z;
        int away = (int) Math.sqrt(dx * dx + dz * dz);
        Component line = away > range
                ? Component.translatable("incoming.map.video_at", range, away)
                : Component.translatable("incoming.map.connecting");
        g.drawString(font, line.copy().withStyle(ChatFormatting.GRAY), w / 2 - font.width(line) / 2, h - 48, INK);
    }

    private static void label(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color);
    }
}
