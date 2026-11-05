package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import ua.zentix.incoming.nuclear.world.landing.PreparedLandingModel;

import java.util.IdentityHashMap;

/** Devtest-only state facts. Capture on the server; the frozen table performs no block or world calls. */
final class PreparedLandingPalette {
    record Material(BlockState state, double volume, double mass, byte collision) {}
    /** Ordinary describes geometry only; cargo remains the live departure owner's responsibility. */
    record Facts(byte collision, boolean material, boolean ordinary, boolean fine, double dragMass, Material alongX, Material alongZ) {
        Material oriented(boolean x) { return x ? alongX : alongZ; }
    }

    private final IdentityHashMap<BlockState, Facts> states;

    private PreparedLandingPalette(IdentityHashMap<BlockState, Facts> states) {
        this.states = new IdentityHashMap<>(states);
    }

    Facts get(BlockState state) {
        Facts facts = states.get(state);
        if (facts == null) throw new IllegalArgumentException("State absent from frozen landing palette");
        return facts;
    }

    int size() { return states.size(); }
    long bytesEstimate() { return 128L + 320L * states.size(); }

    static final class Builder {
        private static final int MAX_STATES = 65_536;
        private final MinecraftServer server;
        private final IdentityHashMap<BlockState, Facts> states = new IdentityHashMap<>();

        Builder(ServerLevel level) {
            server = level.getServer();
            add(Blocks.AIR.defaultBlockState());
        }

        void add(BlockState state) {
            requireServer();
            if (states.containsKey(state)) return;
            if (states.size() == MAX_STATES) throw new IllegalArgumentException("Landing palette exceeds state bound");
            Blast.Props props = Blast.props(state);
            byte collision = collision(state, props);
            double mass = props.volume() * props.density();
            boolean material = !props.air() && !props.fluid() && !props.fixed()
                    && (props.bearing() || props.leaves()) && mass > 0;
            // A mod tag cannot terminally disperse a live cargo owner. Its geometry may be ordinary,
            // but its contents must still pass through the departure/placement ownership protocol.
            boolean fine = !state.hasBlockEntity() && (props.leaves()
                    || state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                    || state.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES));
            BlockState dry = state.hasProperty(BlockStateProperties.WATERLOGGED)
                    ? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
            boolean log = props.log() && dry.hasProperty(BlockStateProperties.AXIS);
            Material x = material(log ? dry.setValue(BlockStateProperties.AXIS, Direction.Axis.X) : dry);
            Material z = log ? material(dry.setValue(BlockStateProperties.AXIS, Direction.Axis.Z)) : x;
            boolean ordinary = material && !fine
                    && collision != PreparedLandingModel.UNSUPPORTED
                    && x.collision != PreparedLandingModel.UNSUPPORTED && z.collision != PreparedLandingModel.UNSUPPORTED;
            states.put(state, new Facts(collision, material, ordinary, fine, mass, x, z));
        }

        PreparedLandingPalette freeze() {
            requireServer();
            return new PreparedLandingPalette(states);
        }

        private void requireServer() {
            if (!server.isSameThread()) throw new IllegalStateException("Landing palette capture must run on the server thread");
        }

        private static Material material(BlockState state) {
            Blast.Props props = Blast.props(state);
            return new Material(state, props.volume(), props.volume() * props.density(), collision(state, props));
        }

        private static byte collision(BlockState state, Blast.Props props) {
            // Minecraft's two-argument getCollisionShape returns this state's immutable cache for
            // non-dynamic blocks. Dynamic shapes may inspect their position/neighbours/context.
            if (state.getBlock().hasDynamicShape()) return PreparedLandingModel.UNSUPPORTED;
            boolean empty = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
            boolean free = props.air() || props.fluid() || props.is(Blast.Props.REPLACEABLE)
                    || !props.bearing() && !props.leaves() && !props.fixed() && !state.hasBlockEntity();
            return free ? empty ? PreparedLandingModel.AIR : PreparedLandingModel.FREE_COLLIDING
                    : empty ? PreparedLandingModel.BLOCKED_EMPTY : PreparedLandingModel.SOLID;
        }
    }
}
