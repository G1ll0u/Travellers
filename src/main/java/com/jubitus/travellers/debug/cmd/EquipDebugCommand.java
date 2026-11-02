package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug equip …`. */
public class EquipDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "equip";
    }

    @Override
    public String description() {
        return "Show the equipment a culture can get";
    }

    @Override
    public String usage() {
        return "<culture>";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeEquipDebug(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        for (CultureDef c : CultureRegistry.all()) {
            if (c != null && c.id != null) ids.add(c.id);
        }
        return getListOfStringsMatchingLastWord(args, ids);
    }

    /** {@code /travellersdebug equip <culture>} — the gear this culture accepts, per bucket, ranked by Millenaire priority. */
    private void executeEquipDebug(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /travellersdebug equip <culture>"));
            return;
        }
        CultureRegistry.bootstrapAllMillenaireCultures();
        String id = args[1].toLowerCase();
        com.jubitus.travellers.unit.culture.CultureDef def = CultureRegistry.get(id);
        if (def == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Unknown culture: " + id));
            return;
        }

        java.util.Map<com.jubitus.travellers.unit.equipment.TravellerMillPriorities.Bucket, java.util.List<ItemStack>> byBucket =
                new java.util.EnumMap<>(com.jubitus.travellers.unit.equipment.TravellerMillPriorities.Bucket.class);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (java.util.List<ItemStack> list : def.getAllCultureItems().values()) {
            for (ItemStack s : list) {
                if (s == null || s.isEmpty()) continue;
                com.jubitus.travellers.unit.equipment.TravellerMillPriorities.Bucket b =
                        com.jubitus.travellers.unit.equipment.TravellerEquipmentPolicy.bucketFor(s, null);
                if (b == null) continue;
                if (com.jubitus.travellers.unit.equipment.TravellerEquipmentPolicy.isForbidden(s)) continue;
                if (!com.jubitus.travellers.unit.equipment.TravellerEquipmentPolicy.cultureAllows(id, false, s, null, false)) continue;
                String key = s.getItem().getRegistryName() + "#" + b;
                if (!seen.add(key)) continue;
                byBucket.computeIfAbsent(b, k -> new java.util.ArrayList<>()).add(s);
            }
        }

        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== Equipment kit for culture '" + id + "' ==="));
        for (com.jubitus.travellers.unit.equipment.TravellerMillPriorities.Bucket b :
                com.jubitus.travellers.unit.equipment.TravellerMillPriorities.Bucket.values()) {
            java.util.List<ItemStack> list = byBucket.get(b);
            if (list == null || list.isEmpty()) continue;
            list.sort((x, y) -> Double.compare(
                    com.jubitus.travellers.unit.equipment.TravellerEquipmentPolicy.quality(y, b),
                    com.jubitus.travellers.unit.equipment.TravellerEquipmentPolicy.quality(x, b)));
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + b.name() + ":"));
            for (ItemStack s : list) {
                Integer raw = com.jubitus.travellers.unit.equipment.TravellerMillPriorities.rawPriority(s, b);
                double q = com.jubitus.travellers.unit.equipment.TravellerEquipmentPolicy.quality(s, b);
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "  " + s.getItem().getRegistryName()
                        + TextFormatting.DARK_GRAY + "  q=" + String.format("%.0f", q)
                        + (raw == null ? " (fallback)" : " (mill)")));
            }
        }
    }
}
