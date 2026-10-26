package ua.zentix.replayscan.stream;

import ua.zentix.replayscan.stream.ReplayFile.Bytes;
import ua.zentix.replayscan.stream.ReplayFile.Segment;
import ua.zentix.replayscan.stream.ReplayFile.SegmentData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Replay actions in order, with the tick in which the replay server applies them. Replay server tick t reads actions
 * until it encounters the t-th {@code next_tick} ({@code ReplayServer.handleActions}): an action after the (t−1)-th
 * {@code next_tick} is applied in tick t, and at the end of that tick ({@code ServerTickEvent.Post}) the replay world
 * already contains it. The server applies the first chunk snapshot before the first tick (tick 0); the next snapshot
 * is applied only with {@code forcePlaySnapshot}, when reading reaches that chunk (actions of the same tick). Other
 * snapshots repeat what the server already has and are not read.
 */
public final class Timeline {
    public static final String NEXT_TICK = "flashback:action/next_tick";

    /** Action: tick, name, body as a slice of the chunk array; {@code snapshot} means it is from a snapshot (state, not an event). */
    public record Action(int tick, String name, byte[] data, int off, int len, boolean snapshot) {
        public Bytes body() {
            return new Bytes(data, off, off + len);
        }
    }

    private final ReplayFile file;
    private final List<String> warnings = new ArrayList<>();
    private int segment = -1;
    private SegmentData data;
    private Bytes in;
    /** Reading a chunk snapshot: up to {@code data.actionsStart}. */
    private boolean snapshot;
    /** Number of {@code next_tick} actions read. */
    private int ticks;

    public Timeline(ReplayFile file) {
        this.file = file;
    }

    /** Ticks elapsed: the last {@code next_tick} read. */
    public int ticks() {
        return ticks;
    }

    /** Recording inconsistencies (chunks of the wrong length): Flashback stops on such a replay. */
    public List<String> warnings() {
        return warnings;
    }

    /** Next action, or {@code null} at the end of the recording. */
    public Action next() throws IOException {
        while (true) {
            if (in != null && in.remaining() > 0) return read();
            if (in != null && snapshot) {
                snapshot = false;
                in = new Bytes(data.data, data.actionsStart, data.data.length);
                continue;
            }
            if (!enter(segment + 1)) return null;
        }
    }

    private boolean enter(int index) throws IOException {
        data = null;
        in = null;
        List<Segment> segments = file.segments();
        if (index >= segments.size()) return false;
        segment = index;
        Segment s = segments.get(index);
        if (ticks != s.start()) warnings.add(s.file() + " starts at tick " + s.start() + " but the stream before it has " + ticks + " ticks");
        data = file.load(s);
        snapshot = index == 0 || s.forceSnapshot();
        in = snapshot ? new Bytes(data.data, data.snapshotStart, data.actionsStart) : new Bytes(data.data, data.actionsStart, data.data.length);
        return true;
    }

    private Action read() throws IOException {
        int id = in.readVarInt();
        int size = in.readInt();
        if (id < 0 || id >= data.names.size()) throw new IOException(data.segment.file() + ": unknown action id " + id);
        if (size < 0 || size > in.remaining()) throw new IOException(data.segment.file() + ": bad action size " + size);
        String name = data.names.get(id);
        int off = in.pos;
        in.pos += size;
        boolean fromSnapshot = snapshot;
        int tick;
        if (fromSnapshot) {
            tick = segment == 0 ? 0 : ticks + 1;
        } else if (name.equals(NEXT_TICK)) {
            tick = ++ticks;
        } else {
            tick = ticks + 1;
        }
        return new Action(tick, name, data.data, off, size, fromSnapshot);
    }
}
