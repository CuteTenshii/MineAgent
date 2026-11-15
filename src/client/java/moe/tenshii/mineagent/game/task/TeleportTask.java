package moe.tenshii.mineagent.game.task;

import java.util.Locale;
import moe.tenshii.mineagent.game.Flight;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.path.PathWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Teleports the player with the server's tp command, after checking the destination is safe to arrive at.
 */
public final class TeleportTask implements Task {
	private static final int MAX_WAIT_TICKS = 40;
	// The server places the player at the block's center; anything this close counts as arrived.
	private static final double ARRIVED = 0.75;
	private static final int SAFE_FALL = 3;
	private static final int MAX_FALL_CHECK = 64;

	private final BlockPos target;
	private @Nullable Vec3 from;
	private int waited;
	private @Nullable String note;

	public TeleportTask(BlockPos target) {
		this.target = target.immutable();
	}

	/**
	 * The teleport command this player may use. The server only sends a player the commands they are allowed to run,
	 * so this is null without operator permission or cheats.
	 */
	public static @Nullable String command(@Nullable LocalPlayer player) {
		if (player == null || !player.chatAbilities().canSendCommands()) {
			return null;
		}
		var root = player.connection.getCommands().getRoot();
		if (root.getChild("tp") != null) {
			return "tp";
		}
		return root.getChild("teleport") != null ? "teleport" : null;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		if (from == null) {
			String command = command(player);
			if (command == null) {
				throw new TaskFailure("This player cannot teleport here: the server does not allow /tp for it (it needs operator "
					+ "permission, or cheats in singleplayer).");
			}
			note = checkDestination(mc, player);
			PlayerControl.takeControl(mc);
			PlayerControl.idle(mc);
			from = player.position();
			// The block center as a number: "-11.5" written from -11 would be inside block -12.
			player.connection.sendCommand(String.format(Locale.ROOT, "%s @s %.1f %d %.1f", command, target.getX() + 0.5, target.getY(), target.getZ() + 0.5));
			return null;
		}
		if (player.position().distanceTo(Vec3.atBottomCenterOf(target)) < ARRIVED) {
			return String.format(Locale.ROOT, "Teleported from %s to %s; %s.%s", BlockPos.containing(from).toShortString(),
				target.toShortString(), state(player), note == null ? "" : " " + note);
		}
		if (++waited > MAX_WAIT_TICKS) {
			throw new TaskFailure("The server did not move the player to " + target.toShortString() + " within "
				+ MAX_WAIT_TICKS / 20 + " seconds; it may have refused the teleport.");
		}
		return null;
	}

	/**
	 * Refuses destinations that would hurt the player: inside blocks, or above a long fall for a player who cannot fly.
	 *
	 * @return a note for the result when the destination could not be checked, or null
	 */
	private @Nullable String checkDestination(Minecraft mc, LocalPlayer player) throws TaskFailure {
		PathWorld world = new PathWorld(mc.level, player, false, false);
		if (world.state(target) == null || world.state(target.above()) == null) {
			return "The destination was not loaded, so it was not checked for safety; observe to see where the player ended up.";
		}
		if (!world.passable(target) || !world.passable(target.above())) {
			throw new TaskFailure("Not teleported: " + target.toShortString() + " has no room for the player (feet and head "
				+ "must both be open air or water, with no lava or fire). Pick an open spot.");
		}
		if (Flight.canFly(player) || world.supported(target) || world.climbable(target)) {
			return null;
		}
		for (int drop = 1; drop <= MAX_FALL_CHECK; drop++) {
			BlockPos below = target.below(drop);
			if (world.isWater(below)) {
				return null;
			}
			if (world.solidFloor(below.below())) {
				if (drop <= SAFE_FALL) {
					return null;
				}
				break;
			}
			if (!world.passable(below)) {
				break;
			}
		}
		throw new TaskFailure("Not teleported: there is nothing to stand on at " + target.toShortString()
			+ " and the player cannot fly, so it would fall more than " + SAFE_FALL + " blocks. Pick a spot on the ground.");
	}

	private static String state(LocalPlayer player) {
		if (Flight.isFlying(player)) {
			return "flying";
		}
		if (player.isInWater()) {
			return "in water";
		}
		return player.onGround() ? "standing on the ground" : "in the air, about to land";
	}

	@Override
	public int timeoutTicks() {
		return MAX_WAIT_TICKS + 20;
	}

	@Override
	public String describe() {
		return "teleporting to " + target.toShortString();
	}
}
