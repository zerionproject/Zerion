package com.professor.zerion.android.contact.request;

import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.core.api.contact.request.ContactRequestManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RestorePausePromptTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final RequestFixtures.Queue db = new RequestFixtures.Queue();
	private final ContactRequestManager requests =
			mock(ContactRequestManager.class);
	private RequestFixtures.Host host;

	@Before
	public void setUp() {
		ShadowDialog.reset();
		RestorePausePrompt.resetForTests();
		host = Robolectric.buildActivity(RequestFixtures.Host.class)
				.setup().get();
	}

	private AlertDialog prompt() {
		RestorePausePrompt.showOnce(host, requests, db);
		db.runAll();
		return RequestFixtures.latestAlert();
	}

	@Test
	public void aPausedAccountAsksWhetherThisIsTheOnlyDevice()
			throws Exception {
		when(requests.isPausedAfterRestore()).thenReturn(true);
		AlertDialog d = prompt();
		TextView message = d.findViewById(android.R.id.message);
		assertNotNull(message);
		String text = message.getText().toString();
		assertEquals("After a restore, requests pause so two phones on one "
				+ "account do not split them. Confirm to turn them back on.",
				text);
		assertEquals("Yes, continue",
				d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
		assertEquals("Not now",
				d.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		db.runAll();
		verify(requests).confirmOnlyActiveDevice();
	}

	@Test
	public void notNowStaysPausedAndTheQuestionIsAskedOncePerSession()
			throws Exception {
		when(requests.isPausedAfterRestore()).thenReturn(true);
		AlertDialog d = prompt();
		d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		db.runAll();
		verify(requests, never()).confirmOnlyActiveDevice();
		ShadowDialog.reset();
		RestorePausePrompt.showOnce(host, requests, db);
		db.runAll();
		assertNull(ShadowDialog.getLatestDialog());
	}

	@Test
	public void anAccountThatIsNotPausedIsNotAsked() throws Exception {
		when(requests.isPausedAfterRestore()).thenReturn(false);
		RestorePausePrompt.showOnce(host, requests, db);
		db.runAll();
		assertNull(ShadowDialog.getLatestDialog());
	}
}
