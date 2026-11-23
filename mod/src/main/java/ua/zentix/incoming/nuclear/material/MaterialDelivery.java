package ua.zentix.incoming.nuclear.material;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

/** Per-player cursor shared by live revisions and cold snapshots. One part per tick includes room for framing. */
public final class MaterialDelivery {
    private final List<MaterialBirth> queue = new ArrayList<>();
    private final HashSet<UUID> announced = new HashSet<>();
    private int part;
    private long last = Long.MIN_VALUE;
    private static final int PARTS_PER_SECOND = 20;
    private final long[] sent = new long[PARTS_PER_SECOND];
    private int cursor, filled;

    public void snapshot(Collection<MaterialBirth> births, double x, double z) {
        announced.clear();
        queue.clear();
        queue.addAll(births);
        queue.sort(Comparator.comparingDouble(b -> Math.hypot(b.x - x, b.z - z)));
        part = 0;
    }
    public void announce(MaterialBirth birth) {
        if (announced.add(birth.id)) queue.add(birth);
    }
    public void detonate(MaterialBirth birth) {
        if (!announced.remove(birth.id)) queue.add(birth);
    }
    public static void snapshot(ServerPlayer player, Collection<MaterialBirth> visible, Collection<MaterialBirth> warnings) {
        var all = new ArrayList<>(visible);
        all.addAll(warnings);
        var delivery = player.getData(ModAttachments.MATERIAL_DELIVERY);
        delivery.snapshot(all, player.getX(), player.getZ());
        for (var b : warnings) delivery.announced.add(b.id);
    }
    public static void send(ServerPlayer player, long tick) {
        if (!player.hasData(ModAttachments.MATERIAL_DELIVERY)) return;
        var packet = player.getData(ModAttachments.MATERIAL_DELIVERY).next(tick);
        if (packet != null) PacketDistributor.sendToPlayer(player, packet);
    }
    public S2C.NukeMaterial next(long tick) {
        return next(tick, System.nanoTime());
    }
    public S2C.NukeMaterial next(long tick, long now) {
        if (last == tick || queue.isEmpty() || filled == sent.length && now - sent[cursor] < 1_000_000_000L) return null;
        var data = queue.getFirst().encoded();
        if (data == null) return null;
        var packet = data.packets().get(part++);
        if (part == data.packets().size()) {
            queue.removeFirst();
            part = 0;
        }
        sent[cursor] = now;
        cursor = (cursor + 1) % sent.length;
        filled = Math.min(sent.length, filled + 1);
        last = tick;
        return packet;
    }
}
