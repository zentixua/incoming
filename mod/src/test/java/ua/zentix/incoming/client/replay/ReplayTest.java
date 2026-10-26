package ua.zentix.incoming.client.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live replay events Flashback by replay server tags. Server - model {@code ReplayServer.tickServer},
 * {@code runUpdates} and {@code handleActions} Flashback 0.39.10: place in record ({@code targetTick}) and read
 * ({@code currentTick}); rewind or forward beyond the length of a piece into another piece - a snapshot of the piece (with the last packet of each type) and reading from its beginning; to the place further 20 tick - reading at once to the place − 20, the last ones - by tick
 *  with {@code fastForwarding}; when outputting video - one reading per step. Each reading ends with a server tick - the label:
 *  rewind, if there was a snapshot or inspection by tick ({@code doClientRendering}), when outputting a video - only inspection. Client
 *  parses packets from one connection in order: events, then label; when is not important. Each check - and with Flashback
 * NeoForge Fixed ({@code fork}): from the beginning of the snapshot, it accumulates packets and sends them at the end of the replay server tick, after the marks of all
 * reads ({@code MixinReplayServer.catchUpNewViewers}).
 */
class ReplayTest {
    /** A piece of recording is 5 minutes (pictures are at the beginning of each). */
    private static final int CHUNK = 6000;
    /** Snapshot event: last packet of the view, not the recording location. */
    private static final int SNAPSHOT = -1;
    private static final boolean[] FORKS = {false, true};

    /** The event that the client accepted: where it was recorded, where it was, and whether it was alive. */
    private record Event(int recorded, boolean live) {}

    private static final class Rig {
        final Replay.Markers markers = new Replay.Markers();
        final List<Integer> pending = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        /** Pieces at the beginning of which a snapshot is placed and when reading in a row (the junction of glued records). */
        final List<Integer> forced = new ArrayList<>();
        /** Flashback NeoForge Fixed: that it saves until the end of the server tick. */
        final List<Integer> backlog = new ArrayList<>();
        final boolean fork;
        boolean deferring;
        int target, current;
        boolean paused = true, export, seeking;
        int rewinds;

        Rig(boolean fork) {
            this.fork = fork;
        }

        /** server tick: repeat step, rewind to {@code jump} or stand; at the end - accumulated. */
        void tick(Integer jump) {
            step(jump);
            pending.addAll(backlog);
            backlog.clear();
            deferring = false;
        }

        private void step(Integer jump) {
            boolean normal = false;
            if (jump != null) {
                target = jump;
            } else if (!paused) {
                target++;
                normal = true;
            }
            if (export || target == current || normal) {
                runUpdates();
                return;
            }
            int real = target;
            target = target < current ? Math.max(real / CHUNK * CHUNK + 1, real - 20) : Math.max(current + 1, real - 20);
            if (target >= real) {
                target = real;
                runUpdates();
                return;
            }
            while (true) {
                seeking = target < real;
                runUpdates();
                if (target == real) break;
                target++;
            }
            seeking = false;
        }

        void tick() {
            tick(null);
        }

        void seek(int to) {
            tick(to);
        }

        /** Read to the location and tick the server (the mark at the end of it). */
        private void runUpdates() {
            boolean snapshot = false;
            if (target != current) {
                if (target < current || target > current + CHUNK && target / CHUNK != current / CHUNK) {
                    snapshot();
                    snapshot = true;
                    current = target / CHUNK * CHUNK;
                }
                while (current < target) {
                    current++;
                    if (forced.contains(current)) {
                        snapshot();
                        snapshot = true;
                    }
                    deliver(current);
                }
            }
            Replay.Markers.Verdict v = markers.next(target, export ? seeking : seeking || snapshot);
            if (v == Replay.Markers.Verdict.SETTLED) rewinds++;
            for (int t : pending) events.add(new Event(t, v == Replay.Markers.Verdict.LIVE));
            pending.clear();
        }

        private void snapshot() {
            deferring = fork;
            deliver(SNAPSHOT);
        }

        private void deliver(int recorded) {
            (deferring ? backlog : pending).add(recorded);
        }

        List<Event> take() {
            List<Event> e = new ArrayList<>(events);
            events.clear();
            return e;
        }
    }

    /** The replay is open, it stands at {@code at}, three more ticks - and the marks are established. */
    private static Rig at(int at, boolean fork) {
        Rig r = new Rig(fork);
        r.seek(at);
        for (int i = 0; i < 3; i++) r.tick();
        r.take();
        r.rewinds = 0;
        return r;
    }

    private static long live(List<Event> events) {
        return events.stream().filter(Event::live).count();
    }

    /** There is a repeat: every event is live, there is no rewinding; stands and walks again - too. */
    @Test
    void playbackIsLive() {
        for (boolean fork : FORKS) {
            Rig r = at(1000, fork);
            r.paused = false;
            for (int i = 0; i < 400; i++) r.tick();
            List<Event> e = r.take();
            assertEquals(400, e.size());
            assertEquals(400, live(e));
            r.paused = true;
            for (int i = 0; i < 200; i++) r.tick();
            r.paused = false;
            for (int i = 0; i < 50; i++) r.tick();
            assertEquals(50, live(r.take()));
            assertEquals(0, r.rewinds);
        }
    }

    /**
     * Opening a replay: snapshot packets before the first mark or immediately after it (Flashback NeoForge Fixed - at the end of the tick)
     *  and the first two ticks of the move - the past; Next is a live replay.
     */
    @Test
    void openingIsPast() {
        for (boolean late : new boolean[] {false, true}) {
            Rig r = new Rig(late);
            if (!late) r.pending.add(SNAPSHOT);
            r.tick();
            if (late) r.pending.add(SNAPSHOT);
            r.paused = false;
            r.tick();
            r.tick();
            List<Event> e = r.take();
            assertEquals(3, e.size());
            assertEquals(0, live(e));
            r.tick();
            r.tick();
            assertEquals(2, live(r.take()));
            assertEquals(1, r.rewinds);
        }
    }

    /**
     * Rewind (on tick, in the same piece, to the beginning of the piece, to the previous one) and forward (on 2–3 tick, on 20, on 21, further,
     *  to another piece): everything read and the first tick of the move after it is the past, one rewind; on pause and on the go; with
     *  snapshot via Flashback NeoForge Fixed the read comes after the last read mark. The third tick of the move is live.
     */
    @Test
    void seekEventsArePast() {
        int[] targets = {8999, 8990, 6500, 6001, 6000, 3000, 9003, 9020, 9021, 9030, 9100, 11000, 14000, 16000};
        for (boolean fork : FORKS) {
            for (boolean playing : new boolean[] {false, true}) {
                for (int to : targets) {
                    String what = "→ " + to + (playing ? " on the go" : " on pause") + (fork ? ", Fixed" : "");
                    Rig r = at(9000, fork);
                    r.paused = !playing;
                    if (playing) r.tick();
                    r.take();
                    r.seek(to);
                    r.paused = false;
                    r.tick();
                    List<Event> e = r.take();
                    assertTrue(e.size() > 1, what);
                    assertEquals(0, live(e), what);
                    r.tick();
                    r.tick();
                    List<Event> next = r.take();
                    assertEquals(2, next.size(), what);
                    assertTrue(next.get(1).live(), what + ": After rewinding, the replay is live again");
                    assertEquals(1, r.rewinds, what);
                }
            }
        }
    }

    /** During the pause, one tick forward is the progress of the repetition: its events are live. */
    @Test
    void singleStepIsLive() {
        for (boolean fork : FORKS) {
            Rig r = at(9000, fork);
            r.seek(9001);
            List<Event> e = r.take();
            assertEquals(1, e.size());
            assertEquals(1, live(e));
            assertEquals(0, r.rewinds);
        }
    }

    /**
     * Video output: the server stands between steps (the client ticks itself), each step is one read. First, rewind to 40 ticks
     *  before the beginning - the past, and the first two steps after it; steps by tick and with acceleration (up to 20 tick per frame) are live;
     *  the next step is past.
     */
    @Test
    void exportStepsAreLive() {
        for (boolean fork : FORKS) {
            Rig r = at(9000, fork);
            r.export = true;
            r.seek(2960);
            r.seek(2961);
            assertEquals(0, live(r.take()), "rewind to the beginning of the video");
            for (int t = 2962; t <= 3000; t++) r.seek(t);
            List<Event> warm = r.take();
            assertEquals(39, warm.size());
            assertEquals(38, live(warm), "the second step after rewinding is still the past");
            for (int i = 0; i < 100; i++) r.tick(); // the server is waiting for a step
            for (int t = 3001; t <= 3200; t++) r.seek(t);
            assertEquals(200, live(r.take()));
            for (int t = 3220; t <= 3600; t += 20) r.seek(t);
            assertEquals(400, live(r.take()), "acceleration: 20 tick per step");
            r.seek(3700);
            assertEquals(0, live(r.take()));
            assertEquals(1, r.rewinds);
        }
    }

    /** The replay is accelerated, client frames are rare: tags go one at a time to the server tick, events are live. */
    @Test
    void fastPlaybackIsLive() {
        for (boolean fork : FORKS) {
            Rig r = at(1000, fork);
            r.paused = false;
            for (int i = 0; i < 2000; i++) r.tick();
            assertEquals(2000, live(r.take()));
        }
    }

    /** The repetition reached the end of the piece: the next piece is read in a row, without a snapshot, - live. */
    @Test
    void chunkBoundaryIsLive() {
        for (boolean fork : FORKS) {
            Rig r = at(CHUNK - 5, fork);
            r.paused = false;
            for (int i = 0; i < 10; i++) r.tick();
            List<Event> e = r.take();
            assertEquals(10, live(e));
            assertTrue(e.stream().noneMatch(ev -> ev.recorded() == SNAPSHOT));
        }
    }

    /**
     * The junction of glued records: a snapshot in the middle of the replay progress - its tick past (in the snapshot are the last packets of the last record),
     *  and the next two, then live.
     */
    @Test
    void forcedSnapshotIsPast() {
        for (boolean fork : FORKS) {
            Rig r = at(CHUNK - 5, fork);
            r.forced.add(CHUNK);
            r.paused = false;
            for (int i = 0; i < 4; i++) r.tick();
            assertEquals(4, live(r.take()));
            for (int i = 0; i < 3; i++) r.tick();
            List<Event> e = r.take();
            assertTrue(e.stream().anyMatch(ev -> ev.recorded() == SNAPSHOT));
            assertEquals(0, live(e), "tick with a photo and the next two");
            r.tick();
            assertEquals(1, live(r.take()));
            assertEquals(1, r.rewinds);
        }
    }

    /** A place back or forward further than {@link Replay.Markers#STEP} without a rewind flag is also a thing of the past. */
    @Test
    void jumpWithoutFlagIsPast() {
        Replay.Markers m = new Replay.Markers();
        assertEquals(Replay.Markers.Verdict.PAST, m.next(100, false), "first mark");
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(101, false));
        assertFalse(m.steady());
        assertEquals(Replay.Markers.Verdict.PAST, m.next(102, false));
        assertTrue(m.steady());
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(103, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(102, false));
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(103, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(104, false));
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(105, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(105 + Replay.Markers.STEP + 1, false));
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(126 + Replay.Markers.STEP, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(146, false));
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(146, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(200, true));
        m.reset();
        assertEquals(Replay.Markers.Verdict.PAST, m.next(200, false), "after leaving the world - the first mark");
        assertFalse(m.steady());
    }
}
