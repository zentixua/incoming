package ua.zentix.incoming.gametest.scenario;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Synchronous loading guard: the mod code read an unready chunk, and the server waited for it directly in tick (CLAUDE.md, "Nothing
 *  read in unloaded chunks from tick"). The chunk that is waiting for {@code ServerChunkCache.getChunk} becomes
 *  complete in the task completed inside the wait ({@code managedBlock}) - the event {@link ChunkEvent.Load} comes
 *  with a stack where under {@code getChunk} stands the one who was waiting. If this is a mod code (not a check), it is a violation.
 *  Downloads in the background come from the server task queue, without {@code getChunk} in the stack.
 */
final class SyncLoadWatch {
    private static final String MOD = "ua.zentix.incoming.";
    /** Check code: it loads chunks of sites immediately, this is not a violation. */
    private static final List<String> RIGS = List.of(MOD + "gametest.", MOD + "stress.", MOD + "scenario.", MOD + "rig.");
    private static final String CHUNK_CACHE = ServerChunkCache.class.getName();
    private static final String EVENT_LOOP = BlockableEventLoop.class.getName();

    /** Synchronous loading due to the fault of the mod: game tick, chunk and frame of the mod that was waiting. */
    record Violation(long gameTime, String chunk, String frame) {}

    private static final List<Violation> VIOLATIONS = new ArrayList<>();
    private static boolean registered;

    private SyncLoadWatch() {}

    /** Listen from the first script to the end of the run: loading events go on the server thread. */
    static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(SyncLoadWatch::onLoad);
    }

    private static void onLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.getServer().getRunningThread() != Thread.currentThread()) return;
        Optional<String> frame = StackWalker.getInstance().walk(frames -> {
            boolean inWait = false;
            for (var it = frames.iterator(); it.hasNext(); ) {
                StackWalker.StackFrame f = it.next();
                String cls = f.getClassName();
                if (!inWait) {
                    // the innermost expectation of a chunk
                    inWait = cls.equals(CHUNK_CACHE) && f.getMethodName().equals("getChunk");
                    continue;
                }
                // before the external wait (the task completed inside it is not the one who was waiting for this chunk)
                if (cls.equals(EVENT_LOOP) && f.getMethodName().equals("managedBlock")) return Optional.<String>empty();
                if (cls.startsWith(MOD) && RIGS.stream().noneMatch(cls::startsWith)) return Optional.of(cls + "." + f.getMethodName() + ":" + f.getLineNumber());
            }
            return Optional.<String>empty();
        });
        frame.ifPresent(f -> {
            synchronized (VIOLATIONS) {
                VIOLATIONS.add(new Violation(level.getGameTime(), e.getChunk().getPos().toString(), f));
            }
        });
    }

    /** Violations from game tick {@code from} inclusive. */
    static List<Violation> since(long from) {
        synchronized (VIOLATIONS) {
            return VIOLATIONS.stream().filter(v -> v.gameTime >= from).toList();
        }
    }
}
