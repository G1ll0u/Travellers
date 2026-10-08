package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Random;

/** Debug: spawn a virtual traveller/raider/worker at a position picked on the population map. */
public class MsgSpawnEntityAtPos implements IMessage {

    public static final int TYPE_TRAVELLER = 0;
    public static final int TYPE_WORKER = 1;
    public static final int TYPE_RAIDER = 2;

    private int x, z;
    private int type;

    public MsgSpawnEntityAtPos() {}

    public MsgSpawnEntityAtPos(int x, int z, int type) {
        this.x = x;
        this.z = z;
        this.type = type;
    }

    @Override public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        z = buf.readInt();
        type = buf.readByte();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeByte(type);
    }

    public static class Handler implements IMessageHandler<MsgSpawnEntityAtPos, IMessage> {
        @Override
        public IMessage onMessage(MsgSpawnEntityAtPos msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            if (!com.jubitus.travellers.debug.DebugAccess.allowed(player)) {
                return null;
            }

            player.getServerWorld().addScheduledTask(() -> {
                WorldServer ws = player.getServerWorld();
                int y = ws.getHeight(msg.x, msg.z);
                double px = msg.x + 0.5, py = y, pz = msg.z + 0.5;

                String entityClass;
                EntityTraveller e;
                switch (msg.type) {
                    case TYPE_WORKER:
                        e = new EntityTravellerWorker(ws);
                        entityClass = "WORKER";
                        break;
                    case TYPE_RAIDER:
                        e = new EntityTravellerRaider(ws);
                        entityClass = "RAIDER";
                        break;
                    default:
                        e = new EntityTraveller(ws);
                        entityClass = "TRAVELLER";
                        break;
                }
                e.setPosition(px, py, pz);

                Random rand = ws.rand;
                String cultureId = com.jubitus.travellers.spawn.SpawnCulture.pickNear(ws, new BlockPos(msg.x, y, msg.z), rand);
                DifficultyInstance diff = new DifficultyInstance(ws.getDifficulty(), ws.getWorldTime(), 0, 0.5f);
                e.onInitialSpawn(diff, cultureId != null ? new EntityTraveller.SpawnData(cultureId) : null);

                NBTTagCompound nbt = new NBTTagCompound();
                e.writeToNBT(nbt);

                VirtualTravellerRecord r = new VirtualTravellerRecord(
                        e.getUniqueID(), px, py, pz, 0f, ws.provider.getDimension(), nbt);
                r.entityClass = entityClass;
                r.name = e.getName();
                r.health = e.getMaxHealth();
                if (msg.type == TYPE_WORKER) {
                    r.goal = new BlockPos(px, py, pz);
                }

                VirtualTravellerManager.get(ws).addRecord(r);
                e.setDead();

                player.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + "Spawned virtual " + entityClass.toLowerCase(java.util.Locale.ROOT) + " at "
                        + msg.x + "," + y + "," + msg.z + "."));
            });

            return null;
        }
    }
}
