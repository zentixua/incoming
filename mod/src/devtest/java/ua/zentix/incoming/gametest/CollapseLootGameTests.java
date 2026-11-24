package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Loot containers removed by a collapse ({@code StrikeCollapse.remove}, called on one block): an unopened chest's loot
 * travels unrolled on its dropped item and comes back when the chest is placed; a decorated pot, which restores no
 * {@code container_loot}, rolls its loot into loose items as a hand break does; the loot chest's drops pass through
 * NeoForge's {@link BlockDropsEvent} as every other collapse drop does.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CollapseLootGameTests {
    private CollapseLootGameTests() {}

    private static final ResourceKey<LootTable> POT = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.withDefaultNamespace("pots/trial_chambers/corridor"));
    private static final ResourceKey<LootTable> CHEST = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.withDefaultNamespace("chests/simple_dungeon"));
    private static final List<BlockPos> DROPS_AT = new ArrayList<>();
    private static boolean listening;

    private static boolean remove(ServerLevel level, BlockPos p) throws ReflectiveOperationException {
        Method m = Class.forName("ua.zentix.incoming.warhead.StrikeCollapse")
                .getDeclaredMethod("remove", ServerLevel.class, BlockPos.class, BlockState.class, BlockState.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, level, p, level.getBlockState(p), Blocks.AIR.defaultBlockState());
    }

    private static List<ItemStack> takeItems(ServerLevel level, BlockPos p) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(2))) {
            out.add(e.getItem().copy());
            e.discard();
        }
        return out;
    }

    /** The loot table a block placed from {@code stack} at {@code at} would roll; null: none. */
    private static ResourceKey<LootTable> placedTable(GameTestHelper h, BlockPos at, Block block, ItemStack stack) {
        h.setBlock(at, block);
        BlockEntity be = h.getBlockEntity(at);
        be.applyComponentsFromItemStack(stack);
        return ((RandomizableContainer) be).getLootTable();
    }

    @GameTest(template = "pad", batch = "collapse")
    public static void potLootSurvives(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        BlockPos rel = new BlockPos(2, 2, 2), abs = h.absolutePos(rel);
        h.setBlock(rel, Blocks.DECORATED_POT);
        ((RandomizableContainer) h.getBlockEntity(rel)).setLootTable(POT, 7L);
        remove(level, abs);
        List<ItemStack> items = takeItems(level, abs);
        int loose = 0;
        ResourceKey<LootTable> carried = null;
        for (ItemStack s : items) {
            if (s.is(Blocks.DECORATED_POT.asItem())) carried = placedTable(h, new BlockPos(4, 2, 2), Blocks.DECORATED_POT, s);
            else if (!s.is(Items.BRICK) && !s.is(ItemTags.DECORATED_POT_SHERDS)) loose++;
        }
        h.assertTrue(loose > 0 || POT.equals(carried), "trial-chamber pot loot lost in a collapse: " + items.size()
                + " drop(s) " + items + ", " + loose + " loose loot stack(s), placed pot rolls " + carried);
        h.succeed();
    }

    @GameTest(template = "pad", batch = "collapse")
    public static void chestLootRoundTrip(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        BlockPos rel = new BlockPos(2, 2, 2), abs = h.absolutePos(rel);
        h.setBlock(rel, Blocks.CHEST);
        ((RandomizableContainer) h.getBlockEntity(rel)).setLootTable(CHEST, 42L);
        remove(level, abs);
        ResourceKey<LootTable> carried = null;
        long seed = 0;
        for (ItemStack s : takeItems(level, abs)) {
            if (!s.is(Blocks.CHEST.asItem())) continue;
            carried = placedTable(h, new BlockPos(4, 2, 2), Blocks.CHEST, s);
            seed = ((RandomizableContainer) h.getBlockEntity(new BlockPos(4, 2, 2))).getLootTableSeed();
        }
        h.assertTrue(CHEST.equals(carried) && seed == 42L, "placed chest rolls " + carried + " seed " + seed);
        h.succeed();
    }

    @GameTest(template = "pad", batch = "collapse")
    public static void lootChestFiresDropsEvent(GameTestHelper h) throws ReflectiveOperationException {
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener((BlockDropsEvent e) -> DROPS_AT.add(e.getPos().immutable()));
        }
        ServerLevel level = h.getLevel();
        BlockPos plain = new BlockPos(2, 2, 2), loot = new BlockPos(4, 2, 2);
        h.setBlock(plain, Blocks.CHEST);
        h.setBlock(loot, Blocks.CHEST);
        ((RandomizableContainer) h.getBlockEntity(loot)).setLootTable(CHEST, 42L);
        DROPS_AT.clear();
        remove(level, h.absolutePos(plain));
        remove(level, h.absolutePos(loot));
        takeItems(level, h.absolutePos(plain));
        takeItems(level, h.absolutePos(loot));
        boolean plainSeen = DROPS_AT.contains(h.absolutePos(plain)), lootSeen = DROPS_AT.contains(h.absolutePos(loot));
        h.assertTrue(plainSeen && lootSeen, "BlockDropsEvent: plain chest " + plainSeen + ", loot chest " + lootSeen);
        h.succeed();
    }
}
