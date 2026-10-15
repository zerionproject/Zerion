package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Authorization;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.OwnRotation;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.PeerAddress;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Reach;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Tor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ContactConnectionSectionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final Pattern ONION = Pattern.compile("[a-z2-7]{56}");

	private static View inflate() {
		Context ctx = new ContextThemeWrapper(
				ApplicationProvider.getApplicationContext(),
				R.style.ZerionTheme);
		return LayoutInflater.from(ctx).inflate(
				R.layout.chat_settings_connection, null, false);
	}

	private static List<String> shown(LinearLayout rows) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < rows.getChildCount(); i++) {
			TextView row = (TextView) rows.getChildAt(i);
			if (row.getVisibility() == View.VISIBLE) {
				out.add(row.getText().toString());
			}
		}
		return out;
	}

	@Test
	public void theSectionRendersEveryRowAsOneSentence() {
		View card = inflate();
		TextView title = card.findViewById(R.id.connection_title);
		assertEquals("Connection", title.getText().toString());
		assertTrue(title.isAccessibilityHeading());
		LinearLayout rows = card.findViewById(R.id.connection_rows);
		ContactConnectionSection section = new ContactConnectionSection(rows);

		section.show(ContactConnectionLines.lines(
				new ContactConnectionSnapshot(Tor.CONNECTED,
						Reach.RETRY_LATER, 41_000, 125_000,
						PeerAddress.NEW_RETRY_LATER, 300_000,
						OwnRotation.CONTACT_NOT_MOVED,
						Authorization.REPAIRING, 3, 2, 5), true));

		assertEquals(Arrays.asList(
				"Tor is connected.",
				"Your address is published.",
				"Waiting for contact, next attempt in about 40 seconds.",
				"Last connected about 2 minutes ago.",
				"Contact's new address did not answer, retrying in about 5 minutes.",
				"Your address is changing, this contact has not moved yet.",
				"Client authorization repair in progress, attempt 3.",
				"Address generation: yours 2, contact's 5."), shown(rows));
		float density = card.getResources().getDisplayMetrics().density;
		for (int i = 0; i < rows.getChildCount(); i++) {
			TextView row = (TextView) rows.getChildAt(i);
			assertTrue(row.getMinHeight() >= Math.round(48 * density));
			assertTrue(row.isScreenReaderFocusable());
			assertFalse(ONION.matcher(row.getText()).find());
			assertFalse(row.getText().toString().contains(".onion"));
		}
	}

	@Test
	public void rowsAreReusedAndHiddenWhenTheStateShrinks() {
		View card = inflate();
		LinearLayout rows = card.findViewById(R.id.connection_rows);
		ContactConnectionSection section = new ContactConnectionSection(rows);
		section.show(ContactConnectionLines.lines(
				new ContactConnectionSnapshot(Tor.CONNECTED, Reach.RETRY_SOON,
						UNKNOWN, UNKNOWN, PeerAddress.NEW_ANNOUNCED, UNKNOWN,
						OwnRotation.PREPARING, Authorization.ACTIVE, 0, 1, 1),
				false));
		assertEquals(Arrays.asList(
				"Tor is connected.",
				"Your address is still being published.",
				"Waiting for contact, next attempt in a few seconds.",
				"Not connected since Zerion started.",
				"Contact announced a new address, trying it.",
				"Your address is changing, the new one is not announced yet.",
				"Client authorization is active.",
				"Address generation: yours 1, contact's 1."), shown(rows));
		View first = rows.getChildAt(0);

		section.show(ContactConnectionLines.lines(
				new ContactConnectionSnapshot(Tor.OFFLINE_MODE, Reach.ONLINE,
						UNKNOWN, UNKNOWN, PeerAddress.CURRENT, UNKNOWN,
						OwnRotation.IDLE, Authorization.NOT_SUPPORTED, 0, 0,
						0), false));
		assertEquals(Arrays.asList(
				"Offline mode is on, so Tor is stopped.",
				"Contact is online now.",
				"Client authorization is not supported by this contact."),
				shown(rows));
		assertSame(first, rows.getChildAt(0));
		assertEquals(8, rows.getChildCount());
	}

	@Test
	public void aLockedInContactShowsRenewalAndRepairDials() {
		View card = inflate();
		LinearLayout rows = card.findViewById(R.id.connection_rows);
		ContactConnectionSection section = new ContactConnectionSection(rows);
		section.show(ContactConnectionLines.lines(
				new ContactConnectionSnapshot(Tor.CONNECTED, Reach.ONLINE,
						UNKNOWN, UNKNOWN, PeerAddress.CURRENT, UNKNOWN,
						OwnRotation.IDLE, Authorization.RENEWING, 0, 3, 4),
				true));
		assertTrue(shown(rows).contains(
				"Client authorization is being renewed with this contact."));
		section.show(ContactConnectionLines.lines(
				new ContactConnectionSnapshot(Tor.CONNECTED, Reach.ONLINE,
						UNKNOWN, UNKNOWN, PeerAddress.CURRENT, UNKNOWN,
						OwnRotation.IDLE, Authorization.REACHING, 2, 3, 4),
				true));
		assertTrue(shown(rows).contains(
				"Trying to reach this contact through repair, attempt 2."));
		assertFalse(shown(rows).contains(
				"Client authorization is being renewed with this contact."));
	}

	@Test
	public void durationsAreRoundedToFriendlyUnits() {
		assertEquals("5 seconds",
				ContactConnectionLines.duration(Locale.ENGLISH, 0));
		assertEquals("5 seconds",
				ContactConnectionLines.duration(Locale.ENGLISH, 3_000));
		assertEquals("40 seconds",
				ContactConnectionLines.duration(Locale.ENGLISH, 41_000));
		assertEquals("1 minute",
				ContactConnectionLines.duration(Locale.ENGLISH, 58_000));
		assertEquals("2 minutes",
				ContactConnectionLines.duration(Locale.ENGLISH, 125_000));
		assertEquals("59 minutes",
				ContactConnectionLines.duration(Locale.ENGLISH, 3_540_000));
		assertEquals("3 hours",
				ContactConnectionLines.duration(Locale.ENGLISH, 3 * 3_600_000));
	}
}
