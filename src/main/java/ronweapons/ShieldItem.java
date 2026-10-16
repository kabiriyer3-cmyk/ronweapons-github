package ronweapons;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * RoN ballistic shield. Carry it in the off hand with a Glock or Python: it stops damage from the front,
 * right click raises it (and aims the pistol over its edge), the bash key shoves whoever is in front.
 */
public class ShieldItem extends Item {
    public ShieldItem(Settings settings) { super(settings); }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) { return false; }
}
