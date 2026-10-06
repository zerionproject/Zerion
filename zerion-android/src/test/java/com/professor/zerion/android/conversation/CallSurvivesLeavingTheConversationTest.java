package com.professor.zerion.android.conversation;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.conversation.voice.VoiceCallService;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class CallSurvivesLeavingTheConversationTest {

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
	public void stoppingTheConversationDoesNotStopTheCallService() {
		LockManager lock = mock(LockManager.class);
		when(lock.isLocked()).thenReturn(false);
		when(lock.isLockable()).thenReturn(new MutableLiveData<>(false));
		ProbeConversation.notifications = mock(AndroidNotificationManager.class);
		ProbeConversation.lock = lock;
		Application app = ApplicationProvider.getApplicationContext();

		Intent i = new Intent(app, ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, CONTACT);
		ActivityController<ProbeConversation> controller =
				Robolectric.buildActivity(ProbeConversation.class, i).setup();
		shadowOf(Looper.getMainLooper()).idle();
		shadowOf(app).clearStartedServices();

		controller.pause().stop();
		shadowOf(Looper.getMainLooper()).idle();

		Intent stopped;
		while ((stopped = shadowOf(app).getNextStoppedService()) != null) {
			assertNull("the conversation screen stopped the call service",
					stopped.getComponent() != null
							&& VoiceCallService.class.getName().equals(
							stopped.getComponent().getClassName())
							? stopped : null);
		}
	}
}
