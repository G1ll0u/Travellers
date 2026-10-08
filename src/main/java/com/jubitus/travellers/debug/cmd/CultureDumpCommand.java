package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.unit.culture.VoiceSet;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.Loader;
import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug cultures`, also the default when no subcommand matches. */
public class CultureDumpCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "cultures";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
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
        return true;
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
}
