package ua.zentix.incoming.strike;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.UUID;

/**
 * Order lifecycle ({@link ua.zentix.incoming.api}): the one admission step every entry takes once Incoming's own checks
 * passed (designator, commands, fixed launcher, nuclear key wait, {@code nuke now}), and the round events. Server thread
 * only.
 */
public final class Orders {
    /** {@link StrikeOrderEvent.Request} listeners running now: an order they start runs again on a later tick. */
    private static int requesting;

    private Orders() {}

    /** What an admitted order does: pay, record, {@link Order#accept}, launch; on a shortfall {@link Order#reject} it. */
    public interface Admission {
        /** @return the entry's own result: launched or recorded */
        boolean run(Order order);
    }

    /**
     * An order entry must run elsewhere: off the server thread (another mod's worker) it runs on the server thread; inside
     * a {@link StrikeOrderEvent.Request} listener it runs on a later tick. Both rerun the whole entry from its outermost
     * call, so all its checks (rights, target, active-strike limit, a launcher's mission and stock) see the state of that
     * moment.
     *
     * @param entry the same call again
     * @return true: {@code entry} is queued, the caller returns (an order queued counts as given)
     */
    public static boolean elsewhere(ServerLevel level, Runnable entry) {
        var server = level.getServer();
        if (server != null && !server.isSameThread()) {
            server.execute(entry);
            return true;
        }
        if (requesting == 0) return false;
        Incoming.LOG.info("Strike order started inside another order's request, queued to a later tick");
        StrikeWorld.get(level).defer(level.getGameTime() + 1, entry);
        return true;
    }

    /**
     * An order entry started now in {@code level} would run elsewhere ({@link #elsewhere}): off the server thread or inside
     * a {@link StrikeOrderEvent.Request} listener. Nothing is queued.
     */
    public static boolean deferred(ServerLevel level) {
        var server = level.getServer();
        return server != null && !server.isSameThread() || requesting > 0;
    }

    /**
     * Ask the listeners ({@link StrikeOrderEvent.Request}), then run {@code body}, which pays and answers. Request, Rejected
     * and Accepted are posted at once: they are the admission itself, and a second order must see the first one's
     * Accepted (a cooldown, a balance). Exactly one Rejected or Accepted follows: a veto rejects before payment; a body that
     * did not answer (exception) is rejected with {@link StrikeOrderEvent.Reason#ERROR}. A stopping server admits nothing:
     * its unsaved waits are already settled.
     *
     * @return {@code body}'s result; false if vetoed or the server is stopping
     */
    public static boolean admit(Order order, Admission body) {
        var server = order.level().getServer();
        if (server != null && !server.isRunning()) {
            Incoming.LOG.info("Strike order {} ×{} — {} not admitted: the server is stopping", order.weapon().getSerializedName(), order.count(),
                    order.actorName());
            return false;
        }
        if (elsewhere(order.level(), () -> admit(order, body))) return true;
        StrikeOrderEvent.Request request = new StrikeOrderEvent.Request(order);
        boolean asked;
        requesting++;
        try {
            asked = publish(request);
        } finally {
            requesting--;
        }
        if (!asked || request.isCanceled()) {
            order.reject(asked ? StrikeOrderEvent.Reason.VETOED : StrikeOrderEvent.Reason.ERROR);
            Component why = request.getRefusal() != null ? request.getRefusal() : Component.translatable("incoming.order.refused");
            if (order.feedback() != null) order.feedback().accept(why);
            Incoming.LOG.info("Strike order {} refused{}: {} ×{} at {} {} {} — {}", order.id(), asked ? " by a listener" : " (listener error)",
                    order.weapon().getSerializedName(), order.count(), Mth.floor(order.target().x), Mth.floor(order.target().y),
                    Mth.floor(order.target().z), order.actorName());
            return false;
        }
        try {
            boolean ok = body.run(order);
            if (!order.answered()) {
                Incoming.LOG.error("Strike order {} finished admission without an answer", order.id());
                order.reject(StrikeOrderEvent.Reason.ERROR);
            }
            return ok;
        } catch (RuntimeException e) {
            if (!order.answered()) order.reject(StrikeOrderEvent.Reason.ERROR);
            throw e;
        }
    }

    /** Round {@code r} left at {@code origin} toward {@code target}. */
    public static void released(ServerLevel level, RoundRef r, WeaponType weapon, @Nullable UUID owner, Vec3 origin, Vec3 target) {
        queue(level, new StrikeRoundEvent.Released(level.dimension(), r, weapon.getSerializedName(), owner, origin, target));
    }

    /** Round {@code r} is over. Callers end each round once: its carrier forgets it in the same step. */
    public static void ended(ServerLevel level, RoundRef r, WeaponType weapon, @Nullable UUID owner, RoundEnd end) {
        queue(level, new StrikeRoundEvent.Ended(level.dimension(), r, weapon.getSerializedName(), owner, end));
    }

    /**
     * Post {@code event} at the end of this world tick (or of the server tick), in the order queued: round, detonation and
     * grid events happen inside Incoming's own loops over salvos, key waits, flights and scheduled strikes, where a
     * listener must not run, and a round's events reach every listener in order (a listener reacting to Released cannot
     * make Ended overtake it). One queue for the whole server, kept with the overworld: a round that changes dimension
     * keeps its order.
     */
    public static void queue(ServerLevel level, Event event) {
        StrikeWorld.get(level.getServer().overworld()).events().add(event);
    }

    /** Deliver the queued events; events their listeners queue follow in the same pass. */
    public static void deliver(MinecraftServer server) {
        ServerLevel home = server.overworld();
        if (home == null || !home.hasData(ModAttachments.STRIKE_WORLD)) return;
        StrikeWorld world = StrikeWorld.get(home);
        if (world.events().isEmpty() || world.delivering) return;
        world.delivering = true;
        try {
            for (Event e; (e = world.events().poll()) != null; ) publish(e);
        } finally {
            world.delivering = false;
        }
    }

    /** {@link #deliver(MinecraftServer)} for {@code level}'s server. */
    public static void deliver(ServerLevel level) {
        deliver(level.getServer());
    }

    /** Server tick end, after impacts and nuclear work: whatever they queued goes out this tick. */
    public static void onServerTick(ServerTickEvent.Post event) {
        deliver(event.getServer());
    }

    /**
     * Post on the game bus now; a listener's failure, whatever it throws, is logged, never thrown into a projectile's
     * removal, a salvo or a world tick (the virtual machine's own errors still propagate).
     *
     * @return false if a listener failed
     */
    public static boolean publish(Event event) {
        try {
            NeoForge.EVENT_BUS.post(event);
            return true;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            Incoming.LOG.error("A listener of {} failed", event.getClass().getSimpleName(), e);
            return false;
        }
    }
}
