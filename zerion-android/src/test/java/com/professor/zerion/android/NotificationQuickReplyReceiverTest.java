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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.core.app.RemoteInput;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class NotificationQuickReplyReceiverTest {

	private static final List<Throwable> UNCAUGHT =
			Collections.synchronizedList(new ArrayList<>());

	static {
		TestAndroidKeyStore.register();
	}

	private Context app;
	private LockManager lockManager;
	private final List<NotificationQuickReplyReceiver> receivers =
			new ArrayList<>();
	private Set<Thread> replyThreadsBefore;
	private Thread.UncaughtExceptionHandler appHandler;

	@Before
	public void setUp() {
		replyThreadsBefore = replyThreads();
		app = RuntimeEnvironment.getApplication();
		KeyguardManager keyguard = (KeyguardManager) app.getSystemService(
				Context.KEYGUARD_SERVICE);
		Shadows.shadowOf(keyguard).setIsDeviceSecure(true);
		lockManager = ((ZerionApplication) app).getApplicationComponent()
				.lockManager();
		appHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(
				(thread, throwable) -> UNCAUGHT.add(throwable));
	}

	@After
	public void tearDown() {
		lockManager.setLocked(false);
		Thread.setDefaultUncaughtExceptionHandler(appHandler);
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
		NotificationQuickReplyReceiver receiver =
				new NotificationQuickReplyReceiver();
		receivers.add(receiver);
		receiver.onReceive(app, intent);
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

	private void assertNoReplyStarted() {
		for (NotificationQuickReplyReceiver receiver : receivers) {
			assertFalse("a reply was started",
					Shadows.shadowOf(receiver).wentAsync());
		}
		Set<Thread> started = replyThreads();
		started.removeAll(replyThreadsBefore);
		assertTrue("a reply thread was started: " + started,
				started.isEmpty());
	}

	private static Set<Thread> replyThreads() {
		Set<Thread> threads = new HashSet<>();
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if ("NotificationQuickReply".equals(t.getName())) threads.add(t);
		}
		return threads;
	}
}
