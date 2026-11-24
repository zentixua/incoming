package ua.zentix.incoming.nuclear.radiation;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.DataPackFixture;
import ua.zentix.incoming.gametest.PackExamples;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlackRainModel;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModDataMaps;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Worn radiation shielding ({@link WornShielding}, doc universal-integration C1), loaded the way a pack maker ships it:
 * the guide's suit example ({@link PackExamples#SHIELDING}) as a real datapack ({@link DataPackFixture}) with chainmail
 * standing in for the example suit, a quarter per piece. Against each real dose path, bodies side by side at one place
 * with nothing, one piece and the full set: prompt dose at the pulse ({@link NuclearWarhead#expose}), mob fallout
 * ({@link MobFallout}), player fallout and black rain ({@link RadiationTicker#step}). One piece cuts new exposure by a
 * quarter, the full set stops it, a dose taken before is never lowered. A stand-in for the Mekanism capability
 * ({@link WornShielding#install}, Mekanism is not in the GameTest runtime) shows the data map winning on an item both
 * describe, and Mekanism's hazmat numbers adding up to full protection.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShieldingGameTests {
    private static final String FILE = "data/incoming/data_maps/item/radiation_shielding.json";
    private static final Item[] CHAINMAIL = {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
    private static final Item[] IRON = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    /** Mekanism's hazmat suit, head to feet ({@code ItemHazmatSuitArmor.getShieldingByArmor}). */
    private static final double[] HAZMAT = {0.25, 0.4, 0.2, 0.15};
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** What the stand-in capability says of the turtle helmet; the pack lists it at 0.1. */
    private static final double TURTLE_OTHER = 0.9;

    private ShieldingGameTests() {}

    /** The guide's example with chainmail for the suit, plus the turtle helmet at 0.1 for the both-sources case. */
    private static String pack() {
        String json = PackExamples.SHIELDING;
        for (int i = 0; i < 4; i++) json = json.replace(PackExamples.SUIT.get(i), BuiltInRegistries.ITEM.getKey(CHAINMAIL[i]).toString());
        return json.replace("\"minecraft:chainmail_boots\": 0.25", "\"minecraft:chainmail_boots\": 0.25,\n    \"minecraft:turtle_helmet\": 0.1");
    }

    /** Stand-in for Mekanism's capability: iron armor as its hazmat suit, the turtle helmet at 0.9, a golden helmet broken at 1.5. */
    private static double standIn(ItemStack stack) {
        for (int i = 0; i < 4; i++) if (stack.is(IRON[i])) return HAZMAT[i];
        if (stack.is(Items.TURTLE_HELMET)) return TURTLE_OTHER;
        if (stack.is(Items.GOLDEN_HELMET)) return 1.5;
        return Double.NaN;
    }

    @GameTest(template = "range", batch = "radiation_shielding", skyAccess = true, timeoutTicks = 600)
    public static void wornShieldingStopsNewExposureOnly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        WornShielding.Source[] before = new WornShielding.Source[1];
        List<LivingEntity> spawned = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            if (before[0] != null) WornShielding.install(before[0]);
            // fake players are cached for the server's life: leave them without dose or sickness
            spawned.forEach(e -> {
                if (e instanceof ServerPlayer p) RadiationTicker.clear(p);
                else e.discard();
            });
        });
        DataPackFixture.run(h, "incoming_test_shielding", Map.of(FILE, pack()),
                () -> {
                    // without Mekanism nothing of compat/mekanism runs: its API is not even on the class path
                    h.assertFalse(ModList.get().isLoaded("mekanism"), "Mekanism is in the GameTest runtime");
                    h.assertTrue(WornShielding.source() == WornShielding.NONE, "a shielding source is installed without Mekanism");
                    try {
                        Class.forName("mekanism.api.radiation.capability.IRadiationShielding", false, WornShielding.class.getClassLoader());
                        throw new net.minecraft.gametest.framework.GameTestAssertException("Mekanism's API is on the runtime class path");
                    } catch (ClassNotFoundException expected) {
                        // the compat module could not even link: it is never loaded
                    }
                    h.assertTrue(data(Items.CHAINMAIL_HELMET) == null,
                            "chainmail has shielding before the pack");
                },
                () -> {
                    before[0] = WornShielding.install(ShieldingGameTests::standIn);
                    worn(h, spawned);
                    prompt(h, spawned);
                    mobFallout(h, spawned);
                    playerFalloutAndRain(h, spawned);
                },
                () -> {
                    WornShielding.install(before[0]);
                    before[0] = null;
                    h.assertTrue(data(Items.CHAINMAIL_HELMET) == null,
                            "shielding left after the pack");
                });
    }

    /**
     * A value outside [0, 1] is not clamped: the loader rejects the file (an error in the log), its valid entries with it,
     * so a pack maker sees the mistake instead of a suit that silently protects less or more.
     */
    @GameTest(template = "range", batch = "radiation_shielding_invalid", timeoutTicks = 400)
    public static void valueOutsideTheRangeRejectsTheFile(GameTestHelper h) {
        String json = """
                {
                  "values": {
                    "minecraft:iron_boots": 0.5,
                    "minecraft:iron_helmet": 1.5
                  }
                }
                """;
        DataPackFixture.run(h, "incoming_test_shielding_invalid", Map.of(FILE, json),
                () -> h.assertTrue(data(Items.IRON_BOOTS) == null && data(Items.IRON_HELMET) == null, "iron armor has shielding before the pack"),
                () -> {
                    h.assertTrue(data(Items.IRON_HELMET) == null, "1.5 loaded as " + data(Items.IRON_HELMET));
                    h.assertTrue(data(Items.IRON_BOOTS) == null, "a file with an invalid value loaded in part: boots " + data(Items.IRON_BOOTS));
                },
                () -> {});
    }

    private static Float data(Item item) {
        return new ItemStack(item).getItemHolder().getData(ModDataMaps.RADIATION_SHIELDING);
    }

    /** The sums: data map per piece, the other source where the map is silent, capped at 1; hands do not count. */
    private static void worn(GameTestHelper h, List<LivingEntity> spawned) {
        Cow c = cow(h, spawned, new BlockPos(20, 12, 20));
        eq(h, WornShielding.of(c), 0, "nothing worn");
        c.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
        eq(h, WornShielding.of(c), 0.25, "one listed piece");
        dress(c, CHAINMAIL);
        eq(h, WornShielding.of(c), 1, "the listed full set");
        dress(c, IRON);
        h.assertTrue(WornShielding.of(c) == 1, "Mekanism's hazmat numbers do not add up to full protection: " + WornShielding.of(c));
        strip(c);
        c.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
        eq(h, WornShielding.of(c), 0.1, "listed by the pack and the other source: the data map");
        c.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        eq(h, WornShielding.of(c), 1, "a broken foreign value is limited to 1");
        strip(c);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CHAINMAIL_HELMET));
        eq(h, WornShielding.of(c), 0, "a piece in hand");
    }

    /** Prompt dose at the pulse: one piece three quarters, the full set none; a dose taken before stays. */
    private static void prompt(GameTestHelper h, List<LivingEntity> spawned) {
        ServerLevel level = h.getLevel();
        BlockPos at = new BlockPos(40, 12, 32);
        Cow bare = cow(h, spawned, at), one = cow(h, spawned, at), full = cow(h, spawned, at), hazmat = cow(h, spawned, at), before = cow(h, spawned, at);
        one.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
        dress(full, CHAINMAIL);
        dress(hazmat, IRON);
        dress(before, CHAINMAIL);
        RadiationTicker.addDose(before, 3);
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(), bare.position().add(-40, 0, 0), bare.getY(), 15, true,
                level.getGameTime() - 1, 0, 0, 20_000, 7, 0.1f, false, Detonation.OLD_WAVE);
        for (Cow c : List.of(bare, one, full, hazmat, before)) NuclearWarhead.expose(level, d, c, null);
        float b = RadiationTicker.dose(bare).doseGy();
        h.assertTrue(b > 1, "control: prompt dose " + b + " Gy");
        ratio(h, RadiationTicker.dose(one).doseGy(), b, 0.75, "prompt dose, one piece");
        h.assertTrue(RadiationTicker.dose(full).doseGy() == 0, "prompt dose under the full set: " + RadiationTicker.dose(full).doseGy());
        h.assertTrue(RadiationTicker.dose(hazmat).doseGy() == 0, "prompt dose under Mekanism's hazmat numbers: " + RadiationTicker.dose(hazmat).doseGy());
        h.assertTrue(RadiationTicker.dose(before).doseGy() == 3, "a dose taken before the suit changed: " + RadiationTicker.dose(before).doseGy());
    }

    /** Mob fallout over one account: the same ratios. */
    private static void mobFallout(GameTestHelper h, List<LivingEntity> spawned) {
        ServerLevel level = h.getLevel();
        if (!IncomingConfig.SERVER.nukeMobRadiation.get()) throw new net.minecraft.gametest.framework.GameTestAssertException("mob radiation is off");
        BlockPos at = new BlockPos(20, 12, 44);
        Cow bare = cow(h, spawned, at), one = cow(h, spawned, at), full = cow(h, spawned, at), before = cow(h, spawned, at);
        one.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
        dress(full, CHAINMAIL);
        dress(before, CHAINMAIL);
        RadiationTicker.addDose(before, 0.5f);
        // fallout landed 100 s ago, the herd in the trail at ground zero (NuclearGameTests.mobsGetRadiationSickness)
        long landed = (long) Math.ceil(20 * 60 * 4 * Math.pow(15, 0.2));
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(), bare.position(), bare.getY(), 15, true,
                level.getGameTime() - 2000 - landed, 0, 5, 20_000, 7, 0.1f, true, Detonation.OLD_WAVE);
        WorkClock clock = WorkClock.counting(1_000_000L);
        clock.start(1_000_000_000_000L);
        new MobFallout().work(level, List.of(d), clock);
        float b = RadiationTicker.dose(bare).doseGy();
        h.assertTrue(b > 0, "control: mob fallout dose " + b + " Gy");
        ratio(h, RadiationTicker.dose(one).doseGy(), b, 0.75, "mob fallout, one piece");
        h.assertTrue(RadiationTicker.dose(full).doseGy() == 0, "mob fallout under the full set: " + RadiationTicker.dose(full).doseGy());
        h.assertTrue(RadiationTicker.dose(before).doseGy() == 0.5f, "a mob's dose taken before the suit changed: " + RadiationTicker.dose(before).doseGy());
    }

    /**
     * A player's second in the trail at ground zero while black rain begins: fallout and new rain dust, three quarters under
     * one piece, nothing under the full set; a dose taken before stays.
     */
    private static void playerFalloutAndRain(GameTestHelper h, List<LivingEntity> spawned) {
        ServerLevel level = h.getLevel();
        if (!IncomingConfig.SERVER.nukeRadiation.get() || !IncomingConfig.SERVER.nukeBlackRain.get())
            throw new net.minecraft.gametest.framework.GameTestAssertException("radiation or black rain is off");
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(44, 12, 44)));
        ServerPlayer bare = player(h, spawned, 0, at), one = player(h, spawned, 1, at), full = player(h, spawned, 2, at), before = player(h, spawned, 3, at);
        one.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
        dress(full, CHAINMAIL);
        dress(before, CHAINMAIL);
        RadiationTicker.addDose(before, 2);
        // the rain starts over the fire zone at BlackRainModel.START_S, the end of the show: real time, one second's step
        long age = (long) (BlackRainModel.START_S * 20);
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(), at, at.y, 15, true,
                level.getGameTime() - age, 0, 5, 20_000, 7, 0.1f, true, Detonation.OLD_WAVE);
        double seconds = NuclearEvents.get(level).seconds(d, level);
        h.assertTrue(d.blackRainAt(at.x, at.z, seconds), "no black rain at ground zero at " + seconds + " s");
        h.assertTrue(d.falloutRateAt(at.x, at.z, seconds) > MobFallout.BACKGROUND, "no fallout at ground zero: " + d.falloutRateAt(at.x, at.z, seconds));
        h.assertTrue(Detonation.underOpenSky(level, bare.getEyePosition()), "the players are not under the open sky");
        RadiationTicker.step(level, List.of(bare, one, full, before), List.of(d));
        RadiationDose b = RadiationTicker.dose(bare), o = RadiationTicker.dose(one), f = RadiationTicker.dose(full);
        h.assertTrue(b.doseGy() > 0 && b.contamination() > 0, "control: dose " + b.doseGy() + " Gy, contamination " + b.contamination());
        ratio(h, o.contamination(), b.contamination(), 0.75, "rain contamination, one piece");
        ratio(h, o.doseGy(), b.doseGy(), 0.75, "player fallout and rain dose, one piece");
        h.assertTrue(f.doseGy() == 0 && f.contamination() == 0, "under the full set: dose " + f.doseGy() + " Gy, contamination " + f.contamination());
        h.assertTrue(RadiationTicker.dose(before).doseGy() == 2, "a player's dose taken before the suit changed: " + RadiationTicker.dose(before).doseGy());
        // the counter still reads the place: the suit lowers the dose, not the rate around the body
        double place = b.rate() - b.contamination();
        h.assertTrue(place > 0 && Math.abs(f.rate() - place) < 1e-4 * place, "Geiger rate under the full set " + f(f.rate()) + ", the place's " + f(place));
    }

    private static Cow cow(GameTestHelper h, List<LivingEntity> spawned, BlockPos at) {
        Cow c = h.spawn(EntityType.COW, at);
        c.setNoAi(true);
        c.setInvulnerable(true);
        c.removeData(ModAttachments.NUKE_SEEN);
        spawned.add(c);
        return c;
    }

    private static ServerPlayer player(GameTestHelper h, List<LivingEntity> spawned, int n, Vec3 at) {
        String name = "shielding_" + n;
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(at.x, at.y, at.z);
        strip(p);
        RadiationTicker.clear(p);
        spawned.add(p);
        return p;
    }

    private static void dress(LivingEntity e, Item[] set) {
        for (int i = 0; i < 4; i++) e.setItemSlot(SLOTS[i], new ItemStack(set[i]));
    }

    private static void strip(LivingEntity e) {
        for (EquipmentSlot s : EquipmentSlot.values()) e.setItemSlot(s, ItemStack.EMPTY);
    }

    private static void eq(GameTestHelper h, double got, double want, String what) {
        h.assertTrue(Math.abs(got - want) < 1e-6, what + ": worn shielding " + f(got) + ", want " + f(want));
    }

    private static void ratio(GameTestHelper h, double got, double of, double want, String what) {
        h.assertTrue(of > 0 && Math.abs(got / of - want) < 1e-4, what + ": " + f(got) + " against " + f(of) + " bare, ratio "
                + f(of > 0 ? got / of : Double.NaN) + ", want " + f(want));
    }

    private static String f(double x) {
        return String.format(Locale.ROOT, "%.6f", x);
    }
}
