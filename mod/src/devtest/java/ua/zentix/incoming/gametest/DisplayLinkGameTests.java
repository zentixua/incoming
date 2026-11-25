package ua.zentix.incoming.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.create.DisplaySources;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Create Display Link sources ({@code compat/create}) through Create itself: a real Display Link against the SAM or the
 * launcher, its sources found by Create's own {@link DisplaySource#getAll}, its line sent to a sign by Create's own
 * {@link DisplayLinkBlockEntity#updateGatheredData} (what a redstone pulse or the passive refresh runs). Create is in the
 * GameTest runtime (the pack's jar); without it the bridge never loads and these tests have nothing to read.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DisplayLinkGameTests {
    private static final BlockPos PAD = new BlockPos(16, 4, 8);

    private DisplayLinkGameTests() {}

    /**
     * Two SAMs beside a Shahed's path, one owned by a stranger, one by the Shahed's owner. Each board reads "Clear" before
     * the Shahed; while the stranger's SAM tracks it, its board reads "ALERT" (its alarm is on) and the owner's stays
     * "Clear", and the stranger's link shows the alert by its own refresh within one radar sweep; three sweeps after the
     * Shahed is gone both read "Clear".
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "display_link_sam", skyAccess = true)
    public static void samBoardFollowsTheAlarm(GameTestHelper h) {
        if (!ModList.get().isLoaded("create")) {
            h.succeed();
            return;
        }
        SamGameTests.air(h, 1, 1);
        UUID shooter = UUID.randomUUID();
        SamBlockEntity hostile = SamGameTests.sam(h, SamGameTests.SAM, UUID.randomUUID(), 0);
        SamBlockEntity friendly = SamGameTests.sam(h, SamGameTests.SAM.east(6), shooter, 0);
        Board hostileLink = Board.at(h, SamGameTests.SAM, Direction.EAST);
        Board friendlyLink = Board.at(h, SamGameTests.SAM.east(6), Direction.EAST);
        String on = english(h, "incoming.display_source.sam_alert.on");
        String off = english(h, "incoming.display_source.sam_alert.off");
        h.assertFalse(on.equals(off), "alert and clear read the same: " + on);
        DroneEntity[] drone = {null};
        long[] seen = {0};
        h.startSequence()
                .thenExecute(() -> {
                    h.assertTrue(hostile.hostile() == 0, "a SAM tracks before the Shahed");
                    h.assertValueEqual(hostileLink.read(Source.SAM_ALERT), off, "stranger's board before the Shahed");
                    h.assertValueEqual(friendlyLink.read(Source.SAM_ALERT), off, "owner's board before the Shahed");
                    drone[0] = SamGameTests.drone(h, 16, shooter);
                })
                .thenWaitUntil(() -> h.assertTrue(hostile.hostile() > 0, "the stranger's SAM does not track the Shahed"))
                .thenExecute(() -> seen[0] = h.getLevel().getGameTime())
                // the link's own passive refresh, no forced update: within one sweep of the alarm
                .thenWaitUntil(() -> h.assertValueEqual(hostileLink.shown(), on, "stranger's board by its own refresh"))
                .thenExecute(() -> {
                    long late = h.getLevel().getGameTime() - seen[0];
                    h.assertTrue(late <= SamBlockEntity.SWEEP, "the board showed the alert " + late + " ticks after the alarm");
                    h.assertTrue(hostile.alarm(), "tracking without the alarm");
                    h.assertValueEqual(hostileLink.read(Source.SAM_ALERT), on, "stranger's board while it tracks");
                    h.assertTrue(friendly.hostile() == 0, "the owner's SAM tracks its own Shahed");
                    h.assertValueEqual(friendlyLink.read(Source.SAM_ALERT), off, "owner's board while the Shahed flies");
                    drone[0].discard();
                })
                .thenIdle(3 * SamBlockEntity.SWEEP)
                .thenExecute(() -> {
                    h.assertTrue(!hostile.alarm() && hostile.hostile() == 0, "the SAM still tracks a removed Shahed");
                    h.assertValueEqual(hostileLink.read(Source.SAM_ALERT), off, "stranger's board after the Shahed");
                })
                .thenSucceed();
    }

    /**
     * One launcher with both of its sources. Empty and without a mission: 0 rounds, not ready. Two Shaheds and a Grad pack
     * with a one-Shahed mission: the pack's rockets counted with the Shaheds, ready. The same stock with a three-Shahed
     * mission: not ready, exactly where a redstone signal would be refused (stock short). Reading changes nothing; a
     * label goes before the value.
     */
    @GameTest(template = "runway", timeoutTicks = 200, batch = "display_link_launcher", skyAccess = true)
    public static void launcherBoardsShowStockAndReadiness(GameTestHelper h) {
        if (!ModList.get().isLoaded("create")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, PAD, UUID.randomUUID());
        Board link = Board.at(h, PAD, Direction.WEST);
        List<String> offered = Board.offered(level, be.getBlockPos());
        h.assertTrue(offered.containsAll(List.of("incoming:launcher_munitions", "incoming:launcher_ready")) && !offered.contains("incoming:sam_alert"),
                "Create offers the launcher " + offered);
        h.assertValueEqual(Board.offered(level, h.absolutePos(PAD.east(3))), List.of(), "Create's sources at air");
        String ready = english(h, "incoming.display_source.launcher_ready.on");
        String notReady = english(h, "incoming.display_source.launcher_ready.off");
        h.assertFalse(ready.equals(notReady), "ready and not ready read the same: " + ready);
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1000);
        Mission one = new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE);
        Mission three = new Mission(WeaponType.DRONE, 3, 0, new Target.Point(far), far, Waypoints.NONE);
        int pack = WeaponType.ROCKET.spec().munition().rounds();
        h.assertTrue(pack > 1, "a Grad pack holds " + pack + " rockets");

        h.assertValueEqual(link.read(Source.LAUNCHER_MUNITIONS), "0", "empty launcher's rounds");
        h.assertValueEqual(link.read(Source.LAUNCHER_READY), notReady, "launcher without a mission");

        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.GRAD_ROCKETS.get(), 1));
        be.setMission(one);
        h.assertValueEqual(link.read(Source.LAUNCHER_MUNITIONS), Integer.toString(2 + pack), "rounds of two Shaheds and a Grad pack");
        h.assertValueEqual(link.read(Source.LAUNCHER_READY), ready, "launcher with stock for its mission");

        be.setMission(three);
        h.assertValueEqual(link.read(Source.LAUNCHER_READY), notReady, "launcher short of stock for its mission");
        h.assertValueEqual(be.mission(), three, "the mission after the board's reads");
        h.assertValueEqual(link.read(Source.LAUNCHER_MUNITIONS), Integer.toString(2 + pack), "rounds after the reads");
        // a player's label goes before the value, as with Create's own sources
        link.label("Stock");
        h.assertValueEqual(link.read(Source.LAUNCHER_MUNITIONS), "Stock " + (2 + pack), "labelled rounds");
        h.succeed();
    }

    /**
     * {@code key}'s English text from our {@code en_us.json}, what the guide promises; the sign shows the server's
     * translation, so a missing key (the raw key on the sign) or a different text fails.
     */
    private static String english(GameTestHelper h, String key) {
        try (var in = DisplayLinkGameTests.class.getResourceAsStream("/assets/incoming/lang/en_us.json")) {
            h.assertTrue(in != null, "no en_us.json");
            JsonObject strings = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            h.assertTrue(strings.has(key), "en_us.json has no " + key);
            return strings.get(key).getAsString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Our sources, by registry name: the test methods name no Create type (GameTest reflects over them). */
    private enum Source {
        SAM_ALERT, LAUNCHER_MUNITIONS, LAUNCHER_READY
    }

    /**
     * A Display Link with a sign: every Create type lives here, loaded only after the test found Create, so this class
     * loads without Create too.
     */
    private static final class Board {
        private final GameTestHelper h;
        private final DisplayLinkBlockEntity link;

        private Board(GameTestHelper h, DisplayLinkBlockEntity link) {
            this.h = h;
            this.link = link;
        }

        /**
         * A Display Link on the {@code face} side of {@code source}, facing away from it (Create's source is the block it
         * is attached to), and an oak sign two blocks farther as its target; both are removed after the test.
         */
        static Board at(GameTestHelper h, BlockPos source, Direction face) {
            BlockPos at = source.relative(face), sign = source.relative(face, 2);
            h.setBlock(at, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "display_link")).defaultBlockState()
                    .setValue(BlockStateProperties.FACING, face));
            h.setBlock(sign, Blocks.OAK_SIGN);
            StrikeGameTests.afterTest(h, () -> {
                h.getLevel().setBlock(h.absolutePos(at), Blocks.AIR.defaultBlockState(), 3);
                h.getLevel().setBlock(h.absolutePos(sign), Blocks.AIR.defaultBlockState(), 3);
            });
            DisplayLinkBlockEntity link = h.getBlockEntity(at);
            h.assertValueEqual(link.getSourcePosition(), h.absolutePos(source), "the link's source");
            link.target(h.absolutePos(sign));
            return new Board(h, link);
        }

        /** Registry names of the sources Create offers a link attached to {@code pos} ({@link DisplaySource#getAll}). */
        static List<String> offered(ServerLevel level, BlockPos pos) {
            return DisplaySource.getAll(level, pos).stream().map(s -> String.valueOf(CreateBuiltInRegistries.DISPLAY_SOURCE.getKey(s))).toList();
        }

        /** {@code which}'s line on the sign, sent by Create's own update. */
        String read(Source which) {
            DisplaySource source = switch (which) {
                case SAM_ALERT -> DisplaySources.SAM_ALERT.get();
                case LAUNCHER_MUNITIONS -> DisplaySources.LAUNCHER_MUNITIONS.get();
                case LAUNCHER_READY -> DisplaySources.LAUNCHER_READY.get();
            };
            h.assertTrue(DisplaySource.getAll(h.getLevel(), link.getSourcePosition()).contains(source), "Create does not offer " + which);
            link.activeSource = source;
            link.updateGatheredData();
            h.assertValueEqual(link.activeSource, source, "Create dropped the source");
            return shown();
        }

        /** The label a player types in the link's screen (Create's "Attached Label"). */
        void label(String text) {
            CompoundTag config = link.getSourceConfig().copy();
            config.putString("Label", text);
            link.setSourceConfig(config);
        }

        /** The sign's first line as it stands. */
        String shown() {
            SignBlockEntity sign = (SignBlockEntity) h.getLevel().getBlockEntity(link.getTargetPosition());
            h.assertTrue(sign != null, "no sign at the link's target");
            return sign.getFrontText().getMessage(0, false).getString();
        }
    }
}
