package io.github.westernbear.celerant.gametest;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class CelerantIrisTestBridge {

	private CelerantIrisTestBridge() {
	}

	public static boolean isPackInUseQuick() {
		return invokeBoolean("net.irisshaders.iris.Iris", "isPackInUseQuick", false);
	}

	public static String getCurrentPackName() {
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			return (String) iris.getMethod("getCurrentPackName").invoke(null);
		} catch (ReflectiveOperationException exception) {
			return "";
		}
	}

	public static Optional<String> getStoredError() {
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			@SuppressWarnings("unchecked")
			Optional<String> error = (Optional<String>) iris.getMethod("getStoredError").invoke(null);
			return error;
		} catch (ReflectiveOperationException exception) {
			return Optional.empty();
		}
	}

	public static boolean isValidShaderpack(Path path) {
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			return (boolean) iris.getMethod("isValidShaderpack", Path.class).invoke(null, path);
		} catch (ReflectiveOperationException exception) {
			return false;
		}
	}

	public static void reload() {
		invokeVoid("net.irisshaders.iris.Iris", "reload");
	}

	public static Object getIrisConfig() {
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			return iris.getMethod("getIrisConfig").invoke(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	public static Object getPipelineManager() {
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			return iris.getMethod("getPipelineManager").invoke(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	public static Object getShaderPackOptionQueue() {
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			return iris.getMethod("getShaderPackOptionQueue").invoke(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	public static void setShaderPackName(Object config, String packName) throws ReflectiveOperationException {
		config.getClass().getMethod("setShaderPackName", String.class).invoke(config, packName);
	}

	public static void setShadersEnabled(Object config, boolean enabled) throws ReflectiveOperationException {
		config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
	}

	public static void setDebugEnabled(Object config, boolean enabled) throws ReflectiveOperationException {
		config.getClass().getMethod("setDebugEnabled", boolean.class).invoke(config, enabled);
	}

	public static void saveConfig(Object config) throws ReflectiveOperationException {
		config.getClass().getMethod("save").invoke(config);
	}

	public static boolean pipelinePresent(Object pipelineManager) {
		try {
			Optional<?> pipeline = (Optional<?>) pipelineManager.getClass().getMethod("getPipeline").invoke(pipelineManager);
			return pipeline.isPresent();
		} catch (ReflectiveOperationException exception) {
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	public static void putShaderPackOptions(Object queue, Map<String, String> options) throws ReflectiveOperationException {
		queue.getClass().getMethod("putAll", Map.class).invoke(queue, options);
	}

	private static boolean invokeBoolean(String className, String method, boolean fallback) {
		try {
			Class<?> type = Class.forName(className);
			return (boolean) type.getMethod(method).invoke(null);
		} catch (ReflectiveOperationException exception) {
			return fallback;
		}
	}

	private static void invokeVoid(String className, String method) {
		try {
			Class<?> type = Class.forName(className);
			type.getMethod(method).invoke(null);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Iris API unavailable: " + className + "#" + method, exception);
		}
	}
}
