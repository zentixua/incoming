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
    private final java.util.Set<MaterialBirth> visible = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final java.util.Set<MaterialBirth> retired = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private MaterialBirth.Encoded sending;
    private int part;
    private long epoch = -1;
    private boolean forceManifest;
    private List<UUID> manifest = List.of();
    private long last = Long.MIN_VALUE;
    private static final int PARTS_PER_SECOND = 20;
    private final long[] sent = new long[PARTS_PER_SECOND];
    private int cursor, filled;

    public void snapshot(Collection<MaterialBirth> births, double x, double z) {
        epoch = -1;
        forceManifest = true;
        announced.clear();
        queue.clear();
        visible.clear();
        visible.addAll(births);
        visible.removeAll(retired);
        queue.addAll(visible);
        sending = null;
        queue.sort(Comparator.comparingDouble(b -> Math.hypot(b.x - x, b.z - z)));
        part = 0;
    }
    public void announce(MaterialBirth birth) {
        epoch = -1;
        announced.add(birth.id);
        visible.add(birth);
        refine(birth);
    }
    public void detonate(MaterialBirth birth) {
        epoch = -1;
        if (!announced.remove(birth.id)) {
            visible.add(birth);
            refine(birth);
        }
    }
    public static void retire(ServerPlayer player, UUID id) {
        if (!player.hasData(ModAttachments.MATERIAL_DELIVERY)) return;
        var delivery = player.getData(ModAttachments.MATERIAL_DELIVERY);
        var it = delivery.visible.iterator();
        while (it.hasNext()) {
            var birth = it.next();
            if (!birth.id.equals(id)) continue;
            it.remove();
            delivery.retired.add(birth);
            delivery.epoch = -1;
            return;
        }
    }

    public void refine(MaterialBirth birth) {
        if (visible.contains(birth) && !queue.contains(birth)) queue.add(birth);
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
        var delivery = player.getData(ModAttachments.MATERIAL_DELIVERY);
        delivery.manifest(player);
        var packet = delivery.next(tick);
        if (packet != null) PacketDistributor.sendToPlayer(player, packet);
    }
    public void manifest(ServerPlayer player) {
        var events = ua.zentix.incoming.nuclear.NuclearEvents.get(player.serverLevel());
        if (epoch != events.materialEpoch()) {
            epoch = events.materialEpoch();
            var ids = events.currentMaterials().stream().filter(visible::contains).map(b -> b.id).toList();
            if (forceManifest || !ids.equals(manifest)) {
                forceManifest = false;
                if (!queue.isEmpty() && !ids.contains(queue.getFirst().id)) { sending = null; part = 0; }
                queue.removeIf(b -> !ids.contains(b.id));
                // Replace archived UUID batches in the recorder cache with a one-byte tombstone.
                for (UUID old : manifest) if (!ids.contains(old))
                    PacketDistributor.sendToPlayer(player, new S2C.NukeMaterial(old, 0, 0, new byte[]{0}));
                PacketDistributor.sendToPlayer(player, new S2C.NukeMaterialSources(ids));
                manifest = ids;
            }
        }
    }
    public S2C.NukeMaterial next(long tick) {
        return next(tick, System.nanoTime());
    }
    public S2C.NukeMaterial next(long tick, long now) {
        if (last == tick || queue.isEmpty() || filled == sent.length && now - sent[cursor] < 1_000_000_000L) return null;
        var birth = queue.getFirst();
        if (sending == null) sending = birth.encoded();
        var data = sending;
        if (data == null) return null;
        var packet = data.packets().get(part++);
        if (part == data.packets().size()) {
            queue.removeFirst();
            if (birth.encoded() != sending) queue.add(birth);
            sending = null;
            part = 0;
        }
        sent[cursor] = now;
        cursor = (cursor + 1) % sent.length;
        filled = Math.min(sent.length, filled + 1);
        last = tick;
        return packet;
    }
}
