package com.jubitus.travellers.client;

import com.jubitus.jubiconfig.factory.AbstractModGuiFactory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class TravellersGuiFactory extends AbstractModGuiFactory {

    @Override
    protected String getModId() {
        return "travellers";
    }
}
