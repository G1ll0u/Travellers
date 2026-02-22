package com.jubitus.travellers.client.gui.transport;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class GuiWorkerInventory extends GuiContainer {

    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker.png");
    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    public GuiWorkerInventory(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerInventory(player.inventory, worker));
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 150;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // Title
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.inventory.title", "Worker Inventory"),
                8, 6, 0x404040
        );

        // Player inventory title
        this.fontRenderer.drawString(
                this.player.inventory.getDisplayName().getUnformattedText(),
                8, this.ySize - 96 + 2,
                0x404040
        );
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.getTextureManager().bindTexture(TEX);
        int x = this.guiLeft;
        int y = this.guiTop;
        this.drawTexturedModalRect(x, y, 0, 0, this.xSize, this.ySize);
    }
}
