package io.github.westernbear.celerant.client.toon;

import net.irisshaders.iris.vertices.ImmediateState;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ToonShader {
	private static final System.Logger LOGGER = System.getLogger(ToonShader.class.getName());
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static volatile boolean enabled = true;

	private ToonShader() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean enabled) {
		ToonShader.enabled = enabled;
	}

	public static void renderFinalPass() {
		if (!enabled || Boolean.getBoolean("celerant.testing.disableToonShader")) {
			return;
		}
		boolean bypass = ImmediateState.bypass;
		boolean renderingLevel = ImmediateState.isRenderingLevel;
		try {
			ImmediateState.bypass = true;
			ImmediateState.isRenderingLevel = false;
			com.modularmods.mcgltf.ToonShader.renderFrame();
		} catch (RuntimeException | LinkageError exception) {
			if (WARNED.compareAndSet(false, true)) {
				LOGGER.log(System.Logger.Level.ERROR, "ToonShader final pass failed; the ShaderPack output is unchanged", exception);
			}
		} finally {
			ImmediateState.isRenderingLevel = renderingLevel;
			ImmediateState.bypass = bypass;
		}
	}
}
