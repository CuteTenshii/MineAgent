package moe.tenshii.mineagent.agent.llm;

import java.util.List;

/**
 * A conversation with one model provider. Each backend keeps the history in its provider's native format
 * so provider-specific content (such as thinking blocks) is replayed exactly.
 */
public interface ChatBackend extends AutoCloseable {
	/** Screenshots kept in the history; older ones are replaced by a note so each request stays affordable. */
	int MAX_IMAGES_KEPT = 3;

	void addUserText(String text);

	/** Sends the conversation and appends the model's reply to it. Blocks until the reply arrives. */
	Reply next() throws ApiFailure, InterruptedException;

	/** Appends tool results, followed by any extra user text, as the next user turn. */
	void addToolResults(List<ToolResult> results, List<String> extraUserTexts);

	/** Aborts a request in flight; {@link #next()} then throws {@link java.util.concurrent.CancellationException}. */
	void cancel();

	@Override
	void close();
}
