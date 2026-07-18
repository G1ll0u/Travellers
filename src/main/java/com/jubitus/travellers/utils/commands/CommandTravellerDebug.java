package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import com.jubitus.travellers.entity.culture.VoiceSet;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Loader;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class CommandTravellerDebug extends CommandBase {

    @Override
    public String getName() {
        return "travellersdebug";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersdebug [reset [workers]|camp [here|recompute|join <n> [culture] [TRAVELLER|RAIDER]|leave|reset [all]]|battle [list|info <n>|end <n>|spawnfight [x z]|loot]|map resweep [all]|map resweep cancel|sound stress|sound rapidfire|cultures]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 2 && args[0].equalsIgnoreCase("reset") && args[1].equalsIgnoreCase("workers")) {
            executeResetWorkers(server, sender);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reset")) {
            executeReset(server, sender);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("camp")) {
            executeCampList(server, sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("battle")) {
            executeBattle(sender, args);
            return;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("map") && args[1].equalsIgnoreCase("resweep")
                && args[2].equalsIgnoreCase("cancel")) {
            executeMapResweepCancel(server, sender);
            return;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("map") && args[1].equalsIgnoreCase("resweep")) {
            boolean allDimensions = args.length >= 3 && args[2].equalsIgnoreCase("all");
            executeMapResweep(server, sender, allDimensions);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("sound")) {
            String sub = args.length >= 2 ? args[1].toLowerCase() : "stress";
            executeSoundDebug(server, sender, sub);
            return;
        }

        // Default: culture dump
        try {
            CultureRegistry.bootstrapAllMillenaireCultures();
        } catch (Exception ex) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Warning: error bootstrapping cultures: " + ex.getMessage()));
        }

        File configDir = Loader.instance().getConfigDir();
        if (configDir == null) {
            throw new CommandException("Config directory is null; cannot write debug file.");
        }

        File outFile = new File(configDir, "traveller_culture_dump.txt");

        try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {

            dumpEverything(out);

        } catch (IOException e) {
            throw new CommandException("Failed to write debug file: " + e.getMessage());
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Traveller debug exported to: " +
                        TextFormatting.YELLOW + outFile.getAbsolutePath()));
    }

    // --- Battle debug: list / inspect / force-end / spawn a test fight ---

    private void executeBattle(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Server world required.");
        WorldServer ws = (WorldServer) w;
        com.jubitus.travellers.utils.world.battle.VirtualBattleManager mgr =
                com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get(ws);
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        if (sub.equals("spawnfight")) {
            int x, z;
            if (args.length >= 4) {
                x = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } else {
                x = (int) sender.getPositionVector().x + 100;
                z = (int) sender.getPositionVector().z;
            }
            spawnTestFight(ws, x, z, sender);
            return;
        }

        if (sub.equals("loot")) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Pending loot drops: " + mgr.getPendingLootCount()));
            return;
        }

        java.util.List<com.jubitus.travellers.utils.world.battle.BattleRecord> list = new java.util.ArrayList<>();
        for (com.jubitus.travellers.utils.world.battle.BattleRecord b : mgr.getBattlesView()) list.add(b);

        if (sub.equals("info") || sub.equals("end")) {
            int idx = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
            if (idx < 0 || idx >= list.size()) throw new CommandException("No battle #" + idx
                    + " (" + list.size() + " active).");
            com.jubitus.travellers.utils.world.battle.BattleRecord b = list.get(idx);
            if (sub.equals("end")) {
                mgr.debugEndBattle(ws, b);
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Battle #" + idx + " ended."));
            } else {
                sender.sendMessage(new TextComponentString(describeBattle(ws, b, true)));
            }
            return;
        }

        // list (default)
        if (list.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No active battles."));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "#" + i + " " + describeBattle(ws, list.get(i), false)));
        }
    }

    private static String describeBattle(WorldServer ws, com.jubitus.travellers.utils.world.battle.BattleRecord b,
                                         boolean detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("Battle @ ").append(b.center.getX()).append(",").append(b.center.getZ())
                .append(" sideA=").append(b.countAlive(0))
                .append(" sideB=").append(b.countAlive(1))
                .append(" age=").append((ws.getTotalWorldTime() - b.startTick) / 20).append("s");
        if (b.campAnchorId != null) sb.append(" [camp assault]");
        if (detailed) {
            VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
            for (com.jubitus.travellers.utils.world.battle.BattleParticipant p : b.participants.values()) {
                com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord r =
                        vtm.getRecord(p.uuid);
                sb.append("\n  [").append(p.side == 0 ? "A" : "B").append("] ")
                        .append(p.groupKey)
                        .append(p.isGroupLeader ? " LEADER" : "")
                        .append(p.alive ? "" : " DEAD")
                        .append(r != null
                                ? String.format(" hp=%.1f @%d,%d (%s)", r.getHealthResolved(),
                                        (int) r.posX, (int) r.posZ, r.getCombatProfile())
                                : (p.alive ? " [loaded]" : ""));
            }
        }
        return sb.toString();
    }

    /** Spawns two hostile virtual groups ~30 blocks apart so a battle starts on the next detection pass. */
    private static void spawnTestFight(WorldServer ws, int x, int z, ICommandSender sender) {
        VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
        net.minecraft.world.DifficultyInstance diff = new net.minecraft.world.DifficultyInstance(
                ws.getDifficulty(), ws.getWorldTime(), 0, 0.5f);
        int y = ws.getHeight(x, z);

        for (int i = 0; i < 4; i++) {
            spawnTestRecord(ws, vtm, diff, false, x - 15, y, z + i * 2);
            spawnTestRecord(ws, vtm, diff, true, x + 15, y, z + i * 2);
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                + "Spawned 4 virtual travellers vs 4 virtual raiders near " + x + "," + z + "."));
    }

    private static void spawnTestRecord(WorldServer ws, VirtualTravellerManager vtm,
                                        net.minecraft.world.DifficultyInstance diff,
                                        boolean raider, int x, int y, int z) {
        EntityTraveller e = raider
                ? new com.jubitus.travellers.entity.travellers.EntityTravellerRaider(ws)
                : new EntityTraveller(ws);
        e.setPosition(x + 0.5, y, z + 0.5);
        e.onInitialSpawn(diff, null);
        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        e.writeToNBT(nbt);
        com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord r =
                new com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord(
                        e.getUniqueID(), x + 0.5, y, z + 0.5, 0f, ws.provider.getDimension(), nbt);
        r.entityClass = raider ? "RAIDER" : "TRAVELLER";
        r.name = e.getName();
        r.health = e.getMaxHealth();
        vtm.addRecord(r);
        e.setDead();
    }

    // --- Reset: wipe all virtual travellers/raiders and kill loaded ones (camps untouched, see "camp reset") ---

    private void executeReset(MinecraftServer server, ICommandSender sender) {
        int virtualRemoved = 0;
        int loadedKilled = 0;

        for (WorldServer ws : server.worlds) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
            virtualRemoved += mgr.clearByClass("TRAVELLER", "RAIDER");

            for (Entity e : new java.util.ArrayList<>(ws.loadedEntityList)) {
                if (e instanceof EntityTravellerWorker) continue;
                if (e instanceof EntityTraveller && !e.isDead) {
                    e.setDead();
                    loadedKilled++;
                }
            }
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Reset complete: " +
                TextFormatting.YELLOW + virtualRemoved + TextFormatting.GREEN + " virtual records removed, " +
                TextFormatting.YELLOW + loadedKilled + TextFormatting.GREEN + " loaded entities killed." +
                TextFormatting.GRAY + " Workers and campsites untouched (use 'camp reset' for camps)."));
    }

    // --- Reset workers: wipe non-hired virtual/loaded workers, hired workers are preserved ---

    private void executeResetWorkers(MinecraftServer server, ICommandSender sender) {
        int virtualRemoved = 0;
        int loadedKilled = 0;

        for (WorldServer ws : server.worlds) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
            virtualRemoved += mgr.clearWorkersExceptHired();

            for (Entity e : new java.util.ArrayList<>(ws.loadedEntityList)) {
                if (!(e instanceof EntityTravellerWorker) || e.isDead) continue;
                EntityTravellerWorker worker = (EntityTravellerWorker) e;
                if (worker.isHiredFollower()) continue;
                com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager.INSTANCE
                        .untrackChunkloader(worker);
                worker.setDead();
                loadedKilled++;
            }
        }

        sender.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Worker reset complete: " +
                TextFormatting.YELLOW + virtualRemoved + TextFormatting.GREEN + " virtual records removed, " +
                TextFormatting.YELLOW + loadedKilled + TextFormatting.GREEN + " loaded workers killed." +
                TextFormatting.GRAY + " Hired workers preserved."));
    }

    // --- Camp listing: show campsites near the player ---

    private void executeCampList(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            boolean deleteAll = args.length >= 3 && args[2].equalsIgnoreCase("all");
            executeCampReset(server, sender, deleteAll);
            return;
        }

        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Must be run by a player."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer ws = player.getServerWorld();
        com.jubitus.travellers.utils.world.camp.CampsiteManager mgr =
                com.jubitus.travellers.utils.world.camp.CampsiteManager.get(ws);
        net.minecraft.util.math.BlockPos origin = player.getPosition();
        int dim = ws.provider.getDimension();

        if (args.length >= 2 && args[1].equalsIgnoreCase("here")) {
            com.jubitus.travellers.utils.world.camp.CampsiteRecord rec = mgr.forceCreateAt(ws, origin);
            if (rec == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED
                        + "Could not create a campsite here (bad ground or too close to another)."));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Campsite created at "
                        + rec.center + " with " + (rec.slotsComputed ? rec.slots.size() : "?") + " slots."));
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("recompute")) {
            int n = mgr.recomputeNear(ws, origin, 256);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Recomputed slots for " + n + " nearby campsite(s)."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("join")) {
            com.jubitus.travellers.utils.world.camp.CampsiteRecord rec = mgr.nearest(dim, origin, 256);
            if (rec == null || !rec.slotsComputed) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "No campsite with slots within 256b."));
                return;
            }
            int count = 1;
            try { if (args.length >= 3) count = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
            String culture = args.length >= 4 ? args[3] : "default";
            String faction = args.length >= 5 ? args[4].toUpperCase(java.util.Locale.ROOT) : "TRAVELLER";
            long now = ws.getTotalWorldTime();
            long depart = now + 20L * 60L * 60L; // ~1h, stays put for testing
            int placed = 0;
            for (int i = 0; i < count; i++) {
                int slot = mgr.claim(rec, java.util.UUID.randomUUID(), faction, culture, now, depart);
                if (slot < 0) break;
                placed++;
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Claimed " + placed + " slot(s) as "
                    + faction + " (culture=" + culture + ") at " + rec.center
                    + ". Shelters build on the next camp tick if a template is installed."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("leave")) {
            com.jubitus.travellers.utils.world.camp.CampsiteRecord rec = mgr.nearest(dim, origin, 256);
            if (rec == null) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "No campsite within 256b."));
                return;
            }
            long now = ws.getTotalWorldTime();
            int n = rec.occupants.size();
            for (com.jubitus.travellers.utils.world.camp.CampOccupant o : rec.occupants.values()) o.departTick = now;
            mgr.markDirty();
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Forced " + n
                    + " occupant(s) to leave; camp tears down on the next camp tick."));
            return;
        }

        java.util.List<com.jubitus.travellers.utils.world.camp.CampsiteRecord> near = new java.util.ArrayList<>();
        for (com.jubitus.travellers.utils.world.camp.CampsiteRecord r : mgr.getAll()) {
            if (r.dimension == dim && r.center.distanceSq(origin) <= 512 * 512) near.add(r);
        }
        near.sort(java.util.Comparator.comparingDouble(r -> r.center.distanceSq(origin)));

        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Campsites within 512b: "
                + TextFormatting.YELLOW + near.size() + TextFormatting.GRAY + " (total " + mgr.getAll().size() + ")"));
        int shown = 0;
        for (com.jubitus.travellers.utils.world.camp.CampsiteRecord r : near) {
            if (shown++ >= 10) break;
            int dist = (int) Math.sqrt(r.center.distanceSq(origin));
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "  " + r.center.getX() + "," + r.center.getY()
                    + "," + r.center.getZ() + TextFormatting.GRAY + " dist=" + dist + " slots="
                    + (r.slotsComputed ? r.slots.size() : "?") ));
        }
    }

    /**
     * Full physical camp reset across every dimension: restores all campsite terrain to its
     * pre-camp state (paced across ticks by {@link com.jubitus.travellers.utils.world.camp.CampResetCoordinator},
     * no player presence required). Plain "camp reset" keeps campsite records/slots so they don't
     * need rediscovery; "camp reset all" additionally deletes every campsite so the world starts
     * from zero (rediscovered from scratch as chunks load again). Progress is reported to
     * {@code sender} periodically until each dimension's job finishes.
     */
    private void executeCampReset(MinecraftServer server, ICommandSender sender, boolean deleteAll) {
        int alreadyRunning = 0;
        for (WorldServer ws : server.worlds) {
            if (com.jubitus.travellers.utils.world.camp.CampResetCoordinator.INSTANCE.isActive(ws.provider.getDimension())) {
                alreadyRunning++;
                continue;
            }
            com.jubitus.travellers.utils.world.camp.CampResetCoordinator.INSTANCE.start(ws, sender, deleteAll);
        }
        if (alreadyRunning > 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Camp reset already running in " + alreadyRunning + " dimension(s); skipped."));
        }
    }

    /**
     * On-demand worldmap correction for tiles {@code MapLoadedChunkResampler} can never reach
     * automatically (nobody has visited that chunk since it was first captured, e.g. deep in a
     * bulk-pregenerated world). Plain "map resweep" only processes the sender's current dimension;
     * "map resweep all" processes every loaded dimension. Progress is reported to {@code sender}
     * periodically until each dimension's job finishes.
     */
    private void executeMapResweep(MinecraftServer server, ICommandSender sender, boolean allDimensions) {
        java.util.List<WorldServer> targets = new java.util.ArrayList<>();
        if (allDimensions) {
            targets.addAll(java.util.Arrays.asList(server.worlds));
        } else if (sender instanceof EntityPlayerMP) {
            targets.add(((EntityPlayerMP) sender).getServerWorld());
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Must be run by a player, or use 'map resweep all' to target every dimension."));
            return;
        }

        int alreadyRunning = 0;
        for (WorldServer ws : targets) {
            if (com.jubitus.travellers.server.map.MapResweepCoordinator.INSTANCE.isActive(ws.provider.getDimension())) {
                alreadyRunning++;
                continue;
            }
            com.jubitus.travellers.server.map.MapResweepCoordinator.INSTANCE.start(ws, sender);
        }
        if (alreadyRunning > 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Map resweep already running in " + alreadyRunning + " dimension(s); skipped."));
        }
    }

    /**
     * Stops every running "map resweep" job across every dimension, wherever it was started from
     * (player or server console) and regardless of the sender's current dimension - there's no
     * legitimate reason to want a bulk correction job left running once you've asked to cancel it.
     */
    private void executeMapResweepCancel(MinecraftServer server, ICommandSender sender) {
        int cancelled = 0;
        for (WorldServer ws : server.worlds) {
            if (com.jubitus.travellers.server.map.MapResweepCoordinator.INSTANCE.cancel(ws.provider.getDimension(), sender)) {
                cancelled++;
            }
        }
        if (cancelled == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "No map resweep running."));
        }
    }

    // --- Sound stress test ---

    private void executeSoundDebug(MinecraftServer server, ICommandSender sender, String sub) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        World world = player.world;

        double radius = 64.0;
        AxisAlignedBB box = player.getEntityBoundingBox().grow(radius, 32, radius);
        List<EntityTraveller> nearby = world.getEntitiesWithinAABB(EntityTraveller.class, box,
                t -> t != null && t.isEntityAlive());

        if (nearby.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "No travellers within " + (int) radius + " blocks."));
            return;
        }

        int triggered = 0;
        if (sub.equals("stress")) {
            // Play ambient (streamed) sounds on all nearby travellers simultaneously.
            // This stresses the sound engine with many concurrent OGG streams.
            for (EntityTraveller t : nearby) {
                SoundEvent snd = pickAmbientOrTalk(t);
                if (snd == null) continue;
                t.playVoiceMoving(snd, 1.0f, 1.0f, 400, true, EntityTraveller.VoiceKind.AMBIENT);
                triggered++;
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN +
                    "Sound stress: triggered " + triggered + "/" + nearby.size() + " travellers. Check logs for CodecJOrbis errors."));

        } else if (sub.equals("rapidfire")) {
            // Rapid stop+start on each entity: play ambient, immediately replace with talk.
            // This reproduces the race condition where a streamed OGG is stopped and immediately reopened.
            for (EntityTraveller t : nearby) {
                SoundEvent ambient = pickAmbientOrTalk(t);
                SoundEvent talk = pickTalkOrAmbient(t);
                if (ambient == null || talk == null) continue;
                // First play starts the stream
                t.playVoiceMoving(ambient, 1.0f, 1.0f, 200, true, EntityTraveller.VoiceKind.AMBIENT);
                // Immediate replacement triggers the stop+restart race in the sound engine
                t.playVoiceMoving(talk, 1.0f, 1.0f, 200, true, EntityTraveller.VoiceKind.TALK);
                triggered++;
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN +
                    "Sound rapidfire: triggered " + triggered + "/" + nearby.size() + " stop+restart cycles. Check for CodecJOrbis errors."));

        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED +
                    "Unknown sound subcommand. Use: stress | rapidfire"));
        }
    }

    @Nullable
    private SoundEvent pickAmbientOrTalk(EntityTraveller t) {
        VoiceSet vs = CultureRegistry.TRAVELLER_VOICES.get(t.getCultureId().toLowerCase(java.util.Locale.ROOT));
        if (vs == null) return null;
        if (vs.ambient.length > 0) return vs.ambient[t.getRNG().nextInt(vs.ambient.length)];
        if (vs.talk.length > 0) return vs.talk[t.getRNG().nextInt(vs.talk.length)];
        return null;
    }

    @Nullable
    private SoundEvent pickTalkOrAmbient(EntityTraveller t) {
        VoiceSet vs = CultureRegistry.TRAVELLER_VOICES.get(t.getCultureId().toLowerCase(java.util.Locale.ROOT));
        if (vs == null) return null;
        if (vs.talk.length > 0) return vs.talk[t.getRNG().nextInt(vs.talk.length)];
        if (vs.ambient.length > 0) return vs.ambient[t.getRNG().nextInt(vs.ambient.length)];
        return null;
    }

    private void dumpEverything(PrintWriter out) {
        out.println("Traveller / Raider Culture Debug Dump");
        out.println("====================================");
        out.println();
        out.println("Total cultures: " + CultureRegistry.all().size());
        out.println();

        for (CultureDef c : CultureRegistry.all()) {
            dumpCulture(out, c);
        }

        out.println();
        out.println("======= Summary: Voice Maps =======");
        out.println("Traveller voices map size: " + CultureRegistry.TRAVELLER_VOICES.size());
        out.println("Raider voices map size   : " + CultureRegistry.RAIDER_VOICES.size());
        out.println();

        out.println("TRAVELLER_VOICES keys:");
        for (String key : CultureRegistry.TRAVELLER_VOICES.keySet()) {
            out.println("  - " + key);
        }

        out.println();
        out.println("RAIDER_VOICES keys:");
        for (String key : CultureRegistry.RAIDER_VOICES.keySet()) {
            out.println("  - " + key);
        }
        out.println("======= Biome → Culture Weights =======");
        for (Map.Entry<String, Map<String, Integer>> e : CultureRegistry.getBiomeWeights().entrySet()) {
            out.println("Biome key: " + e.getKey());
            for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                out.println("  " + c.getKey() + " = " + c.getValue());
            }
        }

        out.println();
        out.println("======= End of Dump =======");
    }

    private void dumpCulture(PrintWriter out, CultureDef c) {
        out.println("------------------------------------");
        out.println("Culture id     : " + c.id);
        out.println("Display name   : " + c.displayName);

        // --- Traveller appearances / textures ---
        try {
            int count = c.travellerAppearanceCount();
            out.println("Traveller looks: " + count);
            for (int i = 0; i < count; i++) {
                TravellerAppearance app = c.getAppearance(i);
                out.println("  [" + i + "] base=" + (app.baseTexture != null ? app.baseTexture.toString() : "<null>"));
                if (app.clothes.isEmpty()) {
                    out.println("       clothes: (none)");
                } else {
                    out.println("       clothes (" + app.clothes.size() + "):");
                    for (int j = 0; j < app.clothes.size(); j++) {
                        out.println("         - " + app.clothes.get(j).toString());
                    }
                }
            }
        } catch (Throwable t) {
            // If you’re still on the old CultureDef with travellerTextureCount/raiderTextureCount,
            // this will catch the missing methods and avoid crashing the command.
            out.println("Traveller looks: <error retrieving> (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }


        VoiceSet tv = CultureRegistry.TRAVELLER_VOICES.get(c.id.toLowerCase(java.util.Locale.ROOT));
        VoiceSet rv = CultureRegistry.RAIDER_VOICES.get(c.id.toLowerCase(java.util.Locale.ROOT));

        out.println("Traveller voices present: " + (tv != null));
        if (tv != null) {
            dumpVoiceArray(out, "  talk", tv.talk);
            dumpVoiceArray(out, "  hurt", tv.hurt);
            dumpVoiceArray(out, "  hello", tv.hello);
            dumpVoiceArray(out, "  ambient", tv.ambient);
        }

        out.println("Raider voices present   : " + (rv != null));
        if (rv != null) {
            dumpVoiceArray(out, "  talk", rv.talk);
            dumpVoiceArray(out, "  hurt", rv.hurt);
            dumpVoiceArray(out, "  hello", rv.hello);
            dumpVoiceArray(out, "  ambient", rv.ambient);
        }

        out.println();
    }

    private void dumpVoiceArray(PrintWriter out, String label, @Nullable SoundEvent[] arr) {
        if (arr == null || arr.length == 0) {
            out.println(label + ": (none)");
            return;
        }

        out.println(label + " (" + arr.length + "):");
        for (int i = 0; i < arr.length; i++) {
            SoundEvent ev = arr[i];
            out.println("    - [" + i + "] " + (ev != null && ev.getRegistryName() != null
                    ? ev.getRegistryName().toString()
                    : "<null>"));
        }
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP only
    }
}