package moe.tenshii.mineagent.config;

/** The screen corner the agent's status box sits in; offsets move it from there toward the center. */
public enum HudCorner {
	TOP_LEFT("Top left", false, false),
	TOP_RIGHT("Top right", true, false),
	BOTTOM_LEFT("Bottom left", false, true),
	BOTTOM_RIGHT("Bottom right", true, true);

	private final String label;
	private final boolean right;
	private final boolean bottom;

	HudCorner(String label, boolean right, boolean bottom) {
		this.label = label;
		this.right = right;
		this.bottom = bottom;
	}

	public String label() {
		return label;
	}

	public boolean right() {
		return right;
	}

	public boolean bottom() {
		return bottom;
	}
}
