package com.jubitus.travellers.utils.world.mill;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
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

        // Only when sneaking
        if (!player.isSneaking()) return;

        // Only when targeting a vanilla chest
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) return;

        // --- NEW BEHAVIOR ---
        TravellerHomeManager mgr = TravellerHomeManager.get(world);

        // If this chest is already a home -> remove it
        if (mgr.isHomeChestOfPlayer(player, pos)) {
            boolean ok = mgr.removeHomeChest(player, pos);
            if (ok) {
                player.sendMessage(new TextComponentString(
                        TextFormatting.YELLOW + "Home removed."
                ));
                world.playSound(null, pos,
                        SoundEvents.ENTITY_ITEM_BREAK,
                        SoundCategory.PLAYERS,
                        0.6F, 1.0F);
            }

            cir.setReturnValue(EnumActionResult.SUCCESS);
            cir.cancel();
            return;
        }

        // Not a home yet -> open naming prompt GUI
        player.openGui(
                Travellers.INSTANCE,
                TravellersGuiIds.HOME_NAME_PROMPT,
                world,
                pos.getX(), pos.getY(), pos.getZ()
        );

        // Block original wand logic
        cir.setReturnValue(EnumActionResult.SUCCESS);
        cir.cancel();
    }
}
