package moe.tenshii.mineagent.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.impl.display.DisplaySpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Recipes from Roughly Enough Items, through the same search its recipe screen runs.
 */
final class ReiRecipes implements RecipeViewer {
	// REI's built-in pages that list tag members or show text, not ways to make the item.
	private static final Set<Identifier> NOT_RECIPES = Set.of(
		Identifier.withDefaultNamespace("plugins/tag"), Identifier.withDefaultNamespace("plugins/information"));

	@Override
	public String name() {
		return "REI";
	}

	@Override
	public @Nullable String notReady() {
		// Displays arrive from the server after joining, so an empty registry means they are still on the way.
		return PluginManager.areAnyReloading() || DisplayRegistry.getInstance().size() == 0
			? "REI is still loading recipes; try again in a few seconds." : null;
	}

	@Override
	public List<Category> recipesFor(ItemStack stack) {
		Map<DisplayCategory<?>, List<DisplaySpec>> found = ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(stack)).buildMapInternal();
		List<Category> categories = new ArrayList<>();
		for (Map.Entry<DisplayCategory<?>, List<DisplaySpec>> entry : found.entrySet()) {
			DisplayCategory<?> category = entry.getKey();
			if (NOT_RECIPES.contains(category.getCategoryIdentifier().getIdentifier())) {
				continue;
			}
			List<Recipe> recipes = new ArrayList<>();
			for (DisplaySpec spec : entry.getValue().subList(0, Math.min(MAX_PER_CATEGORY, entry.getValue().size()))) {
				Display display = spec.provideInternalDisplay();
				recipes.add(new Recipe(slots(display.getInputEntries()), slots(display.getOutputEntries())));
			}
			categories.add(new Category(category.getTitle().getString(), category.getCategoryIdentifier().getIdentifier(),
				workstations(category), recipes, entry.getValue().size() > MAX_PER_CATEGORY));
		}
		return categories;
	}

	private static List<Identifier> workstations(DisplayCategory<?> category) {
		List<Identifier> result = new ArrayList<>();
		for (EntryIngredient workstation : CategoryRegistry.getInstance().get(category.getCategoryIdentifier()).getWorkstations()) {
			List<Option> options = options(workstation);
			if (!options.isEmpty()) {
				result.add(options.getFirst().id());
			}
		}
		return result;
	}

	private static List<List<Option>> slots(List<EntryIngredient> ingredients) {
		List<List<Option>> slots = new ArrayList<>();
		for (EntryIngredient ingredient : ingredients) {
			List<Option> options = options(ingredient);
			if (!options.isEmpty()) {
				slots.add(options);
			}
		}
		return slots;
	}

	private static List<Option> options(EntryIngredient ingredient) {
		List<Option> options = new ArrayList<>();
		for (EntryStack<?> entry : ingredient) {
			if (entry.isEmpty()) {
				continue;
			}
			if (entry.getType() == VanillaEntryTypes.ITEM) {
				ItemStack item = entry.castValue();
				options.add(new Option(BuiltInRegistries.ITEM.getKey(item.getItem()), item.getCount(), true));
			} else if (entry.getIdentifier() != null) {
				options.add(new Option(entry.getIdentifier(), 0, false));
			}
		}
		return options;
	}
}
