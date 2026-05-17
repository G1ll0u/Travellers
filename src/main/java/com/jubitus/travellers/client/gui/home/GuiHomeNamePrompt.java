package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgRegisterHomeChest;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

import static net.minecraft.client.gui.Gui.drawRect;

@SideOnly(Side.CLIENT)
public class GuiHomeNamePrompt extends GuiScreen {

    private final EntityPlayer player;
    private final BlockPos pos;

    private GuiTextField nameField;
    private GuiButton okBtn;
    private GuiButton cancelBtn;

    private final int xSize = 176;
    private final int ySize = 90;
    private int guiLeft;
    private int guiTop;

    public GuiHomeNamePrompt(EntityPlayer player, BlockPos pos) {
        this.player = player;
        this.pos = pos;
    }

    @Override
    public void initGui() {
        super.initGui();
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        this.buttonList.clear();

        int cx = this.guiLeft + this.xSize / 2;

        this.nameField = new GuiTextField(
                0,
                this.fontRenderer,
                this.guiLeft + 12,
                this.guiTop + 28,
                152,
                16
        );
        this.nameField.setMaxStringLength(32);
        this.nameField.setFocused(true);

        this.okBtn = this.addButton(new GuiButton(
                1,
                cx - 80,
                this.guiTop + 55,
                75,
                18,
                I18n.format("travellers.gui.save", "Save")
        ));
        this.cancelBtn = this.addButton(new GuiButton(
                2,
                cx + 5,
                this.guiTop + 55,
                75,
                18,
                I18n.format("travellers.gui.cancel", "Cancel")
        ));

        this.okBtn.enabled = false;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.nameField.updateCursorCounter();
        this.okBtn.enabled = !this.nameField.getText().trim().isEmpty();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.nameField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            this.mc.player.closeScreen();
            return;
        }

        if (keyCode == org.lwjgl.input.Keyboard.KEY_RETURN
                || keyCode == org.lwjgl.input.Keyboard.KEY_NUMPADENTER) {
            if (this.okBtn.enabled) {
                actionPerformed(this.okBtn);
            }
            return;
        }

        if (this.nameField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == this.cancelBtn) {
            this.mc.player.closeScreen();
            return;
        }

        if (button == this.okBtn) {
            String name = this.nameField.getText().trim();
            if (!name.isEmpty()) {
                TravellerFollowNet.CHANNEL.sendToServer(new MsgRegisterHomeChest(this.pos, name));
            }
            this.mc.player.closeScreen();
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + this.ySize, 0xCC000000);
        drawRect(this.guiLeft + 2, this.guiTop + 2, this.guiLeft + this.xSize - 2, this.guiTop + this.ySize - 2, 0xFF202020);

        this.fontRenderer.drawString(
                I18n.format("travellers.home_name_prompt.title", "Name of your home"),
                this.guiLeft + 8,
                this.guiTop + 6,
                0xFFFFFF
        );
        this.fontRenderer.drawString(
                I18n.format("travellers.home_name_prompt.subtitle", "You can assign it to hired workers."),
                this.guiLeft + 8,
                this.guiTop + 16,
                0xC0C0C0
        );

        this.nameField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}