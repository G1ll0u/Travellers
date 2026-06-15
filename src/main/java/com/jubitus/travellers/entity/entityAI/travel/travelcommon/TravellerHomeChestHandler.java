package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgOpenLoneBarrackPrompt;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.millenaire.common.item.MillItems;

import java.util.ArrayList;
import java.util.List;

public class TravellerHomeChestHandler {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        World world = event.getWorld();
        if (world.isRemote) return;

        EntityPlayer player = event.getEntityPlayer();
        ItemStack stack = event.getItemStack();

        if (stack.isEmpty() || stack.getItem() != MillItems.SUMMONING_WAND) return;
        if (!player.isSneaking()) return;
        if (world.provider.getDimension() != 0) return;

        if (player instanceof EntityPlayerMP) {
            BlockPos playerPos = player.getPosition();

            LoneBarrackManager lbMgr = LoneBarrackManager.get(world);
            List<LoneBarrackRecord> records = lbMgr.getByOwner(player.getUniqueID());
            List<MsgOpenLoneBarrackPrompt.ExistingBarrack> existing = new ArrayList<>(records.size());
            for (LoneBarrackRecord r : records) {
                existing.add(new MsgOpenLoneBarrackPrompt.ExistingBarrack(
                    r.center.getX(), r.center.getZ(), r.halfWidth, r.halfDepth, r.name));
            }

            TravellerFollowNet.CHANNEL.sendTo(
                new MsgOpenLoneBarrackPrompt(playerPos, existing),
                (EntityPlayerMP) player
            );
        }

        event.setCanceled(true);
        event.setUseItem(Event.Result.DENY);
        event.setUseBlock(Event.Result.DENY);
    }
}
