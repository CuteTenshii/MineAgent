package moe.tenshii.mineagent.ui;

import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Asks for the current provider's API key with masked input, so it never lands in chat or command history.
 */
public final class ApiKeyScreen extends Screen {
	private static final int FIELD_WIDTH = 300;
	private final @Nullable Screen parent;
	private final Provider provider;
	private @Nullable EditBox field;

	public ApiKeyScreen(@Nullable Screen parent, Provider provider) {
		super(Component.literal("MineAgent API key for " + provider.id()));
		this.parent = parent;
		this.provider = provider;
	}

	@Override
	protected void init() {
		int left = (width - FIELD_WIDTH) / 2;
		int top = height / 2 - 20;
		field = new EditBox(font, left, top, FIELD_WIDTH, 20, Component.literal("API key"));
		field.setMaxLength(512);
		field.setHint(Component.literal("API key"));
		field.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
		addRenderableWidget(field);
		setInitialFocus(field);
		int buttonWidth = (FIELD_WIDTH - 8) / 3;
		addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
			.bounds(left, top + 30, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Remove saved key"), button -> remove())
			.bounds(left + buttonWidth + 4, top + 30, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
			.bounds(left + (buttonWidth + 4) * 2, top + 30, buttonWidth, 20).build());
	}

	private void save() {
		if (field != null && !field.getValue().isBlank()) {
			AgentConfig config = AgentConfig.get();
			config.setApiKey(provider, field.getValue());
			config.save();
		}
		onClose();
	}

	private void remove() {
		AgentConfig config = AgentConfig.get();
		config.setApiKey(provider, "");
		config.save();
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int top = height / 2 - 20;
		graphics.text(font, title, (width - font.width(title)) / 2, top - 34, 0xFFFFFFFF);
		Component note = Component.literal("Paste the key (Ctrl+V). It is saved to config/mineagent.json.");
		graphics.text(font, note, (width - font.width(note)) / 2, top - 18, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
