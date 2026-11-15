package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.check;
import static moe.tenshii.mineagent.test.GameTestKit.cmd;
import static moe.tenshii.mineagent.test.GameTestKit.count;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.MockModel.text;
import static moe.tenshii.mineagent.test.MockModel.toolCall;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.AgentInput;
import moe.tenshii.mineagent.game.PlayerControl;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/**
 * The whole agent loop against scripted local model servers, so no API key or network access is needed: both
 * backends' request formats, the turn limit, damage reports and dying mid-goal.
 */
public final class AgentLoopGameTest implements FabricClientGameTest {
	private static final String CHAT = "/v1/chat/completions";

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			openAiLoop(ctx, world);
			anthropicLoop(ctx);
			turnLimit(ctx);
			damageReport(ctx, world);
			deathDuringGoal(ctx, world, false);
			deathDuringGoal(ctx, world, true);
			advancementDuringGoal(ctx, world);
			questions(ctx);
		});
	}

	private static void openAiLoop(ClientGameTestContext context, TestSingleplayerContext world) {
		try (MockModel model = new MockModel()) {
			model.route(CHAT, (request, turn) -> MockModel.Reply.ok(switch (turn) {
				case 1 -> toolCall("call_1", "observe", "{}");
				case 2 -> toolCall("call_2", "craft", "{\"item\":\"minecraft:oak_planks\",\"count\":4}");
				case 3 -> toolCall("call_3", "look", "{}");
				default -> text("Crafted the planks.");
			})).start();
			cmd(world, "give @a oak_log 1");
			context.waitTicks(5);
			int planksBefore = context.computeOnClient(mc -> count(mc, Items.OAK_PLANKS));
			model.use(Provider.CUSTOM, "/v1", "mock-model");
			AgentConfig.get().vision.put(Provider.CUSTOM.id(), true);
			AgentConfig.get().showToolCalls = true;
			MockModel.runGoal(context, "make some planks");

			check(context, mc -> count(mc, Items.OAK_PLANKS) == planksBefore + 4, "agent loop should craft 4 planks");
			check(context, mc -> !(mc.player.input instanceof AgentInput) && !PlayerControl.inControl(),
				"control should return to the keyboard after the goal");
			List<MockModel.Request> requests = model.requests();
			assertTrue(requests.size() == 4, "expected 4 model requests, got " + requests.size());
			JsonObject observeResult = last(requests.get(1).messages(), 1);
			assertTrue("tool".equals(observeResult.get("role").getAsString()) && observeResult.get("content").getAsString().contains("Position:"),
				"observe result should be sent back as a tool message: " + observeResult);
			JsonObject craftResult = last(requests.get(2).messages(), 1);
			assertTrue(craftResult.get("content").getAsString().startsWith("Crafted 4 oak_planks"), "craft result should report success: " + craftResult);
			assertTrue(requests.getFirst().json().getAsJsonArray("tools").size() >= 20, "all tools should be offered");
			assertTrue(requests.getFirst().offersTool("look"), "look should be offered with vision on");

			JsonObject lookResult = last(requests.get(3).messages(), 2);
			JsonObject imageMessage = last(requests.get(3).messages(), 1);
			assertTrue("tool".equals(lookResult.get("role").getAsString()) && lookResult.get("content").getAsString().contains("attached"),
				"the look result should point to the attached image: " + lookResult);
			JsonArray imageParts = imageMessage.getAsJsonArray("content");
			assertTrue("user".equals(imageMessage.get("role").getAsString()) && imageParts.size() == 2
				&& imageParts.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().startsWith("data:image/jpeg;base64,/9j/"),
				"the screenshot should follow as a JPEG image_url part");
			log("Agent loop OK; craft result: " + craftResult.get("content").getAsString() + " | look: " + lookResult.get("content").getAsString());

			context.runOnClient(mc -> mc.gui.setScreen(new ChatScreen("", false)));
			context.waitTicks(5);
			log("Tool chat screenshot " + context.takeScreenshot("mineagent-tool-chat"));
			context.runOnClient(mc -> mc.gui.setScreen(null));
		}
	}

	private static void anthropicLoop(ClientGameTestContext context) {
		try (MockModel model = new MockModel()) {
			model.route("/v1/messages", (request, turn) -> MockModel.Reply.ok(turn == 1
				? MockModel.anthropic("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-abc\"},"
					+ "{\"type\":\"text\",\"text\":\"Checking the inventory.\"},"
					+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"recipes\",\"input\":{\"item\":\"minecraft:stick\"}},"
					+ "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"look\",\"input\":{}}", "tool_use")
				: MockModel.anthropic("{\"type\":\"text\",\"text\":\"Sticks are craftable.\"}", "end_turn"))).start();
			model.use(Provider.ANTHROPIC, "", null);
			MockModel.runGoal(context, "can I make sticks?");

			List<MockModel.Request> requests = model.chatRequests();
			assertTrue(requests.size() == 2, "expected 2 Messages API requests, got " + requests.size());
			MockModel.Request first = requests.getFirst();
			JsonObject body = first.json();
			assertTrue("claude-opus-5-5".equals(body.get("model").getAsString()), "default model: " + body.get("model"));
			assertTrue("default".equals(body.get("fallbacks").getAsString()), "refusal fallback should be requested");
			assertTrue(String.valueOf(first.headers().get("anthropic-beta")).contains("server-side-fallback-2026-07-01"),
				"fallback beta header: " + first.headers());
			assertTrue(MockModel.TEST_KEY.equals(first.headers().get("x-api-key")) && "2026-10-21".equals(first.headers().get("anthropic-version")),
				"key and API version headers: " + first.headers());
			assertTrue("medium".equals(body.getAsJsonObject("output_config").get("effort").getAsString()), "effort should be sent");
			assertTrue(body.has("cache_control"), "automatic prompt caching should be on");
			assertTrue(body.getAsJsonArray("tools").size() >= 20, "all tools should be offered");

			JsonArray messages = requests.get(1).messages();
			assertTrue(messages.get(1).toString().contains("sig-abc"), "thinking block must be replayed unchanged: " + messages.get(1));
			JsonArray toolTurn = messages.get(2).getAsJsonObject().getAsJsonArray("content");
			JsonObject result = toolTurn.get(0).getAsJsonObject();
			assertTrue("tool_result".equals(result.get("type").getAsString()) && "toolu_1".equals(result.get("tool_use_id").getAsString())
				&& result.toString().contains("Unlocked crafting recipes for stick"), "tool result should answer the call: " + result);
			JsonObject look = toolTurn.get(1).getAsJsonObject();
			JsonArray lookParts = look.getAsJsonArray("content");
			JsonObject source = lookParts.get(1).getAsJsonObject().getAsJsonObject("source");
			assertTrue("toolu_2".equals(look.get("tool_use_id").getAsString()) && "text".equals(lookParts.get(0).getAsJsonObject().get("type").getAsString())
				&& "image".equals(lookParts.get(1).getAsJsonObject().get("type").getAsString()) && "base64".equals(source.get("type").getAsString())
				&& "image/jpeg".equals(source.get("media_type").getAsString()) && source.get("data").getAsString().startsWith("/9j/"),
				"the screenshot should be an image block inside the tool result");
			log("Anthropic loop OK; tool result: " + result + " | look caption: " + lookParts.get(0).getAsJsonObject().get("text").getAsString());
		}
	}

	private static void turnLimit(ClientGameTestContext context) {
		try (MockModel model = new MockModel()) {
			model.route(CHAT, (request, turn) -> MockModel.Reply.ok(toolCall("call_" + turn, "wait", "{\"seconds\":1}"))).start();
			model.use(Provider.CUSTOM, "/v1", "mock-model");
			AgentConfig.get().maxTurns = 2;
			MockModel.startGoal(context, "wait forever");
			context.waitFor(mc -> AgentController.get().phase().startsWith("paused"), 20 * 30);
			context.waitTicks(20);
			log("Paused screenshot " + context.takeScreenshot("mineagent-paused"));
			List<MockModel.Request> requests = model.requests();
			assertTrue(requests.size() == 2, "should pause after 2 turns, made " + requests.size() + " requests");
			check(context, mc -> AgentController.get().isRunning() && !(mc.player.input instanceof AgentInput),
				"the goal stays alive and the keyboard gets control back while paused");

			assertTrue(context.computeOnClient(mc -> AgentController.get().continueGoal(1)) == null, "continue should be accepted");
			context.waitFor(mc -> requests.size() == 3 && AgentController.get().phase().startsWith("paused"), 20 * 30);
			context.waitTicks(20);
			assertTrue(requests.size() == 3, "one more turn after /agent continue 1, made " + requests.size());
			assertTrue(requests.get(2).messages().size() > requests.get(1).messages().size(), "the conversation should carry on, not restart");

			// The [Continue] chat button sends its command this way when clicked.
			context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("agent continue", null));
			context.waitFor(mc -> requests.size() == 5 && AgentController.get().phase().startsWith("paused"), 20 * 30);
			check(context, mc -> mc.gui.screen() == null, "a clicked /agent command must not open a confirmation screen");

			context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("agent stop", null));
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 10);
			log("Turn limit OK: paused at 2, resumed for 1, paused at 3, button continue to 5, button stop");
		}
	}

	private static void damageReport(ClientGameTestContext context, TestSingleplayerContext world) {
		try (MockModel model = new MockModel()) {
			model.route(CHAT, (request, turn) -> MockModel.Reply.ok(turn == 1 ? toolCall("call_wait", "wait", "{\"seconds\":4}")
				: text("Noted the damage."))).start();
			cmd(world, "difficulty easy");
			BlockPos pos = context.computeOnClient(mc -> mc.player.blockPosition());
			cmd(world, "summon zombie %d %d %d {NoAI:1b,Tags:[\"hitter\"]}", pos.getX() + 3, pos.getY(), pos.getZ());
			context.waitTicks(2);
			model.use(Provider.CUSTOM, "/v1", "mock-model");
			MockModel.startGoal(context, "stand still");
			context.waitFor(mc -> model.requests().size() == 1 && "waiting".equals(AgentController.get().phase()), 20 * 30);
			cmd(world, "damage @p 4 minecraft:mob_attack by @e[tag=hitter,limit=1]");
			context.waitTicks(15);
			cmd(world, "damage @p 4 minecraft:mob_attack by @e[tag=hitter,limit=1]");
			context.waitTicks(15);
			cmd(world, "damage @p 2 minecraft:fall");
			MockModel.awaitGoalEnd(context);
			cmd(world, "kill @e[tag=hitter]");
			cmd(world, "effect give @a instant_health 1 5");
			cmd(world, "difficulty peaceful");

			assertTrue(model.requests().size() == 2, "expected 2 model requests, got " + model.requests().size());
			// Found by role: game events, such as the Monster Hunter advancement the zombie hits can earn, may follow it.
			String report = lastToolResult(model.requests().get(1).messages());
			log("Damage report: " + report.replace("\n", " | "));
			assertTrue(report.contains("Took ") && report.matches("(?s).*zombie #\\d+ x2.*") && report.contains("fall x1") && report.contains("health now"),
				"the wait result should report the hits: " + report);
		}
	}

	private static void deathDuringGoal(ClientGameTestContext context, TestSingleplayerContext world, boolean keepInventory) {
		try (MockModel model = new MockModel()) {
			model.route(CHAT, (request, turn) -> MockModel.Reply.ok(turn == 1 ? toolCall("call_wait", "wait", "{\"seconds\":20}")
				: text("I died, stopping here."))).start();
			model.use(Provider.CUSTOM, "/v1", "mock-model");
			cmd(world, "gamerule keep_inventory " + keepInventory);
			cmd(world, "give @a cobblestone 5");
			context.waitTicks(5);
			MockModel.startGoal(context, "wait around");
			context.waitFor(mc -> model.requests().size() == 1 && "waiting".equals(AgentController.get().phase()), 20 * 30);
			cmd(world, "kill @a");
			MockModel.awaitGoalEnd(context);

			check(context, mc -> mc.player.isAlive() && !(mc.gui.screen() instanceof DeathScreen), "player should be respawned with no death screen");
			assertTrue(model.requests().size() == 2, "expected 2 model requests, got " + model.requests().size());
			JsonArray messages = model.requests().get(1).messages();
			String history = messages.toString();
			assertTrue(history.contains("Error: The player died"), "the interrupted wait should report the death: " + history);
			assertTrue(history.contains("Game event: You died at"), "the model should be told about the death: " + history);
			assertTrue(history.contains(keepInventory ? "keepInventory is on, so you kept" : "keepInventory is off: your items ("),
				"the death report should say whether items were kept: " + history);
			if (keepInventory) {
				check(context, mc -> count(mc, Items.COBBLESTONE) >= 5, "items should survive with keep_inventory on");
			}
			log("Death handling OK: " + messages.get(messages.size() - 1));
		}
	}

	/** An advancement completed while a goal runs reaches the model as a game event, with its title and description. */
	private static void advancementDuringGoal(ClientGameTestContext context, TestSingleplayerContext world) {
		try (MockModel model = new MockModel()) {
			model.route(CHAT, (request, turn) -> MockModel.Reply.ok(turn == 1 ? toolCall("call_wait", "wait", "{\"seconds\":3}")
				: text("Nice, an advancement."))).start();
			model.use(Provider.CUSTOM, "/v1", "mock-model");
			cmd(world, "advancement revoke @a only minecraft:story/mine_stone");
			MockModel.startGoal(context, "wait around");
			context.waitFor(mc -> model.requests().size() == 1 && "waiting".equals(AgentController.get().phase()), 20 * 30);
			cmd(world, "advancement grant @a only minecraft:story/mine_stone");
			MockModel.awaitGoalEnd(context);

			assertTrue(model.requests().size() == 2, "expected 2 model requests, got " + model.requests().size());
			String history = model.requests().get(1).messages().toString();
			assertTrue(history.contains("Game event: Advancement unlocked: Stone Age (Mine Stone with your new Pickaxe)."),
				"the model should be told about the advancement: " + history);
			log("Advancement event OK");
		}
	}

	/** ask_player pauses the goal until the player answers, by clicking an option or in their own words. */
	private static void questions(ClientGameTestContext context) {
		try (MockModel model = new MockModel()) {
			model.route(CHAT, (request, turn) -> MockModel.Reply.ok(switch (turn) {
				case 1 -> toolCall("call_ask_1", "ask_player",
					"{\"question\":\"Which ore should I mine first?\",\"options\":[\"Iron\",\"Coal\",\"Diamond\"]}");
				case 2 -> toolCall("call_ask_2", "ask_player", "{\"question\":\"What should I call the base?\"}");
				default -> text("Got it.");
			})).start();
			model.use(Provider.CUSTOM, "/v1", "mock-model");
			MockModel.startGoal(context, "mine something");
			context.waitFor(mc -> AgentController.get().isAsking(), 20 * 30);
			context.waitTicks(40);
			log("Question screenshot " + context.takeScreenshot("mineagent-question"));
			List<MockModel.Request> requests = model.requests();
			assertTrue(requests.size() == 1, "the goal should wait for the answer, made " + requests.size() + " requests");
			check(context, mc -> AgentController.get().phase().startsWith("waiting for your answer") && !(mc.player.input instanceof AgentInput),
				"the player gets control back while the agent waits for an answer");

			// The [2] chat button sends its command this way when clicked.
			context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("agent answer 2", null));
			context.waitFor(mc -> requests.size() == 2 && AgentController.get().isAsking(), 20 * 30);
			String chosen = lastToolResult(requests.get(1).messages());
			assertTrue(chosen.equals("The player chose option 2: Coal"), "a clicked option should reach the model by its text: " + chosen);

			// While a question is pending, /agent say answers it instead of queueing a message.
			context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("agent say call it Home", null));
			MockModel.awaitGoalEnd(context);
			assertTrue(requests.size() == 3, "expected 3 model requests, got " + requests.size());
			String typed = lastToolResult(requests.get(2).messages());
			assertTrue(typed.equals("The player answered: call it Home"), "a typed answer should reach the model as written: " + typed);
			String late = context.computeOnClient(mc -> AgentController.get().answer("too late"));
			assertTrue(late != null, "answering with no goal running should be refused");
			log("Questions OK: clicked option, typed answer");
		}
	}

	/** The content of the newest tool result in a chat request's history. */
	private static String lastToolResult(JsonArray messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			JsonObject message = messages.get(i).getAsJsonObject();
			if ("tool".equals(message.get("role").getAsString())) {
				return message.get("content").getAsString();
			}
		}
		throw new AssertionError("no tool result in " + messages);
	}

	/** The message {@code fromEnd} places from the end of a chat request's history: 1 is the last. */
	private static JsonObject last(JsonArray messages, int fromEnd) {
		return messages.get(messages.size() - fromEnd).getAsJsonObject();
	}
}
