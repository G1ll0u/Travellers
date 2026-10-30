package com.jubitus.travellers.village.net;

import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.village.TravellerVillageRelations;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import org.millenaire.common.village.Building;

import javax.annotation.Nullable;

/** What the map tooltip shows about a village or lone building; names and icon are resolved on the client. */
public final class SettlementMapInfo {

    public String cultureKey = "";
    public String typeKey = "";
    public boolean lone;
    public int population;
    public int reputation;
    public boolean underAttack;
    /** Name of the settlement it is raiding, or empty. */
    public String raiding = "";
    /** Name of the player controlling it, or empty. */
    public String controller = "";
    /** Declared an enemy of the viewer's henchmen. */
    public boolean enemy;

    public static SettlementMapInfo of(WorldServer world, EntityPlayerMP viewer, MillSettlements.Settlement s) {
        SettlementMapInfo i = new SettlementMapInfo();
        i.cultureKey = s.cultureKey != null ? s.cultureKey : "";
        i.typeKey = s.typeKey != null ? s.typeKey : "";
        i.lone = !s.isVillage;
        i.population = s.population(world);
        i.enemy = TravellerVillageRelations.get(world).isEnemy(viewer.getUniqueID(), world.provider.getDimension(), s.center);
        Building th = s.townHall(world);
        if (th != null) {
            i.reputation = th.getReputation(viewer);
            i.underAttack = th.underAttack;
            if (th.raidTarget != null) {
                i.raiding = MillSettlements.nameOf(world, new BlockPos(th.raidTarget.getiX(), th.raidTarget.getiY(), th.raidTarget.getiZ()));
            }
            if (th.controlledByName != null) i.controller = th.controlledByName;
        }
        return i;
    }

    public void write(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, cultureKey);
        ByteBufUtils.writeUTF8String(buf, typeKey);
        buf.writeBoolean(lone);
        buf.writeInt(population);
        buf.writeInt(reputation);
        buf.writeBoolean(underAttack);
        ByteBufUtils.writeUTF8String(buf, raiding);
        ByteBufUtils.writeUTF8String(buf, controller);
        buf.writeBoolean(enemy);
    }

    public static SettlementMapInfo read(ByteBuf buf) {
        SettlementMapInfo i = new SettlementMapInfo();
        i.cultureKey = ByteBufUtils.readUTF8String(buf);
        i.typeKey = ByteBufUtils.readUTF8String(buf);
        i.lone = buf.readBoolean();
        i.population = buf.readInt();
        i.reputation = buf.readInt();
        i.underAttack = buf.readBoolean();
        i.raiding = ByteBufUtils.readUTF8String(buf);
        i.controller = ByteBufUtils.readUTF8String(buf);
        i.enemy = buf.readBoolean();
        return i;
    }

    @Nullable
    public static SettlementMapInfo readNullable(ByteBuf buf) {
        return buf.readBoolean() ? read(buf) : null;
    }

    public static void writeNullable(ByteBuf buf, @Nullable SettlementMapInfo i) {
        buf.writeBoolean(i != null);
        if (i != null) i.write(buf);
    }
}
