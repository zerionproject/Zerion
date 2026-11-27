package org.zerionproject.transport.mesh;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class MeshWorkQueue implements Executor {

	public static final int MAX_TASKS = 64;
	public static final long MAX_BYTES = 1024 * 1024;
	public static final int MAX_OWN_TASKS = 16;
	public static final long MAX_OWN_BYTES = 256 * 1024;

	private static final int RELAYED = 0;
	private static final int OWN = 1;
	private static final int CONTROL = 2;

	private static final class Task {
		final Runnable work;
		final long bytes;
		final int kind;

		Task(Runnable work, long bytes, int kind) {
			this.work = work;
			this.bytes = bytes;
			this.kind = kind;
		}
	}

	private final String name;
	private final int maxTasks;
	private final long maxBytes;
	private final Object lock = new Object();
	private final ArrayDeque<Task> queue = new ArrayDeque<>();
	private long queuedBytes = 0;
	private int ownTasks = 0;
	private long ownBytes = 0;
	private long dropped = 0;
	private boolean shutDown = false;
	@Nullable
	private Thread worker = null;

	public MeshWorkQueue(String name) {
		this(name, MAX_TASKS, MAX_BYTES);
	}

	public MeshWorkQueue(String name, int maxTasks, long maxBytes) {
		if (maxTasks < 1 || maxBytes < 1) throw new IllegalArgumentException();
		this.name = name;
		this.maxTasks = maxTasks;
		this.maxBytes = maxBytes;
	}

	public boolean offer(Runnable work, long bytes) {
		if (bytes < 0 || bytes > maxBytes) return false;
		return enqueue(new Task(work, bytes, RELAYED));
	}

	public boolean offerOwn(Runnable work, long bytes) {
		if (bytes < 0 || bytes > Math.min(maxBytes, MAX_OWN_BYTES)) {
			return false;
		}
		return enqueue(new Task(work, bytes, OWN));
	}

	@Override
	public void execute(Runnable work) {
		if (!enqueue(new Task(work, 0, CONTROL))) {
			throw new RejectedExecutionException();
		}
	}

	private boolean enqueue(Task task) {
		synchronized (lock) {
			if (shutDown) return false;
			if (task.kind == OWN) {
				while (ownTasks > 0 && (ownTasks >= MAX_OWN_TASKS
						|| ownBytes + task.bytes > MAX_OWN_BYTES)) {
					dropOldestOf(OWN);
				}
			}
			while (!queue.isEmpty() && (queue.size() >= maxTasks
					|| queuedBytes + task.bytes > maxBytes)) {
				if (!dropOldestOf(RELAYED) && !dropOldestOf(OWN)) {
					dropOldestOf(CONTROL);
				}
			}
			queue.addLast(task);
			queuedBytes += task.bytes;
			if (task.kind == OWN) {
				ownTasks++;
				ownBytes += task.bytes;
			}
			if (worker == null) startWorker();
			lock.notifyAll();
			return true;
		}
	}

	private void startWorker() {
		Thread t = new Thread(this::drain, name);
		t.setDaemon(true);
		worker = t;
		t.start();
	}

	private boolean dropOldestOf(int kind) {
		Iterator<Task> it = queue.iterator();
		while (it.hasNext()) {
			Task t = it.next();
			if (t.kind == kind) {
				it.remove();
				forget(t);
				dropped++;
				return true;
			}
		}
		return false;
	}

	private void forget(Task t) {
		queuedBytes -= t.bytes;
		if (t.kind == OWN) {
			ownTasks--;
			ownBytes -= t.bytes;
		}
	}

	private void drain() {
		try {
			while (true) {
				Task t;
				synchronized (lock) {
					while (queue.isEmpty() && !shutDown) {
						try {
							lock.wait();
						} catch (InterruptedException e) {
							if (shutDown) return;
						}
					}
					if (shutDown) return;
					t = queue.pollFirst();
					forget(t);
				}
				try {
					t.work.run();
				} catch (RuntimeException e) {
				}
			}
		} finally {
			synchronized (lock) {
				if (worker == Thread.currentThread()) {
					worker = null;
					if (!shutDown && !queue.isEmpty()) startWorker();
				}
			}
		}
	}

	public void shutdownNow() {
		synchronized (lock) {
			shutDown = true;
			queue.clear();
			queuedBytes = 0;
			ownTasks = 0;
			ownBytes = 0;
			Thread t = worker;
			if (t != null) t.interrupt();
			lock.notifyAll();
		}
	}

	public int queuedTasks() {
		synchronized (lock) {
			return queue.size();
		}
	}

	public long queuedBytes() {
		synchronized (lock) {
			return queuedBytes;
		}
	}

	public long droppedTasks() {
		synchronized (lock) {
			return dropped;
		}
	}
}
