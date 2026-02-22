package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.mill.MillenaireVillageDirectory;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class CommandTravellerMap extends CommandBase {

    @Override
    public String getName() {
        return "travellersmap";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersmap [radius] [step]\n" +
                "  radius: sampling radius in blocks (gilles 256)\n" +
                "  step: sample spacing in blocks (gilles 8)";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        WorldServer ws = (WorldServer) player.world;

        int radius = 256;
        int step = 8;

        if (args.length >= 1) {
            radius = parseInt(args[0], 16, 4096);
        }
        if (args.length >= 2) {
            step = parseInt(args[1], 1, 128);
        }

        BlockPos center = player.getPosition();

        // Keep Millénaire directory in sync for overlay (names/centers)
        File worldDir = ws.getSaveHandler().getWorldDirectory();
        File millFolder = new File(worldDir, "millenaire");
        MillenaireVillageDirectory.reloadIfStale(millFolder);

        int diameter = radius * 2;
        int samples = diameter / step + 1;

        BufferedImage img = new BufferedImage(samples, samples, BufferedImage.TYPE_INT_ARGB);

        double maxDensitySeen = 0.0;

        // Sample density
        for (int zi = 0; zi < samples; zi++) {
            int z = center.getZ() - radius + zi * step;
            for (int xi = 0; xi < samples; xi++) {
                int x = center.getX() - radius + xi * step;
                BlockPos samplePos = new BlockPos(x, center.getY(), z);

                // Base density from MillVillageIndex (same as soft spawner)
                double baseDensity = MillVillageIndex.travellerDensityAt(ws, samplePos);

                // Boost by village population (same logic as TravellerSoftSpawner)
                double popMult = MillVillageIndex.populationWeightAt(ws, samplePos);
                double effectiveDensity = baseDensity * popMult;

                if (effectiveDensity > maxDensitySeen) {
                    maxDensitySeen = effectiveDensity;
                }

                img.setRGB(xi, zi, densityToColor(effectiveDensity));
            }
        }

        // Overlay Millénaire centers (still using the directory for debug dots)
        for (MillenaireVillageDirectory.Entry e : MillenaireVillageDirectory.getAllEntries()) {
            int dx = e.pos.getX() - (center.getX() - radius);
            int dz = e.pos.getZ() - (center.getZ() - radius);

            int xi = dx / step;
            int zi = dz / step;

            if (xi < 0 || xi >= samples || zi < 0 || zi >= samples) continue;

            int color = e.isVillage ? 0xFFFF0000 : 0xFF00FF00; // red = village, green = lone building
            drawDot(img, xi, zi, color, 2);
        }

        // Output file in world folder
        File outDir = new File(worldDir, "traveller_debug");
        if (!outDir.exists()) {
            // noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        }

        String fileName = String.format("traveller_density_dim%d_%d_%d_r%d_s%d.png",
                ws.provider.getDimension(), center.getX(), center.getZ(), radius, step);
        File outFile = new File(outDir, fileName);

        try {
            ImageIO.write(img, "PNG", outFile);
            sender.sendMessage(new TextComponentString(
                    "Traveller density map saved: " + outFile.getAbsolutePath() +
                            " (max effective density seen = " +
                            String.format("%.3f", maxDensitySeen) + ")"));
        } catch (IOException ex) {
            throw new CommandException("Failed to save image: " + ex.getMessage());
        }
    }

    private static int densityToColor(double d) {
        // Clamp 0..1
        d = Math.max(0.0, Math.min(1.0, d));

        // Simple grayscale: 0 = black, 1 = white
        int v = (int) Math.round(d * 255.0);
        int a = 0xFF;
        return (a << 24) | (v << 16) | (v << 8) | v;

        /*
        // If you prefer a blue->red heatmap, uncomment and use java.awt.Color:
        float hue = (float) (0.7 - 0.7 * d); // 0.7 (blue) -> 0.0 (red)
        int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, (float) (0.3 + 0.7 * d));
        return 0xFF000000 | (rgb & 0x00FFFFFF);
        */
    }

    private static void drawDot(BufferedImage img, int cx, int cz, int color, int radiusPx) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int dz = -radiusPx; dz <= radiusPx; dz++) {
            int zz = cz + dz;
            if (zz < 0 || zz >= h) continue;
            for (int dx = -radiusPx; dx <= radiusPx; dx++) {
                int xx = cx + dx;
                if (xx < 0 || xx >= w) continue;
                // Circle-ish
                if (dx * dx + dz * dz <= radiusPx * radiusPx) {
                    img.setRGB(xx, zz, color);
                }
            }
        }
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP only by gilles, tweak if you want
    }
}
