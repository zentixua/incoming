package ua.zentix.incoming.client.cam;

import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.hud.StrikesHud;
import ua.zentix.incoming.client.render.ScreenProjection;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetPicker;
import ua.zentix.incoming.util.Local;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Projectile camera: view from its stabilized camera (as on loitering munitions). Camera key goes to the projectile
 * nearest by time, then the next; after the last, back to self; Shift exits. Mouse looks around (gimballed camera),
 * LMB retargets the projectile to the crosshair. Shahed and Lancet track a sighted target (player, mob, craft ordered
 * from the remote) only while in frame: each tick the client sends the viewing direction ({@link C2S.CameraView}),
 * and a line above the hint shows the result.
 * <p>Three shots, as in cinema. <b>Launch</b>: while on the launcher and booster, the camera stands beside the launcher
 * and tracks with a long lens. <b>Onboard</b>: after booster separation, the onboard view. <b>Impact</b>: half a second
 * of interference at impact, then the camera slowly orbits the explosion for a few seconds from the approach side;
 * then the next salvo projectile or back to self. While the client has no projectile (beyond render distance or
 * outside the loaded world), {@link TacticalMap} shows a map using server telemetry; video resumes automatically
 * when the server sends the entity. F5 onboard gives an external view. The player stays in place.
 * The filming camera is a client marker entity absent from the world.
 */
public final class ProjectileCamera {
    /** Ticks of interference after losing the link. */
    private static final int LOST_TICKS = 30;
    /** Impact: interference, then orbit the explosion (ticks). */
    private static final int IMPACT_STATIC = 8, IMPACT_TICKS = 110;
    /** Projectile lost nearer than this to the target or during a dive means impact, rather than loss of link range. */
    private static final double IMPACT_RANGE = 40;

    private enum Shot { ONBOARD, LAUNCH, IMPACT, WAIT }
    private static final RandomSource NOISE = RandomSource.create();

    @Nullable
    private static UUID following;
    private static boolean active;
    private static int lostTicks;
    private static float savedYaw, savedPitch;
    private static CameraType savedCamera = CameraType.FIRST_PERSON;
    private static boolean shiftWasDown;
    /** Retargeted location (for the marker), display ticks. */
    @Nullable
    private static Vec3 newTarget;
    private static int newTargetTicks;

    private static Shot shot = Shot.WAIT;
    /** Filming camera (launch and impact). */
    @Nullable
    private static Marker rig;
    /** Projectile position on the launcher: film the launch from here. */
    @Nullable
    private static Vec3 pad;
    private static float padYaw;
    /** Last seen position, heading, phase: to identify and film an impact. */
    @Nullable
    private static Vec3 lastPos, lastDir;
    private static boolean lastTerminal;
    private static int impactTick;
    @Nullable
    private static Vec3 impactAt;
    private static float orbit;
    /** Projectile currently on the map (no video): if it disappears, the map shows where and how it ended. */
    @Nullable
    private static ClientFlights.Tracked mapped;
    /** Projectiles already seen (auto-camera: go straight to a new projectile's launch). */
    private static final java.util.Set<UUID> SEEN = new java.util.HashSet<>();

    private ProjectileCamera() {}

    public static boolean isActive() {
        return active;
    }

    /** Camera currently looks through the projectile's eyes (rather than waiting for a link or filming externally). */
    public static boolean isViewing() {
        Minecraft mc = Minecraft.getInstance();
        return active && mc.getCameraEntity() instanceof StrikeProjectile;
    }

    /** Camera filming externally: launch or impact. */
    public static boolean isFilming() {
        return active && (shot == Shot.LAUNCH || shot == Shot.IMPACT && impactTick >= IMPACT_STATIC);
    }

    /** Camera key: nearest projectile, next, back to self after the last. */
    public static void cycle() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        List<ClientFlights.Tracked> flights = ClientFlights.all();
        if (flights.isEmpty()) {
            if (active) exit();
            else player.displayClientMessage(Component.translatable("incoming.camera.none").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int idx = -1;
        if (active && following != null) {
            for (int i = 0; i < flights.size(); i++) {
                if (flights.get(i).id.equals(following)) idx = i;
            }
            if (idx == flights.size() - 1) {
                exit();
                return;
            }
        }
        follow(flights.get(idx + 1));
    }

    private static void follow(ClientFlights.Tracked f) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!active) {
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            savedCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            // Exit requires a new press: Shift held when the camera opened (auto-camera at launch) does not close it.
            shiftWasDown = mc.options.keyShift.isDown();
        }
        active = true;
        following = f.id;
        lostTicks = 0;
        newTarget = null;
        shot = Shot.WAIT;
        pad = null;
        lastPos = lastDir = null;
        lastTerminal = false;
        mapped = null;
        TacticalMap.reset();
        StrikeProjectile p = f.entity();
        if (p != null) aimAlong(player, p);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.4f));
    }

    /** View follows projectile heading, then the mouse moves the gimballed camera. */
    private static void aimAlong(LocalPlayer player, StrikeProjectile p) {
        player.setYRot(p.getYRot());
        player.setXRot(Mth.clamp(p.getXRot() + 8, -89, 89));
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
    }

    public static void exit() {
        Minecraft mc = Minecraft.getInstance();
        if (!active) return;
        active = false;
        following = null;
        mapped = null;
        lostTicks = 0;
        shot = Shot.WAIT;
        rig = null;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            mc.player.setYRot(savedYaw);
            mc.player.setXRot(savedPitch);
        }
        mc.options.setCameraType(savedCamera);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        autoFollow(mc);
        if (!active) return;
        if (mc.player == null || mc.level == null) {
            active = false;
            following = null;
            return;
        }
        boolean shift = mc.options.keyShift.isDown();
        if (shift && !shiftWasDown) {
            shiftWasDown = true;
            exit();
            return;
        }
        shiftWasDown = shift;
        if (newTargetTicks > 0) newTargetTicks--;

        if (shot == Shot.IMPACT) {
            impactTick++;
            if (impactTick == IMPACT_STATIC) film(mc, impactAt, 0);
            if (impactTick >= IMPACT_STATIC) orbitImpact(mc);
            if (impactTick >= IMPACT_STATIC + IMPACT_TICKS) next(mc);
            return;
        }

        ClientFlights.Tracked f = following == null ? null : ClientFlights.find(following);
        StrikeProjectile p = f == null ? null : f.entity();
        if (p == null && lastPos != null && (lastTerminal || f == null || lastPos.distanceTo(f.target()) < IMPACT_RANGE)) {
            // Projectile disappeared at the target or in a dive: impact.
            startImpact(mc, f == null ? lastPos : nearer(lastPos, f.target()));
            return;
        }
        if (f == null) {
            // Gone without impact (shot down, abort): interference, then next salvo projectile or self.
            if (lostTicks == 0) mc.setCameraEntity(mc.player);
            shot = Shot.WAIT;
            if (++lostTicks >= LOST_TICKS) next(mc);
            return;
        }
        // Projectile is still hidden in its container while the launcher turns, but already film the launcher.
        if (p != null && (p.isActive() || p.flightPhase().onLauncher())) {
            mapped = null;
            if (p.isActive()) {
                lastPos = p.position();
                Vec3 v = p.position().subtract(p.xo, p.yo, p.zo);
                if (v.lengthSqr() > 1.0e-4) lastDir = v.normalize();
                lastTerminal = p.flightPhase() == FlightPhase.TERMINAL || p.flightPhase() == FlightPhase.DRILL;
            }
            if (pad == null && p.flightPhase().onLauncher()) {
                pad = p.position();
                padYaw = p.getYRot();
            }
            boolean launch = pad != null && (p.flightPhase().launching() || p.flightPhase() == FlightPhase.CLIMB && p.phaseAge() < 25);
            if (launch) {
                if (shot != Shot.LAUNCH) {
                    shot = Shot.LAUNCH;
                    // Beside the launcher, slightly ahead and above: rack, plume and launch rail exit in one frame, rack not occluding.
                    film(mc, Local.at(pad, padYaw, 0, 24, 5, 2), 0);
                    lookAt(pad, 1);
                }
                trackRig(p.getPosition(1));
            } else {
                if (shot != Shot.ONBOARD) {
                    if (shot == Shot.LAUNCH) aimAlong(mc.player, p);
                    shot = Shot.ONBOARD;
                }
                if (mc.getCameraEntity() != p) mc.setCameraEntity(p);
                // Server tracks a sighted target only while in frame: only the client knows the camera's viewing direction.
                PacketDistributor.sendToServer(new C2S.CameraView(p.getUUID(), mc.player.getYRot(), mc.player.getXRot()));
            }
        } else {
            // Outside video range: the last sighting is no longer the impact site; map for now.
            shot = Shot.WAIT;
            lastPos = null;
            if (mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
            mapped = f;
            TacticalMap.tick(f);
        }
    }

    /** Auto-camera (client setting): go straight to a new owned projectile, starting with its launch. */
    private static void autoFollow(Minecraft mc) {
        List<ClientFlights.Tracked> all = ClientFlights.all();
        if (all.isEmpty()) {
            SEEN.clear();
            return;
        }
        ClientFlights.Tracked fresh = null;
        for (ClientFlights.Tracked t : all) {
            if (SEEN.add(t.id) && fresh == null) fresh = t;
        }
        if (fresh != null && !active && mc.screen == null && mc.player != null
                && ua.zentix.incoming.IncomingConfig.CLIENT.autoCamera.get()) {
            follow(fresh);
        }
    }

    /** Next salvo projectile or self. */
    private static void next(Minecraft mc) {
        List<ClientFlights.Tracked> rest = ClientFlights.all();
        if (rest.isEmpty()) {
            exit();
            return;
        }
        follow(rest.get(0));
        if (mc.player != null && mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
    }

    private static Vec3 nearer(Vec3 last, Vec3 target) {
        // Projectile view lags a tick or two: if the target is nearby, the explosion is there.
        return last.distanceTo(target) < IMPACT_RANGE ? last.lerp(target, 0.5) : last;
    }

    /** Impact: interference, then orbit from the approach side. */
    private static void startImpact(Minecraft mc, Vec3 at) {
        shot = Shot.IMPACT;
        impactTick = 0;
        impactAt = at;
        Vec3 d = lastDir == null ? new Vec3(0, 0, 1) : lastDir;
        orbit = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    /** Explosion orbit: radius 42→32 blocks, 14–18 above the point (at least terrain height), slowly closing in. */
    private static void orbitImpact(Minecraft mc) {
        if (rig == null || impactAt == null || mc.level == null) return;
        float t = (impactTick - IMPACT_STATIC) / (float) IMPACT_TICKS;
        orbit += 0.35f;
        double r = 42 - 10 * t, rad = Math.toRadians(orbit);
        Vec3 behind = new Vec3(Math.sin(rad) * r, 0, -Math.cos(rad) * r);
        Vec3 at = impactAt.add(behind).add(0, 14 + 4 * t, 0);
        int x = Mth.floor(at.x), z = Mth.floor(at.z);
        if (mc.level.hasChunk(x >> 4, z >> 4)) {
            double ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            at = new Vec3(at.x, Math.max(at.y, ground + 4), at.z);
        }
        moveRig(at);
        lookAt(impactAt.add(0, 6, 0), 0.5f);
    }

    /** Position the filming camera and make it the client camera. */
    private static void film(Minecraft mc, @Nullable Vec3 at, float yaw) {
        if (mc.level == null || mc.player == null || at == null) return;
        if (rig == null || rig.level() != mc.level) rig = EntityType.MARKER.create(mc.level);
        if (rig == null) return;
        rig.moveTo(at.x, at.y, at.z, yaw, 0);
        rig.setOldPosAndRot();
        mc.setCameraEntity(rig);
    }

    private static void moveRig(Vec3 at) {
        if (rig == null) return;
        rig.setOldPosAndRot();
        rig.setPos(at);
    }

    /** Filming camera looks at a point (smooth rotation: camera on a tripod, not fixed to the target). */
    private static void trackRig(Vec3 target) {
        if (rig == null) return;
        rig.setOldPosAndRot();
        // Hard tracking: after its booster, the missile travels 10 blocks per tick.
        lookAt(target, 0.8f);
    }

    /** Turn camera toward a point, covering fraction {@code k} of the remaining angle per tick. */
    private static void lookAt(Vec3 target, float k) {
        if (rig == null) return;
        Vec3 d = target.subtract(rig.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        rig.setYRot(rig.getYRot() + Mth.wrapDegrees(yaw - rig.getYRot()) * k);
        rig.setXRot(Mth.lerp(k, rig.getXRot(), pitch));
    }

    /** Gimballed camera: player viewing direction, half the projectile's roll; filming camera: its own viewing direction. */
    public static void angles(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null) return;
        if (rig != null && mc.getCameraEntity() == rig) {
            float pt = (float) e.getPartialTick();
            e.setYaw(rig.getViewYRot(pt));
            e.setPitch(rig.getViewXRot(pt));
            e.setRoll(0);
            return;
        }
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p)) return;
        float pt = (float) e.getPartialTick();
        e.setYaw(mc.player.getViewYRot(pt));
        e.setPitch(mc.player.getViewXRot(pt));
        e.setRoll(p.roll() * 0.5f);
    }

    /** Narrow field of view as with a zoom camera; long lens at launch, closing in while orbiting the explosion. */
    public static void fov(ViewportEvent.ComputeFov e) {
        if (isViewing()) e.setFOV(e.getFOV() * 0.75);
        else if (active && shot == Shot.LAUNCH) e.setFOV(e.getFOV() * 0.7);
        else if (isFilming()) e.setFOV(e.getFOV() * (0.8 - 0.15 * Math.min(1, (impactTick - IMPACT_STATIC) / (double) IMPACT_TICKS)));
    }

    /** Player stands still while watching the camera. */
    public static void movement(MovementInputUpdateEvent e) {
        if (!active) return;
        var in = e.getInput();
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
    }

    public static void hideHand(RenderHandEvent e) {
        if (active) e.setCanceled(true);
    }

    /** LMB in camera sets a new target; other hand actions disabled. */
    public static void click(InputEvent.InteractionKeyMappingTriggered e) {
        if (!active) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        if (e.isAttack()) retarget();
    }

    private static void retarget() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p) || mc.level == null || mc.player == null) return;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 look = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        TargetPicker.Pick pick = TargetPicker.pick(mc.level, p, eye, look, 1024);
        if (pick == null) {
            mc.player.displayClientMessage(Component.translatable("incoming.target_not_found").withStyle(ChatFormatting.RED), true);
            return;
        }
        C2S.AimHint hint;
        if (pick.entity() != null) {
            hint = new C2S.AimHint(C2S.AimHint.ENTITY, pick.point(), pick.entity().getId(), Vec3.ZERO);
        } else if (pick.target() instanceof Target.OfSubLevel s) {
            hint = new C2S.AimHint(C2S.AimHint.AIRCRAFT, pick.point(), 0, s.plotPos());
        } else {
            hint = new C2S.AimHint(C2S.AimHint.POINT, pick.point(), 0, Vec3.ZERO);
        }
        PacketDistributor.sendToServer(new C2S.Retarget(p.getUUID(), hint));
        newTarget = pick.point();
        newTargetTicks = 40;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(ua.zentix.incoming.registry.ModSounds.DESIGNATOR_LOCK.get(), 1.0f, 0.8f));
    }

    /** Client world is leaving (dimension change): filming camera is no longer needed and must not retain it. */
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (rig != null && rig.level() == e.getLevel()) rig = null;
    }

    public static void reset() {
        close();
        SEEN.clear();
    }

    /**
     * Ordinary abort: close the camera only if its projectile was cancelled. Remember seen projectiles:
     * a remaining nuclear projectile must not open the auto-camera again.
     */
    public static void cancelled(Collection<UUID> projectiles) {
        if (following != null && projectiles.contains(following)) close();
    }

    private static void close() {
        if (active) exit();
        active = false;
        following = null;
        mapped = null;
        rig = null;
    }

    // ---------------------------------------------------------------- visuals

    /** Video feed: frame, crosshair, telemetry, noise; no video means map, impact means full-screen interference. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null) return;
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2, cy = h / 2;
        Font font = mc.font;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        ClientFlights.Tracked f = following == null ? null : ClientFlights.find(following);

        if (shot == Shot.IMPACT) {
            if (impactTick < IMPACT_STATIC) {
                // Impact: signal drops into interference on black, hiding the world (camera is already off the projectile).
                g.fill(0, 0, w, h, 0xFF000000);
                staticNoise(g, w, h, 1.0f);
                big(g, font, Component.translatable("incoming.camera.lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), cx, cy - 6, 2f);
            } else {
                letterbox(g, w, h);
                caption(g, font, Component.translatable("incoming.camera.impact").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), w, h);
            }
            return;
        }
        if (shot == Shot.LAUNCH && f != null) {
            letterbox(g, w, h);
            caption(g, font, Component.translatable("incoming.camera.launch", f.weapon().displayName(), f.number,
                    f.phase().displayName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), w, h);
            hint(g, font, w, h);
            return;
        }
        if (f == null && mapped != null) {
            // Gone from the map: at the target means impact; otherwise (shot down, abort), link lost.
            TacticalMap.render(g, font, mapped, pt);
            boolean hit = mapped.phase() == FlightPhase.TERMINAL || mapped.phase() == FlightPhase.DRILL
                    || mapped.position(pt).distanceTo(mapped.target()) < IMPACT_RANGE;
            Component end = Component.translatable(hit ? "incoming.map.hit" : "incoming.camera.lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            big(g, font, end, cx, cy - 30, 2f);
            return;
        }
        if (f == null) {
            g.fill(0, 0, w, h, 0xFF000000);
            staticNoise(g, w, h, 1.0f);
            Component lost = Component.translatable("incoming.camera.lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            big(g, font, lost, cx, cy - 6, 2f);
            return;
        }
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p)) {
            // Client has no projectile (beyond render distance or outside the loaded world): map from server telemetry.
            TacticalMap.render(g, font, f, pt);
            hint(g, font, w, h);
            return;
        }

        int c = 0xE0E8E8E8;
        int m = 16, l = 18;
        // F5 gives an external view: no video feed, only telemetry and crosshair.
        boolean feed = mc.options.getCameraType().isFirstPerson();
        if (feed) {
            // Light interference and scan lines, as with analogue video.
            staticNoise(g, w, h, 0.05f);
            for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x14000000);
        }
        // Frame corners.
        g.fill(m, m, m + l, m + 1, c);
        g.fill(m, m, m + 1, m + l, c);
        g.fill(w - m - l, m, w - m, m + 1, c);
        g.fill(w - m - 1, m, w - m, m + l, c);
        g.fill(m, h - m - 1, m + l, h - m, c);
        g.fill(m, h - m - l, m + 1, h - m, c);
        g.fill(w - m - l, h - m - 1, w - m, h - m, c);
        g.fill(w - m - 1, h - m - l, w - m, h - m, c);
        // Gapped crosshair.
        g.fill(cx - 22, cy, cx - 5, cy + 1, c);
        g.fill(cx + 5, cy, cx + 22, cy + 1, c);
        g.fill(cx, cy - 22, cx + 1, cy - 5, c);
        g.fill(cx, cy + 5, cx + 1, cy + 22, c);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFF4030);

        // Target marker.
        target(g, f.target(), w, h, 0xFFFF3030);
        if (newTarget != null && newTargetTicks > 0 && (newTargetTicks / 4) % 2 == 0) target(g, newTarget, w, h, 0xFF30FF60);

        // Telemetry.
        double alt = p.getY() - ua.zentix.incoming.entity.StrikeProjectile.surfaceY(mc.level, p.getX(), p.getZ());
        double kmh = ClientFlights.kmh(p.speed());
        double dist = p.position().distanceTo(f.target());
        boolean rec = (mc.level.getGameTime() / 10) % 2 == 0;
        String left1 = (rec ? "● " : "  ") + Component.translatable("incoming.camera.rec").getString() + "  "
                + Component.translatable("incoming.hud.flight", f.weapon().displayName().getString().toUpperCase(Locale.ROOT), f.number).getString();
        g.drawString(font, left1, m + 6, m + 6, rec ? 0xFFFF4040 : 0xFFE8E8E8);
        g.drawString(font, f.phase().displayName().getString().toUpperCase(Locale.ROOT),
                m + 6, m + 18, f.phase() == FlightPhase.TERMINAL ? 0xFFFF4040 : 0xFFE8E8E8);
        Component[] right = {
                Component.translatable("incoming.camera.alt", String.format(Locale.ROOT, "%.0f", alt)),
                Component.translatable("incoming.camera.speed", String.format(Locale.ROOT, "%.0f", kmh)),
                Component.translatable("incoming.camera.range", String.format(Locale.ROOT, "%.0f", dist)),
                Component.translatable("incoming.hud.eta", StrikesHud.clock(f.etaSeconds(pt)))
        };
        for (int i = 0; i < right.length; i++) {
            g.drawString(font, right[i], w - m - 6 - font.width(right[i]), m + 6 + 12 * i, i == 3 ? 0xFFFFC040 : 0xFFE8E8E8);
        }
        if (f.nuclear()) g.drawString(font, "☢", cx - font.width("☢") / 2, m + 6, 0xFFFFD020);
        if (p.pursuit() != StrikeProjectile.Pursuit.NONE) {
            // Sighted target: projectile tracks it only while in frame.
            boolean held = p.pursuit() == StrikeProjectile.Pursuit.HELD;
            Component pursuit = Component.translatable(held ? "incoming.camera.pursuit.held" : "incoming.camera.pursuit.waiting")
                    .withStyle(held ? ChatFormatting.GREEN : ChatFormatting.GOLD, ChatFormatting.BOLD);
            g.drawString(font, pursuit, cx - font.width(pursuit) / 2, h - 60, 0xFFFFFFFF);
        }
        if (f.phase() == FlightPhase.LOITER && rec) {
            // Loitering munition circles: operator searches for a target in frame.
            Component loiter = Component.translatable("incoming.camera.loiter").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
            g.drawString(font, loiter, cx - font.width(loiter) / 2, h - 48, 0xFFFFFFFF);
        }
        hint(g, font, w, h);
    }

    /** Cinema frame 2.39:1: black bars above and below. */
    private static void letterbox(GuiGraphics g, int w, int h) {
        int bar = Math.max(0, (h - (int) (w / 2.39)) / 2);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);
    }

    private static void caption(GuiGraphics g, Font font, Component text, int w, int h) {
        int bar = Math.max(0, (h - (int) (w / 2.39)) / 2);
        g.drawString(font, text, 20, Math.max(6, bar / 2 - 4), 0xFFFFFFFF);
    }

    private static void hint(GuiGraphics g, Font font, int w, int h) {
        Component hint = Component.translatable("incoming.camera.hint", ua.zentix.incoming.client.Keys.CAMERA.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.GRAY);
        g.drawString(font, hint, w / 2 - font.width(hint) / 2, h - 34, 0xFFFFFFFF);
    }

    private static void target(GuiGraphics g, Vec3 at, int w, int h, int color) {
        float[] s = ScreenProjection.project(at);
        if (s == null) return;
        int x = (int) (s[0] * w), y = (int) (s[1] * h);
        if (x < 0 || x > w || y < 0 || y > h) return;
        ua.zentix.incoming.client.hud.HudDraw.box(g, x, y, 7, color);
    }

    private static void big(GuiGraphics g, Font font, Component text, int cx, int y, float scale) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, 0xFFFFFFFF);
        g.pose().popPose();
    }

    /** Interference: random grey strokes; {@code amount} is the screen fraction. */
    private static void staticNoise(GuiGraphics g, int w, int h, float amount) {
        int n = (int) (w * h / 40 * amount);
        for (int i = 0; i < n; i++) {
            int x = NOISE.nextInt(w), y = NOISE.nextInt(h);
            int v = NOISE.nextInt(200) + 55;
            int len = 1 + NOISE.nextInt(6);
            g.fill(x, y, x + len, y + 1, 0xFF000000 | v << 16 | v << 8 | v);
        }
    }
}
