package moe.tenshii.mineagent.game;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The installed mods that add blocks, items or mobs, found by their namespaces in the registries, so the model knows
 * which ids exist beyond vanilla.
 */
public final class ContentMods {
	// A big modpack would otherwise spend thousands of tokens on a list the model rarely needs whole.
	private static final int MAX_LISTED = 40;

	private ContentMods() {
	}

	private record Counts(int blocks, int items, int mobs) {
		Counts plus(Counts other) {
			return new Counts(blocks + other.blocks, items + other.items, mobs + other.mobs);
		}
	}

	/**
	 * One line naming each content mod with its namespace and what it adds.
	 *
	 * @return the line, or null when only vanilla content is registered
	 */
	public static @Nullable String describe() {
		Map<String, Counts> byNamespace = new TreeMap<>();
		count(BuiltInRegistries.BLOCK, byNamespace, new Counts(1, 0, 0));
		count(BuiltInRegistries.ITEM, byNamespace, new Counts(0, 1, 0));
		count(BuiltInRegistries.ENTITY_TYPE, byNamespace, new Counts(0, 0, 1));
		if (byNamespace.isEmpty()) {
			return null;
		}
		String listed = byNamespace.entrySet().stream()
			.limit(MAX_LISTED)
			.map(entry -> entry.getKey() + " (" + modName(entry.getKey()) + ": " + summary(entry.getValue()) + ")")
			.collect(Collectors.joining(", "));
		int hidden = byNamespace.size() - MAX_LISTED;
		return hidden > 0 ? listed + " and " + hidden + " more" : listed;
	}

	private static void count(Registry<?> registry, Map<String, Counts> byNamespace, Counts one) {
		for (Identifier id : registry.keySet()) {
			if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
				byNamespace.merge(id.getNamespace(), one, Counts::plus);
			}
		}
	}

	private static String modName(String namespace) {
		return FabricLoader.getInstance().getModContainer(namespace)
			.map(mod -> mod.getMetadata().getName())
			.orElse(namespace);
	}

	private static String summary(Counts counts) {
		StringBuilder out = new StringBuilder();
		append(out, counts.blocks, "block");
		append(out, counts.items, "item");
		append(out, counts.mobs, "entity type");
		return out.toString();
	}

	private static void append(StringBuilder out, int amount, String noun) {
		if (amount == 0) {
			return;
		}
		if (!out.isEmpty()) {
			out.append(", ");
		}
		out.append(amount).append(' ').append(noun).append(amount == 1 ? "" : "s");
	}
}
