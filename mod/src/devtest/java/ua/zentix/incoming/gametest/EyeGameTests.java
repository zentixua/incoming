package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;

import java.util.UUID;

/** Ожог сетчатки вспышкой ({@link FlashEyes}). */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EyeGameTests {
    private EyeGameTests() {}

    private static final BlockPos CENTER = NuclearGameTests.CENTER;

    /**
     * 15 кт: у смотревшего на шар — пятно в середине взгляда размером с изображение шара; шар правее и выше — пятно
     * правее и выше; шар за спиной или дальше 60° от взгляда — нет. В 40 км (1 блок = 1 км) — между дальностями ожога
     * днём (~31 км) и ночью (~53 км): днём нет, у привыкшего к темноте — есть.
     */
    @GameTest(template = "range", batch = "nuke_eyes", skyAccess = true)
    public static void retinaBurnWhereFireballWas(GameTestHelper h) {
        FakePlayer p = player(h, 0, 0);
        Vec3 eye = p.getEyePosition();
        Detonation near = detonation(h, eye.add(0, 0, 40), 0.1f);
        RetinaScars.Scar s = FlashEyes.scar(near, p, 0);
        h.assertTrue(s != null, "смотрел на шар в 400 м — ожога сетчатки нет");
        h.assertTrue(Math.abs(s.x()) < 1e-4 && Math.abs(s.y()) < 1e-4, "пятно не в середине взгляда: " + s);
        h.assertTrue(Math.abs(s.r() - near.fireballRadius() / 40) < 1e-4, "пятно не размером с изображение шара: " + s);
        // справа от взгляда на юг (+Z) — запад (−X)
        RetinaScars.Scar side = FlashEyes.scar(detonation(h, eye.add(-20, 10, 40), 0.1f), p, 0);
        h.assertTrue(side != null && Math.abs(side.x() - 0.5) < 1e-3 && Math.abs(side.y() - 0.25) < 1e-3, "шар правее и выше, а пятно: " + side);
        h.assertTrue(FlashEyes.scar(detonation(h, eye.add(-80, 0, 40), 0.1f), p, 0) == null, "шар в 63° от взгляда обжёг сетчатку");
        h.assertTrue(FlashEyes.scar(detonation(h, eye.add(0, 0, -40), 0.1f), p, 0) == null, "шар за спиной обжёг сетчатку");
        Detonation far = detonation(h, eye.add(0, 0, 40), 0.001f);
        h.assertTrue(FlashEyes.scar(far, p, 0) == null, "днём в 40 км обжёг сетчатку");
        h.assertTrue(FlashEyes.scar(far, p, 1) != null, "ночью в 40 км не обжёг сетчатку");
        h.succeed();
    }

    /**
     * Через свет по одной сущности, как у импульса: смотревший вверх на шар в 4 км (ожогов кожи и дозы там нет)
     * получает пятно, вторая вспышка — второе, больше {@link RetinaScars#MAX} не помнится; в творческом режиме — нет.
     */
    @GameTest(template = "range", batch = "nuke_eyes_flash", skyAccess = true)
    public static void flashAddsScarsToPlayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer p = player(h, 0, -45);
        Detonation d = detonation(h, p.getEyePosition().add(p.getViewVector(1).scale(40)), 0.01f);
        h.assertTrue(NuclearWarhead.sees(level, d, p), "шар над площадкой не виден — проверка ни о чём");
        NuclearWarhead.expose(level, d, p, null);
        h.assertValueEqual(FlashEyes.scars(p).scars().size(), 1, "пятен после вспышки");
        NuclearWarhead.expose(level, d, p, null);
        h.assertValueEqual(FlashEyes.scars(p).scars().size(), 2, "пятен после второй вспышки");
        for (int i = 0; i < 2 * RetinaScars.MAX; i++) NuclearWarhead.expose(level, d, p, null);
        h.assertValueEqual(FlashEyes.scars(p).scars().size(), RetinaScars.MAX, "пятен после многих вспышек");
        h.assertTrue(p.isAlive() && p.getRemainingFireTicks() <= 0, "в 4 км от 15 кт обожгло кожу — проверка не о глазах");
        FakePlayer creative = player(h, 0, -45);
        creative.setGameMode(GameType.CREATIVE);
        NuclearWarhead.expose(level, d, creative, null);
        h.assertTrue(FlashEyes.scars(creative).isEmpty(), "в творческом режиме обжёг сетчатку");
        h.succeed();
    }

    /**
     * Хребет в 100 блоках от игрока, шар за 2 км за ним: редкие точки тени по карте высот (через 130 блоков) хребет
     * перешагнули бы, плотная проверка у глаза его видит. Хребет — за площадкой к +X, своими блоками (чанки грузит тест),
     * после проверки убирается.
     */
    @GameTest(template = "range", batch = "nuke_eyes_terrain", skyAccess = true)
    public static void ridgeNearFarPlayerHidesFireball(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "incoming_ridge"));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 13, 32)));
        p.moveTo(at.x, at.y, at.z, -90, 0);
        // вверх на 1 блок на каждые 2 вбок: над стеной из барьеров вокруг площадки
        Detonation d = detonation(h, p.getEyePosition().add(2000, 1000, 0), 1);
        BlockPos from = h.absolutePos(new BlockPos(100, 13, 24)), to = h.absolutePos(new BlockPos(107, 80, 40));
        // готовые чанки от игрока до хребта: плотная проверка идёт до первого неготового
        BlockPos start = h.absolutePos(new BlockPos(0, 13, 24));
        for (int cx = Math.min(start.getX(), to.getX()) >> 4; cx <= Math.max(start.getX(), to.getX()) >> 4; cx++) {
            for (int cz = Math.min(from.getZ(), to.getZ()) >> 4; cz <= Math.max(from.getZ(), to.getZ()) >> 4; cz++) level.getChunk(cx, cz);
        }
        h.assertTrue(NuclearWarhead.sees(level, d, p), "без хребта шар не виден — проверка ни о чём");
        try {
            for (BlockPos b : BlockPos.betweenClosed(from, to)) level.setBlock(b, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            h.assertFalse(NuclearWarhead.sees(level, d, p), "шар виден сквозь хребет в 100 блоках");
        } finally {
            for (BlockPos b : BlockPos.betweenClosed(from, to)) level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        h.succeed();
    }

    /** Игрок над серединой площадки, взгляд — поворот и наклон (0, 0 — на юг, +Z). */
    private static FakePlayer player(GameTestHelper h, float yaw, float pitch) {
        FakePlayer p = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "incoming_eyes"));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER.above()));
        p.moveTo(at.x, at.y, at.z, yaw, pitch);
        p.setYHeadRot(yaw);
        return p;
    }

    /** 15 кт в воздухе в {@code burst}; {@code scale} — блоков на метр. */
    private static Detonation detonation(GameTestHelper h, Vec3 burst, float scale) {
        return new Detonation(NuclearGameTests.IDS.incrementAndGet(), burst, h.absolutePos(CENTER).getY(), 15, false, h.getLevel().getGameTime(),
                0, 0, 20_000, 7, scale, false, Detonation.OLD_WAVE);
    }
}
