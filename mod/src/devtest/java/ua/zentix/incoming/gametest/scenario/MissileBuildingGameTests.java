package ua.zentix.incoming.gametest.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.stress.StressDirector;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(Incoming.MOD_ID)
public final class MissileBuildingGameTests {
    /** Reserve sites beyond the weapon × target × disturbance grid. */
    private static final int FIRST_CELL = 12000;
    private static final int HALF_BOX = 6;
    private static final int ROOF_Y = 2 * HALF_BOX;

    private MissileBuildingGameTests() {}

    @GameTestGenerator
    public static List<TestFunction> buildingApproaches() {
        return Arrays.stream(Aim.values()).map(aim -> new TestFunction("missile_building",
                "missilebuilding." + aim.name().toLowerCase(Locale.ROOT), "incoming:pad", Rotation.NONE,
                800, 0, true, false, 1, 1, true, h -> new Approach(h, aim).start())).toList();
    }

    private enum Aim {
        // Final-leg lengths in blocks on the recorded 2400-block routes.
        INSIDE(new Vec3(0.45, 3, 1.15), 1, 1687.150066),
        INSIDE_CENTER(new Vec3(0, 2, 0), -1, 1686),
        NORTH_FACE(new Vec3(0, 5.5, -6.5), 1, 1679.5),
        ROOF(new Vec3(0, 13, 0), 1, 1686),
        OPEN(new Vec3(0, 0, 0), 1, 1686),
        TOP_FLOOR(new Vec3(0, ROOF_Y - 0.5, 0), 1, 1686);

        final Vec3 offset;
        final double side;
        final double entry;

        Aim(Vec3 offset, double side, double entry) {
            this.offset = offset;
            this.side = side;
            this.entry = entry;
        }

        boolean inside() {
            return this == INSIDE || this == INSIDE_CENTER || this == TOP_FLOOR;
        }
    }

    private static final class Approach {
        private final GameTestHelper h;
        private final ServerLevel level;
        private final Aim kind;
        private final ScenarioSite site;
        private final Vec3 center;
        private final Vec3 aim;
        private final UUID id;
        private boolean launched;
        private int terminalAge = -1;
        private Vec3 terminalAt;
        private Vec3 impact;
        private double impactRange;
        private int impactAge;
        private final Consumer<ExplosionEvent.Start> listener = this::onExplosion;

        Approach(GameTestHelper h, Aim kind) {
            this.h = h;
            this.level = h.getLevel();
            this.kind = kind;
            this.site = new ScenarioSite(level, FIRST_CELL + kind.ordinal());
            this.center = site.origin.add(-8, 0, -3);
            this.aim = center.add(kind.offset);
            this.id = new UUID(0x5ce7a41000000002L, kind.ordinal());
        }

        void start() {
            InstantChunks.acquire();
            NeoForge.EVENT_BUS.addListener(listener);
            StrikeGameTests.afterTest(h, this::cleanup);
            site.zone(center, ScenarioSite.TARGET_ZONE);
            site.load();
            if (kind != Aim.OPEN) buildBox();
            h.onEachTick(this::tick);
        }

        private void buildBox() {
            BlockPos base = BlockPos.containing(center);
            for (int x = -HALF_BOX; x <= HALF_BOX; x++) {
                for (int z = -HALF_BOX; z <= HALF_BOX; z++) {
                    level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    for (int y = 0; y <= ROOF_Y; y++) {
                        boolean shell = Math.abs(x) == HALF_BOX || Math.abs(z) == HALF_BOX || y == 0 || y == ROOF_Y;
                        Block block = shell ? Blocks.BRICKS : y == 4 || y == 8 ? Blocks.OAK_PLANKS : Blocks.AIR;
                        if (z == -HALF_BOX && Math.abs(x) <= 2 && (y == 1 || y == 2)) block = Blocks.GLASS_PANE;
                        if (block != Blocks.AIR) level.setBlock(base.offset(x, y, z), block.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }

        private void launch() {
            Vec3 from = center.add(0, 0, -1536);
            Vec3 heading = aim.subtract(from).multiply(1, 0, 1).normalize();
            StrikeProjectile missile = ModEntities.CRUISE_MISSILE.get().create(level);
            if (missile == null) throw new IllegalStateException("Could not create the cruise missile");
            missile.seed(0);
            missile.setUUID(id);
            missile.launch(from, new Target.Point(aim), aim, id);
            missile.setRoute(Route.plan(missile.position(), aim, heading, 2400, kind.entry, kind.side));
            VirtualFlights.launch(level, missile);
            launched = true;
        }

        private void tick() {
            if (!launched) {
                if (site.ticking()) launch();
                return;
            }
            if (impact != null) {
                Vec3 at = impact.subtract(center);
                Incoming.LOG.info("Missile building {}: terminal tick {} at {} relative to aim, impact tick {} at {} relative to box, range left {}",
                        kind, terminalAge, terminalAt, impactAge, at, impactRange);
                h.assertTrue(impactRange > 0, "Missile ran out of range");
                if (kind.inside()) {
                    double edge = HALF_BOX + 0.5;
                    h.assertTrue(Math.abs(at.x) <= edge && Math.abs(at.z) <= edge
                                    && Math.abs(at.y - (ROOF_Y + 1)) < 1.0e-6,
                            "Missile aimed inside missed the top surface of the roof: " + at);
                } else {
                    h.assertTrue(impact.distanceTo(aim) <= 1, "Missile missed its exposed aim point: " + impact.subtract(aim));
                }
                h.succeed();
                return;
            }
            StrikeProjectile live = live();
            if (live == null) return;
            if (terminalAge < 0 && live.flightPhase() == FlightPhase.TERMINAL) {
                terminalAge = live.age();
                terminalAt = live.position().subtract(aim);
            }
            h.assertTrue(live.position().z <= center.z + HALF_BOX + 0.5,
                    "Missile overshot the box on its first pass at tick " + live.age() + ": " + live.position().subtract(center));
        }

        private StrikeProjectile live() {
            if (level.getEntity(id) instanceof StrikeProjectile p && !p.isRemoved()) return p;
            return VirtualFlights.get(level).flights().stream().filter(p -> id.equals(p.getUUID())).findFirst().orElse(null);
        }

        private void onExplosion(ExplosionEvent.Start event) {
            if (event.getLevel() == level && impact == null) {
                StrikeProjectile p = StressDirector.blastProjectile(event.getExplosion());
                if (p != null && id.equals(p.getUUID())) {
                    impact = event.getExplosion().center();
                    impactAge = p.age();
                    impactRange = p.rangeLeft();
                }
            }
        }

        private void cleanup() {
            NeoForge.EVENT_BUS.unregister(listener);
            StrikeProjectile live = live();
            if (live != null) live.discard();
            site.release();
            InstantChunks.release();
        }
    }
}
