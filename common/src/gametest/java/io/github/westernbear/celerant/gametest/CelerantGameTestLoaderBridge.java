package io.github.westernbear.celerant.gametest;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public interface CelerantGameTestLoaderBridge {

	Object configTree();

	KeyMapping uiKey();

	KeyMapping radialKey();

	boolean isOneConfigRoute(Minecraft client);

	void setConfigProp(String name, Object value);

	Object getConfigProp(String name);
}
