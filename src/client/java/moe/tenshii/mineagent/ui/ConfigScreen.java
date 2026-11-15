package moe.tenshii.mineagent.ui;

import java.net.URI;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import moe.tenshii.mineagent.agent.llm.ModelCapabilities;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.config.HudCorner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * MineAgent's settings, built with Cloth Config. Opened from Mod Menu or with /agent config; changes apply when the
 * screen is saved, and most take effect from the next goal.
 */
public final class ConfigScreen {
	private enum Vision {
		AUTO("Auto"), ON("On"), OFF("Off");

		private final String label;

		Vision(String label) {
			this.label = label;
		}
	}

	private ConfigScreen() {
	}

	public static Screen create(@Nullable Screen parent) {
		AgentConfig config = AgentConfig.get();
		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.literal("MineAgent"))
			.setSavingRunnable(config::save);
		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
		general.addEntry(entries.startSelector(Component.literal("Provider"), Provider.values(), config.provider())
			.setNameProvider(provider -> Component.literal(provider.id()))
			.setDefaultValue(Provider.ANTHROPIC)
			.setTooltip(Component.literal("The AI service the agent uses, from the next goal on. Its model, address and screenshots are set "
				+ "in the Providers tab."))
			.setSaveConsumer(provider -> config.provider = provider.id())
			.build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Allow server commands"), config.allowCommands)
			.setDefaultValue(false)
			.setTooltip(Component.literal("Offer the model run_command, limited to the commands the server lets this player use. "
				+ "Turning it on applies from the next goal; turning it off applies at once."))
			.setSaveConsumer(allow -> config.allowCommands = allow)
			.build());
		general.addEntry(entries.startIntField(Component.literal("Turns per goal"), config.maxTurns)
			.setDefaultValue(200)
			.setMin(1)
			.setMax(2000)
			.setTooltip(Component.literal("Model turns before a goal pauses and asks you to continue or stop."))
			.setSaveConsumer(turns -> config.maxTurns = turns)
			.build());
		general.addEntry(entries.startSelector(Component.literal("Thinking effort (Anthropic)"),
				AgentConfig.EFFORT_LEVELS.toArray(String[]::new), config.effort)
			.setDefaultValue("medium")
			.setTooltip(Component.literal("How much Anthropic models think per turn. Other providers ignore it."))
			.setSaveConsumer(level -> config.effort = level)
			.build());
		general.addEntry(entries.startIntField(Component.literal("Max output tokens (Anthropic)"), config.maxTokens)
			.setDefaultValue(16000)
			.setMin(1024)
			.setMax(64000)
			.setTooltip(Component.literal("Longest reply per turn for Anthropic models. Other providers use their own limit."))
			.setSaveConsumer(tokens -> config.maxTokens = tokens)
			.build());

		ConfigCategory providers = builder.getOrCreateCategory(Component.literal("Providers"));
		for (Provider provider : Provider.values()) {
			providers.addEntry(providerSettings(entries, config, provider).build());
		}

		ConfigCategory display = builder.getOrCreateCategory(Component.literal("Display"));
		display.addEntry(entries.startEnumSelector(Component.literal("Status box corner"), HudCorner.class, config.hudCorner)
			.setEnumNameProvider(corner -> Component.literal(((HudCorner) corner).label()))
			.setDefaultValue(HudCorner.TOP_LEFT)
			.setTooltip(Component.literal("Where the goal, current action and token count are shown while the agent works. Move it if "
				+ "it covers a minimap."))
			.setSaveConsumer(corner -> config.hudCorner = corner)
			.build());
		display.addEntry(entries.startIntField(Component.literal("Status box offset across"), config.hudOffsetX)
			.setDefaultValue(0)
			.setMin(0)
			.setMax(AgentConfig.MAX_HUD_OFFSET)
			.setTooltip(Component.literal("Pixels (GUI scale) from the corner's side toward the center."))
			.setSaveConsumer(offset -> config.hudOffsetX = offset)
			.build());
		display.addEntry(entries.startIntField(Component.literal("Status box offset down/up"), config.hudOffsetY)
			.setDefaultValue(0)
			.setMin(0)
			.setMax(AgentConfig.MAX_HUD_OFFSET)
			.setTooltip(Component.literal("Pixels (GUI scale) from the corner's top or bottom edge toward the center."))
			.setSaveConsumer(offset -> config.hudOffsetY = offset)
			.build());
		display.addEntry(entries.startBooleanToggle(Component.literal("Show path"), config.showPath)
			.setDefaultValue(true)
			.setTooltip(Component.literal("Draw the route the agent is walking or flying."))
			.setSaveConsumer(show -> config.showPath = show)
			.build());
		display.addEntry(entries.startBooleanToggle(Component.literal("Show tool calls in chat"), config.showToolCalls)
			.setDefaultValue(false)
			.setTooltip(Component.literal("Post every action the agent takes and its result in chat."))
			.setSaveConsumer(show -> config.showToolCalls = show)
			.build());
		return builder.build();
	}

	private static SubCategoryBuilder providerSettings(ConfigEntryBuilder entries, AgentConfig config, Provider provider) {
		SubCategoryBuilder section = entries.startSubCategory(Component.literal(provider.id()))
			.setExpanded(provider == config.provider());
		section.add(entries.startTextDescription(Component.literal("API key: " + config.keySource(provider)
				+ ". Enter or change it with /agent key while this provider is selected; the key field there hides what you type.")
				.withStyle(ChatFormatting.GRAY))
			.build());
		section.add(entries.startStrField(Component.literal("Model"), config.models.getOrDefault(provider.id(), ""))
			.setDefaultValue("")
			.setTooltip(Component.literal(provider.defaultModel().isEmpty()
				? "The model id; this provider has no default, so one is needed."
				: "The model id. Leave empty for the default, " + provider.defaultModel() + "."))
			.setErrorSupplier(model -> model.strip().chars().anyMatch(Character::isWhitespace)
				? Optional.of(Component.literal("A model id has no spaces, e.g. claude-opus-5-5 or gpt-5."))
				: Optional.empty())
			.setSaveConsumer(model -> {
				if (model.isBlank()) {
					config.models.remove(provider.id());
				} else {
					config.models.put(provider.id(), model.strip());
				}
			})
			.build());
		section.add(entries.startStrField(Component.literal("Base URL"), config.baseUrls.getOrDefault(provider.id(), ""))
			.setDefaultValue("")
			.setTooltip(Component.literal(provider.defaultBaseUrl().isEmpty()
				? "The API address, e.g. http://localhost:8000/v1. This provider has no default, so one is needed."
				: "The API address. Leave empty for the default, " + provider.defaultBaseUrl() + "."))
			.setErrorSupplier(url -> url.isBlank() || isHttpUrl(url.strip()) ? Optional.empty()
				: Optional.of(Component.literal("Give a full http(s) URL, e.g. http://localhost:11434/v1")))
			.setSaveConsumer(url -> {
				if (url.isBlank()) {
					config.baseUrls.remove(provider.id());
				} else {
					config.baseUrls.put(provider.id(), url.strip());
				}
			})
			.build());
		Boolean chosen = config.visionSetting(provider);
		Vision current = chosen == null ? Vision.AUTO : chosen ? Vision.ON : Vision.OFF;
		section.add(entries.startEnumSelector(Component.literal("Screenshots (look)"), Vision.class, current)
			.setEnumNameProvider(vision -> Component.literal(((Vision) vision).label))
			.setDefaultValue(Vision.AUTO)
			.setTooltip(Component.literal(visionTooltip(config, provider)))
			.setSaveConsumer(vision -> {
				switch (vision) {
					case AUTO -> config.vision.remove(provider.id());
					case ON -> {
						config.vision.put(provider.id(), true);
						// Choosing On is how a model that once rejected images gets tried again.
						config.setTextOnly(provider, config.model(provider), false);
					}
					case OFF -> config.vision.put(provider.id(), false);
				}
			})
			.build());
		return section;
	}

	private static String visionTooltip(AgentConfig config, Provider provider) {
		String text = "Let the model see the game with the look tool. The model must accept images. Auto "
			+ (ModelCapabilities.canCheck(provider) ? "checks whether the model reads images, and otherwise is " : "is ")
			+ (provider.visionByDefault() ? "on" : "off") + " for " + provider.id() + ".";
		String model = config.model(provider);
		if (!model.isEmpty() && config.isTextOnly(provider, model)) {
			text += " " + model + " rejected a screenshot before, so it stays off until you choose On.";
		}
		return text;
	}

	private static boolean isHttpUrl(String url) {
		try {
			URI parsed = URI.create(url);
			return parsed.getScheme() != null && parsed.getScheme().matches("https?") && parsed.getHost() != null;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
