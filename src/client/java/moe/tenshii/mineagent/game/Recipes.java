package moe.tenshii.mineagent.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Crafting recipes the player has unlocked, read from the client recipe book.
 */
public final class Recipes {
	private Recipes() {
	}

	public record CraftingRecipe(RecipeDisplayEntry entry, ItemStack result, boolean needsTable, List<SlotDisplay> ingredients) {
	}

	public static List<CraftingRecipe> craftingRecipesFor(LocalPlayer player, Item item) {
		ContextMap context = SlotDisplayContext.fromLevel(player.level());
		ClientRecipeBook book = player.getRecipeBook();
		List<CraftingRecipe> found = new ArrayList<>();
		for (RecipeCollection collection : book.getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				CraftingRecipe recipe = asCrafting(entry, context);
				if (recipe != null && recipe.result().is(item)) {
					found.add(recipe);
				}
			}
		}
		return found;
	}

	private static CraftingRecipe asCrafting(RecipeDisplayEntry entry, ContextMap context) {
		RecipeDisplay display = entry.display();
		ItemStack result = display.result().resolveForFirstStack(context);
		if (result.isEmpty()) {
			return null;
		}
		return switch (display) {
			case ShapedCraftingRecipeDisplay shaped ->
				new CraftingRecipe(entry, result, shaped.width() > 2 || shaped.height() > 2, shaped.ingredients());
			case ShapelessCraftingRecipeDisplay shapeless ->
				new CraftingRecipe(entry, result, shapeless.ingredients().size() > 4, shapeless.ingredients());
			default -> null;
		};
	}

	public static StackedItemContents inventoryContents(LocalPlayer player) {
		StackedItemContents contents = new StackedItemContents();
		player.getInventory().fillStackedContents(contents);
		return contents;
	}

	/** True when the inventory holds the ingredients, or the server did not say what they are. */
	public static boolean canCraft(LocalPlayer player, CraftingRecipe recipe) {
		if (recipe.entry().craftingRequirements().isEmpty()) {
			return true;
		}
		return recipe.entry().canCraft(inventoryContents(player));
	}

	/** "3x oak_planks, 2x stick" with alternatives joined by "/". */
	public static String describeIngredients(LocalPlayer player, CraftingRecipe recipe) {
		ContextMap context = SlotDisplayContext.fromLevel(player.level());
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (SlotDisplay slot : recipe.ingredients()) {
			List<ItemStack> options = slot.resolveForStacks(context);
			if (options.isEmpty()) {
				continue;
			}
			List<String> names = new ArrayList<>();
			for (ItemStack option : options) {
				String name = Lookup.name(option.getItem());
				if (!names.contains(name)) {
					names.add(name);
				}
				if (names.size() == 4) {
					names.add("...");
					break;
				}
			}
			counts.merge(String.join("/", names), 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((name, count) -> parts.add(count + "x " + name));
		return String.join(", ", parts);
	}
}
