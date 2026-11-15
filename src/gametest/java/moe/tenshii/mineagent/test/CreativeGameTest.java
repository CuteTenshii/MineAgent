package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.TASK_TIMEOUT_TICKS;
import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.check;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.count;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.run;
import static moe.tenshii.mineagent.test.GameTestKit.runExpectingFailure;

import java.util.concurrent.CompletableFuture;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.CreativeItems;
import moe.tenshii.mineagent.game.Flight;
import moe.tenshii.mineagent.game.GameThread;
import moe.tenshii.mineagent.game.InventoryOps;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.CommandTask;
import moe.tenshii.mineagent.game.task.FlyTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Creative mode and commands: the abilities line, give_item, flight, and run_command.
 */
public final class CreativeGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			cmd(world, "gamemode creative @a");
			// Singleplayer grants command permission only through the world's "allow commands" setting.
			world.getServer().runOnServer(server -> ((IntegratedServer) server).setWorldAllowCommands(true));
			ctx.waitTicks(10);
			abilities(ctx);
			creativeItems(ctx, world);
			flight(ctx, world, start);
			fastFlightAroundWall(ctx, world, start);
			commands(ctx, start);
			survivalRefusals(ctx, world);
		});
	}

	private static void abilities(ClientGameTestContext context) {
		String abilities = context.computeOnClient(mc -> WorldInfo.abilities(mc.player));
		log("Abilities in creative: " + abilities);
		assertTrue(abilities.contains("can fly") && abilities.contains("give_item") && !abilities.contains("no operator"),
			"observe should offer flight, creative items and commands: " + abilities);
	}

	private static void creativeItems(ClientGameTestContext context, TestSingleplayerContext world) throws TaskFailure {
		InventoryOps.SlotRef hotbar9 = InventoryOps.parseSlot("hotbar 9");
		String pickaxe = context.computeOnClient(mc -> CreativeItems.give(mc, Items.DIAMOND_PICKAXE, 1, null));
		String stone = context.computeOnClient(mc -> CreativeItems.give(mc, Items.STONE, 64, hotbar9));
		context.waitTicks(5);
		log("give_item: " + pickaxe + " | " + stone);
		check(context, mc -> count(mc, Items.DIAMOND_PICKAXE) == 1 && mc.player.getInventory().getItem(8).is(Items.STONE),
			"give_item should fill the client inventory: " + pickaxe + " " + stone);
		boolean serverHasItems = world.getServer().computeOnServer(server -> {
			Inventory inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
			return inventory.contains(new ItemStack(Items.DIAMOND_PICKAXE)) && inventory.getItem(8).is(Items.STONE)
				&& inventory.getItem(8).getCount() == 64;
		});
		assertTrue(serverHasItems, "the server should have the given items");
	}

	private static void flight(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ();
		// A wall across the whole cleared area, so the route has to go over it.
		cmd(world, "fill %d %d %d %d %d %d stone", x - 8, y, z - 12, x - 8, y + 3, z + 12);
		context.waitTicks(5);
		BlockPos landing = new BlockPos(x - 11, y, z + 1);
		String flight = run(context, FlyTask.to(landing, 0, true, 20 * 60));
		check(context, mc -> mc.player.blockPosition().equals(landing) && mc.player.onGround() && !Flight.isFlying(mc.player),
			"fly_to should cross the wall and land on " + landing.toShortString() + ": " + flight);

		BlockPos hover = new BlockPos(x - 11, y + 3, z + 4);
		String hovering = run(context, FlyTask.to(hover, 0, false, 20 * 60));
		context.waitTicks(10);
		String state = context.computeOnClient(mc -> mc.player.position() + " flying " + Flight.isFlying(mc.player));
		check(context, mc -> mc.player.blockPosition().equals(hover) && Flight.isFlying(mc.player),
			"fly_to with land=false should hover at " + hover.toShortString() + ": " + hovering + "; now at " + state);

		BlockPos ground = new BlockPos(x - 11, y, z + 6);
		String walk = run(context, new WalkTask(Goal.near(ground, 0), ground.toShortString(), true, true, TASK_TIMEOUT_TICKS));
		check(context, mc -> mc.player.blockPosition().equals(ground) && !Flight.isFlying(mc.player), "walk_to should land before walking: " + walk);
		cmd(world, "fill %d %d %d %d %d %d air", x - 8, y, z - 12, x - 8, y + 3, z + 12);
	}

	/**
	 * A long sprinting flight that must turn sharply around the end of a tall wall. Momentum used to carry the player
	 * off the route and make it fly back to it, so the distance to the target must never grow much.
	 */
	private static void fastFlightAroundWall(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ();
		cmd(world, "fill %d %d %d %d %d %d stone", x + 40, y, z - 20, x + 40, y + 15, z + 10);
		cmd(world, "tp @a %d.5 %d %d.5", x + 26, y, z);
		context.waitTicks(10);
		BlockPos target = new BlockPos(x + 54, y + 1, z);
		Vec3 aim = Vec3.atBottomCenterOf(target);
		CompletableFuture<String> result = GameTestKit.runAsync(FlyTask.to(target, 0, false, 20 * 60));
		double closest = Double.MAX_VALUE;
		double worstBacktrack = 0.0;
		int ticks = 0;
		while (!result.isDone() && ticks < 20 * 60) {
			context.waitTicks(1);
			ticks++;
			double distance = context.computeOnClient(mc -> mc.player.position().distanceTo(aim));
			closest = Math.min(closest, distance);
			worstBacktrack = Math.max(worstBacktrack, distance - closest);
		}
		String outcome = result.join();
		log(String.format("Fast flight -> %s in %.1f s, worst backtrack %.2f blocks", outcome, ticks / 20.0, worstBacktrack));
		assertTrue(!outcome.startsWith("FAILED"), "the flight around the wall should arrive: " + outcome);
		check(context, mc -> mc.player.blockPosition().equals(target), "the flight should end on " + target.toShortString());
		assertTrue(worstBacktrack < 3.0, "the flight should not double back to its route; it lost " + worstBacktrack + " blocks");
		cmd(world, "fill %d %d %d %d %d %d air", x + 40, y, z - 20, x + 40, y + 15, z + 10);
		cmd(world, "tp @a %d.5 %d %d.5", x - 11, y, z + 6);
		context.waitTicks(10);
	}

	private static void commands(ClientGameTestContext context, BlockPos start) {
		try (ConfigSnapshot ignored = new ConfigSnapshot()) {
			AgentConfig config = AgentConfig.get();
			config.allowCommands = false;
			String disabled = runExpectingFailure(context, new CommandTask("time set day"));
			assertTrue(disabled.contains("/agent commands on"), "commands must stay off until enabled: " + disabled);

			config.allowCommands = true;
			BlockPos gold = new BlockPos(start.getX() - 11, start.getY(), start.getZ() + 8);
			String setblock = run(context, new CommandTask(String.format("/setblock %d %d %d gold_block", gold.getX(), gold.getY(), gold.getZ())));
			check(context, mc -> mc.level.getBlockState(gold).is(Blocks.GOLD_BLOCK), "run_command should place the gold block: " + setblock);
			assertTrue(setblock.contains("Server messages"), "run_command should return the server's reply: " + setblock);
			String unknown = runExpectingFailure(context, new CommandTask("notacommand 1 2"));
			assertTrue(unknown.startsWith("Not sent"), "unknown commands must not be sent: " + unknown);
			String clientSide = runExpectingFailure(context, new CommandTask("agent stop"));
			assertTrue(clientSide.contains("client-side"), "the agent must not run its own client commands: " + clientSide);
		} catch (TaskFailure invalid) {
			throw new AssertionError(invalid.getMessage());
		}
	}

	private static void survivalRefusals(ClientGameTestContext context, TestSingleplayerContext world) {
		cmd(world, "gamemode survival @a");
		context.waitTicks(10);
		String give = context.computeOnClient(mc -> {
			try {
				return CreativeItems.give(mc, Items.DIAMOND, 1, null);
			} catch (GameThread.GameStateException refused) {
				return refused.getMessage();
			}
		});
		assertTrue(give.contains("creative mode"), "give_item must refuse outside creative: " + give);
		String takeOff = runExpectingFailure(context, FlyTask.takeOff());
		assertTrue(takeOff.contains("cannot fly"), "flight must refuse in survival: " + takeOff);
	}
}
