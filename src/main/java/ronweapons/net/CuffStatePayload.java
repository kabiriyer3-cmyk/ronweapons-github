package ronweapons.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import ronweapons.RonWeapons;

/** Server -> clients: a mob is free, being cuffed (by player {@code cuffer}) or cuffed. Drives the mob / player poses. */
public record CuffStatePayload(int entity, byte state, int cuffer) implements CustomPayload {
    public static final byte FREE = 0;
    public static final byte CUFFING = 1;
    public static final byte CUFFED = 2;

    public static final Id<CuffStatePayload> ID = new Id<>(RonWeapons.id("cuff_state"));
    public static final PacketCodec<RegistryByteBuf, CuffStatePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, CuffStatePayload::entity, PacketCodecs.BYTE, CuffStatePayload::state,
            PacketCodecs.VAR_INT, CuffStatePayload::cuffer, CuffStatePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
