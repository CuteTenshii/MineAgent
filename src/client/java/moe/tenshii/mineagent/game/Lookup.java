package moe.tenshii.mineagent.game;

import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns the ids the model writes ("oak_log", "minecraft:oak_log", "#minecraft:logs") into game predicates.
 */
public final class Lookup {
	private Lookup() {
	}

	public record ItemQuery(String label, Predicate<ItemStack> matches) {
	}

	public record BlockQuery(String label, Predicate<BlockState> matches) {
	}

	public static ItemQuery item(String raw) throws TaskFailure {
		String text = normalize(raw);
		if (text.startsWith("#")) {
			Identifier id = parseId(text.substring(1), raw);
			TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
			return new ItemQuery("#" + id, stack -> !stack.isEmpty() && stack.is(tag));
		}
		Identifier id = parseId(text, raw);
		Item item = BuiltInRegistries.ITEM.getOptional(id)
			.orElseThrow(() -> new TaskFailure("Unknown item '" + raw + "'. Use ids like minecraft:oak_planks or tags like #minecraft:planks."));
		return new ItemQuery(name(item), stack -> stack.is(item));
	}

	public static Item exactItem(String raw) throws TaskFailure {
		String text = normalize(raw);
		if (text.startsWith("#")) {
			throw new TaskFailure("'" + raw + "' is a tag; this action needs one specific item id.");
		}
		Identifier id = parseId(text, raw);
		return BuiltInRegistries.ITEM.getOptional(id)
			.orElseThrow(() -> new TaskFailure("Unknown item '" + raw + "'. Use ids like minecraft:oak_planks."));
	}

	public static BlockQuery block(String raw) throws TaskFailure {
		String text = normalize(raw);
		if (text.startsWith("#")) {
			Identifier id = parseId(text.substring(1), raw);
			TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
			return new BlockQuery("#" + id, state -> state.is(tag));
		}
		Identifier id = parseId(text, raw);
		Block block = BuiltInRegistries.BLOCK.getOptional(id)
			.orElseThrow(() -> new TaskFailure("Unknown block '" + raw + "'. Use ids like minecraft:iron_ore or tags like #minecraft:logs."));
		return new BlockQuery(name(block), state -> state.is(block));
	}

	/** Items of {@code item} that hold the potion {@code potion}, e.g. water bottles: minecraft:potion with water. */
	public static ItemQuery withPotion(ItemQuery item, String potion) throws TaskFailure {
		Identifier id = parseId(normalize(potion), potion);
		Holder<Potion> holder = BuiltInRegistries.POTION.get(id)
			.orElseThrow(() -> new TaskFailure("Unknown potion '" + potion + "'. Use ids like water, awkward or minecraft:swiftness."));
		return new ItemQuery(item.label() + " of " + shorten(id), stack -> {
			PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
			return item.matches().test(stack) && contents != null && contents.is(holder);
		});
	}

	public static EntityType<?> entityType(String raw) throws TaskFailure {
		Identifier id = parseId(normalize(raw), raw);
		return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
			.orElseThrow(() -> new TaskFailure("Unknown entity type '" + raw + "'. Use ids like minecraft:cow."));
	}

	private static String normalize(String raw) {
		return raw.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
	}

	private static Identifier parseId(String text, String raw) throws TaskFailure {
		Identifier id = Identifier.tryParse(text);
		if (id == null) {
			throw new TaskFailure("'" + raw + "' is not a valid id.");
		}
		return id;
	}

	public static String name(Item item) {
		return shorten(BuiltInRegistries.ITEM.getKey(item));
	}

	public static String name(Block block) {
		return shorten(BuiltInRegistries.BLOCK.getKey(block));
	}

	public static String name(EntityType<?> type) {
		return shorten(BuiltInRegistries.ENTITY_TYPE.getKey(type));
	}

	public static String shorten(Identifier id) {
		return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? id.getPath() : id.toString();
	}
}
