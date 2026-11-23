package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.material.MaterialBirth;
import ua.zentix.incoming.nuclear.material.MaterialInbox;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaterialJoinGameTests {
    private MaterialJoinGameTests() {}

    @GameTest(template = "range", batch = "nuke_material_login", timeoutTicks = 500, skyAccess = true)
    public static void coldLoginsFromOppositeSidesReceiveTheSameSources(GameTestHelper h) {
        var level = h.getLevel();
        var events = NuclearEvents.get(level);
        var at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 5, 32)));
        var first = HiddenLaunchGameTests.Watcher.at(h, "dust_east", at);
        var second = HiddenLaunchGameTests.Watcher.at(h, "dust_west", at.add(512, 0, 0));
        var ids = new java.util.ArrayList<Integer>();
        var expected = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 2; i++) {
            int id = events.nextId();
            var pos = at.add(i * 512, 0, 0);
            var d = new Detonation(id, pos, pos.y, 1, true, level.getGameTime() - 40, 0, 0, 20000, i, 0.01f, false, 0.35f);
            var birth = MaterialBirth.seal(UUID.randomUUID(), d, d.gameTime() - 200, i, new Long2IntOpenHashMap(), null);
            events.add(d, level);
            events.material(id, birth);
            ids.add(id);
            expected.add(birth.id);
        }
        StrikeGameTests.afterTest(h, () -> {
            events.detonations().removeIf(d -> ids.contains(d.id()));
            ids.forEach(events::forgetMaterial);
            events.setDirty();
        });
        for (var w : List.of(first, second)) {
            NuclearStrikes.onLogin(new PlayerEvent.PlayerLoggedInEvent(w.player));
            var sync = w.got.stream().filter(S2C.NukeSync.class::isInstance).map(S2C.NukeSync.class::cast).findFirst().orElseThrow();
            for (int i = 0; i < ids.size(); i++) h.assertTrue(expected.get(i).equals(sync.materials().get(ids.get(i))), "cold sync missed revision");
        }
        h.succeedWhen(() -> {
            for (int i = 0; i < 2; i++) {
                var w = i == 0 ? first : second;
                var packets = w.got.stream().filter(S2C.NukeMaterial.class::isInstance).map(S2C.NukeMaterial.class::cast)
                        .filter(p -> expected.contains(p.id())).toList();
                h.assertTrue(!packets.isEmpty() && packets.getFirst().id().equals(expected.get(i)), "nearest source first for client " + i);
                var sources = new HashMap<UUID, MaterialInbox>();
                for (var p : packets) sources.computeIfAbsent(p.id(), unused -> new MaterialInbox()).receive(p);
                for (int n = 0; n < 2; n++) {
                    var inbox = sources.get(expected.get(n));
                    h.assertTrue(inbox != null && inbox.map() != null && inbox.map().wet() == (n == 0)
                            && java.util.Arrays.equals(inbox.map().encode(), events.material(ids.get(n)).encoded().bytes()), "client source missing or changed");
                }
            }
        });
    }
}
