package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.FragmentStrike;
import ua.zentix.incoming.warhead.GroundMaterial;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Осколки (S4): «Град» рвётся в метре над площадкой осью вниз — пояс осколков идёт над землёй во все стороны.
 * Манекены на двух кольцах, 8 и 16 м: попаданий на дальнем меньше — по 1/R² (манекен выше пояса на 8 м ловит его
 * долю больше, чем на 16 м, поэтому отношение — 2,8–5, а не ровно 4); за каменной стеной — ни одного. Стекло и кладка
 * в поясе: стёкла выбиты точками, на кладке — выбоины.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FragmentGameTests {
    private static final BlockPos GRASS = new BlockPos(32, 11, 32), CENTER = GRASS.above();

    private FragmentGameTests() {}

    @GameTest(template = "range", timeoutTicks = 400, batch = "fragments", skyAccess = true)
    public static void fragmentsByInverseSquareNotThroughWalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        List<ArmorStand> near = new ArrayList<>(), far = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            // кольца — от 30° до 285°: на 0° — манекен за стеной, на 320° — стекло и кладка
            double a = Math.toRadians(30 + 30 * i);
            near.add(stand(h, 8 * Math.cos(a), 8 * Math.sin(a)));
            far.add(stand(h, 16 * Math.cos(a + Math.toRadians(15)), 16 * Math.sin(a + Math.toRadians(15))));
        }
        // стена 5 × 4 камня в 5 м по +X, манекен за ней в 8 м
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 0; dy < 4; dy++) h.setBlock(CENTER.offset(5, dy, dz), Blocks.STONE);
        }
        ArmorStand hidden = stand(h, 8, 0);
        // на 320°, в 10 м: стенка 6 × 4 из стеклянных панелей, за ней в 12 м — кладка
        List<BlockPos> glass = new ArrayList<>(), bricks = new ArrayList<>();
        double g = Math.toRadians(320);
        for (int k = -3; k <= 2; k++) {
            for (int dy = 0; dy < 4; dy++) {
                BlockPos p = CENTER.offset((int) Math.round(10 * Math.cos(g) - k * Math.sin(g)), dy, (int) Math.round(10 * Math.sin(g) + k * Math.cos(g)));
                h.setBlock(p, Blocks.GLASS);
                glass.add(h.absolutePos(p));
                BlockPos q = CENTER.offset((int) Math.round(12 * Math.cos(g) - k * Math.sin(g)), dy, (int) Math.round(12 * Math.sin(g) + k * Math.cos(g)));
                h.setBlock(q, Blocks.STONE_BRICKS);
                bricks.add(h.absolutePos(q));
            }
        }
        FragmentStrike strike = Warheads.testFragments(level, WeaponType.ROCKET, at, new Vec3(0, -1, 0), 1234);
        h.succeedWhen(() -> {
            h.assertTrue(strike.done(), "осколки не кончились");
            double n = 0, f = 0;
            for (ArmorStand s : near) n += strike.hits(s.getUUID());
            for (ArmorStand s : far) f += strike.hits(s.getUUID());
            n /= near.size();
            f /= far.size();
            int broken = 0, cracked = 0;
            for (BlockPos p : glass) if (level.getBlockState(p).isAir()) broken++;
            for (BlockPos p : bricks) if (level.getBlockState(p).is(Blocks.CRACKED_STONE_BRICKS)) cracked++;
            String got = String.format(Locale.ROOT, "попаданий на манекен: 8 м %.1f, 16 м %.1f (×%.2f), за стеной %.0f; стёкол %d/%d, выбоин %d/%d",
                    n, f, n / Math.max(f, 1e-9), strike.hits(hidden.getUUID()), broken, glass.size(), cracked, bricks.size());
            Incoming.LOG.info("FRAGBENCH {}", got);
            h.assertTrue(f >= 1, got);
            h.assertTrue(n / f >= 2.8 && n / f <= 5, got);
            h.assertTrue(strike.hits(hidden.getUUID()) == 0, got);
            h.assertTrue(broken > 0, got);
            h.assertTrue(cracked > 0, got);
        });
    }

    /**
     * Выбоина меняет вид, а не материал: у каждого треснувшего блока {@link FragmentStrike#CRACKED} — своя запись
     * пробития (без неё блок шёл бы прочим твёрдым, {@code OTHER}, и бомба проходила бы его на другую глубину) и та же,
     * что у целого; тот же ответ волне, тот же грунт воронки и те же теги мода.
     */
    @GameTest(template = "pad", batch = "fragments")
    public static void crackedKeepsMaterial(GameTestHelper h) {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Block, Block> e : FragmentStrike.CRACKED.entrySet()) {
            BlockState whole = e.getKey().defaultBlockState(), cracked = e.getValue().defaultBlockState();
            String name = BuiltInRegistries.BLOCK.getKey(e.getValue()).toString();
            ModDataMaps.Penetrability pw = ModDataMaps.Penetrability.find(whole), pc = ModDataMaps.Penetrability.find(cracked);
            if (pw == null || pc == null || !pw.equals(pc)) wrong.add(name + ": пробитие " + pc + " у целого " + pw);
            if (!BlockResponse.of(whole).equals(BlockResponse.of(cracked)))
                wrong.add(name + ": волна " + BlockResponse.of(cracked) + " у целого " + BlockResponse.of(whole));
            if (GroundMaterial.of(whole) != GroundMaterial.of(cracked))
                wrong.add(name + ": грунт " + GroundMaterial.of(cracked) + " у целого " + GroundMaterial.of(whole));
            BuiltInRegistries.BLOCK.getTagNames().filter(t -> t.location().getNamespace().equals(Incoming.MOD_ID))
                    .filter(t -> whole.is(t) != cracked.is(t))
                    .forEach(t -> wrong.add(name + ": тег " + t.location() + (cracked.is(t) ? " есть" : " нет") + ", у целого — наоборот"));
        }
        h.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        h.succeed();
    }

    private static ArmorStand stand(GameTestHelper h, double dx, double dz) {
        BlockPos p = CENTER.offset((int) Math.round(dx), 0, (int) Math.round(dz));
        ArmorStand s = h.spawn(EntityType.ARMOR_STAND, p);
        s.setInvulnerable(true);
        return s;
    }
}
