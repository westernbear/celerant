package io.github.westernbear.celerant.gametest;

public interface CelerantGameTestWorld extends AutoCloseable {

	Object server();

	void runCommand(String command);

	void waitForChunksRender(boolean wait, int maxTicks);

	void waitForClientboundPackets();

	@Override
	void close();
}
