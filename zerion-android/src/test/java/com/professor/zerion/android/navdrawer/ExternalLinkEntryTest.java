package com.professor.zerion.android.navdrawer;

import android.content.Intent;
import android.net.Uri;

import com.professor.zerion.android.channel.ChannelInviteHandlerActivity;
import com.professor.zerion.android.contact.add.remote.AddContactActivity;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ExternalLinkEntryTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static String contactLink() {
		StringBuilder sb = new StringBuilder("zerion://");
		for (int i = 0; i < 53; i++) sb.append('a');
		return sb.toString();
	}

	private static Intent started(Intent sent) {
		ExternalLinkActivity a = Robolectric.buildActivity(
				ExternalLinkActivity.class, sent).create().get();
		assertTrue("the entry has no window and finishes at once",
				a.isFinishing());
		return shadowOf(a).getNextStartedActivity();
	}

	@Test
	public void aChannelInviteOpensTheInviteScreenInItsOwnTask() {
		Intent next = started(new Intent(Intent.ACTION_VIEW,
				Uri.parse("zerion://channel/abc?cap=x")));
		assertNotNull(next);
		assertEquals(ChannelInviteHandlerActivity.class.getName(),
				next.getComponent().getClassName());
		assertTrue((next.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
	}

	@Test
	public void aContactLinkOpensAddContactInItsOwnTask() {
		Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(contactLink()));
		Intent next = started(view);
		assertNotNull(next);
		assertEquals(AddContactActivity.class.getName(),
				next.getComponent().getClassName());
		assertTrue((next.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
	}

	@Test
	public void anythingElseOpensNothing() {
		assertNull(started(new Intent()));
		assertNull(started(new Intent(Intent.ACTION_VIEW,
				Uri.parse("zerion://unknown/thing"))));
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("text/plain");
		send.putExtra(Intent.EXTRA_TEXT, "hello");
		assertNull(started(send));
		StringBuilder huge = new StringBuilder("zerion://channel/");
		for (int i = 0; i < 4096; i++) huge.append('a');
		assertNull(started(new Intent(Intent.ACTION_VIEW,
				Uri.parse(huge.toString()))));
	}

	@Test
	public void theJoinQuestionDoesNotShowTheLink() throws Exception {
		String s = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/channel/"
						+ "ChannelInviteHandlerActivity.java")),
				StandardCharsets.UTF_8);
		assertFalse("the join question shows the raw link",
				s.contains(".setMessage(data.toString())"));
	}
}
