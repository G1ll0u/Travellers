package com.jubitus.travellers.command;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.LandingSpots;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.unit.equipment.SummonLoadout;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Test command: hired workers spawned around a player, rolled like a natural worker (culture of the
 * nearest village, gear, shield, horse) with optional overrides. They have no home village, so they
 * never count in any settlement's population.
 */
public class CommandSummonWorkers extends CommandBase {

    private static final int MAX_COUNT = 256;
    private static final int MIN_RADIUS = 3;
    private static final int BASE_MAX_RADIUS = 8;
    private static final int CAP_MAX_RADIUS = 16;
    private static final int PLACE_TRIES = 12;

    private static final String KEY = "commands.travellers.summonworkers.";

    @Override
    public String getName() {
        return "summonworkers";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return KEY + "usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return sender.canUseCommand(this.getRequiredPermissionLevel(), this.getName());
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) throw new WrongUsageException(getUsage(sender));

        int count = parseInt(args[0], 1, MAX_COUNT);
        String playerName = null;
        SummonLoadout.Gear gear = SummonLoadout.Gear.NATURAL;
        SummonLoadout.Weapon weapon = SummonLoadout.Weapon.NATURAL;
        SummonLoadout.Choice shield = SummonLoadout.Choice.NATURAL;
        SummonLoadout.Choice mount = SummonLoadout.Choice.NATURAL;
        String cultureId = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            int eq = arg.indexOf('=');
            if (eq < 0) {
                if (playerName != null) throw new WrongUsageException(getUsage(sender));
                playerName = arg;
                continue;
            }
            String key = arg.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = arg.substring(eq + 1);
            switch (key) {
                case "gear":
                    gear = parseEnum(SummonLoadout.Gear.class, value, arg);
                    break;
                case "weapon":
                    weapon = parseEnum(SummonLoadout.Weapon.class, value, arg);
                    break;
                case "shield":
                    shield = parseEnum(SummonLoadout.Choice.class, value, arg);
                    break;
                case "mount":
                    mount = parseEnum(SummonLoadout.Choice.class, value, arg);
                    break;
                case "culture":
                    if (CultureRegistry.get(value) == null) throw new CommandException(KEY + "badCulture", value);
                    cultureId = value;
                    break;
                default:
                    throw new CommandException(KEY + "badOption", arg);
            }
        }
        if (weapon == SummonLoadout.Weapon.BOW && shield == SummonLoadout.Choice.YES) {
            throw new CommandException(KEY + "bowShield");
        }

        EntityPlayerMP target = playerName != null ? getPlayer(server, sender, playerName) : getCommandSenderAsPlayer(sender);
        WorldServer ws = target.getServerWorld();
        Random rand = ws.rand;

        if (mount == SummonLoadout.Choice.YES && !TravellersConfig.MOUNTS.enabled) {
            sender.sendMessage(new TextComponentTranslation(KEY + "mountsDisabled"));
        }

        int maxRadius = Math.min(CAP_MAX_RADIUS, BASE_MAX_RADIUS + count / 8);
        BlockPos center = new BlockPos(target.posX, target.getEntityBoundingBox().minY, target.posZ);
        Set<BlockPos> taken = new HashSet<>();
        int spawned = 0;
        int mounted = 0;

        for (int i = 0; i < count; i++) {
            EntityTravellerWorker worker = new EntityTravellerWorker(ws);
            worker.setLocationAndAngles(target.posX, center.getY(), target.posZ, 0.0F, 0.0F);
            BlockPos spot = findSpot(ws, worker, center, maxRadius, taken, rand);
            if (spot == null) continue;
            taken.add(spot);

            worker.setLocationAndAngles(spot.getX() + 0.5D, LandingSpots.feetY(ws, spot), spot.getZ() + 0.5D,
                    rand.nextFloat() * 360.0F, 0.0F);
            worker.onInitialSpawn(ws.getDifficultyForLocation(spot),
                    cultureId != null ? new EntityTraveller.SpawnData(cultureId) : null);
            CultureDef culture = CultureRegistry.get(worker.getCultureId());
            SummonLoadout.apply(worker, culture, rand, gear, weapon, shield);

            worker.setHomeVillage(null);
            worker.hireAsFollower(target);
            worker.setFollowOrderActive(true);
            worker.clearEscortLeader();

            if (!ws.spawnEntity(worker)) continue;
            spawned++;

            boolean wantsHorse = mount == SummonLoadout.Choice.YES
                    || (mount == SummonLoadout.Choice.NATURAL && TravellerMountSupport.rollGroupMount(rand, "WORKER"));
            if (wantsHorse && TravellerMountSupport.bondNewMount(worker)) mounted++;
        }

        if (spawned <= 0) throw new CommandException(KEY + "none", target.getName());
        sender.sendMessage(new TextComponentTranslation(KEY + "success", spawned, mounted, target.getName()));
        if (spawned < count) {
            sender.sendMessage(new TextComponentTranslation(KEY + "partial", count - spawned));
        }
    }

    /** A safe free cell in a ring around the player, never in a Millénaire building; null when none is found. */
    @Nullable
    private static BlockPos findSpot(WorldServer ws, EntityTravellerWorker body, BlockPos center, int maxRadius,
                                     Set<BlockPos> taken, Random rand) {
        for (int t = 0; t < PLACE_TRIES; t++) {
            double angle = rand.nextDouble() * Math.PI * 2.0D;
            double dist = MIN_RADIUS + rand.nextDouble() * (maxRadius - MIN_RADIUS);
            BlockPos aim = new BlockPos(center.getX() + MathHelper.floor(Math.cos(angle) * dist), center.getY(),
                    center.getZ() + MathHelper.floor(Math.sin(angle) * dist));
            BlockPos spot = LandingSpots.nearest(ws, aim, 2, 4,
                    p -> !taken.contains(p) && LandingSpots.isSafe(ws, p, body, false, false));
            if (spot != null) return spot;
        }
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String arg) throws CommandException {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CommandException(KEY + "badOption", arg);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length < 2) return java.util.Collections.emptyList();
        List<String> options = new ArrayList<>();
        for (SummonLoadout.Gear g : SummonLoadout.Gear.values()) options.add("gear=" + lower(g));
        for (SummonLoadout.Weapon w : SummonLoadout.Weapon.values()) options.add("weapon=" + lower(w));
        for (SummonLoadout.Choice c : SummonLoadout.Choice.values()) {
            options.add("shield=" + lower(c));
            options.add("mount=" + lower(c));
        }
        for (CultureDef c : CultureRegistry.all()) options.add("culture=" + c.id);
        if (args.length == 2) java.util.Collections.addAll(options, server.getOnlinePlayerNames());
        return getListOfStringsMatchingLastWord(args, options);
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}
