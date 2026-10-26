package ua.zentix.incoming.entity;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModEntities;

/**
 * Explosion debris: ground block, casing piece, or glowing ember. Flies ballistically (like falling_block),
 * injures those it hits; with {@code debris_stay}, boulders settle on loose ground, large parts remain as blocks,
 * chips scatter. Hot debris leaves a fire/smoke trail and may ignite its landing site.
 */
public class DebrisEntity extends Entity {
    private static final EntityDataAccessor<BlockState> DATA_BLOCK = SynchedEntityData.defineId(DebrisEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Boolean> DATA_HOT = SynchedEntityData.defineId(DebrisEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_CHIP = SynchedEntityData.defineId(DebrisEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int MAX_AGE = 600;

    private int age;
    private boolean hurtsEntities = true;
    /** Ground/charred material, as distinct from an actual large piece of the airframe. Server only. */
    private boolean groundMaterial;
    private final IntSet hit = new IntOpenHashSet();
    /** Client: random rotation in flight (degrees per tick around two axes). */
    public final float spinX, spinZ;

    public DebrisEntity(EntityType<? extends DebrisEntity> type, Level level) {
        super(type, level);
        this.spinX = (level.random.nextFloat() - 0.5f) * 40f;
        this.spinZ = (level.random.nextFloat() - 0.5f) * 40f;
    }

    public static DebrisEntity create(ServerLevel level, Vec3 pos, BlockState state, boolean hot, boolean hurts, Vec3 velocity) {
        DebrisEntity d = new DebrisEntity(ModEntities.DEBRIS.get(), level);
        d.setPos(pos.x, pos.y, pos.z);
        d.xo = pos.x;
        d.yo = pos.y;
        d.zo = pos.z;
        d.entityData.set(DATA_BLOCK, state);
        d.entityData.set(DATA_HOT, hot);
        d.hurtsEntities = hurts;
        d.setDeltaMovement(velocity);
        return d;
    }

    /** Small fragments, not a whole block. Large ground clods, airframe pieces and burning debris use create(). */
    public static DebrisEntity chip(ServerLevel level, Vec3 pos, BlockState state, boolean hot, boolean hurts, Vec3 velocity) {
        DebrisEntity debris = create(level, pos, state, hot, hurts, velocity);
        debris.entityData.set(DATA_CHIP, true);
        return debris;
    }

    /** Source geometry chooses the size; the landing surface also decides whether loose material can form a block. */
    public static DebrisEntity ground(ServerLevel level, Vec3 pos, BlockState state, boolean hot, boolean hurts, Vec3 velocity, boolean small) {
        DebrisEntity debris = small ? chip(level, pos, state, hot, hurts, velocity) : create(level, pos, state, hot, hurts, velocity);
        debris.groundMaterial = true;
        return debris;
    }

    public boolean isChip() { return entityData.get(DATA_CHIP); }

    public BlockState blockState() {
        return entityData.get(DATA_BLOCK);
    }

    public boolean isHot() {
        return entityData.get(DATA_HOT);
    }

    public int age() {
        return age;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    @Override
    public void tick() {
        if (blockState().isAir()) {
            discard();
            return;
        }
        age++;
        Vec3 before = getDeltaMovement();
        applyGravity();
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(0.98));

        if (level().isClientSide) return;
        ServerLevel level = (ServerLevel) level();
        // Left ticking chunks (beyond the target-area edge): it no longer ticks there and would hang in air until the chunk
        // unloads; scatter immediately, without placing a block where the world does not tick.
        if (!level.isPositionEntityTicking(blockPosition())) {
            discard();
            return;
        }
        if (hurtsEntities && before.lengthSqr() > 0.09) hurtWhoIsHit(level, before);
        if (onGround() || horizontalCollision && before.y < 0 && getDeltaMovement().horizontalDistanceSqr() < 1.0e-4) {
            land(level, before);
        } else if (age > MAX_AGE || getY() < level.getMinBuildHeight() - 16) {
            discard();
        }
    }

    /** Like falling_block: 2 damage per block fallen, capped at 16; once per victim. */
    private void hurtWhoIsHit(ServerLevel level, Vec3 v) {
        for (Entity e : level.getEntities(this, getBoundingBox().expandTowards(v).inflate(0.2), e -> e instanceof LivingEntity && e.isAlive())) {
            if (!hit.add(e.getId())) continue;
            float dmg = (float) Math.min(16, Math.max(2, fallDistance * 2 + v.length() * 3));
            e.hurt(ModDamageTypes.source(level, ModDamageTypes.DEBRIS, this, null), dmg);
        }
    }

    private void land(ServerLevel level, Vec3 impactVelocity) {
        BlockPos pos = blockPosition();
        if (impactVelocity.y <= -0.05) {
            // Impact uses its material's sound (vanilla block sounds: metal, wood, stone, ground), rather than always gravel and
            // stone; volume unchanged: representative debris has no mass yet (shared blast material accounting is still ahead).
            SoundType sound = blockState().getSoundType(level, pos, this);
            level.playSound(null, pos, sound.getBreakSound(), SoundSource.BLOCKS, 2.0f * sound.getVolume(), 0.6f * sound.getPitch());
            level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, 1.5f * sound.getVolume(), 0.7f * sound.getPitch());
            level.sendParticles(ParticleTypes.POOF, getX(), getY(), getZ(), 5, 0.3, 0.1, 0.3, 0.03);
            if (isHot()) level.sendParticles(ParticleTypes.LAVA, getX(), getY(), getZ(), 3, 0.2, 0.1, 0.2, 0);
        }
        if (IncomingConfig.SERVER.debrisStay.get()) {
            // Loose fallout can accumulate on earth, not turn an intact cohesive face into metre-sized cubes.
            boolean looseFloor = !ua.zentix.incoming.warhead.SurfaceBlocks.cohesive(level.getBlockState(pos.below()));
            if (!isChip() && (!groundMaterial || looseFloor)) place(level, pos);
        }
        discard();
    }

    private void place(ServerLevel level, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        BlockState state = blockState();
        boolean replaceable = here.canBeReplaced(new DirectionalPlaceContext(level, pos, Direction.DOWN, ItemStack.EMPTY, Direction.UP));
        boolean supported = state.canSurvive(level, pos) && !FallingBlock.isFree(level.getBlockState(pos.below()));
        if (!replaceable || !supported) return;
        // Burning debris ignites only fuel it lands on or beside; on stone and ground it burns out by itself.
        if (state.is(Blocks.FIRE) && (!IncomingConfig.SERVER.fire.get() || !touchesFuel(level, pos))) return;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && level.getFluidState(pos).getType() == Fluids.WATER) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        level.setBlock(pos, state, 3);
    }

    /** Fuel nearby: fire here will keep burning rather than go out by itself. */
    private static boolean touchesFuel(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).isFlammable(level, n, d.getOpposite())) return true;
        }
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_BLOCK, Blocks.GRAVEL.defaultBlockState());
        builder.define(DATA_HOT, false);
        builder.define(DATA_CHIP, false);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(DATA_BLOCK, NbtUtils.readBlockState(level().holderLookup(Registries.BLOCK), tag.getCompound("block")));
        entityData.set(DATA_HOT, tag.getBoolean("hot"));
        hurtsEntities = tag.getBoolean("hurts");
        age = tag.getInt("age");
        entityData.set(DATA_CHIP, tag.getBoolean("chip"));
        groundMaterial = tag.getBoolean("ground_material");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("block", NbtUtils.writeBlockState(blockState()));
        tag.putBoolean("hot", isHot());
        tag.putBoolean("hurts", hurtsEntities);
        tag.putInt("age", age);
        tag.putBoolean("chip", isChip());
        tag.putBoolean("ground_material", groundMaterial);
    }

    @Nullable
    @Override
    public ItemStack getPickResult() {
        return ItemStack.EMPTY;
    }
}
