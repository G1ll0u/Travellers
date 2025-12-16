package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.Loader;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class CommandDumpCultureItems extends CommandBase {

    @Override
    public String getName() {
        return "travellersDumpCultureItems";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersDumpCultureItems - dump Millenaire items per culture to a txt file";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        // Make sure cultures & traded_goods are loaded
        CultureRegistry.bootstrapAllMillenaireCultures();

        File configDir = Loader.instance().getConfigDir(); // .minecraft/config
        File outFile = new File(configDir, "travellers_culture_items.txt");

        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(outFile),
                        StandardCharsets.UTF_8))) {

            // Header
            w.write("// cultureId;category;registryName;meta");
            w.newLine();

            for (CultureDef def : CultureRegistry.all()) {
                Map<String, List<ItemStack>> itemsByCat = def.getAllCultureItems();
                if (itemsByCat.isEmpty()) continue;

                w.newLine();
                w.write("// ===== Culture: " + def.id + " =====");
                w.newLine();

                for (Map.Entry<String, List<ItemStack>> entry : itemsByCat.entrySet()) {
                    String category = entry.getKey();
                    List<ItemStack> items = entry.getValue();
                    if (items == null || items.isEmpty()) continue;

                    for (ItemStack stack : items) {
                        if (stack == null || stack.isEmpty()) continue;

                        String reg = stack.getItem().getRegistryName() != null
                                ? stack.getItem().getRegistryName().toString()
                                : "unknown";
                        int meta = stack.getMetadata();

                        // cultureId;category;registryName;meta
                        w.write(def.id + ";" +
                                category + ";" +
                                reg + ";" +
                                meta);
                        w.newLine();
                    }
                }
            }
        } catch (Exception ex) {
            sender.sendMessage(new TextComponentString(
                    "[Travellers] Failed to write culture items: " + ex));
            return;
        }

        sender.sendMessage(new TextComponentString(
                "[Travellers] Culture items dumped to: " + outFile.getAbsolutePath()));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP only
    }
}

