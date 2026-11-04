package ua.zentix.incoming.nuclear.world;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModAttachments;

/** Server-thread API for preparation before payment/launch; no flight clock is changed. */
public final class RuinPreparation {
    private RuinPreparation() {}

    public enum Stage { RESOLVING, PLANNING, STORING, READY, PAUSED, FAILED, ABSENT }
    public enum Reason { NONE, RESOLVING, MEMORY, IO, INCOMPATIBLE, EXPIRED, LIMIT, INVALID }
    public record Status(Stage stage, long ready, long total, int reading, int planning, int writing,
                         long residentBytes, Reason reason, @Nullable Vec3 resolvedTarget) {}

    public static void prepare(ServerLevel level, UUID handle, UUID owner, Vec3 target, double yieldKt,
                               boolean surface, boolean airBurst) {
        NuclearWorld.get(level).prep().prepare(level, handle, owner, target, yieldKt, surface, airBurst);
    }

    public static Status status(ServerLevel level, UUID handle) {
        return NuclearWorld.get(level).prep().status(level, handle);
    }

    /** Call only after validating the polling player's identity, dimension and current server order. */
    public static boolean keepAlive(ServerLevel level, UUID handle, UUID owner) {
        return NuclearWorld.get(level).prep().keepAlive(level, handle, owner);
    }

    public static void cancel(ServerLevel level, UUID handle) {
        NuclearWorld.get(level).prep().cancel(handle);
    }

    public static void cancelOwner(MinecraftServer server, UUID owner) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.hasData(ModAttachments.NUCLEAR_WORLD)) NuclearWorld.get(level).prep().cancelOwner(owner);
        }
    }

    /** Pin an accepted carrier order without creating a timed scheduled detonation; -1 rejects. */
    public static int bindCarrier(ServerLevel level, UUID handle, UUID owner) {
        return NuclearWorld.get(level).prep().bindCarrier(level, handle, owner);
    }

    /** Release a lost/cancelled carrier's preparation identity; does not create or cancel an impact. */
    public static void cancelCarrier(ServerLevel level, int preparationIdentity) {
        NuclearWorld.get(level).prep().cancelCarrier(preparationIdentity);
    }

    /** Requires an already accepted scheduled order with this exact owner and geometry. */
    public static boolean bind(ServerLevel level, UUID handle, int strikeId) {
        return NuclearWorld.get(level).prep().bind(level, handle, strikeId);
    }
}
