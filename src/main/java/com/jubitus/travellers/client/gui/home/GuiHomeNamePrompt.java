package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.client.gui.debug.ContainerNull;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.jubitus.travellers.utils.net.message.home.MsgRegisterHomeChest;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public class GuiHomeNamePrompt extends GuiContainer {
    private final EntityPlayer player;
    private final BlockPos pos;

    private GuiTextField nameField;
    private GuiButton okBtn;
    private GuiButton cancelBtn;

    public GuiHomeNamePrompt(EntityPlayer player, BlockPos pos) {
        super(new ContainerNull());
        this.player = player;
        this.pos = pos;
        this.xSize = 176;
        this.ySize = 90;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        int cx = this.guiLeft + this.xSize / 2;

        this.nameField = new GuiTextField(0, this.fontRenderer, this.guiLeft + 12, this.guiTop + 28, 152, 16);
        this.nameField.setMaxStringLength(32);
        this.nameField.setFocused(true);

        this.okBtn = this.addButton(new GuiButton(1, cx - 80, this.guiTop + 55, 75, 18, "Save"));
        this.cancelBtn = this.addButton(new GuiButton(2, cx + 5, this.guiTop + 55, 75, 18, "Cancel"));
        this.okBtn.enabled = false;
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString("Name of your home", 8, 6, 0xFFFFFF);
        fontRenderer.drawString(
                "You can assign it to hired workers.",
                8, 16, 0xC0C0C0
        );
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // simple dark background
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xCC000000);
        drawRect(guiLeft + 2, guiTop + 2, guiLeft + xSize - 2, guiTop + ySize - 2, 0xFF202020);
        this.nameField.drawTextBox();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.nameField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.nameField.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.nameField.updateCursorCounter();
        String s = nameField.getText().trim();
        okBtn.enabled = !s.isEmpty();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 2) {
            mc.player.closeScreen();
            return;
        }
        if (button.id == 1) {
            String name = nameField.getText().trim();
            TravellerFollowNet.CHANNEL.sendToServer(new MsgRegisterHomeChest(pos, name));
            mc.player.closeScreen();
            return;
        }
        super.actionPerformed(button);
    }
}
