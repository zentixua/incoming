package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.target.Target;

/** Empty-zone receipt fixture for authority/payment tests, never included in the shipped jar. */
public final class NuclearReadyFixture {
    private NuclearReadyFixture() {}

    public static UUID prepare(GameTestHelper h, ServerPlayer player, Loadout loadout, ServerActions.Aim aim) {
        UUID handle = UUID.randomUUID();
        boolean air = loadout.weapon().spec().warhead().chooseBurst() && loadout.nuke().airBurst();
        RuinPreparation.prepare(player.serverLevel(), handle, player.getUUID(), aim.point(), loadout.nuke().yieldKt(),
                aim.target() instanceof Target.Ground, air);
        StrikeGameTests.afterTest(h, () -> RuinPreparation.cancel(h.getLevel(), handle));
        ready(player.serverLevel(), handle, aim.point(), loadout.nuke().yieldKt(), air);
        return handle;
    }

    /** Complete only detached empty-zone receipts; this does not exercise real terrain preparation. */
    public static void ready(ServerLevel level, UUID handle, Vec3 resolvedTarget, double yieldKt, boolean airBurst) {
        try {
            var prep = NuclearWorld.get(level).prep();
            for (Object p : (List<?>) field(prep, "preps").get(prep)) {
                if (!handle.equals(field(p, "handle").get(p)) || field(p, "draining").getBoolean(p)) continue;
                var geometry = NuclearWarhead.geometry(level, resolvedTarget, yieldKt, airBurst, (float) IncomingConfig.SERVER.effectsScale());
                field(p, "geometry").set(p, geometry);
                field(p, "ruins").set(p, new RuinContext(level, geometry));
                field(p, "resolvedTarget").set(p, resolvedTarget);
                field(p, "order").set(p, new long[0]);
                field(p, "nextFar").setInt(p, 0);
                if (RuinPreparation.status(level, handle).stage() != RuinPreparation.Stage.READY)
                    throw new AssertionError("Empty-zone receipt fixture not ready: " + RuinPreparation.status(level, handle));
                return;
            }
            throw new AssertionError("Preparation absent: " + handle);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot install empty-zone receipt fixture", failure);
        }
    }

    private static Field field(Object value, String name) throws ReflectiveOperationException {
        Field result = value.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
