package com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber
public final class CampfireGuards {

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        World w = e.getWorld();
        Block fire = CampfireBlock.get();
        if (fire == null) return;

        IBlockState st = w.getBlockState(e.getPos());
        if (st.getBlock() != fire) return;

        // Only if this firepit was placed by a travellers:
        if (w instanceof WorldServer) {
            WorldServer ws = (WorldServer) w;
            if (!CampfireManager.get(ws).isManaged(ws.provider.getDimension(), e.getPos())) return;
        } else return;

        e.setUseBlock(Event.Result.DENY);
        e.setUseItem(Event.Result.DENY);
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHarvestDrops(BlockEvent.HarvestDropsEvent e) {
        Block fire = CampfireBlock.get();
        if (fire == null) return;
        if (e.getState().getBlock() != fire) return;

        World w = e.getWorld();
        if (!(w instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) w;

        if (!CampfireManager.get(ws).isManaged(ws.provider.getDimension(), e.getPos())) return;

        e.getDrops().clear();
        e.setDropChance(0.0f);
    }


    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        World w = e.getWorld();
        Block fire = CampfireBlock.get();
        if (fire == null) return;
        if (w.getBlockState(e.getPos()).getBlock() != fire) return;

        if (w instanceof WorldServer) {
            WorldServer ws = (WorldServer) w;
            // Remove from manager first
            CampfireManager.get(ws).removeIfManaged(ws.provider.getDimension(), e.getPos());
            // Ensure no item entities: replace with air now (if not already)
            ws.setBlockToAir(e.getPos());
            // No XP: BreakEvent has no xp field in 1.12.2; items are already zeroed via HarvestDropsEvent
        }
    }


    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate e) {
        Block fire = CampfireBlock.get();
        if (fire == null) return;
        World w = e.getWorld();
        if (!(w instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) w;
        int dim = ws.provider.getDimension();

        e.getAffectedBlocks().removeIf(pos -> {
            if (ws.getBlockState(pos).getBlock() != fire) return false;
            boolean managed = CampfireManager.get(ws).isManaged(dim, pos);
            if (managed) {
                // Remove the block and its registry entry silently
                ws.setBlockToAir(pos);
                CampfireManager.get(ws).removeIfManaged(dim, pos);
            }
            return managed; // remove from “affected blocks” so no item entities spawn
        });
    }
}