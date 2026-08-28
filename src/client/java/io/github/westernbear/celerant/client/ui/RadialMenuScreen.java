package io.github.westernbear.celerant.client.ui;

import java.util.List;

import io.github.westernbear.celerant.client.RadialMenuActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

/**
 * VRChat-style radial picker: B opens this screen; mouse angle highlights a wedge; click activates.
 */
public final class RadialMenuScreen extends Screen {
	private static final int INNER_RADIUS = 28;
	private static final int OUTER_RADIUS = 92;
	private static final int LABEL_RADIUS = 64;
	private static final int DEAD_ZONE = 18;

	private int hovered = -1;

	public RadialMenuScreen() {
		super(Component.translatable("celerant.radial.title"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0x99000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		List<RadialMenuActions.Slice> slices = RadialMenuActions.slices();
		int n = slices.size();
		if (n == 0) {
			return;
		}
		int cx = this.width / 2;
		int cy = this.height / 2;
		this.hovered = hoverIndex(mouseX, mouseY, cx, cy, n);

		for (int i = 0; i < n; i++) {
			float start = sliceStart(i, n);
			float end = sliceStart(i + 1, n);
			boolean active = i == this.hovered;
			int fill = active ? 0xCC3D7EFF : 0xAA1A1F2B;
			int rim = active ? 0xFFE8F0FF : 0x88A0A8B8;
			drawWedge(graphics, cx, cy, start, end, INNER_RADIUS, OUTER_RADIUS, fill);
			drawWedge(graphics, cx, cy, start, end, OUTER_RADIUS - 2, OUTER_RADIUS, rim);
			drawWedge(graphics, cx, cy, start, end, INNER_RADIUS, INNER_RADIUS + 2, rim);

			float mid = (start + end) * 0.5F;
			int lx = cx + Math.round(Mth.cos(mid) * LABEL_RADIUS);
			int ly = cy + Math.round(Mth.sin(mid) * LABEL_RADIUS) - this.font.lineHeight / 2;
			Component label = slices.get(i).label();
			int tw = this.font.width(label);
			graphics.fill(lx - tw / 2 - 3, ly - 2, lx + tw / 2 + 3, ly + this.font.lineHeight + 2,
				active ? 0xDD000000 : 0x88000000);
			graphics.centeredText(this.font, label, lx, ly, active ? 0xFFFFFFFF : 0xFFD0D4DC);
		}

		graphics.fill(cx - DEAD_ZONE, cy - DEAD_ZONE, cx + DEAD_ZONE, cy + DEAD_ZONE, 0xEE0B0E14);
		Component center = this.hovered >= 0 ? slices.get(this.hovered).label() : this.title;
		graphics.centeredText(this.font, center, cx, cy - this.font.lineHeight / 2, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(event, doubleClick);
		}
		List<RadialMenuActions.Slice> slices = RadialMenuActions.slices();
		int index = hoverIndex((int) event.x(), (int) event.y(), this.width / 2, this.height / 2, slices.size());
		if (index < 0) {
			onClose();
			return true;
		}
		activate(index);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(null);
	}

	/** GameTest / callers: gui-space center of a wedge for cursor targeting. */
	public static double[] sliceGuiPoint(int width, int height, int index, int sliceCount) {
		float mid = (sliceStart(index, sliceCount) + sliceStart(index + 1, sliceCount)) * 0.5F;
		double cx = width / 2.0;
		double cy = height / 2.0;
		return new double[] {
			cx + Mth.cos(mid) * LABEL_RADIUS,
			cy + Mth.sin(mid) * LABEL_RADIUS
		};
	}

	/** Convert gui coords to window coords for {@code TestInput#setCursorPos}. */
	public static double[] guiToWindow(Minecraft client, double guiX, double guiY) {
		var window = client.getWindow();
		double sx = (double) window.getWidth() / (double) window.getGuiScaledWidth();
		double sy = (double) window.getHeight() / (double) window.getGuiScaledHeight();
		return new double[] {guiX * sx, guiY * sy};
	}

	void activate(int index) {
		List<RadialMenuActions.Slice> slices = RadialMenuActions.slices();
		if (index < 0 || index >= slices.size()) {
			onClose();
			return;
		}
		RadialMenuActions.Slice slice = slices.get(index);
		onClose();
		slice.run();
	}

	private static float sliceStart(int index, int n) {
		// Start at top (-90°) and sweep clockwise in screen space (Y+ down ⇒ atan2 clockwise from east).
		return (float) (-Math.PI / 2.0 + (Math.PI * 2.0) * index / n);
	}

	private static int hoverIndex(int mouseX, int mouseY, int cx, int cy, int n) {
		if (n <= 0) {
			return -1;
		}
		double dx = mouseX - cx;
		double dy = mouseY - cy;
		double dist = Math.hypot(dx, dy);
		if (dist < DEAD_ZONE || dist > OUTER_RADIUS + 12) {
			return -1;
		}
		double angle = Math.atan2(dy, dx);
		double fromTop = angle + Math.PI / 2.0;
		if (fromTop < 0) {
			fromTop += Math.PI * 2.0;
		}
		int index = (int) (fromTop / (Math.PI * 2.0 / n));
		return Mth.clamp(index, 0, n - 1);
	}

	private static void drawWedge(GuiGraphicsExtractor graphics, int cx, int cy,
		float start, float end, int inner, int outer, int color) {
		float sweep = end - start;
		int steps = Math.max(12, Math.round(Math.abs(sweep) * 48));
		for (int i = 0; i < steps; i++) {
			float t0 = start + sweep * i / steps;
			float t1 = start + sweep * (i + 1) / steps;
			for (int r = inner; r < outer; r++) {
				int x0 = cx + Math.round(Mth.cos(t0) * r);
				int y0 = cy + Math.round(Mth.sin(t0) * r);
				int x1 = cx + Math.round(Mth.cos(t1) * r);
				int y1 = cy + Math.round(Mth.sin(t1) * r);
				int minX = Math.min(x0, x1);
				int maxX = Math.max(x0, x1);
				int minY = Math.min(y0, y1);
				int maxY = Math.max(y0, y1);
				graphics.fill(minX, minY, maxX + 1, maxY + 1, color);
			}
		}
	}
}
