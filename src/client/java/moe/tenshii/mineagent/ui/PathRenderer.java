package moe.tenshii.mineagent.ui;

import java.util.List;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.path.MoveType;
import moe.tenshii.mineagent.path.PathDisplay;
import moe.tenshii.mineagent.path.PathStep;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the agent's current path in the world. Runs from the end-of-tick event, which vanilla wraps in its per-tick
 * gizmo collection, so the shapes are shown until the next tick replaces them.
 */
public final class PathRenderer {
	private static final int ROUTE_COLOR = 0xFF33DDFF;
	private static final int BREAK_COLOR = 0xFFFF5555;
	private static final int PILLAR_COLOR = 0xFFFFD84A;
	private static final int GOAL_COLOR = 0xFF55FF77;
	private static final float ROUTE_WIDTH = 3.0F;
	private static final float OUTLINE_WIDTH = 2.0F;
	private static final double LINE_HEIGHT = 0.15;
	private static final int MAX_STEPS_DRAWN = 256;

	private PathRenderer() {
	}

	public static void tick(Minecraft mc) {
		List<PathStep> steps = PathDisplay.remaining();
		if (steps.isEmpty() || mc.player == null || !AgentConfig.get().showPath) {
			return;
		}
		Vec3 previous = mc.player.position().add(0.0, LINE_HEIGHT, 0.0);
		int drawn = 0;
		for (PathStep step : steps) {
			if (++drawn > MAX_STEPS_DRAWN) {
				break;
			}
			Vec3 point = Vec3.atBottomCenterOf(step.dest()).add(0.0, LINE_HEIGHT, 0.0);
			Gizmos.line(previous, point, ROUTE_COLOR, ROUTE_WIDTH).setAlwaysOnTop();
			for (BlockPos block : step.toBreak()) {
				Gizmos.cuboid(block, 0.02F, GizmoStyle.stroke(BREAK_COLOR, OUTLINE_WIDTH)).setAlwaysOnTop();
			}
			if (step.type() == MoveType.PILLAR) {
				Gizmos.cuboid(step.from(), 0.02F, GizmoStyle.stroke(PILLAR_COLOR, OUTLINE_WIDTH)).setAlwaysOnTop();
			}
			previous = point;
		}
		Gizmos.cuboid(steps.get(Math.min(steps.size(), MAX_STEPS_DRAWN) - 1).dest(), 0.02F, GizmoStyle.stroke(GOAL_COLOR, OUTLINE_WIDTH))
			.setAlwaysOnTop();
	}
}
