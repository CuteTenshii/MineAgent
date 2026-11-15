package moe.tenshii.mineagent.agent.llm;

/**
 * A provider error turned into a message the player can act on.
 */
public final class ApiFailure extends Exception {
	private final int status;

	public ApiFailure(String message) {
		this(message, 0);
	}

	/**
	 * @param status the HTTP status the provider answered with, or 0 when the failure was not an HTTP error response
	 */
	public ApiFailure(String message, int status) {
		super(message, null, false, false);
		this.status = status;
	}

	public int status() {
		return status;
	}

	/** A client error caused by the request's content, as opposed to the key, billing or rate limits. */
	public boolean rejectedContent() {
		return status / 100 == 4 && status != 401 && status != 402 && status != 403 && status != 429;
	}
}
