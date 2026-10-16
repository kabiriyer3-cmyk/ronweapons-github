package ronweapons;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** SWAT night vision goggles: worn in the head slot, toggled with the NVG key (on/off state lives on the stack). */
public class NvgItem extends Item implements Equipment {
    public NvgItem(Settings settings) { super(settings); }

    @Override
    public EquipmentSlot getSlotType() { return EquipmentSlot.HEAD; }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        return equipAndSwap(this, world, user, hand);
    }
}
