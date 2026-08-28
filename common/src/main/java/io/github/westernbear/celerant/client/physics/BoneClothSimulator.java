package io.github.westernbear.celerant.client.physics;

import java.util.List;
import java.util.Set;

import de.javagl.jgltf.model.NodeModel;

/**
 * Owns all Line BoneCloth graphs for one loaded VRM.
 */
public final class BoneClothSimulator {
	private static final float FIXED_DT = 1.0F / 60.0F;
	private static final int MAX_STEPS_PER_FRAME = 2;

	private final List<BoneClothGraph> graphs;
	private boolean enabled = true;
	private float accumulator;

	BoneClothSimulator(List<BoneClothGraph> graphs) {
		this.graphs = List.copyOf(graphs);
	}

	public static BoneClothSimulator empty() {
		return new BoneClothSimulator(List.of());
	}

	public boolean isEmpty() {
		return graphs.isEmpty();
	}

	public int graphCount() {
		return graphs.size();
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (!enabled) {
			for (BoneClothGraph graph : graphs) {
				graph.restoreRest();
			}
			accumulator = 0.0F;
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void reset() {
		for (BoneClothGraph graph : graphs) {
			graph.reset();
		}
		accumulator = 0.0F;
	}

	/** Steps spring graphs with a capped 60 Hz integrator. */
	public void step(float frameDt) {
		step(frameDt, null);
	}

	/**
	 * Steps spring graphs, skipping chains whose joints are fully covered by
	 * {@code culledJoints} (first-person head/hair cull).
	 */
	public void step(float frameDt, Set<NodeModel> culledJoints) {
		if (graphs.isEmpty()) {
			return;
		}
		if (!enabled) {
			for (BoneClothGraph graph : graphs) {
				graph.restoreRest();
			}
			return;
		}
		float dt = Math.min(0.05F, Math.max(0.0F, frameDt));
		accumulator += dt;
		int steps = 0;
		while (accumulator >= FIXED_DT && steps < MAX_STEPS_PER_FRAME) {
			stepOnce(FIXED_DT, culledJoints);
			accumulator -= FIXED_DT;
			steps++;
		}
		if (steps == 0 && accumulator > FIXED_DT * MAX_STEPS_PER_FRAME) {
			accumulator = FIXED_DT * MAX_STEPS_PER_FRAME;
		}
	}

	private void stepOnce(float dt, Set<NodeModel> culledJoints) {
		for (BoneClothGraph graph : graphs) {
			if (culledJoints != null && !culledJoints.isEmpty() && graph.isFullyCulled(culledJoints)) {
				continue;
			}
			graph.step(dt);
		}
	}
}
