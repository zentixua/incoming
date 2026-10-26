package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.scenario.trailer.CineCamera.Path;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Mod trailer filmed in the game: shot-based scenario (like the operator camera), dawn over a village,
 * gunner with binoculars, remote, Shahed launcher behind them, acceleration and booster separation, Shahed
 * chase, impact; cruise missile from the ground and its own camera, B-2 and bunker buster, sunset salvos,
 * ICBM and nuclear strike, mushroom from afar, black rain.
 * <p>
 * Each shot positions the camera and waits for world loading (normal game speed), then for its event
 * (projectile approach, detonation), then records: the game slows to render speed ({@link Recorder}), frames
 * follow game time. Frames and sound journal: {@code run/scenario/trailer/}; edit:
 * {@code tools/trailer/edit.py}. Run: {@code tools/trailer/record.sh}.
 */
public final class Trailer {
    private static final Set<ResourceLocation> ALWAYS_LAYERS = Set.of(Incoming.id("flash"), Incoming.id("nuke_flash"));

    private final Minecraft mc = Minecraft.getInstance();
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private Recorder rec;
    private boolean started;

    /** Mod interface in the frame (sight, projectile camera, Geiger counter); flashes are always visible. */
    private boolean hud;
    /** The player is seen as a gunner; otherwise he follows the camera invisible, so that the world around it is loaded. */
    private boolean actor = true;
    @Nullable
    private Shot recording;
    private float tickRate = 20;
    private long frameClock;
    /** This rendering frame is a video frame (the tick share is set to its moment). */
    private boolean frameReady;
    /** Attempts to set the window to the frame size ({@link #exactFrame}). */
    private int frameTries;
    private int frameCount;

    // filming locations (searched at the beginning on the server)
    private Vec3 village = Vec3.ZERO;
    private Vec3 post = Vec3.ZERO;
    private Vec3 toPost = new Vec3(0, 0, 1);
    private Vec3 side = new Vec3(1, 0, 0);
    private Vec3 viewpoint = Vec3.ZERO;
    /** The second tower (the explosion is removed from it). */
    private Vec3 watch = Vec3.ZERO;
    /** From the village to the nuclear strike observation point. */
    private Vec3 toView = new Vec3(0, 0, -1);
    /** Game time for detonation (-1 - hasn’t happened yet): the world freezes while plans are being prepared, the client ticks and then. */
    private long detonationTime = -1;
    /** The time at which the last shot ended: the camera between shots stands there. */
    private double idleTime;
    /** The projectile camera showed video from the board at the last tick (transition from the map - mark “video” for editing). */
    private boolean wasViewing, cameraWasActive, closeMarked;
    /** Drawing from options.txt (record.sh): the plan from the side raises its own and then returns this one. */
    private int renderDistance;
    private static final int ONBOARD_RENDER_DISTANCE = 24;
    /** From the side closer to the target, the ground is also visible under the shaders: the montage takes the video from the “close” mark. */
    private static final double CLOSE_RANGE = 150;
    private java.util.Map<Integer, String> seen = java.util.Map.of();
    private final java.util.Set<Integer> released = new java.util.HashSet<>();
    /** Where the gunner stands under the black rain (selected while he is invisible loading the place). */
    private Vec3 falloutSpot = Vec3.ZERO;
    /** tick after detonation, when precipitation falls on {@link #falloutSpot} (not less than 3000). */
    private long falloutRain = 3000;

    private interface Step {
        /** @return step completed */
        boolean tick();
    }

    public Trailer() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::beforeFrame);
        NeoForge.EVENT_BUS.addListener(this::afterFrame);
        NeoForge.EVENT_BUS.addListener(CineCamera::angles);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, CineCamera::fov);
        NeoForge.EVENT_BUS.addListener(this::layer);
        NeoForge.EVENT_BUS.addListener(this::hideActor);
        NeoForge.EVENT_BUS.addListener(this::hideHand);
        NeoForge.EVENT_BUS.addListener(this::hideName);
        script();
    }

    // ================================================================================ Scenario

    private void script() {
        then(this::exactFrame);
        run(() -> {
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("gamerule doMobSpawning false");
            cmd("weather clear");
            cmd("time set 23900");
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(30);
            c.missileFlightTime.set(20);
            c.bomberFlightTime.set(20);
            c.nukeFlightTime.set(600);
            c.loiterTime.set(8);
            c.siren.set(false);
        });
        onServer(this::findLocations);
        waitTicks(5);
        run(this::placeActor);
        waitTicks(60);

        // --- dawn over the village
        shot("dawn").length(200).camera(() -> {
            Vec3 a = village.add(toPost.scale(-70)).add(side.scale(-30)).add(0, 55, 0);
            Vec3 b = village.add(toPost.scale(40)).add(side.scale(10)).add(0, 38, 0);
            Vec3 look = village.add(toPost.scale(-40)).add(0, 5, 0);
            return CineCamera.glide(a, b, 200, () -> look, 60);
        }).hidden().farView();

        // --- gunner: remote control, binoculars, launch
        run(() -> cmd("time set 1000"));
        run(this::placeActor);
        run(() -> cmd("give @s incoming:strike_designator[incoming:loadout={weapon:\"drone\",count:3,spread:6}]"));
        // targets in the village: target markers in HUD are signed with their names (stands - no need for live players in the frame)
        run(() -> {
            // targets - along the edges of the village: marks and dotted lines in the gunner’s frame do not merge with the main target
            summonTarget("ENOTzRPG", target(side.scale(26)).add(toPost.scale(4)));
            summonTarget("WallyFillmark", target(side.scale(-24)).add(toPost.scale(-3)));
        });
        waitTicks(30);
        shot("remote").length(70).player(t -> new Pose(Vec3.ZERO, yawTo(post, village), 8, 0, 70)).hud()
                .cue(0, () -> mc.setScreen(new RemoteScreen()))
                .cue(69, () -> mc.setScreen(null));
        shot("operator").length(110).camera(() -> {
            Vec3 eye = post.add(0, 1.4, 0);
            double start = Math.toDegrees(Math.atan2(toPost.x, toPost.z)) + 180 - 40;
            return CineCamera.orbit(() -> eye, 3.6, 0.1, start, 0.55, 55);
        }).cue(15, () -> mc.options.keyUse.setDown(true));
        shot("scope").length(80).hud().player(t -> {
            float yaw = yawTo(post, village), pitch = pitchTo(post.add(0, 1.62, 0), village.add(0, 2, 0));
            double s = CineCamera.smooth(t / 60);
            return new Pose(Vec3.ZERO, yaw - 14 * (float) (1 - s), pitch - 3 * (float) (1 - s), 0, 70);
        }).cue(0, () -> mc.options.keyUse.setDown(true)).cue(76, Designator::fire);
        run(() -> {
            mc.options.keyUse.setDown(false);
            cmd("incoming salvo drone 2 0 @e[tag=trailer_ENOTzRPG,limit=1]");
            cmd("incoming drone @e[tag=trailer_WallyFillmark,limit=1]");
        });

        // --- launcher behind: lifting the package, igniting, launch rail exit
        shot("launch_drone").after(() -> launcher(WeaponType.DRONE) != null, 100).noPrep().length(190).speed(0.5).camera(() -> {
            LauncherEntity l = launcher(WeaponType.DRONE);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // from the side and slightly behind: you can see the rise of the package, the launch cloud and Shahed’s departure into the sky
            Vec3 a = ground(at.add(right.scale(11)).add(fwd.scale(-6))).add(0, 1.5, 0);
            Vec3 b = ground(at.add(right.scale(9)).add(fwd.scale(-3))).add(0, 1.9, 0);
            return CineCamera.dolly(a, b, 190, () -> at.add(fwd.scale(4)).add(0, 2.6, 0), 64);
        }).endWhen(() -> {
            // shahed moved away from the launcher - right behind him while he is in a busy world
            DroneEntity d = newest(DroneEntity.class);
            LauncherEntity l = launcher(WeaponType.DRONE);
            return d != null && l != null && d.distanceTo(l) > 45;
        }, 0);
        // acceleration and separation of the accelerator - slowly, closely, from above (ground in the frame - speed is visible)
        shot("boost").after(() -> newest(DroneEntity.class) != null, 200).noPrep().length(56).speed(0.4).hidden()
                .camera(() -> chaseOf(newest(DroneEntity.class), 7.5, 1.6, 2.6, 6, 0, 58));
        // shahed on the route
        shot("cruise").after(() -> newest(DroneEntity.class) != null, 200).noPrep().length(110).hidden()
                .camera(() -> chaseOf(newest(DroneEntity.class), 8, 3, -3.5, 22, 6, 58));
        // through the eyes of a gunner: the flock comes from behind and goes towards the village; target marks with shell numbers, dotted lines
        run(this::placeActor);
        shot("targets").length(110).speed(0.6).hud()
                .player(t -> new Pose(Vec3.ZERO, yawTo(post, village) + 6 - (float) CineCamera.smooth(t / 110) * 6,
                        pitchTo(post.add(0, 1.62, 0), village.add(0, 10, 0)), 0, 46))
                .when(() -> nearest(DroneEntity.class, post, 90) != null, 2400);
        // attack on the village: from a hillock near the outer houses, in slow motion
        shot("impact_drone").length(190).speed(0.5).hidden().camera(() -> {
            Vec3 from = ground(village.add(toPost.scale(30)).add(side.scale(14))).add(0, 7, 0);
            // while Shahed is far away, it’s narrow so that they don’t get lost as dots in the sky; to strike - wide
            return CineCamera.track(from, smoothFocus(() -> nearest(DroneEntity.class, village, 300), village.add(0, 3, 0), 0.2),
                    rangeFov(from, () -> nearest(DroneEntity.class, village, 300), 64, 24, 45));
        }).when(() -> nearest(DroneEntity.class, village, 170) != null, 2400)
                .endWhen(() -> nearest(DroneEntity.class, village, 600) == null, 70);

        // --- cruise missile: launch, ground strike; the second - through the eyes of a rocket
        run(this::placeActor);
        waitTicks(40);
        run(() -> fire("missile", target(side.scale(14))));
        shot("launch_missile").after(() -> launcher(WeaponType.MISSILE) != null, 100).noPrep().length(170).camera(() -> {
            LauncherEntity l = launcher(WeaponType.MISSILE);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // front-side: rocket launch rail exit with a rail and goes over the camera
            Vec3 from = ground(at.add(fwd.scale(12)).add(right.scale(-7))).add(0, 2.4, 0);
            return CineCamera.track(from, smoothFocus(() -> nearest(CruiseMissileEntity.class, at, 150), at.add(0, 2.5, 0), 0.35), 68);
        });
        shot("impact_missile").length(90).speed(0.35).hidden().camera(() -> {
            Vec3 t = target(side.scale(14));
            Vec3 from = ground(t.add(toPost.scale(28)).add(side.scale(-16))).add(0, 4, 0);
            return CineCamera.track(from, smoothFocus(() -> nearest(CruiseMissileEntity.class, t, 300), t.add(0, 3, 0), 0.5),
                    rangeFov(from, () -> nearest(CruiseMissileEntity.class, t, 300), 62, 28, 45));
        }).when(() -> nearest(CruiseMissileEntity.class, target(side.scale(14)), 260) != null, 2400)
                .endWhen(() -> nearest(CruiseMissileEntity.class, target(side.scale(14)), 60) == null, 45);
        // gunner near the village: the target is in the video zone (simulation range), the missile is coming from afar - map first
        run(() -> placeActor(operatorNearVillage(), village));
        waitTicks(40);
        run(() -> fire("missile", target(side.scale(-4)).add(toPost.scale(-20))));
        shot("missile_camera").after(() -> !ua.zentix.incoming.client.hud.ClientFlights.all().isEmpty(), 400)
                .prepare(() -> {
                    // in the morning the rays of light Complementary (volumetric light) are many times stronger than at noon, and on the way to the sun
                    //  flood the entire frame from the side of the pinkish-white pall - the video is shot at noon (then again morning)
                    cmd("time set 6000");
                    // shader pack fog in range - from rendering: with 24 chunks the ground is visible earlier
                    renderDistance = mc.options.renderDistance().get();
                    mc.options.renderDistance().set(ONBOARD_RENDER_DISTANCE);
                    if (!ProjectileCamera.isActive()) ProjectileCamera.cycle();
                })
                // operator's map while the rocket is still being drawn: recording - ~2 s before switching to video
                .when(() -> onMap() && missileRange() < 700, 3000)
                // map, on-board video: slide, dive, “signal lost” and hit plan (fly-by)
                // slow motion: the rocket in the world (and therefore on-board video) - only the last ~250 blocks, this is ~20 tick
                .length(220).speed(0.2).hud().projectileCamera().readyChunks(ONBOARD_RENDER_DISTANCE - 2)
                .cueEnd(() -> {
                    ProjectileCamera.exit();
                    cmd("time set 1000");
                    mc.options.renderDistance().set(renderDistance);
                });

        // --- "Lancet": catapult at the post, circle over the village, dive
        run(this::placeActor);
        // launcher Shahed and the missiles are removed: the catapult will take their place, and only it will be in the frame
        run(() -> cmd("kill @e[type=incoming:launcher]"));
        waitTicks(40);
        run(() -> fire("loiter", target(side.scale(8)).add(toPost.scale(-10))));
        shot("loiter_launch").after(() -> launcher(WeaponType.LOITER) != null, 100).noPrep().length(160).speed(0.5).camera(() -> {
            LauncherEntity l = launcher(WeaponType.LOITER);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // on the side of the catapult: jerk along the rail, open the wings, go up
            Vec3 from = ground(at.add(right.scale(8)).add(fwd.scale(2))).add(0, 1.9, 0);
            return CineCamera.track(from, smoothFocus(() -> newest(LoiterEntity.class), at.add(fwd.scale(3)).add(0, 2, 0), 0.35), 62);
        }).endWhen(() -> {
            LoiterEntity e = newest(LoiterEntity.class);
            LauncherEntity l = launcher(WeaponType.LOITER);
            return e != null && l != null && e.distanceTo(l) > 40;
        }, 0);
        // the player is closer to the village: the Lancet circle goes beyond the simulation range from the post, and the projectile would go
        //  into virtual flight - it would disappear from the frame before the dive
        run(() -> placeActor(operatorNearVillage(), village));
        shot("loiter_strike").noPrep().length(260).speed(0.8)
                // behind the Lancet closely: a circle over the village, a dive and an explosion right in front of the camera (the houses are covered from the ground of the dive)
                .camera(() -> chaseOf(nearest(LoiterEntity.class, target(side.scale(8)).add(toPost.scale(-10)), 200), 5.5, 1.8, 2.2, 8, 0, 60))
                .when(() -> nearest(LoiterEntity.class, village, 120) instanceof LoiterEntity e && e.flightPhase() == FlightPhase.LOITER, 1600)
                .endWhen(() -> nearest(LoiterEntity.class, village, 300) == null, 50);

        // --- B-2: from the bottom-back under the belly - the compartment doors open, the bomb goes down (slowly). B-2 comes
        //  from behind the shooter and flies out of the world before being released; the invisible man is on course 150 blocks to the target
        //  (and the camera is waiting there) - the ticking chunks around him cover both the opening of the doors and the reset. The client
        //  sees the reset in phase EGRESS.
        run(() -> placeHidden(bayWatch(), bayTarget()));
        shot("bomb_bay").onReady(() -> fire("bunker", bayTarget())).length(400).speed(0.25).hidden()
                .camera(() -> CineCamera.chase(() -> nearest(BomberEntity.class, bayTarget(), 900), bayWatch(), toPost.scale(-1),
                        26, -7, 9, 14, 0, 58))
                .when(() -> nearest(BomberEntity.class, bayTarget(), 900) instanceof BomberEntity b && b.flightPhase() != FlightPhase.EGRESS, 2400)
                .endWhen(() -> nearest(BomberEntity.class, bayTarget(), 900) instanceof BomberEntity b && b.flightPhase() == FlightPhase.EGRESS, 16);

        // --- B-2 and a concrete-piercing bomb: from a height near the village - flying, falling, drilling, underground explosion
        shot("bomber").onReady(() -> fire("bunker", target(side.scale(-12)))).length(260).speed(0.75).hidden().camera(() -> {
            Vec3 t = target(side.scale(-12));
            Vec3 from = ground(t.add(side.scale(-32)).add(toPost.scale(20))).add(0, 8, 0);
            // B-2 on 170 blocks - narrow so that it is large; bomb near the ground - wide, the entire gap is in the frame
            return CineCamera.track(from, smoothFocus(this::bomberFocus, t.add(0, 8, 0), 0.3), rangeFov(from, this::bomberSubject, 66, 16, 55));
        }).when(() -> bomberFocus() != null, 2400);

        // --- MLRS: package of 40 pipes at the post, burst burst; explosions cover the village
        run(this::placeActor);
        waitTicks(40);
        run(() -> fire("salvo rocket 24 34", village.add(side.scale(-6))));
        shot("rocket_launch").after(() -> launcher(WeaponType.ROCKET) != null, 100).noPrep().length(150).speed(0.7).camera(() -> {
            LauncherEntity l = launcher(WeaponType.ROCKET);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // low on the side and in front of the package: pipes, ignition, shells go above the camera in arcs
            Vec3 a = ground(at.add(right.scale(9)).add(fwd.scale(5))).add(0, 2.2, 0);
            Vec3 b = ground(at.add(right.scale(8)).add(fwd.scale(8))).add(0, 2.0, 0);
            return CineCamera.dolly(a, b, 150, () -> at.add(fwd.scale(3)).add(0, 2.2, 0), 72);
        });
        shot("rocket_impact").noPrep().length(200).speed(0.6).camera(() -> {
            Vec3 t = village.add(side.scale(-6));
            Vec3 a = ground(t.add(toPost.scale(38)).add(side.scale(40))).add(0, 11, 0);
            Vec3 b = ground(t.add(toPost.scale(34)).add(side.scale(30))).add(0, 9, 0);
            return CineCamera.dolly(a, b, 200, () -> t.add(0, 3, 0), 60);
        }).when(() -> nearest(RocketEntity.class, village, 90) != null, 600)
                .endWhen(() -> nearest(RocketEntity.class, village, 600) == null, 60);

        // --- volleys at sunset
        run(() -> {
            cmd("time set 12650");
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.droneFlightTime.set(8);
            c.missileFlightTime.set(6);
        });
        shot("salvo").onReady(() -> fire("salvo drone 8 24", village)).length(280).hidden().camera(() -> {
            // the village in the frame is motionless, the camera zooms in slightly: a flock enters the frame and covers the houses
            Vec3 a = ground(village.add(toPost.scale(95)).add(side.scale(-25))).add(0, 22, 0);
            Vec3 b = ground(village.add(toPost.scale(80)).add(side.scale(-18))).add(0, 19, 0);
            return CineCamera.dolly(a, b, 280, () -> village.add(0, 8, 0), 58);
        }).when(() -> nearest(DroneEntity.class, village, 330) != null, 2400)
                .endWhen(() -> nearest(DroneEntity.class, village, 800) == null, 120);
        shot("salvo_missiles").onReady(() -> fire("salvo missile 5 26", village)).length(170).speed(0.6).hidden().camera(() -> {
            Vec3 a = ground(village.add(toPost.scale(70)).add(side.scale(45))).add(0, 30, 0);
            Vec3 b = ground(village.add(toPost.scale(62)).add(side.scale(30))).add(0, 26, 0);
            return CineCamera.dolly(a, b, 170, () -> village.add(0, 4, 0), 60);
        }).when(() -> nearest(CruiseMissileEntity.class, village, 350) != null, 2400);

        // --- frame for the mod icon: Shahed in acceleration from below, from the side, in its entirety, against the background of the daytime sky. Before ICBM:
        //  hits the village, and after it everything at the post is burning and smoking
        run(() -> {
            cmd("time set 6000");
            cmd("weather clear");
        });
        run(this::placeActor);
        waitTicks(100);
        run(() -> fire("drone", target(Vec3.ZERO)));
        shot("icon").after(() -> newest(DroneEntity.class) instanceof DroneEntity d && !d.flightPhase().onLauncher(), 1600).noPrep()
                .length(40).speed(0.3).hidden()
                .camera(() -> chaseOf(newest(DroneEntity.class), 11, -3.5, 4.5, 10, 0, 40));

        // --- ICBM: start behind the gunner ~2 km from the village, explosion from behind his shoulder, mushroom from afar
        run(() -> {
            cmd("time set 12500");
            buildTower(viewpoint);
            placeActor(viewpoint.add(0, 12, 0), village);
        });
        shot("icbm").onReady(() -> cmd(String.format(Locale.ROOT, "incoming nuke at %.1f %.1f %.1f 15 ground", village.x, village.y, village.z))).length(260).camera(() -> {
            Vec3 pad = ground(viewpoint.add(toView.scale(30)));
            Vec3 sideV = new Vec3(-toView.z, 0, toView.x);
            // in 70 blocks on the side so that the launch cloud does not cover the camera; after the rocket - collision
            Vec3 from = ground(pad.add(sideV.scale(70)).add(toView.scale(-20))).add(0, 3, 0);
            Path p = CineCamera.track(from, smoothFocus(() -> newest(IcbmEntity.class), pad.add(0, 8, 0), 0.4), 50);
            return t -> t < 100 ? p.at(t) : CineCamera.zoom(p, 50, 22, 160).at(t - 100);
        });
        // explosion from behind the gunner's shoulder: flash, ball, the front reaches the tower. The gunner is already on the second tower in 150 blocks
        //  to the side - the first one has a launch cloud hanging
        run(() -> {
            watch = ground(viewpoint.add(new Vec3(-toView.z, 0, toView.x).scale(150)));
            buildTower(watch);
            placeActor(watch.add(0, 12, 0), village);
        });
        shot("nuke").noPrep().length(480).hud().camera(() -> {
            Vec3 sideV = new Vec3(-toView.z, 0, toView.x);
            Vec3 back = watch.add(0, 12, 0).add(toView.scale(3.2)).add(sideV.scale(1.3)).add(0, 1.9, 0);
            return CineCamera.track(back, () -> village.add(0, 600, 0), 68);
        }).when(() -> warningTicks() <= 200, 2400);
        // mushroom from afar, quickly
        run(() -> {
            placeHidden(mushroomView());
        });
        shot("mushroom").length(1600).speed(4).hidden().camera(() -> {
            // cap rises above 5 km: frame wider and higher, stem base above the lower letterbox bar
            return CineCamera.track(mushroomView(), () -> village.add(0, 2800, 0), 75);
        }).when(() -> sinceDetonation() > 420, 3000);
        // black rain in a trail of precipitation, Geiger counter in hand
        run(() -> placeInFallout(false));
        waitTicks(40);
        run(() -> placeInFallout(true));
        run(() -> cmd("item replace entity @s weapon.mainhand with incoming:geiger_counter"));
        shot("fallout").length(170).hud().player(t -> new Pose(Vec3.ZERO, yawTo(mc.player.position(), village) + 150 - (float) t * 0.35f,
                        -18 + (float) Math.sin(t / 40) * 4, 0, 70))
                .when(() -> sinceDetonation() > falloutRain + 900, 12000);

        run(() -> {
            rec.finish();
            Incoming.LOG.info("TRAILER done");
            // the filming world is disposable, and saving it after long flights (9 km) takes minutes - we leave immediately
            Runtime.getRuntime().halt(0);
        });
    }

    // ============================================================================================================================================================================================================================================================================================================================= Location

    /** There is a village nearby and a gunner's post ~150 blocks away on flat, dry ground, facing the village. */
    private void findLocations(ServerLevel level) {
        // flat villages (open place, visible far away; villages exist only in the world with buildings - ClientScenario
        //  includes them for the trailer): of the several closest ones - the one with the best post
        var structures = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var plains = net.minecraft.core.HolderSet.direct(structures.getHolderOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.VILLAGE_PLAINS));
        List<BlockPos> villages = new ArrayList<>();
        for (int[] o : new int[][]{{0, 0}, {1600, 0}, {0, 1600}, {-1600, 0}, {0, -1600}}) {
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, plains, new BlockPos(o[0], 64, o[1]), 60, false);
            if (found != null && villages.stream().noneMatch(v -> v.distManhattan(found.getFirst()) < 300)) villages.add(found.getFirst());
        }
        if (villages.isEmpty()) villages.add(BlockPos.ZERO);
        double best = Double.MAX_VALUE;
        for (BlockPos v : villages) {
            Vec3 center = new Vec3(v.getX() + 0.5, height(level, v.getX(), v.getZ()), v.getZ() + 0.5);
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI / 12;
                for (int r = 120; r <= 200; r += 20) {
                    int x = Mth.floor(center.x + Math.sin(a) * r), z = Mth.floor(center.z + Math.cos(a) * r);
                    double score = postScore(level, center, x, z);
                    if (score < best) {
                        best = score;
                        village = center;
                        post = new Vec3(x + 0.5, height(level, x, z), z + 0.5);
                    }
                }
            }
            Incoming.LOG.info("TRAILER village {} best so far {}", center, best);
        }
        Vec3 d = post.subtract(village);
        toPost = new Vec3(d.x, 0, d.z).normalize();
        side = new Vec3(-toPost.z, 0, toPost.x);
        // filming location: post without grass and trees, direct view of the village
        clearAround(level, BlockPos.containing(post), 34, true);
        for (double k = 0; k <= 1; k += 2.0 / post.distanceTo(village)) {
            Vec3 p = post.lerp(village, k);
            if (p.distanceTo(village) > 45) clearAround(level, BlockPos.containing(p.x, height(level, Mth.floor(p.x), Mth.floor(p.z)), p.z), 5, false);
        }
        Incoming.LOG.info("TRAILER village {} post {} (score {})", village, post, best);
        findViewpoint(level);
    }

    /**
     * The smaller, the better the post: flat and dry (launcher), little forest (we’ll remove it, but the forest around the bare clearing is strange),
     *  the village is visible above the relief (the beam from the eyes to the village does not go underground), the post is slightly higher than the village.
     */
    private static double postScore(ServerLevel level, Vec3 village, int x, int z) {
        int y = height(level, x, z);
        Vec3 eye = new Vec3(x + 0.5, y + 1.6, z + 0.5), to = village.add(0, 3, 0);
        double blocked = 0;
        for (double k = 0.08; k < 0.9; k += 0.02) {
            Vec3 p = eye.lerp(to, k);
            blocked = Math.max(blocked, height(level, Mth.floor(p.x), Mth.floor(p.z)) - p.y);
        }
        double rise = y - village.y;
        return groundScore(level, x, z) + Math.max(0, blocked) * 30 + Math.max(0, 5 - rise) * 8;
    }

    /** Site 48×48 around a point: height difference, water, crowns (the smaller the better). */
    private static double groundScore(ServerLevel level, int x, int z) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, canopy = 0;
        boolean dry = true;
        for (int dx = -24; dx <= 24; dx += 8) {
            for (int dz = -24; dz <= 24; dz += 8) {
                int h = height(level, x + dx, z + dz);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                if (!level.getFluidState(new BlockPos(x + dx, h - 1, z + dz)).isEmpty()) dry = false;
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz) > h + 1) canopy++;
            }
        }
        return (hi - lo) * 10 + (dry ? 0 : 1000) + canopy * 15;
    }

    /**
     * An observation point for a nuclear strike ~2 km from the village (not towards the post - there is a testing ground) and an ICBM site
     *  in 30 blocks behind it ({@code NuclearStrikes.launchFrom}): both are flat, dry, without forest.
     */
    private void findViewpoint(ServerLevel level) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            Vec3 dir = new Vec3(Math.sin(a), 0, Math.cos(a));
            if (dir.dot(toPost) > 0.8) continue;
            for (int r = 1700; r <= 2100; r += 400) {
                Vec3 p = village.add(dir.scale(r)), pad = p.add(dir.scale(30));
                double score = groundScore(level, Mth.floor(p.x), Mth.floor(p.z)) + groundScore(level, Mth.floor(pad.x), Mth.floor(pad.z));
                if (score < best) {
                    best = score;
                    toView = dir;
                    viewpoint = p;
                }
            }
        }
        int x = Mth.floor(viewpoint.x), z = Mth.floor(viewpoint.z);
        viewpoint = new Vec3(x + 0.5, height(level, x, z), z + 0.5);
        Vec3 pad = viewpoint.add(toView.scale(30));
        clearAround(level, BlockPos.containing(viewpoint), 40, true);
        clearAround(level, BlockPos.containing(pad.x, height(level, Mth.floor(pad.x), Mth.floor(pad.z)), pad.z), 14, true);
        Incoming.LOG.info("TRAILER viewpoint {} (score {})", viewpoint, best);
    }

    /**
     * Remove the trees (and grass with flowers, if {@code plants}) in the circle: the script is filming, not acting - the forest in the
     *  line of sight and waist-deep grass near the camera only get in the way. Blocks are placed without updating neighbors and without dropping.
     */
    private static void clearAround(ServerLevel level, BlockPos c, int r, boolean plants) {
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                level.getChunk(x >> 4, z >> 4);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = ground - 8; y <= Math.max(top, ground + 3) + 1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var st = level.getBlockState(p);
                    boolean tree = st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS) && y >= ground - 8;
                    boolean plant = plants && !st.isAir() && st.getFluidState().isEmpty() && (st.canBeReplaced()
                            || st.is(net.minecraft.tags.BlockTags.FLOWERS) || st.is(net.minecraft.tags.BlockTags.REPLACEABLE_BY_TREES));
                    if (tree || plant) level.setBlock(p, air, 2 | 16);
                }
            }
        }
    }

    /** Height of the ground on the server; the chunk is loaded (and generated) immediately - this is possible in the script. */
    private static int height(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** A point on the ground of the village with a shift (the ground is based on the client’s height map, if there is a chunk). */
    private Vec3 target(Vec3 offset) {
        return ground(village.add(offset));
    }

    /** The ground under the point: according to the client’s height map, and if the client doesn’t have a chunk, ask the server (it will load it). */
    private Vec3 ground(Vec3 p) {
        int x = Mth.floor(p.x), z = Mth.floor(p.z);
        if (mc.level != null && mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return new Vec3(p.x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), p.z);
        }
        MinecraftServer server = mc.getSingleplayerServer();
        int y = server.submit(() -> {
            ServerLevel level = server.overworld();
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }).join();
        return new Vec3(p.x, y, p.z);
    }

    private void placeActor() {
        placeActor(post, village);
    }

    /** Gunner at the edge of the village: shells above the village - within the simulation range from him (in the world, on-board video). */
    private Vec3 operatorNearVillage() {
        return ground(village.add(toPost.scale(60)).add(side.scale(30)));
    }

    /** Where the invisible man is waiting for B-2: on the approach course, 150 blocks from the target. */
    private Vec3 bayWatch() {
        return bayTarget().add(toPost.scale(150)).add(0, 40, 0);
    }

    /** Where does B-2 hit in the plan with the compartment (away from the target of the second B-2 - the crater does not coincide). */
    private Vec3 bayTarget() {
        return target(side.scale(14)).add(toPost.scale(-28));
    }

    /** The target is a stance with a name (the target label in HUD labels it in the same way as the player). */
    private static void summonTarget(String name, Vec3 at) {
        cmd(String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %.1f %.1f {CustomName:'\"%s\"',Tags:[\"trailer_%s\"],NoGravity:1b}",
                at.x, at.y, at.z, name, name));
    }

    /**
     * Where the mushroom is visible in its entirety: 5 km to the south, looking north - the moon and sun move from east to west and do not fall into the frame
     * .
     */
    private Vec3 mushroomView() {
        return new Vec3(village.x, 240, village.z + 5000);
    }

    /** Observation platform 3×3 in 12 blocks above the ground (the gunner stands on it, visible over the forest and hills). */
    private static void buildTower(Vec3 base) {
        BlockPos p = BlockPos.containing(base);
        cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", p.getX() - 1, p.getY() + 11, p.getZ() - 1,
                p.getX() + 1, p.getY() + 11, p.getZ() + 1));
    }

    /** The gunner stands at his post facing the target, in creative mode, visible in the frame. */
    private void placeActor(Vec3 at, Vec3 face) {
        actor = true;
        float yaw = yawTo(at, face);
        withPlayer((level, p) -> {
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(level, at.x, at.y, at.z, yaw, 0);
        });
    }

    /** Invisible player (observer) where the camera will be: so that the server sends this area. */
    private void placeHidden(Vec3 at) {
        actor = false;
        withPlayer((level, p) -> {
            p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, at.x, at.y, at.z, p.getYRot(), p.getXRot());
        });
    }

    /** The same, but facing the point: according to the shooter’s gaze, the approach course B-2 is selected. */
    private void placeHidden(Vec3 at, Vec3 facing) {
        actor = false;
        withPlayer((level, p) -> {
            p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, at.x, at.y, at.z, yawTo(at, facing), 0);
        });
    }

    /**
     * In the wake of precipitation not far downwind (as in a nuclear scenario; precipitation there - not before it arrives from the cloud):
     *  first invisible (load), then on the ground, on a platform made of stone (suddenly there is water there). In a weak wind, this
     *  point is in a crater or in a ravine - then further down the wind, to an open place.
     */
    private void placeInFallout(boolean land) {
        var list = ClientNuclear.detonations();
        if (list.isEmpty()) return;
        MinecraftServer server = mc.getSingleplayerServer();
        if (!land) {
            var d = list.getLast().d;
            double m = Math.min(3000, d.windSpeed() * 150) * d.scale();
            double dx = Math.cos(d.windDir()), dz = Math.sin(d.windDir());
            falloutSpot = server.submit(() -> openGround(server.overworld(), d.burst().x, d.burst().z, dx, dz, m)).join();
            falloutRain = Math.max(3000, (long) Math.ceil(d.falloutArrivalTicks(falloutSpot.x, falloutSpot.z)));
            placeHidden(new Vec3(falloutSpot.x, 200, falloutSpot.z));
            return;
        }
        BlockPos c = BlockPos.containing(falloutSpot);
        // clearing: black rain is visible against the sky, not in the foliage
        int y = server.submit(() -> {
            clearAround(server.overworld(), c, 18, true);
            return server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ());
        }).join();
        Vec3 g = new Vec3(falloutSpot.x, y, falloutSpot.z);
        BlockPos b = BlockPos.containing(g);
        cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", b.getX() - 1, b.getY(), b.getZ() - 1, b.getX() + 1, b.getY(), b.getZ() + 1));
        placeActor(g.add(0, 1, 0), village);
    }

    /** The first point downwind of {@code from} blocks where the ground is not lower than the surrounding area in 20 blocks (not a hole). */
    private static Vec3 openGround(ServerLevel level, double x0, double z0, double dx, double dz, double from) {
        for (int i = 0; i < 40; i++) {
            double r = from + i * 24;
            int x = Mth.floor(x0 + dx * r), z = Mth.floor(z0 + dz * r);
            int h = height(level, x, z), around = Integer.MIN_VALUE;
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                around = Math.max(around, height(level, x + (int) (Math.cos(a) * 20), z + (int) (Math.sin(a) * 20)));
            }
            if (h >= around - 3) return new Vec3(x + 0.5, h, z + 0.5);
        }
        return new Vec3(x0 + dx * from, 0, z0 + dz * from);
    }

    // ====================================================================== Who's in the frame?

    @Nullable
    private LauncherEntity launcher(WeaponType weapon) {
        LauncherEntity best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof LauncherEntity l && l.weapon() == weapon && (best == null || l.distanceToSqr(post) < best.distanceToSqr(post))) best = l;
        }
        return best;
    }

    /** The youngest projectile of this type (just came off the launcher). */
    @Nullable
    private <T extends StrikeProjectile> T newest(Class<T> type) {
        T best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (type.isInstance(e) && !e.isRemoved() && ((StrikeProjectile) e).isActive() && (best == null || e.tickCount < best.tickCount)) {
                best = type.cast(e);
            }
        }
        return best;
    }

    @Nullable
    private <T extends Entity> T nearest(Class<T> type, Vec3 p, double radius) {
        T best = null;
        double bd = radius * radius;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!type.isInstance(e) || e.isRemoved()) continue;
            if (e instanceof StrikeProjectile s && !s.isActive()) continue;
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = type.cast(e);
            }
        }
        return best;
    }

    /** Who is being monitored by the zoom plan B-2: the bomb while it lasts, otherwise B-2 itself. */
    @Nullable
    private Entity bomberSubject() {
        Vec3 t = target(side.scale(-12));
        BunkerBusterEntity bomb = nearest(BunkerBusterEntity.class, t, 600);
        return bomb != null ? bomb : nearest(BomberEntity.class, t, 450);
    }

    /** B-2 - while it is in 450 blocks; after the release - a bomb. */
    @Nullable
    private Vec3 bomberFocus() {
        float pt = CineCamera.partial();
        Vec3 t = target(side.scale(-12));
        BunkerBusterEntity bomb = nearest(BunkerBusterEntity.class, t, 600);
        if (bomb != null && bomb.flightPhase() != FlightPhase.DRILL) return bomb.getPosition(pt);
        if (bomb != null) return t.add(0, 1, 0);
        BomberEntity b = nearest(BomberEntity.class, t, 450);
        return b == null ? null : b.getPosition(pt);
    }

    /** tick the game after the explosion (-1 - there has been no explosion yet). */
    private long sinceDetonation() {
        return detonationTime < 0 ? -1 : mc.level.getGameTime() - detonationTime;
    }

    /** The projectile camera is turned on and shows the operator's map (the projectile is further than the video zone). */
    private static boolean onMap() {
        return ProjectileCamera.isActive() && !ProjectileCamera.isViewing() && !ProjectileCamera.isFilming();
    }

    /** To the nearest projectile in flight from the player, blocks (according to the server - and for off-world projectiles). */
    private double missileRange() {
        double best = Double.MAX_VALUE;
        for (var f : ua.zentix.incoming.client.hud.ClientFlights.all()) best = Math.min(best, f.position(1).distanceTo(mc.player.position()));
        return best;
    }

    /** Before detonation on alarm, tick (a lot - if there is no alarm). */
    private long warningTicks() {
        long now = mc.level.getGameTime();
        long left = Long.MAX_VALUE;
        for (var w : ClientNuclear.warnings()) left = Math.min(left, w.detonateTime() - now);
        return left;
    }

    private Path chaseOf(@Nullable Entity e, double back, double up, double side, double lead, float roll, double fov) {
        final Entity target = e;
        return CineCamera.chase(() -> target != null && !target.isRemoved() ? target : null, back, up, side, lead, roll, fov);
    }

    /**
     * Where the camera is looking: at what {@code focus} returned (projectile, flock), otherwise at {@code rest}; the transition is smoothed
     *  according to the game time ({@code rate} is the fraction of the path per tick) so that the camera does not twitch when changing the target.
     */
    private Supplier<Vec3> smoothFocus(Supplier<?> focus, Vec3 rest, double rate) {
        final Vec3[] look = {rest};
        final double[] lastT = {Double.NaN};
        return () -> {
            Object f = focus.get();
            Vec3 want = f instanceof Entity e ? e.getPosition(CineCamera.partial()) : f instanceof Vec3 v ? v : rest;
            double t = shotTime();
            double dt = Double.isNaN(lastT[0]) ? 1e9 : Math.max(0, t - lastT[0]);
            lastT[0] = t;
            double k = 1 - Math.pow(1 - rate, dt);
            look[0] = look[0].lerp(want, Mth.clamp(k, 0, 1));
            return look[0];
        };
    }

    /**
     * Field of view in which a {@code focus} projectile is visible from a {@code from} of the same size: {@code wide} is closer than
     * {@code near} blocks, further away it is narrower (not less than {@code narrow}); without a projectile (explosion) - again {@code wide}.
     * Smoothed by game time, like {@link #smoothFocus}.
     */
    private DoubleSupplier rangeFov(Vec3 from, Supplier<? extends Entity> focus, double wide, double narrow, double near) {
        final double[] fov = {Double.NaN};
        final double[] lastT = {Double.NaN};
        return () -> {
            Entity e = focus.get();
            double want = e == null ? wide
                    : Mth.clamp(wide * near / Math.max(near, e.getPosition(CineCamera.partial()).distanceTo(from)), narrow, wide);
            double t = shotTime();
            double dt = Double.isNaN(lastT[0]) ? 1e9 : Math.max(0, t - lastT[0]);
            lastT[0] = t;
            fov[0] = Double.isNaN(fov[0]) ? want : Mth.lerp(Mth.clamp(1 - Math.pow(0.85, dt), 0, 1), fov[0], want);
            return fov[0];
        };
    }

    // ================================================================================ Steps

    private void then(Step s) {
        steps.add(s);
    }

    private void run(Runnable r) {
        then(() -> {
            r.run();
            return true;
        });
    }

    private void waitTicks(int n) {
        final int[] left = {n};
        then(() -> --left[0] <= 0);
    }

    /** Work on the server (search for places, heights): the script waits for it to finish. */
    private void onServer(Consumer<ServerLevel> work) {
        final boolean[] done = {false};
        final boolean[] sent = {false};
        then(() -> {
            if (!sent[0]) {
                sent[0] = true;
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> {
                    work.accept(server.overworld());
                    mc.execute(() -> done[0] = true);
                });
            }
            return done[0];
        });
    }

    private void withPlayer(java.util.function.BiConsumer<ServerLevel, ServerPlayer> work) {
        MinecraftServer server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) work.accept(server.overworld(), p);
        });
    }

    private Shot shot(String name) {
        Shot s = new Shot(name);
        then(s);
        return s;
    }

    /** Plan: preparation (camera in place, world busy), waiting for the moment, recording. */
    private final class Shot implements Step {
        private record Cue(int at, Runnable action) {}

        final String name;
        private int length = 100;
        private double speed = 1;
        private Supplier<Path> camera;
        /** View through the eyes of the player: only the gaze sets the path. */
        private Path playerView;
        private boolean projectileCamera;
        private boolean hudOn;
        private boolean hiddenActor;
        private boolean prep = true;
        /** Wait for it to load almost the entire drawing distance (wide shot from above), and not just next to the camera. */
        private int readyChunks = 4;
        private BooleanSupplier after, when, end;
        private int afterTimeout, whenTimeout, endDelay;
        private final List<Cue> cues = new ArrayList<>();
        private Runnable atEnd, atPrepare, atReady;

        private int phase, waited, readyFor, t, endAt = -1;
        private Path path;

        Shot(String name) {
            this.name = name;
        }

        Shot length(int ticks) {
            length = ticks;
            return this;
        }

        Shot speed(double s) {
            speed = s;
            return this;
        }

        Shot camera(Supplier<Path> c) {
            camera = c;
            return this;
        }

        Shot player(Path view) {
            playerView = view;
            return this;
        }

        Shot projectileCamera() {
            projectileCamera = true;
            return this;
        }

        Shot hud() {
            hudOn = true;
            return this;
        }

        Shot hidden() {
            hiddenActor = true;
            return this;
        }

        Shot farView() {
            return readyChunks(10);
        }

        /** Before shooting, wait for the world to {@code chunks} chunks around the camera (no further than drawing). */
        Shot readyChunks(int chunks) {
            readyChunks = chunks;
            return this;
        }

        Shot noPrep() {
            prep = false;
            return this;
        }

        /** Condition before placing the camera (for example, the launcher has already appeared - a path is built from it). */
        Shot after(BooleanSupplier c, int timeout) {
            after = c;
            afterTimeout = timeout;
            return this;
        }

        /** The moment recording begins (the camera is already in place). */
        Shot when(BooleanSupplier c, int timeout) {
            when = c;
            whenTimeout = timeout;
            return this;
        }

        /** End before length: {@code delay} tick after the condition becomes true. */
        Shot endWhen(BooleanSupplier c, int delay) {
            end = c;
            endDelay = delay;
            return this;
        }

        Shot cue(int at, Runnable action) {
            cues.add(new Cue(at, action));
            return this;
        }

        /** The action when the camera is put in place (before waiting for the moment). */
        Shot prepare(Runnable action) {
            atPrepare = action;
            return this;
        }

        /** Action when the camera is in place and the world is busy (launch, whose approach we are filming, so as not to miss it). */
        Shot onReady(Runnable action) {
            atReady = action;
            return this;
        }

        Shot cueEnd(Runnable action) {
            atEnd = action;
            return this;
        }

        /** INCOMING_TRAILER_SHOTS=a,b - shoot only these plans (the rest are played without recording, for debugging). */
        boolean selected() {
            String only = System.getenv("INCOMING_TRAILER_SHOTS");
            return only == null || only.isBlank() || java.util.Arrays.asList(only.split(",")).contains(name);
        }

        boolean player() {
            return playerView != null;
        }

        /** Plan time with fraction tick: the frame after tick n shows the world between tick n−1 and n. */
        double time() {
            if (phase < 4) return 0;
            return recording == this ? rec.frames() * rec.step() : Math.max(0, t - 1 + CineCamera.partial());
        }

        @Override
        public boolean tick() {
            switch (phase) {
                case 0 -> {
                    if (after != null && !after.getAsBoolean() && waited++ < afterTimeout) return false;
                    if (after != null && waited > afterTimeout) {
                        Incoming.LOG.warn("TRAILER {}: the conditions were not met, the plan was missed", name);
                        return true;
                    }
                    waited = 0;
                    hud = hudOn;
                    if (hiddenActor && actor) {
                        actor = false;
                        withPlayer((level, p) -> p.setGameMode(GameType.SPECTATOR));
                    }
                    if (camera != null) {
                        path = camera.get();
                        CineCamera.use(path);
                    } else {
                        CineCamera.release();
                    }
                    if (atPrepare != null) atPrepare.run();
                    setTickRate(20);
                    phase = prep ? 1 : 2;
                    // the world stands still while the camera waits to load: the projectile does not reach the target before recording starts
                    if (prep) setFrozen(true);
                    if (!prep && atReady != null) atReady.run();
                    Incoming.LOG.info("TRAILER {}: preparation", name);
                }
                case 1 -> {
                    followCamera();
                    readyFor = worldReady(readyChunks) ? readyFor + 1 : 0;
                    if (readyFor >= 10 && waited >= 40 || waited > 1200) {
                        setFrozen(false);
                        phase = 2;
                        waited = 0;
                        if (atReady != null) atReady.run();
                    } else {
                        waited++;
                    }
                }
                case 2 -> {
                    followCamera();
                    if (when != null && !when.getAsBoolean()) {
                        if (++waited % 200 == 0) Incoming.LOG.info("TRAILER {}: waiting for the moment ({} tick)", name, waited);
                        if (waited < whenTimeout) return false;
                        Incoming.LOG.warn("TRAILER {}: the moment hasn’t come, let’s shoot as is", name);
                    }
                    if (selected()) {
                        rec.start(name, speed, hudOn);
                        recording = this;
                        frameClock = 0;
                        setTickRate(Math.max(1, (float) (8 * rec.step())));
                    }
                    phase = 4;
                    t = 0;
                    runCues();
                }
                case 4 -> {
                    followCamera();
                    t++;
                    runCues();
                    if (end != null && endAt < 0 && end.getAsBoolean()) endAt = t + endDelay;
                    if (t >= length || endAt >= 0 && t >= endAt) {
                        rec.stop();
                        recording = null;
                        idleTime = time();
                        if (atEnd != null) atEnd.run();
                        setTickRate(20);
                        return true;
                    }
                }
                default -> throw new IllegalStateException();
            }
            return false;
        }

        private void runCues() {
            for (Cue c : cues) if (c.at == t) c.action.run();
        }

        /** Camera on frame: movie camera - along the way, player's view - head turn. */
        void applyCamera() {
            double time = time();
            if (path != null) CineCamera.apply(time);
            if (playerView != null && mc.player != null) {
                Pose p = playerView.at(time);
                CineCamera.viewFov = p.fov();
                mc.player.setYRot(p.yaw());
                mc.player.yRotO = p.yaw();
                mc.player.setXRot(p.pitch());
                mc.player.xRotO = p.pitch();
                mc.player.setYHeadRot(p.yaw());
                mc.player.yHeadRotO = p.yaw();
            }
        }
    }

    // ============================================================================================================================================================================================================================================================================================================= game events

    private void onTick(ClientTickEvent.Post e) {
        if (mc.player == null || mc.level == null) return;
        if (!started) {
            started = true;
            // recording folder: duplicate of individual plans (INCOMING_TRAILER_SHOTS) - in its own folder, editing takes them on top of the main one
            rec = new Recorder(mc.gameDirectory.toPath().resolve(System.getenv().getOrDefault("INCOMING_TRAILER_DIR", "trailer")));
            mc.getSoundManager().addListener(rec);
            mc.options.hideGui = false;
        }
        if (detonationTime < 0 && !ClientNuclear.detonations().isEmpty()) {
            detonationTime = mc.level.getGameTime();
            if (rec.recording()) rec.mark("detonation");
        }
        boolean viewing = ProjectileCamera.isViewing(), camera = ProjectileCamera.isActive();
        markImpacts(rec.recording());
        if (rec.recording() && viewing && !wasViewing) rec.mark("video");
        if (!viewing) {
            closeMarked = false;
        } else if (rec.recording() && !closeMarked && mc.getCameraEntity() instanceof StrikeProjectile p
                && ua.zentix.incoming.client.hud.ClientFlights.find(p.getUUID()) instanceof ua.zentix.incoming.client.hud.ClientFlights.Tracked f
                && p.position().distanceTo(f.target()) < CLOSE_RANGE) {
            rec.mark("close");
            closeMarked = true;
        }
        // the projectile camera returned to the player: further in the plan there is a first-person view, the montage cuts to this mark
        if (rec.recording() && !camera && cameraWasActive) rec.mark("exit");
        wasViewing = viewing;
        cameraWasActive = camera;
        while (!steps.isEmpty()) {
            if (!steps.peek().tick()) break;
            steps.poll();
        }
    }

    /**
     * The shells that were in the frame and disappeared (explosion), and the dropping of a bomb B-2: marks for editing - the sound comes later
     * pictures. The list is updated between plans, otherwise shells that were missing before the entry would be marked at the beginning of
     *  of the next plan.
     */
    private void markImpacts(boolean mark) {
        java.util.Map<Integer, String> now = new java.util.HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof BomberEntity b) {
                if (b.flightPhase() == FlightPhase.EGRESS && released.add(b.getId()) && mark) rec.mark("release");
            } else if (e instanceof StrikeProjectile p && p.isActive()) {
                now.put(e.getId(), e.getType().toShortString());
            }
        }
        for (var e : seen.entrySet()) {
            if (mark && !now.containsKey(e.getKey())) rec.mark("gone:" + e.getValue());
        }
        seen = now;
    }

    /**
     * Before the frame: when recording - the tick fraction exactly at the moment of the frame (the frame after tick n shows the world between the ticks
     * n−1 and n); if the client has not yet reached the moment (the rendering frame does not count) - the frame is not shot.
     */
    private void beforeFrame(RenderFrameEvent.Pre e) {
        if (!started) return;
        frameReady = false;
        if (recording != null) {
            double partial = recording.time() - (recording.t - 1);
            if (partial > 1.0001) {
                FrameClock.advanceNext(partial - 1 + 0.01);
            } else {
                if (partial < -0.02) Incoming.LOG.warn("TRAILER {}: client overtook frame by {} tick", recording.name, -partial);
                FrameClock.setPartial((float) Mth.clamp(partial, 0, 1));
                frameReady = true;
            }
        }
        if (steps.peek() instanceof Shot shot) shot.applyCamera();
        else CineCamera.apply(idleTime); // between shots the camera stands where it left off
        holdTarget();
    }

    /**
     * On-board video: a camera on a gimbal looks where the player is moving the mouse; in the trailer, the mouse does not move, and the
     *  gimbal would remain on the entry course - at the peak there would be a horizon in the frame. The cameraman keeps the target in the center of the frame.
     */
    private void holdTarget() {
        if (!ProjectileCamera.isViewing() || !(mc.getCameraEntity() instanceof StrikeProjectile p)
                || !(ua.zentix.incoming.client.hud.ClientFlights.find(p.getUUID()) instanceof ua.zentix.incoming.client.hud.ClientFlights.Tracked f)) {
            return;
        }
        Vec3 d = f.target().subtract(p.getPosition(mc.getTimer().getGameTimeDeltaPartialTick(false)));
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
        float pitch = (float) (-Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
    }

    private void afterFrame(RenderFrameEvent.Post e) {
        if (recording == null || !frameReady) return;
        rec.frame(recording.time(), mc.gameRenderer.getMainCamera());
        FrameClock.advanceNext(rec.step());
        adaptTickRate();
    }

    /** Time of the current shot (to smooth the view). */
    private double shotTime() {
        return steps.peek() instanceof Shot s ? s.time() : 0;
    }

    /**
     * The server moves at the same pace as the recording (tick per second = rendering frames per second × frame step): then
     *  entity data arrives to the client on time, and not in jerks.
     */
    private void adaptTickRate() {
        long now = System.nanoTime();
        if (frameClock == 0) {
            frameClock = now;
            frameCount = 0;
            return;
        }
        if (++frameCount < 10) return;
        double fps = frameCount / ((now - frameClock) / 1e9);
        frameClock = now;
        frameCount = 0;
        float want = (float) Mth.clamp(fps * rec.step(), 1, 20);
        if (Math.abs(want - tickRate) / tickRate > 0.1) setTickRate(want);
    }

    private void setFrozen(boolean frozen) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> server.tickRateManager().setFrozen(frozen));
    }

    /**
     * The frame is exactly {@code INCOMING_SIZE}: a window without a frame and exactly this size. In KWin, the window with the title was compressed under the
     *  screen (frame 1920×1052); it didn’t work out - filming doesn’t start: frames of a different size are not suitable for editing.
     */
    private boolean exactFrame() {
        String[] want = System.getProperty("incoming.frame", "").split("x");
        if (want.length != 2) return true;
        int w = Integer.parseInt(want[0]), h = Integer.parseInt(want[1]);
        var target = mc.getMainRenderTarget();
        if (target.width == w && target.height == h) {
            Incoming.LOG.info("TRAILER frame {}x{}", w, h);
            return true;
        }
        long window = mc.getWindow().getWindow();
        if (frameTries == 0) {
            Incoming.LOG.info("TRAILER window {}x{}, you need {}x{}: I remove the frame and set the size", target.width, target.height, w, h);
            org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_DECORATED, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        }
        if (frameTries % 20 == 0) org.lwjgl.glfw.GLFW.glfwSetWindowSize(window, w, h);
        if (++frameTries > 200) {
            Incoming.LOG.error("TRAILER frame {}x{} instead of {}x{}: is the screen smaller than the window? Filming stopped", target.width, target.height, w, h);
            Runtime.getRuntime().halt(3);
        }
        return false;
    }

    private void setTickRate(float rate) {
        if (rate == tickRate) return;
        tickRate = rate;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> server.tickRateManager().setTickRate(rate));
    }

    /**
     * A world of {@code chunks} chunks around the camera is received and assembled into sections for rendering. Long shots from above - 10:
     *  the first shot in the fresh world would otherwise be filming a village on an “island” above the void; video from on board - the entire rendering (rocket
     *  looks beyond the village). For the rest, 4 is enough: while we are waiting for the distant ones, the projectile has already reached, and the strike plan would have been removed without the strike.
     */
    private boolean worldReady(int chunks) {
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int cx = Mth.floor(cam.x) >> 4, cz = Mth.floor(cam.z) >> 4;
        int r = Math.max(4, Math.min(chunks, mc.options.getEffectiveRenderDistance() - 2));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return mc.levelRenderer.hasRenderedAllSections();
    }

    /** The invisible player stays close to the movie camera: the server sends the world around the player, not around the camera. */
    private void followCamera() {
        Pose p = CineCamera.pose();
        if (actor || p == null || mc.player == null) return;
        if (mc.player.position().distanceToSqr(p.pos()) < 24 * 24) return;
        Vec3 at = p.pos();
        withPlayer((level, sp) -> sp.teleportTo(level, at.x, at.y, at.z, sp.getYRot(), sp.getXRot()));
    }

    /** The game interface is not needed in the frame; The interface of the mod is only in plans “with an interface”, flashes are always there. */
    private void layer(RenderGuiLayerEvent.Pre e) {
        if (!started) return;
        ResourceLocation id = e.getName();
        if (ALWAYS_LAYERS.contains(id)) return;
        if (hud && id.getNamespace().equals(Incoming.MOD_ID)) return;
        e.setCanceled(true);
    }

    private void hideActor(RenderPlayerEvent.Pre e) {
        if (started && !actor) e.setCanceled(true);
    }

    private void hideName(net.neoforged.neoforge.client.event.RenderNameTagEvent e) {
        if (started) e.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    private void hideHand(RenderHandEvent e) {
        if (CineCamera.active()) e.setCanceled(true);
    }

    // ====================================================================== trifles

    private void fire(String what, Vec3 at) {
        cmd(String.format(Locale.ROOT, "incoming %s at %.1f %.1f %.1f", what, at.x, at.y, at.z));
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Incoming.LOG.info("TRAILER /{}", c);
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90f;
    }

    private static float pitchTo(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        return (float) (-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
    }
}
