package moe.tenshii.mineagent.game;

import moe.tenshii.mineagent.path.PathWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Creative-style flight: turning it on and off and finding somewhere safe to come down. Client thread only.
 */
public final class Flight {
	public static final int MAX_LANDING_DEPTH = 64;

	private Flight() {
	}

	/** Creative and spectator players may fly, and so may anyone a server or mod grants flight. */
	public static boolean canFly(LocalPlayer player) {
		return player.getAbilities().mayfly;
	}

	public static boolean isFlying(LocalPlayer player) {
		return player.getAbilities().flying;
	}

	/** Tells the server too, the same way a double-tapped jump does. */
	public static void setFlying(LocalPlayer player, boolean flying) {
		if (player.getAbilities().flying != flying) {
			player.getAbilities().flying = flying;
			player.onUpdateAbilities();
		}
	}

	/**
	 * Where the feet end up when descending straight down from {@code feet}: on the first solid floor, or in the
	 * first water.
	 *
	 * @return null when the column reaches lava, a hazard, unloaded chunks or the void first
	 */
	public static @Nullable BlockPos landingSpot(PathWorld world, BlockPos feet) {
		for (int depth = 0; depth <= MAX_LANDING_DEPTH; depth++) {
			BlockPos pos = feet.below(depth);
			if (world.isWater(pos)) {
				return pos;
			}
			if (world.passable(pos)) {
				continue;
			}
			if (depth == 0) {
				// The feet are already inside a low block such as a slab or snow layer, which is where they will rest.
				BlockState state = world.state(pos);
				return state != null && state.getFluidState().isEmpty() && !world.isHazard(pos) ? pos : null;
			}
			return world.solidFloor(pos) ? pos.above() : null;
		}
		return null;
	}

	public static TaskFailure unavailable(Minecraft mc) {
		return new TaskFailure("This player cannot fly: flight needs creative or spectator mode, or a server that grants it (game mode is "
			+ mc.gameMode.getPlayerMode().getName() + ").");
	}
}
