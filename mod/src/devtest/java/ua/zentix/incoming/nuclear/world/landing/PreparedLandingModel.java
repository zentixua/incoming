package ua.zentix.incoming.nuclear.world.landing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Devtest-only, world-free experiment: run the current coarse material law once against detached
 * collision cells. Free flight retains the existing numerical law with exclusive occupied voxels
 * from departure, including airborne material (a coarse voxel-transport approximation). Inelastic
 * contact changes to timed, strictly downhill cell transfers with unique material owners;
 * horizontal displacement remains the existing estimated one-block repose rule. The transient motion queue is bounded by one input window; only finite landing
 * operations survive the solve. Unknown geometry and exhausted work return explicit continuations,
 * never artificial ground. No production code calls this class.
 */
public final class PreparedLandingModel {
    public static final byte AIR = 0, SOLID = 1, UNKNOWN = 2, UNSUPPORTED = 3, FREE_COLLIDING = 4, BLOCKED_EMPTY = 5;
    public static final double GRAVITY = 9.80665, DT = 1.0 / 20;

    public enum Kind { ORDINARY, UNSUPPORTED_SOURCE, UNRESOLVED_STRUCTURE, FINE }
    public enum Status {
        LANDED, OUT_OF_WINDOW, UNKNOWN_GEOMETRY, UNSUPPORTED_COLLISION,
        UNSUPPORTED_SOURCE, UNRESOLVED_STRUCTURE, OUTSIDE_HEIGHT, WORK_LIMIT, SOURCE_CONFLICT, DISPERSED
    }

    /** State is an opaque exact palette identity, never a material-family substitution. Times are seconds. */
    public record Source(long id, int event, int x, int y, int z, int state, double volume, double mass,
                         double departure, double windX, double windZ, double drag, double pulse, Kind kind, byte landingCell) {
        public Source(long id, int event, int x, int y, int z, int state, double volume, double mass,
                      double departure, double windX, double windZ, double drag, double pulse, Kind kind) {
            this(id, event, x, y, z, state, volume, mass, departure, windX, windZ, drag, pulse, kind, SOLID);
        }
        public Source {
            if (kind == null || landingCell < AIR || landingCell > BLOCKED_EMPTY || state < 0 || !(volume > 0) || !(mass > 0) || departure < 0 || drag < 0 || pulse < 0
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
            for (byte cell : cells) if (cell < AIR || cell > BLOCKED_EMPTY) throw new IllegalArgumentException("Invalid collision cell");
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

    public record Cell(int x, int y, int z) {}
    public record Trace(long collisions, long climbs, long spills, long sameCellSteps, long maxSameCellRun, long reposeABA,
                        Cell firstContact, double firstContactTime, long contactTransfers, long postContactRises) {}
    public record Outcome(Source source, Status status, int x, int y, int z, double time, long segments, Cell blocked, Trace trace) {
        /** Never publish a landing before its computed arrival. */
        public long dueTick() { return (long) Math.ceil(time * 20); }
    }

    public record Result(List<Outcome> outcomes, long segments, long collisionReads, long queueOperations, int peakQueue,
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
        boolean departed, queued, contact;
        int ownerCell = -1, tx, ty, tz;
        double fallingSpeed;
        Status status;
        long segments;
        long collisions, climbs, spills, sameCellSteps, sameCellRun, maxSameCellRun, reposeABA;
        long previousCell = Long.MIN_VALUE, previousSpill = Long.MIN_VALUE, precedingSpill = Long.MIN_VALUE;
        Cell blocked, firstContact;
        double firstContactTime = -1;
        long contactTransfers, postContactRises;
        Motion(Source source) {
            this.source = source;
            x = source.x + .5; y = source.y; z = source.z + .5;
            time = due = source.departure;
        }
    }

    private final Snapshot snapshot;
    private final byte[] occupancy;
    private final Motion[] owners;
    private final List<Motion> motions = new ArrayList<>();
    private final Limits limits;
    private final PriorityQueue<Motion> events = new PriorityQueue<>(ORDER);
    private final List<Outcome> outcomes = new ArrayList<>();
    private long segments, reads, queueOperations;
    private int peak;

    private PreparedLandingModel(Snapshot snapshot, Limits limits) {
        this.snapshot = snapshot; this.limits = limits; occupancy = snapshot.cells.clone();
        owners = new Motion[occupancy.length];
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
            var motion = new Motion(source);
            motion.queued = true;
            model.motions.add(motion);
            model.events.add(motion);
            model.queueOperations++;
        }
        model.peak = model.events.size();
        while (!model.events.isEmpty()) {
            Motion motion = model.events.remove();
            model.queueOperations++;
            motion.queued = false;
            if (model.segments >= limits.segments) { model.finish(motion, Status.WORK_LIMIT); continue; }
            if (!motion.departed) model.depart(motion);
            else if (motion.contact) model.transfer(motion);
            else model.advance(motion);
        }
        for (Motion motion : model.motions) {
            if (motion.status == null) throw new IllegalStateException("Material has no final owner status");
            model.outcomes.add(new Outcome(motion.source, motion.status, floor(motion.x), floor(motion.y), floor(motion.z),
                    motion.time, motion.segments, motion.blocked, trace(motion)));
        }
        model.outcomes.sort(Comparator.comparing(Outcome::source, SOURCES));
        return new Result(model.outcomes, model.segments, model.reads, model.queueOperations, model.peak,
                snapshot.cells.length, 448L * sources.size() + 5L * snapshot.cells.length,
                176L * sources.size()); // Includes diagnostic counters; not measured heap or a persistent codec size.
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
            wake(m.source.x, m.source.y, m.source.z, m.time);
            finish(m, m.source.kind == Kind.FINE ? Status.DISPERSED : Status.UNSUPPORTED_SOURCE);
            return;
        }
        if (old == UNSUPPORTED) { finish(m, Status.UNSUPPORTED_SOURCE); return; }
        m.departed = true;
        claim(m, m.source.x, m.source.y, m.source.z);
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
        m.queued = true;
        events.add(m);
        queueOperations++;
    }

    private void advance(Motion m) {
        segments++; m.segments++;
        m.time = m.due;
        long position = position(floor(m.x), floor(m.y), floor(m.z));
        if (position == m.previousCell) { m.sameCellSteps++; m.sameCellRun++; }
        else m.sameCellRun = 0;
        m.previousCell = position;
        m.maxSameCellRun = Math.max(m.maxSameCellRun, m.sameCellRun);
        int x = floor(m.nx), y = floor(m.ny), z = floor(m.nz);
        int cell = cell(m, x, y, z);
        if (fallback(m, cell, x, y, z)) return;
        if (!free(cell) || !empty(cell)) {
            m.collisions++;
            int hx = floor(m.x), hy = floor(m.y), hz = floor(m.z);
            if (owner(hx, hy, hz) != m) throw new IllegalStateException("Airborne material lost its exclusive cell");
            m.vx = m.vz = 0;
            int vertical = cell(m, hx, y, hz);
            if (fallback(m, vertical, hx, y, hz)) return;
            if (free(vertical) && empty(vertical)) {
                move(m, hx, y, hz);
                m.y = m.ny; m.vy -= GRAVITY * m.dt;
            } else if (m.ny < m.y) { settle(m, hx, hy, hz); return; }
            else m.vy = Math.min(0, m.vy);
        } else {
            move(m, x, y, z);
            m.x = m.nx; m.y = m.ny; m.z = m.nz;
            m.vx += m.ax * m.dt; m.vy -= GRAVITY * m.dt; m.vz += m.az * m.dt;
        }
        schedule(m);
    }

    /** Inelastic contact retains the already-owned voxel; it never projects material upward. */
    private void settle(Motion m, int x, int y, int z) {
        if (owner(x, y, z) != m) throw new IllegalStateException("Contact has no exclusive material owner");
        int below = cell(x, y - 1, z);
        if (fallback(m, below, x, y - 1, z)) return;
        if (free(below) && empty(below)) { schedule(m); return; }
        m.x = x + .5; m.y = y; m.z = z + .5;
        m.vx = m.vy = m.vz = 0;
        m.contact = true;
        m.firstContact = new Cell(x, y, z); m.firstContactTime = m.time;
        repose(m);
    }

    private void move(Motion m, int x, int y, int z) {
        int destination = snapshot.index(x, y, z);
        if (destination == m.ownerCell) return;
        double oldX = m.x, oldY = m.y, oldZ = m.z;
        int ox = floor(oldX), oy = floor(oldY), oz = floor(oldZ);
        owners[m.ownerCell] = null;
        claim(m, x, y, z);
        m.x = oldX; m.y = oldY; m.z = oldZ;
        wake(ox, oy, oz, m.time);
    }

    private Motion owner(int x, int y, int z) {
        int index = snapshot.index(x, y, z);
        return index < 0 ? null : owners[index];
    }

    private void claim(Motion m, int x, int y, int z) {
        int index = snapshot.index(x, y, z);
        if (owners[index] != null) throw new IllegalStateException("Two materials claim one cell");
        owners[index] = m;
        m.ownerCell = index;
        m.x = x + .5; m.y = y; m.z = z + .5;
    }

    /** Every successful post-contact transfer strictly lowers y. Gravity determines its arrival. */
    private void repose(Motion m) {
        int x = floor(m.x), y = floor(m.y), z = floor(m.z);
        int below = cell(x, y - 1, z);
        if (fallback(m, below, x, y - 1, z)) return;
        if (free(below) && empty(below)) {
            queueTransfer(m, x, y - 1, z, m.fallingSpeed); return;
        }
        m.fallingSpeed = 0;
        int offset = Math.floorMod(Long.hashCode(m.source.id), 4);
        for (int k = 0; k < 4; k++) {
            int d = (k + offset) % 4, sx = x + DX[d], sz = z + DZ[d];
            int side = cell(sx, y, sz), under = cell(sx, y - 1, sz);
            if (fallback(m, side, sx, y, sz) || fallback(m, under, sx, y - 1, sz)) return;
            if (free(side) && empty(side) && free(under) && empty(under)
                    && owner(sx, y, sz) == null && owner(sx, y - 1, sz) == null) {
                m.spills++;
                queueTransfer(m, sx, y - 1, sz, 0); return;
            }
        }
        finish(m, Status.LANDED);
    }

    private void queueTransfer(Motion m, int x, int y, int z, double speed) {
        // Contact events have a finite integer-height potential. A long physical avalanche is not
        // a numerical-flight loop: retain the operation bound, not the free-flight age guard.
        m.status = null;
        m.tx = x; m.ty = y; m.tz = z;
        m.dt = 2 / (speed + Math.sqrt(speed * speed + 2 * GRAVITY));
        m.due = m.time + m.dt;
        m.fallingSpeed = speed + GRAVITY * m.dt;
        m.queued = true; events.add(m); queueOperations++;
    }

    private void transfer(Motion m) {
        segments++; m.segments++; m.time = m.due;
        int cell = cell(m.tx, m.ty, m.tz);
        if (fallback(m, cell, m.tx, m.ty, m.tz)) return;
        int side = cell(m.tx, floor(m.y), m.tz);
        if (fallback(m, side, m.tx, floor(m.y), m.tz)) return;
        boolean horizontalBlocked = (m.tx != floor(m.x) || m.tz != floor(m.z)) && (!free(side) || !empty(side));
        // The target is not reserved before arrival. A preceding event may occupy it. In that case
        // retain the current owned material and reconsider the now-current support, never climb.
        if (horizontalBlocked || !free(cell) || !empty(cell) || owner(m.tx, m.ty, m.tz) != null) {
            m.fallingSpeed = 0; repose(m); return;
        }
        int x = floor(m.x), y = floor(m.y), z = floor(m.z);
        m.contactTransfers++;
        if (m.ty >= y) { m.postContactRises++; throw new IllegalStateException("Non-descending contact transfer"); }
        owners[m.ownerCell] = null;
        claim(m, m.tx, m.ty, m.tz);
        wake(x, y, z, m.time);
        repose(m);
    }

    private void wake(int x, int y, int z, double time) {
        wakeOwner(owner(x, y + 1, z), time);
        for (int d = 0; d < 4; d++) {
            wakeOwner(owner(x + DX[d], y + 1, z + DZ[d]), time);
            wakeOwner(owner(x + DX[d], y, z + DZ[d]), time);
        }
    }

    private void wakeOwner(Motion m, double time) {
        if (m == null || m.queued || m.status != Status.LANDED) return;
        m.time = Math.max(m.time, time);
        m.status = null;
        repose(m);
    }

    private static boolean free(int cell) { return cell == AIR || cell == FREE_COLLIDING; }
    private static boolean empty(int cell) { return cell == AIR || cell == BLOCKED_EMPTY; }

    private int cell(Motion self, int x, int y, int z) {
        reads++;
        int index = snapshot.index(x, y, z);
        return index < 0 ? index : owners[index] != null && owners[index] != self ? SOLID : occupancy[index];
    }

    private int cell(int x, int y, int z) {
        reads++;
        int index = snapshot.index(x, y, z);
        return index < 0 ? index : owners[index] != null ? SOLID : occupancy[index];
    }

    private boolean fallback(Motion m, int cell, int x, int y, int z) {
        Status status = switch (cell) {
            case -1 -> Status.OUT_OF_WINDOW;
            case -2 -> Status.OUTSIDE_HEIGHT;
            case UNKNOWN -> Status.UNKNOWN_GEOMETRY;
            case UNSUPPORTED -> Status.UNSUPPORTED_COLLISION;
            default -> null;
        };
        if (status == null) return false;
        m.blocked = new Cell(x, y, z);
        finish(m, status);
        return true;
    }

    private void finish(Motion m, Status status) {
        m.status = status;
    }

    private static Trace trace(Motion m) {
        return new Trace(m.collisions, m.climbs, m.spills, m.sameCellSteps, m.maxSameCellRun, m.reposeABA, m.firstContact, m.firstContactTime, m.contactTransfers, m.postContactRises);
    }

    private static long position(int x, int y, int z) {
        return ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | (y & 0xfff);
    }

    private static int floor(double value) { return (int) Math.floor(value); }

}
