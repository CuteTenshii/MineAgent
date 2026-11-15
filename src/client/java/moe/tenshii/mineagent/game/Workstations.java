package moe.tenshii.mineagent.game;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Recognizes crafting tables and furnaces from other mods as well as the vanilla ones: by the vanilla block classes
 * mods reuse, and by the shared {@code c} tags they put their variants in.
 */
public final class Workstations {
	// The tag older mods still use; Variant Crafting Tables puts its tables only here.
	private static final TagKey<Block> LEGACY_WORKBENCHES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "workbench"));

	private Workstations() {
	}

	public static boolean isCraftingTable(BlockState state) {
		// The exact class: smithing tables subclass it but open a different menu.
		return state.getBlock().getClass() == CraftingTableBlock.class
			|| state.is(ConventionalBlockTags.PLAYER_WORKSTATIONS_CRAFTING_TABLES)
			|| state.is(LEGACY_WORKBENCHES);
	}

	/** Furnaces, smokers and blast furnaces, plus modded furnaces tagged as such. */
	public static boolean isFurnace(BlockState state) {
		return state.getBlock() instanceof AbstractFurnaceBlock || state.is(ConventionalBlockTags.PLAYER_WORKSTATIONS_FURNACES);
	}

	/** A crafting grid big enough for any recipe: the vanilla table's menu or a modded table's own. */
	public static boolean isTableGrid(AbstractContainerMenu menu) {
		return menu instanceof AbstractCraftingMenu crafting && crafting.getGridWidth() >= 3 && crafting.getGridHeight() >= 3;
	}
}
