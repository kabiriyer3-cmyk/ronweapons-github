package ronweapons.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.Vec3d;
import ronweapons.RonWeapons;

/**
 * Server -> nearby clients: what a shot or reload looked like, for tracers, bullet holes, casings and dropped mags.
 * kind SHOT carries one hit per pellet (end point; face = block side id, or -1 for entities / misses).
 */
public record FxPayload(byte kind, int shooter, byte weapon, Vec3d start, List<Hit> hits) implements CustomPayload {
    public static final byte SHOT = 0;
    public static final byte RELOAD_EMPTY = 1;
    public static final byte RELOAD_TAC = 2;

    public record Hit(Vec3d pos, byte face) {}

    public static final Id<FxPayload> ID = new Id<>(RonWeapons.id("fx"));
    public static final PacketCodec<RegistryByteBuf, FxPayload> CODEC = PacketCodec.of(FxPayload::write, FxPayload::read);

    private void write(RegistryByteBuf buf) {
        buf.writeByte(kind);
        buf.writeVarInt(shooter);
        buf.writeByte(weapon);
        writeVec(buf, start);
        buf.writeByte(hits.size());
        for (Hit h : hits) {
            writeVec(buf, h.pos());
            buf.writeByte(h.face());
        }
    }

    private static FxPayload read(RegistryByteBuf buf) {
        byte kind = buf.readByte();
        int shooter = buf.readVarInt();
        byte weapon = buf.readByte();
        Vec3d start = readVec(buf);
        int n = buf.readUnsignedByte();
        List<Hit> hits = new ArrayList<>(n);
        for (int i = 0; i < n; i++) hits.add(new Hit(readVec(buf), buf.readByte()));
        return new FxPayload(kind, shooter, weapon, start, hits);
    }

    private static void writeVec(RegistryByteBuf buf, Vec3d v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3d readVec(RegistryByteBuf buf) { return new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()); }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
