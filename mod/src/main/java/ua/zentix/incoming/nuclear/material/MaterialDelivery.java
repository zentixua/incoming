package ua.zentix.incoming.nuclear.material;

import ua.zentix.incoming.net.S2C;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Per-player cursor shared by live revisions and cold snapshots. One part per tick includes room for framing. */
public final class MaterialDelivery {
    private final List<MaterialBirth> queue = new ArrayList<>();
    private int part;
    private long last = Long.MIN_VALUE;

    public void snapshot(Collection<MaterialBirth> births, double x, double z) {
        queue.clear();
        queue.addAll(births);
        queue.sort(Comparator.comparingDouble(b -> Math.hypot(b.x - x, b.z - z)));
        part = 0;
    }
    public void offer(MaterialBirth b) { queue.add(b); }
    public S2C.NukeMaterial next(long tick) {
        if (last == tick || queue.isEmpty()) return null;
        var data = queue.getFirst().encoded();
        if (data == null) return null;
        var packet = data.packets().get(part++);
        if (part == data.packets().size()) {
            queue.removeFirst();
            part = 0;
        }
        last = tick;
        return packet;
    }
}
