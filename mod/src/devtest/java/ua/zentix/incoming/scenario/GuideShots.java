package ua.zentix.incoming.scenario;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.map.MapProjection;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.client.screen.MapScreen;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.WeaponType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Player guide frames ({@code docs/guide}): {@code tools/client_scenario.sh guide}, then {@code tools/guide_images.sh}
 * crops them and builds GIFs in {@code docs/guide/img}. Village at scenario-world spawn (center: square bell), viewer
 * on high ground 90–130 blocks away; in order: crafting-table recipes (images), then GIFs for binoculars, console screen,
 * targeting map, projectiles on screen, missile camera, Lancet, SAM, fixed launcher. Each screen-GIF frame is a step,
 * with mouse and key label drawn on top (cannot press the real key without a window); world GIF runs at the specified
 * pace ({@link #startGif}). {@code INCOMING_GUIDE} selects only named sections ({@link #only}). Frames:
 * {@code run/scenario/screenshots/guide-*.png}; GIF frames: {@code guide-gif-<name>-NNNN.png}. With Distant Horizons
 * ({@code dh}), map would show distant terrain as for modpack players, but in the cloud it builds almost nothing:
 * there the map uses loaded chunks.
 */
final class GuideShots {
    private static final String[] RECIPES = {"strike_designator", "shahed", "lancet", "cruise_missile", "grad_rockets", "bunker_buster",
            "nuclear_warhead", "icbm", "sam", "interceptor", "substation", "fixed_launcher"};
    /** GIF frames per second: the script step is frame by frame, so GIF is smooth for any fps client without a window. */
    private static final int FPS = 12;
    /** How long to wait until Distant Horizons (if worth) calculates the terrain around (from the entrance to the world), tick. */
    private static final int DH_WARMUP = 4800;
    /** Drawing the map and chunks for a while: the server loads the terrain around it wider than usual. */
    private static final int MAP_RENDER_DISTANCE = 20;
    /** How many chunks to wait around in front of the map: in the cloud in 5 minutes they are loaded about 14 (generation is slow). */
    private static final int MAP_CHUNKS = 13;
    /** The target on the map is so many blocks away from the viewer, inside the circle of loaded chunks; waypoints - along it. */
    private static final int MAP_TARGET = 180;
    /** The card is moved away by the wheel by so many clicks (×1,25 each). */
    private static final int ZOOM_OUT = 2;

    private final Minecraft mc = Minecraft.getInstance();

    // ---------------------------------------------------------------- steps by tick

    private interface Step {
        boolean done(int t);
    }

    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int stepTick, tick;
    /** Drawn footage from the launch. */
    private int frames;

    // ---------------------------------------------------------------- filming location (sets server stream)

    private volatile Vec3 village, view;
    /** Direction from the viewer towards the village and across it (horizontally). */
    private Vec3 along, across;
    /** Map: target outside the village, two waypoints on the side, middle of the view. */
    private Vec3 target, w1, w2, mid;

    // ---------------------------------------------------------------- GIF

    private record Seg(int frames, IntConsumer frame) {}

    private final List<Seg> building = new ArrayList<>();
    private List<Seg> gif;
    private String gifName;
    private int segIndex, segFrame, gifFrame;
    /** The speed of the world in GIF: 1 - as in life, 2 - twice as fast; 0 - GIF screen, the pace of the world does not change. */
    private double pace;
    private long paceNanos;
    private double tickRate = 20;
    /** The mouse is at coordinates GUI, is it visible, does it have a signature and to what frame, the click circle. */
    private double mx, my;
    private boolean cursor;
    private String hint;
    private int hintUntil, ringAt = -100;
    private double ringX, ringY;

    GuideShots() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onFramePre);
        NeoForge.EVENT_BUS.addListener(this::onFramePost);
        NeoForge.EVENT_BUS.addListener(this::onScreenRender);
        NeoForge.EVENT_BUS.addListener(this::onGuiRender);
        plan();
    }

    private void plan() {
        await(60);
        run(() -> {
            mc.options.chatVisibility().set(ChatVisiblity.HIDDEN);
            cmd("gamerule sendCommandFeedback false");
            cmd("time set 5000");
            cmd("weather clear");
            onServer(this::findPlace);
        });
        until("filming locations", 6000, () -> view != null);
        run(() -> {
            along = new Vec3(village.x - view.x, 0, village.z - view.z).normalize();
            across = new Vec3(-along.z, 0, along.x);
            Incoming.LOG.info("SCENARIO guide: village {}, viewer {}", xyz(village), xyz(view));
            stand(view, village);
            give("missile", 6, 20, "map");
        });
        await(200);
        // the server loads the place and calculates the terrain: the workbench and launch wait until its tick becomes normal
        until("server tick is less than 60 ms", 2400, () -> {
            MinecraftServer server = mc.getSingleplayerServer();
            return server != null && server.getAverageTickTimeNanos() < 60_000_000L;
        });
        // sections of chunks around are collected: on llvmpipe the frame immediately after the entrance is one sky
        until("drawings around", 2400, this::worldReady);
        if (only("recipes")) recipes();
        if (only("scope")) scope();
        if (only("remote")) remote();
        if (only("nuke-remote")) nukeRemote();
        if (only("map")) {
            // without Distant Horizons the map draws loaded chunks: wider drawing - relief to the goal and route
            run(() -> mc.options.renderDistance().set(MAP_RENDER_DISTANCE));
            until("chunks around", 6000, () -> mc.level.getChunkSource().getLoadedChunksCount() >= (2 * MAP_CHUNKS + 1) * (2 * MAP_CHUNKS + 1)
                    && (tick >= DH_WARMUP || !net.neoforged.fml.ModList.get().isLoaded("distanthorizons")));
            mapGif();
            run(() -> mc.options.renderDistance().set(12));
            until("shahed hits from the map", 2400, () -> ClientFlights.all().isEmpty());
        }
        if (only("hud")) {
            hud();
            until("salvo hits", 2400, () -> ClientFlights.all().isEmpty());
        }
        if (only("camera")) camera();
        if (only("loiter")) loiter();
        if (only("sam")) sam();
        if (only("launcher")) launcher();
        run(() -> {
            Incoming.LOG.info("SCENARIO done");
            mc.stop();
        });
    }

    /** Survey sections from {@code incoming.guide} (separated by commas: recipes, scope, remote, nuke-remote, map, hud, camera, loiter, sam, launcher); without him - everything. */
    private static boolean only(String section) {
        String list = System.getProperty("incoming.guide");
        return list == null || List.of(list.split(",")).contains(section);
    }

    // ---------------------------------------------------------------- sections

    private void recipes() {
        BlockPos[] table = new BlockPos[1];
        run(() -> {
            table[0] = BlockPos.containing(view.add(along.scale(2)));
            setblock(table[0], "minecraft:crafting_table");
            cmd("recipe give @s *");
        });
        await(10);
        for (String id : RECIPES) {
            run(() -> mc.player.closeContainer());
            await(5);
            run(() -> onServer(server -> ingredients(server, id)));
            await(8);
            run(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(table[0]).add(0, 0.5, 0), Direction.UP, table[0], false)));
            until("workbench", 400, () -> mc.screen instanceof CraftingScreen && mc.player.containerMenu instanceof CraftingMenu);
            run(() -> mc.level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, id)).ifPresentOrElse(
                    r -> mc.gameMode.handlePlaceRecipe(mc.player.containerMenu.containerId, r, false),
                    () -> Incoming.LOG.warn("SCENARIO guide: no recipe {}", id)));
            until("recipe " + id + " at the workbench", 400, () -> mc.player.containerMenu instanceof CraftingMenu m && m.getSlot(0).hasItem());
            run(() -> mouse(2, 2));
            settle();
            shot("recipe-" + id);
        }
        run(() -> mc.player.closeContainer());
        await(5);
        // when closed, the workbench grid returns to inventory: close and clear in one step on the server
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            if (p != null) {
                p.closeContainer();
                p.getInventory().clearContent();
            }
            server.overworld().removeBlock(table[0], false);
        }));
        await(5);
        run(() -> give("missile", 6, 20, "map"));
        await(10);
    }

    /** Binoculars (GIF): RMB - binoculars, wheel - weapon back and forth, LMB - launch missiles around the village. */
    private void scope() {
        run(() -> {
            give("missile", 1, 0, "look");
            look(village.add(0, 0.5, 0));
        });
        await(20);
        gHold(0.8);
        gDo(() -> mc.options.keyUse.setDown(true));
        gHint("RMB (hold)", 2.0);
        gHold(1.6);
        gHint("Mouse wheel", 2.8);
        for (double d : new double[]{1, 1, -1, -1}) {
            gDo(() -> Designator.scroll(d));
            gHold(0.65);
        }
        gHold(0.3);
        gDo(Designator::fire);
        gHint("LMB: launch", 1.8);
        gHold(2.4);
        gDo(() -> mc.options.keyUse.setDown(false));
        gHold(0.8);
        startGif("scope", 1);
        await(10);
    }

    /** Remote control screen (GIF): Shahed, number and spread, target mode, fire - Shahed to where the viewer is looking. */
    private void remote() {
        String count = "incoming.remote.count", spread = "incoming.remote.spread";
        run(() -> {
            give("missile", 6, 20, "look");
            look(village);
        });
        await(10);
        run(() -> {
            mc.setScreen(new RemoteScreen());
            double[] c = center();
            mouse(c[0] + 40, c[1] + 70);
            cursor = true;
        });
        await(10);
        gHold(0.6);
        gMove(() -> widgetWith(WeaponType.DRONE.displayName().getString()), 0.8);
        gHold(0.2);
        gClick(0);
        gHold(0.7);
        gMove(() -> sliderAt(count, 6, 1, 30), 0.8);
        gDrag(() -> sliderAt(count, 3, 1, 30), 0.7);
        gHold(0.5);
        gMove(() -> sliderAt(spread, 20, 0, 150), 0.6);
        gDrag(() -> sliderAt(spread, 10, 0, 150), 0.6);
        gHold(0.5);
        gMove(() -> widgetWith(TargetMode.LOOK.displayName().getString()), 0.8);
        gHold(0.2);
        gClick(0);
        gHold(1.0);
        gMove(() -> widget("incoming.remote.fire"), 0.8);
        gHold(0.4);
        // fire covers the screen: the frame after the click is already the world, guide_images.sh cuts it off
        gClick(0);
        startGif("remote", 0);
        run(() -> {
            cursor = false;
            if (mc.screen != null) mc.setScreen(null);
        });
        await(10);
    }

    /** Remote control with the nuke: power - slider 1–15 kt, detonation height - button (frame {@code guide-nuke-remote.png}). */
    private void nukeRemote() {
        run(() -> {
            cmd("item replace entity @s weapon.mainhand with incoming:strike_designator[incoming:loadout={weapon:\"nuke\",count:1,spread:0,mode:\"look\",nuke:{yield:8,air_burst:true}}]");
            look(village);
        });
        await(10);
        run(() -> mc.setScreen(new RemoteScreen()));
        await(10);
        shot("nuke-remote");
        run(() -> {
            if (mc.screen != null) mc.setScreen(null);
        });
        await(5);
    }

    /**
     * Map: from the remote control screen - “Open guidance map...”, zoom out, move, select a place outside the village, two points
     *  of the route (Shift + LMB), drag the second one, fire; then the card again (key ",") - Shahed on it.
     */
    private void mapGif() {
        run(() -> {
            target = view.add(along.scale(MAP_TARGET));
            w1 = view.add(along.scale(MAP_TARGET * 0.3)).add(across.scale(MAP_TARGET * 0.35));
            w2 = view.add(along.scale(MAP_TARGET * 0.7)).add(across.scale(MAP_TARGET * 0.45));
            mid = view.add(target).scale(0.5).add(across.scale(MAP_TARGET * 0.15));
            give("drone", 3, 20, "map");
            look(village);
            MapTarget.clearRoute();
        });
        await(10);
        // relief of the same type in advance: the map in GIF is not being completed before our eyes
        run(() -> {
            mc.setScreen(new MapScreen(new RemoteScreen()));
            mouse(center()[0], center()[1]);
        });
        await(5);
        run(() -> {
            for (int i = 0; i < ZOOM_OUT; i++) mc.screen.mouseScrolled(mx, my, 0, -1);
            pan(mid);
        });
        until("relief map", 2400, () -> {
            TerrainTiles.Progress p = TerrainTiles.progress();
            if (tick % 100 == 0) Incoming.LOG.info("SCENARIO guide: card {} from {} ({}), {}", p.ready(), p.visible(), p.layers(), TerrainTiles.stats());
            return p.visible() > 0 && p.ready() >= p.visible();
        });
        run(() -> {
            mc.screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
            for (int i = 0; i < ZOOM_OUT; i++) mc.screen.mouseScrolled(mx, my, 0, 1);
            mc.setScreen(new RemoteScreen());
        });
        await(10);
        run(() -> {
            double[] c = center();
            mx = c[0] + 40;
            my = c[1] + 60;
            cursor = true;
        });
        // GIF
        gMove(() -> widget("incoming.remote.open_map"), 1.0);
        gHold(0.3);
        gClick(0);
        gHold(0.6);
        gMove(this::center, 0.6);
        for (int i = 0; i < ZOOM_OUT; i++) {
            gScroll(-1, "Mouse wheel");
            gHold(0.35);
        }
        gDrag(() -> {
            MapProjection p = projection();
            double[] c = center();
            return new double[]{c[0] + (p.cx() - mid.x) * p.k(), c[1] + (p.cz() - mid.z) * p.k()};
        }, 1.2);
        gHold(0.4);
        gMove(() -> at(target), 1.0);
        gHold(0.2);
        gClick(0);
        gHold(1.2);
        gMove(() -> at(w1), 0.9);
        gHint("Shift+LMB", 1.0);
        gDo(() -> addWaypoint(w1));
        gHold(0.7);
        gMove(() -> at(w2), 0.9);
        gHint("Shift+LMB", 1.0);
        gDo(() -> addWaypoint(w2));
        gHold(0.7);
        gDrag(() -> at(w2.add(across.scale(MAP_TARGET * 0.2)).add(along.scale(-MAP_TARGET * 0.07))), 1.0);
        gHold(1.0);
        gMove(() -> widget("incoming.remote.fire"), 1.0);
        gHold(0.3);
        gClick(0);
        // frame without window - about a second of the game: Shahed on the launcher for a few more seconds, the flight on the map is faster than life
        gHold(0.5);
        gDo(() -> {
            mc.setScreen(new MapScreen(new RemoteScreen()));
            double[] c = center();
            mx = c[0] + 150;
            my = c[1] + 70;
            hint = "Key ,";
            hintUntil = gifFrame + FPS * 3 / 2;
        });
        gHold(3.0);
        startGif("map", 0);
        run(() -> {
            cursor = false;
            if (mc.screen != null) mc.setScreen(null);
        });
        await(10);
    }

    /**
     * Projectiles on screen (GIF, twice as fast as life): Shahed salvo and missiles towards the village. GIF - from the approach of missiles: marks
     *  shells are heading towards the village, the list on the right is counting the time, missiles are hitting; the spectator with an empty hand looks at the village
     *  from the direction from which they are flying.
     */
    private void hud() {
        run(() -> {
            give("missile", 1, 0, "look");
            look(village);
            mc.gameRenderer.setRenderHand(false); // then the hand only covers the frame
            cmd(String.format(Locale.ROOT, "incoming salvo drone 3 8 at %.1f %.1f %.1f", village.x, village.y, village.z));
            cmd(String.format(Locale.ROOT, "incoming salvo missile 2 10 at %.1f %.1f %.1f", village.x, village.y, village.z));
        });
        until("missile approach", 2400, () -> ClientFlights.all().stream()
                .anyMatch(f -> f.weapon() == WeaponType.MISSILE && f.etaSeconds(0) <= 10));
        run(() -> {
            mc.player.getInventory().selected = 8;
            ClientFlights.all().stream().filter(f -> f.weapon() == WeaponType.MISSILE).findFirst().ifPresent(f -> {
                Vec3 from = f.position(0).subtract(village);
                look(village.add(new Vec3(from.x, 0, from.z).normalize().scale(40)).add(0, 12, 0));
            });
        });
        await(5);
        gHold(7.0);
        startGif("hud", 2);
    }

    /**
     * Rocket camera (GIF): in the distance - a map with its path, closer 160 m to the player - video from the board, dive and hit. The path is straight
     *  (from and via are on the same line with the village) and comes from the side of the village where the terrain is lower: the missile attack does not see
     *  the slope between it and the target, and from the viewer’s hill it would cling to the trees on its top. Viewer - on the way near the village:
     *  video - the entire approach.
     */
    private void camera() {
        until("empty sky", 2400, () -> ClientFlights.all().isEmpty());
        Vec3[] side = new Vec3[1];
        run(() -> onServer(server -> {
            ServerLevel level = server.overworld();
            int best = Integer.MAX_VALUE;
            Vec3 pick = along;
            for (Vec3 d : new Vec3[]{along, along.reverse(), across, across.reverse()}) {
                int top = Integer.MIN_VALUE;
                for (int t = 16; t <= 200; t += 8) {
                    int x = (int) Math.floor(village.x + d.x * t), z = (int) Math.floor(village.z + d.z * t);
                    level.getChunk(x >> 4, z >> 4);
                    top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
                }
                if (top < best) {
                    best = top;
                    pick = d;
                }
            }
            Vec3 e = village.add(pick.scale(40));
            stand(new Vec3(e.x, surface(level, (int) Math.floor(e.x), (int) Math.floor(e.z)), e.z), village);
            Incoming.LOG.info("SCENARIO guide: rocket approach from {}, terrain on approach to y {}", xyz(village.add(pick.scale(200))), best);
            side[0] = pick;
        }));
        until("spectator seats", 200, () -> side[0] != null);
        await(40);
        until("sketches near the village", 1200, this::worldReady);
        run(() -> {
            Vec3 from = village.add(side[0].scale(400)), via = village.add(side[0].scale(150));
            cmd(String.format(Locale.ROOT, "incoming salvo missile 1 0 at %.1f %.1f %.1f from %.1f %.1f via %.1f %.1f",
                    village.x, village.y, village.z, from.x, from.z, via.x, via.z));
        });
        until("launch", 200, () -> !ClientFlights.all().isEmpty());
        run(ProjectileCamera::cycle);
        // ~2–3 s the map, video from ~200 m to the village, dive and hit
        until("rocket approach", 2400, () -> ClientFlights.all().stream().anyMatch(f -> f.etaSeconds(0) <= 5));
        gHold(6.5);
        startGif("camera", 1);
        run(() -> {
            if (ProjectileCamera.isActive()) ProjectileCamera.exit();
        });
        until("missile hits", 1200, () -> ClientFlights.all().isEmpty());
    }

    /** “Lancet” (GIF, one and a half times faster than life): camera on a circle, LMB around the village - new target, dive and hit. */
    private void loiter() {
        // the camera shows a video closer to 160 m from the player, then a map: the viewer is near the village, the entire Lancet circle is nearby
        run(() -> onServer(server -> {
            ServerLevel level = server.overworld();
            Vec3 e = village.subtract(along.scale(50));
            stand(new Vec3(e.x, surface(level, (int) Math.floor(e.x), (int) Math.floor(e.z)), e.z), village);
        }));
        await(40);
        until("sketches near the village", 1200, this::worldReady);
        // launcher is behind the village, on the other side of the viewer's seat: the path to the village does not go through its hill
        run(() -> {
            Vec3 site = village.add(along.scale(250));
            cmd(String.format(Locale.ROOT, "incoming salvo loiter 1 0 at %.1f %.1f %.1f from %.1f %.1f", village.x, village.y, village.z, site.x, site.z));
        });
        until("launch", 200, () -> !ClientFlights.all().isEmpty());
        run(ProjectileCamera::cycle);
        until("circle \"Lancet\"", 2400, () -> ClientFlights.all().stream().anyMatch(f -> f.phase() == FlightPhase.LOITER));
        // the Lancet camera looks where the player is looking: the sight is on the village
        Supplier<Vec3> aim = () -> village.add(across.scale(12));
        gEach(3.0, () -> look(village));
        gEach(0.6, () -> look(aim.get()));
        gDo(this::retarget);
        gHint("LMB: new target", 1.8);
        gEach(6.0, () -> look(aim.get()));
        startGif("loiter", 1.5);
        run(() -> {
            if (ProjectileCamera.isActive()) ProjectileCamera.exit();
        });
        until("lancet hits", 1200, () -> ClientFlights.all().isEmpty());
    }

    /**
     * SAM (GIF): SAM in the spectator's clearing, missiles from the crater on the side. Shahed fly with a launcher behind the village (“from”) towards the village,
     * ZRK hits them on approach. The viewer is in 25 blocks behind the SAM and away from it: the missiles leave the containers vertically,
     *  and the column of smoke launching above the SAM close up would cover the entire frame. Shahed is launched when all four missiles are on the guides:
     *  in GIF is a SAM salvo, not a reload.
     */
    private void sam() {
        BlockPos[] sam = new BlockPos[1];
        int[] ready = {0};
        // launchers of the previous sections are located before “incoming clear”: the launcher of section hud is at the viewer’s place, and the viewer ZRK
        //  (in 22 blocks behind SAM) would stand close to it - it covered half the frame
        run(() -> cmd("incoming clear"));
        run(() -> onServer(server -> {
            Vec3 at = view.add(across.scale(10));
            sam[0] = BlockPos.containing(at.x, surface(server.overworld(), (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z);
        }));
        until("sAM places", 100, () -> sam[0] != null);
        // clearing 41×41 near SAM and the viewer: under the foliage SAM does not shoot (there is no open sky), and it would block the view of the viewer;
        // flat - at the height of SAM, the slope does not block the viewer ahead (fill - up to 32768 blocks at a time: air in two halves)
        run(() -> {
            BlockPos s = sam[0];
            Vec3 m = Vec3.atBottomCenterOf(s).subtract(along.scale(10)).subtract(across.scale(5));
            int x = (int) Math.floor(m.x), y = s.getY(), z = (int) Math.floor(m.z);
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 20, y, z - 20, x, y + 30, z + 20));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 1, y, z - 20, x + 20, y + 30, z + 20));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block", x - 20, y - 1, z - 20, x + 20, y - 1, z + 20));
        });
        run(() -> {
            BlockPos s = sam[0];
            setblock(s, "incoming:sam");
            setblock(s.east(), "minecraft:hopper[facing=west]{Items:[{Slot:0b,id:\"incoming:interceptor\",count:12}]}");
            Vec3 c = Vec3.atBottomCenterOf(s);
            stand(c.subtract(along.scale(22)).add(across.scale(-12)), c.add(along.scale(80)).add(0, 20, 0));
            mc.player.getInventory().selected = 8;
            mc.gameRenderer.setRenderHand(false); // hand and remote control in hand would cover half the frame
        });
        until("sAM missiles on guides", 1200, () -> {
            onServer(server -> {
                if (server.overworld().getBlockEntity(sam[0]) instanceof SamBlockEntity be) ready[0] = be.ready();
            });
            return ready[0] >= 4;
        });
        run(() -> {
            Vec3 site = village.add(along.scale(300));
            cmd(String.format(Locale.ROOT, "incoming salvo drone 4 20 at %.1f %.1f %.1f from %.1f %.1f", village.x, village.y, village.z, site.x, site.z));
        });
        // launcher Shahed gets up and launches; SAM hits them as soon as they are in the air
        await(40);
        gHold(14.0);
        startGif("sam", 1);
        until("hits", 1200, () -> ClientFlights.all().isEmpty());
        // SAM without an owner hits any projectiles: it would shoot down a rocket from a stationary launcher at launch
        run(() -> {
            setblock(sam[0].east(), "minecraft:air");
            setblock(sam[0], "minecraft:air");
        });
    }

    /**
     * Stationary launcher (GIF): a platform, a launcher with the task of “cruise missile through the village” through the waypoint ahead -
     * , the package immediately turns towards it, and the launch course is known without a test launch (its smoke would be in the frame). Spectator -
     * on the side of the course, next to the launcher lever, launch - lever signal, like a player.
     */
    private void launcher() {
        BlockPos[] pad = new BlockPos[1];
        BlockPos[] lever = new BlockPos[1];
        String[] wall = new String[1];
        run(() -> onServer(server -> {
            Vec3 at = view.add(across.scale(-12));
            pad[0] = BlockPos.containing(at.x, surface(server.overworld(), (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z);
        }));
        until("launcher places", 100, () -> pad[0] != null);
        // site 33×33 (fill - no more than 32768 blocks at a time)
        run(() -> {
            BlockPos s = pad[0];
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", s.getX() - 16, s.getY(), s.getZ() - 16, s.getX(), s.getY() + 30, s.getZ() + 16));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", s.getX() + 1, s.getY(), s.getZ() - 16, s.getX() + 16, s.getY() + 30, s.getZ() + 16));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", s.getX() - 16, s.getY() - 1, s.getZ() - 16, s.getX() + 16, s.getY() - 1, s.getZ() + 16));
            setblock(s, "incoming:fixed_launcher");
            Vec3 c = Vec3.atBottomCenterOf(s), via = c.add(along.scale(120));
            cmd(String.format(Locale.ROOT, "incoming launcher %d %d %d mission missile 1 0 %.1f %.1f %.1f via %.1f %.1f",
                    s.getX(), s.getY(), s.getZ(), village.x, village.y, village.z, via.x, via.z));
            cmd(String.format(Locale.ROOT, "incoming launcher %d %d %d load 4", s.getX(), s.getY(), s.getZ()));
            Vec3 forward = new Vec3(via.x - c.x, 0, via.z - c.z).normalize(), side = new Vec3(-forward.z, 0, forward.x);
            if (side.dot(across) < 0) side = side.scale(-1); // to the spectator's clearing
            Direction d = Direction.getNearest(side.x, 0, side.z);
            lever[0] = s.relative(d);
            wall[0] = "minecraft:lever[face=wall,facing=" + d.getSerializedName();
            setblock(lever[0], wall[0] + ",powered=false]");
            stand(c.add(side.scale(6)).subtract(forward.scale(4)), c.add(forward.scale(4)).add(0, 3, 0));
            mc.player.getInventory().selected = 8;
            mc.gameRenderer.setRenderHand(false);
        });
        // the package rises and turns on course
        await(200);
        until("launcher rendering", 600, this::worldReady);
        gHold(1.2);
        gDo(() -> setblock(lever[0], wall[0] + ",powered=true]"));
        gHint("Lever: signal", 1.8);
        gHold(7.0);
        startGif("launcher", 1);
    }

    // ---------------------------------------------------------------- filming location

    /**
     * Server flow: the village at the spawn and the viewer’s place 90–130 blocks from it - on the ground (not in the water), from where the middle of the
     *  village is visible above the foliage; of these is the highest. If there is no such thing, the highest place is, and the viewer stands
     *  on the pole until the village becomes visible.
     */
    private void findPlace(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos v = level.findNearestMapStructure(StructureTags.VILLAGE, level.getSharedSpawnPos(), 64, false);
        if (v == null) {
            Incoming.LOG.warn("SCENARIO guide: there is no village - I’m filming at the spawn");
            v = level.getSharedSpawnPos();
        }
        // the middle of the village is the bell in the square, and not the beginning of the building (it happens on the edge, in the forest)
        level.getPoiManager().ensureLoadedAndValid(level, v, 80);
        v = level.getPoiManager().findClosest(t -> t.is(PoiTypes.MEETING), v, 80, PoiManager.Occupancy.ANY).orElse(v);
        Vec3 center = new Vec3(v.getX() + 0.5, surface(level, v.getX(), v.getZ()), v.getZ() + 0.5);
        Vec3 best = null, highest = null;
        for (int d = 90; d <= 130; d += 20) {
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                int x = (int) Math.round(center.x + d * Math.cos(a)), z = (int) Math.round(center.z + d * Math.sin(a));
                int y = surface(level, x, z);
                if (!level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;
                Vec3 at = new Vec3(x + 0.5, y, z + 0.5);
                if (highest == null || y > highest.y) highest = at;
                if (sees(level, at, center) && (best == null || y > best.y)) best = at;
            }
        }
        if (best == null && highest != null) {
            for (int up = 4; up <= 32 && best == null; up += 4) {
                Vec3 at = highest.add(0, up, 0);
                if (sees(level, at, center)) {
                    BlockPos base = BlockPos.containing(highest);
                    for (int k = 0; k < up; k++) level.setBlock(base.above(k), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    best = at;
                }
            }
        }
        if (best == null) best = highest != null ? highest : center.add(110, 20, 0);
        village = center;
        view = best;
    }

    /** From the viewer's eyes in {@code at} the middle of the village is visible (the foliage is an obstacle). */
    private static boolean sees(ServerLevel level, Vec3 at, Vec3 village) {
        Vec3 eye = at.add(0, 1.62, 0), aim = village.add(0, 3, 0);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceTo(aim) < 12;
    }

    /** Top of the ground in the column; the chunk is loaded immediately (scenario: you can wait). */
    private static int surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Chunks in 8 around the camera are received and assembled for rendering. */
    private boolean worldReady() {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        int cx = (int) Math.floor(cam.x) >> 4, cz = (int) Math.floor(cam.z) >> 4;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return mc.levelRenderer.hasRenderedAllSections();
    }

    /** The player stands at {@code at} (on the ground there) and looks at {@code toward}. */
    private void stand(Vec3 at, Vec3 toward) {
        float[] r = angles(at.add(0, 1.62, 0), toward);
        onServer(server -> {
            ServerPlayer p = player(server);
            if (p == null) return;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(server.overworld(), at.x, at.y, at.z, r[0], r[1]);
        });
    }

    private void look(Vec3 toward) {
        float[] r = angles(mc.gameRenderer.getMainCamera().getPosition(), toward);
        mc.player.setYRot(r[0]);
        mc.player.setXRot(r[1]);
    }

    private static float[] angles(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        return new float[]{(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))};
    }

    /** Remote control in hand with settings. */
    private void give(String weapon, int count, int spread, String mode) {
        cmd(String.format(Locale.ROOT, "item replace entity @s weapon.mainhand with incoming:strike_designator[incoming:loadout={weapon:\"%s\",count:%d,spread:%d,mode:\"%s\"}]",
                weapon, count, spread, mode));
    }

    /** Server flow: inventory - exactly the ingredients of the recipe (one item per cell), so that the workbench lays them out. */
    private void ingredients(MinecraftServer server, String id) {
        ServerPlayer p = player(server);
        var recipe = server.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, id));
        if (p == null || recipe.isEmpty()) return;
        p.getInventory().clearContent();
        for (Ingredient in : recipe.get().value().getIngredients()) {
            if (in.isEmpty()) continue;
            ItemStack[] items = in.getItems();
            if (items.length > 0) p.getInventory().add(items[0].copyWithCount(1));
        }
        p.inventoryMenu.broadcastChanges();
    }

    // ---------------------------------------------------------------- map

    private double[] center() {
        Screen s = mc.screen;
        return s == null ? new double[]{mx, my} : new double[]{s.width / 2.0, s instanceof MapScreen ? 22 + (s.height - 22 - 56) / 2.0 : s.height / 2.0};
    }

    /** The middle of the screen button according to its signature key. */
    private double[] widget(String key) {
        return widgetWith(Component.translatable(key).getString());
    }

    /** The middle of a screen button whose signature begins with {@code text}. */
    private double[] widgetWith(String text) {
        AbstractWidget w = findWidget(text);
        return w == null ? new double[]{mx, my} : new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
    }

    /**
     * Remote screen slider point for value {@code v} from {@code min…max} (as {@code setValueFromMouse}); slider -
     * by key signature {@code key} to the colon (the language is known only in the game, not when planning steps).
     */
    private double[] sliderAt(String key, int v, int min, int max) {
        AbstractWidget w = findWidget(Component.translatable(key, "").getString().split(":")[0]);
        if (w == null) return new double[]{mx, my};
        return new double[]{w.getX() + 4 + (w.getWidth() - 8) * (v - min) / (double) (max - min), w.getY() + w.getHeight() / 2.0};
    }

    private AbstractWidget findWidget(String text) {
        if (mc.screen != null) {
            for (var c : mc.screen.children()) {
                if (c instanceof AbstractWidget w && w.getMessage().getString().startsWith(text)) return w;
            }
        }
        Incoming.LOG.warn("SCENARIO guide: there is no “{}” button", text);
        return null;
    }

    /** LMB in the projectile camera: new target where the crosshair is pointing (closed camera method: scenario only). */
    private void retarget() {
        try {
            Method m = ProjectileCamera.class.getDeclaredMethod("retarget");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Map projection (closed screen method: script only). */
    private MapProjection projection() {
        try {
            var m = MapScreen.class.getDeclaredMethod("projection");
            m.setAccessible(true);
            return (MapProjection) m.invoke(mc.screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private double[] at(Vec3 world) {
        if (!(mc.screen instanceof MapScreen)) return new double[]{mx, my};
        MapProjection p = projection();
        return new double[]{p.ox() + (world.x - p.cx()) * p.k(), p.oy() + (world.z - p.cz()) * p.k()};
    }

    /** Shift the map so that {@code world} is in the middle (like dragging). */
    private void pan(Vec3 world) {
        double[] c = center();
        MapProjection p = projection();
        double dx = (p.cx() - world.x) * p.k(), dy = (p.cz() - world.z) * p.k();
        mc.screen.mouseClicked(c[0], c[1], 0);
        mc.screen.mouseDragged(c[0] + dx, c[1] + dy, 0, dx, dy);
        mc.screen.mouseReleased(c[0] + dx, c[1] + dy, 0);
    }

    /** The route point under the cursor: Shift+LMB (the real Shift cannot be clicked without a window - the point is set by the same call as at the screen). */
    private void addWaypoint(Vec3 world) {
        ring();
        MapTarget.add(mc.level, new MapTarget.Place(world.x, world.z));
    }

    // ---------------------------------------------------------------- GIF: assembly

    private static int frames(double seconds) {
        return Math.max(1, (int) Math.round(seconds * FPS));
    }

    private void gMove(Supplier<double[]> to, double seconds) {
        int n = frames(seconds);
        double[] from = new double[2], dest = new double[2];
        building.add(new Seg(n, i -> {
            if (i == 0) {
                from[0] = mx;
                from[1] = my;
                double[] d = to.get();
                dest[0] = d[0];
                dest[1] = d[1];
            }
            double k = ease((i + 1) / (double) n);
            mx = from[0] + (dest[0] - from[0]) * k;
            my = from[1] + (dest[1] - from[1]) * k;
        }));
    }

    /** LMB is held where the cursor is, dragged to {@code to} and released (shifting the map or waypoint). */
    private void gDrag(Supplier<double[]> to, double seconds) {
        int n = frames(seconds);
        double[] from = new double[2], dest = new double[2];
        building.add(new Seg(n, i -> {
            Screen s = mc.screen;
            if (s == null) return;
            if (i == 0) {
                from[0] = mx;
                from[1] = my;
                double[] d = to.get();
                dest[0] = d[0];
                dest[1] = d[1];
                s.mouseClicked(mx, my, 0);
            }
            double k = ease((i + 1) / (double) n);
            double x = from[0] + (dest[0] - from[0]) * k, y = from[1] + (dest[1] - from[1]) * k;
            s.mouseDragged(x, y, 0, x - mx, y - my);
            mx = x;
            my = y;
            if (i == n - 1) s.mouseReleased(x, y, 0);
        }));
    }

    private void gClick(int button) {
        building.add(new Seg(1, i -> {
            ring();
            Screen s = mc.screen;
            if (s == null) return;
            s.mouseClicked(mx, my, button);
            if (mc.screen != null) mc.screen.mouseReleased(mx, my, button);
        }));
    }

    private void gScroll(double amount, String label) {
        building.add(new Seg(1, i -> {
            if (mc.screen != null) mc.screen.mouseScrolled(mx, my, 0, amount);
            hint = label;
            hintUntil = gifFrame + frames(0.4);
        }));
    }

    private void gHint(String text, double seconds) {
        building.add(new Seg(1, i -> {
            hint = text;
            hintUntil = gifFrame + frames(seconds);
        }));
    }

    private void gDo(Runnable r) {
        building.add(new Seg(1, i -> r.run()));
    }

    private void gHold(double seconds) {
        building.add(new Seg(frames(seconds), i -> {}));
    }

    /** Each frame of {@code seconds} seconds is {@code r} (Lancet camera sight behind the dot). */
    private void gEach(double seconds, Runnable r) {
        building.add(new Seg(frames(seconds), i -> r.run()));
    }

    /**
     * GIF from steps {@code building}: each frame drawn is a GIF frame. GIF of the world ({@code pace} > 0) goes with the pace of
     * {@code pace} from life: without a window, the client draws several frames per second, so the world slows down ({@code /tick
     * rate}) so that there are 20 per frame pace / FPS tick, - according to fps of the last frames.
     */
    private void startGif(String name, double pace) {
        List<Seg> segs = List.copyOf(building);
        building.clear();
        run(() -> {
            gif = segs;
            gifName = name;
            segIndex = segFrame = gifFrame = 0;
            hint = null; // the signature of the previous GIF counted frames according to its counter
            hintUntil = 0;
            ringAt = -100;
            this.pace = pace;
            paceNanos = 0;
            if (pace > 0) tickRate(20 * pace * 5 / FPS); // without a window - about 5 frames per second, further along the measurement
            Incoming.LOG.info("SCENARIO guide: GIF {} - {} frames", name, segs.stream().mapToInt(Seg::frames).sum());
        });
        // tick wait while GIF goes through the frames
        steps.add(t -> gif == null);
    }

    /** World tempo, tick per second; command - only when there is a change of more than a tenth. */
    private void tickRate(double rate) {
        double r = Math.max(1, Math.min(60, rate));
        if (Math.abs(r - tickRate) <= tickRate * 0.1) return;
        tickRate = r;
        cmd(String.format(Locale.ROOT, "tick rate %.1f", r));
    }

    private static double ease(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    private void ring() {
        ringAt = gifFrame;
        ringX = mx;
        ringY = my;
    }

    // ---------------------------------------------------------------- GIF: footage

    private void onFramePre(RenderFrameEvent.Pre e) {
        frames++;
        if (gif == null || mc.player == null) return;
        if (pace > 0 && gifFrame % 6 == 0) {
            long now = System.nanoTime();
            if (paceNanos != 0) tickRate(6e9 / (now - paceNanos) * 20 * pace / FPS);
            paceNanos = now;
        }
        Seg s = gif.get(segIndex);
        s.frame().accept(segFrame);
        if (++segFrame >= s.frames()) {
            segIndex++;
            segFrame = 0;
        }
        mouse(mx, my);
    }

    private void onFramePost(RenderFrameEvent.Post e) {
        if (gif == null || mc.player == null) return;
        grab(String.format(Locale.ROOT, "gif-%s-%04d", gifName, gifFrame++));
        if (segIndex >= gif.size()) {
            Incoming.LOG.info("SCENARIO guide: GIF {} removed, {} frames", gifName, gifFrame);
            gif = null;
            if (pace > 0) tickRate(20);
            pace = 0;
        }
    }

    /** The key signature in the GIF world (without screen) is at the bottom left, larger than the game text. */
    private void onGuiRender(RenderGuiEvent.Post e) {
        if (gif == null || mc.screen != null || hint == null || gifFrame >= hintUntil) return;
        GuiGraphics g = e.getGuiGraphics();
        // layers HUD go up z, and rectangles and text GUI are drawn with a depth check (the drawing type
        //  itself includes it): the signature under the binoculars is not visible. Depth - reset, then the signature is on top of everything
        g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        g.pose().pushPose();
        g.pose().translate(10, g.guiHeight() - 44, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        int w = mc.font.width(hint);
        g.fill(-4, -4, w + 4, 12, 0xD0101010);
        g.drawString(mc.font, hint, 0, 0, 0xFFFFE070, false);
        g.pose().popPose();
        g.flush();
    }

    /** The cursor, key label and click circle are on top of the screen. */
    private void onScreenRender(ScreenEvent.Render.Post e) {
        if (gif == null || !cursor) return;
        GuiGraphics g = e.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        int age = gifFrame - ringAt;
        if (age >= 0 && age < 6) {
            double r = 3 + age * 1.6;
            int alpha = 255 - age * 40;
            for (int k = 0; k < 28; k++) {
                double a = k * Math.PI * 2 / 28;
                int x = (int) Math.round(ringX + r * Math.cos(a)), y = (int) Math.round(ringY + r * Math.sin(a));
                g.fill(x, y, x + 1, y + 1, alpha << 24 | 0xFFD040);
            }
        }
        if (hint != null && gifFrame < hintUntil) {
            int w = mc.font.width(hint);
            int x = (int) mx + 12, y = (int) my + 14;
            g.fill(x - 3, y - 3, x + w + 3, y + 11, 0xD0101010);
            g.drawString(mc.font, hint, x, y, 0xFFFFE070, false);
        }
        drawCursor(g, (int) Math.round(mx), (int) Math.round(my));
        g.pose().popPose();
    }

    /** Mouse arrow: “X” - outline, “.” - filling. */
    private static final String[] ARROW = {
            "X", "XX", "X.X", "X..X", "X...X", "X....X", "X.....X", "X......X", "X.......X", "X........X",
            "X.....XXXXX", "X..X..X", "X.X X..X", "XX  X..X", "X    X..X", "     X..X", "      XX"};

    private static void drawCursor(GuiGraphics g, int x, int y) {
        for (int row = 0; row < ARROW.length; row++) {
            String line = ARROW[row];
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (c == 'X') g.fill(x + col, y + row, x + col + 1, y + row + 1, 0xFF000000);
                else if (c == '.') g.fill(x + col, y + row, x + col + 1, y + row + 1, 0xFFFFFFFF);
            }
        }
    }

    /** Game mouse at point GUI: the screen draws the mouse pointing over it. Without a mouse event window, there is no mouse event - the field is placed directly. */
    private void mouse(double guiX, double guiY) {
        mx = guiX;
        my = guiY;
        var w = mc.getWindow();
        try {
            Field x = MouseHandler.class.getDeclaredField("xpos"), y = MouseHandler.class.getDeclaredField("ypos");
            x.setAccessible(true);
            y.setAccessible(true);
            x.setDouble(mc.mouseHandler, guiX * w.getScreenWidth() / w.getGuiScaledWidth());
            y.setDouble(mc.mouseHandler, guiY * w.getScreenHeight() / w.getGuiScaledHeight());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------------------------------------------------------- steps

    private void onTick(ClientTickEvent.Post e) {
        if (mc.player == null || mc.level == null) return;
        tick++;
        while (!steps.isEmpty()) {
            if (!steps.peek().done(stepTick++)) return;
            steps.poll();
            stepTick = 0;
        }
    }

    private void run(Runnable r) {
        steps.add(t -> {
            r.run();
            return true;
        });
    }

    private void await(int ticks) {
        steps.add(t -> t >= ticks);
    }

    private void until(String what, int max, BooleanSupplier ok) {
        steps.add(t -> {
            if (ok.getAsBoolean()) return true;
            if (t < max) return false;
            Incoming.LOG.warn("SCENARIO guide: did not wait for {} for {} tick", what, max);
            return true;
        });
    }

    /**
     * Frame after the change: without a window, the client draws ~5 frames per second, ticks go in batches between frames - the snapshot immediately
     *  after the change takes the frame before it. Wait until three frames come out.
     */
    private void settle() {
        int[] start = new int[1];
        run(() -> start[0] = frames);
        steps.add(t -> frames >= start[0] + 3);
    }

    private void shot(String name) {
        run(() -> grab(name));
    }

    private void grab(String name) {
        mc.getToasts().clear(); // “new recipes”, achievements – out of frame
        Screenshot.grab(mc.gameDirectory, "guide-" + name + ".png", mc.getMainRenderTarget(), c -> {});
    }

    private void onServer(Consumer<MinecraftServer> r) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> r.accept(server));
    }

    private ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayer(mc.player.getUUID());
    }

    private static void setblock(BlockPos p, String block) {
        cmd(String.format(Locale.ROOT, "setblock %d %d %d %s", p.getX(), p.getY(), p.getZ(), block));
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Incoming.LOG.info("SCENARIO /{}", c);
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }
}
