package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Devtest-only continuation after finite-span Collapse decisions. Local contact components are condensed
 * before crossing section faces. No live level, tickets, original-removal journal or worldwide cell graph.
 * A missing snapshot is an unresolved dependency, never an anchor. A budget refusal publishes no plan.
 */
final class PreparedSupportPrototype {
    private static final int CELLS = 4096, PORTS = 6 * 256;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final byte ANCHOR = 1, OPEN = 2;

    record Key(int x, int y, int z) {
        Key next(Direction direction) {
            return new Key(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
        }
    }

    /** Caller owns coherent before/after snapshots; after differs only by already decided removals. */
    static final class Section {
        final Key key;
        private final BlockState[] states;
        private final BitSet removed;
        private final Blast.PropsView props;
        private final int minY;

        Section(Key key, BlockState[] states, BitSet removed, Blast.PropsView props, int minY) {
            if (states.length != CELLS || removed.length() > CELLS || props == Blast.SERVER)
                throw new IllegalArgumentException("Expected a complete detached section and published properties");
            this.key = key;
            this.states = states.clone();
            this.removed = (BitSet) removed.clone();
            this.props = props;
            this.minY = minY;
            for (int cell = removed.nextSetBit(0); cell >= 0; cell = removed.nextSetBit(cell + 1))
                if (props.get(states[cell]).fixed()) throw new IllegalArgumentException("Protected source cannot be removed");
        }

        private Blast.Props at(int cell) { return props.get(states[cell]); }
        private boolean exists(int cell, boolean after) { return (!after || !removed.get(cell)) && at(cell).bearing(); }
        private boolean anchor(int cell) { return at(cell).fixed() || key.y * 16 + (cell >> 8) == minY; }
    }

    record Budget(int sections, int components) {
        Budget {
            if (sections < 1 || components < 1) throw new IllegalArgumentException("Positive limits required");
        }
    }

    /** Immutable output identifies actual source cells; cargo stays in the unchanged source until commit. */
    static final class Plan {
        final Key key;
        private final BitSet direct, detached, unresolved;

        Plan(Section section, BitSet detached, BitSet unresolved) {
            this.key = section.key;
            this.direct = (BitSet) section.removed.clone();
            this.detached = (BitSet) detached.clone();
            this.unresolved = (BitSet) unresolved.clone();
        }

        BitSet direct() { return (BitSet) direct.clone(); }
        BitSet detached() { return (BitSet) detached.clone(); }
        BitSet unresolved() { return (BitSet) unresolved.clone(); }
    }

    record Metrics(int sections, int beforeComponents, int afterComponents, int detachedCells, int unresolvedCells,
                   long summaryBytes, long scratchBytes, long snapshotBytes, long outputBytes) {}
    record Result(List<Plan> plans, Metrics metrics) {
        Result { plans = List.copyOf(plans); }
    }

    private record Components(short[] ports, byte[] flags) {}
    private record Summary(Section section, Components before, Components after, short[] original, int beforeBase, int afterBase) {}

    static Result solve(List<Section> sections, Budget budget) {
        if (sections.size() > budget.sections) throw new IllegalArgumentException("Support section budget exceeded");
        var summaries = new LinkedHashMap<Key, Summary>();
        // The only cell-indexed graph workspace: reused for every section, including output reconstruction.
        short[] beforeLabels = new short[CELLS], afterLabels = new short[CELLS];
        int[] queue = new int[CELLS];
        int beforeCount = 0, afterCount = 0;
        long summaryBytes = 0;
        for (Section section : sections) {
            if (summaries.containsKey(section.key)) throw new IllegalArgumentException("Duplicate support section");
            Components before = components(section, false, beforeLabels, queue);
            Components after = components(section, true, afterLabels, queue);
            if ((long) beforeCount + afterCount + before.flags.length + after.flags.length > budget.components)
                throw new IllegalArgumentException("Support component budget exceeded");
            short[] original = new short[after.flags.length];
            for (int cell = 0; cell < CELLS; cell++) {
                int component = afterLabels[cell] - 1;
                if (component >= 0) original[component] = beforeLabels[cell];
            }
            summaries.put(section.key, new Summary(section, before, after, original, beforeCount, afterCount));
            beforeCount += before.flags.length;
            afterCount += after.flags.length;
            summaryBytes += 192L + 2L * (before.ports.length + after.ports.length + original.length)
                    + before.flags.length + after.flags.length;
        }
        Graph before = new Graph(beforeCount), after = new Graph(afterCount);
        connect(summaries, before, false);
        connect(summaries, after, true);
        var plans = new ArrayList<Plan>();
        int detachedCount = 0, unresolvedCount = 0;
        long outputBytes = 0;
        for (Summary summary : summaries.values()) {
            // The summaries never retain interior labels; reconstruct one local component at a time.
            components(summary.section, true, afterLabels, queue);
            var detached = new BitSet(CELLS);
            var unresolved = new BitSet(CELLS);
            for (int cell = 0; cell < CELLS; cell++) {
                int local = afterLabels[cell] - 1;
                if (local < 0) continue;
                int old = before.flags(summary.beforeBase + summary.original[local] - 1);
                int now = after.flags(summary.afterBase + local);
                if ((now & ANCHOR) != 0 || (old & (ANCHOR | OPEN)) == 0) continue;
                if ((old & ANCHOR) != 0 && (now & OPEN) == 0) detached.set(cell);
                else unresolved.set(cell);
            }
            detachedCount += detached.cardinality();
            unresolvedCount += unresolved.cardinality();
            outputBytes += 128L + 3L * CELLS / 8;
            plans.add(new Plan(summary.section, detached, unresolved));
        }
        // Conservative array/object estimates, not a measured total JVM heap claim.
        summaryBytes += 128L + 8L * (beforeCount + afterCount);
        return new Result(plans, new Metrics(sections.size(), beforeCount, afterCount, detachedCount, unresolvedCount,
                summaryBytes, 65536,
                sections.size() * (128L + CELLS * 8L + CELLS / 8L), outputBytes));
    }

    private static Components components(Section section, boolean after, short[] labels, int[] queue) {
        Arrays.fill(labels, (short) 0);
        byte[] flags = new byte[CELLS];
        int count = 0;
        for (int start = 0; start < CELLS; start++) {
            if (labels[start] != 0 || !section.exists(start, after)) continue;
            short label = (short) ++count;
            labels[start] = label;
            int tail = 1;
            queue[0] = start;
            for (int head = 0; head < tail; head++) {
                int cell = queue[head];
                if (section.anchor(cell)) flags[label - 1] |= ANCHOR;
                for (Direction direction : DIRECTIONS) {
                    int next = adjacent(cell, direction);
                    if (next < 0 || labels[next] != 0 || !section.exists(next, after)
                            || !section.at(cell).touches(direction, section.at(next))) continue;
                    labels[next] = label;
                    queue[tail++] = next;
                }
            }
        }
        short[] ports = count == 0 ? new short[0] : new short[PORTS];
        if (count != 0) for (Direction direction : DIRECTIONS) for (int face = 0; face < 256; face++)
            ports[direction.ordinal() * 256 + face] = labels[faceCell(direction, face)];
        return new Components(ports, Arrays.copyOf(flags, count));
    }

    private static void connect(LinkedHashMap<Key, Summary> summaries, Graph graph, boolean after) {
        for (Summary summary : summaries.values()) {
            Components own = after ? summary.after : summary.before;
            int base = after ? summary.afterBase : summary.beforeBase;
            for (int component = 0; component < own.flags.length; component++) graph.add(base + component, own.flags[component]);
            if (own.ports.length == 0) continue;
            for (Direction direction : DIRECTIONS) {
                Summary neighbour = summaries.get(summary.section.key.next(direction));
                for (int face = 0; face < 256; face++) {
                    int local = own.ports[direction.ordinal() * 256 + face] - 1;
                    if (local < 0) continue;
                    if (summary.section.at(faceCell(direction, face)).contacts()[direction.ordinal()].isEmpty()) continue;
                    if (neighbour == null) {
                        // World-floor contacts have a true anchor; all other missing snapshots remain open.
                        graph.add(base + local, OPEN);
                        continue;
                    }
                    Components other = after ? neighbour.after : neighbour.before;
                    if (other.ports.length == 0) continue;
                    int otherLocal = other.ports[direction.getOpposite().ordinal() * 256 + face] - 1;
                    if (otherLocal < 0 || !summary.section.at(faceCell(direction, face)).touches(direction,
                            neighbour.section.at(faceCell(direction.getOpposite(), face)))) continue;
                    graph.join(base + local, (after ? neighbour.afterBase : neighbour.beforeBase) + otherLocal);
                }
            }
        }
    }

    private static int adjacent(int cell, Direction direction) {
        int x = (cell & 15) + direction.getStepX();
        int y = (cell >> 8) + direction.getStepY();
        int z = (cell >> 4 & 15) + direction.getStepZ();
        return x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15 ? -1 : y << 8 | z << 4 | x;
    }

    private static int faceCell(Direction direction, int face) {
        int a = face & 15, b = face >> 4;
        return switch (direction) {
            case DOWN -> b << 4 | a;
            case UP -> 15 << 8 | b << 4 | a;
            case NORTH -> b << 8 | a;
            case SOUTH -> b << 8 | 15 << 4 | a;
            case WEST -> b << 8 | a << 4;
            case EAST -> b << 8 | a << 4 | 15;
        };
    }

    /** Union computes the same monotone boundary-connectivity fixed point in either section order. */
    private static final class Graph {
        private final int[] parent;
        private final byte[] rank, flags;

        Graph(int size) {
            parent = new int[size]; rank = new byte[size]; flags = new byte[size];
            for (int node = 0; node < size; node++) parent[node] = node;
        }

        int root(int node) {
            while (parent[node] != node) { parent[node] = parent[parent[node]]; node = parent[node]; }
            return node;
        }

        int flags(int node) { return flags[root(node)]; }
        void add(int node, byte value) { flags[root(node)] |= value; }
        void join(int a, int b) {
            a = root(a); b = root(b);
            if (a == b) return;
            if (rank[a] < rank[b]) { int swap = a; a = b; b = swap; }
            parent[b] = a;
            flags[a] |= flags[b];
            if (rank[a] == rank[b]) rank[a]++;
        }
    }

    private PreparedSupportPrototype() {}
}
