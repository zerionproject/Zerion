package com.professor.zerion.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

public final class CountingSecurePrefs implements AutoCloseable {

	private final SharedPreferences secure;
	private final Field delegate;
	private final SharedPreferences original;
	private final AtomicInteger reads = new AtomicInteger();

	private CountingSecurePrefs(SharedPreferences secure, Field delegate,
			SharedPreferences original) {
		this.secure = secure;
		this.delegate = delegate;
		this.original = original;
	}

	public static CountingSecurePrefs install(Context ctx) throws Exception {
		SharedPreferences secure =
				AppModule.getAndroidComponent(ctx).securePreferences();
		Field f = secure.getClass().getDeclaredField("delegate");
		f.setAccessible(true);
		SharedPreferences original = (SharedPreferences) f.get(secure);
		CountingSecurePrefs counter =
				new CountingSecurePrefs(secure, f, original);
		f.set(secure, Proxy.newProxyInstance(
				SharedPreferences.class.getClassLoader(),
				new Class<?>[] {SharedPreferences.class}, (proxy, m, args) -> {
					if (m.getName().startsWith("get")
							|| m.getName().equals("contains")) {
						counter.reads.incrementAndGet();
					}
					try {
						return m.invoke(original, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				}));
		return counter;
	}

	public int reads() {
		return reads.get();
	}

	@Override
	public void close() throws Exception {
		delegate.set(secure, original);
	}
}
