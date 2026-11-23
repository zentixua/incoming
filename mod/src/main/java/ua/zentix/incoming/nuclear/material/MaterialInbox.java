package ua.zentix.incoming.nuclear.material;

import ua.zentix.incoming.net.S2C;

import java.io.ByteArrayOutputStream;

/** One source revision. Only the complete snapshot becomes queryable. A replay can begin directly with its batches. */
public final class MaterialInbox {
    private ByteArrayOutputStream parts;
    private int next, count;
    private MaterialMap map;
    public MaterialMap map() { return map; }

    public void receive(S2C.NukeMaterial packet) {
        if (map != null) return;
        if (packet.part() == 0) {
            parts = new ByteArrayOutputStream();
            count = packet.remainingBatches() + 1;
            next = 0;
        }
        if (parts == null || next != packet.part() || next + packet.remainingBatches() + 1 != count) return;
        parts.writeBytes(packet.data());
        next++;
        if (next == count) {
            map = MaterialMap.decode(parts.toByteArray());
            parts = null;
        }
    }
}
