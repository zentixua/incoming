package ua.zentix.incoming.grid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * Grid node — substation supplying an area of radius {@code radius}. A nearby strike knocks it out
 * ({@link Blackouts#onExplosion}): the area goes dark in a cascade from it.
 *
 * @param block node is a placed substation block (otherwise marked on the map by command)
 */
public record Node(int id, BlockPos pos, double radius, boolean block) {
    public static final Codec<Node> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Node::id),
            BlockPos.CODEC.fieldOf("pos").forGetter(Node::pos),
            Codec.DOUBLE.fieldOf("radius").forGetter(Node::radius),
            Codec.BOOL.fieldOf("block").forGetter(Node::block)
    ).apply(i, Node::new));
}
