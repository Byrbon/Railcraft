/* 
 * Copyright (c) CovertJaguar, 2014 http://railcraft.info
 * 
 * This code is the property of CovertJaguar
 * and may only be used with explicit written
 * permission unless otherwise specified on the
 * license page at http://railcraft.info/wiki/info:license.
 */
package mods.railcraft.client.gui;

import mods.railcraft.client.gui.buttons.GuiBetterButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.passive.EntityVillager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.List;

public class GuiTools {

    public static void drawCenteredString(FontRenderer fr, String s, int y) {
        drawCenteredString(fr, s, y, 176);
    }

    public static void drawCenteredString(FontRenderer fr, String s, int y, int guiWidth) {
        drawCenteredString(fr, s, y, guiWidth, 0x404040, false);
    }

    public static void drawCenteredString(FontRenderer fr, String s, int y, int guiWidth, int color, boolean shadow) {
        int sWidth = fr.getStringWidth(s);
        int sPos = guiWidth / 2 - sWidth / 2;
        fr.drawString(s, sPos, y, color, shadow);
    }

    public static void drawStringCenteredAtPos(FontRenderer fr, String s, int x, int y) {
        drawStringCenteredAtPos(fr, s, x, y, 0x404040, false);
    }

    public static void drawStringCenteredAtPos(FontRenderer fr, String s, int x, int y, int color, boolean shadow) {
        int sWidth = fr.getStringWidth(s);
        fr.drawString(s, x - sWidth / 2, y, color, shadow);
    }

    public static void newButtonRowAuto(List buttonList, int xStart, int xSize, List<? extends GuiBetterButton> buttons) {
        int buttonWidth = 0;
        for (GuiBetterButton b : buttons) {
            buttonWidth += b.getWidth();
        }
        int remaining = xSize - buttonWidth;
        int spacing = remaining / (buttons.size() + 1);
        int pointer = 0;
        for (GuiBetterButton b : buttons) {
            pointer += spacing;
            b.xPosition = xStart + pointer;
            pointer += b.getWidth();
            buttonList.add(b);
        }
    }

    public static void newButtonRowBookended(List buttonList, int xStart, int xEnd, List<? extends GuiBetterButton> buttons) {
        int buttonWidth = 0;
        for (GuiBetterButton b : buttons) {
            buttonWidth += b.getWidth();
        }
        int remaining = (xEnd - xStart) - buttonWidth;
        int spacing = remaining / (buttons.size() + 1);
        int pointer = 0;
        for (GuiBetterButton b : buttons) {
            pointer += spacing;
            b.xPosition = xStart + pointer;
            pointer += b.getWidth();
            buttonList.add(b);
        }
    }

    public static void newButtonRow(List buttonList, int xStart, int spacing, List<? extends GuiBetterButton> buttons) {
        int pointer = 0;
        for (GuiBetterButton b : buttons) {
            b.xPosition = xStart + pointer;
            pointer += b.getWidth() + spacing;
            buttonList.add(b);
        }
    }

    public static void drawVillager(EntityVillager villager, int x, int y, int scale, float yaw, float pitch) {
        OpenGL.glPushAttrib(OpenGL.GL_ENABLE_BIT);
        OpenGL.glEnable(OpenGL.GL_LIGHTING);
        OpenGL.glEnable(OpenGL.GL_DEPTH_TEST);
        OpenGL.glEnable(OpenGL.GL_COLOR_MATERIAL);
        OpenGL.glPushMatrix();
        OpenGL.glTranslatef((float) x, (float) y, 50.0F);
        OpenGL.glScalef((float) (-scale), (float) scale, (float) scale);
        OpenGL.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
        OpenGL.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        OpenGL.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        OpenGL.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);
        OpenGL.glRotatef(-((float) Math.atan((double) (pitch / 40.0F))) * 20.0F, 1.0F, 0.0F, 0.0F);
        villager.renderYawOffset = (float) Math.atan((double) (yaw / 40.0F)) * 20.0F;
        villager.rotationYaw = (float) Math.atan((double) (yaw / 40.0F)) * 40.0F;
        villager.rotationPitch = -((float) Math.atan((double) (pitch / 40.0F))) * 20.0F;
        villager.rotationYawHead = villager.rotationYaw;
        OpenGL.glTranslatef(0.0F, (float) villager.getYOffset(), 0.0F);
        Minecraft.getMinecraft().getRenderManager().playerViewY = 180.0F;
        Minecraft.getMinecraft().getRenderManager().renderEntityWithPosYaw(villager, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
        OpenGL.glPopMatrix();
        RenderHelper.disableStandardItemLighting();
        OpenGL.glDisable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        OpenGL.glDisable(OpenGL.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGL.glPopAttrib();
    }

}
