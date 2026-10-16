package ronweapons;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Durability doubles as the magazine: damage = rounds fired since the last reload. */
public class GunItem extends Item {
    public final WeaponDef def;

    public GunItem(WeaponDef def, Settings settings) {
        super(settings);
        this.def = def;
    }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) { return false; }
}
