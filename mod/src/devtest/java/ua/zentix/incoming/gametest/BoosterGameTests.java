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

/** Очистка ускорителя: настоящая сущность, момент удаления и позднее начало отслеживания. */
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
        boolean[] faded = {false}, landed = {false};
        h.succeedWhen(() -> {
            if (e.landed()) landed[0] = true;
            if (e.visibility(0) > 0 && e.visibility(0) < 1) faded[0] = true;
            if (e.remainingTicks() <= 1) h.assertTrue(e.visibility(0) == 0 && e.visibility(0.99f) == 0, "свет в последнем кадре");
            h.assertTrue(e.isRemoved(), "ускоритель ещё лежит");
            h.assertTrue(landed[0] && faded[0], "падение или плавное исчезновение пропущено");
            h.assertTrue(e.age() < BoosterLifetime.MAX_AGE, "общий предел вместо очистки после падения");
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
        // Начальные данные нового наблюдателя — те же часы, а не age=0 нового объекта.
        SpentBoosterEntity late = ModEntities.SPENT_BOOSTER.get().create(h.getLevel());
        late.getEntityData().assignValues(e.getEntityData().getNonDefaultValues());
        h.assertTrue(late.age() == e.age() && late.visibility(0) == 0, "поздний наблюдатель заново видит горячее сопло");
        h.succeedWhen(() -> {
            h.assertTrue(e.visibility(0) == 0, "свет перед общим пределом");
            h.assertTrue(e.isRemoved(), "общий предел не удалил ускоритель");
            h.assertFalse(e.landed(), "вместо общего предела сработало падение");
        });
    }
}
