package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.TASK_TIMEOUT_TICKS;
import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.run;
import static moe.tenshii.mineagent.test.GameTestKit.runExpectingFailure;

import java.util.List;
import moe.tenshii.mineagent.game.Aim;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.FaceTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Turning the head with face: toward each kind of target, after a walk has turned it, and before throwing.
 */
public final class FacingGameTest implements FabricClientGameTest {
	private static final float TOLERANCE = 2.5F;

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			directionsAndAngles(ctx);
			blockAndEntity(ctx, world, start);
			afterWalking(ctx, start);
			throwingWhereFaced(ctx, world);
		});
	}

	private static void directionsAndAngles(ClientGameTestContext context) throws TaskFailure {
		String west = run(context, new FaceTask(Aim.direction("west")));
		assertRotation(context, 90.0F, 0.0F, "face west: " + west);
		assertTrue(west.contains("facing west"), "face should report the new facing: " + west);

		run(context, new FaceTask(new Aim.Angle(-45.0F, -30.0F)));
		assertRotation(context, -45.0F, -30.0F, "face yaw -45, pitch -30");

		run(context, new FaceTask(Aim.direction("up")));
		assertRotation(context, -45.0F, -90.0F, "face up should keep the yaw");

		String observe = context.computeOnClient(mc -> WorldInfo.observe(mc));
		assertTrue(observe.contains("(yaw -45, pitch -90)"), "observe should report yaw and pitch: " + observe.lines().findFirst().orElse(""));
	}

	private static void blockAndEntity(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		BlockPos gold = start.offset(4, 0, -3);
		cmd(world, "setblock %d %d %d gold_block", gold.getX(), gold.getY(), gold.getZ());
		context.waitTicks(5);
		String faced = run(context, new FaceTask(new Aim.AtBlock(gold)));
		BlockPos centered = context.computeOnClient(mc -> mc.player.pick(64.0, 1.0F, false) instanceof BlockHitResult hit
			&& hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null);
		assertTrue(gold.equals(centered), "face should center the view on the gold block: " + faced);
		assertTrue(faced.contains("gold"), "face should name the block at the center of the view: " + faced);

		cmd(world, "summon armor_stand %d %d %d {NoGravity:1b,Tags:[\"facing\"]}", start.getX() - 5, start.getY(), start.getZ() + 4);
		context.waitTicks(5);
		int id = context.computeOnClient(mc -> mc.level.getEntitiesOfClass(Entity.class, mc.player.getBoundingBox().inflate(16),
			entity -> entity.getType() == EntityTypes.ARMOR_STAND).getFirst().getId());
		run(context, new FaceTask(new Aim.AtEntity(id)));
		boolean facingStand = context.computeOnClient(mc -> {
			Entity stand = mc.level.getEntity(id);
			return PlayerControl.isFacing(mc.player, stand.getBoundingBox().getCenter(), TOLERANCE);
		});
		assertTrue(facingStand, "face should turn toward the armor stand #" + id);
		String gone = runExpectingFailure(context, new FaceTask(new Aim.AtEntity(987654)));
		assertTrue(gone.contains("not loaded"), "facing a missing entity should fail clearly: " + gone);
		cmd(world, "kill @e[tag=facing]");
		cmd(world, "setblock %d %d %d air", gold.getX(), gold.getY(), gold.getZ());
	}

	/** The walk turns the head toward its last step; face must be able to turn it anywhere afterwards. */
	private static void afterWalking(ClientGameTestContext context, BlockPos start) throws TaskFailure {
		BlockPos east = start.offset(6, 0, 0);
		run(context, new WalkTask(Goal.near(east, 0), east.toShortString(), false, false, TASK_TIMEOUT_TICKS));
		float walkYaw = context.computeOnClient(mc -> Mth.wrapDegrees(mc.player.getYRot()));
		log("Yaw after walking east: " + walkYaw);
		run(context, new FaceTask(Aim.direction("north")));
		assertRotation(context, 180.0F, 0.0F, "face north after walking east (yaw was " + walkYaw + ")");
	}

	private static void throwingWhereFaced(ClientGameTestContext context, TestSingleplayerContext world) throws TaskFailure {
		cmd(world, "give @a snowball 4");
		context.waitTicks(5);
		run(context, new FaceTask(Aim.direction("west")));
		Vec3 thrownFrom = context.computeOnClient(mc -> {
			PlayerControl.holdItem(mc, stack -> stack.is(Items.SNOWBALL));
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			return mc.player.position();
		});
		context.waitTicks(4);
		List<Vec3> snowballs = context.computeOnClient(mc -> mc.level.getEntitiesOfClass(Entity.class, new AABB(thrownFrom, thrownFrom).inflate(24),
			entity -> entity.getType() == EntityTypes.SNOWBALL).stream().map(Entity::position).toList());
		log("Snowballs after facing west: " + snowballs + " thrown from " + thrownFrom);
		assertTrue(snowballs.size() == 1, "one snowball should be in flight: " + snowballs);
		Vec3 flight = snowballs.getFirst().subtract(thrownFrom);
		assertTrue(flight.x < -1.5 && Math.abs(flight.z) < 0.5, "the snowball should fly west, where the player faces: moved " + flight);
	}

	private static void assertRotation(ClientGameTestContext context, float yaw, float pitch, String what) {
		float[] actual = context.computeOnClient(mc -> new float[] {mc.player.getYRot(), mc.player.getXRot()});
		boolean matches = Math.abs(Mth.wrapDegrees(actual[0] - yaw)) <= TOLERANCE && Math.abs(actual[1] - pitch) <= TOLERANCE;
		assertTrue(matches, what + ": expected yaw " + yaw + ", pitch " + pitch + " but got yaw " + Mth.wrapDegrees(actual[0]) + ", pitch " + actual[1]);
	}
}
