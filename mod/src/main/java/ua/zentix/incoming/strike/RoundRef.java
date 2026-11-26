package ua.zentix.incoming.strike;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

/**
 * One admitted round's identity ({@link Order}): saved with whatever carries the round (projectile, scheduled ICBM strike)
 * and handed over when its form changes, so its events keep one id across virtual flight, rematerialization and restart.
 * It also carries the world rules its order was made under, so a rule changed later never reaches it.
 *
 * @param tag   group name from the order; null without one
 * @param rules world rules captured with the order ({@link Order#rules}); a round saved without them takes the config
 *              when loaded
 */
public record RoundRef(UUID order, UUID round, @Nullable String tag, StrikeRules rules) {
    public static final Codec<RoundRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("order").forGetter(RoundRef::order),
            UUIDUtil.CODEC.fieldOf("round").forGetter(RoundRef::round),
            Codec.STRING.optionalFieldOf("tag").forGetter(r -> Optional.ofNullable(r.tag())),
            StrikeRules.CODEC.optionalFieldOf("rules").forGetter(r -> Optional.of(r.rules()))
    ).apply(i, (order, round, tag, rules) -> new RoundRef(order, round, tag.orElse(null), rules.orElseGet(StrikeRules::live))));

    /** Round {@code index} of order {@code order}: a name-based UUID, so a salvo recomputes it after a restart. */
    public static RoundRef of(UUID order, int index, @Nullable String tag, StrikeRules rules) {
        ByteBuffer b = ByteBuffer.allocate(20).putLong(order.getMostSignificantBits()).putLong(order.getLeastSignificantBits()).putInt(index);
        return new RoundRef(order, UUID.nameUUIDFromBytes(b.array()), tag, rules);
    }

    /** Same under the rules the config holds now (tests). */
    public static RoundRef of(UUID order, int index, @Nullable String tag) {
        return of(order, index, tag, StrikeRules.live());
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("order", order);
        t.putUUID("round", round);
        if (tag != null) t.putString("tag", tag);
        t.putInt("rules", rules.bits());
        return t;
    }

    @Nullable
    public static RoundRef load(CompoundTag t) {
        if (!t.hasUUID("order") || !t.hasUUID("round")) return null;
        return new RoundRef(t.getUUID("order"), t.getUUID("round"), t.contains("tag") ? t.getString("tag") : null,
                t.contains("rules", Tag.TAG_INT) ? StrikeRules.of(t.getInt("rules")) : StrikeRules.live());
    }
}
