package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import com.jubitus.travellers.entity.culture.VoiceSet;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.Loader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class CommandTravellerDebug extends CommandBase {

    @Override
    public String getName() {
        return "travellersdebug";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersdebug";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        // Ensure cultures are loaded from Millénaire before dumping
        try {
            CultureRegistry.bootstrapAllMillenaireCultures();
        } catch (Exception ex) {
            // Don't hard-fail the command, just note it
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Warning: error bootstrapping cultures: " + ex.getMessage()));
        }

        // Where to write the dump: config folder
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


        // --- Voices ---
        VoiceSet tv = CultureRegistry.TRAVELLER_VOICES.get(c.id.toLowerCase(java.util.Locale.ROOT));
        VoiceSet rv = CultureRegistry.RAIDER_VOICES.get(c.id.toLowerCase(java.util.Locale.ROOT));

        out.println("Traveller voices present: " + (tv != null));
        if (tv != null) {
            out.println("  talk : " + (tv.talk != null ? tv.talk.getRegistryName() : "<null>"));
            out.println("  hurt : " + (tv.hurt != null ? tv.hurt.getRegistryName() : "<null>"));
            out.println("  hello: " + (tv.hello != null ? tv.hello.getRegistryName() : "<null>"));
            if (tv.ambient != null && tv.ambient.length > 0) {
                out.println("  ambient (" + tv.ambient.length + "):");
                for (int i = 0; i < tv.ambient.length; i++) {
                    out.println("    - " + (tv.ambient[i] != null ? tv.ambient[i].getRegistryName() : "<null>"));
                }
            } else {
                out.println("  ambient: (none)");
            }
        }

        out.println("Raider voices present   : " + (rv != null));
        if (rv != null) {
            out.println("  talk : " + (rv.talk != null ? rv.talk.getRegistryName() : "<null>"));
            out.println("  hurt : " + (rv.hurt != null ? rv.hurt.getRegistryName() : "<null>"));
            out.println("  hello: " + (rv.hello != null ? rv.hello.getRegistryName() : "<null>"));
            if (rv.ambient != null && rv.ambient.length > 0) {
                out.println("  ambient (" + rv.ambient.length + "):");
                for (int i = 0; i < rv.ambient.length; i++) {
                    out.println("    - " + (rv.ambient[i] != null ? rv.ambient[i].getRegistryName() : "<null>"));
                }
            } else {
                out.println("  ambient: (none)");
            }
        }

        out.println();
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP only
    }
}