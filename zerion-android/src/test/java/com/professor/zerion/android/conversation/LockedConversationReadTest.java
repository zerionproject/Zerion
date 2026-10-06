package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.contact.ContactId;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LockedConversationReadTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int CONTACT = 5;

	public static class ProbeConversation extends ConversationActivity {

		static AndroidNotificationManager notifications;
		static LockManager lock;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			notificationManager = notifications;
			lockManager = lock;
		}
	}

	@Test
	public void aLockedAppDoesNotReadTheConversation() {
		AndroidNotificationManager n = mock(AndroidNotificationManager.class);
		LockManager lock = mock(LockManager.class);
		when(lock.isLocked()).thenReturn(true);
		when(lock.isLockable()).thenReturn(new MutableLiveData<>(true));
		ProbeConversation.notifications = n;
		ProbeConversation.lock = lock;

		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, CONTACT);
		Robolectric.buildActivity(ProbeConversation.class, i).setup();
		shadowOf(Looper.getMainLooper()).idle();

		verify(n, never()).clearContactNotification(any(ContactId.class));
		verify(n, never()).blockContactNotification(any(ContactId.class));
	}

	@Test
	public void anUnlockedAppReadsTheConversation() {
		AndroidNotificationManager n = mock(AndroidNotificationManager.class);
		LockManager lock = mock(LockManager.class);
		when(lock.isLocked()).thenReturn(false);
		when(lock.isLockable()).thenReturn(new MutableLiveData<>(false));
		ProbeConversation.notifications = n;
		ProbeConversation.lock = lock;

		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, CONTACT);
		Robolectric.buildActivity(ProbeConversation.class, i).setup();
		shadowOf(Looper.getMainLooper()).idle();

		verify(n).clearContactNotification(new ContactId(CONTACT));
	}
}
