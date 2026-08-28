package io.github.westernbear.celerant.gametest;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;

public interface CelerantGameTestHost {

	void runOnClient(Consumer<Minecraft> action);

	<T> T computeOnClient(Function<Minecraft, T> action);

	void waitFor(Predicate<Minecraft> condition, int maxTicks);

	void waitTicks(int ticks);

	void waitForScreen(Class<?> screenClass);

	Path takeScreenshot(String name);

	CelerantGameTestInput getInput();

	CelerantGameTestWorld openSingleplayerWorld();

	void registerMatrixFrameRecorder(Runnable onFrame);

	Path gameDirectory();
}
