package moe.tenshii.mineagent.ui;

import moe.tenshii.mineagent.agent.AgentController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One-line status in the top-left corner while a goal is running.
 */
public final class AgentHud implements HudElement {
	private static final int MAX_WIDTH = 320;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int GOAL_COLOR = 0xFFA0E8FF;
	private static final int BACKGROUND = 0x90000000;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		AgentController controller = AgentController.get();
		String goal = controller.currentGoal();
		Minecraft mc = Minecraft.getInstance();
		if (goal == null || mc.gui.hud.isHidden()) {
			return;
		}
		Font font = mc.font;
		String goalLine = font.plainSubstrByWidth("Agent: " + goal, MAX_WIDTH);
		String phaseLine = font.plainSubstrByWidth(controller.phase(), MAX_WIDTH);
		int width = Math.max(font.width(goalLine), font.width(phaseLine)) + 6;
		graphics.fill(2, 2, 2 + width, 2 + font.lineHeight * 2 + 6, BACKGROUND);
		graphics.text(font, goalLine, 5, 5, GOAL_COLOR);
		graphics.text(font, phaseLine, 5, 5 + font.lineHeight + 1, TEXT_COLOR);
	}
}
