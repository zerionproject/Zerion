

package com.professor.zerion.android.viewmodel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LiveEventTestUtil {
	public static <T> T getOrAwaitValue(final LiveEvent<T> liveEvent)
			throws InterruptedException {
		final AtomicReference<T> data = new AtomicReference<>();
		final CountDownLatch latch = new CountDownLatch(1);
		liveEvent.observeEventForever(new LiveEvent.LiveEventHandler<T>() {
			@Override
			public void onEvent(T o) {
				data.set(o);
				latch.countDown();

			}
		});

		if (!latch.await(2, TimeUnit.SECONDS)) {
			throw new RuntimeException("LiveEvent value was never set.");
		}
		return data.get();
	}
}
