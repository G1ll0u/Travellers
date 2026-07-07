package com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Minimal helper to work with Millénaire's fire pit without a hard compile dep.
 */
public final class CampfireBlock {
    private static final ResourceLocation ID = new ResourceLocation("millenaire", "fire_pit");

    private CampfireBlock() {
    }

    /**
     * Build a lit state aligned to the travellers’s yaw. Null if block missing.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static IBlockState litStateFacingYaw(float yaw) {
        Block b = get();
        if (b == null) return null;

        IBlockState st = b.getDefaultState();

        // lit=true (property name is "lit" in Millénaire)
        PropertyBool lit = propBool(st, "lit");
        if (lit != null) {
            st = st.withProperty(lit, true);
        }

        // alignment = "x" or "z" (EnumAlignment implements IStringSerializable)
        PropertyEnum<?> alignAny = propEnum(st, "alignment");
        if (alignAny != null) {
            // 1.12 requires raw cast to satisfy withProperty signature
            PropertyEnum align = alignAny;

            // Millénaire's fromAxis maps X->Z and Z->X; mirror that with strings
            String desired = (EnumFacing.fromAngle(yaw).getAxis() == EnumFacing.Axis.X) ? "z" : "x";

            for (Object v : align.getAllowedValues()) {
                IStringSerializable is = (IStringSerializable) v;
                if (desired.equals(is.getName())) {
                    st = st.withProperty(align, (Comparable) v);
                    break;
                }
            }
        }

        return st;
    }

    /**
     * Returns the block instance, or null if Millénaire isn't loaded.
     */
    public static Block get() {
        return ForgeRegistries.BLOCKS.getValue(ID);
    }

    // ---- helpers ----

    private static PropertyBool propBool(IBlockState st, String name) {
        for (IProperty<?> p : st.getPropertyKeys()) {
            if (p instanceof PropertyBool && name.equals(p.getName())) return (PropertyBool) p;
        }
        return null;
    }

    private static PropertyEnum<?> propEnum(IBlockState st, String name) {
        for (IProperty<?> p : st.getPropertyKeys()) {
            if (p instanceof PropertyEnum && name.equals(p.getName())) return (PropertyEnum<?>) p;
        }
        return null;
    }

    /**
     * Same fire-pit state with the 'lit' property set to {@code lit} (alignment preserved).
     * Null if {@code s} isn't the fire pit or has no 'lit' property.
     */
    public static IBlockState withLit(IBlockState s, boolean lit) {
        Block b = get();
        if (b == null || s == null || s.getBlock() != b) return null;
        PropertyBool litProp = propBool(s, "lit");
        if (litProp == null) return null;
        return s.withProperty(litProp, lit);
    }

    /**
     * True if this is Millénaire's fire pit and its 'lit' property is true.
     */
    public static boolean isLit(IBlockState s) {
        if (s == null) return false;
        Block b = get();
        if (b == null || s.getBlock() != b) return false;
        PropertyBool lit = propBool(s, "lit");
        return lit != null && s.getValue(lit);
    }

}

