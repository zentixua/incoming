package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.client.hud.Alerts;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.map.MapProjection;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.client.screen.MapScreen;
import ua.zentix.incoming.client.screen.NuclearReviewScreen;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.client.sound.ClientSounds;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Two real clients: scripted screen actions and passive receiver observations, without desktop input. */
final class ReleaseCheckClient {
    private final Minecraft mc = Minecraft.getInstance();
    private final Path directory = Path.of(System.getProperty("incoming.check.dir"));
    private int tick, lastConnect = -1000, phase = -1, phaseStart, reviewStart = -1;
    private boolean fired;
    private MapScreen pendingMap;
    private int fireAt;
    private String pendingShot;
    private int lastImpact = -1;

    ReleaseCheckClient() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent event) -> log("CHAT " + phase + " " + event.getMessage()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) -> {
            if (pendingShot != null) {
                Screenshot.grab(mc.gameDirectory, pendingShot + ".png", mc.getMainRenderTarget(), ignored -> {});
                pendingShot = null;
            }
        });
    }

    private void tick(ClientTickEvent.Post event) {
        tick++;
        if (Files.exists(directory.resolve("done")) || Files.exists(directory.resolve("failed"))) { mc.stop(); return; }
        if (mc.level == null || mc.player == null) {
            if (mc.screen != null && !(mc.screen instanceof ConnectScreen) && tick - lastConnect > 200) {
                lastConnect = tick;
                String address = "127.0.0.1:" + System.getProperty("incoming.mp.port");
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                        new ServerData("release-check", address, ServerData.Type.OTHER), false, null);
            }
            return;
        }
        if (tick % 10 != 0 || !Files.exists(directory.resolve("phase.properties"))) return;
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(directory.resolve("phase.properties"))) { p.load(reader); }
        catch (IOException e) { throw new IllegalStateException(e); }
        int next = Integer.parseInt(p.getProperty("phase"));
        if (next != phase) {
            phase = next;
            phaseStart = tick;
            fired = false;
            reviewStart = -1;
            lastImpact = -1;
            log("PHASE " + phase + " " + p);
        }
        boolean alpha = mc.player.getGameProfile().getName().equals("Alpha");
        if (alpha && phase == 0) {
            int elapsed = tick - phaseStart;
            if (elapsed == 20) mc.setScreen(new ua.zentix.incoming.client.screen.IncomingConfigScreen(
                    net.neoforged.fml.ModList.get().getModContainerById(Incoming.MOD_ID).orElseThrow(), null));
            if (elapsed == 40) { log("MP_CONFIG " + ConfigScreenCheck.messages(mc.screen)); shot("config-multiplayer"); }
            if (elapsed == 60) mc.setScreen(null);
        }
        if (!alpha && phase >= 8 && tick - phaseStart > 180 && tick % 40 == 0)
            PacketDistributor.sendToServer(new C2S.NuclearKey());
        if (alpha && !fired && Boolean.parseBoolean(p.getProperty("ready"))) {
            fired = true;
            mapFire(p);
        }
        if (pendingMap != null && tick >= fireAt) {
            shot("map-" + phase);
            call(pendingMap, "fire", new Class<?>[0]);
            pendingMap = null;
        }
        if (alpha && mc.screen instanceof NuclearReviewScreen review) {
            S2C.NuclearReview data = (S2C.NuclearReview) field(review, "review");
            // Exercise the real server's three-second authorization; no host keyboard events are generated.
            if (reviewStart < 0) { reviewStart = tick; PacketDistributor.sendToServer(new C2S.NuclearAction(data.id(), C2S.NuclearAction.BEGIN)); }
            if (tick - reviewStart >= 80) PacketDistributor.sendToServer(new C2S.NuclearAction(data.id(), C2S.NuclearAction.COMMIT));
        }
        int age = tick - phaseStart;
        if (mc.screen == null) {
            net.minecraft.world.phys.Vec3 look = new net.minecraft.world.phys.Vec3(Double.parseDouble(p.getProperty("x")), 64, 0);
            double nearest = Double.POSITIVE_INFINITY;
            int impact = Integer.parseInt(p.getProperty("impact"));
            if (impact < 0) for (var track : FlightTracks.all()) {
                if (track.confirmedEnd()) continue;
                double[] xyz = new double[3]; track.at(track.lastTick(), xyz);
                var point = new net.minecraft.world.phys.Vec3(xyz[0], xyz[1], xyz[2]);
                double distance = point.distanceToSqr(mc.player.position());
                if (distance < nearest) { nearest = distance; look = point; }
            }
            var delta = look.subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
            if (impact >= 0 && lastImpact < 0) { shot("impact-" + phase); lastImpact = impact; }
        }
        if (age % 20 == 0) {
            int alarms = 0, warnings = 0;
            for (var warning : ClientNuclear.warnings()) { warnings++; if (warning.alarm()) alarms++; }
            log("STATE " + phase + " " + age + " ownerFlights=" + ClientFlights.all().size() + " warnings=" + warnings
                    + " alarms=" + alarms + " banner=" + field(null, Alerts.class, "bannerLeft")
                    + " lastSiren=" + field(null, Alerts.class, "lastSiren")
                    + " tracks=" + FlightTracks.all().size() + " sound=" + ClientSounds.describe());
            for (var track : FlightTracks.all()) {
                double[] at = new double[3]; track.at(track.lastTick(), at);
                log("TRACK " + phase + " " + track.id + " " + track.weapon + " bomber=" + track.bomber + " ended=" + track.confirmedEnd()
                        + " xyz=" + java.util.Arrays.toString(at));
            }
        }
        if (age % 100 == 0) shot("phase-" + phase + "-" + age);
    }

    private void mapFire(Properties p) {
        WeaponType weapon = WeaponType.parse(p.getProperty("weapon"));
        double x = Double.parseDouble(p.getProperty("x"));
        RemoteScreen remote = new RemoteScreen();
        field(remote, "loadout", new Loadout(weapon, 1, 0, TargetMode.MAP, "", new Loadout.Nuke(1, true, Boolean.parseBoolean(p.getProperty("carrier")))));
        MapTarget.reset();
        MapScreen map = new MapScreen(remote);
        mc.setScreen(map);
        field(null, MapScreen.class, "viewX", x);
        field(null, MapScreen.class, "viewZ", -600.0);
        field(null, MapScreen.class, "scale", 0.06);
        MapProjection projection = (MapProjection) call(map, "projection", new Class<?>[0]);
        int[] target = projection.at(x, 0), launch = projection.at(x, -800);
        call(map, "select", new Class<?>[]{double.class, double.class}, (double) target[0], (double) target[1]);
        if (phase < 8) {
            field(map, "pickingLaunch", true);
            map.mouseClicked(launch[0], launch[1], 0);
            map.mouseReleased(launch[0], launch[1], 0);
        }
        if (weapon == WeaponType.BUNKER) {
            for (double[] waypoint : new double[][]{{x - 900, -1000}, {x, -400}, {x, -60}}) {
                int[] pixel = projection.at(waypoint[0], waypoint[1]);
                call(map, "addWaypoint", new Class<?>[]{double.class, double.class}, (double) pixel[0], (double) pixel[1]);
            }
        }
        log("MAP " + phase + " launch=" + MapTarget.launchPoint(mc.level) + " via=" + MapTarget.waypoints(mc.level));
        pendingMap = map;
        fireAt = tick + 20;
    }

    private void shot(String name) {
        pendingShot = name;
    }

    private void log(String line) {
        line = "time=" + System.currentTimeMillis() + " tick=" + tick + " " + line;
        Incoming.LOG.info("RELEASE_CHECK {}", line);
        try { Files.writeString(directory.resolve(mc.getUser().getName() + ".log"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static Object field(Object target, String name) { return field(target, target.getClass(), name); }
    private static Object field(Object target, Class<?> type, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static void field(Object target, String name, Object value) { field(target, target.getClass(), name, value); }
    private static void field(Object target, Class<?> type, String name, Object value) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
