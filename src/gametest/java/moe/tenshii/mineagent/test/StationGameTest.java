package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.check;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.count;
import static moe.tenshii.mineagent.test.GameTestKit.run;
import static moe.tenshii.mineagent.test.GameTestKit.runExpectingFailure;

import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.task.AnvilTask;
import moe.tenshii.mineagent.game.task.BrewTask;
import moe.tenshii.mineagent.game.task.EnchantTask;
import moe.tenshii.mineagent.game.task.GrindstoneTask;
import moe.tenshii.mineagent.game.task.SmithTask;
import moe.tenshii.mineagent.game.task.StonecutTask;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The workstation tools with real items in survival, each station placed in a row near the player.
 */
public final class StationGameTest implements FabricClientGameTest {
	private static final String[] STATIONS = {"stonecutter", "smithing_table", "anvil", "enchanting_table", "grindstone", "brewing_stand"};
	private static final String[] ITEMS = {
		"stone 4", "netherite_upgrade_smithing_template", "diamond_sword", "netherite_ingot", "iron_pickaxe[damage=200]", "iron_ingot 2",
		"lapis_lazuli 3", "book", "potion[potion_contents={potion:\"minecraft:water\"}] 3", "nether_wart", "blaze_powder"};

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			for (int i = 0; i < STATIONS.length; i++) {
				cmd(world, "setblock %d %d %d %s", start.getX() - 6 + i * 2, start.getY(), start.getZ() - 10, STATIONS[i]);
			}
			for (String item : ITEMS) {
				cmd(world, "give @a " + item);
			}
			cmd(world, "xp add @a 30 levels");
			ctx.waitTicks(10);
			stonecutter(ctx);
			smithingTable(ctx);
			anvil(ctx);
			enchantingTable(ctx);
			grindstone(ctx);
			brewingStand(ctx, world);
		});
	}

	private static void stonecutter(ClientGameTestContext context) {
		String cut = run(context, new StonecutTask(Items.STONE_SLAB, 4, null));
		check(context, mc -> count(mc, Items.STONE_SLAB) == 4 && count(mc, Items.STONE) == 2, "stonecut 4 slabs from 2 stone: " + cut);
	}

	private static void smithingTable(ClientGameTestContext context) throws TaskFailure {
		String smith = run(context, new SmithTask(Lookup.item("minecraft:netherite_upgrade_smithing_template"),
			Lookup.item("minecraft:diamond_sword"), Lookup.item("minecraft:netherite_ingot"), null));
		check(context, mc -> count(mc, Items.NETHERITE_SWORD) == 1 && count(mc, Items.DIAMOND_SWORD) == 0, "smith a netherite sword: " + smith);
	}

	private static void anvil(ClientGameTestContext context) throws TaskFailure {
		int levelsBefore = context.computeOnClient(mc -> mc.player.experienceLevel);
		String anvil = run(context, new AnvilTask(Lookup.item("minecraft:iron_pickaxe"), Lookup.item("minecraft:iron_ingot"), "Digger", null));
		check(context, mc -> {
			int slot = PlayerControl.findSlot(mc.player, stack -> stack.is(Items.IRON_PICKAXE));
			ItemStack pickaxe = slot < 0 ? ItemStack.EMPTY : mc.player.getInventory().getItem(slot);
			return pickaxe.getDamageValue() < 200 && "Digger".equals(pickaxe.getHoverName().getString()) && mc.player.experienceLevel < levelsBefore;
		}, "the anvil should repair and rename the pickaxe for levels: " + anvil);
	}

	private static void enchantingTable(ClientGameTestContext context) throws TaskFailure {
		String offers = run(context, new EnchantTask(Lookup.item("minecraft:book"), 0, null));
		assertTrue(offers.contains("Offers") && offers.contains("1: needs level"), "enchant should list offers: " + offers);
		check(context, mc -> count(mc, Items.BOOK) == 1 && count(mc, Items.LAPIS_LAZULI) == 3, "listing offers must give the items back");
		String enchant = run(context, new EnchantTask(Lookup.item("minecraft:book"), 1, null));
		check(context, mc -> count(mc, Items.ENCHANTED_BOOK) == 1 && count(mc, Items.LAPIS_LAZULI) == 2, "enchant the book with offer 1: " + enchant);
		assertTrue(enchant.contains("-1 lapis_lazuli"), "the inventory change should count only the lapis used: " + enchant);
	}

	private static void grindstone(ClientGameTestContext context) throws TaskFailure {
		String grind = run(context, new GrindstoneTask(Lookup.item("minecraft:enchanted_book"), null, null));
		check(context, mc -> count(mc, Items.BOOK) == 1 && count(mc, Items.ENCHANTED_BOOK) == 0, "grind the enchantment off: " + grind);
	}

	private static void brewingStand(ClientGameTestContext context, TestSingleplayerContext world) throws TaskFailure {
		Lookup.ItemQuery water = Lookup.withPotion(Lookup.item("minecraft:potion"), "water");
		Lookup.ItemQuery awkward = Lookup.withPotion(Lookup.item("minecraft:potion"), "awkward");
		String brew = run(context, new BrewTask(water, 3, Lookup.item("minecraft:nether_wart"), null));
		check(context, mc -> PlayerControl.countItems(mc.player, awkward.matches()) == 3 && count(mc, Items.NETHER_WART) == 0,
			"brew 3 awkward potions: " + brew);

		String notIngredient = runExpectingFailure(context, new BrewTask(awkward, 1, Lookup.item("minecraft:stone_slab"), null));
		assertTrue(notIngredient.contains("not a brewing ingredient"), "stone slabs are not an ingredient: " + notIngredient);
		// Let the server see the stand close first, or the item is sent to the closed menu and never reaches the client.
		context.waitTicks(10);
		cmd(world, "give @a nether_wart");
		context.waitTicks(5);
		String nothing = runExpectingFailure(context, new BrewTask(awkward, 1, Lookup.item("minecraft:nether_wart"), null));
		assertTrue(nothing.contains("does not brew"), "nether wart on awkward potions brews nothing: " + nothing);
		check(context, mc -> PlayerControl.countItems(mc.player, awkward.matches()) == 3 && count(mc, Items.STONE_SLAB) == 4
			&& count(mc, Items.NETHER_WART) == 1, "failed brews should leave the items with the player");
	}
}
