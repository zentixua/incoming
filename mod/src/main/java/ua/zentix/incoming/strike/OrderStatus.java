package ua.zentix.incoming.strike;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Unfinished rounds of admitted orders ({@code /incoming status}), read from where each round lives. A round is unfinished
 * from its order's {@code Accepted} until its {@code Ended} ({@link Orders}), and after an impact until its own impact
 * work is done ({@link ImpactWork}, {@link NuclearEvents.Impact}): so none left means every matching round has ended and
 * its work is over. Rounds saved before order tracking carry no round id and are not counted (they post no events either);
 * an order entry deferred to a later tick is not admitted yet. Server thread only.
 */
public final class OrderStatus {
    /** Arrival cannot be predicted (salvo or second key not launched yet, a strike waiting for its chunk). */
    public static final int UNKNOWN = -1;
    /** No arrival: only impact work is left. */
    public static final int NONE = -2;

    private OrderStatus() {}

    /** Where a round is: not launched yet, in the air (or on its rail, or a scheduled ICBM), or its impact work runs. */
    public enum Phase { QUEUED, FLIGHT, IMPACT }

    /**
     * One unfinished round.
     *
     * @param owner the order's owner; null for console, command blocks and unowned launchers
     * @param eta   ticks until it arrives ({@link Phase#FLIGHT}), {@link #UNKNOWN} otherwise
     */
    public record Round(RoundRef ref, @Nullable UUID owner, WeaponType weapon, Phase phase, int eta) {
        public static Round queued(RoundRef ref, @Nullable UUID owner, WeaponType weapon) {
            return new Round(ref, owner, weapon, Phase.QUEUED, UNKNOWN);
        }

        public static Round flight(RoundRef ref, @Nullable UUID owner, WeaponType weapon, int eta) {
            return new Round(ref, owner, weapon, Phase.FLIGHT, eta < 0 ? UNKNOWN : eta);
        }

        public static Round impact(RoundRef ref, @Nullable UUID owner, WeaponType weapon) {
            return new Round(ref, owner, weapon, Phase.IMPACT, UNKNOWN);
        }
    }

    /**
     * Which rounds a status asks about.
     *
     * @param owners only these owners' rounds; null means any owner
     * @param tag    only rounds of orders with this group name; null means any
     */
    public record Filter(@Nullable Set<UUID> owners, @Nullable String tag) {
        public static final Filter ALL = new Filter(null, null);

        public boolean test(Round r) {
            return (owners == null || r.owner() != null && owners.contains(r.owner())) && (tag == null || tag.equals(r.ref().tag()));
        }
    }

    /**
     * One order's unfinished rounds by phase.
     *
     * @param weapon the weapon of its first round seen (a nuclear impact shows as {@link WeaponType#NUKE})
     * @param eta    ticks until its last known arrival; {@link #UNKNOWN} if any queued or in-flight round cannot be
     *               predicted; {@link #NONE} with only impact work left
     */
    public record Line(UUID order, @Nullable String tag, @Nullable UUID owner, WeaponType weapon, int queued, int flight, int impact, int eta) {
        public int unfinished() {
            return queued + flight + impact;
        }
    }

    /** Every unfinished round on {@code server} passing {@code filter}, one per round id. */
    public static List<Round> rounds(MinecraftServer server, Filter filter) {
        List<Round> all = new ArrayList<>();
        // an order inside its admission: its accepted rounds before their carrier exists (an Accepted listener asks)
        for (Order o : Orders.admitting()) {
            for (int i = 0; i < o.admitted(); i++) all.add(Round.queued(o.round(i), o.owner(), o.weapon()));
        }
        for (ServerLevel level : server.getAllLevels()) collect(level, all);
        return distinct(all, filter);
    }

    /**
     * {@code rounds} passing {@code filter}, one per round id: a round is in one place, and should a hand-over show it in
     * two at once, the later phase is where it is.
     */
    public static List<Round> distinct(Collection<Round> rounds, Filter filter) {
        Map<UUID, Round> byId = new LinkedHashMap<>();
        for (Round r : rounds) {
            if (filter.test(r)) byId.merge(r.ref().round(), r, (a, b) -> b.phase().ordinal() > a.phase().ordinal() ? b : a);
        }
        return List.copyOf(byId.values());
    }

    /** The rounds that live in {@code level}. */
    private static void collect(ServerLevel level, List<Round> out) {
        long now = level.getGameTime();
        SalvoData.get(level).rounds(out::add);
        if (level.hasData(ModAttachments.STRIKE_WORLD)) {
            StrikeWorld world = StrikeWorld.get(level);
            world.keys().rounds(out::add);
            for (ImpactWork.Impact i : world.impactWork().running()) out.add(Round.impact(i.round(), i.owner(), i.weapon()));
        }
        for (StrikeProjectile p : StrikeWorld.projectiles(level, true)) {
            RoundRef r = p.round();
            if (r != null) out.add(Round.flight(r, p.ownerId(), p.weapon(), p.etaTicks()));
        }
        NuclearEvents events = NuclearEvents.get(level);
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            // a `nuke now` waiting for its ground has no flight (launch and burst time are one): queued; an ICBM past due
            // waits for its chunk too, arrival unknown
            s.round().ifPresent(r -> out.add(s.launchTime() == s.detonateTime() ? Round.queued(r, s.owner().orElse(null), WeaponType.NUKE)
                    : Round.flight(r, s.owner().orElse(null), WeaponType.NUKE,
                    now < s.detonateTime() ? (int) Math.min(Integer.MAX_VALUE, s.detonateTime() - now) : UNKNOWN)));
        }
        for (NuclearEvents.Impact i : events.impacts()) out.add(Round.impact(i.round(), i.owner().orElse(null), WeaponType.NUKE));
    }

    /**
     * Rounds grouped by order: soonest known arrival first, then unpredictable ones, then those with only impact work
     * left; order id breaks ties.
     */
    public static List<Line> lines(Collection<Round> rounds) {
        Map<UUID, List<Round>> byOrder = new LinkedHashMap<>();
        for (Round r : rounds) byOrder.computeIfAbsent(r.ref().order(), k -> new ArrayList<>()).add(r);
        List<Line> out = new ArrayList<>(byOrder.size());
        for (Map.Entry<UUID, List<Round>> e : byOrder.entrySet()) {
            int queued = 0, flight = 0, impact = 0, eta = NONE;
            boolean unknown = false;
            for (Round r : e.getValue()) {
                switch (r.phase()) {
                    case QUEUED -> {
                        queued++;
                        unknown = true;
                    }
                    case FLIGHT -> {
                        flight++;
                        if (r.eta() < 0) unknown = true;
                        else eta = Math.max(eta, r.eta());
                    }
                    case IMPACT -> impact++;
                }
            }
            Round first = e.getValue().getFirst();
            out.add(new Line(e.getKey(), first.ref().tag(), first.owner(), first.weapon(), queued, flight, impact, unknown ? UNKNOWN : eta));
        }
        out.sort(Comparator.comparingInt((Line l) -> l.eta() >= 0 ? 0 : l.eta() == UNKNOWN ? 1 : 2)
                .thenComparingInt(l -> Math.max(l.eta(), 0)).thenComparing(Line::order));
        return out;
    }
}
