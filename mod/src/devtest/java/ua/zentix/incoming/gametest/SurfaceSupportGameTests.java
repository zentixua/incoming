package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.SurfaceDamage;
import ua.zentix.incoming.warhead.Warheads;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Regression classes: backing rather than block names, fractional cohesive excavation, outward block/body order. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceSupportGameTests {
    private static final BlockPos C = new BlockPos(32, 12, 32);

    private SurfaceSupportGameTests() {}

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_support", skyAccess = true)
    public static void backingHoldsTheSamePanelThatBreaksInAir(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(C.above(2));
        Vec3 at = Vec3.atCenterOf(c);
        BlockPos free = c.east(2), backed = c.west(2);
        for (BlockPos p : List.of(free, backed)) level.setBlock(p, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
        level.setBlock(backed.west(), Blocks.DIRT.defaultBlockState(), 2);
        Warheads.Probe blast = Warheads.testCharge(level, at, WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(level.getBlockState(free).isAir(), "free concrete panel survived");
            h.assertTrue(level.getBlockState(backed).is(Blocks.GRAY_CONCRETE), "backed concrete panel broke");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fractional", skyAccess = true)
    public static void lancetDamagesSolidStoneWithoutOpeningTheSlab(GameTestHelper h) {
        fractional(h, false);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fractional_ground", skyAccess = true)
    public static void shahedDamagesSupportedStoneWithoutRemovingAMetre(GameTestHelper h) {
        fractional(h, true);
    }

    private static void fractional(GameTestHelper h, boolean supported) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos top = h.absolutePos(C.above(3));
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, -3, -6), top.offset(6, 0, 6))) {
            level.setBlock(p, p.getY() == top.getY() ? stone : supported ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        Warheads.detonate(level, supported ? WeaponType.DRONE : WeaponType.LOITER, Vec3.atBottomCenterOf(top.above()), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "impact unfinished");
            for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, 0, -6), top.offset(6, 0, 6))) {
                h.assertTrue(level.getBlockState(p).is(Blocks.STONE), "sub-block excavation removed slab at " + p);
            }
            double depth = SurfaceDamage.depth(level, top);
            h.assertTrue(depth > 0 && depth < 1, "missing fractional depth: " + depth);
            List<SurfaceDamage.Mark> marks = level.getChunkAt(top).getData(ModAttachments.SURFACE_DAMAGE);
            var encoded = SurfaceDamage.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, marks).getOrThrow();
            var restored = SurfaceDamage.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded).getOrThrow();
            h.assertTrue(restored.equals(marks), "fractional damage did not survive save/load codec");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_front_wall", skyAccess = true)
    public static void bodiesBehindBrokenWallsAreHitButIntactWallsStillShield(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(C.above(2));
        Vec3 at = Vec3.atCenterOf(c);
        for (int y = -2; y <= 3; y++) for (int z = -4; z <= 4; z++) {
            level.setBlock(c.offset(1, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            level.setBlock(c.offset(-1, y, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
        }
        Pig open = pig(level, Vec3.atBottomCenterOf(c.offset(2, 0, 0)), 1);
        Pig shielded = pig(level, Vec3.atBottomCenterOf(c.offset(-2, 0, 0)), 2);
        h.assertTrue(net.minecraft.world.level.Explosion.getSeenPercent(at, open) == 0, "broken-wall test has no initial shield");
        Warheads.Probe blast = Warheads.testCharge(level, at, WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(level.getBlockState(c.east()).isAir(), "plank wall did not break");
            h.assertTrue(!open.isAlive() || open.getHealth() < open.getMaxHealth(), "body behind broken wall not wounded");
            h.assertTrue(shielded.isAlive() && shielded.getHealth() == shielded.getMaxHealth(), "body behind obsidian wounded");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_front_breach", skyAccess = true)
    public static void theSameChargeWoundsABodyUnderItsBreach(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(C.above(5));
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, -4, -6), top.offset(6, 0, 6))) {
            level.setBlock(p, p.getY() == top.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        Vec3 at = Vec3.atBottomCenterOf(top.above());
        Pig body = pig(level, Vec3.atBottomCenterOf(top.below(2)), 3);
        h.assertTrue(net.minecraft.world.level.Explosion.getSeenPercent(at, body) == 0, "slab does not initially shield body");
        Warheads.Probe blast = Warheads.testSurfaceCharge(level, at, WeaponType.DRONE.spec().charge(), 15);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(blast.breached() > 0 && level.getBlockState(top).isAir(), "no breach");
            h.assertTrue(!body.isAlive() || body.getHealth() < body.getMaxHealth(), "body under breach not wounded");
        });
    }

    private static Pig pig(ServerLevel level, Vec3 at, int id) {
        Pig pig = EntityType.PIG.create(level);
        pig.setUUID(new UUID(0x415333, id));
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.setPos(at);
        level.addFreshEntity(pig);
        return pig;
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_front_order", skyAccess = true)
    public static void theFrontDoesNotRemoveAFurtherWallBeforeHittingANearerBody(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(C.above(2)), far = c.east(4);
        level.setBlock(far, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        boolean[] hit = {false}, intactAtHit = {false};
        Pig body = new Pig(EntityType.PIG, level) {
            @Override
            public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
                hit[0] = true;
                intactAtHit[0] = level.getBlockState(far).is(Blocks.OAK_PLANKS);
                return super.hurt(source, amount);
            }
        };
        body.setUUID(new UUID(0x415333, 4));
        body.setNoAi(true);
        body.setNoGravity(true);
        body.setPos(Vec3.atBottomCenterOf(c.east(2)));
        level.addFreshEntity(body);
        Warheads.Probe blast = Warheads.testCharge(level, Vec3.atCenterOf(c), WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(hit[0] && intactAtHit[0], "further wall was already gone at the nearer body's hit");
            h.assertTrue(level.getBlockState(far).isAir(), "further wall never broke: ordering test is empty");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater_seed", skyAccess = true)
    public static void craterSamplingDoesNotDependOnOtherWorldRandomDraws(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos a = h.absolutePos(new BlockPos(18, 11, 32)), b = a.east(28);
        for (BlockPos top : List.of(a, b)) {
            for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, -5, -6), top.offset(6, 0, 6))) {
                level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        Warheads.testDetonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(a.above()), null, 0x415333L);
        for (int i = 0; i < 1024; i++) level.random.nextLong();
        Warheads.testDetonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(b.above()), null, 0x415333L);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "impacts unfinished");
            int holes = 0;
            for (BlockPos rel : BlockPos.betweenClosed(new BlockPos(-5, -4, -5), new BlockPos(5, 0, 5))) {
                BlockState left = level.getBlockState(a.offset(rel)), right = level.getBlockState(b.offset(rel));
                h.assertTrue(left.equals(right), "same impact seed produced different craters at " + rel);
                // in the removed one the fuel crater (FuelFires) can burn: fire is also a pit
                if (left.isAir() || left.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) holes++;
            }
            h.assertTrue(holes > 0, "seed comparison did not excavate soil");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_surface_wall", skyAccess = true)
    public static void surfaceExcavationDoesNotJumpToTheTopOfAnAdjacentThickWall(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos ground = h.absolutePos(new BlockPos(32, 11, 32));
        for (BlockPos p : BlockPos.betweenClosed(ground.offset(0, 1, -4), ground.offset(1, 7, 4)))
            level.setBlock(p, Blocks.GRAY_CONCRETE.defaultBlockState(), Block.UPDATE_CLIENTS);
        // Exercise the entire impact, including the air wave and the crater.
        Warheads.testDetonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(ground.offset(-2, 1, 0)), null, 0x415333);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "impact unfinished");
            for (int x = 0; x <= 1; x++) for (int z = -2; z <= 2; z++) {
                BlockPos cap = ground.offset(x, 7, z);
                h.assertTrue(level.getBlockState(cap).is(Blocks.GRAY_CONCRETE), "crater shaved wall cap " + cap);
                h.assertTrue(SurfaceDamage.depth(level, cap) == 0, "cracks moved to wall cap " + cap);
                h.assertTrue(level.getBlockState(cap.above()).isAir(), "rim deposited on wall cap");
            }
        });
    }

    @GameTest(template = "range", timeoutTicks = 80, batch = "blast_surface_rubble", skyAccess = true)
    public static void surfaceRubbleIsTransientRebuiltAndClearedWithTheBlock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(C.above(3));
        level.setBlock(top, Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
        SurfaceDamage.mark(level, top, level.getBlockState(top), 0.4);
        var displays = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class,
                new AABB(top).inflate(2), e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get());
        h.assertTrue(displays.size() == 1, "expected one composite surface display: " + displays.size());
        var display = displays.getFirst();
        h.assertTrue(display.shouldRenderAtSqrDistance(64 * 64), "rubble inherits the 32 m crack limit");
        h.assertTrue(!display.save(new net.minecraft.nbt.CompoundTag()), "surface entity is saved with the world");
        var encoded = SurfaceDamage.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,
                level.getChunkAt(top).getData(ModAttachments.SURFACE_DAMAGE)).getOrThrow();
        var restored = SurfaceDamage.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded).getOrThrow();
        h.assertTrue(restored.stream().anyMatch(m -> m.pos().equals(top) && m.depth() == 0.4 && m.rubble().isEmpty()),
                "damage did not save independently of entity UUIDs");
        display.discard();
        level.getChunkAt(top).setData(ModAttachments.SURFACE_DAMAGE, restored);
        SurfaceDamage.mark(level, top, level.getBlockState(top), 0.4);
        displays = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, new AABB(top).inflate(2),
                e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get());
        h.assertTrue(displays.size() == 1 && !displays.getFirst().getUUID().equals(display.getUUID()), "saved damage did not rebuild its display");
        UUID replacement = displays.getFirst().getUUID();
        level.setBlock(top, Blocks.AIR.defaultBlockState(), 2);
        h.runAtTickTime(30, () -> {
            h.assertTrue(level.getEntity(replacement) == null, "rubble survived removal of its substrate");
            h.succeed();
        });
    }

    @GameTest(template = "range", timeoutTicks = 80, batch = "blast_surface_cap", skyAccess = true)
    public static void surfaceBudgetDoesNotLimitSavedDamageOrDuplicateColumns(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // The budget is per world, while batches share that world. Retire preceding test presentation first.
        java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
                .filter(e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get()).toList()
                .forEach(net.minecraft.world.entity.Entity::discard);
        StrikeGameTests.afterTest(h, () -> level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class,
                h.getBounds().inflate(4), e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get())
                .forEach(net.minecraft.world.entity.Entity::discard));
        int marked = 0;
        for (int x = 16; x < 48; x++) for (int z = 16; z < 48; z++) {
            BlockPos p = h.absolutePos(new BlockPos(x, C.getY() + 3, z));
            level.setBlock(p, Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
            SurfaceDamage.mark(level, p, level.getBlockState(p), 0.4);
            SurfaceDamage.mark(level, p, level.getBlockState(p), 0.4);
            h.assertTrue(SurfaceDamage.depth(level, p) == 0.4, "display cap lost damage");
            marked++;
        }
        var displays = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class,
                h.getBounds().inflate(4), e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get());
        h.assertTrue(marked > SurfaceDamage.DISPLAY_CAP && !displays.isEmpty() && displays.size() <= SurfaceDamage.DISPLAY_CAP,
                "unbounded surface entities: " + displays.size());
        h.assertTrue(displays.stream().noneMatch(e -> e.save(new net.minecraft.nbt.CompoundTag())), "saved surface entity");
        // Reuse a slot in the same tick, rather than waiting for the periodic cleanup.
        displays.getFirst().discard();
        BlockPos fresh = h.absolutePos(new BlockPos(48, C.getY() + 3, 32));
        level.setBlock(fresh, Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
        SurfaceDamage.mark(level, fresh, level.getBlockState(fresh), 0.4);
        h.assertTrue(!level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, new AABB(fresh).inflate(0.1),
                e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get()).isEmpty(), "removed display still reserves a slot");
        var legacy = EntityType.BLOCK_DISPLAY.create(level);
        legacy.setPos(h.absolutePos(C).getCenter());
        legacy.getPersistentData().putLong("incoming_surface_pos", h.absolutePos(C).asLong());
        h.assertTrue(!level.addFreshEntity(legacy), "legacy saved rubble survived migration");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 700, batch = "blast_surface_salvo", skyAccess = true)
    public static void stoneSalvoLeavesAtMostTheDisplayBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 12; x <= 52; x++) for (int z = 12; z <= 52; z++) {
            BlockPos p = h.absolutePos(new BlockPos(x, C.getY(), z));
            level.setBlock(p.below(), Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        for (int x = 18; x <= 46; x += 7) for (int z = 18; z <= 46; z += 7)
            Warheads.testDetonate(level, WeaponType.DRONE, h.absolutePos(new BlockPos(x, C.getY(), z)).getCenter(), null, x * 100L + z);
        int[] maximum = {0};
        h.onEachTick(() -> {
            int count = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, h.getBounds().inflate(4),
                    e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get()).size();
            maximum[0] = Math.max(maximum[0], count);
            h.assertTrue(count <= SurfaceDamage.DISPLAY_CAP, "salvo exceeded display budget during impact");
        });
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "salvo unfinished");
            h.assertTrue(maximum[0] > 0, "salvo never displayed partial damage");
            var displays = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, h.getBounds().inflate(4),
                    e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get());
            h.assertTrue(displays.size() <= SurfaceDamage.DISPLAY_CAP, "salvo left " + displays.size() + " entities");
            h.assertTrue(displays.stream().noneMatch(e -> e.save(new net.minecraft.nbt.CompoundTag())), "salvo left saved rubble");
            h.assertTrue(SurfaceDamage.depth(level, h.absolutePos(new BlockPos(18, C.getY(), 18))) > 0, "salvo never damaged stone");
        });
    }

    @GameTest(template = "range", timeoutTicks = 90, batch = "blast_surface_chip", skyAccess = true)
    public static void conventionalChipDoesNotPlantAMetreBlockOnIntactPaving(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(C.above(3));
        level.setBlock(top, Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
        boolean stay = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(true);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(stay));
        var chip = ua.zentix.incoming.entity.DebrisEntity.chip(level, top.above(2).getCenter(),
                Blocks.BLACK_CONCRETE.defaultBlockState(), false, true, new Vec3(0, -0.2, 0));
        level.addFreshEntity(chip);
        h.runAtTickTime(40, () -> {
            h.assertTrue(!chip.isAlive(), "chip did not land");
            h.assertTrue(level.getBlockState(top).is(Blocks.BLACK_CONCRETE), "chip damaged its intact paving");
            h.assertTrue(level.getBlockState(top.above()).isAir(), "chip planted a full metre block");
            h.succeed();
        });
    }

    @GameTest(template = "range", timeoutTicks = 90, batch = "blast_surface_debris_stay", skyAccess = true)
    public static void debrisStayRetainsClodsAirframePartsAndBurningDebris(GameTestHelper h) {
        debrisLanding(h, true);
    }

    @GameTest(template = "range", timeoutTicks = 90, batch = "blast_surface_debris_off", skyAccess = true)
    public static void debrisStayOffDispersesAllLandingKinds(GameTestHelper h) {
        debrisLanding(h, false);
    }

    private static void debrisLanding(GameTestHelper h, boolean stay) {
        ServerLevel level = h.getLevel();
        boolean old = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(stay);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(old));
        List<BlockPos> floors = new java.util.ArrayList<>();
        List<ua.zentix.incoming.entity.DebrisEntity> pieces = new java.util.ArrayList<>();
        List<BlockState> states = List.of(Blocks.COARSE_DIRT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(),
                Blocks.GRAVEL.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState(), Blocks.CAMPFIRE.defaultBlockState());
        for (int i = 0; i < states.size(); i++) {
            BlockPos floor = h.absolutePos(new BlockPos(20 + i * 4, C.getY() + 3, 32));
            floors.add(floor);
            level.setBlock(floor, (i == 1 || i == 3 ? Blocks.BLACK_CONCRETE : Blocks.DIRT).defaultBlockState(), 2);
            Vec3 start = floor.above(2).getCenter(), velocity = new Vec3(0, -0.2, 0);
            var piece = i < 3 ? ua.zentix.incoming.entity.DebrisEntity.ground(level, start, states.get(i), false, true, velocity, i == 2)
                    : ua.zentix.incoming.entity.DebrisEntity.create(level, start, states.get(i), i == 4, false, velocity);
            pieces.add(piece);
            level.addFreshEntity(piece);
        }
        StrikeGameTests.afterTest(h, () -> pieces.forEach(net.minecraft.world.entity.Entity::discard));
        h.runAtTickTime(40, () -> {
            h.assertTrue(pieces.stream().noneMatch(net.minecraft.world.entity.Entity::isAlive), "debris did not land");
            for (int i = 0; i < states.size(); i++) {
                boolean placed = stay && (i == 0 || i >= 3);
                BlockState landed = level.getBlockState(floors.get(i).above());
                h.assertTrue(placed ? landed.getBlock() == states.get(i).getBlock() : landed.isAir(),
                        "debris_stay=" + stay + " case " + i + " left " + landed);
            }
            h.succeed();
        });
    }

    @GameTest(template = "range", timeoutTicks = 90, batch = "blast_surface_debris_source", skyAccess = true)
    public static void spawnersKeepLargeGroundClodsAndAircraftWreckage(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos earth = h.absolutePos(new BlockPos(20, C.getY() + 3, 32));
        BlockPos paving = earth.east(10), wreck = earth.west(8);
        level.setBlock(earth, Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(paving, Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
        ua.zentix.incoming.warhead.DebrisSpawner.drone(level, earth.getCenter(), ua.zentix.incoming.warhead.GroundMaterial.DIRT);
        ua.zentix.incoming.warhead.DebrisSpawner.drone(level, paving.getCenter(), ua.zentix.incoming.warhead.GroundMaterial.STONE);
        ua.zentix.incoming.warhead.DebrisSpawner.wreck(level, wreck.getCenter(), Vec3.ZERO, 12);
        var clods = level.getEntitiesOfClass(ua.zentix.incoming.entity.DebrisEntity.class, new AABB(earth).inflate(3));
        var chips = level.getEntitiesOfClass(ua.zentix.incoming.entity.DebrisEntity.class, new AABB(paving).inflate(3));
        var aircraft = level.getEntitiesOfClass(ua.zentix.incoming.entity.DebrisEntity.class, new AABB(wreck).inflate(3));
        h.assertTrue(!clods.isEmpty() && clods.stream().noneMatch(ua.zentix.incoming.entity.DebrisEntity::isChip), "expected nonempty earth debris containing only large clods");
        h.assertTrue(chips.stream().anyMatch(ua.zentix.incoming.entity.DebrisEntity::isChip), "expected small chips from intact paving");
        h.assertTrue(!aircraft.isEmpty() && aircraft.stream().noneMatch(ua.zentix.incoming.entity.DebrisEntity::isChip), "expected nonempty B-2 wreckage containing only large pieces");
        StrikeGameTests.afterTest(h, () -> level.getEntitiesOfClass(ua.zentix.incoming.entity.DebrisEntity.class,
                h.getBounds().inflate(4)).forEach(net.minecraft.world.entity.Entity::discard));
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 90, batch = "blast_surface_depth", skyAccess = true)
    public static void savedDepthChangesTheNativeSyncedDisplayTransformation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(C.above(3));
        level.setBlock(top, Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
        SurfaceDamage.mark(level, top, level.getBlockState(top), 0.01);
        var small = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, new AABB(top).inflate(2),
                e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get()).getFirst();
        var smallTransform = displayTransform(small);
        var scale = smallTransform.getScale();
        h.assertTrue(small.getEntityData().getNonDefaultValues().stream().anyMatch(v -> v.value() instanceof org.joml.Vector3f vec
                && vec.distance(scale) < 1e-6), "depth scale is not in vanilla synced entity data");
        SurfaceDamage.mark(level, top, level.getBlockState(top), 0.9);
        var large = level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, new AABB(top).inflate(2),
                e -> e.getType() == ua.zentix.incoming.registry.ModEntities.SURFACE_DAMAGE.get()).getFirst();
        var largeTransform = displayTransform(large);
        h.assertTrue(scale.x() < largeTransform.getScale().x() / 5 && scale.z() < largeTransform.getScale().z() / 5,
                "0.01 and 0.9 depth have the same footprint");
        h.assertTrue(scale.y() < largeTransform.getScale().y() / 10, "chip height ignores saved depth");
        h.assertTrue(Math.abs(smallTransform.getTranslation().x() + scale.x() / 2 - 0.5) < 1e-6,
                "shallow fracture moved out of its damaged cell");
        h.assertTrue(small.isRemoved() && !large.save(new net.minecraft.nbt.CompoundTag()), "depth update lost cleanup/noSave");
        StrikeGameTests.afterTest(h, large::discard);
        h.succeed();
    }

    private static com.mojang.math.Transformation displayTransform(net.minecraft.world.entity.Display.BlockDisplay display) {
        var tag = display.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        return com.mojang.math.Transformation.EXTENDED_CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get("transformation")).getOrThrow();
    }

    /** Pure layout check over translated threshold hashes, including a reproduced failing origin. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "surface_layout", skyAccess = true)
    public static void nearbyPostsAreSampledForEveryThresholdHash(GameTestHelper h) {
        double tnt = WeaponType.MISSILE.spec().charge();
        for (int i = 0; i < 32; i++) {
            BlockPos c = new BlockPos(-10982969 + i * 997, 177, -15764973 + i * 613);
            Vec3 at = Vec3.atBottomCenterOf(c);
            Set<BlockPos> samples = BlastGameTests.sampledPosts(c, at, 22);
            h.assertTrue(!samples.contains(c.offset(0, 0, 9)), "regression origin no longer demonstrates a gap between rays");
            List<BlastGameTests.Post> bricks = BlastGameTests.layPosts(c, at, 12, tnt,
                    BlastDamage.breakRange(BlastModel.kpa(12), tnt), List.of(), b -> b < 0);
            List<BlastGameTests.Post> wood = BlastGameTests.layPosts(c, at, 4, tnt,
                    BlastDamage.breakRange(BlastModel.kpa(4), tnt), bricks, b -> b > 0);
            h.assertTrue(!bricks.isEmpty() && !wood.isEmpty(), "no test posts");
            h.assertTrue(wood.stream().anyMatch(p -> p.share() <= 0.65), "lost the strict 0.6 R inner plank post");
            h.assertTrue(wood.stream().anyMatch(p -> p.share() >= 1.3), "lost outer plank post");
            for (var p : wood) h.assertTrue(samples.contains(p.pos()), "unsampled plank " + p);
            for (var p : bricks) h.assertTrue(samples.contains(p.pos()), "unsampled brick " + p);
        }
        h.succeed();
    }
}
