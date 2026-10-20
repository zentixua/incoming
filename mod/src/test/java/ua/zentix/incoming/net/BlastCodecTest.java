package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Взрыв в пакете: со снарядом, который взорвался (его путь у клиента кончается), и без него. */
class BlastCodecTest {
    @Test
    void blastComesBackWithItsProjectile() {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), roundTrip(new S2C.Blast(S2C.Blast.MISSILE, new Vec3(1234.5, 64.25, -987.125), 3, 70.5f, -42L, Optional.of(id), 1)).projectile());
        assertEquals(Optional.empty(), roundTrip(new S2C.Blast(S2C.Blast.BUNKER, new Vec3(-8.5, -12, 4096.75), 0, 80, Long.MAX_VALUE, Optional.empty(), 2)).projectile());
    }

    /** Оружие — в конце пакета: повтор, записанный до него, читается с оружием по виду (шахед и «Ланцет» — видом шахеда). */
    @Test
    void weaponComesBackAndOldReplaysTakeItFromTheKind() {
        for (WeaponType w : WeaponType.values()) {
            assertEquals(w, roundTrip(new S2C.Blast(S2C.Blast.DRONE, Vec3.ZERO, 0, 0, 1, Optional.empty(), w.id())).weaponType());
        }
        assertEquals(WeaponType.DRONE, new S2C.Blast(S2C.Blast.DRONE, Vec3.ZERO, 0, 0, 1, Optional.empty(), S2C.Blast.NO_WEAPON).weaponType());
        assertEquals(WeaponType.MISSILE, new S2C.Blast(S2C.Blast.MISSILE, Vec3.ZERO, 0, 0, 1, Optional.empty(), S2C.Blast.NO_WEAPON).weaponType());
        assertEquals(WeaponType.ROCKET, new S2C.Blast(S2C.Blast.ROCKET, Vec3.ZERO, 0, 0, 1, Optional.empty(), S2C.Blast.NO_WEAPON).weaponType());
        assertEquals(WeaponType.BUNKER, new S2C.Blast(S2C.Blast.BUNKER, Vec3.ZERO, 0, 0, 1, Optional.empty(), S2C.Blast.NO_WEAPON).weaponType());
    }

    private static S2C.Blast roundTrip(S2C.Blast b) {
        ByteBuf buf = Unpooled.buffer();
        try {
            S2C.Blast.CODEC.encode(buf, b);
            S2C.Blast back = S2C.Blast.CODEC.decode(buf);
            assertFalse(buf.isReadable(), "лишние байты после взрыва");
            assertEquals(b, back);
            return back;
        } finally {
            buf.release();
        }
    }
}
