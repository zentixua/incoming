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

/** Взрыв в пакете: со снарядом, который взорвался (его путь у клиента кончается), и без него; с оружием взрыва. */
class BlastCodecTest {
    @Test
    void blastComesBackWithItsProjectile() {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), roundTrip(new S2C.Blast(S2C.Blast.MISSILE, new Vec3(1234.5, 64.25, -987.125), 3, 70.5f, -42L, Optional.of(id),
                WeaponType.MISSILE.id())).projectile());
        assertEquals(Optional.empty(), roundTrip(new S2C.Blast(S2C.Blast.BUNKER, new Vec3(-8.5, -12, 4096.75), 0, 80, Long.MAX_VALUE, Optional.empty(),
                WeaponType.BUNKER.id())).projectile());
    }

    /** Каждое оружие доходит до клиента самим собой: «Ланцет» — не шахед, хотя вид взрыва у них один. */
    @Test
    void blastNamesItsWeapon() {
        for (WeaponType w : WeaponType.values()) {
            S2C.Blast b = roundTrip(new S2C.Blast(S2C.Blast.DRONE, Vec3.ZERO, 0, 64, 1, Optional.empty(), w.id()));
            assertEquals(w, b.weaponType());
        }
        // повтор до поля weapon: оружие — по виду
        assertEquals(WeaponType.ROCKET, new S2C.Blast(S2C.Blast.ROCKET, Vec3.ZERO, 0, 64, 1, Optional.empty(), S2C.Blast.UNKNOWN).weaponType());
        assertEquals(WeaponType.BUNKER, new S2C.Blast(S2C.Blast.BUNKER, Vec3.ZERO, 0, 64, 1, Optional.empty(), S2C.Blast.UNKNOWN).weaponType());
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
