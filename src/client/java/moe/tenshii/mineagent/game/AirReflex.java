package moe.tenshii.mineagent.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Keeps the player from drowning while the agent is in control. Between actions it treads water at the surface;
 * during an action it takes over when air runs low, swims up (mining a block overhead if needed), and hands
 * control back once air has recovered. Client thread only.
 */
public final class AirReflex {
	private static final int GIVE_UP_TICKS = 20 * 20;

	private static boolean surfacing;
	private static int surfacingTicks;
	private static int surfacings;

	private AirReflex() {
	}

	public enum Outcome {
		/** Nothing to do; the action may tick normally. */
		NONE,
		/** The reflex drove the player this tick; skip the action's tick. */
		SURFACING,
		/** Air could not be reached in time. */
		GAVE_UP
	}

	/** Called every tick while the agent controls the player and no action is running. */
	public static void tickIdle(Minecraft mc) {
		LocalPlayer player = mc.player;
		surfacing = false;
		PlayerControl.input().jump = player != null && player.isInWater();
	}

	/** Called every tick before the running action's own tick. */
	public static Outcome tickDuringAction(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return Outcome.NONE;
		}
		int max = player.getMaxAirSupply();
		if (!surfacing) {
			if (!player.isUnderWater() || player.getAirSupply() >= max / 2) {
				return Outcome.NONE;
			}
			surfacing = true;
			surfacingTicks = 0;
			surfacings++;
			PlayerControl.stopMining(mc);
		}
		if (!player.isUnderWater() && player.getAirSupply() >= max) {
			surfacing = false;
			PlayerControl.input().clear();
			return Outcome.NONE;
		}
		if (++surfacingTicks > GIVE_UP_TICKS) {
			surfacing = false;
			PlayerControl.idle(mc);
			return Outcome.GAVE_UP;
		}
		AgentInput input = PlayerControl.input();
		input.clear();
		input.jump = true;
		BlockPos overhead = BlockPos.containing(player.getEyePosition()).above();
		boolean blocked = !mc.level.getBlockState(overhead).getCollisionShape(mc.level, overhead).isEmpty();
		if (player.isUnderWater() && blocked) {
			PlayerControl.mineTick(mc, overhead);
		} else {
			PlayerControl.stopMining(mc);
			PlayerControl.turnTowards(player, player.getYRot(), -60.0F);
		}
		return Outcome.SURFACING;
	}

	public static void beginAction() {
		surfacing = false;
		surfacings = 0;
	}

	/** How often the action was paused for air, or 0. */
	public static int endAction() {
		surfacing = false;
		return surfacings;
	}
}
