package ua.zentix.incoming.strike;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Visibility;

import java.util.UUID;

/**
 * Unpaid short-lived launch review. Does not claim terrain preparation: until a pre-launch admission contract exists,
 * launching retains the current backend. No reservation, worker, chunk ticket or saved order is created here.
 * One player attachment bounds memory; restart/logout/death cannot launch or lose a munition.
 */
public final class NuclearReview {
    public static final int HOLD_TICKS = 60;
    private static final int EXPIRE_TICKS = 1200;
    @Nullable private Entry entry;
    private long lastRequest = Long.MIN_VALUE;

    private record Entry(UUID id, C2S.Fire order, ServerActions.Aim aim, ResourceKey<Level> dimension,
                         long expires, float visibility, float scale, int cap, int flight, boolean carrier,
                         boolean opsOnly, int keyWindow, int keyDistance, long heldAt) {
        Entry held(long tick) {
            return new Entry(id, order, aim, dimension, expires, visibility, scale, cap, flight, carrier, opsOnly, keyWindow, keyDistance, tick);
        }
    }

    public NuclearReview() {}

    public static void request(C2S.ReviewNuclear packet, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer p) request(p, packet.order());
    }

    public static void request(ServerPlayer p, C2S.Fire order) {
        NuclearReview state = p.getData(ModAttachments.NUCLEAR_REVIEW);
        long now = p.serverLevel().getGameTime();
        if (state.lastRequest != Long.MIN_VALUE && now - state.lastRequest < 4) return;
        state.lastRequest = now;
        state.entry = null;
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
        var c = IncomingConfig.SERVER;
        Entry e = new Entry(UUID.randomUUID(), order, aim, p.level().dimension(), now + EXPIRE_TICKS,
                visibility(p), (float) c.effectsScale(), c.nukeMaxYield.get(), c.nukeFlightTime.get(), c.carrierNukes.get(),
                c.nukeOpsOnly.get(), c.nukeSecondKeyWindow.get(), c.nukeSecondKeyDistance.get(), -1);
        state.entry = e;
        PacketDistributor.sendToPlayer(p, new S2C.NuclearReview(e.id, order, aim.point(), e.visibility, e.scale));
    }

    /** Current unpaid review, for status readers; no world access. */
    public java.util.Optional<S2C.NuclearReview> snapshot() {
        Entry e = entry;
        return e == null ? java.util.Optional.empty() : java.util.Optional.of(new S2C.NuclearReview(e.id, e.order, e.aim.point(), e.visibility, e.scale));
    }

    private static boolean allowed(ServerPlayer p, C2S.Fire order) {
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

    private static void finish(ServerPlayer p, Entry e) {
        PacketDistributor.sendToPlayer(p, new S2C.NuclearReview(e.id, e.order, e.aim.point(), e.visibility, e.scale, true));
    }

    /** Public for counted-tick GameTests; identity and all authority live server-side. */
    public static boolean act(ServerPlayer p, UUID id, int action) {
        NuclearReview state = p.getData(ModAttachments.NUCLEAR_REVIEW);
        Entry e = state.entry;
        if (e == null || !e.id.equals(id)) return false;
        if (action == C2S.NuclearAction.DISCARD) { state.entry = null; return true; }
        if (action == C2S.NuclearAction.RELEASE) { state.entry = e.held(-1); return true; }
        long now = p.serverLevel().getGameTime();
        var c = IncomingConfig.SERVER;
        if (!p.level().dimension().equals(e.dimension) || now > e.expires || !allowed(p, e.order)
                || e.cap != c.nukeMaxYield.get() || e.flight != c.nukeFlightTime.get() || e.carrier != c.carrierNukes.get()
                || e.opsOnly != c.nukeOpsOnly.get() || e.keyWindow != c.nukeSecondKeyWindow.get() || e.keyDistance != c.nukeSecondKeyDistance.get()
                || e.scale != (float) c.effectsScale() || e.visibility != visibility(p)) {
            state.entry = null;
            p.sendSystemMessage(Component.translatable("incoming.nuke.review.changed"));
            finish(p, e);
            return false;
        }
        if (action == C2S.NuclearAction.BEGIN) {
            if (e.heldAt < 0) state.entry = e.held(now);
            return true;
        }
        if (action != C2S.NuclearAction.COMMIT || e.heldAt < 0 || now - e.heldAt < HOLD_TICKS) return false;
        state.entry = null; // consume before dispatch: duplicates cannot pay or launch twice
        Vec3 current = e.aim.target().subject().resolve(p.serverLevel()).orElse(null);
        if (current == null || current.distanceToSqr(e.aim.point()) > 1 || ServerActions.ruled(p, e.aim) == null) {
            p.sendSystemMessage(Component.translatable("incoming.nuke.review.changed"));
            finish(p, e);
            return false;
        }
        ServerActions.fire(p, e.order, e.aim);
        finish(p, e);
        return true;
    }
}
