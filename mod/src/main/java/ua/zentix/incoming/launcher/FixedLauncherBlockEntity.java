package ua.zentix.incoming.launcher;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.LaunchQueue;
import ua.zentix.incoming.entity.Launcher;
import ua.zentix.incoming.entity.LauncherMount;
import ua.zentix.incoming.entity.Rails;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.info.Readout;
import ua.zentix.incoming.registry.ModBlockEntities;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Local;
import ua.zentix.incoming.util.Magazine;

import java.util.UUID;

/**
 * Fixed launcher at its site ({@link FixedLauncherBlock}): placer (projectiles belong to them, their side is
 * friendly), munition inventory, mission ({@link Mission}), and rack on a turntable above the block ({@link
 * Launcher}, {@link LauncherMount#PAD} mount). Hopper, Create funnel and chute load inventory; mechanical arm and
 * belt work through a funnel ({@link Capabilities.ItemHandler#BLOCK} from any side; no extraction). Redstone
 * rising edge issues one mission order ({@link LauncherOrders#fire}) via the same path as a player order: rules,
 * active-projectile limit, launch sector, “Abort”. Clients receive only rack weapon, heading, and raising time;
 * mission and inventory stay server-side.
 */
public class FixedLauncherBlockEntity extends BlockEntity implements Launcher {
    /** Storage slots, as in a dispenser. */
    public static final int SLOTS = 9;

    @Nullable
    private UUID owner;
    /**
     * Munition inventory: insertion only from outside; spare slot holds a partly opened Grad pack when a hopper
     * keeps public slots full.
     */
    private final Magazine stock = new Magazine(SLOTS, 1, FixedLauncherBlockEntity::munition, this::setChanged);
    @Nullable
    private Mission mission;
    /** Weapon rack on the turntable (client renders it); null means no mission. */
    @Nullable
    private WeaponType rack;
    private float yaw;
    private long deployedAt = Long.MIN_VALUE / 2;
    private final LaunchQueue queue = new LaunchQueue();
    /** Result of last signal or its launch, for status (not saved). */
    @Nullable
    private Component last;

    public FixedLauncherBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FIXED_LAUNCHER.get(), pos, state);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.FIXED_LAUNCHER.get(), (be, side) -> be.stock.loader());
    }

    /**
     * Munitions for weapons accepted by the launcher ({@link Mission#accepts}): Shaheds, Lancets, missiles, Grad
     * packs.
     */
    public static boolean munition(ItemStack stack) {
        for (WeaponType w : WeaponType.values()) {
            if (Mission.accepts(w) && stack.is(w.spec().munition().item().get())) return true;
        }
        return false;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    @Nullable
    public Mission mission() {
        return mission;
    }

    /**
     * New mission (null clears): place its weapon rack on the turntable and turn toward target or first waypoint
     * if quiet.
     */
    public void setMission(@Nullable Mission mission) {
        this.mission = mission;
        WeaponType shown = mission != null && Mission.accepts(mission.weapon()) ? mission.weapon() : null;
        long now = level != null ? level.getGameTime() : 0;
        if (shown != rack) {
            // A different rack is placed anew: raises from zero.
            rack = shown;
            deployedAt = now;
        }
        if (mission != null && rack != null) turnTo(yawTo(mission.heading()), now);
        sync();
    }

    /** Heading from launcher to {@code p}, °. */
    public float yawTo(Vec3 p) {
        return FlightController.anglesTo(position(), p)[0];
    }

    /** Munition inventory (launch payment: {@link Munitions#pay(Munitions.Store, Munitions.Bill)}). */
    public Munitions.Store store() {
        return new Munitions.Store() {
            @Override
            public IItemHandlerModifiable items() {
                return stock;
            }

            @Override
            public void put(ItemStack s) {
                // Refunds and opened pack remainder go to spare slot if public slots are full; overflow there drops.
                ItemStack rest = stock.stow(s);
                if (!rest.isEmpty() && level != null) Block.popResource(level, worldPosition.above(), rest);
            }
        };
    }

    /** Insert munitions into storage; returns what did not fit. */
    public ItemStack load(ItemStack stack) {
        return stock.load(stack);
    }

    /** Items in storage. */
    public int stockCount() {
        return stock.count();
    }

    /** Comparator signal follows storage fullness. */
    public int comparator() {
        return stock.comparator();
    }

    /** Block broken or exploded: inventory drops. */
    void dropContents(Level level, BlockPos pos) {
        stock.dropAll(level, pos);
    }

    /**
     * Redstone signal arrived (or game-master command): issue mission order; failure clicks like an empty
     * dispenser and gives the reason in status.
     *
     * @return null if ordered; otherwise the reason it failed
     */
    @Nullable
    public Component fire(ServerLevel level) {
        return fire(level, null);
    }

    /** Same; {@code made} receives the answered order ({@link LauncherOrders#fire}). */
    @Nullable
    public Component fire(ServerLevel level, @Nullable java.util.function.Consumer<ua.zentix.incoming.strike.Order> made) {
        Component problem = LauncherOrders.fire(level, this, made);
        report(problem != null ? problem : Component.translatable("incoming.fixed_launcher.fired"));
        if (problem != null) level.levelEvent(1001, worldPosition, 0);
        return problem;
    }

    /** Order or salvo projectile result from this launcher: into status. */
    public void report(Component what) {
        last = what;
    }

    /** Result of last signal or its launch; null means nothing since loading. */
    @Nullable
    public Component lastReport() {
        return last;
    }

    /**
     * Status line for {@code viewer} at the launcher: the full line ({@link #status()}) for the owner's side and operators
     * ({@link Readout#privileged}); for anyone else the rack's weapon and the stock, and the last
     * result only when it tells nothing about the owner or the mission.
     */
    public Component status(ServerPlayer viewer) {
        if (Readout.privileged(viewer.server, owner, viewer)) return status();
        Component state = mission == null ? Component.translatable("incoming.fixed_launcher.no_mission", stockCount())
                : Component.translatable("incoming.fixed_launcher.status.public", mission.weapon().displayName(),
                Munitions.held(stock, mission.weapon().spec().munition().item().get(), mission.weapon().spec().munition().rounds()));
        boolean shown = last != null && (Readout.publicRefusal(last)
                || last.getContents() instanceof TranslatableContents t && t.getKey().equals("incoming.fixed_launcher.fired"));
        return shown ? Component.translatable("incoming.fixed_launcher.status.last", state, last) : state;
    }

    /** Full status line (owner's side, operators, the operator's {@code /incoming launcher} command). */
    public Component status() {
        Component state = mission == null ? Component.translatable("incoming.fixed_launcher.no_mission", stockCount())
                : Component.translatable("incoming.fixed_launcher.status", mission.weapon().displayName(), mission.count(),
                Math.round(mission.point().x), Math.round(mission.point().z), mission.spread(),
                Munitions.held(stock, mission.weapon().spec().munition().item().get(), mission.weapon().spec().munition().rounds()));
        return last == null ? state : Component.translatable("incoming.fixed_launcher.status.last", state, last);
    }

    // ---------------------------------------------------------------- rack (Launcher)

    @Override
    public WeaponType weapon() {
        return rack != null ? rack : WeaponType.DRONE;
    }

    /** A rack is on the turntable (mission weapon has one). */
    public boolean hasRack() {
        return rack != null;
    }

    /**
     * The craft carrying this launcher (Create Aeronautics, through {@link SubLevels}), or null on the ground. The block
     * sits in the craft's plot; rack heading, pivot and rails are kept in that frame and reach the world through the
     * craft's current pose.
     */
    @Nullable
    private SubLevels.Frame craft() {
        return level == null ? null : SubLevels.carrying(level, worldPosition);
    }

    /**
     * Where the launcher is in the world is known: on the ground, or on a craft that is loaded now. A block in Sable's plot
     * grid whose craft is not loaded has no world place, and its plot coordinates are a distant corner of the world.
     */
    public boolean located() {
        return level == null || craft() != null || !SubLevels.inPlotGrid(level, new net.minecraft.world.level.ChunkPos(worldPosition));
    }

    /** A craft carries this launcher now: its rails move with the craft ({@code StrikeProjectile.rideRail}). */
    @Override
    public boolean carried() {
        return craft() != null;
    }

    @Override
    public Vec3 position() {
        Vec3 own = Vec3.atBottomCenterOf(worldPosition);
        SubLevels.Frame craft = craft();
        return craft == null ? own : craft.toWorld(own);
    }

    /** Rack heading in the world: its heading on the turntable ({@link #rackYaw}) turned by the craft carrying it. */
    @Override
    public float yaw() {
        SubLevels.Frame craft = craft();
        return craft == null ? yaw : heading(craft.toWorldDirection(Local.horizontal(yaw)), yaw);
    }

    /** Rack heading on the turntable in the block's own frame (the craft's on a craft), °: what the renderer turns. */
    public float rackYaw() {
        return yaw;
    }

    @Override
    public Rails rails(float yaw) {
        SubLevels.Frame craft = craft();
        if (craft == null) return mount().rails(position(), yaw, weapon());
        Rails own = mount().rails(Vec3.atBottomCenterOf(worldPosition), ownYaw(craft, yaw), weapon());
        java.util.List<Vec3> points = new java.util.ArrayList<>(own.slots());
        for (Vec3 p : own.points()) points.add(craft.toWorld(p));
        float[] axis = FlightController.anglesTo(Vec3.ZERO, craft.toWorldDirection(own.direction()));
        return new Rails(points, axis[0], -axis[1]);
    }

    /** Rack heading on the turntable that points along world heading {@code yaw} on {@code craft}. */
    private static float ownYaw(SubLevels.Frame craft, float yaw) {
        return heading(craft.toPlotDirection(Local.horizontal(yaw)), yaw);
    }

    /** Heading of {@code dir}'s horizontal part; {@code fallback} for a vertical direction (a craft on its side). */
    private static float heading(Vec3 dir, float fallback) {
        return dir.x * dir.x + dir.z * dir.z < 1e-8 ? fallback : FlightController.anglesTo(Vec3.ZERO, dir)[0];
    }

    /** Rack heading without raising again: on block placement, away from placer (world heading). */
    public void setYaw(float yaw) {
        SubLevels.Frame craft = craft();
        this.yaw = craft == null ? yaw : ownYaw(craft, yaw);
        setChanged();
    }

    @Override
    public LauncherMount mount() {
        return LauncherMount.PAD;
    }

    @Override
    public long deployedAt() {
        return deployedAt;
    }

    @Override
    public LaunchQueue queue() {
        return queue;
    }

    /** Turn the rack to world heading {@code yaw} ({@link Launcher#turnTo}); kept as the turntable's own heading. */
    @Override
    public void turnTo(float yaw, long now) {
        if (turnedYaw(yaw, now) == yaw()) return;
        SubLevels.Frame craft = craft();
        this.yaw = craft == null ? yaw : ownYaw(craft, yaw);
        deployedAt = now;
        sync();
    }

    /** Save and send clients what they render. */
    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    // ---------------------------------------------------------------- saving and client

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("owner", owner);
        tag.put("stock", stock.serializeNBT(registries));
        if (mission != null) Mission.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), mission).ifSuccess(t -> tag.put("mission", t));
        saveShown(tag);
    }

    /** What the client sees: rack weapon, heading, raising tick. */
    private void saveShown(CompoundTag tag) {
        if (rack != null) tag.putString("rack", rack.getSerializedName());
        tag.putFloat("yaw", yaw);
        tag.putLong("deployed_at", deployedAt);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Client update packet omits owner, inventory, and mission: leave them alone.
        if (tag.hasUUID("owner")) owner = tag.getUUID("owner");
        if (tag.contains("stock")) stock.deserializeNBT(registries, tag.getCompound("stock"));
        if (tag.contains("mission")) {
            mission = Mission.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get("mission")).result().orElse(null);
        }
        rack = tag.contains("rack") ? WeaponType.CODEC.parse(NbtOps.INSTANCE, tag.get("rack")).result().orElse(null) : null;
        yaw = tag.getFloat("yaw");
        deployedAt = tag.contains("deployed_at") ? tag.getLong("deployed_at") : Long.MIN_VALUE / 2;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveShown(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
