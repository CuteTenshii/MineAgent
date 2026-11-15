package moe.tenshii.mineagent.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Turns the Markdown models write into styled chat text. Underscores and asterisks only count as emphasis at word
 * boundaries, so ids like oak_planks and sums like 2 * 3 stay as written. Follows Discord's convention that
 * {@code __text__} underlines, since chat can show underline and plain Markdown has no syntax for it.
 */
public final class ChatMarkdown {
	private static final ChatFormatting CODE_COLOR = ChatFormatting.GOLD;
	private static final ChatFormatting QUOTE_COLOR = ChatFormatting.GRAY;
	private static final ChatFormatting LINK_COLOR = ChatFormatting.BLUE;
	private static final String FENCE = "```";
	// Longest delimiters first, so ** is not read as two single asterisks.
	private static final List<Delimiter> DELIMITERS = List.of(
		new Delimiter("**", false, style -> style.withBold(true)),
		new Delimiter("__", true, style -> style.withUnderlined(true)),
		new Delimiter("~~", false, style -> style.withStrikethrough(true)),
		new Delimiter("*", false, style -> style.withItalic(true)),
		new Delimiter("_", true, style -> style.withItalic(true)));

	private record Delimiter(String marker, boolean wordBound, UnaryOperator<Style> apply) {
	}

	private ChatMarkdown() {
	}

	public static MutableComponent render(String text, Style base) {
		MutableComponent out = Component.empty();
		boolean inFence = false;
		boolean first = true;
		for (String line : text.split("\r?\n", -1)) {
			if (line.strip().startsWith(FENCE)) {
				inFence = !inFence;
				continue;
			}
			if (!first) {
				out.append("\n");
			}
			first = false;
			if (inFence) {
				out.append(Component.literal(line).withStyle(base.applyFormat(CODE_COLOR)));
				continue;
			}
			renderLine(out, line, base);
		}
		return out;
	}

	private static void renderLine(MutableComponent out, String line, Style base) {
		String trimmed = line.stripLeading();
		String indent = line.substring(0, line.length() - trimmed.length());
		int hashes = 0;
		while (hashes < trimmed.length() && hashes < 6 && trimmed.charAt(hashes) == '#') {
			hashes++;
		}
		if (hashes > 0 && hashes < trimmed.length() && trimmed.charAt(hashes) == ' ') {
			inline(out, trimmed.substring(hashes + 1).strip(), base.withBold(true));
			return;
		}
		if (trimmed.length() > 1 && "-*+".indexOf(trimmed.charAt(0)) >= 0 && trimmed.charAt(1) == ' ') {
			out.append(Component.literal(indent + "• ").withStyle(base));
			inline(out, trimmed.substring(2), base);
			return;
		}
		if (trimmed.startsWith("> ") || trimmed.equals(">")) {
			out.append(Component.literal(indent + "| ").withStyle(base.applyFormat(QUOTE_COLOR)));
			inline(out, trimmed.length() > 2 ? trimmed.substring(2) : "", base.applyFormat(QUOTE_COLOR).withItalic(true));
			return;
		}
		inline(out, line, base);
	}

	private static void inline(MutableComponent out, String text, Style style) {
		StringBuilder plain = new StringBuilder();
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length() && isPunctuation(text.charAt(i + 1))) {
				plain.append(text.charAt(i + 1));
				i += 2;
				continue;
			}
			if (c == '`') {
				int close = text.indexOf('`', i + 1);
				if (close > i + 1) {
					flush(out, plain, style);
					out.append(Component.literal(text.substring(i + 1, close)).withStyle(style.applyFormat(CODE_COLOR)));
					i = close + 1;
					continue;
				}
			}
			if (c == '[') {
				int consumed = link(out, plain, text, i, style);
				if (consumed > 0) {
					i += consumed;
					continue;
				}
			}
			Delimiter opened = opening(text, i);
			if (opened != null) {
				int close = findClose(text, i + opened.marker().length(), opened);
				if (close >= 0) {
					flush(out, plain, style);
					inline(out, text.substring(i + opened.marker().length(), close), opened.apply().apply(style));
					i = close + opened.marker().length();
					continue;
				}
			}
			plain.append(c);
			i++;
		}
		flush(out, plain, style);
	}

	/** Renders [label](https://...) as a clickable link; returns how many characters it used, or 0 if it is not one. */
	private static int link(MutableComponent out, StringBuilder plain, String text, int start, Style style) {
		int labelEnd = text.indexOf("](", start + 1);
		if (labelEnd < 0) {
			return 0;
		}
		int urlEnd = text.indexOf(')', labelEnd + 2);
		if (urlEnd < 0) {
			return 0;
		}
		String url = text.substring(labelEnd + 2, urlEnd).strip();
		String lower = url.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
			return 0;
		}
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			return 0;
		}
		flush(out, plain, style);
		Style linkStyle = style.applyFormat(LINK_COLOR).withUnderlined(true)
			.withClickEvent(new ClickEvent.OpenUrl(uri))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(url)));
		inline(out, text.substring(start + 1, labelEnd), linkStyle);
		return urlEnd + 1 - start;
	}

	private static Delimiter opening(String text, int index) {
		for (Delimiter delimiter : DELIMITERS) {
			String marker = delimiter.marker();
			if (!text.startsWith(marker, index)) {
				continue;
			}
			int after = index + marker.length();
			boolean followedByText = after < text.length() && !Character.isWhitespace(text.charAt(after));
			boolean boundaryBefore = !delimiter.wordBound() || index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
			return followedByText && boundaryBefore ? delimiter : null;
		}
		return null;
	}

	private static int findClose(String text, int from, Delimiter delimiter) {
		String marker = delimiter.marker();
		char markerChar = marker.charAt(0);
		int i = from;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '\\') {
				i += 2;
				continue;
			}
			if (c == '`') {
				int close = text.indexOf('`', i + 1);
				if (close > 0) {
					i = close + 1;
					continue;
				}
			}
			if (text.startsWith(marker, i) && i > from) {
				int after = i + marker.length();
				boolean precededByText = !Character.isWhitespace(text.charAt(i - 1));
				boolean boundaryAfter = !delimiter.wordBound() || after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
				// A single * or _ must not be half of a doubled marker, which belongs to bold or underline.
				boolean partOfLonger = marker.length() == 1 && (after < text.length() && text.charAt(after) == markerChar
					|| text.charAt(i - 1) == markerChar);
				if (precededByText && boundaryAfter && !partOfLonger) {
					return i;
				}
				if (partOfLonger && after < text.length() && text.charAt(after) == markerChar) {
					i += 2;
					continue;
				}
			}
			i++;
		}
		return -1;
	}

	private static void flush(MutableComponent out, StringBuilder plain, Style style) {
		if (!plain.isEmpty()) {
			out.append(Component.literal(plain.toString()).withStyle(style));
			plain.setLength(0);
		}
	}

	private static boolean isPunctuation(char c) {
		return "\\`*_~[]()#+-.!>|".indexOf(c) >= 0;
	}
}
