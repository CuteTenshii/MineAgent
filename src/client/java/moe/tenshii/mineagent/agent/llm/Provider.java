package moe.tenshii.mineagent.agent.llm;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Supported model providers. Everything except Anthropic speaks the OpenAI Chat Completions API.
 * Default models are starting points; any model id the provider serves can be set in /agent config.
 */
public enum Provider {
	ANTHROPIC("anthropic", Protocol.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", "ANTHROPIC_API_KEY", true),
	OPENAI("openai", Protocol.OPENAI, "https://api.openai.com/v1", "gpt-5", "OPENAI_API_KEY", true),
	DEEPSEEK("deepseek", Protocol.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat", "DEEPSEEK_API_KEY", true),
	GEMINI("gemini", Protocol.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-pro", "GEMINI_API_KEY", true),
	OPENROUTER("openrouter", Protocol.OPENAI, "https://openrouter.ai/api/v1", "", "OPENROUTER_API_KEY", true),
	GROQ("groq", Protocol.OPENAI, "https://api.groq.com/openai/v1", "", "GROQ_API_KEY", true),
	MISTRAL("mistral", Protocol.OPENAI, "https://api.mistral.ai/v1", "mistral-large-latest", "MISTRAL_API_KEY", true),
	XAI("xai", Protocol.OPENAI, "https://api.x.ai/v1", "", "XAI_API_KEY", true),
	OLLAMA("ollama", Protocol.OPENAI, "http://localhost:11434/v1", "", "", false),
	CUSTOM("custom", Protocol.OPENAI, "", "", "", false);

	public enum Protocol {
		ANTHROPIC,
		OPENAI
	}

	private final String id;
	private final Protocol protocol;
	private final String defaultBaseUrl;
	private final String defaultModel;
	private final String envVar;
	private final boolean keyRequired;

	Provider(String id, Protocol protocol, String defaultBaseUrl, String defaultModel, String envVar, boolean keyRequired) {
		this.id = id;
		this.protocol = protocol;
		this.defaultBaseUrl = defaultBaseUrl;
		this.defaultModel = defaultModel;
		this.envVar = envVar;
		this.keyRequired = keyRequired;
	}

	public String id() {
		return id;
	}

	public Protocol protocol() {
		return protocol;
	}

	public String defaultBaseUrl() {
		return defaultBaseUrl;
	}

	public String defaultModel() {
		return defaultModel;
	}

	/** Environment variable read when no key is saved in the config; empty if none. */
	public String envVar() {
		return envVar;
	}

	/** Whether the default models read images; providers serving many text-only models start with look turned off. */
	public boolean visionByDefault() {
		return this == ANTHROPIC || this == OPENAI || this == GEMINI || this == OPENROUTER;
	}

	public boolean keyRequired() {
		return keyRequired;
	}

	public static Optional<Provider> byId(String id) {
		String wanted = id.strip().toLowerCase(Locale.ROOT);
		return Arrays.stream(values()).filter(provider -> provider.id.equals(wanted)).findFirst();
	}

	public static List<String> ids() {
		return Arrays.stream(values()).map(Provider::id).toList();
	}
}
