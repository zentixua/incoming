package ua.zentix.incoming.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.hud.HudDraw;
import ua.zentix.incoming.client.map.MapPlayers;
import ua.zentix.incoming.client.map.MapProjection;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Sightings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Targeting map, like an operator's tablet: north-up terrain map ({@link TerrainTiles}: Distant Horizons for
 * kilometres, otherwise loaded chunks), grid, own position and viewing direction, other players ({@link MapPlayers}),
 * projectiles in flight. LMB selects impact site (coordinates, distance and bearing from self) or player (server
 * permits strikes only when visible, and only camera-equipped weapons track them while in frame,
 * {@code ServerActions.sighted}); drag pans, wheel zooms at cursor, Enter fires, Space returns to self. Fire uses remote
 * settings; server checks range and permissions ({@code map_range}).
 * <p>
 * Route ({@link Waypoints}, remembered by {@link MapTarget}): Shift+LMB appends waypoint (up to {@link Waypoints#MAX}),
 * drag to move, RMB removes one, button clears all. Line runs from launch site through waypoints to target; below map,
 * length and flight time. Beyond weapon route range (spec, {@link WeaponSpec.Route#reach}), line is red and fire
 * unavailable. Weapons without waypoint flight (ballistic, MLRS) ignore route; line is dim.
 * <p>
 * Launch site (remembered by {@link MapTarget}) only for operator and world host ({@link #mayPickLaunchSite}): Ctrl+LMB
 * or button then click places launcher there (as {@code from x z} command); drag icon, RMB on it or button resets
 * to launch near self. Without selection, launch near player; server validates site ({@code ServerActions.launchSiteProblem}).
 * <p>
 * Thick dark-outlined symbols over terrain ({@link HudDraw#HALO}): thin dotted lines would vanish over a varied city.
 */
public class MapScreen extends Screen {
    /** Pixels per block: from 32 blocks per pixel to 8 pixels per block. */
    private static final double MIN_SCALE = 1.0 / 32, MAX_SCALE = 8;
    private static final double ZOOM_STEP = 1.25;
    /** Mouse movement beyond this (pixels) means map drag, rather than site selection. */
    private static final double DRAG_THRESHOLD = 3;
    /** Projectile and player markers beyond map edge placed this many pixels inward. */
    private static final int EDGE_MARGIN = 12;
    /** Click within this distance (pixels) of a player icon or waypoint selects it, rather than a location. */
    private static final int PICK_RADIUS = 9;
    /** Query server for players every this many ticks while map is open. */
    private static final int PLAYERS_PERIOD = 10;
    /**
     * Top: title and cursor; bottom: target line (separate, clear of grid label), route line and buttons,
     * plus launch-site line for players allowed to choose it ({@link #LAUNCH_LINE}).
     */
    private static final int TOP = 22, BOTTOM = 56, LAUNCH_LINE = 12;
    /** Buttons below map: no wider than this many pixels, separated by {@link #BUTTON_GAP}. */
    private static final int BUTTON_W = 100, BUTTON_GAP = 4;

    private static final int BG = 0xFF0A1410, GRID = 0x3060FF90, GRID_TEXT = 0xC060FF90, INK = 0xFFD8F0E0, DIM = 0xFF90A898;
    /** Slightly dim terrain keeps overlaid symbols readable while showing city and roads. */
    private static final int TERRAIN_DIM = 0x480A1410;
    private static final int PANEL = 0xD0000000, TARGET = 0xFFFF3030, SPREAD = 0xF0FF5040, SPREAD_FILL = 0x38FF3020;
    private static final int CRAFT = 0xFFFFD040, ROUTE = 0xFFFF8040, AIM_LINE = 0xD0FFFFFF, PLAYER = 0xFF50DCFF, OPERATOR = 0xFFFFFFFF;
    /** Operator route: valid green, beyond weapon range red ({@link #TARGET}), unused by weapon {@link #DIM}. */
    private static final int WAYPOINTS = 0xFF7CFF8C;
    /** Team friendlies and sighted hostile projectiles and others (neither players nor craft). */
    private static final int FRIEND = 0xFF60FF80, HOSTILE = 0xFFFF6040;
    /** Launch site: distinct from all map symbols (players cyan, friendlies green, projectiles yellow, target red). */
    private static final int LAUNCH = 0xFFC080FF;

    /** Map view retained between openings while player stays in same world and dimension. */
    private static double viewX, viewZ, scale = 1;
    /** Dimension of view (as site in {@link MapTarget}); null if never set. */
    @Nullable
    private static ResourceKey<Level> placedIn;

    private final RemoteScreen remote;
    private Button fireButton, clearRouteButton, launchButton;
    /** Player selects launch site ({@link #mayPickLaunchSite}, decided on opening): button, line and icon. */
    private boolean launchAllowed;
    /** Next map click selects launch site (launch-site button pressed). */
    private boolean pickingLaunch;
    private double pressX, pressY;
    /** LMB held on map, not button; drag pans map or moves waypoint or launch site. */
    private boolean pressing, dragging;
    /** Waypoint where LMB pressed (being dragged), or −1. */
    private int grabbed = -1;
    /** LMB pressed on launch-site icon (being dragged). */
    private boolean grabbedLaunch;
    private int ticks;
    /** For logs: first map draw time, initially ready tiles, readiness time logged flag. */
    private long openedNs;
    private TerrainTiles.Progress atOpen = new TerrainTiles.Progress(0, 0, "");
    private boolean readyLogged;
    /** Other players this frame ({@link MapPlayers#marks}): drawing, click and target line share the same data. */
    private List<MapPlayers.Mark> marks = List.of();

    public MapScreen(RemoteScreen remote) {
        super(Component.translatable("incoming.map.title"));
        this.remote = remote;
    }

    /** World changed: map will reopen centred on player. */
    public static void reset() {
        placedIn = null;
        scale = 1;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        TerrainTiles.opened();
        LocalPlayer p = mc.player;
        // Old view in another dimension has foreign coordinates: open map on selected site or player.
        if (p != null && mc.level != null && !mc.level.dimension().equals(placedIn)) {
            MapTarget.Place at = MapTarget.get(mc.level).orElse(new MapTarget.Place(p.getX(), p.getZ()));
            viewX = at.x();
            viewZ = at.z();
            placedIn = mc.level.dimension();
        }
        launchAllowed = mayPickLaunchSite();
        if (!launchAllowed) pickingLaunch = false;
        fireButton = Button.builder(Component.translatable("incoming.remote.fire").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), b -> fire()).build();
        clearRouteButton = Button.builder(Component.translatable("incoming.map.route.clear"), b -> MapTarget.clearRoute()).build();
        launchButton = Button.builder(Component.translatable("incoming.map.launch.pick"), b -> launchButton())
                .tooltip(Tooltip.create(Component.translatable("incoming.map.launch.tooltip"))).build();
        Button center = Button.builder(Component.translatable("incoming.map.center"), b -> centerOnPlayer()).build();
        Button back = Button.builder(Component.translatable("incoming.map.back"), b -> onClose()).build();
        List<Button> row = launchAllowed ? List.of(fireButton, clearRouteButton, launchButton, center, back) : List.of(fireButton, clearRouteButton, center, back);
        // Centred row, narrower buttons for narrow window (large GUI): five at 100 do not fit in 480 pixels.
        int n = row.size(), w = Math.min(BUTTON_W, (width - 8 - BUTTON_GAP * (n - 1)) / n), x = (width - n * w - BUTTON_GAP * (n - 1)) / 2;
        for (Button b : row) {
            b.setRectangle(w, 20, x, height - 24);
            addRenderableWidget(b);
            x += w + BUTTON_GAP;
        }
        updateButtons();
        // Map opened with a selected site: prefetch its area too (server only extends the same area).
        if (!ceiling() && selectedPlayer().isEmpty()) selected().ifPresent(MapScreen::preload);
    }

    @Override
    public void tick() {
        if (ticks++ % PLAYERS_PERIOD == 0) MapPlayers.request();
    }

    /** Start loading target area on server now: usually ready by order time. */
    private static void preload(MapTarget.Place place) {
        PacketDistributor.sendToServer(new C2S.Pick(place.x(), place.z()));
    }

    /** Selected site (when remote target is not a player). */
    private Optional<MapTarget.Place> selected() {
        return MapTarget.get(Minecraft.getInstance().level);
    }

    /** Selected player: remote target is a player (selected on map or remote). */
    private Optional<String> selectedPlayer() {
        Loadout l = remote.loadout();
        return l.mode() == TargetMode.PLAYER && !l.player().isEmpty() ? Optional.of(l.player()) : Optional.empty();
    }

    /**
     * A valid fire target: player in any world, site where map has no ceiling; route, if used by weapon, within its
     * range (player absent from map means server checks route).
     */
    private boolean canFire() {
        return (selectedPlayer().isPresent() || selected().isPresent() && !ceiling()) && routeLength().map(len -> len <= reach()).orElse(true);
    }

    private void updateButtons() {
        fireButton.active = canFire();
        clearRouteButton.active = !route().isEmpty();
        launchButton.active = !ceiling();
        launchButton.setMessage(pickingLaunch ? Component.translatable("incoming.map.launch.picking").withStyle(ChatFormatting.YELLOW)
                : launchSite().isPresent() ? Component.translatable("incoming.map.launch.reset") : Component.translatable("incoming.map.launch.pick"));
    }

    /**
     * Operator or world host may select launch site on map, the same permissions as server
     * ({@code ServerActions.launchSiteProblem}: {@code NuclearKeys.trusted}); others have no launch-site selection.
     */
    static boolean mayPickLaunchSite() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.hasPermissions(2) || mc.hasSingleplayerServer());
    }

    /** Selected launch site as map icon; empty if unselected or player not permitted to select it. */
    private Optional<MapTarget.Place> launchSite() {
        return launchAllowed ? MapTarget.launch(Minecraft.getInstance().level) : Optional.empty();
    }

    /**
     * Map strike launch site: selected site if remote uses it ({@link RemoteScreen#launchFrom}: neither linked
     * launchers nor ICBM); empty means near player. Map path, length and fire use this same site.
     */
    private Optional<Vec3> launchFrom() {
        return launchSite().isPresent() ? remote.launchFrom() : Optional.empty();
    }

    /** Horizontal path origin: strike launch site, otherwise player. */
    private Vec3 start(Vec3 me) {
        return launchFrom().orElse(me);
    }

    /** Launch-site button: select on map, cancel selection, or return to launching near self. */
    private void launchButton() {
        if (pickingLaunch) pickingLaunch = false;
        else if (launchSite().isPresent()) MapTarget.clearLaunch();
        else pickingLaunch = true;
        updateButtons();
    }

    /** Waypoints in this dimension. */
    private static List<MapTarget.Place> route() {
        return MapTarget.route(Minecraft.getInstance().level);
    }

    /** Remote weapon flies through waypoints. */
    private boolean routed() {
        return remote.loadout().weapon().spec().route().waypoints();
    }

    /** Weapon route range, blocks. */
    private double reach() {
        return remote.loadout().weapon().spec().route().reach();
    }

    /** Horizontal strike destination: selected site or selected player if present on map. */
    private Optional<Vec3> aimPoint() {
        Optional<String> player = selectedPlayer();
        if (player.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.target() && m.name().equalsIgnoreCase(player.get())) return Optional.of(new Vec3(m.x(), 0, m.z()));
            }
            return Optional.empty();
        }
        return selected().map(t -> new Vec3(t.x(), 0, t.z()));
    }

    /**
     * Path length from launch site (otherwise player) through waypoints to target, matching server check
     * ({@code ServerActions.routeProblem}). Empty if no route, weapon does not use it, or target unknown.
     */
    private Optional<Double> routeLength() {
        Minecraft mc = Minecraft.getInstance();
        Waypoints via = MapTarget.waypoints(mc.level);
        if (via.isEmpty() || !routed() || mc.player == null) return Optional.empty();
        Vec3 start = start(mc.player.position());
        return aimPoint().map(aim -> via.length(start, aim));
    }

    /** Bottom panel: lines and buttons; one line higher for launch-site selectors (world without ceiling). */
    private int panel() {
        return BOTTOM + (launchAllowed && !ceiling() ? LAUNCH_LINE : 0);
    }

    private MapProjection projection() {
        return new MapProjection(width / 2.0, TOP + (height - TOP - panel()) / 2.0, viewX, viewZ, scale);
    }

    private boolean onMap(double x, double y) {
        return y >= TOP && y < height - panel();
    }

    /** Fire at selection: player already selected in remote, otherwise site (remote may have another target mode). */
    private void fire() {
        if (!canFire()) return;
        if (selectedPlayer().isEmpty()) remote.aimAtPlace();
        remote.fire(MapTarget.waypoints(Minecraft.getInstance().level), launchFrom());
    }

    private void centerOnPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        viewX = p.getX();
        viewZ = p.getZ();
    }

    /** Ceiling world (Nether): map sees only the roof; no site selection, server rejects such a strike. */
    private static boolean ceiling() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimensionType().hasCeiling();
    }

    /**
     * Map click: player under cursor becomes target; otherwise location (x, z), with ground height resolved by server
     * (map surface height is its estimate until the area loads).
     */
    private void select(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        MapPlayers.Mark hit = playerAt(screenX, screenY);
        if (hit != null) {
            remote.aimAtPlayer(hit.name());
        } else {
            if (ceiling()) return;
            MapProjection map = projection();
            MapTarget.Place place = new MapTarget.Place(map.worldX(screenX), map.worldZ(screenY));
            MapTarget.set(mc.level, place);
            remote.aimAtPlace();
            preload(place);
        }
        updateButtons();
    }

    /** Shift+click appends a waypoint for weapons using them, where site selection is allowed. */
    private void addWaypoint(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ceiling() || !routed()) return;
        MapProjection map = projection();
        MapTarget.add(mc.level, new MapTarget.Place(map.worldX(screenX), map.worldZ(screenY)));
        updateButtons();
    }

    /** Launch site at map screen point (Ctrl+click or click after launch-site button). */
    private void placeLaunch(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        pickingLaunch = false;
        if (mc.level == null || ceiling() || !launchAllowed) return;
        MapProjection map = projection();
        MapTarget.setLaunch(mc.level, new MapTarget.Place(map.worldX(screenX), map.worldZ(screenY)));
        updateButtons();
    }

    /** Launch-site icon within {@link #PICK_RADIUS} of screen point (drag or remove). */
    private boolean launchAt(double screenX, double screenY) {
        Optional<MapTarget.Place> site = launchSite();
        if (site.isEmpty()) return false;
        int[] at = projection().at(site.get().x(), site.get().z());
        return Mth.square(at[0] - screenX) + Mth.square(at[1] - screenY) <= PICK_RADIUS * PICK_RADIUS;
    }

    /** Waypoint icon within {@link #PICK_RADIUS} of screen point, or −1. */
    private int waypointAt(double screenX, double screenY) {
        MapProjection map = projection();
        List<MapTarget.Place> route = route();
        int best = -1;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (int i = 0; i < route.size(); i++) {
            int[] at = map.at(route.get(i).x(), route.get(i).z());
            double d = Mth.square(at[0] - screenX) + Mth.square(at[1] - screenY);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Hostile player icon (on map or at edge) within {@link #PICK_RADIUS} of screen point. */
    @Nullable
    private MapPlayers.Mark playerAt(double screenX, double screenY) {
        MapProjection map = projection();
        MapPlayers.Mark best = null;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (MapPlayers.Mark m : marks) {
            if (!m.target()) continue;
            int[] at = map.at(m.x(), m.z());
            int[] edge = edge(at[0], at[1], height - panel());
            if (edge != null) at = edge;
            double d = Mth.square(at[0] - screenX) + Mth.square(at[1] - screenY);
            if (d <= bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- mouse and keys

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (!onMap(mouseX, mouseY)) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // RMB on waypoint removes it; on launch site, returns to launching near self.
            int i = waypointAt(mouseX, mouseY);
            if (i >= 0) MapTarget.remove(Minecraft.getInstance().level, i);
            else if (launchAt(mouseX, mouseY)) MapTarget.clearLaunch();
            else return false;
            updateButtons();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        pressX = mouseX;
        pressY = mouseY;
        pressing = true;
        dragging = false;
        grabbed = waypointAt(mouseX, mouseY);
        grabbedLaunch = grabbed < 0 && !pickingLaunch && launchAt(mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (!pressing) return false;
        if (!dragging && Math.hypot(mouseX - pressX, mouseY - pressY) < DRAG_THRESHOLD) return true;
        dragging = true;
        if (grabbed >= 0 || grabbedLaunch) {
            // Drag waypoint or launch site under cursor (keep it clear of the bottom panel).
            MapProjection map = projection();
            MapTarget.Place place = new MapTarget.Place(map.worldX(mouseX), map.worldZ(Mth.clamp(mouseY, TOP, height - panel() - 1)));
            if (grabbedLaunch) MapTarget.setLaunch(Minecraft.getInstance().level, place);
            else MapTarget.move(Minecraft.getInstance().level, grabbed, place);
            return true;
        }
        viewX -= dragX / scale;
        viewZ -= dragY / scale;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        boolean click = pressing && !dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && onMap(mouseX, mouseY);
        boolean onPoint = grabbed >= 0 || grabbedLaunch;
        pressing = dragging = grabbedLaunch = false;
        grabbed = -1;
        // Click on waypoint or launch site selects nothing: these are dragged or removed.
        if (click && !onPoint) {
            if (pickingLaunch || launchAllowed && hasControlDown()) placeLaunch(mouseX, mouseY);
            else if (hasShiftDown()) addWaypoint(mouseX, mouseY);
            else select(mouseX, mouseY);
        }
        return click || handled;
    }

    /** Wheel zooms; world point under cursor remains there. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || !onMap(mouseX, mouseY)) return false;
        MapProjection before = projection();
        double wx = before.worldX(mouseX), wz = before.worldZ(mouseY);
        scale = Mth.clamp(scale * Math.pow(ZOOM_STEP, scrollY), MIN_SCALE, MAX_SCALE);
        MapProjection after = projection();
        viewX += wx - after.worldX(mouseX);
        viewZ += wz - after.worldZ(mouseY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            fire();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            centerOnPlayer();
            return true;
        }
        // Esc while choosing launch site cancels selection rather than closing map.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && pickingLaunch) {
            pickingLaunch = false;
            updateButtons();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Esc returns to remote. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(remote);
    }

    /** Map closed before view terrain fully built: log how much was ready too. */
    @Override
    public void removed() {
        if (openedNs == 0 || readyLogged) return;
        TerrainTiles.Progress p = TerrainTiles.progress();
        Incoming.LOG.info("Targeting map closed after {} ms with view terrain incomplete: {} of {} tiles ({}), from memory at once {} ({}); {}",
                (System.nanoTime() - openedNs) / 1_000_000, p.ready(), p.visible(), p.layers(), atOpen.ready(), atOpen.layers(), TerrainTiles.stats());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- visuals

    /** Map replaces screen background; buttons draw over it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        g.fill(0, 0, width, height, BG);
        if (p == null) return;
        marks = MapPlayers.marks(mc.level, partialTick);
        MapProjection map = projection();
        int bottom = height - panel();
        TerrainTiles.render(g, map, 0, TOP, width, bottom);
        logReady();
        g.enableScissor(0, TOP, width, bottom);
        g.fill(0, TOP, width, bottom, TERRAIN_DIM);
        map.drawGrid(g, font, 0, TOP, width, bottom, GRID, GRID_TEXT);

        Vec3 me = p.getPosition(partialTick);
        int[] op = map.at(me);
        Optional<MapTarget.Place> site = launchSite();
        int[] siteAt = site.map(t -> map.at(t.x(), t.z())).orElse(op);
        // Path to target starts at launch site if strike uses it, otherwise at player.
        int[] start = launchFrom().isPresent() ? siteAt : op;
        Loadout l = remote.loadout();
        Optional<String> aimedPlayer = selectedPlayer();

        // Target: salvo spread area (fill and boundary), crosshair, line from operator.
        int[] aim = null;
        if (aimedPlayer.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.target() && m.name().equalsIgnoreCase(aimedPlayer.get())) aim = map.at(m.x(), m.z());
            }
        } else {
            aim = selected().map(t -> map.at(t.x(), t.z())).orElse(null);
        }
        List<MapTarget.Place> route = route();
        boolean routed = routed() && !route.isEmpty();
        if (aim != null && !routed) HudDraw.dashed(g, start[0], start[1], aim[0], aim[1], 1, AIM_LINE);
        if (!route.isEmpty()) drawRoute(g, map, start, aim, route, mouseX, mouseY);
        if (aim != null) {
            if (l.spread() > 0) {
                float r = (float) (l.spread() * scale);
                HudDraw.disc(g, aim[0], aim[1], r, SPREAD_FILL);
                HudDraw.ring(g, aim[0], aim[1], r, 2, SPREAD);
                Component spread = Component.translatable("incoming.map.spread", l.spread());
                if (r > 14) label(g, spread, aim[0], Math.round(aim[1] - r) - 11, SPREAD);
            }
        }

        // Projectiles use server telemetry (including outside loaded world): dashed path to target, reticle at target;
        // beyond map edge, arrow at edge pointing toward them.
        for (ClientFlights.Tracked f : ClientFlights.all()) {
            if (f.phase() == FlightPhase.READY) continue;
            int[] c = map.at(f.position(partialTick)), t = map.at(f.target());
            HudDraw.dashed(g, c[0], c[1], t[0], t[1], 2, ROUTE);
            reticle(g, t[0], t[1], 5, ROUTE);
            Component number = Component.translatable("incoming.hud.number", f.number);
            int[] edge = edge(c[0], c[1], bottom);
            if (edge == null) {
                Vec3 v = f.velocity();
                HudDraw.heading(g, c[0], c[1], (float) Math.toDegrees(Math.atan2(-v.x, v.z)), CRAFT);
                label(g, number, c[0], c[1] - 17, CRAFT);
            } else {
                edgeMark(g, edge, c, bottom, number, CRAFT);
            }
        }

        // Friendlies and sightings: icon and name (older sightings dimmer, with age); beyond edge, arrow; under cursor,
        // frame (clicking hostile player selects target).
        MapPlayers.Mark hover = onMap(mouseX, mouseY) ? playerAt(mouseX, mouseY) : null;
        for (MapPlayers.Mark m : marks) {
            int[] at = map.at(m.x(), m.z());
            Component named = m.kind() == Sightings.Kind.AIRCRAFT && m.name().isBlank()
                    ? Component.translatable("incoming.target.aircraft") : Component.literal(m.name());
            Component name = m.age() > 0 ? Component.translatable("incoming.map.last_seen", named, m.age()) : named;
            boolean aimed = m.target() && aimedPlayer.isPresent() && m.name().equalsIgnoreCase(aimedPlayer.get());
            int color = aimed ? TARGET : markColor(m);
            int[] edge = edge(at[0], at[1], bottom);
            int[] mark = edge == null ? at : edge;
            if (edge == null) {
                int r = m.kind() == Sightings.Kind.PROJECTILE ? 2 : 3;
                g.fill(at[0] - r - 1, at[1] - r - 1, at[0] + r + 2, at[1] + r + 2, HudDraw.HALO);
                g.fill(at[0] - r, at[1] - r, at[0] + r + 1, at[1] + r + 1, color);
                label(g, name, at[0], at[1] + 7, color);
            } else {
                edgeMark(g, edge, at, bottom, name, color);
            }
            if (aimed) reticle(g, mark[0], mark[1], 9, TARGET);
            else if (m == hover) HudDraw.ring(g, mark[0], mark[1], 8, 1, OPERATOR);
        }

        // Selected site: crosshair over everything.
        if (aimedPlayer.isEmpty() && aim != null) crosshair(g, aim[0], aim[1]);

        // Launch site: labelled icon (frame under cursor: drag moves, RMB removes), edge arrow if outside; while selecting,
        // under cursor; dim if strike ignores it (linked launchers, ICBM).
        if (site.isPresent()) {
            Component name = Component.translatable("incoming.map.launch.label");
            int color = launchFrom().isPresent() ? LAUNCH : (LAUNCH & 0x00FFFFFF) | 0x80000000;
            int[] edge = edge(siteAt[0], siteAt[1], bottom);
            if (edge == null) {
                launchMark(g, siteAt[0], siteAt[1], color);
                label(g, name, siteAt[0], siteAt[1] + 10, color);
                if (grabbedLaunch || onMap(mouseX, mouseY) && grabbed < 0 && launchAt(mouseX, mouseY)) HudDraw.ring(g, siteAt[0] + 0.5f, siteAt[1] + 0.5f, 10, 1, OPERATOR);
            } else {
                edgeMark(g, edge, siteAt, bottom, name, color);
            }
        }
        if (pickingLaunch && onMap(mouseX, mouseY)) launchMark(g, mouseX, mouseY, (LAUNCH & 0x00FFFFFF) | 0x90000000);

        HudDraw.heading(g, op[0], op[1], p.getViewYRot(partialTick), OPERATOR);
        label(g, Component.translatable("incoming.map.you"), op[0], op[1] + 10, OPERATOR);
        g.disableScissor();
    }

    /**
     * Time from map opening until view terrain fully built, logged once (measurement on player's hardware).
     * Start at first frame showing tiles: map builds no tiles below the largest tiles' scale.
     */
    private void logReady() {
        TerrainTiles.Progress p = TerrainTiles.progress();
        if (p.visible() == 0) return;
        if (openedNs == 0) {
            openedNs = System.nanoTime();
            atOpen = p;
        }
        if (readyLogged || !p.done()) return;
        readyLogged = true;
        Incoming.LOG.info("Targeting map: view terrain ready in {} ms — tiles {}, from memory at once {} ({}); {}",
                (System.nanoTime() - openedNs) / 1_000_000, p.visible(), atOpen.ready(), atOpen.layers(), TerrainTiles.stats());
    }

    /**
     * Route: line from launch site ({@code start}: selected or player) through waypoints to target, waypoint numbers;
     * frame under cursor (drag moves, RMB removes). Colour shows launch behaviour: {@link #WAYPOINTS}, {@link #TARGET}
     * (beyond range), {@link #DIM} (weapon flies without waypoints).
     */
    private void drawRoute(GuiGraphics g, MapProjection map, int[] start, @Nullable int[] aim, List<MapTarget.Place> route, int mouseX, int mouseY) {
        int color = !routed() ? DIM : routeLength().map(len -> len <= reach()).orElse(true) ? WAYPOINTS : TARGET;
        int[] prev = start;
        int[][] at = new int[route.size()][];
        for (int i = 0; i < route.size(); i++) {
            at[i] = map.at(route.get(i).x(), route.get(i).z());
            HudDraw.haloLine(g, prev[0], prev[1], at[i][0], at[i][1], 2, color);
            prev = at[i];
        }
        if (aim != null) HudDraw.haloLine(g, prev[0], prev[1], aim[0], aim[1], 2, color);
        int hover = grabbed >= 0 ? grabbed : onMap(mouseX, mouseY) ? waypointAt(mouseX, mouseY) : -1;
        for (int i = 0; i < route.size(); i++) {
            HudDraw.disc(g, at[i][0] + 0.5f, at[i][1] + 0.5f, 7, HudDraw.HALO);
            HudDraw.disc(g, at[i][0] + 0.5f, at[i][1] + 0.5f, 6, color);
            Component n = Component.literal(Integer.toString(i + 1));
            g.drawString(font, n, at[i][0] - font.width(n) / 2 + 1, at[i][1] - 3, 0xFF000000, false);
            if (i == hover) HudDraw.ring(g, at[i][0] + 0.5f, at[i][1] + 0.5f, 9, 1, OPERATOR);
        }
    }

    /** Launch-site icon: outlined diamond. */
    private static void launchMark(GuiGraphics g, int x, int y, int color) {
        diamond(g, x, y, 7, HudDraw.HALO);
        diamond(g, x, y, 5, color);
    }

    /** Solid filled diamond of radius {@code r}. */
    private static void diamond(GuiGraphics g, int x, int y, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(x - half, y + i, x + half + 1, y + i + 1, color);
        }
    }

    /** Target reticle: ring with a dot. */
    private static void reticle(GuiGraphics g, int x, int y, int r, int color) {
        HudDraw.ring(g, x, y, r, 2, color);
        g.fill(x - 1, y - 1, x + 2, y + 2, HudDraw.HALO);
        g.fill(x, y, x + 1, y + 1, color);
    }

    /** Selected site crosshair: four outlined strokes around frame. */
    private static void crosshair(GuiGraphics g, int x, int y) {
        HudDraw.haloLine(g, x - 11, y + 0.5f, x - 3, y + 0.5f, 2, TARGET);
        HudDraw.haloLine(g, x + 4, y + 0.5f, x + 12, y + 0.5f, 2, TARGET);
        HudDraw.haloLine(g, x + 0.5f, y - 11, x + 0.5f, y - 3, 2, TARGET);
        HudDraw.haloLine(g, x + 0.5f, y + 4, x + 0.5f, y + 12, 2, TARGET);
        HudDraw.box(g, x, y, 5, HudDraw.HALO);
        HudDraw.box(g, x, y, 4, TARGET);
    }

    /** Edge marker for off-map point {@code at}: directional arrow and label. */
    private void edgeMark(GuiGraphics g, int[] edge, int[] at, int bottom, Component text, int color) {
        int cx = width / 2, cy = (TOP + bottom) / 2;
        HudDraw.heading(g, edge[0], edge[1], (float) Math.toDegrees(Math.atan2(-(at[0] - cx), at[1] - cy)), color);
        label(g, text, edge[0], edge[1] + (edge[1] < cy ? 10 : -18), color);
    }

    /** Off-map point's edge marker position, along ray from map centre; null if on map. */
    @Nullable
    private int[] edge(int x, int y, int bottom) {
        int m = EDGE_MARGIN;
        if (x >= m && x <= width - m && y >= TOP + m && y <= bottom - m) return null;
        double cx = width / 2.0, cy = (TOP + bottom) / 2.0, dx = x - cx, dy = y - cy;
        double f = Math.min(dx == 0 ? Double.MAX_VALUE : (cx - m) / Math.abs(dx), dy == 0 ? Double.MAX_VALUE : (cy - TOP - m) / Math.abs(dy));
        return new int[] {(int) Math.round(cx + dx * f), (int) Math.round(cy + dy * f)};
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        updateButtons();
        super.render(g, mouseX, mouseY, partialTick);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        // Top: screen and weapon identity; right, cursor location.
        g.fill(0, 0, width, TOP, PANEL);
        g.fill(0, height - panel(), width, height, PANEL);
        Loadout l = remote.loadout();
        Component weapon = l.count() > 1 || l.spread() > 0
                ? Component.translatable("incoming.hud.loadout.salvo", l.weapon().displayName(), l.count(), l.spread())
                : Component.translatable("incoming.hud.loadout", l.weapon().displayName());
        Component heading = title.copy().withStyle(ChatFormatting.BOLD);
        g.drawString(font, heading, 8, 7, 0xFFFFC040);
        g.drawString(font, weapon.copy().withStyle(ChatFormatting.GOLD), 8 + font.width(heading) + 12, 7, 0xFFFFFFFF);
        if (onMap(mouseX, mouseY)) {
            MapProjection map = projection();
            Component at = place(map.worldX(mouseX), map.worldZ(mouseY), p.position());
            g.drawString(font, at, width - font.width(at) - 8, 7, INK);
        }

        // Bottom panel above buttons: selected target or hint, route, launch site.
        g.drawCenteredString(font, targetLine(p.position()), width / 2, height - panel() + 5, canFire() ? 0xFFFF6050 : DIM);
        routeLine(g);
        if (launchAllowed && !ceiling()) launchLine(g);
        String note = ceiling() ? "incoming.map.no_ceiling" : !TerrainTiles.farTerrain() ? "incoming.map.near_only" : TerrainTiles.farPendingNote();
        if (note != null) g.drawString(font, Component.translatable(note).withStyle(ChatFormatting.ITALIC), 8, TOP + 6, DIM);
    }

    /** Marker colour: friendly, hostile player, craft, projectile; older sightings dimmer. */
    private static int markColor(MapPlayers.Mark m) {
        int rgb = m.friendly() ? FRIEND : switch (m.kind()) {
            case PLAYER -> PLAYER;
            case AIRCRAFT -> CRAFT;
            case PROJECTILE, OTHER -> HOSTILE;
        };
        return m.age() > 0 ? (rgb & 0x00FFFFFF) | 0xA0000000 : rgb;
    }

    /** "Target: player · position", "Target: location", or hint. */
    private Component targetLine(Vec3 me) {
        Optional<String> player = selectedPlayer();
        if (player.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.target() && m.name().equalsIgnoreCase(player.get())) {
                    return Component.translatable("incoming.map.selected_player", m.name(), place(m.x(), m.z(), me));
                }
            }
            return Component.translatable("incoming.map.selected_player_away", player.get());
        }
        Optional<MapTarget.Place> t = selected();
        return t.isPresent()
                ? Component.translatable("incoming.map.selected", place(t.get().x(), t.get().z(), me))
                : Component.translatable("incoming.map.hint");
    }

    /**
     * Route line below target: hint if no waypoints; "3 waypoints · 12.40 km · ≈ 4:55 flight"; beyond range, length and
     * permitted range; for weapons without waypoint flight, explain route is unused.
     */
    private void routeLine(GuiGraphics g) {
        int points = route().size();
        Component line;
        int color;
        if (!routed()) {
            line = points == 0 ? Component.translatable("incoming.map.route.weapon", remote.loadout().weapon().displayName())
                    : Component.translatable("incoming.map.route.unused", remote.loadout().weapon().displayName());
            color = DIM;
        } else if (points == 0) {
            line = Component.translatable("incoming.map.route.hint", Waypoints.MAX);
            color = DIM;
        } else {
            Optional<Double> length = routeLength();
            if (length.isEmpty()) {
                line = Component.translatable("incoming.map.route.no_target", points, Waypoints.MAX);
                color = WAYPOINTS;
            } else if (length.get() > reach()) {
                line = Component.translatable("incoming.map.route.too_long", distance(length.get()), distance(reach()));
                color = TARGET;
            } else {
                // Time from cruise speed: boost, climb and dive barely affect it.
                int seconds = (int) Math.round(length.get() / remote.loadout().weapon().spec().airframe().cruiseSpeed() / 20);
                line = Component.translatable("incoming.map.route", points, Waypoints.MAX, distance(length.get()),
                        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
                color = WAYPOINTS;
            }
        }
        g.drawCenteredString(font, line, width / 2, height - panel() + 17, color);
    }

    /**
     * Launch-site line below route (operator and host): how to select it; selected site's location and target distance,
     * plus weapon behaviour from spec launch method ({@link WeaponSpec.Launch}): launcher placed there, B-2 approaches
     * from that side, ICBM launches from its own pad. Remote with linked fixed launchers uses those launchers.
     */
    private void launchLine(GuiGraphics g) {
        Optional<MapTarget.Place> site = launchSite();
        Component line;
        if (remote.linked() > 0) {
            line = Component.translatable("incoming.map.launch.linked", remote.linked());
        } else if (pickingLaunch) {
            line = Component.translatable("incoming.map.launch.picking_hint");
        } else if (site.isEmpty()) {
            line = Component.translatable("incoming.map.launch.hint");
        } else {
            Component at = Component.translatable("incoming.map.launch.at", Mth.floor(site.get().x()), Mth.floor(site.get().z()));
            Component full = launchEffect(at);
            Optional<Vec3> aim = aimPoint();
            if (aim.isPresent()) {
                double dx = aim.get().x - site.get().x(), dz = aim.get().z - site.get().z();
                full = launchEffect(Component.translatable("incoming.map.launch.at_target", at, distance(Math.sqrt(dx * dx + dz * dz))));
            }
            // Narrow screen (large GUI scale): omit target distance first, then show location only.
            line = fit(full, launchEffect(at), at);
        }
        int color = launchFrom().isPresent() || pickingLaunch ? LAUNCH : DIM;
        g.drawCenteredString(font, line, width / 2, height - panel() + 29, color);
    }

    /**
     * Weapon behaviour at launch site {@code at} from spec launch method ({@link WeaponSpec.Launch}): launcher placed
     * there, B-2 approaches from that side, ICBM ignores it.
     */
    private Component launchEffect(Component at) {
        Component weapon = remote.loadout().weapon().displayName();
        return switch (remote.loadout().weapon().spec().launch()) {
            case ICBM -> Component.translatable("incoming.map.launch.unused", at, weapon);
            case BOMBER -> Component.translatable("incoming.map.launch.approach", at, weapon);
            case GUIDED, ROCKET, LOITER -> Component.translatable("incoming.map.launch.set", at);
        };
    }

    /** First line fitting screen width with margins, otherwise last. */
    private Component fit(Component... lines) {
        for (Component line : lines) {
            if (font.width(line) <= width - 16) return line;
        }
        return lines[lines.length - 1];
    }

    /** "X 1200  Z −340 · 2.40 km · bearing 135°": range and bearing from player. */
    private static Component place(double x, double z, Vec3 from) {
        double dx = x - from.x, dz = z - from.z;
        // Bearing clockwise from north (−Z).
        int azimuth = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(dx, -dz))), 360);
        return Component.translatable("incoming.map.place", Mth.floor(x), Mth.floor(z), distance(Math.sqrt(dx * dx + dz * dz)), azimuth);
    }

    /** "2.40 km" or "340 m". */
    private static Component distance(double blocks) {
        return blocks >= 1000 ? Component.translatable("incoming.map.km", String.format(Locale.ROOT, "%.2f", blocks / 1000))
                : Component.translatable("incoming.map.m", String.format(Locale.ROOT, "%.0f", blocks));
    }

    private void label(GuiGraphics g, Component text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color);
    }
}
