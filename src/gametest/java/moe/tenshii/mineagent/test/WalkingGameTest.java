package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.TASK_TIMEOUT_TICKS;
import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.check;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.run;

import java.util.concurrent.CompletableFuture;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * How the walker follows paths: speed on a winding course, doors and gates, ladders, and drops into water.
 */
public final class WalkingGameTest implements FabricClientGameTest {
	// The walker before sprinting and cutting corners took 322 ticks on the course.
	private static final long COURSE_TICKS_LIMIT = 275;

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			course(ctx, world, start);
			doorAndGate(ctx, world, start);
			ladderAndWaterDrop(ctx, world, start);
		});
	}

	/** Two staggered walls make an S-shaped route of about 60 blocks with straight runs, corners and diagonals. */
	private static void course(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ();
		cmd(world, "fill %d %d %d %d %d %d stone", x - 4, y, z - 30, x - 4, y + 2, z + 6);
		cmd(world, "fill %d %d %d %d %d %d stone", x + 4, y, z - 6, x + 4, y + 2, z + 30);
		cmd(world, "tp @a %d.5 %d %d.5", x - 10, y, z - 10);
		context.waitTicks(10);
		BlockPos finish = new BlockPos(x + 10, y, z + 10);
		long before = context.computeOnClient(mc -> mc.level.getGameTime());
		CompletableFuture<String> walking = GameTestKit.runAsync(new WalkTask(Goal.near(finish, 0), finish.toShortString(), false, false, TASK_TIMEOUT_TICKS));
		int airborne = 0;
		while (!walking.isDone()) {
			context.waitTicks(1);
			if (!context.computeOnClient(mc -> mc.player.onGround())) {
				airborne++;
			}
		}
		String walk = walking.join();
		long ticks = context.computeOnClient(mc -> mc.level.getGameTime()) - before;
		log(String.format("Course walk took %d ticks (%.1f s), %d of them in the air: %s", ticks, ticks / 20.0, airborne, walk));
		assertTrue(!walk.startsWith("FAILED"), "the course walk should arrive: " + walk);
		check(context, mc -> mc.player.blockPosition().equals(finish), "the course walk should end on " + finish.toShortString() + ": " + walk);
		assertTrue(ticks < COURSE_TICKS_LIMIT, "the course should take under " + COURSE_TICKS_LIMIT + " ticks, took " + ticks);
		assertTrue(airborne == 0, "the course is flat, so the walker should never jump; it was in the air for " + airborne + " ticks");
		cmd(world, "fill %d %d %d %d %d %d air", x - 4, y, z - 30, x - 4, y + 2, z + 6);
		cmd(world, "fill %d %d %d %d %d %d air", x + 4, y, z - 6, x + 4, y + 2, z + 30);
	}

	/**
	 * Long walls with a door in one and a fence gate in the other. Breaking through either is cheaper than walking
	 * around, so the walker used to break them.
	 */
	private static void doorAndGate(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ();
		cmd(world, "fill %d %d %d %d %d %d stone", x - 40, y, z + 2, x + 40, y + 2, z + 2);
		cmd(world, "setblock %d %d %d oak_door[half=lower,facing=south]", x, y, z + 2);
		cmd(world, "setblock %d %d %d oak_door[half=upper,facing=south]", x, y + 1, z + 2);
		cmd(world, "fill %d %d %d %d %d %d oak_fence", x - 40, y, z + 8, x + 40, y, z + 8);
		cmd(world, "setblock %d %d %d oak_fence_gate[facing=south]", x, y, z + 8);
		cmd(world, "tp @a %d.5 %d %d.5", x + 1, y, z - 3);
		context.waitTicks(10);

		BlockPos pastDoor = new BlockPos(x - 1, y, z + 6);
		String throughDoor = walk(context, pastDoor);
		check(context, mc -> mc.player.blockPosition().equals(pastDoor), "the walk should go through the door: " + throughDoor);
		check(context, mc -> mc.level.getBlockState(new BlockPos(x, y, z + 2)).is(Blocks.OAK_DOOR)
			&& mc.level.getBlockState(new BlockPos(x, y + 1, z + 2)).is(Blocks.OAK_DOOR), "the door should be opened, not broken: " + throughDoor);
		assertTrue(!throughDoor.contains("broke"), "nothing should be broken on the way through the door: " + throughDoor);

		BlockPos pastGate = new BlockPos(x + 1, y, z + 11);
		String throughGate = walk(context, pastGate);
		check(context, mc -> mc.player.blockPosition().equals(pastGate), "the walk should go through the gate: " + throughGate);
		check(context, mc -> mc.level.getBlockState(new BlockPos(x, y, z + 8)).is(Blocks.OAK_FENCE_GATE), "the gate should be opened, not broken: " + throughGate);
		assertTrue(!throughGate.contains("broke"), "nothing should be broken on the way through the gate: " + throughGate);

		cmd(world, "fill %d %d %d %d %d %d air", x - 40, y, z + 2, x + 40, y + 2, z + 2);
		cmd(world, "fill %d %d %d %d %d %d air", x - 40, y, z + 8, x + 40, y, z + 8);
	}

	/**
	 * Up a ladder onto a 4-block tower the player has no blocks to pillar up, then, with the ladder gone, down a
	 * 5-block drop into a pool, which is only safe because it lands in water.
	 */
	private static void ladderAndWaterDrop(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ();
		cmd(world, "fill %d %d %d %d %d %d stone", x - 8, y, z - 8, x - 6, y + 3, z - 6);
		cmd(world, "fill %d %d %d %d %d %d ladder[facing=west]", x - 9, y, z - 7, x - 9, y + 3, z - 7);
		// The test world is superflat with bedrock 4 blocks down, so the pool is 3 deep.
		cmd(world, "fill %d %d %d %d %d %d water", x - 5, y - 3, z - 8, x - 3, y - 1, z - 6);
		cmd(world, "tp @a %d.5 %d %d.5", x - 11, y, z - 7);
		context.waitTicks(10);

		BlockPos top = new BlockPos(x - 7, y + 4, z - 7);
		String climb = walk(context, top);
		check(context, mc -> mc.player.blockPosition().equals(top), "the walk should climb the ladder onto the tower: " + climb);
		check(context, mc -> mc.level.getBlockState(new BlockPos(x - 9, y + 3, z - 7)).is(Blocks.LADDER), "the ladder should be left in place: " + climb);
		assertTrue(!climb.contains("broke"), "nothing should be broken on the way up: " + climb);

		cmd(world, "fill %d %d %d %d %d %d air", x - 9, y, z - 7, x - 9, y + 3, z - 7);
		context.waitTicks(5);
		BlockPos beyondPool = new BlockPos(x - 1, y, z - 7);
		String drop = walk(context, beyondPool);
		check(context, mc -> mc.player.blockPosition().equals(beyondPool) && mc.player.getHealth() >= mc.player.getMaxHealth(),
			"the walk should drop into the pool and climb out unhurt: " + drop);
		assertTrue(!drop.contains("broke"), "the way down should be the drop, not digging through the tower: " + drop);

		cmd(world, "fill %d %d %d %d %d %d air", x - 8, y, z - 8, x - 6, y + 3, z - 6);
		cmd(world, "fill %d %d %d %d %d %d stone", x - 5, y - 3, z - 8, x - 3, y - 1, z - 6);
	}

	private static String walk(ClientGameTestContext context, BlockPos target) {
		return run(context, new WalkTask(Goal.near(target, 0), target.toShortString(), true, false, TASK_TIMEOUT_TICKS));
	}
}
