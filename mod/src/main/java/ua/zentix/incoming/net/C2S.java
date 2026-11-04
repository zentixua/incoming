package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.util.StreamCodecs;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Client → server. Server trusts no claims: verifies hand, item, range and target itself. */
public final class C2S {
    private C2S() {}

    static final StreamCodec<ByteBuf, InteractionHand> HAND = ByteBufCodecs.BOOL.map(b -> b ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, h -> h == InteractionHand.OFF_HAND);

    /**
     * What client sees under binocular crosshairs. Client/server see moving targets slightly differently,
     * so client identifies its selection, and server locates and validates it.
     *
     * @param kind       0 — point, 1 — entity (entityId), 2 — Sable craft (plotPos — point in plot), 3 — ground location
     *                   from map (x and z; server finds ground height)
     * @param mapSurface map location's ground top from client map (first air above ground, like heightmap),
     *                   if known: server estimate until destination chunk is ready
     */
    public record AimHint(int kind, Vec3 point, int entityId, Vec3 plotPos, Optional<Integer> mapSurface) {
        public static final int POINT = 0, ENTITY = 1, AIRCRAFT = 2, GROUND = 3;

        public AimHint(int kind, Vec3 point, int entityId, Vec3 plotPos) {
            this(kind, point, entityId, plotPos, Optional.empty());
        }

        /** Ground location selected on map: x, z and client-map ground top if known. */
        public static AimHint ground(double x, double z, OptionalInt mapSurface) {
            return new AimHint(GROUND, new Vec3(x, 0, z), 0, Vec3.ZERO,
                    mapSurface.isPresent() ? Optional.of(mapSurface.getAsInt()) : Optional.empty());
        }
        public static final StreamCodec<ByteBuf, AimHint> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AimHint::kind, StreamCodecs.VEC3, AimHint::point, ByteBufCodecs.VAR_INT, AimHint::entityId,
                StreamCodecs.VEC3, AimHint::plotPos, ByteBufCodecs.optional(ByteBufCodecs.VAR_INT), AimHint::mapSurface, AimHint::new);
    }

    /**
     * Launch. Binoculars use {@code aim} (under crosshairs); remote screen uses loadout target mode
     * (where I look / around me / player / listed craft). {@code via}: targeting-map waypoints
     * (empty: launch builds path); {@code from}: map launch site (x, z; empty: near shooter), selected
     * only by operator/host, validated server-side ({@code ServerActions.launchSiteProblem}).
     */
    public record Fire(Loadout loadout, Optional<AimHint> aim, Optional<UUID> aircraft, Waypoints via, Optional<Vec3> from) implements CustomPacketPayload {
        public static final Type<Fire> TYPE = new Type<>(Incoming.id("fire"));
        public static final StreamCodec<ByteBuf, Fire> CODEC = StreamCodec.composite(
                Loadout.STREAM_CODEC, Fire::loadout,
                ByteBufCodecs.optional(AimHint.CODEC), Fire::aim,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Fire::aircraft,
                Waypoints.STREAM_CODEC, Fire::via,
                ByteBufCodecs.optional(StreamCodecs.VEC3), Fire::from,
                Fire::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Request a read-only nuclear order review; no payment or planning starts here. */
    public record ReviewNuclear(Fire order) implements CustomPacketPayload {
        public static final Type<ReviewNuclear> TYPE = new Type<>(Incoming.id("review_nuclear"));
        public static final StreamCodec<ByteBuf, ReviewNuclear> CODEC = Fire.CODEC.map(ReviewNuclear::new, ReviewNuclear::order);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Exact reviewed order: begin hold, cancel/release hold, commit, or discard review. */
    public record NuclearAction(UUID id, int action) implements CustomPacketPayload {
        public static final int BEGIN = 0, RELEASE = 1, COMMIT = 2, DISCARD = 3;
        public static final Type<NuclearAction> TYPE = new Type<>(Incoming.id("nuclear_action"));
        public static final StreamCodec<ByteBuf, NuclearAction> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, NuclearAction::id, ByteBufCodecs.VAR_INT, NuclearAction::action, NuclearAction::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Projectile camera aboard owned {@code projectile} looks along {@code yaw}/{@code pitch}: client
     * sends every tick while viewing ({@code strike.CameraLink}). No more packets means camera closed.
     */
    public record CameraView(UUID projectile, float yaw, float pitch) implements CustomPacketPayload {
        public static final Type<CameraView> TYPE = new Type<>(Incoming.id("camera_view"));
        public static final StreamCodec<ByteBuf, CameraView> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, CameraView::projectile, ByteBufCodecs.FLOAT, CameraView::yaw, ByteBufCodecs.FLOAT, CameraView::pitch,
                CameraView::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Retarget owned projectile to what its camera crosshairs point at. */
    public record Retarget(UUID projectile, AimHint aim) implements CustomPacketPayload {
        public static final Type<Retarget> TYPE = new Type<>(Incoming.id("retarget"));
        public static final StreamCodec<ByteBuf, Retarget> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Retarget::projectile, AimHint.CODEC, Retarget::aim, Retarget::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Save remote settings in held item. */
    public record SetLoadout(InteractionHand hand, Loadout loadout) implements CustomPacketPayload {
        public static final Type<SetLoadout> TYPE = new Type<>(Incoming.id("set_loadout"));
        public static final StreamCodec<ByteBuf, SetLoadout> CODEC = StreamCodec.composite(
                HAND, SetLoadout::hand, Loadout.STREAM_CODEC, SetLoadout::loadout, SetLoadout::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Map location selected (click), no order yet: server preloads area ({@code PickHints}).
     * Only x/z; same checks as a map order.
     */
    public record Pick(double x, double z) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(Incoming.id("pick"));
        public static final StreamCodec<ByteBuf, Pick> CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Pick::x, ByteBufCodecs.DOUBLE, Pick::z, Pick::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Targeting map open: current players (reply: {@link S2C.MapPlayers}); client asks twice per second. */
    public record MapPlayers() implements CustomPacketPayload {
        public static final Type<MapPlayers> TYPE = new Type<>(Incoming.id("map_players_query"));
        public static final StreamCodec<ByteBuf, MapPlayers> CODEC = StreamCodec.unit(new MapPlayers());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Remote cancel: stop player's own strikes ({@code ServerActions.recall}). */
    public record Clear() implements CustomPacketPayload {
        public static final Type<Clear> TYPE = new Type<>(Incoming.id("clear"));
        public static final StreamCodec<ByteBuf, Clear> CODEC = StreamCodec.unit(new Clear());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * “Second key” binding: confirm nearby player's nuclear launch ({@code strike.NuclearKeys}). Server decides what
     * to confirm, who is nearby and whether this is one's own launch.
     */
    public record NuclearKey() implements CustomPacketPayload {
        public static final Type<NuclearKey> TYPE = new Type<>(Incoming.id("second_key"));
        public static final StreamCodec<ByteBuf, NuclearKey> CODEC = StreamCodec.unit(new NuclearKey());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }


}
