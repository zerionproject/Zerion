package org.zerionproject.core.db;

import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LockFairnessTest extends BrambleTestCase {

	@Test
	public void testReadersCanShareTheLock() throws Exception {

		ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
		CountDownLatch firstReaderHasLock = new CountDownLatch(1);
		CountDownLatch firstReaderHasFinished = new CountDownLatch(1);
		CountDownLatch secondReaderHasLock = new CountDownLatch(1);
		CountDownLatch secondReaderHasFinished = new CountDownLatch(1);

		Thread first = new Thread(() -> {
			try {

				lock.readLock().lock();
				try {

					firstReaderHasLock.countDown();

					assertTrue(secondReaderHasLock.await(10, SECONDS));
				} finally {

					lock.readLock().unlock();
				}
			} catch (InterruptedException e) {
				fail();
			}
			firstReaderHasFinished.countDown();
		});
		first.start();

		Thread second = new Thread(() -> {
			try {

				assertTrue(firstReaderHasLock.await(10, SECONDS));

				lock.readLock().lock();
				try {

					secondReaderHasLock.countDown();
				} finally {

					lock.readLock().unlock();
				}
			} catch (InterruptedException e) {
				fail();
			}
			secondReaderHasFinished.countDown();
		});
		second.start();

		assertTrue(firstReaderHasFinished.await(10, SECONDS));
		assertTrue(secondReaderHasFinished.await(10, SECONDS));
	}

	@Test
	public void testWritersDoNotStarve() throws Exception {

		ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
		CountDownLatch firstReaderHasLock = new CountDownLatch(1);
		CountDownLatch firstReaderHasFinished = new CountDownLatch(1);
		CountDownLatch secondReaderHasFinished = new CountDownLatch(1);
		CountDownLatch writerHasFinished = new CountDownLatch(1);
		AtomicBoolean secondReaderHasHeldLock = new AtomicBoolean(false);
		AtomicBoolean writerHasHeldLock = new AtomicBoolean(false);

		Thread first = new Thread(() -> {
			try {

				lock.readLock().lock();
				try {

					firstReaderHasLock.countDown();

					while (lock.getQueueLength() < 2) Thread.sleep(10);

					assertFalse(secondReaderHasHeldLock.get());
					assertFalse(writerHasHeldLock.get());
				} finally {

					lock.readLock().unlock();
				}
			} catch (InterruptedException e) {
				fail();
			}
			firstReaderHasFinished.countDown();
		});
		first.start();

		Thread writer = new Thread(() -> {
			try {

				assertTrue(firstReaderHasLock.await(10, SECONDS));

				lock.writeLock().lock();
				try {
					writerHasHeldLock.set(true);

					assertFalse(secondReaderHasHeldLock.get());
				} finally {
					lock.writeLock().unlock();
				}
			} catch (InterruptedException e) {
				fail();
			}
			writerHasFinished.countDown();
		});
		writer.start();

		Thread second = new Thread(() -> {
			try {

				assertTrue(firstReaderHasLock.await(10, SECONDS));

				while (lock.getQueueLength() < 1) Thread.sleep(10);

				lock.readLock().lock();
				try {
					secondReaderHasHeldLock.set(true);

					assertTrue(writerHasHeldLock.get());
				} finally {
					lock.readLock().unlock();
				}
			} catch (InterruptedException e) {
				fail();
			}
			secondReaderHasFinished.countDown();
		});
		second.start();

		assertTrue(firstReaderHasFinished.await(10, SECONDS));
		assertTrue(secondReaderHasFinished.await(10, SECONDS));
		assertTrue(writerHasFinished.await(10, SECONDS));
	}
}
