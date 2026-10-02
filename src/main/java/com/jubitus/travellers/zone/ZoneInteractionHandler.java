package com.jubitus.travellers.zone;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.zone.net.MsgOpenZonePrompt;
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

/** Sneak-right-click with the Summoning Wand opens the zone-creation prompt (any type). */
public class ZoneInteractionHandler {

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

            PlayerZoneManager lbMgr = PlayerZoneManager.get(world);
            List<PlayerZoneRecord> records = lbMgr.getByOwner(player.getUniqueID());
            List<MsgOpenZonePrompt.ExistingBarrack> existing = new ArrayList<>(records.size());
            for (PlayerZoneRecord r : records) {
                existing.add(new MsgOpenZonePrompt.ExistingBarrack(
                    r.center.getX(), r.center.getZ(), r.halfWidth, r.halfDepth, r.name));
            }

            TravellersNetwork.CHANNEL.sendTo(
                new MsgOpenZonePrompt(playerPos, existing),
                (EntityPlayerMP) player
            );
        }

        event.setCanceled(true);
        event.setUseItem(Event.Result.DENY);
        event.setUseBlock(Event.Result.DENY);
    }
}
