package moe.tenshii.mineagent.path;

import java.util.List;

/**
 * @param complete false when the search gave up and the path only gets closer to the goal
 */
public record Path(List<PathStep> steps, boolean complete) {
}
