package com.professor.zerion.android.backup;

import java.lang.reflect.Method;

import static org.junit.Assume.assumeTrue;

final class ThreadAllocations {

	private final Object bean;
	private final Method allocated;

	private ThreadAllocations(Object bean, Method allocated) {
		this.bean = bean;
		this.allocated = allocated;
	}

	static ThreadAllocations require() {
		try {
			Object bean = Class.forName("java.lang.management.ManagementFactory")
					.getMethod("getThreadMXBean").invoke(null);
			Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
			assumeTrue(type.isInstance(bean));
			boolean enabled = (Boolean) type
					.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean);
			assumeTrue(enabled);
			return new ThreadAllocations(bean,
					type.getMethod("getThreadAllocatedBytes", long.class));
		} catch (ReflectiveOperationException e) {
			assumeTrue("per-thread allocation counting is unavailable", false);
			throw new AssertionError(e);
		}
	}

	long now() {
		try {
			return (Long) allocated.invoke(bean, Thread.currentThread().getId());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
