package com.jubitus.travellers.decor;

import com.jubitus.travellers.Tags;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Decorative blocks; creative-only, no recipes. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class DecorBlocks {

    public static final Block VILLAGE_OF_NATURE = new BlockWallPainting()
            .setRegistryName(Tags.MOD_ID, "village_of_nature")
            .setTranslationKey(Tags.MOD_ID + ".village_of_nature");

    private DecorBlocks() {}

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(VILLAGE_OF_NATURE);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(new ItemBlock(VILLAGE_OF_NATURE).setRegistryName(VILLAGE_OF_NATURE.getRegistryName()));
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(VILLAGE_OF_NATURE), 0,
                new ModelResourceLocation(VILLAGE_OF_NATURE.getRegistryName(), "inventory"));
    }
}
