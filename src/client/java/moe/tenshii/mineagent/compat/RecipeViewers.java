package moe.tenshii.mineagent.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.game.Lookup;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Describes every recipe for an item, modded machines included, through whichever recipe viewer mod is installed.
 */
public final class RecipeViewers {
	private static final int MAX_CATEGORIES = 10;
	private static final int MAX_OPTIONS_SHOWN = 4;
	private static final int MAX_SLOTS_SHOWN = 12;
	private static @Nullable List<RecipeViewer> viewers;

	private RecipeViewers() {
	}

	/** The installed recipe viewers MineAgent can read, preferred first. */
	public static synchronized List<RecipeViewer> viewers() {
		if (viewers == null) {
			List<RecipeViewer> found = new ArrayList<>();
			load(found, "roughlyenoughitems", ReiRecipes::new);
			load(found, "jei", JeiRecipes::new);
			viewers = List.copyOf(found);
		}
		return viewers;
	}

	private static void load(List<RecipeViewer> found, String modId, Supplier<RecipeViewer> adapter) {
		if (!FabricLoader.getInstance().isModLoaded(modId)) {
			return;
		}
		try {
			found.add(adapter.get());
		} catch (LinkageError e) {
			MineAgentClient.LOGGER.warn("Recipes from {} are unavailable with this version of it", modId, e);
		}
	}

	/**
	 * Every recipe kind that makes {@code item}, with workstations and ingredients, from the first recipe viewer
	 * that can answer.
	 *
	 * @return the description, or null when no recipe viewer is installed
	 */
	public static @Nullable String describe(Item item) {
		if (viewers().isEmpty()) {
			return null;
		}
		List<String> problems = new ArrayList<>();
		for (RecipeViewer viewer : viewers()) {
			List<RecipeViewer.Category> categories = read(viewer, item, problems);
			if (categories == null) {
				continue;
			}
			if (categories.isEmpty()) {
				return viewer.name() + " knows no recipe of any kind that makes " + Lookup.name(item) + "; it is likely found, mined or dropped.";
			}
			StringBuilder out = new StringBuilder(viewer.name()).append(" recipes of every kind that make ").append(Lookup.name(item))
				.append(", including other mods' machines:");
			for (RecipeViewer.Category category : categories.subList(0, Math.min(MAX_CATEGORIES, categories.size()))) {
				out.append("\n").append(header(category)).append(':');
				// Viewers can list the same recipe twice, e.g. once per variant they register.
				category.recipes().stream()
					.map(recipe -> slots(recipe.outputs()) + (recipe.inputs().isEmpty() ? "" : " from " + slots(recipe.inputs())))
					.distinct()
					.forEach(line -> out.append("\n  - ").append(line));
				if (category.more()) {
					out.append("\n  - and more recipes of this kind");
				}
			}
			if (categories.size() > MAX_CATEGORIES) {
				out.append("\nand ").append(categories.size() - MAX_CATEGORIES).append(" more recipe kinds");
			}
			return out.toString();
		}
		return String.join(" ", problems);
	}

	/**
	 * The recipe kinds that make {@code item} and where they are made, in one line, for a failed craft.
	 *
	 * @return the line, or null when no recipe viewer knows a recipe for it
	 */
	public static @Nullable String brief(Item item) {
		for (RecipeViewer viewer : viewers()) {
			List<RecipeViewer.Category> categories = read(viewer, item, new ArrayList<>());
			if (categories != null && !categories.isEmpty()) {
				return viewer.name() + " lists these ways to make it: " + categories.stream().limit(MAX_CATEGORIES)
					.map(RecipeViewers::header).collect(Collectors.joining("; ")) + ". Call recipes for the ingredients.";
			}
		}
		return null;
	}

	private static @Nullable List<RecipeViewer.Category> read(RecipeViewer viewer, Item item, List<String> problems) {
		String notReady = viewer.notReady();
		if (notReady != null) {
			problems.add(notReady);
			return null;
		}
		try {
			return viewer.recipesFor(new ItemStack(item));
		} catch (LinkageError | RuntimeException e) {
			MineAgentClient.LOGGER.warn("Reading recipes for {} from {} failed", Lookup.name(item), viewer.name(), e);
			problems.add(viewer.name() + " could not list recipes (" + e + ").");
			return null;
		}
	}

	private static String header(RecipeViewer.Category category) {
		String where = category.workstations().isEmpty() ? ""
			: " at " + category.workstations().stream().distinct().limit(MAX_OPTIONS_SHOWN).map(Lookup::shorten).collect(Collectors.joining(" or "));
		return category.title() + " (" + Lookup.shorten(category.id()) + ")" + where;
	}

	/** Identical slots are merged, so three single-plank slots read "3 oak_planks". */
	private static String slots(List<List<RecipeViewer.Option>> slots) {
		Map<String, Integer> merged = new LinkedHashMap<>();
		for (List<RecipeViewer.Option> slot : slots) {
			RecipeViewer.Option first = slot.getFirst();
			merged.merge(alternatives(slot), first.item() ? Math.max(1, first.count()) : 0, Integer::sum);
		}
		String shown = merged.entrySet().stream().limit(MAX_SLOTS_SHOWN)
			.map(entry -> entry.getValue() == 0 ? entry.getKey() : entry.getValue() + " " + entry.getKey())
			.collect(Collectors.joining(", "));
		return merged.size() > MAX_SLOTS_SHOWN ? shown + " and " + (merged.size() - MAX_SLOTS_SHOWN) + " more" : shown;
	}

	private static String alternatives(List<RecipeViewer.Option> slot) {
		List<String> names = slot.stream().map(option -> Lookup.shorten(option.id()) + (option.item() ? "" : " (not an item)"))
			.distinct().toList();
		if (names.size() == 1) {
			return names.getFirst();
		}
		String shown = String.join(" / ", names.subList(0, Math.min(MAX_OPTIONS_SHOWN, names.size())));
		return "any of " + shown + (names.size() > MAX_OPTIONS_SHOWN ? " / " + (names.size() - MAX_OPTIONS_SHOWN) + " more" : "");
	}
}
