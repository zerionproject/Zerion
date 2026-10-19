package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelRejoinResult;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.SecretKey;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelInviteHandlerPromptTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final String ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	public static class ProbeHandler extends ChannelInviteHandlerActivity {

		static final List<Runnable> io = new ArrayList<>();
		static ChannelManager manager;
		static AccountManager account;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			accountManager = account;
			ioExecutor = io::add;
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final byte[] id = new byte[32];
	private ChannelManager manager;

	@Before
	public void setUp() throws Exception {
		ProbeHandler.io.clear();
		manager = mock(ChannelManager.class);
		ProbeHandler.manager = manager;
		AccountManager account = mock(AccountManager.class);
		when(account.getDatabaseKey()).thenReturn(new SecretKey(new byte[32]));
		ProbeHandler.account = account;
		ChannelInviteLink link = new ChannelInviteLink(id, new byte[32],
				null, false, null, ONION, true);
		when(manager.parseInviteLink(anyString())).thenReturn(link);
		ChannelState held = new ChannelState(id, new byte[16], new byte[32],
				new byte[0], "News", "", null, HOUR, false, new byte[32],
				ONION, 1L, false, 0L);
		when(manager.getChannel(any())).thenReturn(held);
		when(manager.rejoinChannel(any()))
				.thenReturn(ChannelRejoinResult.UNCHANGED);
	}

	@Test
	public void aLinkThatWasNotKeptSaysSo() {
		org.junit.Assert.assertEquals(
				com.professor.zerion.R.string.channels_rejoin_too_many_links,
				ChannelInviteHandlerActivity.rejoinMessage(
						ChannelRejoinResult.TOO_MANY_LINKS));
	}

	@Test
	public void aHeldChannelsApprovalLinkAsksBeforeAnyRequest()
			throws Exception {
		Intent intent = new Intent(ctx, ProbeHandler.class);
		intent.setData(Uri.parse("zerion://channel/a/b?o=" + ONION + "&p=1"));
		Robolectric.buildActivity(ProbeHandler.class, intent).setup();
		runBackgroundWork();

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("nothing was asked", dialog);
		assertTrue(dialog.isShowing());
		assertNothingAsked();

		((androidx.appcompat.app.AlertDialog) dialog)
				.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runBackgroundWork();
		verify(manager).rejoinChannel(any());
		verify(manager, never()).rejoinChannelThroughLink(any());
	}

	@Test
	public void joiningANewChannelAsksPlainlyWithoutAKey() throws Exception {
		byte[] publisher = new byte[32];
		for (int i = 0; i < publisher.length; i++) publisher[i] = (byte) 0xab;
		ChannelInviteLink link = new ChannelInviteLink(id, publisher, null,
				true, null, ONION, false);
		when(manager.parseInviteLink(anyString())).thenReturn(link);
		when(manager.getChannel(any())).thenReturn(null);
		Intent intent = new Intent(ctx, ProbeHandler.class);
		intent.setData(Uri.parse("zerion://channel/a/b?o=" + ONION));
		Robolectric.buildActivity(ProbeHandler.class, intent).setup();
		runBackgroundWork();

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("nothing was asked", dialog);
		android.widget.TextView message =
				dialog.findViewById(android.R.id.message);
		assertNotNull(message);
		String text = message.getText().toString();
		org.junit.Assert.assertEquals("Join this channel?", text);
		org.junit.Assert.assertFalse(text,
				text.matches("(?s).*\\b[0-9a-f]{4}\\b.*"));
		assertNothingAsked();
	}

	@Test
	public void anEmptyNameAsksForTheNameTheOwnerSees() throws Exception {
		when(manager.getChannel(any())).thenReturn(null);
		Intent intent = new Intent(ctx, ProbeHandler.class);
		intent.setData(Uri.parse("zerion://channel/a/b?o=" + ONION + "&p=1"));
		Robolectric.buildActivity(ProbeHandler.class, intent).setup();
		runBackgroundWork();

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("nothing was asked", dialog);
		assertNotNull("not the request dialog", dialog.findViewById(
				com.professor.zerion.R.id.channelApplyNameInput));
		((androidx.appcompat.app.AlertDialog) dialog)
				.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runBackgroundWork();
		org.junit.Assert.assertEquals("Enter the name the owner will see.",
				org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
		assertNothingAsked();
	}

	private void assertNothingAsked() throws Exception {
		verify(manager, never()).rejoinChannel(any());
		verify(manager, never()).rejoinChannelThroughLink(any());
		verify(manager, never()).joinChannel(any());
		verify(manager, never()).applyToJoin(any(ChannelInviteLink.class),
				anyString());
		verify(manager, never()).applyToJoin(any(byte[].class), anyString());
		verify(manager, never()).bootstrapChannel(any());
		verify(manager, never()).refreshChannel(any());
		verify(manager, never()).refreshChannelOnOpen(any());
	}

	private static void runBackgroundWork() {
		for (int round = 0; round < 6; round++) {
			shadowOf(Looper.getMainLooper()).idle();
			if (ProbeHandler.io.isEmpty()) break;
			List<Runnable> pending = new ArrayList<>(ProbeHandler.io);
			ProbeHandler.io.clear();
			for (Runnable r : pending) {
				try {
					r.run();
				} catch (RuntimeException ignored) {
				}
			}
		}
		shadowOf(Looper.getMainLooper()).idle();
	}
}
