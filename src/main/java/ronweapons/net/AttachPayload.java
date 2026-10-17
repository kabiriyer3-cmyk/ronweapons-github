package ronweapons.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import ronweapons.RonWeapons;

/** Attachment menu: put option {@code index} of {@code slot} (0 optic, 1 muzzle device, 2 foregrip) on the held weapon. */
public record AttachPayload(byte slot, byte index) implements CustomPayload {
    public static final byte OPTIC = 0, MUZZLE = 1, GRIP = 2;

    public static final Id<AttachPayload> ID = new Id<>(RonWeapons.id("attach"));
    public static final PacketCodec<RegistryByteBuf, AttachPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.BYTE, AttachPayload::slot, PacketCodecs.BYTE, AttachPayload::index, AttachPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
