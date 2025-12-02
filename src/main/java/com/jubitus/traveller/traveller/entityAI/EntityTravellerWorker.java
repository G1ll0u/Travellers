package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.Travellers;
import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.pathing.TravellerChunkManager;
import com.jubitus.traveller.traveller.travel.TravelCommon;
import com.jubitus.traveller.traveller.utils.debug.TravellersGuiIds;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIFollow;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemStackHandler;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.UUID;

public class EntityTravellerWorker extends EntityTraveller {

    public enum WorkerMode {
        IDLE,
        TO_VILLAGE,
        RETURNING
    }

    @Nullable
    private BlockPos jobVillageCenter = null;
    private WorkerMode mode = WorkerMode.IDLE;
    private boolean jobDelivered = false;

    // "Employer" = player who hired this worker for the current/last job
    @Nullable
    private UUID employerId;
    @Nullable
    private BlockPos employerReturnPos;   // where to go back (captured when job starts)

    // When true, base EntityTraveller will NOT auto-plan routes
    private boolean routeLocked = false;

    public EntityTravellerWorker(World worldIn) {
        super(worldIn);
        this.enablePersistence();

        // Don’t use generic “roam inside village” AI for workers
        removeTaskOfType(EntityAIRoamInsideVillage.class);

        // Replace travel AI with worker-specific step config
        removeTaskOfType(EntityAITravel.class);
        removeTaskOfType(EntityAIFollowTraveller.class);

        this.aiTravel = new EntityAITravel(
                this,
                TravellersModConfig.movementSpeedWhileTravel,
                TravellersModConfig.workerStepBlocks   // add this to config
        );
        this.tasks.addTask(16, aiTravel);
    }

    // Base hook: auto-planner will skip this entity when true
    @Override
    protected boolean isRouteLocked() {
        return routeLocked;
    }

    // called by server when player confirms job in GUI
    public void startVillageDeliveryJob(EntityPlayer employer, BlockPos villageCenter) {
        this.employerId = employer.getUniqueID();
        this.employerReturnPos = employer.getPosition();
        this.jobVillageCenter = villageCenter;
        this.mode = WorkerMode.TO_VILLAGE;
        this.jobDelivered = false;

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;

        // Route: go to village, then back to employer
        tc.route.add(villageCenter);
        if (employerReturnPos != null) {
            tc.route.add(employerReturnPos);
        }

        tc.routeIndex = 0;
        tc.setTargetVillage(villageCenter);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.routeLocked = true;
        this.getNavigator().clearPath();
    }


    /**
     * Called when we have finished donating to the target village.
     * After this, worker will travel back to employerReturnPos.
     */
    public void onJobDelivered() {
        if (this.jobDelivered) return; // guard
        this.jobDelivered = true;
        this.mode = WorkerMode.RETURNING;

        // Make sure we head back toward the employer return position
        if (employerReturnPos != null) {
            TravelCommon tc = this.getTravelCommon();
            tc.route.clear();
            tc.routeIndex = -1;
            tc.route.add(employerReturnPos);
            tc.routeIndex = 0;
            tc.setTargetVillage(employerReturnPos);
            tc.setCurrentWaypoint(null);
            tc.setReadyToDepart(false);
            tc.setDepartCooldownTicks(0);
            this.getNavigator().clearPath();
        }
    }

    /**
     * Final clean up when worker has come back to the employer.
     * NOTE: we keep routeLocked = true so the worker will NOT auto-roam anymore.
     */
    private void finishJob() {
        this.mode = WorkerMode.IDLE;
        this.jobVillageCenter = null;
        this.jobDelivered = false;
        this.routeLocked = false;  // we *also* disable auto plan via shouldAutoPlanRoute()

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.setTargetVillage(null);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }


    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();

        if (world.isRemote) return;

        TravelCommon tc = this.getTravelCommon();

        // 1) Going TO the village
        if (mode == WorkerMode.TO_VILLAGE && jobVillageCenter != null && !jobDelivered) {
            int r = tc.arrivalRadiusBlocks(jobVillageCenter);
            if (this.getDistanceSqToCenter(jobVillageCenter) <= (double) (r * r)) {
                // Arrived: donate and switch to RETURNING
                donateInventoryToVillage(jobVillageCenter);
                this.jobDelivered = true;
                this.mode = WorkerMode.RETURNING;

                // Now head back near employer
                tc.route.clear();
                tc.routeIndex = -1;
                if (employerReturnPos != null) {
                    tc.route.add(employerReturnPos);
                    tc.routeIndex = 0;
                    tc.setTargetVillage(employerReturnPos);
                } else {
                    tc.setTargetVillage(null);
                }
                tc.setCurrentWaypoint(null);
                tc.setReadyToDepart(false);
                tc.setDepartCooldownTicks(0);
                this.getNavigator().clearPath();
            }
        }

        // 2) Returning: when close to employer, announce & finish
        if (mode == WorkerMode.RETURNING && jobDelivered) {
            EntityPlayer employer = getEmployer();
            if (employer != null) {
                if (this.getDistanceSq(employer) <= 4.0D * 4.0D) {
                    employer.sendMessage(new TextComponentString(
                            TextFormatting.WHITE + "I did what I had to do. " +
                                    "Any other next thing?"
                    ));
                    finishJob();
                }
            } else if (employerReturnPos != null) {
                // Fallback: use stored position
                if (this.getDistanceSqToCenter(employerReturnPos) <= 4.0D * 4.0D) {
                    finishJob();
                }
            }
        }
    }


    @Nullable
    private EntityPlayer getEmployerPlayer() {
        if (employerId == null) return null;
        return this.world.getPlayerEntityByUUID(employerId);
    }

    // Use worker stats if you want them different
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH)
                .setBaseValue(TravellersModConfig.workerMaxHealth);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE)
                .setBaseValue(TravellersModConfig.workerFollowRange);
    }

    // NBT: persist employer + job so restart doesn’t lose the mission
    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);

        if (employerId != null) nbt.setUniqueId("EmployerId", employerId);
        if (employerReturnPos != null) {
            nbt.setInteger("EmpX", employerReturnPos.getX());
            nbt.setInteger("EmpY", employerReturnPos.getY());
            nbt.setInteger("EmpZ", employerReturnPos.getZ());
        }
        if (jobVillageCenter != null) {
            nbt.setInteger("JobVX", jobVillageCenter.getX());
            nbt.setInteger("JobVY", jobVillageCenter.getY());
            nbt.setInteger("JobVZ", jobVillageCenter.getZ());
        }
        nbt.setInteger("WorkerMode", mode.ordinal());
        nbt.setBoolean("JobDelivered", jobDelivered);
        nbt.setBoolean("RouteLocked", routeLocked);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);

        employerId = nbt.hasUniqueId("EmployerId") ? nbt.getUniqueId("EmployerId") : null;
        if (nbt.hasKey("EmpX")) {
            employerReturnPos = new BlockPos(
                    nbt.getInteger("EmpX"),
                    nbt.getInteger("EmpY"),
                    nbt.getInteger("EmpZ"));
        } else {
            employerReturnPos = null;
        }
        if (nbt.hasKey("JobVX")) {
            jobVillageCenter = new BlockPos(
                    nbt.getInteger("JobVX"),
                    nbt.getInteger("JobVY"),
                    nbt.getInteger("JobVZ"));
        } else {
            jobVillageCenter = null;
        }
        int m = nbt.getInteger("WorkerMode");
        mode = (m >= 0 && m < WorkerMode.values().length) ? WorkerMode.values()[m] : WorkerMode.IDLE;
        jobDelivered = nbt.getBoolean("JobDelivered");
        routeLocked = nbt.getBoolean("RouteLocked");
    }

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;

        if (this.mode != WorkerMode.IDLE) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "I'm already on a job. Wait for me to come back."
            ));
            return true;
        }

        player.openGui(
                Travellers.INSTANCE,               // your @Mod instance
                TravellersGuiIds.WORKER,           // defined in your gui IDs
                world,
                this.getEntityId(),               // we'll read this as x on the server
                0, 0
        );
        return true;
    }

    /**
     * Donate backpack contents to the target village & then mark job delivered.
     * Call this from your worker-specific delivery AI when at the town hall.
     */
    public void donateInventoryToVillage(BlockPos villageCenter) {
        World world = this.world;
        if (world.isRemote) return;

        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null) return;

        Building th = MillVillageIndex.getTownHallBuilding(wd, villageCenter);
        if (th == null) return;

        ItemStackHandler inv = this.getBackpack();

        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            int meta = stack.getMetadata();
            int count = stack.getCount();

            int stored = th.storeGoods(item, meta, count);
            if (stored > 0) {
                inv.extractItem(slot, stored, false);
            }
        }

        // Mark the job as delivered so onLivingUpdate will handle return
        onJobDelivered();
    }
    @Nullable
    private EntityPlayer getEmployer() {
        if (employerId == null) return null;
        return this.world.getPlayerEntityByUUID(employerId);
    }
}
