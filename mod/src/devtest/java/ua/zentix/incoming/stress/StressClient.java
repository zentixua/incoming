package ua.zentix.incoming.stress;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Load stand player (only devtest, property {@code incoming.stress.client}): enters the server
 *  ({@code --quickPlayMultiplayer}), flies in a circle (moving target), from time to time looks at the camera of the
 *  projectile and redirects it with the LMB, launches it from the remote control (package, like a remote control screen and binoculars). The role of {@code leaver} comes out
 *  mid-strike when the director orders ({@link StressDirector#LEAVE}) and returns. Once in 5 writes {@code STRESSC}: fps, projectiles on the client and in HUD, errors.
 *  When the server stops, the client exits.
 */
@Mod(value = Incoming.MOD_ID, dist = Dist.CLIENT)
public final class StressClient {
    private static final String ROLE = System.getProperty("incoming.stress.client");
    private static final String SERVER = System.getProperty("incoming.stress.server", "127.0.0.1");

    private final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
    private int warnings, errors;
    /** tick from the moment you enter the world (they also go when the client is outside the server). */
    private int tick = -1;
    private int away = -1;
    private int disconnectedFor;
    private int joins;
    /** The director ordered to exit: the exit is in the next tick of the client, not from the packet handler. */
    private volatile boolean leave;

    public StressClient(IEventBus modBus) {
        if (ROLE == null) return;
        LogWatch.install(problems, () -> warnings++, () -> errors++);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onChat);
    }

    private void onChat(ClientChatReceivedEvent.System e) {
        if (!StressDirector.LEAVE.equals(e.getMessage().getString())) return;
        e.setCanceled(true);
        if ("leaver".equals(ROLE)) leave = true;
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (tick < 0 && p == null) return;
        if (tick < 0) log("in the world, the role of %s", ROLE);
        tick++;
        if (p == null) {
            offline(mc);
            return;
        }
        if (disconnectedFor > 0) {
            joins++;
            log("back in the world (entries %d)", joins);
            disconnectedFor = 0;
        }
        fly(p);
        if ("host".equals(ROLE)) host(mc);
        if (tick % 600 == 300) {
            ProjectileCamera.cycle();
            log("camera: active=%b viewing=%b", ProjectileCamera.isActive(), ProjectileCamera.isViewing());
        }
        if (tick % 600 == 380 && ProjectileCamera.isViewing()) {
            // LMB in the projectile chamber: redirect to what is under the crosshair
            KeyMapping.click(mc.options.keyAttack.getKey());
            log("LMB in the chamber");
        }
        if (tick % 600 == 480 && ProjectileCamera.isActive()) ProjectileCamera.exit();
        if (leave) {
            leave = false;
            log("i'm coming out in the middle of a beat");
            away = tick;
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            return;
        }
        if (tick % 100 == 0) stat(mc);
    }

    private void offline(Minecraft mc) {
        disconnectedFor++;
        if (away >= 0 && tick - away >= 300) {
            away = -1;
            log("i'm going back");
            ServerData data = new ServerData("stress", SERVER, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(SERVER), data, false, null);
            return;
        }
        // kicked out not according to the script - the server finished (or crashed): exit
        if (away < 0 && mc.screen instanceof DisconnectedScreen && disconnectedFor > 40) {
            log("the server closed the connection, I exit; warn %d err %d", warnings, errors);
            for (String s : problems) log("problem: %s", s);
            mc.stop();
        }
    }

    /** Fly in a circle with a radius of ~40 blocks at the altitude where the server caught you: the target is moving. */
    private void fly(LocalPlayer p) {
        if (!p.mayFly()) return;
        if (!p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        }
        if ("host".equals(ROLE)) return; // the owner is standing at the launcher
        Minecraft.getInstance().options.keyUp.setDown(!ProjectileCamera.isActive());
        if (!ProjectileCamera.isActive()) p.setYRot(p.getYRot() + 2f);
    }

    /** The owner also launches from the remote control: an MLRS salvo at the player (remote screen) and missiles at a point (binoculars). */
    private void host(Minecraft mc) {
        if (tick == 2600) {
            Loadout l = new Loadout(WeaponType.ROCKET, 30, 150, TargetMode.PLAYER, "Friend2", Loadout.Nuke.DEFAULT);
            PacketDistributor.sendToServer(new C2S.Fire(l, Optional.empty(), Optional.empty(), Waypoints.NONE, Optional.empty()));
            log("remote control: MLRS 30 to Friend2");
        }
        if (tick == 2800) {
            Vec3 at = mc.player.position().add(0, -mc.player.getY() + 70, 300);
            Loadout l = new Loadout(WeaponType.MISSILE, 10, 60, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
            PacketDistributor.sendToServer(new C2S.Fire(l, Optional.of(new C2S.AimHint(C2S.AimHint.POINT, at, 0, Vec3.ZERO)), Optional.empty(), Waypoints.NONE, Optional.empty()));
            log("binoculars: 10 point-by-point missiles");
        }
        if (tick == 4400) {
            Loadout l = new Loadout(WeaponType.LOITER, 30, 150, TargetMode.PLAYER, "Friend1", Loadout.Nuke.DEFAULT);
            PacketDistributor.sendToServer(new C2S.Fire(l, Optional.empty(), Optional.empty(), Waypoints.NONE, Optional.empty()));
            log("remote control: barrage 30 to Friend1");
        }
    }

    private void stat(Minecraft mc) {
        int entities = 0;
        for (var en : mc.level.entitiesForRendering()) {
            if (en instanceof StrikeProjectile) entities++;
        }
        Runtime rt = Runtime.getRuntime();
        log("stat t=%d fps %d | shells from the client %d, in HUD %d | camera %b | particles %s | heap %d MB | warn %d err %d", tick, mc.getFps(), entities,
                ClientFlights.all().size(), ProjectileCamera.isActive(),
                mc.particleEngine.countParticles() + " + effects " + ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE.count(),
                (rt.totalMemory() - rt.freeMemory()) >> 20, warnings, errors);
    }

    private static void log(String fmt, Object... args) {
        Incoming.LOG.info("STRESSC " + String.format(Locale.ROOT, fmt, args));
    }
}
