package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.target.Sightings;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapPlayersCodecTest {
    /**
     * Map marks pass through the buffer without loss: UUID, name (and not in Latin), coordinates with a fraction and minus, what is it,
     * , is it old; whether the map draws them.
     */
    @Test
    void roundTrip() {
        S2C.MapPlayers sent = new S2C.MapPlayers(List.of(
                new S2C.MapPlayer(UUID.randomUUID(), "Steve", -1234.75, 98765.5, Sightings.Kind.PLAYER, false, 12),
                new S2C.MapPlayer(UUID.randomUUID(), "Друг_1", 0.125, -29_999_984.0, Sightings.Kind.PLAYER, true, 0),
                new S2C.MapPlayer(UUID.randomUUID(), "", 7, 8, Sightings.Kind.AIRCRAFT, false, 600),
                new S2C.MapPlayer(UUID.randomUUID(), "Шахед", -7, -8, Sightings.Kind.PROJECTILE, false, 0)), true);
        ByteBuf buf = Unpooled.buffer();
        S2C.MapPlayers.CODEC.encode(buf, sent);
        S2C.MapPlayers got = S2C.MapPlayers.CODEC.decode(buf);
        assertEquals(sent, got);
        assertEquals(0, buf.readableBytes(), "everything written down has been read");

        ByteBuf empty = Unpooled.buffer();
        S2C.MapPlayers.CODEC.encode(empty, new S2C.MapPlayers(List.of(), false));
        assertEquals(new S2C.MapPlayers(List.of(), false), S2C.MapPlayers.CODEC.decode(empty));
    }

    /** A name longer than the limit does not go into the packet (the codec throws the line, but does not cut it silently). */
    @Test
    void nameLimit() {
        S2C.MapPlayers tooLong = new S2C.MapPlayers(List.of(new S2C.MapPlayer(UUID.randomUUID(), "x".repeat(S2C.MapPlayer.MAX_NAME + 1), 0, 0,
                Sightings.Kind.PLAYER, false, 0)), true);
        assertThrows(RuntimeException.class, () -> S2C.MapPlayers.CODEC.encode(Unpooled.buffer(), tooLong));
    }
}
