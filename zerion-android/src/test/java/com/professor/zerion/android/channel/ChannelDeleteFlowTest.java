package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.app.api.channel.ChannelDeleteNotSignedException;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.db.DbException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelDeleteFlowTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class Host extends AppCompatActivity {

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(savedInstanceState);
		}
	}

	private final List<Runnable> io = new ArrayList<>();
	private final byte[] id = new byte[32];
	private ChannelManager manager;
	private AccountManager accounts;
	private Host host;
	private org.robolectric.android.controller.ActivityController<Host>
			controller;
	private int deleted;
	private final List<String> notDeleted = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		ShadowDialog.reset();
		ShadowToast.reset();
		manager = mock(ChannelManager.class);
		accounts = mock(AccountManager.class);
		when(manager.getChannel(any())).thenReturn(mock(ChannelState.class));
		controller = Robolectric.buildActivity(Host.class).setup();
		host = controller.get();
	}

	private void start() {
		new ChannelDeleteFlow(host, manager, accounts, io::add, id,
				new ChannelDeleteFlow.Listener() {
					@Override
					public void onDeleted() {
						deleted++;
					}

					@Override
					public void onNotDeleted(CharSequence reason) {
						notDeleted.add(reason.toString());
					}
				}).start("News");
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private void runBackgroundWork() {
		for (int round = 0; round < 4 && !io.isEmpty(); round++) {
			List<Runnable> pending = new ArrayList<>(io);
			io.clear();
			for (Runnable r : pending) r.run();
			idle();
		}
		idle();
	}

	private AlertDialog wordStep() {
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue("no confirmation", d instanceof AlertDialog);
		return (AlertDialog) d;
	}

	private BottomSheetDialog typeTheWordAndContinue() {
		AlertDialog dialog = wordStep();
		EditText word = dialog.findViewById(R.id.channelDeleteWordInput);
		assertNotNull(word);
		word.setText("DELETE");
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		Dialog sheet = ShadowDialog.getLatestDialog();
		assertTrue("no password step", sheet instanceof BottomSheetDialog);
		assertTrue(sheet.isShowing());
		return (BottomSheetDialog) sheet;
	}

	private void enterPassword(BottomSheetDialog sheet, String password) {
		EditText field = sheet.findViewById(R.id.channelDeleteAuthPassword);
		assertNotNull(field);
		field.setText(password);
		Button confirm = sheet.findViewById(R.id.channelDeleteAuthConfirm);
		assertNotNull(confirm);
		assertTrue(confirm.isEnabled());
		confirm.performClick();
		idle();
		runBackgroundWork();
	}

	private static CharSequence error(BottomSheetDialog sheet) {
		TextInputLayout layout = sheet.findViewById(
				R.id.channelDeleteAuthPasswordLayout);
		assertNotNull(layout);
		return layout.getError();
	}

	private void assertNothingDeleted() throws Exception {
		verify(manager, never()).deleteChannelForEveryone(any());
		verify(manager, never()).deleteChannel(any());
		verify(manager, never()).leaveChannel(any());
		assertEquals(0, deleted);
	}

	@Test
	public void continueWaitsForTheWordDelete() throws Exception {
		start();
		AlertDialog dialog = wordStep();
		Button go = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
		EditText word = dialog.findViewById(R.id.channelDeleteWordInput);
		assertFalse(go.isEnabled());
		word.setText("DELET");
		assertFalse(go.isEnabled());
		word.setText("delete for me");
		assertFalse(go.isEnabled());
		word.setText(" DELETE ");
		assertTrue(go.isEnabled());
		go.performClick();
		idle();
		assertTrue(ShadowDialog.getLatestDialog() instanceof BottomSheetDialog);
		assertNothingDeleted();
	}

	private static final String WHEN = "subscribers' phones remove %s when"
			+ " they check for posts while this phone runs Zerion and is"
			+ " connected.";
	private static final String DONE = "Channel deleted. For the next 14"
			+ " days, " + String.format(WHEN, "it");

	@Test
	public void theDoneMessageRepeatsTheWarningExactly() throws Exception {
		start();
		String warning = String.valueOf(((android.widget.TextView) wordStep()
				.findViewById(android.R.id.message)).getText());
		assertTrue(warning, warning.contains("For the next 14 days, "
				+ String.format(WHEN, "the channel")));
		Context ctx = ApplicationProvider.getApplicationContext();
		assertEquals(DONE, ctx.getString(R.string.channels_delete_done));
		assertTrue(ctx.getString(R.string.channels_delete_done_pending)
				.startsWith(DONE + " "));
	}

	@Test
	public void theWarningSaysWhatHappensToSubscribers() {
		start();
		View message = wordStep().findViewById(android.R.id.message);
		String text = String.valueOf(((android.widget.TextView) message)
				.getText());
		assertTrue(text, text.contains("14 days"));
		assertTrue(text, text.contains("keeps its copy"));
		assertTrue(text, text.contains("cannot be undone"));
	}

	@Test
	public void cancellingThePasswordStepDeletesNothing() throws Exception {
		start();
		BottomSheetDialog sheet = typeTheWordAndContinue();
		Button cancel = sheet.findViewById(R.id.channelDeleteAuthCancel);
		assertNotNull(cancel);
		cancel.performClick();
		idle();
		assertFalse(sheet.isShowing());
		runBackgroundWork();
		assertNothingDeleted();
		verify(accounts, never()).verifyPassword(any());
	}

	@Test
	public void aWrongPasswordDeletesNothingAndCanBeTriedAgain()
			throws Exception {
		doThrow(new DecryptionException(DecryptionResult.INVALID_PASSWORD))
				.when(accounts).verifyPassword(any());
		start();
		BottomSheetDialog sheet = typeTheWordAndContinue();
		enterPassword(sheet, "not my password");
		assertTrue(sheet.isShowing());
		assertEquals("Wrong password. Nothing was deleted.",
				String.valueOf(error(sheet)));
		EditText field = sheet.findViewById(R.id.channelDeleteAuthPassword);
		assertEquals(0, field.length());
		assertTrue(field.isEnabled());
		assertNothingDeleted();
		assertTrue(notDeleted.isEmpty());
	}

	@Test
	public void theRightPasswordDeletesForEveryone() throws Exception {
		when(manager.getChannel(any())).thenReturn(null);
		when(manager.hasDeletionNotice(any())).thenReturn(true);
		start();
		BottomSheetDialog sheet = typeTheWordAndContinue();
		enterPassword(sheet, "my password");
		verify(accounts).verifyPassword(any());
		verify(manager).deleteChannelForEveryone(id);
		verify(manager, never()).deleteChannel(any());
		verify(manager, never()).leaveChannel(any());
		assertFalse(sheet.isShowing());
		assertEquals(1, deleted);
		assertEquals(DONE, ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void anUnsignedDeleteSaysNothingWasRemoved() throws Exception {
		AlertDialog offer = unsignedDelete();
		String message = message(offer);
		assertTrue(message, message.startsWith("Could not delete the channel"
				+ " for everyone. Nothing was removed."));
		assertTrue(message, message.contains("Subscribers are not told, keep"
				+ " their copy and get no new posts."));
		assertEquals("Remove from this phone only", String.valueOf(
				offer.getButton(AlertDialog.BUTTON_POSITIVE).getText()));
		verify(manager).deleteChannelForEveryone(id);
		verify(manager, never()).deleteChannel(any());
		verify(manager, never()).leaveChannel(any());
		assertEquals(0, deleted);
		assertFalse("a success was reported",
				String.valueOf(ShadowToast.getTextOfLatestToast())
						.startsWith("Channel deleted"));
	}

	private AlertDialog unsignedDelete() throws Exception {
		doThrow(new ChannelDeleteNotSignedException()).when(manager)
				.deleteChannelForEveryone(any());
		start();
		enterPassword(typeTheWordAndContinue(), "my password");
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue("no offer: " + d, d instanceof AlertDialog);
		return (AlertDialog) d;
	}

	private static String message(AlertDialog d) {
		android.widget.TextView t = d.findViewById(android.R.id.message);
		assertNotNull(t);
		return String.valueOf(t.getText());
	}

	@Test
	public void keepingTheChannelAfterTheOfferRemovesNothing()
			throws Exception {
		AlertDialog offer = unsignedDelete();
		assertEquals("Keep the channel", String.valueOf(
				offer.getButton(AlertDialog.BUTTON_NEGATIVE).getText()));
		offer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idle();
		runBackgroundWork();
		verify(manager, never()).deleteChannel(any());
		assertEquals(0, deleted);
	}

	@Test
	public void removingFromThisPhoneAsksForDeleteAndThePasswordAgain()
			throws Exception {
		AlertDialog offer = unsignedDelete();
		offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		verify(manager, never()).deleteChannel(any());
		AlertDialog word = wordStep();
		assertTrue(word != offer);
		String explain = message(word);
		assertTrue(explain, explain.contains("Subscribers are not told"));
		Button go = word.getButton(AlertDialog.BUTTON_POSITIVE);
		assertFalse(go.isEnabled());
		((EditText) word.findViewById(R.id.channelDeleteWordInput))
				.setText("DELETE");
		go.performClick();
		idle();
		Dialog sheet = ShadowDialog.getLatestDialog();
		assertTrue(sheet instanceof BottomSheetDialog);
		String ask = String.valueOf(((android.widget.TextView) sheet
				.findViewById(R.id.channelDeleteAuthMessage)).getText());
		assertTrue(ask, ask.contains("from this phone only"));
		assertEquals("Remove from this phone", String.valueOf(((Button) sheet
				.findViewById(R.id.channelDeleteAuthConfirm)).getText()));
		verify(manager, never()).deleteChannel(any());
		when(manager.getChannel(any())).thenReturn(null);
		enterPassword((BottomSheetDialog) sheet, "my password");
		verify(accounts, org.mockito.Mockito.times(2)).verifyPassword(any());
		verify(manager).deleteChannel(id);
		verify(manager).deleteChannelForEveryone(id);
		assertEquals(1, deleted);
		assertEquals("Removed from this phone only. Subscribers were not told"
				+ " and keep their copy.", ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void aWrongPasswordForThePhoneOnlyStepRemovesNothing()
			throws Exception {
		AlertDialog offer = unsignedDelete();
		doThrow(new DecryptionException(DecryptionResult.INVALID_PASSWORD))
				.when(accounts).verifyPassword(any());
		offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		AlertDialog word = wordStep();
		((EditText) word.findViewById(R.id.channelDeleteWordInput))
				.setText("DELETE");
		word.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		BottomSheetDialog sheet =
				(BottomSheetDialog) ShadowDialog.getLatestDialog();
		enterPassword(sheet, "not my password");
		assertEquals("Wrong password. Nothing was deleted.",
				String.valueOf(error(sheet)));
		verify(manager, never()).deleteChannel(any());
		assertEquals(0, deleted);
	}

	@Test
	public void theOfferNeverAppearsForAnyOtherFailure() throws Exception {
		doThrow(new DbException()).when(manager)
				.deleteChannelForEveryone(any());
		start();
		enterPassword(typeTheWordAndContinue(), "my password");
		for (Dialog d : ShadowDialog.getShownDialogs()) {
			if (!(d instanceof AlertDialog)) continue;
			Button b = ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE);
			assertFalse(b != null && "Remove from this phone only".equals(
					String.valueOf(b.getText())));
		}
		verify(manager, never()).deleteChannel(any());
	}

	@Test
	public void aFailedDeleteOfAChannelStillHereAsksToTryAgain()
			throws Exception {
		doThrow(new DbException()).when(manager)
				.deleteChannelForEveryone(any());
		start();
		enterPassword(typeTheWordAndContinue(), "my password");
		assertEquals(0, deleted);
		assertEquals("Could not delete the channel for everyone. Try again.",
				notDeleted.get(0));
	}

	@Test
	public void anErrorIsNotTakenForADeletionWhenTheChannelCannotBeRead()
			throws Exception {
		doThrow(new DbException()).when(manager)
				.deleteChannelForEveryone(any());
		when(manager.getChannel(any())).thenReturn(null);
		start();
		enterPassword(typeTheWordAndContinue(), "my password");
		assertEquals(0, deleted);
		assertEquals(1, notDeleted.size());
		assertEquals("Could not delete the channel for everyone. Try again.",
				notDeleted.get(0));
	}

	@Test
	public void withoutAStoredNoticeASilentReturnIsNoSuccess()
			throws Exception {
		when(manager.getChannel(any())).thenReturn(null);
		start();
		enterPassword(typeTheWordAndContinue(), "my password");
		verify(manager).hasDeletionNotice(id);
		assertEquals(0, deleted);
		assertEquals("Could not delete the channel for everyone. Try again.",
				notDeleted.get(0));
	}

	@Test
	public void aStoredNoticeWithCleanupLeftIsReportedAsDone()
			throws Exception {
		doThrow(new DbException()).when(manager)
				.deleteChannelForEveryone(any());
		when(manager.hasDeletionNotice(any())).thenReturn(true);
		start();
		enterPassword(typeTheWordAndContinue(), "my password");
		assertEquals(1, deleted);
		assertTrue(notDeleted.isEmpty());
		assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()),
				String.valueOf(ShadowToast.getTextOfLatestToast()).endsWith(
						"It is fully removed from this phone the next time"
								+ " you sign in."));
	}

	@Test
	public void aLockoutRefusesWithoutCheckingOrDeleting() throws Exception {
		when(accounts.signInLockoutRemainingMs()).thenReturn(120_000L);
		start();
		BottomSheetDialog sheet = typeTheWordAndContinue();
		enterPassword(sheet, "my password");
		verify(accounts, never()).verifyPassword(any());
		assertNothingDeleted();
		assertTrue(String.valueOf(error(sheet)),
				String.valueOf(error(sheet)).startsWith(
						"Too many wrong passwords. Try again in 2 minutes"));
		Button confirm = sheet.findViewById(R.id.channelDeleteAuthConfirm);
		assertFalse(confirm.isEnabled());
	}

	@Test
	public void theDuressAnswerErasesEvenWhenTheScreenIsGone()
			throws Exception {
		when(accounts.isEraseRequested()).thenReturn(true);
		start();
		BottomSheetDialog sheet = typeTheWordAndContinue();
		EditText field = sheet.findViewById(R.id.channelDeleteAuthPassword);
		field.setText("my duress password");
		sheet.findViewById(R.id.channelDeleteAuthConfirm).performClick();
		idle();
		controller.pause().stop().destroy();
		runBackgroundWork();
		verify(accounts).shredDatabaseKey();
		assertNothingDeleted();
	}

	@Test
	public void thePasswordStepIsKeptOutOfScreenshots() {
		start();
		BottomSheetDialog sheet = typeTheWordAndContinue();
		assertNotNull(sheet.getWindow());
		assertTrue((sheet.getWindow().getAttributes().flags
				& android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
	}
}
