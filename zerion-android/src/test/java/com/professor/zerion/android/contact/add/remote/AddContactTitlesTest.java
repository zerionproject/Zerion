package com.professor.zerion.android.contact.add.remote;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.appcompat.app.ActionBar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "nl")
public class AddContactTitlesTest {

	private static final String ADD_CONTACT = "Contact toevoegen";

	private static String title(AddContactActivity a) {
		shadowOf(Looper.getMainLooper()).idle();
		ActionBar bar = a.getSupportActionBar();
		assertNotNull(bar);
		return String.valueOf(bar.getTitle());
	}

	@Test
	public void theSendInviteScreenIsTitledLikeItsCard() {
		AddContactActivity a = Robolectric.buildActivity(
				AddContactActivity.class).setup().get();
		assertEquals("the chooser", ADD_CONTACT, title(a));

		a.showNextFragment(new SendInviteFragment());
		assertEquals("send invite matches the card", ADD_CONTACT, title(a));

		a.getSupportFragmentManager().popBackStackImmediate();
		assertEquals("back on the chooser", ADD_CONTACT, title(a));
	}

	@Test
	public void scanQrIsTitledAddContactLikeTheChooser() {
		int add = com.professor.zerion.R.string.add_contact_screen_title;
		assertEquals(add, AddContactActivity.titleFor(new QrExchangeFragment()));
		assertEquals(add, AddContactActivity.titleFor(com.professor.zerion
				.android.contact.add.nearby.AddNearbyContactFragment.newInstance(
				true)));
		assertEquals(add, AddContactActivity.titleFor(
				new AddContactChooserFragment()));
		assertEquals(com.professor.zerion.R.string.send_invite_title,
				AddContactActivity.titleFor(new SendInviteFragment()));
	}
}
