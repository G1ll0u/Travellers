package com.jubitus.traveller.traveller.utils.commands;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

public class CommandGlowTravellers extends net.minecraft.command.CommandBase {

    @Override
    public String getName() {
        return "travellersglow";
    }

    @Override
    public String getUsage(ICommandSender s) {
        return "/travellersglow <seconds|off> [color]";
    }

    @Override
    public void execute(MinecraftServer srv, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getUsage(sender));

        if ("off".equalsIgnoreCase(args[0])) {
            int cleared = 0;
            for (WorldServer w : srv.worlds) cleared += clearGlow(w);
            notifyCommandListener(sender, this, "Removed glow from %s travellers.", cleared);
            return;
        }

        int seconds = parseInt(args[0], 1, 99999999); // 1s..1h
        net.minecraft.util.text.TextFormatting color =
                (args.length >= 2) ? parseColor(args[1]) : net.minecraft.util.text.TextFormatting.GOLD;

        int applied = 0;
        for (WorldServer w : srv.worlds) {
            ScorePlayerTeam team = ensureTeam(w.getScoreboard(), "travellers_glow", color);
            applied += applyGlow(w, seconds * 20, team);
        }
        notifyCommandListener(sender, this, "Applied glow (%s s, %s) to %s travellers.",
                seconds, color.getFriendlyName(), applied);
    }

    private int clearGlow(WorldServer world) {
        java.util.List<EntityTraveller> list = world.getEntities(EntityTraveller.class, e -> e.isEntityAlive());
        Scoreboard sb = world.getScoreboard();
        int count = 0;

        for (EntityTraveller t : list) {
            t.removeActivePotionEffect(MobEffects.GLOWING);
            // Remove from whatever team(s) it’s on to drop the outline color
            sb.removePlayerFromTeams(t.getCachedUniqueIdString());
            count++;
        }
        return count;
    }

    private net.minecraft.util.text.TextFormatting parseColor(String s) throws CommandException {
        net.minecraft.util.text.TextFormatting c = net.minecraft.util.text.TextFormatting.getValueByName(
                s.toLowerCase(java.util.Locale.ROOT));
        if (c == null) throw new CommandException("Unknown color: " + s);
        return c;
    }

    private ScorePlayerTeam ensureTeam(Scoreboard sb, String name, net.minecraft.util.text.TextFormatting color) {
        ScorePlayerTeam team = sb.getTeam(name);
        if (team == null) team = sb.createTeam(name);
        team.setColor(color);
        team.setPrefix("");
        team.setSuffix("");
        team.setSeeFriendlyInvisiblesEnabled(true);
        return team;
    }

    private int applyGlow(WorldServer world, int ticks, ScorePlayerTeam team) {
        java.util.List<EntityTraveller> list = world.getEntities(EntityTraveller.class, e -> e.isEntityAlive());
        Scoreboard sb = world.getScoreboard();
        int count = 0;

        for (EntityTraveller t : list) {
            // Put the entity on the colored team
            sb.addPlayerToTeam(t.getCachedUniqueIdString(), team.getName());

            // Apply vanilla glowing (no particles, non-ambient)
            t.addPotionEffect(new PotionEffect(MobEffects.GLOWING, ticks, 0, false, false));

            count++;
        }
        return count;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    } // ops
}
