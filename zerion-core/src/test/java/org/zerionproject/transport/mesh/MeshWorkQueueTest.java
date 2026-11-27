package org.zerionproject.transport.mesh;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MeshWorkQueueTest {

	private static final int FRAME = 32 * 1024;
	private static final int FLOOD = 1000;

	private final MeshWorkQueue queue = new MeshWorkQueue("test");
	private final CountDownLatch busy = new CountDownLatch(1);
	private final List<Integer> ran =
			Collections.synchronizedList(new ArrayList<>());

	@After
	public void tearDown() {
		busy.countDown();
		queue.shutdownNow();
	}

	@Test
	public void aFloodOfLargeFramesNeverQueuesMoreThanTheByteBound()
			throws Exception {
		blockWorker();
		for (int i = 0; i < FLOOD; i++) {
			assertTrue(queue.offer(record(i), FRAME));
			assertTrue("queued " + queue.queuedBytes() + " bytes",
					queue.queuedBytes() <= MeshWorkQueue.MAX_BYTES);
			assertTrue(queue.queuedTasks() <= MeshWorkQueue.MAX_TASKS);
		}
		long held = MeshWorkQueue.MAX_BYTES / FRAME;
		assertEquals(held, queue.queuedTasks());
		assertEquals(FLOOD - held, queue.droppedTasks());
		release();
		assertNewestRan((int) held);
	}

	@Test
	public void aFloodOfSmallFramesNeverQueuesMoreThanTheFrameBound()
			throws Exception {
		blockWorker();
		for (int i = 0; i < FLOOD; i++) {
			queue.offer(record(i), 10);
			assertTrue(queue.queuedTasks() <= MeshWorkQueue.MAX_TASKS);
		}
		assertEquals(MeshWorkQueue.MAX_TASKS, queue.queuedTasks());
		release();
		assertNewestRan(MeshWorkQueue.MAX_TASKS);
	}

	@Test
	public void aControlTaskSurvivesAFlood() throws Exception {
		blockWorker();
		CountDownLatch control = new CountDownLatch(1);
		queue.execute(control::countDown);
		for (int i = 0; i < FLOOD; i++) queue.offer(record(i), FRAME);
		release();
		assertTrue("the control task was dropped",
				control.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void ownFramesSurviveARelayFlood() throws Exception {
		blockWorker();
		for (int i = 0; i < 5; i++) queue.offerOwn(record(-1 - i), FRAME);
		for (int i = 0; i < FLOOD; i++) queue.offer(record(i), FRAME);
		release();
		assertRanUpTo(FLOOD - 1);
		for (int i = 0; i < 5; i++) {
			assertTrue("own frame " + i + " was dropped",
					ran.contains(-1 - i));
		}
	}

	@Test
	public void ownFramesHaveACapOfTheirOwn() throws Exception {
		blockWorker();
		for (int i = 0; i < FLOOD; i++) {
			queue.offerOwn(record(i), 10);
			assertTrue(queue.queuedTasks() <= MeshWorkQueue.MAX_OWN_TASKS);
		}
		release();
		assertNewestRan(MeshWorkQueue.MAX_OWN_TASKS);
	}

	@Test
	public void aTaskThatThrowsAnErrorDoesNotStopTheQueue()
			throws Exception {
		Thread.UncaughtExceptionHandler previous =
				Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
		});
		try {
			queue.execute(() -> {
				throw new AssertionError("task failed");
			});
			queue.offer(record(FLOOD - 1), 10);
			assertRanUpTo(FLOOD - 1);
			assertEquals("the queue stopped after a task threw an Error",
					1, ran.size());
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previous);
		}
	}

	@Test
	public void aTaskLargerThanTheBoundIsRefused() {
		assertFalse(queue.offer(record(0), MeshWorkQueue.MAX_BYTES + 1));
		assertEquals(0, queue.queuedTasks());
	}

	@Test
	public void aShutDownQueueDiscardsAndRefuses() throws Exception {
		blockWorker();
		queue.offer(record(0), FRAME);
		queue.shutdownNow();
		assertEquals(0, queue.queuedTasks());
		assertEquals(0, queue.queuedBytes());
		assertFalse(queue.offer(record(1), FRAME));
		try {
			queue.execute(() -> {
			});
			fail();
		} catch (RejectedExecutionException expected) {
		}
	}

	private Runnable record(int i) {
		return () -> ran.add(i);
	}

	private void blockWorker() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		queue.execute(() -> {
			started.countDown();
			try {
				busy.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(started.await(5, TimeUnit.SECONDS));
	}

	private void release() {
		busy.countDown();
	}

	private void assertRanUpTo(int last) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline
				&& (ran.isEmpty() || ran.get(ran.size() - 1) != last)) {
			Thread.sleep(5);
		}
	}

	private void assertNewestRan(int count) throws InterruptedException {
		assertRanUpTo(FLOOD - 1);
		List<Integer> expected = new ArrayList<>();
		for (int i = FLOOD - count; i < FLOOD; i++) expected.add(i);
		assertEquals("the oldest frames are the ones dropped", expected,
				new ArrayList<>(ran));
	}
}
