package moe.tenshii.mineagent.agent;

import java.util.function.Consumer;
import moe.tenshii.mineagent.agent.llm.ModelCapabilities;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;

/**
 * Decides whether a goal offers the look tool, and remembers models that turn out not to read images.
 */
final class VisionPolicy {
	private final Provider provider;
	private final String baseUrl;
	private final String apiKey;
	private final String model;

	VisionPolicy(Provider provider, String baseUrl, String apiKey, String model) {
		this.provider = provider;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.model = model;
	}

	/**
	 * A model that already rejected images loses look; otherwise the player's screenshot setting wins, then what the
	 * provider says about the model, then the provider's default.
	 *
	 * @param tell shows the player a short note when the provider's answer overrides the default
	 */
	boolean offerLook(Consumer<String> tell) throws InterruptedException {
		AgentConfig config = AgentConfig.get();
		Boolean chosen = config.visionSetting(provider);
		if (config.isTextOnly(provider, model)) {
			if (Boolean.TRUE.equals(chosen)) {
				tell.accept("Screenshots (look) stay off: " + model + " rejected images before. Set Screenshots to On in /agent config to retry.");
			}
			return false;
		}
		if (chosen != null) {
			return chosen;
		}
		ModelCapabilities.Vision detected = ModelCapabilities.vision(provider, baseUrl, apiKey, model);
		if (detected == null) {
			return provider.visionByDefault();
		}
		if (detected.acceptsImages() != provider.visionByDefault()) {
			tell.accept("Screenshots (look) are " + (detected.acceptsImages() ? "on" : "off") + " for this goal: " + detected.source()
				+ " says " + model + (detected.acceptsImages() ? " reads images." : " does not read images."));
		}
		return detected.acceptsImages();
	}

	/** Called when the provider refused a request because of its screenshots. */
	void imagesRejected() {
		AgentConfig config = AgentConfig.get();
		config.setTextOnly(provider, model, true);
		config.save();
	}
}
