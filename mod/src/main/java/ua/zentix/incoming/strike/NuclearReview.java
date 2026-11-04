package ua.zentix.incoming.strike;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Visibility;

import java.util.UUID;
import ua.zentix.incoming.nuclear.world.RuinPreparation;
import ua.zentix.incoming.target.Target;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Unpaid exact-order review and leased preparation. Confirmation is accepted only while actually READY. */
public final class NuclearReview {
    public static final int HOLD_TICKS = 60;
    private static final int EXPIRE_TICKS = 1200;
    @Nullable private Entry entry;
    private long lastRequest = Long.MIN_VALUE;

    private static final class Entry {
        final UUID id = UUID.randomUUID();
        final C2S.Fire order;
        final ServerActions.Aim aim;
        final ResourceKey<Level> dimension;
        final float visibility, scale;
        final int cap, flight, keyWindow, keyDistance;
        final boolean carrier, opsOnly;
        long expires, heldAt = -1, lastPoll = Long.MIN_VALUE;
        boolean preparing;
        Entry(ServerPlayer p, C2S.Fire order, ServerActions.Aim aim, long now) {
            this.order = order; this.aim = aim; dimension = p.level().dimension();
            var c = IncomingConfig.SERVER;
            expires = now + EXPIRE_TICKS; visibility = visibility(p); scale = (float) c.effectsScale();
            cap = c.nukeMaxYield.get(); flight = c.nukeFlightTime.get(); carrier = c.carrierNukes.get();
            opsOnly = c.nukeOpsOnly.get(); keyWindow = c.nukeSecondKeyWindow.get(); keyDistance = c.nukeSecondKeyDistance.get();
        }
    }

    public NuclearReview() {}

    public static void request(C2S.ReviewNuclear packet, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer p) request(p, packet.order());
    }

    public static void request(ServerPlayer p, C2S.Fire order) {
        if (!p.isAlive() || p.isRemoved()) { cancel(p); return; }
        if (NuclearKeys.preparingOrder(p)) {
            p.sendSystemMessage(Component.translatable("incoming.nuke.prepare.pending")); return;
        }
        NuclearReview state = p.getData(ModAttachments.NUCLEAR_REVIEW);
        long now = p.serverLevel().getGameTime();
        if (state.lastRequest != Long.MIN_VALUE && now - state.lastRequest < 4) return;
        state.lastRequest = now;
        cancel(p);
        NuclearCommands.cancelPlayer(p);
        if (!allowed(p, order)) return;
        // Linked launchers use their existing assignment path; ICBM is not a fixed-launcher weapon.
        if (!order.from().isEmpty() || !order.via().isEmpty()) {
            var problem = ServerActions.routeProblem(p.serverLevel(), p, true, order.loadout().weapon(),
                    order.from().map(LaunchOrigin.Place::new).orElse(null), order.via(),
                    order.aim().map(C2S.AimHint::point).orElse(p.position()));
            if (problem != null) { p.sendSystemMessage(problem); return; }
        }
        ServerActions.Aim aim = ServerActions.reviewAim(p, order);
        if (aim == null || (aim = ServerActions.ruled(p, aim)) == null) return;
        Entry e = new Entry(p, order, aim, now);
        state.entry = e;
        PacketDistributor.sendToPlayer(p, new S2C.NuclearReview(e.id, order, aim.point(), e.visibility, e.scale));
    }

    /** Current unpaid review, for status readers; no world access. */
    public java.util.Optional<S2C.NuclearReview> snapshot() {
        Entry e = entry;
        return e == null ? java.util.Optional.empty() : java.util.Optional.of(new S2C.NuclearReview(e.id, e.order, e.aim.point(), e.visibility, e.scale));
    }

    private static boolean allowed(ServerPlayer p, C2S.Fire order) {
        if (order.from().isPresent()) {
            var problem = ServerActions.launchSiteProblem(p, order.from().get());
            if (problem != null) { p.sendSystemMessage(problem); return false; }
        }
        if (!ua.zentix.incoming.launcher.LauncherLinks.of(ServerActions.designator(p)).isEmpty()) {
            p.sendSystemMessage(Component.translatable("incoming.fixed_launcher.no_nuke"));
            return false;
        }
        if (!order.loadout().nuclear() || !ServerActions.mayUse(p) || !ServerActions.mayUseNuke(p)) {
            p.sendSystemMessage(Component.translatable("incoming.no_permission"));
            return false;
        }
        if (order.loadout().nuke().yieldKt() > IncomingConfig.SERVER.nukeMaxYield.get()
                || order.loadout().nuke().onCarrier() && !order.loadout().weapon().spec().warhead().always()
                && !IncomingConfig.SERVER.carrierNukes.get()) {
            p.sendSystemMessage(Component.translatable("incoming.nuke.review.cap", IncomingConfig.SERVER.nukeMaxYield.get()));
            return false;
        }
        return true;
    }

    /** Current weather estimate, using the same clear/rain/thunder visibility anchors as the event. */
    public static float visibility(ServerPlayer p) {
        return (float) (p.serverLevel().isThundering() ? Visibility.THUNDER : p.serverLevel().isRaining() ? Visibility.RAIN : Visibility.CLEAR);
    }

    public static void action(C2S.NuclearAction packet, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer p) act(p, packet.id(), packet.action());
    }

    /** Discard authority before notifying the client, so an already queued COMMIT cannot survive cancellation. */
    public static boolean cancel(ServerPlayer p) {
        if (!p.hasData(ModAttachments.NUCLEAR_REVIEW)) return false;
        NuclearReview state = p.getData(ModAttachments.NUCLEAR_REVIEW);
        Entry e = state.entry;
        state.entry = null;
        if (e == null) return false;
        releasePreparation(p, e);
        finish(p, e);
        return true;
    }

    /** Global nuclear abort includes unpaid reviews across all dimensions. No inventory was reserved. */
    public static int cancelAll(java.util.Collection<? extends ServerPlayer> players) {
        int cancelled = 0;
        for (ServerPlayer p : players) if (cancel(p)) cancelled++;
        return cancelled;
    }

    /** Respawn attachment loss happens too late: invalidate on the actual, non-cancelled death event. */
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) cancel(p);
    }

    private static void finish(ServerPlayer p, Entry e) {
        PacketDistributor.sendToPlayer(p, new S2C.NuclearReview(e.id, e.order, e.aim.point(), e.visibility, e.scale, true));
    }

    private static void releasePreparation(ServerPlayer p, Entry e) {
        var level = p.server.getLevel(e.dimension);
        if (level != null && e.preparing) RuinPreparation.cancel(level, e.id);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) cancel(p);
    }

    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) cancel(p);
    }

    public static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.hasData(ModAttachments.NUCLEAR_REVIEW)) {
            Entry e = p.getData(ModAttachments.NUCLEAR_REVIEW).entry;
            if (e != null && (!p.isAlive() || p.isRemoved() || !p.level().dimension().equals(e.dimension)
                    || p.serverLevel().getGameTime() >= e.expires)) cancel(p);
        }
    }

    /** Re-resolve through disclosure rules; a ground estimate changing Y is not target movement. */
    private static boolean targetValid(ServerPlayer p, Entry e) {
        var current = ServerActions.reviewAim(p, e.order);
        if (current == null || ServerActions.ruled(p, current) == null) return false;
        if (e.aim.target() instanceof Target.Ground) {
            return current.target() instanceof Target.Ground && current.point().x == e.aim.point().x && current.point().z == e.aim.point().z;
        }
        return current.point().distanceToSqr(e.aim.point()) <= 1;
    }

    private static void status(ServerPlayer p, Entry e) {
        var s = RuinPreparation.status(p.serverLevel(), e.id);
        PacketDistributor.sendToPlayer(p, new S2C.NuclearPreparation(e.id, s.stage().ordinal(), s.ready(), s.total(),
                s.reason().ordinal(), java.util.Optional.ofNullable(s.resolvedTarget())));
    }

    /** Public for counted-tick GameTests; identity and all authority live server-side. */
    public static boolean act(ServerPlayer p, UUID id, int action) {
        if (!p.isAlive() || p.isRemoved()) { cancel(p); return false; }
        NuclearReview state = p.getData(ModAttachments.NUCLEAR_REVIEW);
        Entry e = state.entry;
        if (e == null || !e.id.equals(id)) return false;
        if (action == C2S.NuclearAction.DISCARD) { cancel(p); return true; }
        if (action == C2S.NuclearAction.RELEASE) { e.heldAt = -1; return true; }
        long now = p.serverLevel().getGameTime();
        var c = IncomingConfig.SERVER;
        if (!p.level().dimension().equals(e.dimension) || now >= e.expires || !allowed(p, e.order)
                || e.cap != c.nukeMaxYield.get() || e.flight != c.nukeFlightTime.get() || e.carrier != c.carrierNukes.get()
                || e.opsOnly != c.nukeOpsOnly.get() || e.keyWindow != c.nukeSecondKeyWindow.get() || e.keyDistance != c.nukeSecondKeyDistance.get()
                || e.scale != (float) c.effectsScale() || e.visibility != visibility(p) || !targetValid(p, e)) {
            cancel(p);
            p.sendSystemMessage(Component.translatable("incoming.nuke.review.changed"));
            return false;
        }
        if (action == C2S.NuclearAction.POLL || action == C2S.NuclearAction.PREPARE) {
            boolean firstPreparation = action == C2S.NuclearAction.PREPARE && !e.preparing;
            if (!firstPreparation && e.lastPoll != Long.MIN_VALUE && now - e.lastPoll < 10) return false;
            e.lastPoll = now;
            e.expires = now + EXPIRE_TICKS;
            if (action == C2S.NuclearAction.PREPARE && !e.preparing) {
                e.preparing = true;
                var l = e.order.loadout();
                RuinPreparation.prepare(p.serverLevel(), e.id, p.getUUID(), e.aim.point(), l.nuke().yieldKt(),
                        e.aim.target() instanceof Target.Ground, l.weapon().spec().warhead().chooseBurst() && l.nuke().airBurst());
            }
            if (e.preparing) {
                RuinPreparation.keepAlive(p.serverLevel(), e.id, p.getUUID());
                if (RuinPreparation.status(p.serverLevel(), e.id).stage() != RuinPreparation.Stage.READY) e.heldAt = -1;
                status(p, e);
            }
            return true;
        }
        if (!e.preparing || RuinPreparation.status(p.serverLevel(), e.id).stage() != RuinPreparation.Stage.READY) {
            e.heldAt = -1;
            return false;
        }
        if (action == C2S.NuclearAction.BEGIN) {
            if (e.heldAt < 0) e.heldAt = now;
            return true;
        }
        if (action != C2S.NuclearAction.COMMIT || e.heldAt < 0 || now - e.heldAt < HOLD_TICKS) return false;
        state.entry = null; // consume before payment or dispatch: duplicates cannot launch twice
        var l = e.order.loadout();
        Vec3 resolved = RuinPreparation.status(p.serverLevel(), e.id).resolvedTarget();
        ServerActions.Aim aim = new ServerActions.Aim(e.aim.target() instanceof Target.Ground ? new Target.Ground(resolved) : e.aim.target(),
                resolved, e.aim.label());
        LaunchOrigin from = e.order.from().map(at -> (LaunchOrigin) new LaunchOrigin.Place(at)).orElse(null);
        boolean accepted = ServerActions.strikePrepared(p, l.weapon(), 1, 0, aim, l.nuke(), e.order.via(), from, true, e.id);
        if (!accepted) releasePreparation(p, e);
        finish(p, e);
        return true; // exact confirmation consumed, even when ordinary payment or launch failed
    }
}
