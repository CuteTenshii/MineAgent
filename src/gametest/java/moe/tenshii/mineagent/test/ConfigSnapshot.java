package moe.tenshii.mineagent.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.config.HudCorner;

/**
 * Puts every agent setting a test may change back as it was, so tests cannot leak providers, keys or toggles.
 */
final class ConfigSnapshot implements AutoCloseable {
	private final String provider;
	private final Map<String, String> apiKeys;
	private final Map<String, String> models;
	private final Map<String, String> baseUrls;
	private final Map<String, Boolean> vision;
	private final List<String> textOnlyModels;
	private final String effort;
	private final boolean showPath;
	private final boolean showToolCalls;
	private final boolean allowCommands;
	private final boolean allowWiki;
	private final int maxTurns;
	private final int maxTokens;
	private final HudCorner hudCorner;
	private final int hudOffsetX;
	private final int hudOffsetY;

	ConfigSnapshot() {
		AgentConfig config = AgentConfig.get();
		provider = config.provider;
		apiKeys = new LinkedHashMap<>(config.apiKeys);
		models = new LinkedHashMap<>(config.models);
		baseUrls = new LinkedHashMap<>(config.baseUrls);
		vision = new LinkedHashMap<>(config.vision);
		textOnlyModels = new ArrayList<>(config.textOnlyModels);
		effort = config.effort;
		showPath = config.showPath;
		showToolCalls = config.showToolCalls;
		allowCommands = config.allowCommands;
		allowWiki = config.allowWiki;
		maxTurns = config.maxTurns;
		maxTokens = config.maxTokens;
		hudCorner = config.hudCorner;
		hudOffsetX = config.hudOffsetX;
		hudOffsetY = config.hudOffsetY;
	}

	@Override
	public void close() {
		AgentConfig config = AgentConfig.get();
		config.provider = provider;
		config.apiKeys = new LinkedHashMap<>(apiKeys);
		config.models = new LinkedHashMap<>(models);
		config.baseUrls = new LinkedHashMap<>(baseUrls);
		config.vision = new LinkedHashMap<>(vision);
		config.textOnlyModels = new ArrayList<>(textOnlyModels);
		config.effort = effort;
		config.showPath = showPath;
		config.showToolCalls = showToolCalls;
		config.allowCommands = allowCommands;
		config.allowWiki = allowWiki;
		config.maxTurns = maxTurns;
		config.maxTokens = maxTokens;
		config.hudCorner = hudCorner;
		config.hudOffsetX = hudOffsetX;
		config.hudOffsetY = hudOffsetY;
		// Image rejection saves the config file; write the restored values back so the dev run's file is unchanged.
		config.save();
	}
}
