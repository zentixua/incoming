package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.IOUtilities;
import ua.zentix.incoming.Incoming;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Dimension-owned cold-load admission. Disk data remains authoritative while capacity is unavailable. */
final class LedgerLoads {
    private final Map<String, Load<?>> loads = new HashMap<>();

    <T extends LedgerData> T get(ServerLevel level, NuclearWorld owner, String name,
                               Supplier<T> empty) {
        var storage = level.getDataStorage();
        var cached = storage.cache.get(name);
        if (cached instanceof LedgerData ledger) owner.ledger(ledger);
        Load<?> previous = loads.get(name);
        if (previous != null) {
            previous.poll(level, owner, name);
            cached = storage.cache.get(name);
            if (previous.done) loads.remove(name);
        }
        if (cached != null) {
            @SuppressWarnings("unchecked") T typed = (T) cached;
            return typed;
        }
        T value = empty.get();
        Path file = storage.dataFolder.toPath().resolve(name + ".dat");
        storage.set(name, value); // Install before any recursive accounting/event lookup.
        owner.ledger(value);
        if (Files.exists(file)) {
            value.loading(true);
            var active = name.equals("incoming_crater_matter")
                    ? java.util.Set.copyOf(ua.zentix.incoming.nuclear.NuclearEvents.get(level).craters().keySet())
                    : java.util.Set.<Integer>of();
            Load<T> load = new Load<>(value, file, active);
            loads.put(name, load);
            IOUtilities.withIOWorker(() -> {
                try { load.measured = LedgerScan.measure(file, name, load.activeCraters, NuclearPrep.PLANS_CAP); }
                catch (Exception error) { load.error = error; }
                finally { load.scanning = false; }
            });
        }
        return value;
    }

    boolean pending() { return !loads.isEmpty(); }

    private static final class Load<T extends LedgerData> {
        final T placeholder;
        final Path file;
        final java.util.Set<Integer> activeCraters;
        volatile boolean scanning = true, reading;
        volatile LedgerScan.Measure measured;
        volatile T result;
        volatile Exception error;
        long reservation;
        boolean done, reported;

        Load(T placeholder, Path file, java.util.Set<Integer> activeCraters) {
            this.placeholder = placeholder; this.file = file; this.activeCraters = activeCraters;
        }

        void poll(ServerLevel level, NuclearWorld owner, String name) {
            if (scanning || reading) return;
            if (level.getDataStorage().cache.get(name) != placeholder) {
                owner.materialReservation -= reservation; reservation = 0; result = null; done = true; return;
            }
            if (result != null) {
                owner.ledger(result); // Transfer ownership before releasing the import reservation.
                owner.materialReservation -= reservation;
                reservation = 0;
                // Test/world replacement invalidates publication, but never releases a running read early.
                if (level.getDataStorage().cache.get(name) == placeholder) level.getDataStorage().set(name, result);
                result = null; done = true;
                return;
            }
            if (error != null) {
                owner.materialReservation -= reservation;
                reservation = 0;
                if (!reported) {
                    Incoming.LOG.error("Material ledger remains paused; original file preserved: {}", file, error);
                    reported = true;
                }
                return;
            }
            long required = measured.owned();
            if (required > NuclearPrep.PLANS_CAP) {
                if (!reported) {
                    Incoming.LOG.warn("Material ledger remains on disk: {} needs {} bytes of bounded import room (cap {})",
                            file, required, NuclearPrep.PLANS_CAP);
                    reported = true;
                }
                return;
            }
            // Polling must not recurse through get()/admission while collecting cross-dimension charges.
            if (required > NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level)) return;
            owner.materialReservation += required;
            reservation = required;
            reading = true;
            var registries = level.registryAccess();
            IOUtilities.withIOWorker(() -> {
                try {
                    @SuppressWarnings("unchecked") T restored = (T) LedgerReader.read(file, name, registries, measured.row(), activeCraters);
                    result = restored;
                } catch (Exception failure) { error = failure; }
                finally { reading = false; }
            });
        }
    }
}
