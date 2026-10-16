package ronweapons.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import ronweapons.RonWeapons;

public record ActionPayload(byte action) implements CustomPayload {
    public static final byte RELOAD_TAC = 1;
    public static final byte RELOAD_EMPTY = 2;

    public static final Id<ActionPayload> ID = new Id<>(RonWeapons.id("action"));
    public static final PacketCodec<RegistryByteBuf, ActionPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.BYTE, ActionPayload::action, ActionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
