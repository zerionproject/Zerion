package com.professor.zerion.android.util;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.professor.zerion.android.security.KeystoreUnavailableActivity;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

final class FocusHost implements AutoCloseable {

	static final String TOKEN_PREFIX = "zerion-device-test-";

	private final Instrumentation instrumentation;
	private final Activity activity;

	private FocusHost(Instrumentation instrumentation, Activity activity) {
		this.instrumentation = instrumentation;
		this.activity = activity;
	}

	static FocusHost open() {
		Instrumentation inst = InstrumentationRegistry.getInstrumentation();
		Intent i = new Intent(inst.getTargetContext(),
				KeystoreUnavailableActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
						| Intent.FLAG_ACTIVITY_NO_ANIMATION);
		Activity a = inst.startActivitySync(i);
		long deadline = SystemClock.uptimeMillis() + 10_000;
		while (!a.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) {
			inst.waitForIdleSync();
			SystemClock.sleep(50);
		}
		assertTrue("no window of the app got focus; unlock the phone and keep"
				+ " the screen on while the test runs", a.hasWindowFocus());
		return new FocusHost(inst, a);
	}

	static FocusHost returnFromBackground() {
		Instrumentation inst = InstrumentationRegistry.getInstrumentation();
		String pkg = inst.getTargetContext().getPackageName();
		ParcelFileDescriptor out = inst.getUiAutomation().executeShellCommand(
				"monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
		try (InputStream in =
				new ParcelFileDescriptor.AutoCloseInputStream(out)) {
			while (in.read() != -1) {
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		long deadline = SystemClock.uptimeMillis() + 20_000;
		Activity focused = null;
		while (focused == null && SystemClock.uptimeMillis() < deadline) {
			inst.waitForIdleSync();
			focused = focusedActivity(inst);
			if (focused == null) SystemClock.sleep(50);
		}
		assertTrue("no window of the app got focus after returning to it;"
				+ " unlock the phone and keep the screen on", focused != null);
		return new FocusHost(inst, focused);
	}

	private static Activity focusedActivity(Instrumentation inst) {
		AtomicReference<Activity> found = new AtomicReference<>();
		inst.runOnMainSync(() -> {
			Collection<Activity> resumed = ActivityLifecycleMonitorRegistry
					.getInstance().getActivitiesInStage(Stage.RESUMED);
			for (Activity a : resumed) {
				if (a.hasWindowFocus()) found.set(a);
			}
		});
		return found.get();
	}

	Context context() {
		return instrumentation.getTargetContext();
	}

	ClipboardManager clipboard() {
		return (ClipboardManager) context().getSystemService(
				Context.CLIPBOARD_SERVICE);
	}

	String clipText() {
		ClipData clip = clipboard().getPrimaryClip();
		if (clip == null || clip.getItemCount() == 0) return null;
		CharSequence text = clip.getItemAt(0).getText();
		return text == null ? null : text.toString();
	}

	void clearTestClips() {
		String text = clipText();
		if (text != null && text.startsWith(TOKEN_PREFIX)) {
			clipboard().clearPrimaryClip();
		}
	}

	void leave() {
		context().startActivity(new Intent(Intent.ACTION_MAIN)
				.addCategory(Intent.CATEGORY_HOME)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		long deadline = SystemClock.uptimeMillis() + 10_000;
		while (activity.hasWindowFocus()
				&& SystemClock.uptimeMillis() < deadline) {
			SystemClock.sleep(50);
		}
		assertFalse("the app kept the focus", activity.hasWindowFocus());
	}

	void onMain(Runnable r) {
		instrumentation.runOnMainSync(r);
	}

	@Override
	public void close() {
		instrumentation.runOnMainSync(activity::finish);
		instrumentation.waitForIdleSync();
	}
}
