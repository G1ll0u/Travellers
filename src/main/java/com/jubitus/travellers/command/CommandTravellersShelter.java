package com.jubitus.travellers.command;

import com.jubitus.travellers.camp.build.BuildStep;
import com.jubitus.travellers.camp.build.IncrementalStructureBuilder;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.camp.CampsiteRecord;
import com.jubitus.travellers.camp.ShelterSlot;
import com.jubitus.travellers.camp.shelter.ShelterPlacer;
import com.jubitus.travellers.camp.shelter.ShelterTemplate;
import com.jubitus.travellers.camp.shelter.ShelterTemplateRegistry;
import com.jubitus.travellers.camp.shelter.TerrainSnapshot;
import net.minecraft.block.BlockColored;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;

import javax.annotation.Nullable;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Author and test traveller shelters.
 *   /travellersshelter create &lt;name&gt; "&lt;culture&gt;" &lt;NORTH|EAST|SOUTH|WEST&gt;
 *   /travellersshelter list | reload | test &lt;name&gt; | clear | import &lt;name&gt; [culture]
 */
public class CommandTravellersShelter extends CommandBase {

    private static final int SCAN_XZ = 8;
    private static final int SCAN_Y = 5;
    private static final int MAX_HEIGHT = 8;

    // Last debug test placements per player, for /clear.
    private static final Map<UUID, List<TerrainSnapshot>> LAST_TEST = new HashMap<>();

    @Override
    public String getName() {
        return "travellersshelter";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersshelter create <name> \"<culture>\" <NORTH|EAST|SOUTH|WEST> | list | reload | test <name> | clear | import <name> [culture]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new CommandException(getUsage(sender));
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create": executeCreate(sender, args); break;
            case "list": executeList(sender); break;
            case "reload":
                ShelterTemplateRegistry.reload();
                msg(sender, TextFormatting.GREEN, "Shelter templates reloaded.");
                break;
            case "test": executeTest(sender, args); break;
            case "clear": executeClear(sender); break;
            case "import": executeImport(sender, args); break;
            default: throw new CommandException(getUsage(sender));
        }
    }

    private void executeCreate(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Must be run by a player.");
        if (args.length < 4) throw new CommandException(getUsage(sender));
        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer world = player.getServerWorld();

        String name = args[1];
        String culture = args[2].replace("\"", "");
        EnumFacing entrance = parseFacing(args[3]);
        if (entrance == null) throw new CommandException("Facing must be NORTH, EAST, SOUTH or WEST.");

        BlockPos floor = findRedWoolFloor(world, player.getPosition());
        if (floor == null) {
            throw new CommandException("No 5x5 red-wool floor found nearby. Lay a flat 5x5 red wool square and build on top of it.");
        }

        int height = measureHeight(world, floor);
        if (height < 1) throw new CommandException("Nothing is built on the red-wool floor.");

        // Capture the structure that sits on top of the wool (wool itself is the anchor, not captured).
        // Template y=0 = wool.up(); ShelterPlacer always lands this layer at the ground surface Y,
        // so whatever's authored directly on the wool (bare floor, or a bed) sits at terrain level.
        BlockPos start = floor.up();

        Template template = new Template();
        template.takeBlocksFromWorld(world, start, new BlockPos(ShelterSlot.FOOTPRINT, height, ShelterSlot.FOOTPRINT), false, null);
        template.setAuthor(player.getName());

        ShelterTemplate shelter = new ShelterTemplate(name, culture, entrance, template);
        File cultureDir = new File(ShelterTemplateRegistry.sheltersDir(), shelter.culture);
        File file = new File(cultureDir, name + ".nbt");
        try {
            shelter.save(file);
        } catch (Exception e) {
            throw new CommandException("Failed to save shelter: " + e.getMessage());
        }
        ShelterTemplateRegistry.reload();

        msg(sender, TextFormatting.GREEN, "Saved shelter '" + name + "' (culture=" + shelter.culture
                + ", entrance=" + entrance + ", height=" + height + ") to " + file.getName());
    }

    private void executeList(ICommandSender sender) {
        Map<String, List<ShelterTemplate>> view = ShelterTemplateRegistry.view();
        if (view.isEmpty()) {
            msg(sender, TextFormatting.YELLOW, "No shelter templates installed.");
            return;
        }
        for (Map.Entry<String, List<ShelterTemplate>> e : view.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (ShelterTemplate t : e.getValue()) sb.append(t.name).append(' ');
            msg(sender, TextFormatting.AQUA, e.getKey() + ": " + sb.toString().trim());
        }
    }

    private void executeTest(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Must be run by a player.");
        if (args.length < 2) throw new CommandException("Usage: /travellersshelter test <name> [slotIndex|all]");
        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer world = player.getServerWorld();

        ShelterTemplate shelter = ShelterTemplateRegistry.find(null, args[1]);
        if (shelter == null) throw new CommandException("Unknown shelter template: " + args[1]);

        CampsiteRecord camp = CampsiteManager.get(world).nearest(world.provider.getDimension(), player.getPosition(), 256);
        if (camp == null || camp.slots.isEmpty()) {
            throw new CommandException("No campsite with computed slots within 256 blocks. Use /travellersdebug camp.");
        }

        boolean all = args.length >= 3 && args[2].equalsIgnoreCase("all");
        int slotIdx = (!all && args.length >= 3) ? parseInt(args[2]) : 0;
        if (!all && (slotIdx < 0 || slotIdx >= camp.slots.size())) {
            throw new CommandException("Slot index out of range (camp has " + camp.slots.size() + " slots).");
        }

        List<TerrainSnapshot> placedList = LAST_TEST.computeIfAbsent(player.getUniqueID(), k -> new java.util.ArrayList<>());
        // Place the firepit at the center on the first test placement, so the camp layout is visible.
        if (placedList.isEmpty()) {
            TerrainSnapshot fireSnap = com.jubitus.travellers.camp.CampFirepit.place(world, camp.firepitPos);
            if (fireSnap != null) placedList.add(fireSnap);
        }
        int placedCount = 0;
        if (all) {
            for (ShelterSlot slot : camp.slots) {
                placedList.add(placeShelterInstantly(world, slot, shelter));
                placedCount++;
            }
        } else {
            ShelterSlot slot = camp.slots.get(slotIdx);
            placedList.add(placeShelterInstantly(world, slot, shelter));
            placedCount = 1;
        }
        msg(sender, TextFormatting.GREEN, "Placed '" + shelter.name + "' x" + placedCount
                + " at campsite " + camp.center + ". /travellersshelter clear to restore.");
    }

    /** Debug-only: grades + places every step of the plan instantly (this command is for previewing a shelter, not testing the animated build). */
    private static TerrainSnapshot placeShelterInstantly(WorldServer world, ShelterSlot slot, ShelterTemplate shelter) {
        ShelterPlacer.BuildStart start = ShelterPlacer.startBuild(world, slot, shelter);
        for (BuildStep step : start.plan) IncrementalStructureBuilder.placeStep(world, step);
        return start.snapshot;
    }

    private void executeClear(ICommandSender sender) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Must be run by a player.");
        EntityPlayerMP player = (EntityPlayerMP) sender;
        List<TerrainSnapshot> placedList = LAST_TEST.remove(player.getUniqueID());
        if (placedList == null || placedList.isEmpty()) {
            msg(sender, TextFormatting.YELLOW, "No test placement to clear.");
            return;
        }
        // Restore in reverse placement order so overlapping snapshots come back cleanly.
        for (int i = placedList.size() - 1; i >= 0; i--) {
            ShelterPlacer.remove(player.getServerWorld(), placedList.get(i));
        }
        msg(sender, TextFormatting.GREEN, "Restored terrain at " + placedList.size() + " test placement(s).");
    }

    /**
     * Pastes an installed template on a fresh red-wool floor in front of the player, for in-game editing.
     * Mirrors {@code create}'s own convention (wool = anchor, structure starts at floor.up()) so the
     * result can be re-captured with /travellersshelter create once edited.
     */
    private void executeImport(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("Must be run by a player.");
        if (args.length < 2) throw new CommandException("Usage: /travellersshelter import <name> [culture]");
        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer world = player.getServerWorld();

        String culture = args.length >= 3 ? args[2].replace("\"", "") : null;
        ShelterTemplate shelter = ShelterTemplateRegistry.find(culture, args[1]);
        if (shelter == null) throw new CommandException("Unknown shelter template: " + args[1]);

        BlockPos floor = player.getPosition().offset(player.getHorizontalFacing(), 3);
        placeWoolFloor(world, floor);

        PlacementSettings settings = new PlacementSettings().setRotation(Rotation.NONE).setMirror(Mirror.NONE).setIgnoreEntities(false);
        shelter.template.addBlocksToWorld(world, floor.up(), settings);

        msg(sender, TextFormatting.GREEN, "Imported '" + shelter.name + "' (culture=" + shelter.culture
                + ", entrance=" + shelter.entranceFacing + ") at " + floor
                + ". Edit it, then /travellersshelter create " + shelter.name + " \"" + shelter.culture
                + "\" " + shelter.entranceFacing + " to re-save.");
    }

    private static void placeWoolFloor(WorldServer world, BlockPos minCorner) {
        IBlockState red = Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED);
        for (int dx = 0; dx < ShelterSlot.FOOTPRINT; dx++) {
            for (int dz = 0; dz < ShelterSlot.FOOTPRINT; dz++) {
                world.setBlockState(minCorner.add(dx, 0, dz), red, 3);
            }
        }
    }

    // --- helpers ---

    @Nullable
    private static EnumFacing parseFacing(String s) {
        switch (s.toUpperCase(Locale.ROOT)) {
            case "NORTH": return EnumFacing.NORTH;
            case "EAST": return EnumFacing.EAST;
            case "SOUTH": return EnumFacing.SOUTH;
            case "WEST": return EnumFacing.WEST;
            default: return null;
        }
    }

    private static boolean isRedWool(IBlockState st) {
        return st.getBlock() == Blocks.WOOL && st.getValue(BlockColored.COLOR) == EnumDyeColor.RED;
    }

    /**
     * Finds a flat 5×5 red-wool square near {@code around}. Returns the lowest-XZ corner at wool level,
     * choosing the level closest to the player's feet. Requires exactly a complete 5×5 (25 blocks).
     */
    @Nullable
    private static BlockPos findRedWoolFloor(WorldServer world, BlockPos around) {
        BlockPos best = null;
        int bestDy = Integer.MAX_VALUE;
        for (int dy = -SCAN_Y; dy <= SCAN_Y; dy++) {
            int y = around.getY() + dy;
            // Bounding box of red wool at this Y near the player.
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, count = 0;
            for (int dx = -SCAN_XZ; dx <= SCAN_XZ; dx++) {
                for (int dz = -SCAN_XZ; dz <= SCAN_XZ; dz++) {
                    BlockPos p = new BlockPos(around.getX() + dx, y, around.getZ() + dz);
                    if (!isRedWool(world.getBlockState(p))) continue;
                    minX = Math.min(minX, p.getX());
                    maxX = Math.max(maxX, p.getX());
                    minZ = Math.min(minZ, p.getZ());
                    maxZ = Math.max(maxZ, p.getZ());
                    count++;
                }
            }
            if (count != ShelterSlot.FOOTPRINT * ShelterSlot.FOOTPRINT) continue;
            if (maxX - minX != ShelterSlot.FOOTPRINT - 1 || maxZ - minZ != ShelterSlot.FOOTPRINT - 1) continue;
            if (Math.abs(dy) < bestDy) {
                bestDy = Math.abs(dy);
                best = new BlockPos(minX, y, minZ);
            }
        }
        return best;
    }

    /** Highest non-air layer above the wool floor, in [1..MAX_HEIGHT]. */
    private static int measureHeight(WorldServer world, BlockPos floor) {
        int top = 0;
        for (int dy = 1; dy <= MAX_HEIGHT; dy++) {
            boolean any = false;
            for (int dx = 0; dx < ShelterSlot.FOOTPRINT && !any; dx++) {
                for (int dz = 0; dz < ShelterSlot.FOOTPRINT && !any; dz++) {
                    BlockPos p = floor.add(dx, dy, dz);
                    if (!world.isAirBlock(p)) any = true;
                }
            }
            if (any) top = dy;
        }
        return top;
    }

    private static void msg(ICommandSender sender, TextFormatting color, String text) {
        sender.sendMessage(new TextComponentString(color + text));
    }
}
