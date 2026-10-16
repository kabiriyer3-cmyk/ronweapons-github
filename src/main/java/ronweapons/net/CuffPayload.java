package ronweapons.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import ronweapons.RonWeapons;

/** Client -> server: start / cancel restraining a mob, or cut a cuffed one free. */
public record CuffPayload(byte op, int entity) implements CustomPayload {
    public static final byte START = 0;
    public static final byte CANCEL = 1;
    public static final byte RELEASE = 2;

    public static final Id<CuffPayload> ID = new Id<>(RonWeapons.id("cuff"));
    public static final PacketCodec<RegistryByteBuf, CuffPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BYTE, CuffPayload::op, PacketCodecs.VAR_INT, CuffPayload::entity, CuffPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
