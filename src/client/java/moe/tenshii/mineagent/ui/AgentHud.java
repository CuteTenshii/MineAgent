package moe.tenshii.mineagent.ui;

import java.util.List;
import java.util.Locale;
import moe.tenshii.mineagent.agent.AgentController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Status in the top-left corner while a goal is running: the goal, the current action and the tokens used so far.
 */
public final class AgentHud implements HudElement {
	private static final int MAX_WIDTH = 320;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int GOAL_COLOR = 0xFFA0E8FF;
	private static final int TOKEN_COLOR = 0xFFB0B0B0;
	private static final int BACKGROUND = 0x90000000;
	private static final int PADDING = 3;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		AgentController controller = AgentController.get();
		String goal = controller.currentGoal();
		Minecraft mc = Minecraft.getInstance();
		if (goal == null || mc.gui.hud.isHidden()) {
			return;
		}
		Font font = mc.font;
		List<String> lines = List.of(
			font.plainSubstrByWidth("Agent: " + goal, MAX_WIDTH),
			font.plainSubstrByWidth(controller.phase(), MAX_WIDTH),
			"Tokens: " + compact(controller.inputTokens()) + " in / " + compact(controller.outputTokens()) + " out");
		List<Integer> colors = List.of(GOAL_COLOR, TEXT_COLOR, TOKEN_COLOR);
		int width = lines.stream().mapToInt(font::width).max().orElse(0) + PADDING * 2;
		int lineStep = font.lineHeight + 1;
		graphics.fill(2, 2, 2 + width, 2 + lineStep * lines.size() + PADDING * 2 - 1, BACKGROUND);
		for (int i = 0; i < lines.size(); i++) {
			graphics.text(font, lines.get(i), 2 + PADDING, 2 + PADDING + i * lineStep, colors.get(i));
		}
	}

	/** 950, 12.3k, 1.2M. */
	static String compact(long tokens) {
		if (tokens < 1_000) {
			return Long.toString(tokens);
		}
		if (tokens < 1_000_000) {
			return String.format(Locale.ROOT, "%.1fk", tokens / 1_000.0);
		}
		return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
	}
}
