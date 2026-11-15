package moe.tenshii.mineagent.test;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.TaskRunner;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Shared steps for the client game tests: a prepared world, running tasks the way the agent does, and checks.
 */
final class GameTestKit {
	static final int TASK_TIMEOUT_TICKS = 20 * 120;
	private static final String FAILED = "FAILED: ";

	/** A test body that runs in a prepared world; lookups such as {@link Lookup#item} may throw. */
	@FunctionalInterface
	interface WorldTest {
		void run(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) throws TaskFailure;
	}

	private GameTestKit() {
	}

	/**
	 * Creates a fresh world, clears a 25x25 stone-floored area around the player so layouts do not depend on terrain,
	 * and runs {@code test} there.
	 */
	static void inWorld(ClientGameTestContext context, WorldTest test) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitFor(mc -> mc.player != null && mc.level != null && mc.player.onGround(), 20 * 30);
			cmd(world, "gamemode survival @a");
			cmd(world, "difficulty peaceful");
			cmd(world, "time set day");
			cmd(world, "clear @a");
			cmd(world, "recipe give @a *");
			BlockPos start = context.computeOnClient(mc -> mc.player.blockPosition());
			int x = start.getX();
			int y = start.getY();
			int z = start.getZ();
			cmd(world, "fill %d %d %d %d %d %d air", x - 12, y, z - 12, x + 12, y + 6, z + 12);
			cmd(world, "fill %d %d %d %d %d %d stone", x - 12, y - 1, z - 12, x + 12, y - 1, z + 12);
			context.waitTicks(10);
			test.run(context, world, start);
			context.runOnClient(PlayerControl::releaseControl);
		} catch (TaskFailure badLookup) {
			throw new AssertionError(badLookup.getMessage());
		}
	}

	/** Runs a server command, formatting it when arguments are given. */
	static void cmd(TestSingleplayerContext world, String command, Object... args) {
		world.getServer().runCommand(args.length == 0 ? command : String.format(Locale.ROOT, command, args));
	}

	/** Starts a task from a background thread, as the agent does; the result is the outcome or "FAILED: reason". */
	static CompletableFuture<String> runAsync(Task task) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return TaskRunner.run(task, () -> false);
			} catch (TaskFailure failure) {
				return FAILED + failure.getMessage();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return FAILED + "interrupted";
			}
		});
	}

	/** Runs a task to completion while the client keeps ticking, and fails the test if the task fails. */
	static String run(ClientGameTestContext context, Task task) {
		String outcome = await(context, task);
		log(task.describe() + " -> " + outcome);
		if (outcome.startsWith(FAILED)) {
			throw new AssertionError(task.describe() + " failed: " + outcome);
		}
		return outcome;
	}

	/** Runs a task that should fail and returns its error message. */
	static String runExpectingFailure(ClientGameTestContext context, Task task) {
		String outcome = await(context, task);
		if (!outcome.startsWith(FAILED)) {
			throw new AssertionError(task.describe() + " should have failed but returned: " + outcome);
		}
		return outcome.substring(FAILED.length());
	}

	private static String await(ClientGameTestContext context, Task task) {
		CompletableFuture<String> result = runAsync(task);
		context.waitFor(mc -> result.isDone(), task.timeoutTicks() + 40);
		return result.join();
	}

	/** Checks the client state after letting a couple of ticks pass for server updates to arrive. */
	static void check(ClientGameTestContext context, Predicate<Minecraft> condition, String message) {
		context.waitTicks(2);
		if (!context.computeOnClient(condition::test)) {
			throw new AssertionError(message);
		}
	}

	static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	static int count(Minecraft mc, Item item) {
		return PlayerControl.countItems(mc.player, (ItemStack stack) -> stack.is(item));
	}

	static void log(String message) {
		System.out.println("[MineAgent test] " + message);
	}
}
