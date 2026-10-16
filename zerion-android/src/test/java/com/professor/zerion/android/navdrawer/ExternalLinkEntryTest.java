package com.professor.zerion.android.navdrawer;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
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

	private static final String[] SCHEMES = {
			"Zerion", "ZERION", "zErIoN", "zerioN"
	};

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
	public void anyCaseOfTheSchemeOpensTheInviteWithTheLowercaseScheme() {
		for (String scheme : SCHEMES) {
			Intent next = started(new Intent(Intent.ACTION_VIEW,
					Uri.parse(scheme + "://channel/abc?cap=x")));
			assertNotNull(scheme, next);
			assertEquals(ChannelInviteHandlerActivity.class.getName(),
					next.getComponent().getClassName());
			assertEquals(scheme, "zerion://channel/abc?cap=x",
					next.getDataString());
		}
	}

	@Test
	public void anyCaseOfTheSchemeOpensAddContactWithTheLowercaseScheme() {
		String lower = contactLink();
		String rest = lower.substring("zerion".length());
		for (String scheme : SCHEMES) {
			Intent next = started(new Intent(Intent.ACTION_VIEW,
					Uri.parse(scheme + rest)));
			assertNotNull(scheme, next);
			assertEquals(AddContactActivity.class.getName(),
					next.getComponent().getClassName());
			assertEquals(scheme, lower, next.getDataString());

			Intent send = new Intent(Intent.ACTION_SEND);
			send.setType("text/plain");
			send.putExtra(Intent.EXTRA_TEXT, scheme + rest);
			Intent shared = started(send);
			assertNotNull(scheme, shared);
			assertEquals(AddContactActivity.class.getName(),
					shared.getComponent().getClassName());
			assertEquals(scheme, lower,
					shared.getStringExtra(Intent.EXTRA_TEXT));
		}
	}

	@Test
	public void aLookalikeSchemeOpensNothing() {
		assertNull(started(new Intent(Intent.ACTION_VIEW,
				Uri.parse("zer\u0131on://channel/abc?cap=x"))));
		assertNull(started(new Intent(Intent.ACTION_VIEW,
				Uri.parse("ZER\u0130ON" + contactLink().substring(6)))));
	}

	@Test
	public void androidHandsEveryCaseOfTheSchemeToTheLinkEntry() {
		PackageManager pm = ApplicationProvider.getApplicationContext()
				.getPackageManager();
		for (int mask = 0; mask < 64; mask++) {
			StringBuilder scheme = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				char c = "zerion".charAt(i);
				scheme.append((mask & (1 << i)) != 0
						? Character.toUpperCase(c) : c);
			}
			Intent view = new Intent(Intent.ACTION_VIEW,
					Uri.parse(scheme + "://channel/abc"));
			view.addCategory(Intent.CATEGORY_BROWSABLE);
			List<ResolveInfo> found = pm.queryIntentActivities(view, 0);
			boolean entry = false;
			for (ResolveInfo r : found) {
				if (ExternalLinkActivity.class.getName()
						.equals(r.activityInfo.name)) {
					entry = true;
				}
			}
			assertTrue(scheme.toString(), entry);
		}
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
