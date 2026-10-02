package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRoles;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * {@link ChestSource} backed by the zone's real chest {@code TileEntity}s - used while a loaded
 * palfrenier is doing the work, so taming/saddling/breeding spend real items with the real chest
 * shrinking in front of the player. Unloaded chest positions are skipped (there is no mirror
 * bookkeeping here; that is {@link MirrorChestSource}'s job for the fully offscreen case).
 */
public final class RealChestSource implements ChestSource {

    private final WorldServer world;
    private final PlayerZoneRecord zone;

    public RealChestSource(WorldServer world, PlayerZoneRecord zone) {
        this.world = world;
        this.zone = zone;
    }

    @Override
    public int available(Item item) {
        int total = 0;
        for (BlockPos p : zone.getRolePositions(ZoneRoles.CHEST)) {
            IInventory inv = invAt(p);
            if (inv == null) continue;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
            }
        }
        return total;
    }

    @Override
    public boolean consume(Item item) {
        for (BlockPos p : zone.getRolePositions(ZoneRoles.CHEST)) {
            IInventory inv = invAt(p);
            if (inv == null) continue;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.isEmpty() || s.getItem() != item) continue;
                s.shrink(1);
                inv.setInventorySlotContents(i, s);
                inv.markDirty();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    private IInventory invAt(BlockPos p) {
        if (!world.isBlockLoaded(p)) return null;
        TileEntity te = world.getTileEntity(p);
        return (te instanceof IInventory) ? (IInventory) te : null;
    }
}
