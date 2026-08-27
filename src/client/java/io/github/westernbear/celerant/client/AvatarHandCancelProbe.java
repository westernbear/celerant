package io.github.westernbear.celerant.client;

import java.util.concurrent.atomic.AtomicInteger;

/** GameTest probe for cancelled vanilla first-person hand/arm submits. */
public final class AvatarHandCancelProbe {
	private static final AtomicInteger CANCELLED = new AtomicInteger();

	private AvatarHandCancelProbe() {
	}

	public static void note() {
		CANCELLED.incrementAndGet();
	}

	public static int take() {
		return CANCELLED.getAndSet(0);
	}
}
