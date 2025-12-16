package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.millenaire.common.item.MillItems;

public class TravellerHomeChestHandler {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        World world = event.getWorld();
        if (world.isRemote) {
            return; // server-side only
        }

        EntityPlayer player = event.getEntityPlayer();
        ItemStack stack = event.getItemStack();
        BlockPos pos = event.getPos();
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // Only care about the summoning wand
        if (stack.isEmpty() || stack.getItem() != MillItems.SUMMONING_WAND) {
            return;
        }

        // Only when sneaking
        if (!player.isSneaking()) {
            return;
        }

        // Same dimension guard as original code: overworld only
        if (world.provider.getDimension() != 0) {
            return;
        }

        // Only when targeting a vanilla chest
        if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) {
            return;
        }

        // --- Our custom behaviour: register home chest ---

        TravellerHomeManager.get(world).addHomeChest(player, pos);

        player.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "This chest is now one of your homes for workers."
        ));

        world.playSound(
                null,
                pos,
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS,
                0.5F,
                1.2F
        );

        // IMPORTANT: prevent the original ItemSummoningWand.onItemUseFirst logic
        event.setCanceled(true);
        event.setUseItem(Event.Result.DENY);
        event.setUseBlock(Event.Result.DENY);
    }
}

