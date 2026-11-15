package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.check;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.count;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.run;

import java.util.concurrent.CompletableFuture;
import moe.tenshii.mineagent.game.DamageTracker;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.task.GatherTask;
import moe.tenshii.mineagent.game.task.WaitTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;

/**
 * What the agent does on its own while in control: fighting back when hit, and surfacing for air.
 */
public final class ReflexGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			combat(ctx, world, start);
			drowning(ctx, world, start);
		});
	}

	private static void combat(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) throws TaskFailure {
		cmd(world, "difficulty easy");
		cmd(world, "give @a iron_sword");
		cmd(world, "give @a stone_pickaxe");
		for (int i = 0; i < 4; i++) {
			cmd(world, "setblock %d %d %d stone", start.getX() - 8 + i * 2, start.getY(), start.getZ() - 8);
		}
		context.runOnClient(PlayerControl::takeControl);
		// Fire resistance keeps the zombie from burning up in daylight before it reaches the player.
		cmd(world, "summon zombie %d %d %d {Tags:[\"fighter\"]}", start.getX() + 5, start.getY(), start.getZ());
		cmd(world, "effect give @e[tag=fighter] fire_resistance 120 0 true");
		context.waitTicks(2);
		int cobbleBefore = context.computeOnClient(mc -> count(mc, Items.COBBLESTONE));
		context.runOnClient(DamageTracker::begin);
		String gather = run(context, new GatherTask(Lookup.block("minecraft:stone"), 4, 16));
		String report = context.computeOnClient(DamageTracker::end);
		log("Gather with a zombie: " + gather + " | " + report);
		check(context, mc -> count(mc, Items.COBBLESTONE) >= cobbleBefore + 4, "the gather should finish after the fight: " + gather);
		check(context, mc -> mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(32), Zombie::isAlive).isEmpty(),
			"the reflex should kill the zombie: " + report);
		assertTrue(report != null && report.matches("(?s).*Fought back during this action: zombie #\\d+ \\(killed\\).*"),
			"the result should name the zombie that was fought: " + report);

		cmd(world, "effect give @a instant_health 1 5");
		context.waitTicks(5);
		cmd(world, "damage @p 18 minecraft:generic");
		// Resistance IV, applied after the setup damage, keeps the low-health part from turning into a death.
		cmd(world, "effect give @a resistance 60 3 true");
		BlockPos pos = context.computeOnClient(mc -> mc.player.blockPosition());
		// No AI, so the only hit is the scripted one below.
		cmd(world, "summon zombie %d %d %d {NoAI:1b,Tags:[\"fighter\"]}", pos.getX() + 2, pos.getY(), pos.getZ());
		cmd(world, "effect give @e[tag=fighter] fire_resistance 120 0 true");
		CompletableFuture<String> waiting = GameTestKit.runAsync(new WaitTask(20 * 15));
		// Past the setup hit's invulnerability window, which would swallow a weaker hit.
		context.waitTicks(25);
		check(context, mc -> mc.player.getHealth() <= 6.0F, "the setup should leave the player at low health");
		cmd(world, "damage @p 1 minecraft:mob_attack by @e[tag=fighter,limit=1]");
		context.waitFor(mc -> waiting.isDone(), 20 * 20);
		String interrupted = waiting.join();
		log("Low-health fight: " + interrupted);
		assertTrue(interrupted.startsWith("FAILED: Interrupted: health is low") && interrupted.contains("zombie #"),
			"a fight at low health should interrupt the action: " + interrupted);
		String passive = run(context, new WaitTask(20 * 2));
		assertTrue(passive.startsWith("Waited"), "the action after the interruption should run without the reflex: " + passive);

		cmd(world, "kill @e[tag=fighter]");
		cmd(world, "effect clear @a");
		cmd(world, "effect give @a instant_health 1 5");
		cmd(world, "difficulty peaceful");
		context.runOnClient(PlayerControl::releaseControl);
		context.waitTicks(10);
	}

	private static void drowning(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ();
		// The test world is superflat with bedrock 4 blocks down, so the pool is 3 deep: enough to be fully submerged.
		cmd(world, "fill %d %d %d %d %d %d water", x - 10, y - 3, z + 8, x - 7, y - 1, z + 11);
		String bottom = String.format("tp @a %d.5 %d %d.5", x - 9, y - 3, z + 9);
		context.waitTicks(5);

		context.runOnClient(PlayerControl::takeControl);
		cmd(world, bottom);
		context.waitTicks(20 * 25);
		String idleState = context.computeOnClient(mc -> String.format("health %.1f, air %d, y %.2f, underwater %s, input %s",
			mc.player.getHealth(), mc.player.getAirSupply(), mc.player.getY(), mc.player.isUnderWater(), mc.player.input.getClass().getSimpleName()));
		log("Idle in water: " + idleState);
		check(context, mc -> mc.player.getHealth() >= mc.player.getMaxHealth() && mc.player.getAirSupply() >= mc.player.getMaxAirSupply() / 2,
			"idle in water: should float up and take no damage (" + idleState + ")");

		cmd(world, bottom);
		context.waitTicks(5);
		context.runOnClient(DamageTracker::begin);
		String wait = run(context, new HoldStill(20 * 20));
		String report = context.computeOnClient(DamageTracker::end);
		log("Underwater wait: " + wait + " | " + report);
		check(context, mc -> mc.player.getHealth() >= mc.player.getMaxHealth(), "the reflex should surface during a wait: " + report);
		assertTrue(report != null && report.contains("to swim up for air"), "the result should mention surfacing: " + report);

		cmd(world, "tp @a %d.5 %d %d.5", x - 5, y, z + 9);
		context.waitTicks(10);
		BlockPos across = new BlockPos(x - 12, y, z + 9);
		context.runOnClient(DamageTracker::begin);
		String cross = run(context, new WalkTask(Goal.near(across, 0), across.toShortString(), true, true, 20 * 60));
		String crossReport = context.computeOnClient(DamageTracker::end);
		log("Pool crossing: " + cross + " | " + crossReport);
		check(context, mc -> mc.player.blockPosition().equals(across) && mc.player.getHealth() >= mc.player.getMaxHealth(),
			"cross the pool without drowning: " + cross + " | " + crossReport);
	}

	/** Holds the controls still like a busy action (mining, using a block) would, so only the reflex can surface. */
	private static final class HoldStill implements Task {
		private final int duration;
		private int ticks;

		HoldStill(int duration) {
			this.duration = duration;
		}

		@Override
		public String tick(Minecraft mc) {
			PlayerControl.input().clear();
			return ++ticks >= duration ? "Held still for " + duration / 20 + " seconds." : null;
		}

		@Override
		public int timeoutTicks() {
			return duration * 2;
		}

		@Override
		public String describe() {
			return "holding still underwater";
		}
	}
}
