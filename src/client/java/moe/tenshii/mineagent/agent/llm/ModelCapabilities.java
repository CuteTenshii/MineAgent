package moe.tenshii.mineagent.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import moe.tenshii.mineagent.MineAgentClient;
import org.jspecify.annotations.Nullable;

/**
 * Asks a provider whether a model reads images, for the providers that publish it.
 */
public final class ModelCapabilities {
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final String INPUT_MODALITIES = "input_modalities";
	private static final String CAPABILITIES = "capabilities";
	private static final String OPENROUTER_SOURCE = "OpenRouter's model list";
	// DeepSeek has no capability endpoint; its pricing page lists vision per model (api-docs.deepseek.com, November 2026).
	// The retired v4-flash names are still accepted and served by the current Flash model, which reads images.
	private static final Set<String> DEEPSEEK_VISION = Set.of("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp");

	/** The answer and where it came from, for telling the player. */
	public record Vision(boolean acceptsImages, String source) {
	}

	private ModelCapabilities() {
	}

	/** Whether {@link #vision} can say anything about this provider's models. */
	public static boolean canCheck(Provider provider) {
		return switch (provider) {
			case ANTHROPIC, OPENROUTER, OLLAMA, DEEPSEEK, MISTRAL, XAI -> true;
			case OPENAI, GEMINI, GROQ, CUSTOM -> false;
		};
	}

	/**
	 * @return the answer, or null when the provider does not say or could not be asked
	 */
	public static @Nullable Vision vision(Provider provider, String baseUrl, String apiKey, String model) throws InterruptedException {
		try (JsonHttp http = new JsonHttp(provider.id())) {
			return switch (provider) {
				case OPENROUTER -> openRouter(http, baseUrl, apiKey, model);
				case OLLAMA -> ollama(http, baseUrl, model);
				case DEEPSEEK -> deepSeek(model);
				case ANTHROPIC -> anthropic(http, baseUrl, apiKey, model);
				case MISTRAL -> mistral(http, baseUrl, apiKey, model);
				case XAI -> xai(http, baseUrl, apiKey, model);
				default -> null;
			};
		} catch (ApiFailure | RuntimeException e) {
			MineAgentClient.LOGGER.info("Could not check whether {} reads images: {}", model, e.getMessage());
			return null;
		}
	}

	/** OpenRouter's model list gives each model's input modalities. */
	private static @Nullable Vision openRouter(JsonHttp http, String baseUrl, String apiKey, String model)
		throws ApiFailure, InterruptedException {
		URI endpoint = JsonHttp.endpoint("OpenRouter", baseUrl, "/models");
		JsonObject architecture = architectureOf(http.get(endpoint, bearer(apiKey), TIMEOUT), model);
		if (architecture == null) {
			return null;
		}
		JsonElement inputs = architecture.get(INPUT_MODALITIES);
		if (inputs != null && inputs.isJsonArray()) {
			return new Vision(contains(inputs.getAsJsonArray(), "image"), OPENROUTER_SOURCE);
		}
		String modality = OpenAiBackend.stringOr(architecture, "modality", "");
		int arrow = modality.indexOf("->");
		return arrow < 0 ? null : new Vision(modality.substring(0, arrow).contains("image"), OPENROUTER_SOURCE);
	}

	private static @Nullable JsonObject architectureOf(JsonObject models, String model) {
		JsonElement data = models.get("data");
		if (data == null || !data.isJsonArray()) {
			return null;
		}
		for (JsonElement element : data.getAsJsonArray()) {
			if (element.isJsonObject() && model.equals(OpenAiBackend.stringOr(element.getAsJsonObject(), "id", ""))) {
				JsonElement architecture = element.getAsJsonObject().get("architecture");
				return architecture != null && architecture.isJsonObject() ? architecture.getAsJsonObject() : null;
			}
		}
		return null;
	}

	/** The Models API reports capabilities.image_input.supported for each Claude model. */
	private static @Nullable Vision anthropic(JsonHttp http, String baseUrl, String apiKey, String model) throws ApiFailure, InterruptedException {
		URI endpoint = JsonHttp.endpoint("Anthropic", baseUrl, "/v1/models/" + pathSegment(model));
		JsonObject info = http.get(endpoint, Map.of("x-api-key", apiKey, "anthropic-version", AnthropicBackend.API_VERSION), TIMEOUT);
		JsonObject capabilities = object(info, CAPABILITIES);
		JsonObject imageInput = capabilities == null ? null : object(capabilities, "image_input");
		JsonElement supported = imageInput == null ? null : imageInput.get("supported");
		return supported != null && supported.isJsonPrimitive() ? new Vision(supported.getAsBoolean(), "Anthropic's Models API") : null;
	}

	/** Mistral's model card has a boolean capabilities.vision. */
	private static @Nullable Vision mistral(JsonHttp http, String baseUrl, String apiKey, String model) throws ApiFailure, InterruptedException {
		URI endpoint = JsonHttp.endpoint("Mistral", baseUrl, "/models/" + pathSegment(model));
		JsonObject capabilities = object(http.get(endpoint, bearer(apiKey), TIMEOUT), CAPABILITIES);
		JsonElement vision = capabilities == null ? null : capabilities.get("vision");
		return vision != null && vision.isJsonPrimitive() ? new Vision(vision.getAsBoolean(), "Mistral's model card") : null;
	}

	/** xAI lists input modalities on its language-models endpoint, not on /models. */
	private static @Nullable Vision xai(JsonHttp http, String baseUrl, String apiKey, String model) throws ApiFailure, InterruptedException {
		URI endpoint = JsonHttp.endpoint("xAI", baseUrl, "/language-models/" + pathSegment(model));
		JsonElement inputs = http.get(endpoint, bearer(apiKey), TIMEOUT).get(INPUT_MODALITIES);
		return inputs != null && inputs.isJsonArray() ? new Vision(contains(inputs.getAsJsonArray(), "image"), "xAI's model list") : null;
	}

	private static Map<String, String> bearer(String apiKey) {
		return apiKey.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
	}

	private static @Nullable JsonObject object(JsonObject parent, String key) {
		JsonElement child = parent.get(key);
		return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
	}

	private static String pathSegment(String model) {
		return URLEncoder.encode(model.strip(), StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** Any other DeepSeek model falls back to the provider default, which is off. */
	private static @Nullable Vision deepSeek(String model) {
		return DEEPSEEK_VISION.contains(model.strip().toLowerCase(Locale.ROOT)) ? new Vision(true, "DeepSeek's model documentation") : null;
	}

	/** Ollama's native /api/show lists capabilities such as "vision"; older versions leave them out. */
	private static @Nullable Vision ollama(JsonHttp http, String baseUrl, String model) throws ApiFailure, InterruptedException {
		String root = baseUrl.strip();
		while (root.endsWith("/")) {
			root = root.substring(0, root.length() - 1);
		}
		// The OpenAI-compatible API lives under /v1; the native API that lists capabilities is at the root.
		if (root.endsWith("/v1")) {
			root = root.substring(0, root.length() - "/v1".length());
		}
		URI endpoint = JsonHttp.endpoint("Ollama", root, "/api/show");
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		JsonObject response = http.post(endpoint, Map.of(), body.toString(), TIMEOUT);
		JsonElement capabilities = response.get(CAPABILITIES);
		if (capabilities == null || !capabilities.isJsonArray()) {
			return null;
		}
		return new Vision(contains(capabilities.getAsJsonArray(), "vision"), "Ollama's model details");
	}

	private static boolean contains(JsonArray values, String wanted) {
		for (JsonElement value : values) {
			if (value.isJsonPrimitive() && wanted.equals(value.getAsString())) {
				return true;
			}
		}
		return false;
	}
}
