package moe.tenshii.mineagent.compat;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Recipes of every kind (crafting, smelting, modded machines) as a recipe viewer mod knows them. Called on the game
 * thread; implementations touch the mod's classes, so they are only created when that mod is loaded.
 */
public interface RecipeViewer {
	/** Recipes listed per category; more than this only says that more exist. */
	int MAX_PER_CATEGORY = 3;

	/** One way to fill a slot: an item with its count, or another kind of ingredient such as a fluid. */
	record Option(Identifier id, int count, boolean item) {
	}

	/** Each slot lists the alternatives that fit it, e.g. every plank for "any planks". */
	record Recipe(List<List<Option>> inputs, List<List<Option>> outputs) {
	}

	/** A recipe kind, e.g. a mod machine, with the blocks or items that perform it. */
	record Category(String title, Identifier id, List<Identifier> workstations, List<Recipe> recipes, boolean more) {
	}

	/** The mod's display name, e.g. "JEI". */
	String name();

	/** Why recipes cannot be read right now, or null when they can. */
	@Nullable String notReady();

	/** The categories with recipes that produce {@code stack}, each holding at most {@link #MAX_PER_CATEGORY} recipes. */
	List<Category> recipesFor(ItemStack stack);
}
