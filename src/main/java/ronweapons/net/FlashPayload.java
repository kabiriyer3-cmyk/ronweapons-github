package ronweapons.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import ronweapons.RonWeapons;

/** Server -> one player: you were hit by a flashbang / stinger / CS gas. strength 0..1, seconds of the effect. */
public record FlashPayload(byte kind, float strength, float seconds) implements CustomPayload {
    public static final byte FLASH = 0;
    public static final byte STING = 1;
    public static final byte GAS = 2;

    public static final Id<FlashPayload> ID = new Id<>(RonWeapons.id("flash"));
    public static final PacketCodec<RegistryByteBuf, FlashPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BYTE, FlashPayload::kind, PacketCodecs.FLOAT, FlashPayload::strength,
            PacketCodecs.FLOAT, FlashPayload::seconds, FlashPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
