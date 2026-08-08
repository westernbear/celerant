package io.github.westernbear.celerant.client;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public final class PresentedWindowCapture {
	private PresentedWindowCapture() { }

	public static void main(String[] args) throws Exception {
		if (args.length != 5) {
			throw new IllegalArgumentException("expected x y width height output");
		}
		Rectangle bounds = new Rectangle(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
			Integer.parseInt(args[2]), Integer.parseInt(args[3]));
		Toolkit.getDefaultToolkit().sync();
		Robot robot = new Robot();
		robot.delay(50);
		if (!ImageIO.write(robot.createScreenCapture(bounds), "png", Path.of(args[4]).toFile())) {
			throw new IllegalStateException("PNG writer is unavailable");
		}
	}
}
