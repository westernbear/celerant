package io.github.westernbear.celerant.gametest;

import java.util.function.Function;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;

public interface CelerantGameTestInput {

	void pressKey(KeyMapping mapping);

	void pressKey(int glfwKey);

	void holdKey(Function<Options, KeyMapping> mapping);

	void releaseKey(Function<Options, KeyMapping> mapping);

	void typeChars(String text);

	void setCursorPos(double x, double y);

	void pressMouse(int button);

	void holdMouse(int button);

	void releaseMouse(int button);

	void lookAt(BlockPos target);

	void lookAt(float yaw, float pitch);

	void resizeWindow(int width, int height);
}
