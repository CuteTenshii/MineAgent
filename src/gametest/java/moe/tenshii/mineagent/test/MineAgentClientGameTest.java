package moe.tenshii.mineagent.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.AgentInput;
import moe.tenshii.mineagent.game.CreativeItems;
import moe.tenshii.mineagent.game.Flight;
import moe.tenshii.mineagent.game.GameThread;
import moe.tenshii.mineagent.game.DamageTracker;
import moe.tenshii.mineagent.game.InventoryOps;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.TaskRunner;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.AttackTask;
import moe.tenshii.mineagent.game.task.ContainerTask;
import moe.tenshii.mineagent.game.task.CollectTask;
import moe.tenshii.mineagent.game.task.CommandTask;
import moe.tenshii.mineagent.game.task.CraftTask;
import moe.tenshii.mineagent.game.task.DropTask;
import moe.tenshii.mineagent.game.task.EatTask;
import moe.tenshii.mineagent.game.task.FlyTask;
import moe.tenshii.mineagent.game.task.GatherTask;
import moe.tenshii.mineagent.game.task.MineTask;
import moe.tenshii.mineagent.game.task.PlaceTask;
import moe.tenshii.mineagent.game.task.SmeltTask;
import moe.tenshii.mineagent.game.task.UseBlockTask;
import moe.tenshii.mineagent.game.task.WaitTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the real tasks in a singleplayer world, then runs the whole agent loop against a scripted
 * OpenAI-compatible server so no API key or network access is needed.
 */
public final class MineAgentClientGameTest implements FabricClientGameTest {
	private static final int TASK_TIMEOUT_TICKS = 20 * 120;

	@Override
	public void runTest(ClientGameTestContext context) {
		try {
			scenario(context);
		} catch (TaskFailure badLookup) {
			throw new AssertionError(badLookup.getMessage());
		}
	}

	private static void scenario(ClientGameTestContext context) throws TaskFailure {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitFor(mc -> mc.player != null && mc.level != null && mc.player.onGround(), 20 * 30);
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("difficulty peaceful");
			world.getServer().runCommand("time set day");
			world.getServer().runCommand("clear @a");
			world.getServer().runCommand("recipe give @a *");
			context.waitTicks(10);

			testInventoryOps(context, world);
			testDropping(context, world);

			BlockPos start = context.computeOnClient(mc -> mc.player.blockPosition());
			int x = start.getX();
			int y = start.getY();
			int z = start.getZ();
			log("Player starts at " + start.toShortString() + " standing on "
				+ context.computeOnClient(mc -> Lookup.name(mc.level.getBlockState(start.below()).getBlock())));

			// Clear a working area and give it a solid floor so the layout does not depend on the generated terrain.
			world.getServer().runCommand(String.format("fill %d %d %d %d %d %d air", x - 12, y, z - 12, x + 12, y + 6, z + 12));
			world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x - 12, y - 1, z - 12, x + 12, y - 1, z + 12));
			world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x + 3, y, z - 3, x + 3, y + 2, z + 3));
			context.waitTicks(5);

			BlockPos walkTarget = new BlockPos(x + 6, y, z);
			WalkTask walkTask = new WalkTask(Goal.near(walkTarget, 0), walkTarget.toShortString(), true, true, TASK_TIMEOUT_TICKS);
			CompletableFuture<String> walking = CompletableFuture.supplyAsync(() -> {
				try {
					return TaskRunner.run(walkTask, () -> false);
				} catch (TaskFailure | InterruptedException e) {
					return "FAILED: " + e.getMessage();
				}
			});
			context.waitTicks(8);
			int pathGizmos = context.computeOnClient(mc -> mc.getPerTickGizmos().size());
			Path screenshot = context.takeScreenshot("mineagent-path");
			log("Path overlay: " + pathGizmos + " gizmos this tick, screenshot " + screenshot);
			assertTrue(pathGizmos > 0, "the path should be drawn while walking");
			context.waitFor(mc -> walking.isDone(), TASK_TIMEOUT_TICKS);
			String walk = walking.join();
			log("walk -> " + walk);
			check(context, mc -> mc.player.blockPosition().equals(walkTarget), "walk_to should end on " + walkTarget.toShortString() + ": " + walk);

			BlockPos log = new BlockPos(x + 8, y, z);
			world.getServer().runCommand(String.format("setblock %d %d %d oak_log", log.getX(), log.getY(), log.getZ()));
			context.waitTicks(2);
			String mine = run(context, new MineTask(log, true, false));
			check(context, mc -> count(mc, Items.OAK_LOG) == 1, "mine_block should pick up the log: " + mine);

			world.getServer().runCommand("give @a oak_log 3");
			context.waitTicks(2);
			String planks = run(context, new CraftTask(Items.OAK_PLANKS, 16));
			check(context, mc -> count(mc, Items.OAK_PLANKS) == 16, "craft planks: " + planks);
			String sticks = run(context, new CraftTask(Items.STICK, 4));
			check(context, mc -> count(mc, Items.STICK) == 4, "craft sticks: " + sticks);
			String table = run(context, new CraftTask(Items.CRAFTING_TABLE, 1));
			check(context, mc -> count(mc, Items.CRAFTING_TABLE) == 1, "craft crafting table: " + table);

			BlockPos tablePos = new BlockPos(x + 6, y, z + 2);
			String placeTable = run(context, new PlaceTask(Lookup.item("minecraft:crafting_table"), tablePos));
			check(context, mc -> mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE), "place crafting table: " + placeTable);

			String pickaxe = run(context, new CraftTask(Items.WOODEN_PICKAXE, 1));
			check(context, mc -> count(mc, Items.WOODEN_PICKAXE) == 1 && mc.player.containerMenu == mc.player.inventoryMenu,
				"craft wooden pickaxe at the table: " + pickaxe);

			for (int i = 0; i < 3; i++) {
				world.getServer().runCommand(String.format("setblock %d %d %d stone", x + 9, y + i % 2, z - 6 + i * 2));
			}
			context.waitTicks(2);
			String gather = run(context, new GatherTask(Lookup.block("minecraft:stone"), 3, 16));
			check(context, mc -> count(mc, Items.COBBLESTONE) >= 3, "gather stone: " + gather);

			BlockPos water = new BlockPos(x - 4, y - 1, z + 4);
			world.getServer().runCommand(String.format("setblock %d %d %d water", water.getX(), water.getY(), water.getZ()));
			world.getServer().runCommand("give @a bucket");
			context.waitTicks(5);
			context.runOnClient(mc -> PlayerControl.holdItem(mc, stack -> stack.is(Items.BUCKET)));
			String fill = run(context, new UseBlockTask(water, null));
			check(context, mc -> count(mc, Items.WATER_BUCKET) == 1 && count(mc, Items.BUCKET) == 0
				&& mc.level.getBlockState(water).getFluidState().isEmpty(), "fill a bucket from a water source: " + fill);
			BlockPos floor = new BlockPos(x - 4, y - 1, z + 2);
			context.runOnClient(mc -> PlayerControl.holdItem(mc, stack -> stack.is(Items.WATER_BUCKET)));
			String pour = run(context, new UseBlockTask(floor, null));
			check(context, mc -> count(mc, Items.BUCKET) == 1 && !mc.level.getFluidState(floor.above()).isEmpty(), "pour the water back out: " + pour);
			world.getServer().runCommand(String.format("setblock %d %d %d air", floor.getX(), floor.getY() + 1, floor.getZ()));
			world.getServer().runCommand(String.format("setblock %d %d %d stone", water.getX(), water.getY(), water.getZ()));

			testDrowningSafety(context, world, x, y, z);

			world.getServer().runCommand("give @a furnace");
			world.getServer().runCommand("give @a raw_iron 2");
			world.getServer().runCommand("give @a coal 1");
			world.getServer().runCommand("give @a chest");
			context.waitTicks(2);
			BlockPos furnacePos = new BlockPos(x + 4, y, z + 6);
			String placeFurnace = run(context, new PlaceTask(Lookup.item("minecraft:furnace"), furnacePos));
			check(context, mc -> mc.level.getBlockState(furnacePos).is(Blocks.FURNACE), "place furnace: " + placeFurnace);
			String smelt = run(context, new SmeltTask(Lookup.item("minecraft:raw_iron"), null, 2, null));
			check(context, mc -> count(mc, Items.IRON_INGOT) == 2, "smelt raw iron: " + smelt);

			BlockPos chestPos = new BlockPos(x + 6, y, z + 6);
			String placeChest = run(context, new PlaceTask(Lookup.item("minecraft:chest"), chestPos));
			check(context, mc -> mc.level.getBlockState(chestPos).is(Blocks.CHEST), "place chest: " + placeChest);
			int cobble = context.computeOnClient(mc -> count(mc, Items.COBBLESTONE));
			String put = run(context, new ContainerTask(chestPos, ContainerTask.Action.PUT, Lookup.item("minecraft:cobblestone"), 64));
			check(context, mc -> count(mc, Items.COBBLESTONE) == 0, "put cobblestone in chest: " + put);
			String take = run(context, new ContainerTask(chestPos, ContainerTask.Action.TAKE, Lookup.item("minecraft:cobblestone"), 64));
			check(context, mc -> count(mc, Items.COBBLESTONE) == cobble, "take cobblestone back: " + take);

			world.getServer().runCommand(String.format("summon pig %d %d %d", x - 6, y, z - 4));
			context.waitTicks(5);
			String attack = run(context, new AttackTask(entity -> entity instanceof Pig, "pig", 1));
			check(context, mc -> mc.level.getEntitiesOfClass(Pig.class,
				mc.player.getBoundingBox().inflate(48), pig -> pig.isAlive()).isEmpty(), "kill the pig: " + attack);

			testCombatReflex(context, world, x, y, z);

			world.getServer().runCommand("difficulty easy");
			world.getServer().runCommand("effect give @a hunger 30 255");
			context.waitFor(mc -> mc.player.getFoodData().getFoodLevel() <= 14, 20 * 60);
			world.getServer().runCommand("effect clear @a");
			world.getServer().runCommand("difficulty peaceful");
			world.getServer().runCommand("give @a bread 2");
			context.waitTicks(2);
			int foodBefore = context.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
			String eat = run(context, new EatTask(null));
			check(context, mc -> mc.player.getFoodData().getFoodLevel() > foodBefore && count(mc, Items.BREAD) == 1, "eat bread: " + eat);

			world.getServer().runCommand("effect give @a poison 20 1");
			world.getServer().runCommand("effect give @a speed 60 0");
			context.waitTicks(5);
			String observed = context.computeOnClient(WorldInfo::observe);
			String alerts = context.computeOnClient(WorldInfo::alerts);
			log("Effects line: " + observed.lines().filter(line -> line.startsWith("Effects:")).findFirst().orElse("(missing)") + " | " + alerts);
			assertTrue(observed.contains("poison II") && observed.contains("(harmful)") && observed.contains("speed I"),
				"observe should list effects: " + observed);
			assertTrue(alerts != null && alerts.contains("poison"), "alerts should flag poison: " + alerts);
			world.getServer().runCommand("effect clear @a");
			context.waitTicks(5);
			assertTrue(context.computeOnClient(WorldInfo::alerts) == null, "no alerts once effects are cleared");

			testCreativeTools(context, world, x, y, z);

			runAgentLoopAgainstMockServer(context, world);
			runAnthropicLoopAgainstMockServer(context);
			runDamageReport(context, world);
			runTurnLimitPause(context);
			runDeathDuringGoal(context, world, false);
			runDeathDuringGoal(context, world, true);
		}
	}

	private static void testInventoryOps(ClientGameTestContext context, TestSingleplayerContext world) throws TaskFailure {
		world.getServer().runCommand("clear @a");
		world.getServer().runCommand("item replace entity @a container.20 with minecraft:stone_pickaxe");
		world.getServer().runCommand("item replace entity @a container.30 with minecraft:shield");
		world.getServer().runCommand("item replace entity @a container.15 with minecraft:bread 5");
		world.getServer().runCommand("item replace entity @a container.25 with minecraft:torch 16");
		world.getServer().runCommand("item replace entity @a container.12 with minecraft:cobblestone 10");
		world.getServer().runCommand("item replace entity @a container.27 with minecraft:cobblestone 20");
		world.getServer().runCommand("item replace entity @a container.2 with minecraft:oak_planks 8");
		context.waitTicks(5);

		InventoryOps.SlotRef hotbar1 = InventoryOps.parseSlot("hotbar 1");
		context.runOnClient(mc -> InventoryOps.swap(mc, InventoryOps.find(mc.player, stack -> stack.is(Items.STONE_PICKAXE)), hotbar1));
		context.waitTicks(3);
		check(context, mc -> mc.player.getInventory().getItem(0).is(Items.STONE_PICKAXE), "move pickaxe to hotbar 1");

		context.runOnClient(mc -> InventoryOps.swap(mc, InventoryOps.find(mc.player, stack -> stack.is(Items.SHIELD)), InventoryOps.parseSlot("offhand")));
		context.waitTicks(3);
		check(context, mc -> mc.player.getOffhandItem().is(Items.SHIELD), "move shield to the offhand");

		String tidy = context.computeOnClient(InventoryOps::tidy);
		context.waitTicks(5);
		log("Tidy: " + tidy.replace("\n", " | "));
		check(context, mc -> {
			Inventory inventory = mc.player.getInventory();
			int cobbleStacks = 0;
			for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
				if (inventory.getItem(slot).is(Items.COBBLESTONE)) {
					cobbleStacks++;
				}
			}
			return cobbleStacks == 1 && inventory.getItem(1).is(Items.STONE_PICKAXE) && inventory.getItem(4).is(Items.BREAD)
				&& inventory.getItem(5).is(Items.TORCH) && inventory.getItem(6).is(Items.COBBLESTONE) && inventory.getItem(6).getCount() == 30;
		}, "tidy should merge cobblestone and lay out the hotbar: " + tidy);
		boolean serverAgrees = world.getServer().computeOnServer(server -> {
			Inventory inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
			return inventory.getItem(1).is(Items.STONE_PICKAXE) && inventory.getItem(6).getCount() == 30
				&& server.getPlayerList().getPlayers().getFirst().getOffhandItem().is(Items.SHIELD);
		});
		assertTrue(serverAgrees, "the server's copy of the inventory should match");
		world.getServer().runCommand("clear @a");
		context.waitTicks(3);
	}

	private static void testDropping(ClientGameTestContext context, TestSingleplayerContext world) throws TaskFailure {
		world.getServer().runCommand("clear @a");
		world.getServer().runCommand("give @a cobblestone 10");
		world.getServer().runCommand("give @a stone_pickaxe");
		context.waitTicks(5);
		// Looking straight down is what used to drop everything onto the player's feet.
		context.runOnClient(mc -> {
			PlayerControl.takeControl(mc);
			mc.player.setXRot(90.0F);
		});
		context.waitTicks(2);
		String drop = run(context, new DropTask(Lookup.item("minecraft:cobblestone"), 10, false));
		context.waitTicks(60);
		check(context, mc -> count(mc, Items.COBBLESTONE) == 0, "dropped cobblestone must not be picked back up: " + drop);
		double distance = context.computeOnClient(mc -> mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(8),
				item -> item.getItem().is(Items.COBBLESTONE)).stream().mapToDouble(item -> item.distanceTo(mc.player)).min().orElse(-1));
		log("Drop: " + drop + " | landed " + String.format("%.1f", distance) + " blocks away");
		assertTrue(distance >= 1.5, "the items should land away from the player, landed at " + distance);

		String refused = runExpectingFailure(context, new DropTask(Lookup.item("minecraft:stone_pickaxe"), 1, false));
		assertTrue(refused.contains("force=true"), "tools must not be dropped without force: " + refused);

		BlockPos here = context.computeOnClient(mc -> mc.player.blockPosition());
		run(context, new CollectTask(here, 8, null, 20 * 5));
		check(context, mc -> count(mc, Items.COBBLESTONE) == 0, "automatic pickup must skip the agent's own drops");
		run(context, new CollectTask(here, 8, stack -> stack.is(Items.COBBLESTONE), 20 * 10));
		check(context, mc -> count(mc, Items.COBBLESTONE) == 10, "asking for the item explicitly should still pick it up");
		context.runOnClient(mc -> PlayerControl.releaseControl(mc));
		world.getServer().runCommand("clear @a");
		context.waitTicks(3);
	}

	private static void testCombatReflex(ClientGameTestContext context, TestSingleplayerContext world, int x, int y, int z) throws TaskFailure {
		world.getServer().runCommand("difficulty easy");
		world.getServer().runCommand("give @a iron_sword");
		for (int i = 0; i < 4; i++) {
			world.getServer().runCommand(String.format("setblock %d %d %d stone", x - 8 + i * 2, y, z - 8));
		}
		context.runOnClient(mc -> PlayerControl.takeControl(mc));
		BlockPos pos = context.computeOnClient(mc -> mc.player.blockPosition());
		// Fire resistance keeps the zombie from burning up in daylight before it reaches the player.
		world.getServer().runCommand(String.format("summon zombie %d %d %d {Tags:[\"fighter\"]}", pos.getX() + 5, pos.getY(), pos.getZ()));
		world.getServer().runCommand("effect give @e[tag=fighter] fire_resistance 120 0 true");
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

		world.getServer().runCommand("effect give @a instant_health 1 5");
		context.waitTicks(5);
		world.getServer().runCommand("damage @p 18 minecraft:generic");
		// Resistance IV, applied after the setup damage, keeps the low-health part from turning into a death.
		world.getServer().runCommand("effect give @a resistance 60 3 true");
		pos = context.computeOnClient(mc -> mc.player.blockPosition());
		// No AI, so the only hit is the scripted one below.
		world.getServer().runCommand(String.format("summon zombie %d %d %d {NoAI:1b,Tags:[\"fighter\"]}", pos.getX() + 2, pos.getY(), pos.getZ()));
		world.getServer().runCommand("effect give @e[tag=fighter] fire_resistance 120 0 true");
		CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> {
			try {
				return "UNEXPECTED SUCCESS: " + TaskRunner.run(new WaitTask(20 * 15), () -> false);
			} catch (TaskFailure failure) {
				return failure.getMessage();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "interrupted";
			}
		});
		// Past the setup hit's invulnerability window, which would swallow a weaker hit.
		context.waitTicks(25);
		check(context, mc -> mc.player.getHealth() <= 6.0F, "the setup should leave the player at low health");
		world.getServer().runCommand("damage @p 1 minecraft:mob_attack by @e[tag=fighter,limit=1]");
		context.waitFor(mc -> waiting.isDone(), 20 * 20);
		String interrupted = waiting.join();
		log("Low-health fight: " + interrupted);
		assertTrue(interrupted.startsWith("Interrupted: health is low") && interrupted.contains("zombie #"),
			"a fight at low health should interrupt the action: " + interrupted);
		String passive = run(context, new WaitTask(20 * 2));
		assertTrue(passive.startsWith("Waited"), "the action after the interruption should run without the reflex: " + passive);

		world.getServer().runCommand("kill @e[tag=fighter]");
		world.getServer().runCommand("clear @a iron_sword");
		world.getServer().runCommand("effect clear @a");
		world.getServer().runCommand("effect give @a instant_health 1 5");
		world.getServer().runCommand("difficulty peaceful");
		context.runOnClient(mc -> PlayerControl.releaseControl(mc));
		context.waitTicks(10);
	}

	/** Runs a task that should fail and returns its error message. */
	private static String runExpectingFailure(ClientGameTestContext context, Task task) {
		CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
			try {
				return "UNEXPECTED SUCCESS: " + TaskRunner.run(task, () -> false);
			} catch (TaskFailure failure) {
				return failure.getMessage();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "interrupted";
			}
		});
		context.waitFor(mc -> result.isDone(), task.timeoutTicks() + 40);
		return result.join();
	}

	private static void testCreativeTools(ClientGameTestContext context, TestSingleplayerContext world, int x, int y, int z) throws TaskFailure {
		boolean commandsBefore = world.getServer().computeOnServer(server -> server.getWorldData().isAllowCommands());
		world.getServer().runCommand("gamemode creative @a");
		world.getServer().runOnServer(server -> ((IntegratedServer) server).setWorldAllowCommands(true));
		context.waitTicks(10);
		String abilities = context.computeOnClient(mc -> WorldInfo.abilities(mc.player));
		log("Abilities in creative: " + abilities);
		assertTrue(abilities.contains("can fly") && abilities.contains("give_item") && !abilities.contains("no operator"),
			"observe should offer flight, creative items and commands: " + abilities);

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

		// A wall across the whole cleared area, so the route has to go over it.
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x - 8, y, z - 12, x - 8, y + 3, z + 12));
		context.waitTicks(5);
		BlockPos landing = new BlockPos(x - 11, y, z + 1);
		String flight = run(context, FlyTask.to(landing, 0, true, 20 * 60));
		check(context, mc -> mc.player.blockPosition().equals(landing) && mc.player.onGround() && !Flight.isFlying(mc.player),
			"fly_to should cross the wall and land on " + landing.toShortString() + ": " + flight);

		BlockPos hover = new BlockPos(x - 11, y + 3, z + 4);
		String hovering = run(context, FlyTask.to(hover, 0, false, 20 * 60));
		context.waitTicks(10);
		check(context, mc -> mc.player.blockPosition().equals(hover) && Flight.isFlying(mc.player),
			"fly_to with land=false should hover at " + hover.toShortString() + ": " + hovering + "; now at "
				+ context.computeOnClient(mc2 -> mc2.player.position() + " flying " + Flight.isFlying(mc2.player)));
		BlockPos ground = new BlockPos(x - 11, y, z + 6);
		String walk = run(context, new WalkTask(Goal.near(ground, 0), ground.toShortString(), true, true, TASK_TIMEOUT_TICKS));
		check(context, mc -> mc.player.blockPosition().equals(ground) && !Flight.isFlying(mc.player),
			"walk_to should land before walking: " + walk);

		testFastFlightAroundWall(context, world, x, y, z);

		AgentConfig config = AgentConfig.get();
		boolean allowedBefore = config.allowCommands;
		try {
			config.allowCommands = false;
			String disabled = runExpectingFailure(context, new CommandTask("time set day"));
			assertTrue(disabled.contains("/agent commands on"), "commands must stay off until enabled: " + disabled);
			config.allowCommands = true;
			BlockPos gold = new BlockPos(x - 11, y, z + 8);
			String setblock = run(context, new CommandTask(String.format("/setblock %d %d %d gold_block", gold.getX(), gold.getY(), gold.getZ())));
			check(context, mc -> mc.level.getBlockState(gold).is(Blocks.GOLD_BLOCK), "run_command should place the gold block: " + setblock);
			assertTrue(setblock.contains("Server messages"), "run_command should return the server's reply: " + setblock);
			String unknown = runExpectingFailure(context, new CommandTask("notacommand 1 2"));
			assertTrue(unknown.startsWith("Not sent"), "unknown commands must not be sent: " + unknown);
			String clientSide = runExpectingFailure(context, new CommandTask("agent stop"));
			assertTrue(clientSide.contains("client-side"), "the agent must not run its own client commands: " + clientSide);
		} finally {
			config.allowCommands = allowedBefore;
		}

		world.getServer().runCommand("gamemode survival @a");
		world.getServer().runOnServer(server -> ((IntegratedServer) server).setWorldAllowCommands(commandsBefore));
		context.waitTicks(10);
		String survivalGive = context.computeOnClient(mc -> {
			try {
				return CreativeItems.give(mc, Items.DIAMOND, 1, null);
			} catch (GameThread.GameStateException refused) {
				return refused.getMessage();
			}
		});
		assertTrue(survivalGive.contains("creative mode"), "give_item must refuse outside creative: " + survivalGive);
		String survivalFlight = runExpectingFailure(context, FlyTask.takeOff());
		assertTrue(survivalFlight.contains("cannot fly"), "flight must refuse in survival: " + survivalFlight);
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d air", x - 8, y, z - 12, x - 8, y + 3, z + 12));
		world.getServer().runCommand("clear @a");
		context.runOnClient(mc -> PlayerControl.releaseControl(mc));
		context.waitTicks(5);
	}

	/**
	 * A long sprinting flight that must turn sharply around the end of a tall wall. Momentum used to carry the player
	 * off the route and make it fly back to it, so the distance to the target must never grow much.
	 */
	private static void testFastFlightAroundWall(ClientGameTestContext context, TestSingleplayerContext world, int x, int y, int z) {
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x + 40, y, z - 20, x + 40, y + 15, z + 10));
		world.getServer().runCommand(String.format("tp @a %d.5 %d %d.5", x + 26, y, z));
		context.waitTicks(10);
		BlockPos target = new BlockPos(x + 54, y + 1, z);
		Vec3 aim = Vec3.atBottomCenterOf(target);
		Task flight = FlyTask.to(target, 0, false, 20 * 60);
		CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
			try {
				return TaskRunner.run(flight, () -> false);
			} catch (TaskFailure | InterruptedException e) {
				return "FAILED: " + e.getMessage();
			}
		});
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
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d air", x + 40, y, z - 20, x + 40, y + 15, z + 10));
		world.getServer().runCommand(String.format("tp @a %d.5 %d %d.5", x - 11, y, z + 6));
		context.waitTicks(10);
	}

	private static void testDrowningSafety(ClientGameTestContext context, TestSingleplayerContext world, int x, int y, int z) {
		// The test world is superflat with bedrock 4 blocks down, so the pool is 3 deep: enough to be fully submerged.
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d water", x - 10, y - 3, z + 8, x - 7, y - 1, z + 11));
		String bottom = String.format("tp @a %d.5 %d %d.5", x - 9, y - 3, z + 9);
		context.waitTicks(5);

		context.runOnClient(mc -> PlayerControl.takeControl(mc));
		world.getServer().runCommand(bottom);
		context.waitTicks(20 * 25);
		String idleState = context.computeOnClient(mc -> String.format("health %.1f, air %d, y %.2f, underwater %s, input %s",
			mc.player.getHealth(), mc.player.getAirSupply(), mc.player.getY(), mc.player.isUnderWater(), mc.player.input.getClass().getSimpleName()));
		log("Idle in water: " + idleState);
		check(context, mc -> mc.player.getHealth() >= mc.player.getMaxHealth() && mc.player.getAirSupply() >= mc.player.getMaxAirSupply() / 2,
			"idle in water: should float up and take no damage (" + idleState + ")");

		world.getServer().runCommand(bottom);
		context.waitTicks(5);
		context.runOnClient(DamageTracker::begin);
		String wait = run(context, new Task() {
			private int ticks;

			// Holds the controls still like a busy action (mining, using a block) would, so only the reflex can surface.
			@Override
			public String tick(Minecraft mc) {
				PlayerControl.input().clear();
				return ++ticks >= 20 * 20 ? "Held still for 20 seconds." : null;
			}

			@Override
			public int timeoutTicks() {
				return 20 * 40;
			}

			@Override
			public String describe() {
				return "holding still underwater";
			}
		});
		String report = context.computeOnClient(DamageTracker::end);
		log("Underwater wait: " + wait + " | " + report);
		check(context, mc -> mc.player.getHealth() >= mc.player.getMaxHealth(), "the reflex should surface during a wait: " + report);
		assertTrue(report != null && report.contains("to swim up for air"), "the result should mention surfacing: " + report);

		world.getServer().runCommand(String.format("tp @a %d.5 %d %d.5", x - 5, y, z + 9));
		context.waitTicks(10);
		BlockPos across = new BlockPos(x - 12, y, z + 9);
		context.runOnClient(DamageTracker::begin);
		String cross = run(context, new WalkTask(Goal.near(across, 0), across.toShortString(), true, true, 20 * 60));
		String crossReport = context.computeOnClient(DamageTracker::end);
		log("Pool crossing: " + cross + " | " + crossReport);
		check(context, mc -> mc.player.blockPosition().equals(across) && mc.player.getHealth() >= mc.player.getMaxHealth(),
			"cross the pool without drowning: " + cross + " | " + crossReport);

		context.runOnClient(mc -> PlayerControl.releaseControl(mc));
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x - 10, y - 3, z + 8, x - 7, y - 1, z + 11));
		context.waitTicks(5);
	}

	private static void runAgentLoopAgainstMockServer(ClientGameTestContext context, TestSingleplayerContext world) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/chat/completions", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			String body = switch (turn.incrementAndGet()) {
				case 1 -> toolCallResponse("call_1", "observe", "{}");
				case 2 -> toolCallResponse("call_2", "craft", "{\"item\":\"minecraft:oak_planks\",\"count\":4}");
				default -> textResponse("Crafted the planks.");
			};
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			world.getServer().runCommand("give @a oak_log 1");
			context.waitTicks(5);
			int planksBefore = context.computeOnClient(mc -> count(mc, Items.OAK_PLANKS));

			AgentConfig config = AgentConfig.get();
			config.provider = Provider.CUSTOM.id();
			config.baseUrls.put(Provider.CUSTOM.id(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
			config.models.put(Provider.CUSTOM.id(), "mock-model");
			config.showToolCalls = true;
			String error = context.computeOnClient(mc -> AgentController.get().start("make some planks"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 60);

			check(context, mc -> count(mc, Items.OAK_PLANKS) == planksBefore + 4, "agent loop should craft 4 planks");
			check(context, mc -> !(mc.player.input instanceof AgentInput) && !PlayerControl.inControl(),
				"control should return to the keyboard after the goal");
			if (requests.size() != 3) {
				throw new AssertionError("expected 3 model requests, got " + requests.size());
			}
			JsonArray second = requests.get(1).getAsJsonArray("messages");
			JsonObject observeResult = second.get(second.size() - 1).getAsJsonObject();
			assertTrue("tool".equals(observeResult.get("role").getAsString()) && observeResult.get("content").getAsString().contains("Position:"),
				"observe result should be sent back as a tool message: " + observeResult);
			JsonArray third = requests.get(2).getAsJsonArray("messages");
			JsonObject craftResult = third.get(third.size() - 1).getAsJsonObject();
			assertTrue(craftResult.get("content").getAsString().startsWith("Crafted 4 oak_planks"),
				"craft result should report success: " + craftResult);
			assertTrue(requests.getFirst().getAsJsonArray("tools").size() >= 20, "all tools should be offered");
			log("Agent loop OK; craft result: " + craftResult.get("content").getAsString());
			context.runOnClient(mc -> mc.gui.setScreen(new ChatScreen("", false)));
			context.waitTicks(5);
			log("Tool chat screenshot " + context.takeScreenshot("mineagent-tool-chat"));
			context.runOnClient(mc -> mc.gui.setScreen(null));
			config.showToolCalls = false;
		} finally {
			server.stop(0);
		}
	}

	private static void runAnthropicLoopAgainstMockServer(ClientGameTestContext context) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		List<String> betaHeaders = new CopyOnWriteArrayList<>();
		List<String> authHeaders = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/messages", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			betaHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-beta")));
			authHeaders.add(exchange.getRequestHeaders().getFirst("x-api-key") + " " + exchange.getRequestHeaders().getFirst("anthropic-version"));
			String body = turn.incrementAndGet() == 1
				? "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":["
					+ "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-abc\"},"
					+ "{\"type\":\"text\",\"text\":\"Checking the inventory.\"},"
					+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"recipes\",\"input\":{\"item\":\"minecraft:stick\"}}],"
					+ "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}"
				: "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":["
					+ "{\"type\":\"text\",\"text\":\"Sticks are craftable.\"}],"
					+ "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":80,\"output_tokens\":5}}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			AgentConfig config = AgentConfig.get();
			config.provider = Provider.ANTHROPIC.id();
			config.apiKeys.put(Provider.ANTHROPIC.id(), "test-key");
			config.baseUrls.put(Provider.ANTHROPIC.id(), "http://127.0.0.1:" + server.getAddress().getPort());
			config.models.remove(Provider.ANTHROPIC.id());
			String error = context.computeOnClient(mc -> AgentController.get().start("can I make sticks?"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 60);
			if (requests.size() != 2) {
				throw new AssertionError("expected 2 Messages API requests, got " + requests.size());
			}
			JsonObject first = requests.getFirst();
			assertTrue("claude-opus-5-5".equals(first.get("model").getAsString()), "default model: " + first.get("model"));
			assertTrue("default".equals(first.get("fallbacks").getAsString()), "refusal fallback should be requested");
			assertTrue(betaHeaders.getFirst().contains("server-side-fallback-2026-07-01"), "fallback beta header: " + betaHeaders);
			assertTrue("test-key 2026-10-21".equals(authHeaders.getFirst()), "key and API version headers: " + authHeaders);
			assertTrue("medium".equals(first.getAsJsonObject("output_config").get("effort").getAsString()), "effort should be sent");
			assertTrue(first.has("cache_control"), "automatic prompt caching should be on");
			assertTrue(first.getAsJsonArray("tools").size() >= 20, "all tools should be offered");
			JsonArray messages = requests.get(1).getAsJsonArray("messages");
			JsonObject assistant = messages.get(1).getAsJsonObject();
			assertTrue(assistant.toString().contains("sig-abc"), "thinking block must be replayed unchanged: " + assistant);
			JsonObject toolTurn = messages.get(2).getAsJsonObject();
			JsonObject result = toolTurn.getAsJsonArray("content").get(0).getAsJsonObject();
			assertTrue("tool_result".equals(result.get("type").getAsString()) && "toolu_1".equals(result.get("tool_use_id").getAsString())
				&& result.toString().contains("Recipes for stick"), "tool result should answer the call: " + result);
			log("Anthropic loop OK; tool result: " + result);
		} finally {
			server.stop(0);
		}
	}

	private static void runTurnLimitPause(ClientGameTestContext context) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/chat/completions", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			byte[] bytes = toolCallResponse("call_" + requests.size(), "wait", "{\"seconds\":1}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		AgentConfig config = AgentConfig.get();
		int savedMaxTurns = config.maxTurns;
		try {
			config.provider = Provider.CUSTOM.id();
			config.baseUrls.put(Provider.CUSTOM.id(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
			config.models.put(Provider.CUSTOM.id(), "mock-model");
			config.maxTurns = 2;
			String error = context.computeOnClient(mc -> AgentController.get().start("wait forever"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> AgentController.get().phase().startsWith("paused"), 20 * 30);
			context.waitTicks(20);
			Path pausedShot = context.takeScreenshot("mineagent-paused");
			log("Paused screenshot " + pausedShot);
			assertTrue(requests.size() == 2, "should pause after 2 turns, made " + requests.size() + " requests");
			check(context, mc -> AgentController.get().isRunning() && !(mc.player.input instanceof AgentInput),
				"the goal stays alive and the keyboard gets control back while paused");

			assertTrue(context.computeOnClient(mc -> AgentController.get().continueGoal(1)) == null, "continue should be accepted");
			context.waitFor(mc -> requests.size() == 3 && AgentController.get().phase().startsWith("paused"), 20 * 30);
			context.waitTicks(20);
			assertTrue(requests.size() == 3, "one more turn after /agent continue 1, made " + requests.size());
			JsonArray history = requests.get(2).getAsJsonArray("messages");
			assertTrue(history.size() > requests.get(1).getAsJsonArray("messages").size(), "the conversation should carry on, not restart");

			// The [Continue] chat button sends its command this way when clicked.
			context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("agent continue", null));
			context.waitFor(mc -> requests.size() == 5 && AgentController.get().phase().startsWith("paused"), 20 * 30);
			check(context, mc -> mc.gui.screen() == null, "a clicked /agent command must not open a confirmation screen");

			context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("agent stop", null));
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 10);
			log("Turn limit OK: paused at 2, resumed for 1, paused at 3, button continue to 5, button stop");
		} finally {
			config.maxTurns = savedMaxTurns;
			server.stop(0);
		}
	}

	private static void runDamageReport(ClientGameTestContext context, TestSingleplayerContext world) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/chat/completions", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			String body = turn.incrementAndGet() == 1
				? toolCallResponse("call_wait", "wait", "{\"seconds\":4}")
				: textResponse("Noted the damage.");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			world.getServer().runCommand("difficulty easy");
			BlockPos pos = context.computeOnClient(mc -> mc.player.blockPosition());
			world.getServer().runCommand(String.format("summon zombie %d %d %d {NoAI:1b,Tags:[\"hitter\"]}", pos.getX() + 3, pos.getY(), pos.getZ()));
			context.waitTicks(2);
			AgentConfig config = AgentConfig.get();
			config.provider = Provider.CUSTOM.id();
			config.baseUrls.put(Provider.CUSTOM.id(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
			config.models.put(Provider.CUSTOM.id(), "mock-model");
			String error = context.computeOnClient(mc -> AgentController.get().start("stand still"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> requests.size() == 1 && "waiting".equals(AgentController.get().phase()), 20 * 30);
			world.getServer().runCommand("damage @p 4 minecraft:mob_attack by @e[tag=hitter,limit=1]");
			context.waitTicks(15);
			world.getServer().runCommand("damage @p 4 minecraft:mob_attack by @e[tag=hitter,limit=1]");
			context.waitTicks(15);
			world.getServer().runCommand("damage @p 2 minecraft:fall");
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 30);
			world.getServer().runCommand("kill @e[tag=hitter]");
			world.getServer().runCommand("effect give @a instant_health 1 5");
			world.getServer().runCommand("difficulty peaceful");
			assertTrue(requests.size() == 2, "expected 2 model requests, got " + requests.size());
			JsonArray messages = requests.get(1).getAsJsonArray("messages");
			String report = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
			log("Damage report: " + report.replace("\n", " | "));
			assertTrue(report.contains("Took ") && report.matches("(?s).*zombie #\\d+ x2.*") && report.contains("fall x1")
				&& report.contains("health now"), "the wait result should report the hits: " + report);
		} finally {
			server.stop(0);
		}
	}

	private static void runDeathDuringGoal(ClientGameTestContext context, TestSingleplayerContext world, boolean keepInventory) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/chat/completions", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			String body = turn.incrementAndGet() == 1
				? toolCallResponse("call_wait", "wait", "{\"seconds\":20}")
				: textResponse("I died, stopping here.");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			AgentConfig config = AgentConfig.get();
			config.provider = Provider.CUSTOM.id();
			config.baseUrls.put(Provider.CUSTOM.id(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
			config.models.put(Provider.CUSTOM.id(), "mock-model");
			world.getServer().runCommand("gamerule keep_inventory " + keepInventory);
			world.getServer().runCommand("give @a cobblestone 5");
			context.waitTicks(5);
			String error = context.computeOnClient(mc -> AgentController.get().start("wait around"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> requests.size() == 1 && "waiting".equals(AgentController.get().phase()), 20 * 30);
			world.getServer().runCommand("kill @a");
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 30);
			check(context, mc -> mc.player.isAlive() && !(mc.gui.screen() instanceof DeathScreen), "player should be respawned with no death screen");
			assertTrue(requests.size() == 2, "expected 2 model requests, got " + requests.size());
			JsonArray messages = requests.get(1).getAsJsonArray("messages");
			String history = messages.toString();
			assertTrue(history.contains("Error: The player died"), "the interrupted wait should report the death: " + history);
			assertTrue(history.contains("Game event: You died at"), "the model should be told about the death: " + history);
			assertTrue(history.contains(keepInventory ? "keepInventory is on, so you kept" : "keepInventory is off: your items ("),
				"the death report should say whether items were kept: " + history);
			if (keepInventory) {
				check(context, mc -> count(mc, Items.COBBLESTONE) >= 5, "items should survive with keep_inventory on");
			}
			log("Death handling OK: " + messages.get(messages.size() - 1));
		} finally {
			server.stop(0);
		}
	}

	private static String toolCallResponse(String id, String name, String arguments) {
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		JsonObject call = new JsonObject();
		call.addProperty("id", id);
		call.addProperty("type", "function");
		call.add("function", function);
		JsonArray calls = new JsonArray();
		calls.add(call);
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.add("content", JsonNull.INSTANCE);
		message.add("tool_calls", calls);
		return completion(message, "tool_calls");
	}

	private static String textResponse(String text) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", text);
		return completion(message, "stop");
	}

	private static String completion(JsonObject message, String finishReason) {
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.add("message", message);
		choice.addProperty("finish_reason", finishReason);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject usage = new JsonObject();
		usage.addProperty("prompt_tokens", 100);
		usage.addProperty("completion_tokens", 10);
		JsonObject root = new JsonObject();
		root.add("choices", choices);
		root.add("usage", usage);
		return root.toString();
	}

	/** Runs a task the way the agent does, from a background thread, while the test keeps the client ticking. */
	private static String run(ClientGameTestContext context, Task task) {
		CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
			try {
				return TaskRunner.run(task, () -> false);
			} catch (TaskFailure failure) {
				return "FAILED: " + failure.getMessage();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "FAILED: interrupted";
			}
		});
		context.waitFor(mc -> result.isDone(), task.timeoutTicks() + 40);
		String outcome = result.join();
		log(task.describe() + " -> " + outcome);
		if (outcome.startsWith("FAILED")) {
			throw new AssertionError(task.describe() + " failed: " + outcome);
		}
		return outcome;
	}

	private static void check(ClientGameTestContext context, Predicate<Minecraft> condition, String message) {
		context.waitTicks(2);
		if (!context.computeOnClient(condition::test)) {
			throw new AssertionError(message);
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static int count(Minecraft mc, Item item) {
		return PlayerControl.countItems(mc.player, (ItemStack stack) -> stack.is(item));
	}

	private static void log(String message) {
		System.out.println("[MineAgent test] " + message);
	}
}
