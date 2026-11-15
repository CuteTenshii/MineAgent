package moe.tenshii.mineagent.game.task;

import moe.tenshii.mineagent.game.Aim;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Turns the player's head toward a block, an entity or an angle, the way a player would move the mouse.
 */
public final class FaceTask implements Task {
	static final float TOLERANCE = 2.0F;
	// Turning is limited per tick, so even a half turn finishes well within this.
	static final int MAX_TURN_TICKS = 20;

	private final Aim aim;
	private int turnTicks;

	public FaceTask(Aim aim) {
		this.aim = aim;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		PlayerControl.input().clear();
		if (!Aim.turnStep(mc, player, aim, TOLERANCE) && ++turnTicks <= MAX_TURN_TICKS) {
			return null;
		}
		return "Turned toward " + aim.label() + ", now " + WorldInfo.view(mc, player);
	}

	@Override
	public int timeoutTicks() {
		return MAX_TURN_TICKS + 20;
	}

	@Override
	public String describe() {
		return "turning toward " + aim.label();
	}
}
