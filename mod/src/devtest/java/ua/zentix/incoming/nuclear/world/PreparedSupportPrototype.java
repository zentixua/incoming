package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.ArrayDeque;
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

    /** Times are in the caller's common event clock, ordered by the section's direct-removal bits. */
    static final class TimedSection {
        final Section section;
        private final double[] times;

        TimedSection(Section section, double[] times) {
            if (times.length != section.removed.cardinality()) throw new IllegalArgumentException("Missing removal times");
            for (double time : times) if (!Double.isFinite(time) || time < 0) throw new IllegalArgumentException("Invalid removal time");
            this.section = section;
            this.times = times.clone();
        }
    }

    record Release(int cell, double at) {}
    static final class TimedPlan {
        final Key key;
        final List<Release> direct, detached;
        private final BitSet unresolved;

        TimedPlan(Key key, List<Release> direct, List<Release> detached, BitSet unresolved) {
            this.key = key; this.direct = List.copyOf(direct); this.detached = List.copyOf(detached);
            this.unresolved = (BitSet) unresolved.clone();
        }

        BitSet unresolved() { return (BitSet) unresolved.clone(); }
    }

    record TimedResult(List<TimedPlan> plans, int sectionVisits, long cellPops, long boundaryBytes) {
        TimedResult { plans = List.copyOf(plans); }
    }

    private static final double UNREACHED = -1;
    private static final class TimedState {
        final TimedSection input;
        final double[] lower = new double[PORTS], upper = new double[PORTS];
        boolean queued;

        TimedState(TimedSection input) {
            this.input = input;
            Arrays.fill(lower, UNREACHED); Arrays.fill(upper, UNREACHED);
        }
    }

    /**
     * Last-support time = max over original anchor paths of min removal time along each path.
     * Section-local widest paths exchange only face values. Unknown faces seed an upper bound;
     * actual anchors seed both bounds. A finite release requires matching bounds. This does not
     * model elastic signal travel or change the upstream finite-span failure law.
     */
    static TimedResult timed(List<TimedSection> sections, int sectionLimit, int visitLimit) {
        if (sections.size() > sectionLimit || sectionLimit < 1 || visitLimit < 1)
            throw new IllegalArgumentException("Timed support section budget exceeded");
        var states = new LinkedHashMap<Key, TimedState>();
        var queue = new ArrayDeque<TimedState>();
        for (TimedSection section : sections) {
            var state = new TimedState(section);
            if (states.put(section.section.key, state) != null) throw new IllegalArgumentException("Duplicate support section");
            state.queued = true; queue.addLast(state);
        }
        var work = new TimedWork(visitLimit);
        var heap = new LocalHeap();
        double[] capacity = new double[CELLS], lower = new double[CELLS];
        while (!queue.isEmpty()) {
            TimedState state = queue.removeFirst(); state.queued = false;
            for (boolean upper : new boolean[]{false, true}) {
                localTimes(state, states, upper, heap, capacity, work);
                double[] ports = upper ? state.upper : state.lower;
                for (Direction direction : DIRECTIONS) {
                    boolean changed = false;
                    for (int face = 0; face < 256; face++) {
                        int port = direction.ordinal() * 256 + face;
                        double value = heap.values[faceCell(direction, face)];
                        if (value > ports[port]) { ports[port] = value; changed = true; }
                    }
                    TimedState neighbour = states.get(state.input.section.key.next(direction));
                    if (changed && neighbour != null && !neighbour.queued) {
                        neighbour.queued = true; queue.addLast(neighbour);
                    }
                }
            }
        }
        var plans = new ArrayList<TimedPlan>();
        for (TimedState state : states.values()) {
            localTimes(state, states, false, heap, capacity, work);
            System.arraycopy(heap.values, 0, lower, 0, CELLS);
            localTimes(state, states, true, heap, capacity, work);
            var direct = new ArrayList<Release>();
            var detached = new ArrayList<Release>();
            var unresolved = new BitSet(CELLS);
            Section section = state.input.section;
            for (int cell = 0, ordinal = 0; cell < CELLS; cell++) {
                if (section.removed.get(cell)) { direct.add(new Release(cell, state.input.times[ordinal++])); continue; }
                if (!section.at(cell).bearing()) continue;
                double lo = lower[cell], hi = heap.values[cell];
                if (lo == Double.POSITIVE_INFINITY || lo == UNREACHED && hi == UNREACHED) continue;
                if (lo >= 0 && lo == hi) detached.add(new Release(cell, lo));
                else unresolved.set(cell);
            }
            plans.add(new TimedPlan(section.key, direct, detached, unresolved));
        }
        return new TimedResult(plans, work.visits, work.pops, states.size() * (128L + 2L * PORTS * Double.BYTES));
    }

    private static void localTimes(TimedState state, LinkedHashMap<Key, TimedState> states, boolean upper,
                                   LocalHeap heap, double[] capacity, TimedWork work) {
        if (++work.visits > work.limit) throw new IllegalArgumentException("Timed support work budget exceeded");
        Section section = state.input.section;
        Arrays.fill(capacity, Double.POSITIVE_INFINITY);
        for (int cell = section.removed.nextSetBit(0), ordinal = 0; cell >= 0; cell = section.removed.nextSetBit(cell + 1))
            capacity[cell] = state.input.times[ordinal++];
        heap.clear();
        for (int cell = 0; cell < CELLS; cell++) if (section.at(cell).bearing() && section.anchor(cell)) heap.raise(cell, capacity[cell]);
        for (Direction direction : DIRECTIONS) {
            TimedState neighbour = states.get(section.key.next(direction));
            for (int face = 0; face < 256; face++) {
                int cell = faceCell(direction, face);
                var props = section.at(cell);
                if (!props.bearing() || props.contacts()[direction.ordinal()].isEmpty()) continue;
                if (neighbour == null) {
                    if (upper) heap.raise(cell, capacity[cell]);
                    continue;
                }
                int other = faceCell(direction.getOpposite(), face);
                var otherProps = neighbour.input.section.at(other);
                if (!otherProps.bearing() || !props.touches(direction, otherProps)) continue;
                double value = (upper ? neighbour.upper : neighbour.lower)[direction.getOpposite().ordinal() * 256 + face];
                heap.raise(cell, Math.min(capacity[cell], value));
            }
        }
        while (!heap.empty()) {
            int cell = heap.pop(); work.pops++;
            for (Direction direction : DIRECTIONS) {
                int next = adjacent(cell, direction);
                if (next < 0 || !section.at(next).bearing() || !section.at(cell).touches(direction, section.at(next))) continue;
                heap.raise(next, Math.min(heap.values[cell], capacity[next]));
            }
        }
    }

    private static final class TimedWork {
        final int limit;
        int visits;
        long pops;
        TimedWork(int limit) { this.limit = limit; }
    }

    /** Indexed max heap: one slot per local cell; no per-block objects or stale queue entries. */
    private static final class LocalHeap {
        final double[] values = new double[CELLS];
        final int[] heap = new int[CELLS], positions = new int[CELLS];
        int size;

        void clear() { size = 0; Arrays.fill(values, UNREACHED); Arrays.fill(positions, -1); }
        boolean empty() { return size == 0; }
        void raise(int cell, double value) {
            if (value <= values[cell]) return;
            values[cell] = value;
            int at = positions[cell];
            if (at < 0) { at = size++; heap[at] = cell; positions[cell] = at; }
            while (at > 0) {
                int parent = (at - 1) / 2;
                if (values[heap[parent]] >= value) break;
                swap(at, parent); at = parent;
            }
        }

        int pop() {
            int result = heap[0]; positions[result] = -1;
            if (--size == 0) return result;
            heap[0] = heap[size]; positions[heap[0]] = 0;
            int at = 0;
            while (at * 2 + 1 < size) {
                int child = at * 2 + 1;
                if (child + 1 < size && values[heap[child + 1]] > values[heap[child]]) child++;
                if (values[heap[at]] >= values[heap[child]]) break;
                swap(at, child); at = child;
            }
            return result;
        }

        private void swap(int a, int b) {
            int temporary = heap[a]; heap[a] = heap[b]; heap[b] = temporary;
            positions[heap[a]] = a; positions[heap[b]] = b;
        }
    }

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
