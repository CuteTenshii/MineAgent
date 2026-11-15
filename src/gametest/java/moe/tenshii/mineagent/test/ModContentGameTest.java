package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.check;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.count;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.run;

import moe.tenshii.mineagent.game.ContentMods;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.CraftTask;
import moe.tenshii.mineagent.game.task.SmeltTask;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

/**
 * Content from real mods loaded into the dev runtime (see build.gradle): Variant Crafting Tables, Brick Furnace and
 * Farmer's Delight.
 */
public final class ModContentGameTest implements FabricClientGameTest {
	private static final String TABLE = "variantcraftingtables:birch_crafting_table";

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			contentList();
			moddedIds();
			craftAtModdedTable(ctx, world, start);
			smeltInModdedFurnaceWithModdedFuel(ctx, world, start);
		});
	}

	private static void contentList() {
		String mods = ContentMods.describe();
		log("Content mods: " + mods);
		assertTrue(mods != null && mods.contains("variantcraftingtables (Variant Crafting Tables: ")
			&& mods.contains("brickfurnace (") && mods.contains("farmersdelight (Farmer's Delight: "),
			"the content mods should be listed by namespace and name: " + mods);
		assertTrue(!mods.contains("mineagent"), "MineAgent adds no content and should not be listed: " + mods);
	}

	private static void moddedIds() throws TaskFailure {
		Item tomato = Lookup.exactItem("farmersdelight:tomato");
		assertTrue(Lookup.name(tomato).equals("farmersdelight:tomato"), "modded ids should keep their namespace: " + Lookup.name(tomato));
		String stove = Lookup.block("farmersdelight:stove").label();
		assertTrue(stove.equals("farmersdelight:stove"), "modded blocks should be found by id: " + stove);
	}

	/** A smithing table subclasses the crafting table block; it sits closer so craft has to skip it. */
	private static void craftAtModdedTable(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start) {
		BlockPos smithing = start.offset(2, 0, -3);
		BlockPos table = start.offset(-4, 0, -5);
		cmd(world, "setblock %d %d %d smithing_table", smithing.getX(), smithing.getY(), smithing.getZ());
		cmd(world, "setblock %d %d %d %s", table.getX(), table.getY(), table.getZ(), TABLE);
		cmd(world, "give @a oak_planks 3");
		cmd(world, "give @a stick 2");
		context.waitTicks(10);
		String craft = run(context, new CraftTask(Items.WOODEN_PICKAXE, 1));
		check(context, mc -> count(mc, Items.WOODEN_PICKAXE) == 1, "craft a wooden pickaxe at the birch crafting table: " + craft);
		boolean usedModdedTable = context.computeOnClient(mc -> mc.player.blockPosition().distSqr(table) < mc.player.blockPosition().distSqr(smithing));
		assertTrue(usedModdedTable, "the player should have walked to the modded table, not the smithing table: " + craft);
		cmd(world, "clear @a wooden_pickaxe");
	}

	/** The variant tables burn for a fixed time set by their mod; smelt must load exactly enough of them. */
	private static void smeltInModdedFurnaceWithModdedFuel(ClientGameTestContext context, TestSingleplayerContext world, BlockPos start)
		throws TaskFailure {
		Item tableItem = Lookup.exactItem(TABLE);
		CookingFuel fuel = new ItemStack(tableItem).get(DataComponents.COOKING_FUEL);
		assertTrue(fuel != null && fuel.burnTime() instanceof ResolvableInt.Constant,
			"the birch crafting table should be a fuel with a fixed burn time: " + fuel);
		int burnTicks = ((ResolvableInt.Constant) fuel.burnTime()).value();
		int smelts = 3;
		int needed = (int) Math.ceil(smelts * 200.0 / burnTicks);
		log("Birch crafting table burns " + burnTicks + " ticks; " + needed + " needed for " + smelts + " items");

		BlockPos furnace = start.offset(5, 0, 4);
		cmd(world, "setblock %d %d %d brickfurnace:brick_furnace", furnace.getX(), furnace.getY(), furnace.getZ());
		cmd(world, "give @a raw_iron %d", smelts);
		cmd(world, "give @a %s %d", TABLE, needed + 1);
		context.waitTicks(10);
		String observe = context.computeOnClient(WorldInfo::observe);
		assertTrue(observe.contains("brickfurnace:brick_furnace"), "observe should name the modded furnace nearby");
		String smelt = run(context, new SmeltTask(Lookup.item("minecraft:raw_iron"), null, smelts, null));
		check(context, mc -> count(mc, Items.IRON_INGOT) == smelts && count(mc, tableItem) == 1,
			"smelt " + smelts + " raw iron in the brick furnace burning exactly " + needed + " birch tables: " + smelt);
	}
}
