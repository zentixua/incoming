package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** NBT vector as three numbers: {@code <prefix>_x/_y/_z}, or {@code x/y/z} without prefix (existing world format). */
public final class Nbt {
    private Nbt() {}

    public static void putVec(CompoundTag tag, String prefix, Vec3 v) {
        tag.putDouble(key(prefix, "x"), v.x);
        tag.putDouble(key(prefix, "y"), v.y);
        tag.putDouble(key(prefix, "z"), v.z);
    }

    /** Vector or {@code null} if absent from the tag. */
    @Nullable
    public static Vec3 getVec(CompoundTag tag, String prefix) {
        if (!tag.contains(key(prefix, "x"))) return null;
        return new Vec3(tag.getDouble(key(prefix, "x")), tag.getDouble(key(prefix, "y")), tag.getDouble(key(prefix, "z")));
    }

    private static String key(String prefix, String axis) {
        return prefix.isEmpty() ? axis : prefix + "_" + axis;
    }
}
