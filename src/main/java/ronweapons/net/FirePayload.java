package ronweapons.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import ronweapons.RonWeapons;

public record FirePayload() implements CustomPayload {
    public static final Id<FirePayload> ID = new Id<>(RonWeapons.id("fire"));
    public static final PacketCodec<RegistryByteBuf, FirePayload> CODEC = PacketCodec.unit(new FirePayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
