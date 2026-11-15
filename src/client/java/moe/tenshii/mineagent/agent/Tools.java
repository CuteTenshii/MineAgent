package moe.tenshii.mineagent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import moe.tenshii.mineagent.agent.llm.ToolResult;
import moe.tenshii.mineagent.agent.llm.ToolSpec;
import moe.tenshii.mineagent.game.CreativeItems;
import moe.tenshii.mineagent.game.GameThread;
import moe.tenshii.mineagent.game.InventoryOps;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Recipes;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.AnvilTask;
import moe.tenshii.mineagent.game.task.AttackTask;
import moe.tenshii.mineagent.game.task.BrewTask;
import moe.tenshii.mineagent.game.task.CollectTask;
import moe.tenshii.mineagent.game.task.CommandTask;
import moe.tenshii.mineagent.game.task.ContainerTask;
import moe.tenshii.mineagent.game.task.CraftTask;
import moe.tenshii.mineagent.game.task.DropTask;
import moe.tenshii.mineagent.game.task.EatTask;
import moe.tenshii.mineagent.game.task.EnchantTask;
import moe.tenshii.mineagent.game.task.FlyTask;
import moe.tenshii.mineagent.game.task.GatherTask;
import moe.tenshii.mineagent.game.task.GrindstoneTask;
import moe.tenshii.mineagent.game.task.LookTask;
import moe.tenshii.mineagent.game.task.MineTask;
import moe.tenshii.mineagent.game.task.PlaceTask;
import moe.tenshii.mineagent.game.task.SmeltTask;
import moe.tenshii.mineagent.game.task.SmithTask;
import moe.tenshii.mineagent.game.task.StonecutTask;
import moe.tenshii.mineagent.game.task.UseBlockTask;
import moe.tenshii.mineagent.game.task.WaitTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * The tools offered to the model and their implementations.
 */
final class Tools {
	private static final int MAX_SEARCH_RADIUS = 64;

	interface Context {
		String runTask(Task task) throws TaskFailure, InterruptedException;

		<T> T onGame(Supplier<T> action) throws TaskFailure, InterruptedException;

		/** Sends an image to the model along with this call's text result. */
		void attachImage(ToolResult.Image image);
	}

	@FunctionalInterface
	private interface Handler {
		String run(ToolArgs args, Context context) throws TaskFailure, InterruptedException;
	}

	private record Definition(ToolSpec spec, Handler handler) {
	}

	private final Map<String, Definition> definitions = new LinkedHashMap<>();
	private volatile boolean lookDisabled;

	/**
	 * @param allowCommands whether to offer run_command; the tool still re-checks the setting before each command
	 * @param allowLook whether to offer look, which needs a model that reads images
	 */
	Tools(boolean allowCommands, boolean allowLook) {
		register(new Schema("observe",
				"Get the player's current state: position, health, hunger, time of day, held item, armor, full inventory, "
					+ "nearby entities, and every block type within 8 blocks with its nearest position. Call this first and whenever unsure."),
			(args, ctx) -> ctx.onGame(() -> WorldInfo.observe(Minecraft.getInstance())));

		if (allowLook) {
			register(new Schema("look",
					"Take a screenshot of what the player sees (HUD hidden), optionally turning to face a block first. Use it to check "
						+ "a build, read the surroundings or confirm something text cannot show. Each screenshot costs about 1,000 input "
						+ "tokens, so use it when it helps, not after every action.")
					.integer("x", "Optional block x to face", false)
					.integer("y", "Optional block y to face", false)
					.integer("z", "Optional block z to face", false),
				this::look);
		}

		register(new Schema("find_blocks",
				"Search loaded chunks for blocks by id (minecraft:iron_ore) or tag (#minecraft:logs). Returns positions nearest first, "
					+ "with distance and whether each block touches air (exposed blocks are cheaper to reach).")
				.str("block", "Block id or #tag", true)
				.integer("radius", "Search radius in blocks, default 48, max " + MAX_SEARCH_RADIUS, false)
				.integer("limit", "Maximum results, default 10, max 50", false),
			this::findBlocks);

		register(new Schema("inspect_block", "Describe the block at a position: full block state, hardness, tool requirement and distance.")
				.position(),
			(args, ctx) -> {
				BlockPos pos = args.pos();
				return ctx.onGame(() -> WorldInfo.inspectBlock(Minecraft.getInstance().level, Minecraft.getInstance().player, pos));
			});

		register(new Schema("find_entities", "List living entities, players and dropped items near the player, nearest first, with their numeric ids.")
				.str("type", "Optional entity type id such as minecraft:cow; omit to list everything", false)
				.integer("radius", "Search radius in blocks, default 32, max 64", false),
			this::findEntities);

		register(new Schema("recipes",
				"List the unlocked crafting recipes that make an item, their ingredients, whether a crafting table is needed, "
					+ "and whether the current inventory can craft them.")
				.str("item", "Item id, e.g. minecraft:stone_pickaxe", true),
			this::recipes);

		register(new Schema("walk_to",
				"Pathfind to a position and walk there. Breaks blocks in the way and pillars up with dirt/cobblestone/planks when needed. "
					+ "Long trips are fine; the path is re-planned as chunks load.")
				.position()
				.integer("range", "How close the feet must get, in blocks; default 0 (exact block), max 32", false)
				.bool("allow_breaking", "Whether the path may break blocks; default true. Set false near builds you must not damage.", false),
			this::walkTo);

		register(new Schema("fly_to",
				"Fly to a position through open air, like a creative player (needs creative or spectator mode, or a server that "
					+ "grants flight; observe says whether the player can fly). Faster than walking and never breaks blocks. "
					+ "By default lands on the ground below the destination; pass land=false to hover there, e.g. to build in the air.")
				.position()
				.integer("range", "How close the feet must get, in blocks; default 0 (exact block), max 32", false)
				.bool("land", "Descend to the ground below on arrival and stop flying; default true", false),
			this::flyTo);

		register(new Schema("fly",
				"Start or stop flying in place. on=true takes off and hovers about a block up; on=false descends straight down "
					+ "until standing on the ground. walk_to and tools that walk land on their own first.")
				.bool("on", "true to take off, false to land", true),
			(args, ctx) -> ctx.runTask(args.optBool("on", true) ? FlyTask.takeOff() : FlyTask.landHere()));

		register(new Schema("give_item",
				"Creative mode only: take any item from the creative menu into the inventory, instead of gathering or crafting it. "
					+ "Without to, it goes into the first empty slot, hotbar first.")
				.str("item", "Item id, e.g. minecraft:diamond_pickaxe", true)
				.integer("count", "How many, from 1 up to the item's stack size; default 1", false)
				.str("to", "Optional slot to fill, e.g. hotbar 1, inv 5 or offhand; whatever is there is deleted", false),
			this::giveItem);

		if (allowCommands) {
			register(new Schema("run_command",
					"Run a server command as the player, e.g. time set day, gamemode creative, tp, fill, give. Only commands the server "
						+ "lets this player use go through; others are refused before sending. Returns the server's replies.")
					.str("command", "The command, with or without the leading slash", true),
				(args, ctx) -> ctx.runTask(new CommandTask(args.string("command"))));
		}

		register(new Schema("mine_block",
				"Walk into reach of one block, break it with the best tool carried, and pick up the drops. "
					+ "Fails if the block needs a better tool to drop anything unless force is true.")
				.position()
				.bool("collect_drops", "Pick up the drops afterwards; default true", false)
				.bool("force", "Break the block even if it will drop nothing; default false", false),
			(args, ctx) -> ctx.runTask(new MineTask(args.pos(), args.optBool("collect_drops", true), args.optBool("force", false))));

		register(new Schema("gather",
				"Find and mine several nearby blocks of one kind (nearest and exposed first), picking up drops. "
					+ "Use this instead of many mine_block calls, e.g. to get 5 logs or 12 cobblestone.")
				.str("block", "Block id or #tag, e.g. #minecraft:logs or minecraft:stone", true)
				.integer("count", "How many blocks to break, 1-64", true)
				.integer("radius", "Search radius, default 32, max " + MAX_SEARCH_RADIUS, false),
			(args, ctx) -> ctx.runTask(new GatherTask(Lookup.block(args.string("block")), args.optInt("count", 1, 1, 64),
				args.optInt("radius", 32, 4, MAX_SEARCH_RADIUS))));

		register(new Schema("place_block", "Place a block item from the inventory at a position. The position must be empty and next to a solid block.")
				.str("item", "Block item id, e.g. minecraft:crafting_table", true)
				.position(),
			(args, ctx) -> ctx.runTask(new PlaceTask(Lookup.item(args.string("item")), args.pos())));

		register(new Schema("craft",
				"Craft an item through the recipe book. Small recipes use the inventory grid; 3x3 recipes walk to a crafting table "
					+ "within 24 blocks. count is how many result items you want (recipes that make several per craft may overshoot).")
				.str("item", "Item id, e.g. minecraft:oak_planks", true)
				.integer("count", "How many items to end up with, 1-256; default 1", false),
			(args, ctx) -> ctx.runTask(new CraftTask(Lookup.exactItem(args.string("item")), args.optInt("count", 1, 1, 256))));

		register(new Schema("smelt",
				"Smelt or cook items in a furnace, smoker or blast furnace: loads exactly count items plus enough fuel, waits, and collects "
					+ "the output. Uses the nearest furnace within 24 blocks unless x, y and z are given. This blocks until done (10s per item in a furnace).")
				.str("item", "Input item id or #tag, e.g. minecraft:raw_iron", true)
				.integer("count", "How many items to smelt, 1-64", true)
				.str("fuel", "Optional fuel item id; by default the longest-burning fuel carried", false)
				.integer("x", "Optional furnace x", false)
				.integer("y", "Optional furnace y", false)
				.integer("z", "Optional furnace z", false),
			this::smelt);

		register(new Schema("stonecut",
				"Cut blocks into stairs, slabs, walls, bricks and other shapes at a stonecutter within 24 blocks (or at x, y, z). "
					+ "Picks a carried input that makes the item and loads just enough of it.")
				.str("item", "Item to make, e.g. minecraft:stone_brick_stairs", true)
				.integer("count", "How many to make, 1-64; default 1 (recipes that make two per block may overshoot by one)", false)
				.optionalPosition("stonecutter"),
			(args, ctx) -> ctx.runTask(new StonecutTask(Lookup.exactItem(args.string("item")), args.optInt("count", 1, 1, 64), optPos(args))));

		register(new Schema("smith",
				"Use a smithing table within 24 blocks (or at x, y, z): upgrade diamond gear to netherite, or trim armor.")
				.str("template", "Smithing template id, e.g. minecraft:netherite_upgrade_smithing_template", true)
				.str("base", "The item to upgrade or trim, e.g. minecraft:diamond_pickaxe", true)
				.str("addition", "The material, e.g. minecraft:netherite_ingot or a trim material such as minecraft:gold_ingot", true)
				.optionalPosition("smithing table"),
			(args, ctx) -> ctx.runTask(new SmithTask(Lookup.item(args.string("template")), Lookup.item(args.string("base")),
				Lookup.item(args.string("addition")), optPos(args))));

		register(new Schema("anvil",
				"Use an anvil within 24 blocks (or at x, y, z) on one item: combine it with another of the same kind or an enchanted "
					+ "book, repair it with its material (up to 4 units), and/or rename it. Costs experience levels; fails if the "
					+ "player cannot pay.")
				.str("item", "Item id or #tag to work on", true)
				.str("with", "Optional second item: same item, enchanted_book, or repair material such as minecraft:iron_ingot", false)
				.str("name", "Optional new name, up to 50 characters", false)
				.optionalPosition("anvil"),
			(args, ctx) -> ctx.runTask(new AnvilTask(Lookup.item(args.string("item")),
				args.has("with") ? Lookup.item(args.string("with")) : null, args.optString("name"), optPos(args))));

		register(new Schema("enchant",
				"Use an enchanting table within 24 blocks (or at x, y, z). Without option, lists the three offers for the item with "
					+ "their level requirements and a hint of one enchantment each. With option 1-3, takes that offer, paying that "
					+ "many lapis lazuli and levels.")
				.str("item", "Item id or #tag to enchant, e.g. minecraft:diamond_sword or minecraft:book", true)
				.integer("option", "1, 2 or 3 to enchant; omit to list offers", false)
				.optionalPosition("enchanting table"),
			(args, ctx) -> ctx.runTask(new EnchantTask(Lookup.item(args.string("item")), args.optInt("option", 0, 0, 3), optPos(args))));

		register(new Schema("grindstone",
				"Use a grindstone within 24 blocks (or at x, y, z): remove non-curse enchantments from an item (returning some "
					+ "experience), or merge two damaged items of the same kind.")
				.str("item", "Item id or #tag", true)
				.str("with", "Optional second item of the same kind to merge with", false)
				.optionalPosition("grindstone"),
			(args, ctx) -> ctx.runTask(new GrindstoneTask(Lookup.item(args.string("item")),
				args.has("with") ? Lookup.item(args.string("with")) : null, optPos(args))));

		register(new Schema("brew",
				"Brew potions at a brewing stand within 24 blocks (or at x, y, z): loads up to 3 bottles and one ingredient, adds "
					+ "blaze_powder fuel if the stand has none, waits about 20 seconds and collects the bottles. Usual chain: water "
					+ "bottles + nether_wart = awkward; awkward + an effect ingredient (sugar, blaze_powder...) = effect potion; "
					+ "redstone extends, glowstone_dust strengthens, gunpowder makes splash.")
				.str("ingredient", "Ingredient id, e.g. minecraft:nether_wart", true)
				.str("bottles", "Bottle item id; default minecraft:potion (also splash_potion, lingering_potion)", false)
				.str("potion", "Optional potion the bottles must hold, e.g. water, awkward or minecraft:swiftness", false)
				.integer("count", "Bottles to brew, 1-3; default 3", false)
				.optionalPosition("brewing stand"),
			this::brew);

		register(new Schema("container",
				"Open a chest, barrel, shulker box or other container block and list it, or move whole stacks of an item in or out.")
				.position()
				.enumeration("action", "list, take, or put", List.of("list", "take", "put"), true)
				.str("item", "Item id or #tag to move (required for take/put)", false)
				.integer("count", "Stop after at least this many items moved; default 64", false),
			this::container);

		register(new Schema("use_block",
				"Right-click a block with the held item, aiming at it with a clear line of sight: open or close doors, press buttons, "
					+ "pull levers, sleep in beds, and use items on blocks, such as filling a bucket from a water or lava source "
					+ "(target the fluid block), placing water, bone meal, hoes or flint and steel. Hold the item first with equip. "
					+ "The result lists the inventory change, so check it to see whether the use worked.")
				.position(),
			(args, ctx) -> ctx.runTask(new UseBlockTask(args.pos(), null)));

		register(new Schema("inventory",
				"See and rearrange the inventory slot by slot. list: every slot's contents. move: put an item (by id/tag, or by its "
					+ "current slot with from) into a slot; whatever was there swaps places. tidy: merge partial stacks and put weapon, "
					+ "pickaxe, axe, shovel, food, torches, building blocks and water bucket in hotbar 1-8. Slots are named hotbar 1-9, "
					+ "inv 1-27 and offhand (e.g. move a shield or torches to offhand).")
				.enumeration("action", "list, move or tidy", List.of("list", "move", "tidy"), true)
				.str("item", "For move: item id or #tag to move", false)
				.str("from", "For move: the slot to move from, instead of item", false)
				.str("to", "For move: destination slot, e.g. hotbar 1, inv 5, offhand", false),
			this::inventory);

		register(new Schema("equip",
				"Hold an item in the main hand. Armor, elytra and shields are worn/equipped instead of just held.")
				.str("item", "Item id or #tag", true),
			this::equip);

		register(new Schema("use_item", "Right-click the air with the held item (throw, drink, place-free use).")
				, this::useItem);

		register(new Schema("eat", "Eat food to restore hunger. Picks the most nourishing safe food unless an item is given.")
				.str("item", "Optional food item id", false),
			(args, ctx) -> ctx.runTask(new EatTask(args.has("item") ? Lookup.item(args.string("item")) : null)));

		register(new Schema("attack",
				"Chase and melee-attack living entities with the best sword or axe carried until count of them are dead. "
					+ "Target either an entity id from observe/find_entities, an entity type, or hostile=true for any hostile mob.")
				.integer("entity_id", "Numeric id of one specific entity", false)
				.str("type", "Entity type id, e.g. minecraft:zombie or minecraft:cow", false)
				.bool("hostile", "Attack the nearest hostile mobs of any type", false)
				.integer("count", "How many to kill, 1-32; default 1", false),
			this::attack);

		register(new Schema("collect_items", "Walk over dropped items near the player to pick them up.")
				.integer("radius", "Radius around the player, default 8, max 32", false)
				.str("item", "Optional item id or #tag to pick up only that", false),
			this::collectItems);

		register(new Schema("drop_item",
				"Throw items a few blocks ahead; they despawn after 5 minutes. Only drop things you are sure you will not need "
					+ "(spare stone, dirt, gravel...). Never drop tools, weapons, armor, food or crafting stations to make space: "
					+ "store them in a chest, or craft compact blocks. Tools, weapons and armor are refused unless force=true.")
				.str("item", "Item id or #tag", true)
				.integer("count", "How many to drop; default all", false)
				.bool("force", "Allow dropping tools, weapons and armor; default false", false),
			this::dropItem);


		register(new Schema("wait", "Do nothing for a while, e.g. while crops grow or night passes.")
				.integer("seconds", "1-60", true),
			(args, ctx) -> ctx.runTask(new WaitTask(args.optInt("seconds", 1, 1, 60) * 20)));
	}

	private void register(Schema schema, Handler handler) {
		definitions.put(schema.name, new Definition(schema.build(), handler));
	}

	List<ToolSpec> specs() {
		List<ToolSpec> specs = new ArrayList<>();
		definitions.values().forEach(definition -> specs.add(definition.spec()));
		return specs;
	}

	String execute(String name, Map<String, Object> input, Context context) throws TaskFailure, InterruptedException {
		Definition definition = definitions.get(name);
		if (definition == null) {
			throw new TaskFailure("Unknown tool '" + name + "'.");
		}
		return definition.handler().run(new ToolArgs(input), context);
	}

	private String findBlocks(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.BlockQuery query = Lookup.block(args.string("block"));
		int radius = args.optInt("radius", 48, 1, MAX_SEARCH_RADIUS);
		int limit = args.optInt("limit", 10, 1, 50);
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			List<WorldInfo.Found> found = WorldInfo.findBlocks(mc.level, mc.player.blockPosition(), radius, query.matches(), limit);
			if (found.isEmpty()) {
				return "No " + query.label() + " within " + radius + " blocks of " + mc.player.blockPosition().toShortString()
					+ " (only loaded chunks are searched).";
			}
			StringBuilder text = new StringBuilder("Nearest ").append(query.label()).append(" (player at ")
				.append(mc.player.blockPosition().toShortString()).append("):");
			for (WorldInfo.Found entry : found) {
				text.append(String.format(Locale.ROOT, "%n%s %s %.1fm%s", WorldInfo.blockName(mc.level, entry.pos()), entry.pos().toShortString(),
					entry.distance(), entry.exposed() ? " exposed" : " buried"));
			}
			return text.toString();
		});
	}

	private String findEntities(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		EntityType<?> type = args.has("type") ? Lookup.entityType(args.string("type")) : null;
		int radius = args.optInt("radius", 32, 1, 64);
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			return WorldInfo.describeEntities(mc.level, mc.player, radius, entity -> type == null || entity.getType() == type, 25);
		});
	}

	private String recipes(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Item item = Lookup.exactItem(args.string("item"));
		return ctx.onGame(() -> {
			LocalPlayer player = Minecraft.getInstance().player;
			List<Recipes.CraftingRecipe> recipes = Recipes.craftingRecipesFor(player, item);
			if (recipes.isEmpty()) {
				return "No unlocked crafting recipe makes " + Lookup.name(item) + ". It may need smelting, may be found rather than crafted, "
					+ "or its recipe unlocks after you obtain one of its ingredients.";
			}
			StringBuilder text = new StringBuilder("Recipes for ").append(Lookup.name(item)).append(':');
			for (Recipes.CraftingRecipe recipe : recipes) {
				text.append("\n- makes ").append(recipe.result().getCount()).append(" from ").append(Recipes.describeIngredients(player, recipe))
					.append(recipe.needsTable() ? "; needs a crafting table" : "; fits the inventory grid")
					.append(Recipes.canCraft(player, recipe) ? "; craftable now" : "; missing ingredients");
			}
			return text.toString();
		});
	}

	private String walkTo(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		BlockPos target = args.pos();
		int range = args.optInt("range", 0, 0, 32);
		boolean breaking = args.optBool("allow_breaking", true);
		double distance = ctx.onGame(() -> Math.sqrt(Minecraft.getInstance().player.blockPosition().distSqr(target)));
		int timeout = (int) Math.min(20 * 900, 20 * (45 + distance * 2.0));
		return ctx.runTask(new WalkTask(Goal.near(target, range), target.toShortString(), breaking, true, timeout));
	}

	/** For a model that turned out not to accept images; look stays listed so the history stays valid, but refuses. */
	void disableLook() {
		lookDisabled = true;
	}

	private String look(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		if (lookDisabled) {
			throw new TaskFailure("look is unavailable: this model does not accept images.");
		}
		LookTask task = new LookTask(optPos(args));
		String caption = ctx.runTask(task);
		ctx.attachImage(new ToolResult.Image(LookTask.MEDIA_TYPE, task.encodedImage()));
		return caption;
	}

	private String brew(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.ItemQuery bottles = Lookup.item(args.has("bottles") ? args.string("bottles") : "minecraft:potion");
		if (args.has("potion")) {
			bottles = Lookup.withPotion(bottles, args.string("potion"));
		}
		return ctx.runTask(new BrewTask(bottles, args.optInt("count", 3, 1, 3), Lookup.item(args.string("ingredient")), optPos(args)));
	}

	private static @Nullable BlockPos optPos(ToolArgs args) throws TaskFailure {
		return args.has("x") || args.has("y") || args.has("z") ? args.pos() : null;
	}

	private String flyTo(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		BlockPos target = args.pos();
		int range = args.optInt("range", 0, 0, 32);
		boolean land = args.optBool("land", true);
		double distance = ctx.onGame(() -> Math.sqrt(Minecraft.getInstance().player.blockPosition().distSqr(target)));
		int timeout = (int) Math.min(20 * 900, 20 * (30 + distance * 0.5));
		return ctx.runTask(FlyTask.to(target, range, land, timeout));
	}

	private String giveItem(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Item item = Lookup.exactItem(args.string("item"));
		int count = args.optInt("count", 1, 1, 99);
		InventoryOps.SlotRef slot = args.has("to") ? InventoryOps.parseSlot(args.string("to")) : null;
		return ctx.onGame(() -> CreativeItems.give(Minecraft.getInstance(), item, count, slot));
	}

	private String smelt(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.ItemQuery input = Lookup.item(args.string("item"));
		Lookup.ItemQuery fuel = args.has("fuel") ? Lookup.item(args.string("fuel")) : null;
		int count = args.optInt("count", 1, 1, 64);
		BlockPos pos = args.has("x") || args.has("y") || args.has("z") ? args.pos() : null;
		return ctx.runTask(new SmeltTask(input, fuel, count, pos));
	}

	private String container(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		ContainerTask.Action action = switch (args.string("action").toLowerCase(Locale.ROOT)) {
			case "list" -> ContainerTask.Action.LIST;
			case "take" -> ContainerTask.Action.TAKE;
			case "put" -> ContainerTask.Action.PUT;
			default -> throw new TaskFailure("action must be list, take or put.");
		};
		Lookup.ItemQuery item = null;
		if (action != ContainerTask.Action.LIST) {
			item = Lookup.item(args.string("item"));
		}
		return ctx.runTask(new ContainerTask(args.pos(), action, item, args.optInt("count", 64, 1, 2304)));
	}

	private String inventory(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		String action = args.string("action").toLowerCase(Locale.ROOT);
		switch (action) {
			case "list" -> {
				return ctx.onGame(() -> InventoryOps.list(Minecraft.getInstance().player));
			}
			case "tidy" -> {
				return ctx.onGame(() -> InventoryOps.tidy(Minecraft.getInstance()));
			}
			case "move" -> {
				InventoryOps.SlotRef to = InventoryOps.parseSlot(args.string("to"));
				InventoryOps.SlotRef from = args.has("from") ? InventoryOps.parseSlot(args.string("from")) : null;
				Lookup.ItemQuery item = from == null ? Lookup.item(args.string("item")) : null;
				return ctx.onGame(() -> {
					Minecraft mc = Minecraft.getInstance();
					LocalPlayer player = mc.player;
					InventoryOps.SlotRef source = from != null ? from : InventoryOps.find(player, item.matches());
					if (source == null) {
						throw new GameThread.GameStateException("No " + item.label() + " in the inventory.");
					}
					ItemStack moving = source.stack(player).copy();
					ItemStack displaced = to.stack(player).copy();
					if (moving.isEmpty()) {
						throw new GameThread.GameStateException(source.label() + " is empty.");
					}
					InventoryOps.swap(mc, source, to);
					return "Moved " + WorldInfo.stackName(moving) + " from " + source.label() + " to " + to.label()
						+ (displaced.isEmpty() ? "." : "; " + WorldInfo.stackName(displaced) + " went to " + source.label() + ".");
				});
			}
			default -> throw new TaskFailure("action must be list, move or tidy.");
		}
	}

	private String equip(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.ItemQuery query = Lookup.item(args.string("item"));
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (!PlayerControl.holdItem(mc, query.matches())) {
				throw new GameThread.GameStateException("No " + query.label() + " in the inventory.");
			}
			ItemStack held = player.getMainHandItem();
			Equippable equippable = held.get(DataComponents.EQUIPPABLE);
			if (equippable != null && equippable.slot() != EquipmentSlot.MAINHAND) {
				String name = Lookup.name(held.getItem());
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
				return "Equipped " + name + " in the " + equippable.slot().getName() + " slot.";
			}
			return "Now holding " + WorldInfo.stackName(held) + ".";
		});
	}

	private String useItem(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			String held = WorldInfo.stackName(player.getMainHandItem());
			InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			if (result instanceof InteractionResult.Success success) {
				if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
					PlayerControl.swingUse(player);
				}
				return "Used " + held + ".";
			}
			return "Using " + held + " had no effect.";
		});
	}

	private String attack(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		int count = args.optInt("count", 1, 1, 32);
		Predicate<Entity> filter;
		String label;
		if (args.has("entity_id")) {
			int id = args.integer("entity_id");
			filter = entity -> entity.getId() == id;
			label = "entity #" + id;
			count = 1;
		} else if (args.has("type")) {
			EntityType<?> type = Lookup.entityType(args.string("type"));
			filter = entity -> entity.getType() == type;
			label = Lookup.name(type);
		} else if (args.optBool("hostile", false)) {
			filter = entity -> entity instanceof Enemy;
			label = "hostile mobs";
		} else {
			throw new TaskFailure("Give entity_id, type, or hostile=true.");
		}
		return ctx.runTask(new AttackTask(filter, label, count));
	}

	private String collectItems(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		int radius = args.optInt("radius", 8, 1, 32);
		Lookup.ItemQuery item = args.has("item") ? Lookup.item(args.string("item")) : null;
		BlockPos center = ctx.onGame(() -> Minecraft.getInstance().player.blockPosition());
		return ctx.runTask(new CollectTask(center, radius, item == null ? null : item.matches(), 20 * 60));
	}

	private String dropItem(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.ItemQuery query = Lookup.item(args.string("item"));
		int wanted = args.optInt("count", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
		return ctx.runTask(new DropTask(query, wanted, args.optBool("force", false)));
	}

	/**
	 * Small builder for a tool's JSON schema.
	 */
	private static final class Schema {
		private final String name;
		private final String description;
		private final Map<String, Object> properties = new LinkedHashMap<>();
		private final List<String> required = new ArrayList<>();

		Schema(String name, String description) {
			this.name = name;
			this.description = description;
		}

		Schema str(String key, String text, boolean isRequired) {
			return property(key, Map.of("type", "string", "description", text), isRequired);
		}

		Schema integer(String key, String text, boolean isRequired) {
			return property(key, Map.of("type", "integer", "description", text), isRequired);
		}

		Schema bool(String key, String text, boolean isRequired) {
			return property(key, Map.of("type", "boolean", "description", text), isRequired);
		}

		Schema enumeration(String key, String text, List<String> values, boolean isRequired) {
			return property(key, Map.of("type", "string", "description", text, "enum", values), isRequired);
		}

		Schema position() {
			return integer("x", "Block x", true).integer("y", "Block y", true).integer("z", "Block z", true);
		}

		Schema optionalPosition(String block) {
			return integer("x", "Optional " + block + " x", false).integer("y", "Optional " + block + " y", false)
				.integer("z", "Optional " + block + " z", false);
		}

		private Schema property(String key, Map<String, Object> schema, boolean isRequired) {
			properties.put(key, schema);
			if (isRequired) {
				required.add(key);
			}
			return this;
		}

		ToolSpec build() {
			return new ToolSpec(name, description, Collections.unmodifiableMap(new LinkedHashMap<>(properties)), List.copyOf(required));
		}
	}
}
