package org.zerionproject.core;

import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PoliteExecutorTest extends BrambleTestCase {

	private static final String TAG = "Test";
	private static final int TASKS = 10;

	@Test
	public void testTasksAreDelegatedInOrderOfSubmission() throws Exception {

		Executor delegate = Executors.newSingleThreadExecutor();

		PoliteExecutor polite = new PoliteExecutor(TAG, delegate, TASKS * 2);
		List<Integer> list = new Vector<>();
		CountDownLatch latch = new CountDownLatch(TASKS);
		for (int i = 0; i < TASKS; i++) {
			int result = i;
			polite.execute(() -> {
				list.add(result);
				latch.countDown();
			});
		}

		latch.await();

		assertEquals(ascendingOrder(), list);
	}

	@Test
	public void testQueuedTasksAreDelegatedInOrderOfSubmission()
			throws Exception {

		Executor delegate = Executors.newSingleThreadExecutor();

		PoliteExecutor polite = new PoliteExecutor(TAG, delegate, 2);
		List<Integer> list = new Vector<>();
		CountDownLatch latch = new CountDownLatch(TASKS);
		for (int i = 0; i < TASKS; i++) {
			int result = i;
			polite.execute(() -> {
				list.add(result);
				latch.countDown();
			});
		}

		latch.await();

		assertEquals(ascendingOrder(), list);
	}

	@Test
	public void testTasksRunInParallelOnDelegate() throws Exception {

		Executor delegate = Executors.newCachedThreadPool();

		PoliteExecutor polite = new PoliteExecutor(TAG, delegate, TASKS * 2);
		List<Integer> list = new Vector<>();
		CountDownLatch[] latches = new CountDownLatch[TASKS];
		for (int i = 0; i < TASKS; i++) latches[i] = new CountDownLatch(1);
		for (int i = 0; i < TASKS; i++) {
			int result = i;
			polite.execute(() -> {
				try {

					if (result < TASKS - 1) latches[result + 1].await();
					list.add(result);
				} catch (InterruptedException e) {
					fail();
				}
				latches[result].countDown();
			});
		}

		for (int i = 0; i < TASKS; i++) latches[i].await();

		assertEquals(descendingOrder(), list);
	}

	@Test
	public void testTasksDoNotRunInParallelOnDelegate() throws Exception {

		Executor delegate = Executors.newCachedThreadPool();

		PoliteExecutor polite = new PoliteExecutor(TAG, delegate, 1);
		List<Integer> list = new Vector<>();
		CountDownLatch latch = new CountDownLatch(TASKS);
		for (int i = 0; i < TASKS; i++) {
			int result = i;
			polite.execute(() -> {
				try {

					Thread.sleep(TASKS - result);
					list.add(result);
				} catch (InterruptedException e) {
					fail();
				}
				latch.countDown();
			});
		}

		latch.await();

		assertEquals(ascendingOrder(), list);
	}

	private List<Integer> ascendingOrder() {
		Integer[] array = new Integer[TASKS];
		for (int i = 0; i < TASKS; i++) array[i] = i;
		return Arrays.asList(array);
	}

	private List<Integer> descendingOrder() {
		Integer[] array = new Integer[TASKS];
		for (int i = 0; i < TASKS; i++) array[i] = TASKS - 1 - i;
		return Arrays.asList(array);
	}
}
