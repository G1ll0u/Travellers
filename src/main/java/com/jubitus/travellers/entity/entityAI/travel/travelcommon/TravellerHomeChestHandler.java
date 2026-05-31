package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgOpenHomeNamePrompt;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
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

        // --- Our custom behaviour: register home chest ---

        TravellerHomeManager mgr = TravellerHomeManager.get(world);

// If already registered -> remove immediately
        if (mgr.isHomeChestOfPlayer(player, pos)) {
            boolean ok = mgr.removeHomeChest(player, pos);
            if (ok) {
                player.sendMessage(new TextComponentString(
                        TextFormatting.YELLOW + "Home removed."
                ));
                world.playSound(null, pos, SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.6F, 1.0F);
            }
        } else {
            if (player instanceof EntityPlayerMP) {
                TravellerFollowNet.CHANNEL.sendTo(
                        new MsgOpenHomeNamePrompt(pos),
                        (EntityPlayerMP) player
                );
            }
        }

// prevent wand onItemUseFirst
        event.setCanceled(true);
        event.setUseItem(Event.Result.DENY);
        event.setUseBlock(Event.Result.DENY);
    }
}

