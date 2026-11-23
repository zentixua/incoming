package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /incoming status} arithmetic ({@link OrderStatus}): rounds grouped by order with their phases; one round counted
 * once, at its later phase; an arrival is unknown while any round of the order is unlaunched or unpredictable, absent
 * with only impact work left; filters by owner (an unowned round is nobody's) and tag.
 */
class OrderStatusTest {
    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2);

    private static RoundRef round(UUID order, int i, String tag) {
        return RoundRef.of(order, i, tag);
    }

    @Test
    void linesCountPhasesAndArrival() {
        UUID salvo = new UUID(1, 1), icbm = new UUID(1, 2), hit = new UUID(1, 3), flying = new UUID(1, 4);
        List<OrderStatus.Round> rounds = List.of(
                // a salvo of four: two waiting, one in the air (arrives in 400 ticks), one hit and still working
                OrderStatus.Round.queued(round(salvo, 3, "w"), ALICE, WeaponType.DRONE),
                OrderStatus.Round.queued(round(salvo, 2, "w"), ALICE, WeaponType.DRONE),
                OrderStatus.Round.flight(round(salvo, 1, "w"), ALICE, WeaponType.DRONE, 400),
                OrderStatus.Round.impact(round(salvo, 0, "w"), ALICE, WeaponType.DRONE),
                // an ICBM past due, waiting for its chunk
                OrderStatus.Round.flight(round(icbm, 0, null), null, WeaponType.NUKE, OrderStatus.UNKNOWN),
                // only impact work left
                OrderStatus.Round.impact(round(hit, 0, null), BOB, WeaponType.MISSILE),
                // two in the air: the order arrives with the later one
                OrderStatus.Round.flight(round(flying, 0, null), BOB, WeaponType.MISSILE, 120),
                OrderStatus.Round.flight(round(flying, 1, null), BOB, WeaponType.MISSILE, 300));
        List<OrderStatus.Line> lines = OrderStatus.lines(rounds);
        assertEquals(List.of(flying, salvo, icbm, hit), lines.stream().map(OrderStatus.Line::order).toList(),
                "known arrivals first, then unknown, then impact work only");
        OrderStatus.Line f = lines.get(0), s = lines.get(1), n = lines.get(2), h = lines.get(3);
        assertEquals(300, f.eta());
        assertEquals(new OrderStatus.Line(salvo, "w", ALICE, WeaponType.DRONE, 2, 1, 1, OrderStatus.UNKNOWN), s);
        assertEquals(4, s.unfinished());
        assertEquals(OrderStatus.UNKNOWN, n.eta());
        assertEquals(OrderStatus.NONE, h.eta());
        assertEquals(1, h.impact());
    }

    @Test
    void oneRoundCountsOnceAtItsLaterPhase() {
        UUID order = new UUID(2, 1);
        RoundRef r = round(order, 0, "t");
        List<OrderStatus.Round> seen = OrderStatus.distinct(List.of(
                OrderStatus.Round.flight(r, ALICE, WeaponType.BUNKER, 50),
                OrderStatus.Round.impact(r, ALICE, WeaponType.BUNKER),
                OrderStatus.Round.queued(r, ALICE, WeaponType.BUNKER)), OrderStatus.Filter.ALL);
        assertEquals(1, seen.size());
        assertEquals(OrderStatus.Phase.IMPACT, seen.getFirst().phase());
    }

    @Test
    void filtersByOwnerAndTag() {
        UUID a = new UUID(3, 1), b = new UUID(3, 2), c = new UUID(3, 3);
        List<OrderStatus.Round> rounds = List.of(
                OrderStatus.Round.queued(round(a, 0, "w1"), ALICE, WeaponType.DRONE),
                OrderStatus.Round.queued(round(b, 0, "w1"), BOB, WeaponType.DRONE),
                OrderStatus.Round.queued(round(c, 0, "w1"), null, WeaponType.DRONE),
                OrderStatus.Round.queued(round(c, 1, "W1"), null, WeaponType.DRONE));
        assertEquals(3, OrderStatus.distinct(rounds, new OrderStatus.Filter(null, "w1")).size(), "tags are case-sensitive");
        assertEquals(1, OrderStatus.distinct(rounds, new OrderStatus.Filter(Set.of(ALICE), "w1")).size());
        assertEquals(0, OrderStatus.distinct(rounds, new OrderStatus.Filter(Set.of(ALICE), "w2")).size());
        assertEquals(2, OrderStatus.distinct(rounds, new OrderStatus.Filter(Set.of(ALICE, BOB), null)).size(), "an unowned round is nobody's");
        assertEquals(4, OrderStatus.distinct(rounds, OrderStatus.Filter.ALL).size());
    }
}
