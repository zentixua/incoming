package ua.zentix.incoming.strike;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.RandomSource;
import ua.zentix.incoming.Incoming;

import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/** Weapon types: number, name, spec ({@link WeaponSpec}), everything distinguishing weapons. */
public enum WeaponType implements StringRepresentable {
    /** Shahed-136-style kamikaze drone: ≈150 km/h, cruise above terrain, target dive. */
    DRONE(0, "drone", WeaponSpec.DRONE),
    /** Cruise missile: terrain-hugging, popup, dive; 80 m/s slower than real so approach visible. */
    MISSILE(1, "missile", WeaponSpec.MISSILE),
    /** B-2 and bunker-buster: penetrates ground, detonates underground. */
    BUNKER(2, "bunker", WeaponSpec.BUNKER),
    /** Intercontinental ballistic missile with nuclear warhead (see nuclear package). */
    NUKE(3, "nuke", WeaponSpec.NUKE),
    /**
     * BM-21 Grad-style MLRS: unguided ballistic rockets from 40-tube rack, half-second launch sequence. {@link
     * #id} follows enum order ({@code values()} indices equal ids).
     */
    ROCKET(4, "rocket", WeaponSpec.ROCKET),
    /** Lancet-style loitering munition: circles above target, dives on command or loiter timeout. */
    LOITER(5, "loiter", WeaponSpec.LOITER);

    public static final Codec<WeaponType> CODEC = StringRepresentable.fromEnum(WeaponType::values);
    private static final IntFunction<WeaponType> BY_ID = ByIdMap.continuous(WeaponType::id, values(), ByIdMap.OutOfBoundsStrategy.CLAMP);
    /** By number; unknown number (old world, other version) gives nearest weapon rather than error. */
    public static WeaponType byId(int id) {
        return BY_ID.apply(id);
    }

    public static final StreamCodec<ByteBuf, WeaponType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, WeaponType::id);

    private final int id;
    private final String name;
    private final WeaponSpec spec;

    WeaponType(int id, String name, WeaponSpec spec) {
        this.id = id;
        this.name = name;
        this.spec = spec;
    }

    /** Weapon spec. */
    public WeaponSpec spec() {
        return spec;
    }

    public int id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public Component displayName() {
        return Component.translatable("incoming.weapon." + name);
    }

    public Component description() {
        return Component.translatable("incoming.weapon." + name + ".desc", Component.keybind(Incoming.CAMERA_KEY));
    }

    public SirenKind siren() {
        return spec.siren();
    }

    /** Salvo launch gap, ticks. */
    public int salvoGap(RandomSource random) {
        return spec.salvoGap(random);
    }

    /** Designator/binocular scroll order: light to nuclear. */
    public static List<WeaponType> menu() {
        return List.of(DRONE, LOITER, MISSILE, ROCKET, BUNKER, NUKE);
    }

    public WeaponType next() {
        List<WeaponType> m = menu();
        return m.get((m.indexOf(this) + 1) % m.size());
    }

    public WeaponType previous() {
        List<WeaponType> m = menu();
        return m.get((m.indexOf(this) + m.size() - 1) % m.size());
    }

    /** Command names: English/Russian synonyms (“shahed” means drone). */
    public static WeaponType parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "drone", "shahed", "шахед", "дрон" -> DRONE;
            case "missile", "ракета" -> MISSILE;
            case "bunker", "bomb", "бомба" -> BUNKER;
            case "nuke", "icbm", "ядерка", "ядерная" -> NUKE;
            case "rocket", "grad", "mlrs", "град", "рсзо" -> ROCKET;
            case "loiter", "lancet", "ланцет", "барраж" -> LOITER;
            default -> null;
        };
    }

    public enum SirenKind {
        AIR_RAID, MISSILE, NUCLEAR
    }
}
