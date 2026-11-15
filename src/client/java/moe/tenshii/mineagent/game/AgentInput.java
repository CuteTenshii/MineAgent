package moe.tenshii.mineagent.game;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Replaces the keyboard as the player's movement source while the agent is in control.
 */
public final class AgentInput extends ClientInput {
	public boolean forward;
	public boolean backward;
	public boolean left;
	public boolean right;
	public boolean jump;
	public boolean sneak;
	public boolean sprint;

	public void clear() {
		forward = false;
		backward = false;
		left = false;
		right = false;
		jump = false;
		sneak = false;
		sprint = false;
	}

	@Override
	public void tick() {
		keyPresses = new Input(forward, backward, left, right, jump, sneak, sprint);
		moveVector = new Vec2(impulse(left, right), impulse(forward, backward)).normalized();
	}

	private static float impulse(boolean positive, boolean negative) {
		return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
	}
}
