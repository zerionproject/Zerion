package com.professor.zerion.android.testing;

import java.lang.reflect.Proxy;

public final class Inert {

	private Inert() {
	}

	@SuppressWarnings("unchecked")
	public static <T> T of(Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					Class<?> r = method.getReturnType();
					if (r == boolean.class) return false;
					if (r == int.class) return 0;
					if (r == long.class) return 0L;
					if (r == short.class) return (short) 0;
					if (r == byte.class) return (byte) 0;
					if (r == float.class) return 0f;
					if (r == double.class) return 0d;
					if (r == char.class) return '\0';
					return null;
				});
	}
}
