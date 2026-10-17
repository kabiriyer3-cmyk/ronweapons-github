package ronweapons.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import ronweapons.RonWeapons;

public record ActionPayload(byte action) implements CustomPayload {
    public static final byte RELOAD_TAC = 1;
    public static final byte RELOAD_EMPTY = 2;
    /** shotguns: stop after the shell in hand */
    public static final byte RELOAD_CANCEL = 3;
    public static final byte TOGGLE_SUPPRESSOR = 4;
    public static final byte CYCLE_OPTIC = 5;
    public static final byte TOGGLE_LIGHT = 6;
    public static final byte TOGGLE_NVG = 7;
    public static final byte SHIELD_BASH = 8;
    public static final byte THROW_OVER = 9;
    public static final byte THROW_UNDER = 10;
    public static final byte CYCLE_GRIP = 11;

    public static final Id<ActionPayload> ID = new Id<>(RonWeapons.id("action"));
    public static final PacketCodec<RegistryByteBuf, ActionPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.BYTE, ActionPayload::action, ActionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
