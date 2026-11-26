package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * The player's ear kept on the server ({@code docs/design/time.md} §9 change 6): hearing is client state
 * ({@code client/nuclear/Deafness}), told to the server while hurt ({@link C2S.Ear}) and given back on login
 * ({@link S2C.Ear}), so relogging cures nothing. The server does not read it; a dead player's ear is dropped with the body
 * (attachment not copied on death).
 */
public final class Ears {
    private Ears() {}

    /** The client told the player's ear: keep it (empty — healthy). */
    public static void told(ServerPlayer p, CompoundTag state) {
        if (!p.isAlive()) return;
        if (state.isEmpty()) p.removeData(ModAttachments.EAR);
        else p.setData(ModAttachments.EAR, state.copy());
    }

    /** Login: give the player back the ear it left with. */
    public static void sync(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new S2C.Ear(p.getExistingData(ModAttachments.EAR).orElseGet(CompoundTag::new)));
    }
}
