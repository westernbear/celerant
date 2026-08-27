package io.github.westernbear.celerant.client.physics;

import java.util.List;

/**
 * Owns all Line BoneCloth graphs for one loaded VRM.
 */
public final class BoneClothSimulator {
	private final List<BoneClothGraph> graphs;
	private boolean enabled = true;

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
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void reset() {
		for (BoneClothGraph graph : graphs) {
			graph.reset();
		}
	}

	public void step(float dt) {
		if (graphs.isEmpty()) {
			return;
		}
		if (!enabled) {
			for (BoneClothGraph graph : graphs) {
				graph.restoreRest();
			}
			return;
		}
		for (BoneClothGraph graph : graphs) {
			graph.step(dt);
		}
	}
}
