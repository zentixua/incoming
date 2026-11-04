package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinMatterCodecGameTests {
    private RuinMatterCodecGameTests() {}

    @GameTest(template = "pad", batch = "nuke_material_codec", timeoutTicks = 20, skyAccess = true)
    public static void rotationsKeepPackedLegacyCargoAndMaterialParity(GameTestHelper h) throws java.io.IOException {
        var level = h.getLevel();
        var ledger = new RuinMatter();
        var source = h.absolutePos(new BlockPos(8, 12, 8));
        var cargo = new CompoundTag(); cargo.putString("inventory", "seven diamonds");
        var pieces = new java.util.ArrayList<RuinMatter.Piece>();
        for (var state : java.util.List.of(Blocks.STONE_SLAB.defaultBlockState(), Blocks.BARREL.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState())) {
            var p = new RuinMatter.Piece(source, state, state.hasBlockEntity() ? cargo : null, new Vec3(1, -2, 3), level.getGameTime());
            p.scorch = 123; p.scorchEvent = 456; p.scorchTime = 789;
            p.windX = 4; p.windZ = -5; p.drag = 6; p.pulse = 7;
            pieces.add(p);
        }
        ledger.release(pieces);
        h.assertTrue(ledger.released() == 5 && ledger.pending() == 2, "Fine/coarse release partition changed");
        var legacy = ledger.save(new CompoundTag(), level.registryAccess());
        long owned = ledger.retainedBytes();
        var clock = WorkClock.counting(1);
        for (int i = 0; i < 64; i++) { clock.start(1000); ledger.work(level, clock); }
        h.assertTrue(ledger.save(new CompoundTag(), level.registryAccess()).equals(legacy),
                "Queue rotations changed physical time, cargo, scorch or material balances");
        h.assertTrue(ledger.retainedBytes() == owned, "Queue rotations grew retained ownership");
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
            var writer = new LedgerWriter(output);
            writer.compound(""); ledger.write(writer, level.registryAccess()); writer.end();
        }
        CompoundTag packed;
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            packed = NbtIo.read(input, NbtAccounter.create(1L << 20));
        }
        var rows = packed.getList("moving", Tag.TAG_COMPOUND);
        h.assertTrue(rows.size() == 2, "Packed queue lost coarse material");
        for (int i = 0; i < rows.size(); i++) {
            byte[] motion = rows.getCompound(i).getByteArray("motion");
            h.assertTrue(motion.length == 145 && motion[144] == 0, "Disk payload/schema flags changed");
        }
        for (var restored : java.util.List.of(RuinMatter.load(legacy, level.registryAccess()), RuinMatter.load(packed, level.registryAccess()))) {
            h.assertTrue(restored.save(new CompoundTag(), level.registryAccess()).equals(legacy),
                    "Packed/legacy restore differs in cargo, physical fields or aggregate/per-type balances");
            restored.release(pieces);
            h.assertTrue(restored.released() == 5, "Repeated handoff duplicated material");
            restored.unload(level);
        }
        ledger.unload(level);
        h.succeed();
    }
}
