package com.professor.zerion.android;

import android.content.Context;

import com.professor.zerion.android.conversation.ConversationCache;
import com.professor.zerion.android.message.TestPendingSends;
import com.professor.zerion.android.panic.TestPanicStatics;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.security.TestZerionEncryptedPrefs;
import com.professor.zerion.android.settings.ChatPreferences;
import com.professor.zerion.android.util.TestUtilStatics;

import org.robolectric.TestLifecycleApplication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Provider;
import java.security.Security;
import java.util.Map;

public class TestZerionApplicationImpl extends ZerionApplicationImpl
		implements TestLifecycleApplication {

	private static final long STARTUP_TIMEOUT_MS = 60_000;
	private static final String QUEUED_WORK_THREAD = "queued-work-looper";

	private static volatile TestZerionApplicationImpl earlier;

	@Override
	protected void attachBaseContext(Context base) {
		shutDownEarlierApplication();
		earlier = this;
		TestAndroidKeyStore.register();
		removeSecureRandomProvidersOfEarlierTests();
		AppModule.SecurePrefsHolder.resetForTests();
		TestZerionEncryptedPrefs.reset();
		EarlyPrefs.resetForTests();
		TestUtilStatics.reset();
		TestPanicStatics.reset();
		ConversationCache.getInstance().clearAll();
		TestPendingSends.reset();
		ChatPreferences.invalidateGuiFontScale();
		super.attachBaseContext(base);
	}

	private static void shutDownEarlierApplication() {
		TestZerionApplicationImpl app = earlier;
		earlier = null;
		if (app != null) app.shutDownForTests();
	}

	void shutDownForTests() {
		try {
			awaitEagerSingletons(STARTUP_TIMEOUT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		AndroidComponent component = getApplicationComponent();
		if (component != null) {
			component.torSocksGate().close();
			component.meshTextSender().shutDown();
			component.meshPresenceTracker().shutDown();
			component.meshAttachmentSender().shutDown();
			component.meshGroupSender().shutDown();
			component.voiceCallConnectionManager().shutdown();
		}
		for (Map.Entry<Thread, StackTraceElement[]> e
				: Thread.getAllStackTraces().entrySet()) {
			Thread t = e.getKey();
			if (t instanceof android.os.HandlerThread
					&& QUEUED_WORK_THREAD.equals(t.getName())) {
				android.os.Looper looper =
						((android.os.HandlerThread) t).getLooper();
				if (looper != null) looper.quit();
			} else if (waitsForTheDatabase(e.getValue())) {
				t.interrupt();
			}
		}
		forgetListsOfEarlierScreens();
	}

	private static boolean waitsForTheDatabase(StackTraceElement[] stack) {
		for (StackTraceElement frame : stack) {
			if (frame.getMethodName().equals("waitForDatabase")
					&& frame.getClassName().endsWith("LifecycleManagerImpl")) {
				return true;
			}
		}
		return false;
	}

	static void forgetListsOfEarlierScreens() {
		try {
			Class<?> worker = Class.forName(
					"androidx.recyclerview.widget.GapWorker");
			Field holder = worker.getDeclaredField("sGapWorker");
			holder.setAccessible(true);
			Object current = ((ThreadLocal<?>) holder.get(null)).get();
			if (current == null) return;
			Field lists = worker.getDeclaredField("mRecyclerViews");
			lists.setAccessible(true);
			((java.util.Collection<?>) lists.get(current)).clear();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	static int listsOfEarlierScreens() {
		try {
			Class<?> worker = Class.forName(
					"androidx.recyclerview.widget.GapWorker");
			Field holder = worker.getDeclaredField("sGapWorker");
			holder.setAccessible(true);
			Object current = ((ThreadLocal<?>) holder.get(null)).get();
			if (current == null) return 0;
			Field lists = worker.getDeclaredField("mRecyclerViews");
			lists.setAccessible(true);
			return ((java.util.Collection<?>) lists.get(current)).size();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void removeSecureRandomProvidersOfEarlierTests() {
		Provider[] installed = Security.getProviders("SecureRandom.SHA1PRNG");
		if (installed == null) return;
		for (Provider p : installed) {
			if (p.getClass().getClassLoader() != null) {
				Security.removeProvider(p.getName());
			}
		}
	}

	@Override
	public void beforeTest(Method method) {
		try {
			if (!awaitEagerSingletons(STARTUP_TIMEOUT_MS)) {
				throw new AssertionError(
						"the application did not finish starting up");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	@Override
	public void prepareTest(Object test) {
	}

	@Override
	public void afterTest(Method method) {
		org.mockito.Mockito.framework().clearInlineMocks();
	}
}
