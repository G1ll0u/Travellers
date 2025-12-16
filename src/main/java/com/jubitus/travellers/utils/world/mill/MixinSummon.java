package com.jubitus.travellers.utils.world.mill;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import org.millenaire.common.item.ItemSummoningWand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemSummoningWand.class)
public class MixinSummon {
    /**
     * @author
     * @reason
     */
    @Inject(
            method = "onItemUseFirst",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void injectHomeChestUse(EntityPlayer player, World world, BlockPos pos,
                                    EnumFacing side, float hitX, float hitY, float hitZ,
                                    EnumHand hand,
                                    CallbackInfoReturnable<EnumActionResult> cir) {

        if (world.isRemote) return;
        if (world.provider.getDimension() != 0) return;

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (!player.isSneaking()) return;
        if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) return;

        TravellerHomeManager.get(world).addHomeChest(player, pos);
        player.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "This chest is now one of your homes for workers."
        ));

        world.playSound(null, pos,
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS,
                0.5F, 1.2F);

        // Stop original method, return SUCCESS
        cir.setReturnValue(EnumActionResult.SUCCESS);
        cir.cancel();
    }
}