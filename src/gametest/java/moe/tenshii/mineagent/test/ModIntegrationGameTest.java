package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.runExpectingFailure;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import moe.tenshii.mineagent.compat.RecipeViewer;
import moe.tenshii.mineagent.compat.RecipeViewers;
import moe.tenshii.mineagent.compat.WaypointSource;
import moe.tenshii.mineagent.compat.Waypoints;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.task.CraftTask;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The map and recipe viewer integrations against the real mods in the dev runtime (see build.gradle): waypoints in
 * Xaero's Minimap and JourneyMap, and Farmer's Delight cooking pot recipes through REI and JEI.
 */
public final class ModIntegrationGameTest implements FabricClientGameTest {
	private static final int LOAD_TIMEOUT_TICKS = 20 * 60;
	private static final Identifier COOKING_POT = Identifier.fromNamespaceAndPath("farmersdelight", "cooking_pot");
	private static final Identifier TOMATO = Identifier.fromNamespaceAndPath("farmersdelight", "tomato");

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			waypoints(ctx, start);
			recipeViewers(ctx);
		});
	}

	private static void waypoints(ClientGameTestContext context, BlockPos start) {
		List<String> mods = Waypoints.sources().stream().map(WaypointSource::mod).toList();
		assertTrue(mods.equals(List.of("Xaero's Minimap", "JourneyMap")), "both map mods should be waypoint sources: " + mods);

		BlockPos base = start.offset(40, 3, -30);
		// The map mods load their per-world waypoint data a moment after joining.
		String[] added = new String[1];
		context.waitFor(mc -> {
			try {
				added[0] = Waypoints.add(mc, "Test Base", base);
				return true;
			} catch (TaskFailure notYet) {
				added[0] = notYet.getMessage();
				return false;
			}
		}, LOAD_TIMEOUT_TICKS);
		log("Waypoint add: " + added[0]);
		assertTrue(added[0].contains("to Xaero's Minimap and JourneyMap."), "the waypoint should go to both map mods: " + added[0]);

		String listed = context.computeOnClient(mc -> list(mc, null));
		log("Waypoints:\n" + listed);
		Matcher found = Pattern.compile("\"Test Base\" at " + Pattern.quote(base.toShortString()) + ", 50 blocks away").matcher(listed);
		assertTrue(found.results().count() == 2, "both map mods should list the new waypoint with its distance: " + listed);

		String filtered = context.computeOnClient(mc -> list(mc, "test b"));
		assertTrue(filtered.contains("\"Test Base\""), "a name filter should match case-insensitively: " + filtered);
		String none = context.computeOnClient(mc -> list(mc, "no such place"));
		assertTrue(none.contains("no waypoint named like \"no such place\""), "an unmatched filter should say so: " + none);
	}

	private static String list(Minecraft mc, @Nullable String filter) {
		try {
			return Waypoints.list(mc, filter);
		} catch (TaskFailure e) {
			return "failed: " + e.getMessage();
		}
	}

	private static void recipeViewers(ClientGameTestContext context) throws TaskFailure {
		List<String> names = RecipeViewers.viewers().stream().map(RecipeViewer::name).toList();
		assertTrue(names.equals(List.of("REI", "JEI")), "both recipe viewers should be available: " + names);
		context.waitFor(mc -> RecipeViewers.viewers().stream().allMatch(viewer -> viewer.notReady() == null), LOAD_TIMEOUT_TICKS);

		Item sauce = Lookup.exactItem("farmersdelight:tomato_sauce");
		for (RecipeViewer viewer : RecipeViewers.viewers()) {
			// The server's modded recipes reach REI a few ticks after its own reload says it is done.
			context.waitFor(mc -> viewer.recipesFor(new ItemStack(sauce)).stream().anyMatch(category -> category.workstations().contains(COOKING_POT)),
				LOAD_TIMEOUT_TICKS);
			List<RecipeViewer.Category> categories = context.computeOnClient(mc -> viewer.recipesFor(new ItemStack(sauce)));
			log(viewer.name() + " categories for tomato sauce: " + categories.stream()
				.map(category -> category.title() + " " + category.id() + " at " + category.workstations()).toList());
			RecipeViewer.Category cooking = categories.stream().filter(category -> category.workstations().contains(COOKING_POT)).findFirst()
				.orElseThrow(() -> new AssertionError(viewer.name() + " should list a recipe made at the cooking pot: " + categories));
			boolean tomatoes = cooking.recipes().stream().flatMap(recipe -> recipe.inputs().stream())
				.anyMatch(slot -> slot.stream().anyMatch(option -> option.id().equals(TOMATO)));
			assertTrue(tomatoes, viewer.name() + " should list tomatoes as a cooking pot input: " + cooking);
		}

		String described = context.computeOnClient(mc -> RecipeViewers.describe(sauce));
		log("recipes text:\n" + described);
		assertTrue(described.startsWith("REI recipes of every kind") && described.contains("at farmersdelight:cooking_pot")
			&& (described.contains("2 any of farmersdelight:tomato") || described.contains("2 farmersdelight:tomato")),
			"the recipes text should name the cooking pot and two tomatoes: " + described);
		assertTrue(!described.contains("plugins/tag"), "REI's tag listings are not recipes and should be left out: " + described);

		String craft = runExpectingFailure(context, new CraftTask(sauce, 1));
		assertTrue(craft.contains("REI lists these ways to make it") && craft.contains("farmersdelight:cooking_pot"),
			"a failed craft should point at the cooking pot: " + craft);
	}
}
