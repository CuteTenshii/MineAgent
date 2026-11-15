package moe.tenshii.mineagent.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.agent.llm.Provider;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Persistent settings stored in {@code config/mineagent.json}. Keys, models and base URLs are kept per provider
 * so switching providers does not lose the others' settings.
 */
public final class AgentConfig {
	public static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");
	public static final int MAX_HUD_OFFSET = 1000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static AgentConfig instance;

	public String provider = Provider.ANTHROPIC.id();
	public Map<String, String> apiKeys = new LinkedHashMap<>();
	public Map<String, String> models = new LinkedHashMap<>();
	public Map<String, String> baseUrls = new LinkedHashMap<>();
	/** Per provider: offer the look tool, which sends screenshots. Unset means the provider's default. */
	public Map<String, Boolean> vision = new LinkedHashMap<>();
	/** "provider:model" entries whose API rejected a screenshot; look is not offered to them again. */
	public List<String> textOnlyModels = new ArrayList<>();
	/** Anthropic only: how much the model thinks per turn. */
	public String effort = "medium";
	/** Draw the path the agent is walking. */
	public boolean showPath = true;
	/** Post every tool call and its result in chat. */
	public boolean showToolCalls = false;
	/** Offer the model the run_command tool; the server still decides which commands the player may run. */
	public boolean allowCommands = false;
	/** Where the status box is drawn while a goal runs, so it can be moved away from minimaps and other HUDs. */
	public HudCorner hudCorner = HudCorner.TOP_LEFT;
	public int hudOffsetX = 0;
	public int hudOffsetY = 0;
	public int maxTurns = 200;
	/** Anthropic only; other providers use their own default output limit. */
	public int maxTokens = 16000;

	public static synchronized AgentConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mineagent.json");
	}

	private static AgentConfig load() {
		Path file = path();
		if (!Files.exists(file)) {
			return new AgentConfig();
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			AgentConfig loaded = GSON.fromJson(reader, AgentConfig.class);
			if (loaded == null) {
				return new AgentConfig();
			}
			loaded.sanitize();
			return loaded;
		} catch (IOException | JsonParseException e) {
			MineAgentClient.LOGGER.error("Could not read {}, using defaults", file, e);
			return new AgentConfig();
		}
	}

	private void sanitize() {
		if (provider == null || Provider.byId(provider).isEmpty()) {
			provider = Provider.ANTHROPIC.id();
		}
		apiKeys = apiKeys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(apiKeys);
		models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
		baseUrls = baseUrls == null ? new LinkedHashMap<>() : new LinkedHashMap<>(baseUrls);
		vision = vision == null ? new LinkedHashMap<>() : new LinkedHashMap<>(vision);
		vision.values().removeIf(Objects::isNull);
		textOnlyModels = textOnlyModels == null ? new ArrayList<>() : new ArrayList<>(textOnlyModels);
		textOnlyModels.removeIf(Objects::isNull);
		if (effort == null || !EFFORT_LEVELS.contains(effort.toLowerCase(Locale.ROOT))) {
			effort = "medium";
		}
		effort = effort.toLowerCase(Locale.ROOT);
		if (hudCorner == null) {
			hudCorner = HudCorner.TOP_LEFT;
		}
		hudOffsetX = Math.clamp(hudOffsetX, 0, MAX_HUD_OFFSET);
		hudOffsetY = Math.clamp(hudOffsetY, 0, MAX_HUD_OFFSET);
		maxTurns = Math.clamp(maxTurns, 1, 2000);
		maxTokens = Math.clamp(maxTokens, 1024, 64000);
	}

	public synchronized void save() {
		sanitize();
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			Path temp = Files.createTempFile(file.getParent(), "mineagent", ".json.tmp");
			restrictToOwner(temp);
			try (Writer writer = Files.newBufferedWriter(temp)) {
				GSON.toJson(this, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			MineAgentClient.LOGGER.error("Could not save {}", file, e);
		}
	}

	// The file holds API keys, so other local users should not be able to read it.
	private static void restrictToOwner(Path file) {
		try {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Non-POSIX file systems (Windows) rely on the user profile's ACLs instead.
		}
	}

	public Provider provider() {
		return Provider.byId(provider).orElse(Provider.ANTHROPIC);
	}

	/** The saved key for the provider, falling back to its environment variable. */
	public String apiKey(Provider target) {
		String saved = apiKeys.get(target.id());
		if (saved != null && !saved.isBlank()) {
			return saved.strip();
		}
		if (target.envVar().isEmpty()) {
			return "";
		}
		String env = System.getenv(target.envVar());
		return env == null ? "" : env.strip();
	}

	public String keySource(Provider target) {
		String saved = apiKeys.get(target.id());
		if (saved != null && !saved.isBlank()) {
			return "saved in config";
		}
		if (!target.envVar().isEmpty() && System.getenv(target.envVar()) != null && !System.getenv(target.envVar()).isBlank()) {
			return target.envVar() + " environment variable";
		}
		return target.keyRequired() ? "not set" : "not set (optional)";
	}

	public void setApiKey(Provider target, String key) {
		if (key == null || key.isBlank()) {
			apiKeys.remove(target.id());
		} else {
			apiKeys.put(target.id(), key.strip());
		}
	}

	public String model(Provider target) {
		String chosen = models.get(target.id());
		return chosen != null && !chosen.isBlank() ? chosen.strip() : target.defaultModel();
	}

	/** The player's screenshot setting for the provider, or null when they made none. */
	public @Nullable Boolean visionSetting(Provider target) {
		return vision.get(target.id());
	}

	public boolean isTextOnly(Provider target, String model) {
		return textOnlyModels.contains(modelKey(target, model));
	}

	/** @param textOnly whether the model rejected images and should not be offered look */
	public synchronized void setTextOnly(Provider target, String model, boolean textOnly) {
		String key = modelKey(target, model);
		textOnlyModels.remove(key);
		if (textOnly) {
			textOnlyModels.add(key);
		}
	}

	private static String modelKey(Provider target, String model) {
		return target.id() + ":" + model;
	}

	public String baseUrl(Provider target) {
		String chosen = baseUrls.get(target.id());
		return chosen != null && !chosen.isBlank() ? chosen.strip() : target.defaultBaseUrl();
	}
}
