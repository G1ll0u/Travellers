package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.debug.DebugFiles;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** `/travellersdebug cultureitems`: Millenaire items per culture, written to logs/travellers/dumps/. */
public class CultureItemsDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "cultureitems";
    }

    @Override
    public String description() {
        return "Write the Millenaire items of every culture to a file";
    }

    @Override
    public String usage() {
        return "";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        File outFile = new File(DebugFiles.dir(DebugFiles.DUMPS), "culture_items.txt");
        try {
            write(outFile);
        } catch (IOException ex) {
            throw new CommandException("Failed to write " + outFile.getName() + ": " + ex.getMessage());
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Culture items written: "
                + TextFormatting.YELLOW + outFile.getAbsolutePath()));
        return true;
    }

    /** Also used by the export bundle. */
    public static void write(File outFile) throws IOException {
        CultureRegistry.bootstrapAllMillenaireCultures();
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            w.write("// cultureId;category;registryName;meta");
            w.newLine();
            for (CultureDef def : CultureRegistry.all()) {
                Map<String, List<ItemStack>> itemsByCat = def.getAllCultureItems();
                if (itemsByCat.isEmpty()) continue;
                w.newLine();
                w.write("// ===== Culture: " + def.id + " =====");
                w.newLine();
                for (Map.Entry<String, List<ItemStack>> entry : itemsByCat.entrySet()) {
                    List<ItemStack> items = entry.getValue();
                    if (items == null) continue;
                    for (ItemStack stack : items) {
                        if (stack == null || stack.isEmpty()) continue;
                        String reg = stack.getItem().getRegistryName() != null
                                ? stack.getItem().getRegistryName().toString() : "unknown";
                        w.write(def.id + ";" + entry.getKey() + ";" + reg + ";" + stack.getMetadata());
                        w.newLine();
                    }
                }
            }
        }
    }
}
