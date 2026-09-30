package com.jubitus.travellers.world.camp.shelter;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.template.Template;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * A player-authored shelter: a vanilla {@link Template} plus metadata (culture, the entrance
 * direction as authored). Saved to config/travellers/structures/shelters/&lt;name&gt;.nbt.
 * Rotation, bed orientation, modded blocks and relighting are all delegated to the vanilla Template.
 */
public class ShelterTemplate {

    public final String name;
    public final String culture;
    public final EnumFacing entranceFacing;
    public final Template template;

    public ShelterTemplate(String name, String culture, EnumFacing entranceFacing, Template template) {
        this.name = name;
        this.culture = culture.toLowerCase(Locale.ROOT);
        this.entranceFacing = entranceFacing;
        this.template = template;
    }

    public BlockPos getSize() {
        return template.getSize();
    }

    public void save(File file) throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("Name", name);
        root.setString("Culture", culture);
        root.setInteger("Entrance", entranceFacing.getHorizontalIndex());
        root.setTag("Template", template.writeToNBT(new NBTTagCompound()));
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(root, out);
        }
    }

    public static ShelterTemplate load(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            NBTTagCompound root = CompressedStreamTools.readCompressed(in);
            String name = root.hasKey("Name") ? root.getString("Name")
                    : file.getName().replaceFirst("\\.nbt$", "");
            String culture = root.getString("Culture");
            EnumFacing entrance = EnumFacing.byHorizontalIndex(root.getInteger("Entrance"));
            Template t = new Template();
            t.read(root.getCompoundTag("Template"));
            return new ShelterTemplate(name, culture, entrance, t);
        }
    }
}
