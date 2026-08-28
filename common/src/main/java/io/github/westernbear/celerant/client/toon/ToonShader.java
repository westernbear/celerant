package io.github.westernbear.celerant.client.toon;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ToonShader {
	private static final System.Logger LOGGER = System.getLogger(ToonShader.class.getName());
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static volatile boolean enabled = true;

	private ToonShader() {
	}

	public static boolean isEnabled() {
		return enabled && !Boolean.getBoolean("celerant.testing.disableToonShader");
	}

	public static void setEnabled(boolean enabled) {
		ToonShader.enabled = enabled;
	}

	public static void renderFinalPass() {
		if (!isEnabled()) {
			return;
		}
		boolean bypass = immediateBoolean("bypass", false);
		boolean renderingLevel = immediateBoolean("isRenderingLevel", false);
		try {
			setImmediateBoolean("bypass", true);
			setImmediateBoolean("isRenderingLevel", false);
			com.modularmods.mcgltf.ToonShader.renderFrame();
		} catch (RuntimeException | LinkageError exception) {
			if (WARNED.compareAndSet(false, true)) {
				LOGGER.log(System.Logger.Level.ERROR, "ToonShader final pass failed; the ShaderPack output is unchanged",
					exception);
			}
		} finally {
			setImmediateBoolean("isRenderingLevel", renderingLevel);
			setImmediateBoolean("bypass", bypass);
		}
	}

	private static boolean immediateBoolean(String field, boolean fallback) {
		try {
			Class<?> state = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
			return state.getField(field).getBoolean(null);
		} catch (ReflectiveOperationException exception) {
			return fallback;
		}
	}

	private static void setImmediateBoolean(String field, boolean value) {
		try {
			Class<?> state = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
			state.getField(field).setBoolean(null, value);
		} catch (ReflectiveOperationException exception) {
			// Iris not present — MCglTF ToonShader still runs without ImmediateState tweaks.
		}
	}
}
