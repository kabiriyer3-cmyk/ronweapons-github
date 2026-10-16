package ronweapons;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** RoN less-lethal grenades. Left click: pull the pin and hold, release to throw overarm. Right click: underarm roll. */
public class GrenadeItem extends Item {
    public enum Kind {
        FLASH("flashbang", 30), STING("stinger", 30), GAS("csgas", 25);

        public final String id;
        /** ticks from the throw to the detonation (CS gas: until it starts to vent) */
        public final int fuse;
        Kind(String id, int fuse) { this.id = id; this.fuse = fuse; }

        public static Kind of(int i) { return values()[Math.max(0, Math.min(values().length - 1, i))]; }
    }

    public final Kind kind;

    public GrenadeItem(Kind kind, Settings settings) {
        super(settings);
        this.kind = kind;
    }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) { return false; }
}
