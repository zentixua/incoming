package ua.zentix.incoming.strike;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * Designator settings (item component): weapon, count, spread, target mode, selected player, and nuclear
 * yield/burst type. Clamp values on every construction so invalid values cannot enter from packets or NBT.
 */
public record Loadout(WeaponType weapon, int count, int spread, TargetMode mode, String player, Nuke nuke) {
    public static final int MAX_COUNT = 100;
    public static final int MAX_SPREAD = 500;
    public static final Loadout DEFAULT = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", Nuke.DEFAULT);

    /**
     * Nuclear warhead: yield, kt, airburst (true) or surface burst, and for cruise missile/B-2 whether carried
     * instead of conventional ({@code onCarrier}; ICBM always nuclear).
     */
    public record Nuke(int yieldKt, boolean airBurst, boolean onCarrier) {
        /**
         * Maximum yield, kt. Heavy zone (5 psi at ground and above) grows as Y^⅔: 15 kt covers about 32 thousand
         * chunks; higher yields exceed memory and destruction budget, leaving ruins behind the blast front. Older
         * designators/saves with higher yield are clamped to this.
         */
        public static final int MAX_YIELD = 15;
        public static final Nuke DEFAULT = new Nuke(15, false, false);
        public static final Codec<Nuke> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("yield", DEFAULT.yieldKt).forGetter(Nuke::yieldKt),
                Codec.BOOL.optionalFieldOf("air_burst", DEFAULT.airBurst).forGetter(Nuke::airBurst),
                Codec.BOOL.optionalFieldOf("on_carrier", false).forGetter(Nuke::onCarrier)
        ).apply(i, Nuke::new));
        public static final StreamCodec<ByteBuf, Nuke> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Nuke::yieldKt, ByteBufCodecs.BOOL, Nuke::airBurst, ByteBufCodecs.BOOL, Nuke::onCarrier, Nuke::new);

        public Nuke {
            yieldKt = Mth.clamp(yieldKt, 1, MAX_YIELD);
        }

        public Nuke(int yieldKt, boolean airBurst) {
            this(yieldKt, airBurst, false);
        }

        public Nuke withOnCarrier(boolean on) {
            return new Nuke(yieldKt, airBurst, on);
        }

        public Nuke withYield(int kt) {
            return new Nuke(kt, airBurst, onCarrier);
        }

        public Nuke withAirBurst(boolean air) {
            return new Nuke(yieldKt, air, onCarrier);
        }
    }

    /** This designator setting carries a nuclear warhead: ICBM or nuclear missile/B-2. */
    public boolean nuclear() {
        return weapon.spec().warhead().always() || nuke.onCarrier() && carriesNuke(weapon);
    }

    /** Who can carry a nuclear warhead besides ICBM (spec). */
    public static boolean carriesNuke(WeaponType w) {
        return w.spec().warhead().optional();
    }

    public static final Codec<Loadout> CODEC = RecordCodecBuilder.create(i -> i.group(
            WeaponType.CODEC.optionalFieldOf("weapon", DEFAULT.weapon).forGetter(Loadout::weapon),
            Codec.INT.optionalFieldOf("count", DEFAULT.count).forGetter(Loadout::count),
            Codec.INT.optionalFieldOf("spread", DEFAULT.spread).forGetter(Loadout::spread),
            TargetMode.CODEC.optionalFieldOf("mode", DEFAULT.mode).forGetter(Loadout::mode),
            Codec.STRING.optionalFieldOf("player", "").forGetter(Loadout::player),
            Nuke.CODEC.optionalFieldOf("nuke", Nuke.DEFAULT).forGetter(Loadout::nuke)
    ).apply(i, Loadout::new));

    public static final StreamCodec<ByteBuf, Loadout> STREAM_CODEC = StreamCodec.composite(
            WeaponType.STREAM_CODEC, Loadout::weapon,
            ByteBufCodecs.VAR_INT, Loadout::count,
            ByteBufCodecs.VAR_INT, Loadout::spread,
            TargetMode.STREAM_CODEC, Loadout::mode,
            ByteBufCodecs.stringUtf8(16), Loadout::player,
            Nuke.STREAM_CODEC, Loadout::nuke,
            Loadout::new);

    public Loadout {
        count = Mth.clamp(count, 1, MAX_COUNT);
        spread = Mth.clamp(spread, 0, MAX_SPREAD);
        player = player == null ? "" : player.length() > 16 ? player.substring(0, 16) : player;
        nuke = nuke == null ? Nuke.DEFAULT : nuke;
    }

    public Loadout withWeapon(WeaponType w) {
        // Weapon that cannot fire single shots (MLRS): default to spec burst.
        WeaponSpec.Salvo salvo = w.spec().salvo();
        if (salvo.count() > 1 && weapon != w && count == 1) return new Loadout(w, salvo.count(), Math.max(spread, salvo.spread()), mode, player, nuke);
        return new Loadout(w, count, spread, mode, player, nuke);
    }

    public Loadout withCount(int c) {
        return new Loadout(weapon, c, spread, mode, player, nuke);
    }

    public Loadout withSpread(int s) {
        return new Loadout(weapon, count, s, mode, player, nuke);
    }

    public Loadout withMode(TargetMode m) {
        return new Loadout(weapon, count, spread, m, player, nuke);
    }

    public Loadout withPlayer(String p) {
        return new Loadout(weapon, count, spread, mode, p, nuke);
    }

    public Loadout withNuke(Nuke n) {
        return new Loadout(weapon, count, spread, mode, player, n);
    }
}
