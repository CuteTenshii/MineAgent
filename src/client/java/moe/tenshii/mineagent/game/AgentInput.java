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
	public boolean jump;
	public boolean sneak;
	public boolean sprint;

	public void clear() {
		forward = false;
		backward = false;
		jump = false;
		sneak = false;
		sprint = false;
	}

	@Override
	public void tick() {
		keyPresses = new Input(forward, backward, false, false, jump, sneak, sprint);
		float forwardImpulse = forward == backward ? 0.0F : forward ? 1.0F : -1.0F;
		moveVector = new Vec2(0.0F, forwardImpulse);
	}
}
