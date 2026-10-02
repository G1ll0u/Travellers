package com.jubitus.travellers.mixins;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.gui.TravellersGuiIds;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
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
    private void injectLoneBarrackOpen(EntityPlayer player, World world, BlockPos pos,
                                       EnumFacing side, float hitX, float hitY, float hitZ,
                                       EnumHand hand,
                                       CallbackInfoReturnable<EnumActionResult> cir) {

        if (world.isRemote) return;
        if (world.provider.getDimension() != 0) return;
        if (!player.isSneaking()) return;

        BlockPos playerPos = player.getPosition();
        player.openGui(
                Travellers.INSTANCE,
                TravellersGuiIds.LONE_BARRACK_PROMPT,
                world,
                playerPos.getX(), playerPos.getY(), playerPos.getZ()
        );

        cir.setReturnValue(EnumActionResult.SUCCESS);
        cir.cancel();
    }
}
