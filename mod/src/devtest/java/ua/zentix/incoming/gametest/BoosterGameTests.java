package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BoosterLifetime;
import ua.zentix.incoming.entity.SpentBoosterEntity;
import ua.zentix.incoming.registry.ModEntities;

/** Accelerator cleanup: present entity, timing of removal, and late start of tracking. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BoosterGameTests {
    private BoosterGameTests() {}

    @GameTest(template = "range", timeoutTicks = 150, batch = "debris", skyAccess = true)
    public static void landedBoosterFadesBeforeRemoval(GameTestHelper h) {
        SpentBoosterEntity e = ModEntities.SPENT_BOOSTER.get().create(h.getLevel());
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(32, 12, 32)));
        e.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(e);
        boolean[] faded = {false}, landed = {false}, lostContact = {false};
        h.succeedWhen(() -> {
            if (landed[0] && !e.landed()) lostContact[0] = true;
            if (e.landed()) landed[0] = true;
            if (e.visibility(0) > 0 && e.visibility(0) < 1) faded[0] = true;
            if (e.remainingTicks() <= 1) h.assertTrue(e.visibility(0) == 0 && e.visibility(0.99f) == 0, "light in the last frame");
            h.assertTrue(e.isRemoved(), "the accelerator is still there");
            h.assertTrue(landed[0] && faded[0], "drop or fade missed");
            h.assertFalse(lostContact[0], "onGround reset at rest: pose and nozzle flashing");
            h.assertTrue(e.age() < BoosterLifetime.MAX_AGE, "general limit instead of clearing after a fall");
        });
    }

    @GameTest(template = "range", timeoutTicks = 10, batch = "debris", skyAccess = true)
    public static void airborneBoosterCapAndLateTrackingKeepFade(GameTestHelper h) {
        SpentBoosterEntity e = ModEntities.SPENT_BOOSTER.get().create(h.getLevel());
        CompoundTag tag = new CompoundTag();
        tag.putInt("age", BoosterLifetime.MAX_AGE - 2);
        e.load(tag);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(32, 30, 32)));
        e.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(e);
        // The initial data of the new observer is the same clock, and not age=0 of the new object.
        SpentBoosterEntity late = ModEntities.SPENT_BOOSTER.get().create(h.getLevel());
        late.getEntityData().assignValues(e.getEntityData().getNonDefaultValues());
        h.assertTrue(late.age() == e.age() && late.visibility(0) == 0, "the late observer re-sees the hot nozzle");
        h.succeedWhen(() -> {
            h.assertTrue(e.visibility(0) == 0, "light before the general limit");
            h.assertTrue(e.isRemoved(), "general limit did not remove accelerator");
            h.assertFalse(e.landed(), "instead of the general limit, the fall worked");
        });
    }

    @GameTest(template = "range", timeoutTicks = 80, batch = "debris", skyAccess = true)
    public static void boosterBelowWorldAlsoFades(GameTestHelper h) {
        SpentBoosterEntity e = ModEntities.SPENT_BOOSTER.get().create(h.getLevel());
        BlockPos at = h.absolutePos(new BlockPos(32, 12, 32));
        e.moveTo(at.getX() + 0.5, h.getLevel().getMinBuildHeight() - 40, at.getZ() + 0.5, 0, 0);
        h.getLevel().addFreshEntity(e);
        boolean[] faded = {false};
        h.succeedWhen(() -> {
            if (e.visibility(0) > 0 && e.visibility(0) < 1) faded[0] = true;
            h.assertTrue(e.isRemoved(), "the accelerator is still flying under the world");
            h.assertTrue(faded[0] && e.visibility(0) == 0, "removal under the world cut off the glow");
            h.assertTrue(e.age() < BoosterLifetime.LIE_TICKS, "the cleansing under the world has not set a deadline");
        });
    }
}
