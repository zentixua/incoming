package ua.zentix.incoming.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.defense.DefenseWorld;
import ua.zentix.incoming.defense.Interceptor;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.registry.ModEntities;

/**
 * Interceptor missile in the world: what players see and hear. Does not fly itself: {@link DefenseWorld} runs
 * flight ({@link Interceptor}) inside and outside the world; this entity is its visual over ticking chunks. The
 * server places it at the flight position each tick; the client draws model, plume, and smoke and plays the
 * engine. Not saved (flight lasts seconds), cannot be shot down, and collides with nothing: flight decides the
 * burst.
 */
public class InterceptorEntity extends Entity implements IEntityWithComplexSpawn {
    @Nullable
    private Interceptor flight;
    /** Ticks since launch (sent to client with spawn packet: launch plume and sound only for a young missile). */
    private int age;
    /** Client: server positions one per tick ({@link ArrivalQueue}); server tick packets arrive unevenly. */
    private final ArrivalQueue arrivals = new ArrivalQueue();

    public InterceptorEntity(EntityType<? extends InterceptorEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** Visual of {@code flight} at its position. */
    public static InterceptorEntity of(ServerLevel level, Interceptor flight) {
        InterceptorEntity e = new InterceptorEntity(ModEntities.INTERCEPTOR.get(), level);
        e.flight = flight;
        e.setUUID(flight.id());
        e.follow(flight);
        e.xo = e.getX();
        e.yo = e.getY();
        e.zo = e.getZ();
        e.yRotO = e.getYRot();
        e.xRotO = e.getXRot();
        return e;
    }

    /** Server: move to the current flight position, nose along heading. */
    public void follow(Interceptor f) {
        Vec3 p = f.position();
        float[] a = FlightController.anglesTo(Vec3.ZERO, f.direction());
        setPos(p.x, p.y, p.z);
        setYRot(a[0]);
        setXRot(a[1]);
        setDeltaMovement(f.direction().scale(f.speed()));
        age = f.age();
    }

    public int age() {
        return age;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            ArrivalQueue.Sample s = arrivals.next(getX(), getY(), getZ(), getYRot(), getXRot());
            if (s != null) lerpPositionAndRotationStep(1, s.x(), s.y(), s.z(), s.yRot(), s.xRot());
            age++;
            return;
        }
        // Flight ended or continues without this visual (outside ticking chunks, new visual).
        if (flight == null || flight.done() || flight.view() != this) discard();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        // Visible from afar: limited only by server entity tracking range.
        return distanceSq < 512 * 512;
    }

    @Override
    public ItemStack getPickResult() {
        return ItemStack.EMPTY;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        // Position arrives every server tick: one position per client tick, rather than vanilla's three (the missile would lag behind flight).
        arrivals.add(new ArrivalQueue.Sample(x, y, z, yRot, xRot));
    }

    @Override
    public double lerpTargetX() {
        ArrivalQueue.Sample s = arrivals.latest();
        return s != null ? s.x() : getX();
    }

    @Override
    public double lerpTargetY() {
        ArrivalQueue.Sample s = arrivals.latest();
        return s != null ? s.y() : getY();
    }

    @Override
    public double lerpTargetZ() {
        ArrivalQueue.Sample s = arrivals.latest();
        return s != null ? s.z() : getZ();
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(age);
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        // Vanilla stores the position with the previous (zero) rotation as “previous”, then applies packet rotation: in the first frames the model
        // would turn from south.
        yRotO = getYRot();
        xRotO = getXRot();
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        age = buf.readVarInt();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
