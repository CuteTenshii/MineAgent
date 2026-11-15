package moe.tenshii.mineagent.test;

import static moe.tenshii.mineagent.test.GameTestKit.assertTrue;
import static moe.tenshii.mineagent.test.GameTestKit.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import moe.tenshii.mineagent.ui.ChatMarkdown;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * How the model's Markdown turns into chat styles. Needs no world.
 */
public final class ChatMarkdownGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		MutableComponent rendered = ChatMarkdown.render(
			"**Done**: crafted oak_planks, *then* __rested__ ~~briefly~~; 2 * 3 = `six`\n- one [docs](https://example.com)", Style.EMPTY);
		List<Component> parts = new ArrayList<>();
		rendered.visit((style, text) -> {
			parts.add(Component.literal(text).withStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		assertTrue(rendered.getString().equals("Done: crafted oak_planks, then rested briefly; 2 * 3 = six\n• one docs"),
			"markers should be removed and ids kept: " + rendered.getString());
		assertTrue(styleOf(parts, "Done").isBold() && styleOf(parts, "then").isItalic() && styleOf(parts, "rested").isUnderlined()
			&& styleOf(parts, "briefly").isStrikethrough() && styleOf(parts, "six").getColor() != null
			&& styleOf(parts, "docs").getClickEvent() instanceof ClickEvent.OpenUrl, "each marker should set its style: " + parts);
		assertTrue(!styleOf(parts, ": crafted oak_planks, ").isItalic(), "underscores inside ids are not emphasis");
		log("Markdown OK: " + rendered.getString().replace("\n", " | "));
	}

	private static Style styleOf(List<Component> parts, String text) {
		return parts.stream().filter(part -> part.getString().equals(text)).findFirst()
			.orElseThrow(() -> new AssertionError("no chat part '" + text + "' in " + parts)).getStyle();
	}
}
