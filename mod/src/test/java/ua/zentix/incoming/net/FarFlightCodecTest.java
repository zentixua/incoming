package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Снаряд вдали в пакете: всё, из чего клиент строит путь и позу модели, с креном. */
class FarFlightCodecTest {
    @Test
    void flightComesBackWithItsRoll() {
        // скорость — float в пакете: числа, точные во float
        S2C.FarFlight f = new S2C.FarFlight(UUID.randomUUID(), WeaponType.MISSILE.id(), false, false, true, new Vec3(1234.5, 96.25, -987.125),
                new Vec3(3.5, -0.25, 1.75), -60, 2.5f, -27.5f, FlightPhase.CRUISE.ordinal(), 314, new Vec3(4000.5, 70, -3000.25));
        ByteBuf buf = Unpooled.buffer();
        try {
            S2C.FarFlight.CODEC.encode(buf, f);
            S2C.FarFlight back = S2C.FarFlight.CODEC.decode(buf);
            assertFalse(buf.isReadable(), "лишние байты после снаряда");
            assertEquals(f, back);
            assertEquals(-27.5f, back.roll());
        } finally {
            buf.release();
        }
    }

    /**
     * Земля под снарядом на старте уходит только с ней (флаг): маршевому снаряду байтов не прибавляется, у стартующего
     * она возвращается та же.
     */
    @Test
    void groundOnlyAtLaunch() {
        S2C.FarFlight cruise = new S2C.FarFlight(UUID.randomUUID(), WeaponType.ROCKET.id(), false, false, false, new Vec3(10, 80, 20),
                new Vec3(0, 1, 0), 0, -50, 0, FlightPhase.CRUISE.ordinal(), 4, new Vec3(600, 70, 20));
        S2C.FarFlight boost = new S2C.FarFlight(cruise.id(), cruise.weapon(), false, false, false, cruise.pos(), cruise.velocity(), 0, -50, 0,
                FlightPhase.BOOST.ordinal(), 4, cruise.aim(), 71);
        ByteBuf a = Unpooled.buffer(), b = Unpooled.buffer();
        try {
            S2C.FarFlight.CODEC.encode(a, cruise);
            S2C.FarFlight.CODEC.encode(b, boost);
            assertEquals(a.readableBytes() + 4, b.readableBytes(), "земля — ровно один float и только на старте");
            S2C.FarFlight back = S2C.FarFlight.CODEC.decode(b);
            assertFalse(b.isReadable(), "лишние байты после снаряда");
            assertEquals(71f, back.ground());
            assertTrue(Float.isNaN(S2C.FarFlight.CODEC.decode(a).ground()), "у маршевого земли нет");
        } finally {
            a.release();
            b.release();
        }
    }
}
