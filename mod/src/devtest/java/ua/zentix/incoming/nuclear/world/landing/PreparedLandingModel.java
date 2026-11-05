package ua.zentix.incoming.nuclear.world.landing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Devtest-only, world-free experiment: run the current coarse material law once against detached
 * collision cells. The transient motion queue is bounded by one input window; only finite landing
 * operations survive the solve. Unknown geometry and exhausted work return explicit continuations,
 * never artificial ground. No production code calls this class.
 */
public final class PreparedLandingModel {
    public static final byte AIR = 0, SOLID = 1, UNKNOWN = 2, UNSUPPORTED = 3;
    public static final double GRAVITY = 9.80665, DT = 1.0 / 20;

    public enum Kind { ORDINARY, UNSUPPORTED_SOURCE, UNRESOLVED_STRUCTURE }
    public enum Status {
        LANDED, OUT_OF_WINDOW, UNKNOWN_GEOMETRY, UNSUPPORTED_COLLISION,
        UNSUPPORTED_SOURCE, UNRESOLVED_STRUCTURE, OUTSIDE_HEIGHT, WORK_LIMIT, SOURCE_CONFLICT
    }

    /** State is an opaque exact palette identity, never a material-family substitution. Times are seconds. */
    public record Source(long id, int event, int x, int y, int z, int state, double volume, double mass,
                         double departure, double windX, double windZ, double drag, double pulse, Kind kind) {
        public Source {
            if (kind == null || state < 0 || !(volume > 0) || !(mass > 0) || departure < 0 || drag < 0 || pulse < 0
                    || !Double.isFinite(volume + mass + departure + windX + windZ + drag + pulse))
                throw new IllegalArgumentException("Invalid material source");
        }
    }

    /** Defensive detached copy: callers cannot change collision input during an asynchronous solve. */
    public static final class Snapshot {
        private final int x0, minY, z0, width, height, depth;
        private final byte[] cells;

        public Snapshot(int x0, int minY, int z0, int width, int height, int depth, byte[] cells) {
            if (width < 1 || height < 1 || depth < 1 || width > 48 || depth > 48 || height > 4096
                    || cells.length != Math.multiplyExact(Math.multiplyExact(width, height), depth))
                throw new IllegalArgumentException("Invalid bounded collision window");
            this.x0 = x0; this.minY = minY; this.z0 = z0;
            this.width = width; this.height = height; this.depth = depth;
            this.cells = cells.clone();
            for (byte cell : cells) if (cell < AIR || cell > UNSUPPORTED) throw new IllegalArgumentException("Invalid collision cell");
        }

        private int index(int x, int y, int z) {
            if (x < x0 || x >= x0 + width || z < z0 || z >= z0 + depth) return -1;
            if (y < minY || y >= minY + height) return -2;
            return ((y - minY) * depth + z - z0) * width + x - x0;
        }

        public int cellCount() { return cells.length; }
    }

    /** Operational bounds yield WORK_LIMIT; they do not change the physical trajectory. */
    public record Limits(int sources, long segments, double secondsPerSource) {
        public Limits {
            if (sources < 1 || segments < 1 || !(secondsPerSource > 0) || !Double.isFinite(secondsPerSource))
                throw new IllegalArgumentException("Invalid work limits");
        }
        public static Limits experiment() { return new Limits(131072, 8_000_000, 120); }
    }

    public record Outcome(Source source, Status status, int x, int y, int z, double time, long segments) {
        /** Never publish a landing before its computed arrival. */
        public long dueTick() { return (long) Math.ceil(time * 20); }
    }

    public record Result(List<Outcome> outcomes, long segments, long collisionReads, int peakQueue,
                         long inputBytes, long motionBytesEstimate, long outputBytesEstimate) {
        public Result { outcomes = List.copyOf(outcomes); }
        public long count(Status status) { return outcomes.stream().filter(o -> o.status == status).count(); }
    }

    private static final Comparator<Motion> ORDER = Comparator.comparingDouble((Motion m) -> m.due)
            .thenComparingInt(m -> m.departed ? 1 : 0)
            .thenComparingInt(m -> m.source.event).thenComparingLong(m -> m.source.id);
    private static final Comparator<Source> SOURCES = Comparator.comparingInt(Source::event).thenComparingLong(Source::id);
    // Direction.Plane.HORIZONTAL order in the current material rule: north, east, south, west.
    private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

    private static final class Motion {
        final Source source;
        double x, y, z, vx, vy, vz, time, due, dt, nx, ny, nz, ax, az;
        boolean departed;
        long segments;
        Motion(Source source) {
            this.source = source;
            x = source.x + .5; y = source.y; z = source.z + .5;
            time = due = source.departure;
        }
    }

    private final Snapshot snapshot;
    private final byte[] occupancy;
    private final Limits limits;
    private final PriorityQueue<Motion> events = new PriorityQueue<>(ORDER);
    private final List<Outcome> outcomes = new ArrayList<>();
    private long segments, reads;
    private int peak;

    private PreparedLandingModel(Snapshot snapshot, Limits limits) {
        this.snapshot = snapshot; this.limits = limits; occupancy = snapshot.cells.clone();
    }

    public static Result solve(Snapshot snapshot, List<Source> sources, Limits limits) {
        if (sources.size() > limits.sources) throw new IllegalArgumentException("Input exceeds bounded source window");
        var ordered = new ArrayList<>(sources);
        ordered.sort(SOURCES);
        var ids = new HashSet<Long>();
        var positions = new HashSet<Integer>();
        var model = new PreparedLandingModel(snapshot, limits);
        for (Source source : ordered) {
            if (!ids.add(source.id)) throw new IllegalArgumentException("Duplicate source identity");
            int position = snapshot.index(source.x, source.y, source.z);
            if (position < 0 || !positions.add(position)) throw new IllegalArgumentException("Invalid/duplicate source position");
            model.events.add(new Motion(source));
        }
        model.peak = model.events.size();
        while (!model.events.isEmpty()) {
            Motion motion = model.events.remove();
            if (model.segments >= limits.segments) { model.finish(motion, Status.WORK_LIMIT); continue; }
            if (!motion.departed) model.depart(motion);
            else model.advance(motion);
        }
        model.outcomes.sort(Comparator.comparing(Outcome::source, SOURCES));
        return new Result(model.outcomes, model.segments, model.reads, model.peak,
                snapshot.cells.length, 256L * sources.size() + snapshot.cells.length,
                96L * sources.size()); // Explicit estimates, not measured heap or a persistent codec size.
    }

    private void depart(Motion m) {
        if (m.source.kind == Kind.UNRESOLVED_STRUCTURE) { finish(m, Status.UNRESOLVED_STRUCTURE); return; }
        int position = snapshot.index(m.source.x, m.source.y, m.source.z);
        byte old = occupancy[position];
        if (old == UNKNOWN) { finish(m, Status.UNKNOWN_GEOMETRY); return; }
        if (old == AIR) { finish(m, Status.SOURCE_CONFLICT); return; }
        // Removal is a supplied fact. Unsupported transport is counted separately, not left as an
        // artificial support in the geometry after its known departure.
        occupancy[position] = AIR;
        if (m.source.kind != Kind.ORDINARY) {
            finish(m, m.source.kind == Kind.UNRESOLVED_STRUCTURE ? Status.UNRESOLVED_STRUCTURE : Status.UNSUPPORTED_SOURCE);
            return;
        }
        if (old != SOLID) { finish(m, Status.UNSUPPORTED_SOURCE); return; }
        m.departed = true;
        schedule(m);
    }

    /** Same free-flight law and adaptive segment bounds as RuinMatter.step at 83d65745. */
    private void schedule(Motion m) {
        if (m.time - m.source.departure >= limits.secondsPerSource) { finish(m, Status.WORK_LIMIT); return; }
        double speed = Math.sqrt(m.vx * m.vx + m.vy * m.vy + m.vz * m.vz);
        double dt = Math.min(DT, .5 / Math.max(1, speed));
        double wind = Math.hypot(m.source.windX, m.source.windZ);
        double rx = m.source.windX - m.vx, rz = m.source.windZ - m.vz;
        double age = m.time - m.source.departure;
        double drag = m.source.pulse > 0 && wind > 0 ? m.source.drag * Math.max(0, 1 - age / m.source.pulse)
                * Math.hypot(rx, rz) / (wind * wind) : 0;
        m.ax = drag * rx; m.az = drag * rz;
        double acceleration = Math.sqrt(m.ax * m.ax + m.az * m.az + GRAVITY * GRAVITY);
        dt = Math.min(dt, 1 / (speed + Math.sqrt(speed * speed + acceleration)));
        if (drag > 0) dt = Math.min(dt, .25 / drag);
        m.dt = dt;
        m.nx = m.x + m.vx * dt + m.ax * dt * dt / 2;
        m.ny = m.y + m.vy * dt - GRAVITY * dt * dt / 2;
        m.nz = m.z + m.vz * dt + m.az * dt * dt / 2;
        m.due = m.time + dt;
        events.add(m);
    }

    private void advance(Motion m) {
        segments++; m.segments++;
        m.time = m.due;
        int x = floor(m.nx), y = floor(m.ny), z = floor(m.nz);
        int cell = cell(x, y, z);
        if (fallback(m, cell)) return;
        if (cell == SOLID) {
            int hx = floor(m.x), hy = floor(m.y), hz = floor(m.z);
            int here = cell(hx, hy, hz);
            if (fallback(m, here)) return;
            if (here == SOLID) {
                m.y = hy + 1; m.vx = m.vy = m.vz = 0;
                settle(m, hx, hy + 1, hz);
                return;
            }
            m.vx = m.vz = 0;
            int vertical = cell(hx, y, hz);
            if (fallback(m, vertical)) return;
            if (vertical == AIR) { m.y = m.ny; m.vy -= GRAVITY * m.dt; }
            else if (m.ny < m.y) { settle(m, hx, hy, hz); return; }
            else m.vy = Math.min(0, m.vy);
        } else {
            m.x = m.nx; m.y = m.ny; m.z = m.nz;
            m.vx += m.ax * m.dt; m.vy -= GRAVITY * m.dt; m.vz += m.az * m.dt;
        }
        schedule(m);
    }

    private void settle(Motion m, int x, int y, int z) {
        int own = cell(x, y, z), below = cell(x, y - 1, z);
        if (fallback(m, own) || fallback(m, below)) return;
        if (own == SOLID) {
            m.y = y + 1; m.vx = m.vy = m.vz = 0;
            schedule(m); return;
        }
        if (below == AIR) { schedule(m); return; }
        int offset = Math.floorMod(Long.hashCode(m.source.id), 4);
        for (int k = 0; k < 4; k++) {
            int d = (k + offset) % 4, sx = x + DX[d], sz = z + DZ[d];
            int side = cell(sx, y, sz), under = cell(sx, y - 1, sz);
            if (fallback(m, side) || fallback(m, under)) return;
            if (side == AIR && under == AIR) {
                m.x = sx + .5; m.y = y; m.z = sz + .5; m.vx = m.vy = m.vz = 0;
                schedule(m); return;
            }
        }
        occupancy[snapshot.index(x, y, z)] = SOLID;
        outcomes.add(new Outcome(m.source, Status.LANDED, x, y, z, m.time, m.segments));
    }

    private int cell(int x, int y, int z) {
        reads++;
        int index = snapshot.index(x, y, z);
        return index < 0 ? index : occupancy[index];
    }

    private boolean fallback(Motion m, int cell) {
        Status status = switch (cell) {
            case -1 -> Status.OUT_OF_WINDOW;
            case -2 -> Status.OUTSIDE_HEIGHT;
            case UNKNOWN -> Status.UNKNOWN_GEOMETRY;
            case UNSUPPORTED -> Status.UNSUPPORTED_COLLISION;
            default -> null;
        };
        if (status == null) return false;
        finish(m, status);
        return true;
    }

    private void finish(Motion m, Status status) {
        outcomes.add(new Outcome(m.source, status, floor(m.x), floor(m.y), floor(m.z), m.time, m.segments));
    }

    private static int floor(double value) { return (int) Math.floor(value); }

}
