package com.professor.zerion.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.core.app.RemoteInput;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A quick reply typed into a notification is refused while the app is
 * locked, and a reply without a contact or without text is ignored, so no
 * message is composed and nothing is sent in either case.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class NotificationQuickReplyReceiverTest {

	/**
	 * The application installs a handler that ends the process on an uncaught
	 * exception; installing one here first makes the application's handler
	 * delegate to it in a debug build, so a background failure is recorded
	 * and reported instead of killing the test runtime.
	 */
	private static final List<Throwable> UNCAUGHT =
			Collections.synchronizedList(new ArrayList<>());

	static {
		TestAndroidKeyStore.register();
		Thread.setDefaultUncaughtExceptionHandler(
				(thread, throwable) -> UNCAUGHT.add(throwable));
	}

	private Context app;
	private LockManager lockManager;

	@Before
	public void setUp() {
		app = RuntimeEnvironment.getApplication();
		KeyguardManager keyguard = (KeyguardManager) app.getSystemService(
				Context.KEYGUARD_SERVICE);
		Shadows.shadowOf(keyguard).setIsDeviceSecure(true);
		lockManager = ((ZerionApplication) app).getApplicationComponent()
				.lockManager();
	}

	@After
	public void tearDown() {
		lockManager.setLocked(false);
		synchronized (UNCAUGHT) {
			if (!UNCAUGHT.isEmpty()) {
				Throwable first = UNCAUGHT.get(0);
				UNCAUGHT.clear();
				throw new AssertionError("a thread died: " + first, first);
			}
		}
	}

	@Test
	public void replyIsRefusedWhileLocked() {
		lockManager.setLocked(true);
		assertTrue(lockManager.isLocked());
		deliver(replyIntent("hello", 7));
		assertNoReplyStarted();
	}

	@Test
	public void replyWithoutAContactOrTextIsIgnored() {
		lockManager.setLocked(false);
		deliver(replyIntent("hello", -1));
		deliver(replyIntent("", 7));
		Intent noResults = new Intent(app, NotificationQuickReplyReceiver.class);
		noResults.putExtra(CONTACT_ID, 7);
		deliver(noResults);
		deliver(new Intent());
		assertNoReplyStarted();
	}

	private void deliver(Intent intent) {
		new NotificationQuickReplyReceiver().onReceive(app, intent);
	}

	private Intent replyIntent(String text, int contactId) {
		Intent intent = new Intent(app, NotificationQuickReplyReceiver.class);
		intent.putExtra(CONTACT_ID, contactId);
		Bundle results = new Bundle();
		results.putCharSequence(NotificationQuickReplyReceiver.KEY_REPLY_TEXT,
				text);
		RemoteInput.addResultsToIntent(new RemoteInput[] {
				new RemoteInput.Builder(
						NotificationQuickReplyReceiver.KEY_REPLY_TEXT).build()},
				intent, results);
		return intent;
	}

	/** The receiver composes replies on a thread it names; none may exist. */
	private static void assertNoReplyStarted() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			assertNotEquals("NotificationQuickReply", t.getName());
		}
	}
}
