package moe.tenshii.mineagent.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Recipes from Just Enough Items. JEI creates this class from the "jei_mod_plugin" entrypoint and hands it the
 * runtime once a world is joined; MineAgent creates another instance to read from that runtime.
 */
@JeiPlugin
public final class JeiRecipes implements IModPlugin, RecipeViewer {
	private static volatile @Nullable IJeiRuntime runtime;

	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("mineagent", "recipes");
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime available) {
		runtime = available;
	}

	@Override
	public void onRuntimeUnavailable() {
		runtime = null;
	}

	@Override
	public String name() {
		return "JEI";
	}

	@Override
	public @Nullable String notReady() {
		return runtime == null ? "JEI is still loading recipes; try again in a few seconds." : null;
	}

	@Override
	public List<Category> recipesFor(ItemStack stack) {
		IJeiRuntime jei = runtime;
		if (jei == null) {
			return List.of();
		}
		IRecipeManager recipes = jei.getRecipeManager();
		IFocusFactory focuses = jei.getJeiHelpers().getFocusFactory();
		IFocus<ItemStack> focus = focuses.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack);
		IFocusGroup group = focuses.createFocusGroup(List.of(focus));
		List<Category> categories = new ArrayList<>();
		// Information pages hold text about the item, not ways to make it.
		recipes.createRecipeCategoryLookup().limitFocus(List.of(focus)).get()
			.filter(category -> !category.getRecipeType().getUid().equals(RecipeTypes.INFORMATION.getUid()))
			.forEach(category -> categories.add(category(jei, recipes, category, focus, group)));
		return categories;
	}

	private static <T> Category category(IJeiRuntime jei, IRecipeManager recipes, IRecipeCategory<T> category, IFocus<ItemStack> focus,
		IFocusGroup group) {
		IRecipeType<T> type = category.getRecipeType();
		List<T> found = recipes.createRecipeLookup(type).limitFocus(List.of(focus)).get().limit(MAX_PER_CATEGORY + 1).toList();
		List<Recipe> described = new ArrayList<>();
		for (T recipe : found.subList(0, Math.min(MAX_PER_CATEGORY, found.size()))) {
			Optional<IRecipeLayoutDrawable<T>> layout = recipes.createRecipeLayoutDrawable(category, recipe, group);
			layout.ifPresent(drawable -> described.add(new Recipe(
				slots(jei, drawable.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)),
				slots(jei, drawable.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT)))));
		}
		List<Identifier> workstations = recipes.createCraftingStationLookup(type).getItemStack()
			.map(item -> BuiltInRegistries.ITEM.getKey(item.getItem())).toList();
		return new Category(category.getTitle().getString(), type.getUid(), workstations, described, found.size() > MAX_PER_CATEGORY);
	}

	private static List<List<Option>> slots(IJeiRuntime jei, List<IRecipeSlotView> views) {
		List<List<Option>> slots = new ArrayList<>();
		for (IRecipeSlotView view : views) {
			List<Option> options = new ArrayList<>();
			for (ITypedIngredient<?> ingredient : view.getAllIngredientsList()) {
				Option option = option(jei, ingredient);
				if (option != null) {
					options.add(option);
				}
			}
			if (!options.isEmpty()) {
				slots.add(options);
			}
		}
		return slots;
	}

	private static <V> @Nullable Option option(IJeiRuntime jei, ITypedIngredient<V> ingredient) {
		Optional<ItemStack> item = ingredient.getItemStack();
		if (item.isPresent()) {
			return item.get().isEmpty() ? null
				: new Option(BuiltInRegistries.ITEM.getKey(item.get().getItem()), item.get().getCount(), true);
		}
		Identifier id = jei.getIngredientManager().getIngredientHelper(ingredient.getType()).getIdentifier(ingredient.getIngredient());
		return new Option(id, 0, false);
	}
}
