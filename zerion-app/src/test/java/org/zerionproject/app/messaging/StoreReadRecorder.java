package org.zerionproject.app.messaging;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
final class StoreReadRecorder {

	private static final String ONE_VISIT = ", one visit";

	private final List<String> names = new ArrayList<>();
	private final List<Object[]> arguments = new ArrayList<>();
	private final Map<String, Long> largestByCall = new LinkedHashMap<>();

	<T> T wrap(Class<T> type, T target) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					Object result = invoke(method, target,
							visitorsNoted(method, args));
					note(method.getName(),
							args == null ? new Object[0] : args, result);
					return result;
				}));
	}

	@Nullable
	private Object[] visitorsNoted(Method method, @Nullable Object[] args) {
		if (args == null) return null;
		Object[] passed = args.clone();
		Class<?>[] types = method.getParameterTypes();
		for (int i = 0; i < types.length; i++) {
			if (types[i].isInterface()
					&& types[i].getSimpleName().endsWith("Visitor")) {
				passed[i] = visitorNoted(method.getName(), types[i], args[i]);
			}
		}
		return passed;
	}

	private Object visitorNoted(String call, Class<?> type, Object visitor) {
		return Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					if (method.getDeclaringClass() != Object.class) {
						noteHandOver(call + ONE_VISIT,
								args == null ? new Object[0] : args);
					}
					return invoke(method, visitor, args);
				});
	}

	@Nullable
	private static Object invoke(Method method, Object target,
			@Nullable Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private synchronized void note(String name, Object[] args,
			@Nullable Object result) {
		names.add(name);
		arguments.add(args);
		if (name.startsWith("transaction")) return;
		noteBytes(name, sizeOf(result));
	}

	private synchronized void noteHandOver(String name, Object[] args) {
		long bytes = 0;
		for (Object a : args) bytes += sizeOf(a);
		noteBytes(name, bytes);
	}

	private void noteBytes(String name, long bytes) {
		Long largest = largestByCall.get(name);
		if (largest == null || bytes > largest) {
			largestByCall.put(name, bytes);
		}
	}

	synchronized void forget() {
		names.clear();
		arguments.clear();
		largestByCall.clear();
	}

	synchronized long largestRead() {
		return largestReadExcept();
	}

	synchronized String largestReadCall() {
		return largestReadCallExcept();
	}

	synchronized long largestReadExcept(String... skipped) {
		String call = largestReadCallExcept(skipped);
		Long largest = largestByCall.get(call);
		return largest == null ? 0 : largest;
	}

	synchronized String largestReadCallExcept(String... skipped) {
		List<String> skip = Arrays.asList(skipped);
		String call = "none";
		long most = 0;
		for (Map.Entry<String, Long> e : largestByCall.entrySet()) {
			if (skip.contains(e.getKey())) continue;
			if (e.getValue() > most) {
				most = e.getValue();
				call = e.getKey();
			}
		}
		return call;
	}

	synchronized int callsExcept(String... skipped) {
		int count = 0;
		for (String name : names) {
			if (name.startsWith("transaction")) continue;
			boolean skip = false;
			for (String s : skipped) if (s.equals(name)) skip = true;
			if (!skip) count++;
		}
		return count;
	}

	synchronized List<Object[]> callsOf(String name) {
		List<Object[]> out = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).equals(name)) out.add(arguments.get(i));
		}
		return out;
	}

	static long sizeOf(@Nullable Object o) {
		if (o == null) return 0;
		if (o instanceof byte[]) return ((byte[]) o).length;
		if (o instanceof String) return ((String) o).length();
		if (o instanceof Bytes) return ((Bytes) o).getBytes().length;
		if (o instanceof MessageStatus) {
			return sizeOf(((MessageStatus) o).getMessageId());
		}
		if (o instanceof Message) {
			Message m = (Message) o;
			return sizeOf(m.getId()) + sizeOf(m.getGroupId())
					+ m.getBody().length;
		}
		long total = 0;
		if (o instanceof Map) {
			for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
				total += sizeOf(e.getKey()) + sizeOf(e.getValue());
			}
		} else if (o instanceof Collection) {
			for (Object e : (Collection<?>) o) total += sizeOf(e);
		}
		return total;
	}
}
