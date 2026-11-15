package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.log;
import static moe.tenshii.mineagent.test.GameTestKit.run;
import static moe.tenshii.mineagent.test.MockModel.text;
import static moe.tenshii.mineagent.test.MockModel.toolCall;

import java.util.List;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.task.LookTask;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Screenshots: capturing one with look, deciding per provider and model whether to offer look, and recovering when a
 * model rejects an image.
 */
public final class VisionGameTest implements FabricClientGameTest {
	private static final String CHAT = "/v1/chat/completions";

	@Override
	public void runTest(ClientGameTestContext context) {
		GameTestKit.inWorld(context, (ctx, world, start) -> {
			screenshot(ctx);
			detection(ctx);
			rejectedImage(ctx);
		});
	}

	private static void screenshot(ClientGameTestContext context) throws TaskFailure {
		LookTask look = new LookTask(null);
		String caption = run(context, look);
		String image = look.encodedImage();
		boolean hudBack = context.computeOnClient(mc -> !mc.gui.hud.isHidden());
		log("look -> " + caption + " | " + image.length() + " base64 chars");
		assertTrue(caption.startsWith("Screenshot of the player's view"), "look should describe the view: " + caption);
		assertTrue(image.startsWith("/9j/") && image.length() > 1000, "look should produce a JPEG");
		assertTrue(hudBack, "the HUD must be shown again after the screenshot");
	}

	/** A provider and model whose capability answer decides whether look is offered. */
	private record Case(Provider provider, String basePath, String model, boolean offered) {
	}

	private static void detection(ClientGameTestContext context) {
		try (MockModel model = new MockModel()) {
			model.route("/api/v1/models", "{\"data\":["
					+ "{\"id\":\"acme/text-only\",\"architecture\":{\"input_modalities\":[\"text\"],\"output_modalities\":[\"text\"]}},"
					+ "{\"id\":\"acme/eyes\",\"architecture\":{\"modality\":\"text+image->text\"}}]}")
				.route("/api/show", "{\"capabilities\":[\"completion\",\"vision\",\"tools\"]}")
				.route("/v1/models/claude-text", "{\"id\":\"claude-text\",\"type\":\"model\",\"capabilities\":{\"image_input\":{\"supported\":false}}}")
				.route("/v1/models/mistral-eyes", "{\"id\":\"mistral-eyes\",\"capabilities\":{\"completion_chat\":true,\"vision\":true}}")
				.route("/v1/language-models/grok-eyes", "{\"id\":\"grok-eyes\",\"input_modalities\":[\"text\",\"image\"]}")
				.route("/api/v1/chat/completions", text("Hi."))
				.route(CHAT, text("Hi."))
				.route("/v1/messages", MockModel.anthropic("{\"type\":\"text\",\"text\":\"Hi.\"}", "end_turn"))
				.start();
			List<Case> cases = List.of(
				new Case(Provider.OPENROUTER, "/api/v1", "acme/text-only", false),
				new Case(Provider.OPENROUTER, "/api/v1", "acme/eyes", true),
				new Case(Provider.OLLAMA, "/v1", "llava", true),
				new Case(Provider.DEEPSEEK, "/v1", "deepseek-flash", true),
				new Case(Provider.DEEPSEEK, "/v1", "deepseek-v4-pro", false),
				new Case(Provider.ANTHROPIC, "", "claude-text", false),
				new Case(Provider.MISTRAL, "/v1", "mistral-eyes", true),
				new Case(Provider.XAI, "/v1", "grok-eyes", true));
			for (Case expected : cases) {
				model.use(expected.provider(), expected.basePath(), expected.model());
				assertTrue(model.goalOffersLook(context) == expected.offered(), expected.provider().id() + " " + expected.model() + " should "
					+ (expected.offered() ? "" : "not ") + "be offered look");
			}
			assertTrue(model.requests().stream().anyMatch(request -> request.path().equals("/api/show") && request.body().contains("\"model\":\"llava\"")),
				"Ollama should be asked about the model by name");
			log("Vision detection OK for " + cases.size() + " provider and model combinations");
		}
	}

	private static void rejectedImage(ClientGameTestContext context) {
		try (MockModel model = new MockModel()) {
			model.route(CHAT, (request, turn) -> {
				if (request.body().contains("image_url")) {
					return new MockModel.Reply(400, "{\"error\":{\"message\":\"This model does not support image input.\"}}");
				}
				return MockModel.Reply.ok(turn == 1 ? toolCall("call_look", "look", "{}") : text("I cannot see, but I can carry on."));
			}).start();
			model.use(Provider.CUSTOM, "/v1", "blind-model");
			AgentConfig config = AgentConfig.get();
			config.vision.put(Provider.CUSTOM.id(), true);
			assertTrue(model.goalOffersLook(context), "look should be offered while vision is on");

			List<MockModel.Request> requests = model.chatRequests();
			assertTrue(requests.size() == 3, "expected the look call, the rejected request and its retry, got " + requests.size());
			String retry = requests.get(2).body();
			assertTrue(!retry.contains("image_url") && retry.contains("does not accept images"),
				"the retry should replace the screenshot with a note: " + retry.substring(Math.max(0, retry.length() - 600)));
			assertTrue(config.isTextOnly(Provider.CUSTOM, "blind-model"), "the model should be remembered as text-only");
			assertTrue(!model.goalOffersLook(context), "a model that rejected images must not be offered look again");
			log("Image rejection OK: retried without the screenshot and remembered blind-model as text-only");
		}
	}
}
