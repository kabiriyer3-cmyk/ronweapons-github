package ronweapons;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** RoN plastic zipcuffs: hold right click on a mob within reach to restrain it, sneak + right click a cuffed mob to cut it free. */
public class ZipcuffsItem extends Item {
    public ZipcuffsItem(Settings settings) { super(settings); }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) { return false; }
}
