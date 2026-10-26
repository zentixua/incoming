package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.util.Nbt;
import ua.zentix.incoming.util.Terrain;

/**
 * Where an order launches from when not the shooter: launch site ({@link Place}, {@code /incoming salvo … from x
 * z} or designator map for operator/host; launcher placed there, {@link LaunchSite.Post}) or fixed launcher
 * ({@link Fixed}: projectiles from its rack and inventory). Launch point {@link #at} determines route reach
 * ({@link ServerActions#routeProblem}) and approach heading; salvo stores it ({@link SalvoData}).
 */
public sealed interface LaunchOrigin {
    /** Launch point (horizontal; launch resolves height). */
    Vec3 at();

    /** Ordered launch site. */
    record Place(Vec3 at) implements LaunchOrigin {}

    /** Fixed launcher at {@code pos} ({@link FixedLauncherBlockEntity}). */
    record Fixed(BlockPos pos) implements LaunchOrigin {
        public Fixed {
            pos = pos.immutable();
        }

        @Override
        public Vec3 at() {
            return Vec3.atBottomCenterOf(pos);
        }

        /** Launcher at site; null if its chunk is unready (do not load for launch) or block is gone. */
        @Nullable
        public FixedLauncherBlockEntity launcher(ServerLevel level) {
            if (!Terrain.ready(level, pos)) return null;
            return level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be ? be : null;
        }
    }

    /**
     * Write {@code origin} into {@code tag} (null writes nothing): site under {@code from}, launcher under {@code
     * from_launcher}.
     */
    static void save(CompoundTag tag, @Nullable LaunchOrigin origin) {
        switch (origin) {
            case Place p -> Nbt.putVec(tag, "from", p.at());
            case Fixed f -> tag.putLong("from_launcher", f.pos().asLong());
            case null -> {}
        }
    }

    /** Read what {@link #save} wrote; null if absent. */
    @Nullable
    static LaunchOrigin load(CompoundTag tag) {
        if (tag.contains("from_launcher")) return new Fixed(BlockPos.of(tag.getLong("from_launcher")));
        Vec3 from = Nbt.getVec(tag, "from");
        return from == null ? null : new Place(from);
    }
}
