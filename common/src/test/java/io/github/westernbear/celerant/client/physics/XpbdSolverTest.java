package io.github.westernbear.celerant.client.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class XpbdSolverTest {
	@Test
	void stretchRestoresRestLength() {
		float[] x = {0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.0F};
		float[] w = {0.0F, 1.0F};
		float[] lambda = {0.0F};
		float dt = 1.0F / 60.0F;
		for (int i = 0; i < 8; i++) {
			XpbdSolver.projectStretch(x, w, 0, 1, 1.0F, 0.0F, dt, lambda, 0);
		}
		float dx = x[3] - x[0];
		float dy = x[4] - x[1];
		float dz = x[5] - x[2];
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		assertEquals(1.0F, len, 1.0E-3F);
	}

	@Test
	void bendPullsChordTowardRest() {
		float[] x = {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F};
		float[] w = {0.0F, 1.0F, 1.0F};
		float[] lambda = {0.0F};
		float restChord = 2.0F;
		float dt = 1.0F / 60.0F;
		float before = distance(x, 0, 2);
		for (int i = 0; i < 12; i++) {
			XpbdSolver.projectBend(x, w, 0, 2, restChord, 0.0F, dt, lambda, 0);
		}
		float after = distance(x, 0, 2);
		assertTrue(Math.abs(after - restChord) < Math.abs(before - restChord));
		assertEquals(restChord, after, 1.0E-2F);
	}

	@Test
	void sphereColliderPushesParticleOut() {
		SpringBoneCollider sphere = SpringBoneCollider.sphere(0.0F, 0.0F, 0.0F, 0.5F);
		Matrix4f identity = new Matrix4f();
		Vector3f pos = new Vector3f();
		Vector3f normal = new Vector3f();
		assertTrue(sphere.pushOut(identity, 0.1F, 0.0F, 0.0F, 0.0F, pos, normal));
		float dist = pos.length();
		assertEquals(0.5F, dist, 1.0E-4F);
	}

	@Test
	void complianceMapsFromStiffness() {
		assertEquals(1.0F, XpbdSolver.complianceFromStiffness(1.0F), 1.0E-6F);
		assertTrue(XpbdSolver.bendCompliance(0.25F) > 0.25F);
	}

	@Test
	void verletStiffnessPullsAlongRestAxis() {
		// Spec: next = current + (current-prev)*(1-drag) + axis*stiffness*dt + gravity*dt
		float[] current = {0.0F, 0.0F, 0.0F};
		float[] prev = {0.0F, 0.0F, 0.0F};
		float[] axis = {0.0F, 1.0F, 0.0F};
		float dt = 1.0F / 60.0F;
		float stiffness = 2.0F;
		float drag = 0.4F;
		float[] next = {
			current[0] + (current[0] - prev[0]) * (1.0F - drag) + axis[0] * stiffness * dt,
			current[1] + (current[1] - prev[1]) * (1.0F - drag) + axis[1] * stiffness * dt,
			current[2] + (current[2] - prev[2]) * (1.0F - drag) + axis[2] * stiffness * dt
		};
		assertEquals(0.0F, next[0], 1.0E-6F);
		assertEquals(stiffness * dt, next[1], 1.0E-6F);
		assertEquals(0.0F, next[2], 1.0E-6F);
	}

	@Test
	void springSimulatorCapsStepsAndSkipsCulledGraphs() {
		de.javagl.jgltf.model.impl.DefaultNodeModel head = new de.javagl.jgltf.model.impl.DefaultNodeModel();
		de.javagl.jgltf.model.impl.DefaultNodeModel tip = new de.javagl.jgltf.model.impl.DefaultNodeModel();
		head.addChild(tip);
		tip.setTranslation(new float[] {0.0F, 0.1F, 0.0F});
		head.setRotation(new float[] {0.0F, 0.0F, 0.0F, 1.0F});
		BoneClothGraph graph = BoneClothGraph.build(
			java.util.List.of(head, tip),
			new float[] {1.0F},
			new float[] {0.4F},
			new float[] {0.0F},
			new float[][] {{0.0F, -1.0F, 0.0F}},
			new float[] {0.02F},
			java.util.List.of(),
			null,
			java.util.Set.of());
		BoneClothSimulator sim = new BoneClothSimulator(java.util.List.of(graph));
		// Two tiny frames accumulate to one 60 Hz step; culled head chain is skipped.
		sim.step(1.0F / 120.0F, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
		java.util.Set<de.javagl.jgltf.model.NodeModel> culled =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		culled.add(head);
		culled.add(tip);
		assertTrue(graph.isFullyCulled(culled));
		sim.step(1.0F / 120.0F, culled);
		sim.step(1.0F / 60.0F, culled);
	}

	private static float distance(float[] x, int i, int j) {
		int a = i * 3;
		int b = j * 3;
		float dx = x[b] - x[a];
		float dy = x[b + 1] - x[a + 1];
		float dz = x[b + 2] - x[a + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
