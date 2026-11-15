package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.TASK_TIMEOUT_TICKS;
import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.check;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.count;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.run;
import static moe.tenshii.mineagent.test.GameTestKit.runExpectingFailure;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import moe.tenshii.mineagent.game.InventoryOps;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.ServerTps;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.AttackTask;
import moe.tenshii.mineagent.game.task.CollectTask;
import moe.tenshii.mineagent.game.task.ContainerTask;
import moe.tenshii.mineagent.game.task.CraftTask;
import moe.tenshii.mineagent.game.task.DropTask;
import moe.tenshii.mineagent.game.task.EatTask;
import moe.tenshii.mineagent.game.task.GatherTask;
import moe.tenshii.mineagent.game.task.MineTask;
import moe.tenshii.mineagent.game.task.PlaceTask;
import moe.tenshii.mineagent.game.task.SmeltTask;
import moe.tenshii.mineagent.game.task.UseBlockTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import moe.tenshii.mineagent.path.MoveType;
import moe.tenshii.mineagent.path.PathDisplay;
import moe.tenshii.mineagent.path.PathStep;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The everyday tasks: inventory handling, walking, mining, crafting, gathering, buckets, smelting, chests, fighting
 * and eating, plus how observe reports status effects.
 */
public final class TaskGameTest implements FabricClientGameTest {
	private static final int MIN_ROUTE_PIXELS = 50;

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			inventoryOps(ctx, world);
			dropping(ctx, world);
			survivalBasics(ctx, world, start);
			eating(ctx, world);
			effects(ctx, world);
			serverTps(ctx, world);
			wholeTree(ctx, world, start);
		});
	}

	private static void inventoryOps(ClientGameTestContext context, TestSingleplayerContext world) throws TaskFailure {
		cmd(world, "item replace entity @a container.20 with minecraft:stone_pickaxe");
		cmd(world, "item replace entity @a container.30 with minecraft:shield");
		cmd(world, "item replace entity @a container.15 with minecraft:bread 5");
		cmd(world, "item replace entity @a container.25 with minecraft:torch 16");
		cmd(world, "item replace entity @a container.12 with minecraft:cobblestone 10");
		cmd(world, "item replace entity @a container.27 with minecraft:cobblestone 20");
		cmd(world, "item replace entity @a container.2 with minecraft:oak_planks 8");
		context.waitTicks(5);

		InventoryOps.SlotRef hotbar1 = InventoryOps.parseSlot("hotbar 1");
		context.runOnClient(mc -> InventoryOps.swap(mc, InventoryOps.find(mc.player, stack -> stack.is(Items.STONE_PICKAXE)), hotbar1));
		context.waitTicks(3);
		check(context, mc -> mc.player.getInventory().getItem(0).is(Items.STONE_PICKAXE), "move pickaxe to hotbar 1");

		InventoryOps.SlotRef offhand = InventoryOps.parseSlot("offhand");
		context.runOnClient(mc -> InventoryOps.swap(mc, InventoryOps.find(mc.player, stack -> stack.is(Items.SHIELD)), offhand));
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
		cmd(world, "clear @a");
		context.waitTicks(3);
	}

	private static void dropping(ClientGameTestContext context, TestSingleplayerContext world) throws TaskFailure {
		cmd(world, "give @a cobblestone 10");
		cmd(world, "give @a stone_pickaxe");
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
		context.runOnClient(PlayerControl::releaseControl);
		cmd(world, "clear @a");
		cmd(world, "kill @e[type=item]");
		cmd(world, "tp @a %d.5 %d %d.5", here.getX(), here.getY(), here.getZ());
		context.waitTicks(3);
	}

	/** One survival progression in order, since each step uses what the last one made. */
	private static void survivalBasics(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) throws TaskFailure {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ();
		cmd(world, "tp @a %d.5 %d %d.5", x, y, z);
		cmd(world, "fill %d %d %d %d %d %d stone", x + 3, y, z - 3, x + 3, y + 2, z + 3);
		context.waitTicks(5);

		pathOnScreen(context, start);

		BlockPos walkTarget = new BlockPos(x + 6, y, z);
		WalkTask walkTask = new WalkTask(Goal.near(walkTarget, 0), walkTarget.toShortString(), true, true, TASK_TIMEOUT_TICKS);
		CompletableFuture<String> walking = GameTestKit.runAsync(walkTask);
		context.waitTicks(8);
		int pathGizmos = context.computeOnClient(mc -> mc.getPerTickGizmos().size());
		log("Path overlay while walking: " + pathGizmos + " gizmos this tick, screenshot " + context.takeScreenshot("mineagent-path"));
		assertTrue(pathGizmos > 0, "the path should be drawn while walking");
		context.waitFor(mc -> walking.isDone(), TASK_TIMEOUT_TICKS);
		String walk = walking.join();
		log("walk -> " + walk);
		check(context, mc -> mc.player.blockPosition().equals(walkTarget), "walk_to should end on " + walkTarget.toShortString() + ": " + walk);

		BlockPos log = new BlockPos(x + 8, y, z);
		cmd(world, "setblock %d %d %d oak_log", log.getX(), log.getY(), log.getZ());
		context.waitTicks(2);
		String mine = run(context, new MineTask(log, true, false));
		check(context, mc -> count(mc, Items.OAK_LOG) == 1, "mine_block should pick up the log: " + mine);

		cmd(world, "give @a oak_log 3");
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
			cmd(world, "setblock %d %d %d stone", x + 9, y + i % 2, z - 6 + i * 2);
		}
		context.waitTicks(2);
		String gather = run(context, new GatherTask(Lookup.block("minecraft:stone"), 3, 16));
		check(context, mc -> count(mc, Items.COBBLESTONE) >= 3, "gather stone: " + gather);

		BlockPos water = new BlockPos(x - 4, y - 1, z + 4);
		cmd(world, "setblock %d %d %d water", water.getX(), water.getY(), water.getZ());
		cmd(world, "give @a bucket");
		context.waitTicks(5);
		context.runOnClient(mc -> PlayerControl.holdItem(mc, stack -> stack.is(Items.BUCKET)));
		String fill = run(context, new UseBlockTask(water, null));
		check(context, mc -> count(mc, Items.WATER_BUCKET) == 1 && count(mc, Items.BUCKET) == 0
			&& mc.level.getBlockState(water).getFluidState().isEmpty(), "fill a bucket from a water source: " + fill);
		BlockPos floor = new BlockPos(x - 4, y - 1, z + 2);
		context.runOnClient(mc -> PlayerControl.holdItem(mc, stack -> stack.is(Items.WATER_BUCKET)));
		String pour = run(context, new UseBlockTask(floor, null));
		check(context, mc -> count(mc, Items.BUCKET) == 1 && !mc.level.getFluidState(floor.above()).isEmpty(), "pour the water back out: " + pour);
		cmd(world, "setblock %d %d %d air", floor.getX(), floor.getY() + 1, floor.getZ());
		cmd(world, "setblock %d %d %d stone", water.getX(), water.getY(), water.getZ());

		cmd(world, "give @a furnace");
		cmd(world, "give @a raw_iron 2");
		cmd(world, "give @a coal 2");
		cmd(world, "give @a chest");
		context.waitTicks(2);
		BlockPos furnacePos = new BlockPos(x + 4, y, z + 6);
		String placeFurnace = run(context, new PlaceTask(Lookup.item("minecraft:furnace"), furnacePos));
		check(context, mc -> mc.level.getBlockState(furnacePos).is(Blocks.FURNACE), "place furnace: " + placeFurnace);
		String smelt = run(context, new SmeltTask(Lookup.item("minecraft:raw_iron"), null, 2, null));
		// One coal smelts 8 items, so 2 raw iron must burn exactly one of the two.
		check(context, mc -> count(mc, Items.IRON_INGOT) == 2 && count(mc, Items.COAL) == 1, "smelt raw iron with one coal: " + smelt);

		BlockPos chestPos = new BlockPos(x + 6, y, z + 6);
		String placeChest = run(context, new PlaceTask(Lookup.item("minecraft:chest"), chestPos));
		check(context, mc -> mc.level.getBlockState(chestPos).is(Blocks.CHEST), "place chest: " + placeChest);
		int cobble = context.computeOnClient(mc -> count(mc, Items.COBBLESTONE));
		String put = run(context, new ContainerTask(chestPos, ContainerTask.Action.PUT, Lookup.item("minecraft:cobblestone"), 64));
		check(context, mc -> count(mc, Items.COBBLESTONE) == 0, "put cobblestone in chest: " + put);
		String take = run(context, new ContainerTask(chestPos, ContainerTask.Action.TAKE, Lookup.item("minecraft:cobblestone"), 64));
		check(context, mc -> count(mc, Items.COBBLESTONE) == cobble, "take cobblestone back: " + take);

		cmd(world, "summon pig %d %d %d", x - 6, y, z - 4);
		context.waitTicks(5);
		String attack = run(context, new AttackTask(entity -> entity instanceof Pig, "pig", 1));
		check(context, mc -> mc.level.getEntitiesOfClass(Pig.class, mc.player.getBoundingBox().inflate(48), Pig::isAlive).isEmpty(),
			"kill the pig: " + attack);
	}

	private static void eating(ClientGameTestContext context, TestSingleplayerContext world) {
		cmd(world, "difficulty easy");
		cmd(world, "effect give @a hunger 30 255");
		context.waitFor(mc -> mc.player.getFoodData().getFoodLevel() <= 14, 20 * 60);
		cmd(world, "effect clear @a");
		// Peaceful refills hunger by itself, so it stays easy until both meals are checked.
		cmd(world, "give @a bread 3");
		context.waitTicks(2);
		int foodBefore = context.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
		String eat = run(context, new EatTask(null));
		check(context, mc -> mc.player.getFoodData().getFoodLevel() > foodBefore && count(mc, Items.BREAD) == 2, "eat bread: " + eat);

		// Vanilla ignores the use key while a screen is open, as when the player reads chat during a goal.
		context.runOnClient(mc -> mc.gui.setScreen(new ChatScreen("", false)));
		int foodWithChat = context.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
		String eatWithChat = run(context, new EatTask(null));
		check(context, mc -> mc.player.getFoodData().getFoodLevel() > foodWithChat && count(mc, Items.BREAD) == 1 && mc.gui.screen() instanceof ChatScreen,
			"eat bread with chat open: " + eatWithChat);
		context.runOnClient(mc -> mc.gui.setScreen(null));
		cmd(world, "difficulty peaceful");
	}

	private static void effects(ClientGameTestContext context, TestSingleplayerContext world) {
		cmd(world, "effect give @a poison 20 1");
		cmd(world, "effect give @a speed 60 0");
		context.waitTicks(5);
		String observed = context.computeOnClient(WorldInfo::observe);
		String alerts = context.computeOnClient(WorldInfo::alerts);
		log("Effects line: " + observed.lines().filter(line -> line.startsWith("Effects:")).findFirst().orElse("(missing)") + " | " + alerts);
		assertTrue(observed.contains("poison II") && observed.contains("(harmful)") && observed.contains("speed I"),
			"observe should list effects: " + observed);
		assertTrue(alerts != null && alerts.contains("poison"), "alerts should flag poison: " + alerts);
		cmd(world, "effect clear @a");
		context.waitTicks(5);
		assertTrue(context.computeOnClient(WorldInfo::alerts) == null, "no alerts once effects are cleared");
	}

	/** gather finishes the tree it started instead of cutting the bottom of every tree around. */
	private static void wholeTree(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) throws TaskFailure {
		int x = start.getX();
		int y = start.getY();
		int z = start.getZ() - 9;
		cmd(world, "fill %d %d %d %d %d %d air", x - 8, y, z - 2, x + 8, y + 5, z + 2);
		cmd(world, "fill %d %d %d %d %d %d oak_log", x + 3, y, z, x + 3, y + 4, z);
		cmd(world, "fill %d %d %d %d %d %d oak_log", x - 6, y, z, x - 6, y + 4, z);
		cmd(world, "clear @a oak_log");
		cmd(world, "tp @a %d.5 %d %d.5", x, y, z);
		context.waitTicks(10);
		String gather = run(context, new GatherTask(Lookup.block("minecraft:oak_log"), 5, 16));
		int nearLeft = context.computeOnClient(mc -> countLogs(mc, new BlockPos(x + 3, y, z)));
		int farLeft = context.computeOnClient(mc -> countLogs(mc, new BlockPos(x - 6, y, z)));
		log("Whole tree: near tree has " + nearLeft + " logs left, far tree " + farLeft + ": " + gather);
		assertTrue(nearLeft == 0 && farLeft == 5, "gather should cut the whole near tree and leave the far one: near " + nearLeft
			+ " left, far " + farLeft + " left; " + gather);
	}

	private static int countLogs(Minecraft mc, BlockPos base) {
		int logs = 0;
		for (int dy = 0; dy < 5; dy++) {
			if (mc.level.getBlockState(base.above(dy)).is(Blocks.OAK_LOG)) {
				logs++;
			}
		}
		return logs;
	}

	/** observe reports the server's tick rate: exact in singleplayer, estimated from time updates on a remote server. */
	private static void serverTps(ClientGameTestContext context, TestSingleplayerContext world) {
		context.waitTicks(40);
		String normal = context.computeOnClient(WorldInfo::observe);
		Matcher rate = Pattern.compile("server TPS ([0-9.]+)/20").matcher(normal);
		assertTrue(rate.find() && Double.parseDouble(rate.group(1)) > 15.0, "observe should show about 20 TPS: " + normal.lines().limit(3).toList());
		assertTrue(!normal.contains("(estimated)"), "singleplayer TPS is measured, not estimated: " + normal.lines().limit(3).toList());

		cmd(world, "tick rate 10");
		context.waitTicks(40);
		String slowed = context.computeOnClient(WorldInfo::observe);
		Matcher halved = Pattern.compile("server TPS ([0-9.]+)/10").matcher(slowed);
		assertTrue(halved.find() && Double.parseDouble(halved.group(1)) <= 10.0, "a tick rate of 10 should show as /10: " + slowed.lines().limit(3).toList());
		cmd(world, "tick freeze");
		context.waitTicks(10);
		String frozen = context.computeOnClient(WorldInfo::observe);
		assertTrue(frozen.contains("server ticking frozen"), "a frozen server should be reported: " + frozen.lines().limit(3).toList());
		cmd(world, "tick unfreeze");
		cmd(world, "tick rate 20");

		// A remote server's rate comes from its time updates: 100 ticks in 10 s is 10 TPS.
		ServerTps.reset();
		ServerTps.record(1_000, 0L);
		ServerTps.record(1_100, 10_000_000_000L);
		Double estimated = ServerTps.estimate();
		assertTrue(estimated != null && Math.abs(estimated - 10.0) < 0.01, "100 ticks in 10 s should estimate 10 TPS: " + estimated);
		ServerTps.record(500, 11_000_000_000L);
		assertTrue(ServerTps.estimate() == null, "time going backwards means another world, so the estimate should restart");
		ServerTps.reset();
		log("Server TPS OK: " + rate.group(0) + ", " + halved.group(0) + ", frozen, estimate 10.0");
	}

	/**
	 * Queued gizmos prove nothing if a renderer mod skips them, so this checks the line reaches the screen. The walker
	 * turns the head wherever the route goes, so a fixed route is shown under a camera that looks down at it.
	 */
	private static void pathOnScreen(ClientGameTestContext context, BlockPos start) {
		context.runOnClient(mc -> {
			mc.player.setYRot(-90.0F);
			mc.player.setXRot(45.0F);
			BlockPos from = start;
			List<PathStep> steps = new ArrayList<>();
			for (int i = 1; i <= 2; i++) {
				BlockPos next = start.east(i);
				steps.add(new PathStep(from, next, MoveType.WALK, List.of()));
				from = next;
			}
			PathDisplay.show(steps, 0);
		});
		context.waitTicks(5);
		Path screenshot = context.takeScreenshot("mineagent-path-fixed");
		context.runOnClient(mc -> PathDisplay.clear());
		int routePixels = routePixels(screenshot);
		log("Path overlay: " + routePixels + " route-colored pixels in " + screenshot);
		assertTrue(routePixels >= MIN_ROUTE_PIXELS, "the path line should be visible in the screenshot: " + routePixels + " pixels in " + screenshot);
	}

	/** Pixels close to the overlay's route color (0x33DDFF); nothing else in the test area is that cyan. */
	private static int routePixels(Path screenshot) {
		try (InputStream in = Files.newInputStream(screenshot); NativeImage image = NativeImage.read(in)) {
			int count = 0;
			for (int px = 0; px < image.getWidth(); px++) {
				for (int py = 0; py < image.getHeight(); py++) {
					int argb = image.getPixel(px, py);
					if (Math.abs((argb >> 16 & 0xFF) - 0x33) < 40 && Math.abs((argb >> 8 & 0xFF) - 0xDD) < 40 && Math.abs((argb & 0xFF) - 0xFF) < 40) {
						count++;
					}
				}
			}
			return count;
		} catch (IOException e) {
			throw new AssertionError("could not read " + screenshot, e);
		}
	}
}
