package com.professor.zerion.android.conversation;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.professor.zerion.android.attachment.AttachmentCreator;
import com.professor.zerion.android.attachment.AttachmentRetriever;
import com.professor.zerion.android.conversation.voice.VoiceChunkAssembler;
import com.professor.zerion.android.conversation.voice.VoiceMessageSendManager;
import com.professor.zerion.android.mesh.MeshAttachmentSender;
import com.professor.zerion.android.mesh.MeshPresenceTracker;
import com.professor.zerion.android.mesh.MeshTextSender;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.TextSendController.SendState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.app.api.messaging.ViewOnceMedia;
import org.zerionproject.app.api.messaging.event.ViewOnceOpenedEvent;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.NullableDbCallable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.versioning.ClientVersioningManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import androidx.lifecycle.LiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ViewOnceModelTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final ContactId CONTACT = new ContactId(7);

	private final Queue<Runnable> dbTasks = new ArrayDeque<>();
	private final MessagingManager messagingManager =
			mock(MessagingManager.class);
	private final PrivateMessageFactory factory =
			mock(PrivateMessageFactory.class);
	private final SettingsManager settingsManager =
			mock(SettingsManager.class);
	private final ContactManager contactManager = mock(ContactManager.class);
	private final AutoDeleteManager autoDeleteManager =
			mock(AutoDeleteManager.class);
	private final GroupId group = new GroupId(getRandomId());
	private ConversationViewModel viewModel;

	private static AndroidExecutor mainLooper() {
		Handler main = new Handler(Looper.getMainLooper());
		return new AndroidExecutor() {
			@Override
			public <V> Future<V> runOnBackgroundThread(Callable<V> c) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void runOnBackgroundThread(Runnable r) {
				throw new UnsupportedOperationException();
			}

			@Override
			public <V> Future<V> runOnUiThread(Callable<V> c) {
				FutureTask<V> f = new FutureTask<>(c);
				main.post(f);
				return f;
			}

			@Override
			public void runOnUiThread(Runnable r) {
				main.post(r);
			}
		};
	}

	private static class DirectTransactions implements TransactionManager {

		@Override
		public Transaction startTransaction(boolean readOnly) {
			return new Transaction(null, readOnly);
		}

		@Override
		public void commitTransaction(Transaction txn) {
		}

		@Override
		public void endTransaction(Transaction txn) {
		}

		@Override
		public <E extends Exception> void transaction(boolean readOnly,
				DbRunnable<E> task) throws org.zerionproject.core.api.db
				.DbException, E {
			task.run(new Transaction(null, readOnly));
		}

		@Override
		public <R, E extends Exception> R transactionWithResult(
				boolean readOnly, DbCallable<R, E> task)
				throws org.zerionproject.core.api.db.DbException, E {
			return task.call(new Transaction(null, readOnly));
		}

		@Override
		public <R, E extends Exception> R transactionWithNullableResult(
				boolean readOnly, NullableDbCallable<R, E> task)
				throws org.zerionproject.core.api.db.DbException, E {
			return task.call(new Transaction(null, readOnly));
		}
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private void runDbTasks() {
		Runnable r;
		while ((r = dbTasks.poll()) != null) r.run();
	}

	@Before
	public void setUp() throws Exception {
		Contact contact = mock(Contact.class);
		when(contact.getId()).thenReturn(CONTACT);
		when(contactManager.getContact(CONTACT)).thenReturn(contact);
		Group g = mock(Group.class);
		when(g.getId()).thenReturn(group);
		when(messagingManager.getContactGroup(contact)).thenReturn(g);
		when(messagingManager.getContactMessageFormat(any(), eq(CONTACT)))
				.thenReturn(PrivateMessageFormat.TEXT_IMAGES_SALTED);
		when(messagingManager.supportsViewOnce(CONTACT)).thenReturn(true);
		when(autoDeleteManager.getAutoDeleteTimer(any(), eq(CONTACT)))
				.thenReturn(NO_AUTO_DELETE_TIMER);
		when(autoDeleteManager.getAutoDeleteTimer(any(), eq(CONTACT),
				anyLong())).thenReturn(NO_AUTO_DELETE_TIMER);
		when(settingsManager.getSettings(any())).thenReturn(new Settings());
		MeshPresenceTracker presence = mock(MeshPresenceTracker.class);
		viewModel = new ConversationViewModel(
				(Application) RuntimeEnvironment.getApplication(),
				dbTasks::add, mock(LifecycleManager.class),
				new DirectTransactions(), mainLooper(),
				mock(EventBus.class), messagingManager, contactManager,
				mock(AuthorManager.class), settingsManager, factory,
				mock(AttachmentRetriever.class), mock(AttachmentCreator.class),
				autoDeleteManager, mock(ConversationManager.class),
				mock(VoiceMessageSendManager.class), new VoiceChunkAssembler(),
				mock(ClientVersioningManager.class),
				mock(IdentityManager.class), mock(MeshTextSender.class),
				mock(MeshAttachmentSender.class), presence,
				mock(ConnectionRegistry.class));
		viewModel.setContactId(CONTACT);
		runDbTasks();
		idle();
	}

	private AttachmentHeader photo() {
		return new AttachmentHeader(group, new MessageId(getRandomId()),
				"image/jpeg");
	}

	@Test
	public void theComposerHearsWhetherTheContactSupportsViewOnce() {
		assertEquals(Boolean.TRUE, viewModel.getViewOnceSupported().getValue());
		assertTrue(viewModel.isViewOnceNoticeDue());
	}

	@Test
	public void aViewOnceSendBuildsAViewOnceMessageWithoutText()
			throws Exception {
		AttachmentHeader photo = photo();
		Message m = new Message(new MessageId(getRandomId()), group, 5L,
				new byte[] {1});
		PrivateMessage pm = new PrivateMessage(m, false, singletonList(photo),
				NO_AUTO_DELETE_TIMER, null, true);
		when(factory.createViewOncePrivateMessage(eq(group), anyLong(),
				eq(photo), eq(NO_AUTO_DELETE_TIMER), eq(null)))
				.thenReturn(pm);

		viewModel.sendMessage(null, singletonList(photo),
				NO_AUTO_DELETE_TIMER, null, true);
		runDbTasks();

		verify(messagingManager).addLocalMessage(any(Transaction.class),
				eq(pm));
		verify(factory, never()).createSaltedPrivateMessage(any(), anyLong(),
				any(), any(), anyLong(), any());
	}

	@Test
	public void aViewOnceSendNeedsExactlyOnePhotoOrVideo() throws Exception {
		LiveData<SendState> state = viewModel.sendMessage(null,
				Arrays.asList(photo(), photo()), NO_AUTO_DELETE_TIMER, null,
				true);
		runDbTasks();

		assertEquals(SendState.ERROR, state.getValue());
		verify(factory, never()).createViewOncePrivateMessage(any(),
				anyLong(), any(), anyLong(), any());
	}

	@Test
	public void openingHandsTheMediaToTheScreenOnce() throws Exception {
		MessageId id = new MessageId(getRandomId());
		ViewOnceMedia media = new ViewOnceMedia("image/jpeg", new byte[] {7});
		when(messagingManager.openViewOnce(CONTACT, id)).thenReturn(media);
		List<ConversationViewModel.ViewOnceResult> results = new ArrayList<>();
		viewModel.getViewOnceReady().observeEventForever(r -> {
			ConversationViewModel.ViewOnceResult result;
			while ((result = viewModel.takeViewOnceResult()) != null) {
				results.add(result);
			}
		});

		viewModel.openViewOnce(id);
		viewModel.openViewOnce(id);
		runDbTasks();
		idle();
		viewModel.openViewOnce(id);
		runDbTasks();
		idle();

		assertEquals(1, results.size());
		assertEquals(id, results.get(0).messageId);
		assertSame(media, results.get(0).media);
		verify(messagingManager, times(1)).openViewOnce(CONTACT, id);
	}

	@Test
	public void resultsForTwoTilesAreBothDelivered() throws Exception {
		MessageId first = new MessageId(getRandomId());
		MessageId second = new MessageId(getRandomId());
		when(messagingManager.openViewOnce(CONTACT, first)).thenReturn(
				new ViewOnceMedia("image/jpeg", new byte[] {1}));
		when(messagingManager.openViewOnce(CONTACT, second)).thenReturn(null);

		viewModel.openViewOnce(first);
		viewModel.openViewOnce(second);
		runDbTasks();
		idle();

		ConversationViewModel.ViewOnceResult a = viewModel.takeViewOnceResult();
		ConversationViewModel.ViewOnceResult b = viewModel.takeViewOnceResult();
		assertNotNull(a);
		assertNotNull(b);
		assertEquals(first, a.messageId);
		assertNotNull(a.media);
		assertEquals(second, b.messageId);
		assertNull(b.media);
		assertNull(viewModel.takeViewOnceResult());

		viewModel.openViewOnce(second);
		runDbTasks();
		verify(messagingManager, times(2)).openViewOnce(CONTACT, second);
	}

	@Test
	public void theSwitchFollowsTheContactsVersion() throws Exception {
		when(messagingManager.supportsViewOnce(CONTACT)).thenReturn(false);

		viewModel.recheckFeaturesAndOnboarding(CONTACT);
		runDbTasks();
		idle();

		assertEquals(Boolean.FALSE,
				viewModel.getViewOnceSupported().getValue());
	}

	@Test
	public void aReceiptForThisChatTurnsTheTileIntoViewed() {
		MessageId id = new MessageId(getRandomId());
		List<MessageId> viewed = new ArrayList<>();
		viewModel.getViewOnceViewed().observeEventForever(v -> {
			if (v != null) viewed.add(v);
		});

		viewModel.eventOccurred(new ViewOnceOpenedEvent(new ContactId(8),
				new MessageId(getRandomId()), true));
		viewModel.eventOccurred(new ViewOnceOpenedEvent(CONTACT, id, true));
		idle();

		assertEquals(singletonList(id), viewed);
	}

	@Test
	public void theNoticeIsShownOnlyOnce() throws Exception {
		viewModel.markViewOnceNoticeShown();
		runDbTasks();

		assertFalse(viewModel.isViewOnceNoticeDue());
		verify(settingsManager).mergeSettings(
				org.mockito.ArgumentMatchers.argThat(s -> !s.getBoolean(
						ConversationViewModel.SHOW_VIEW_ONCE_NOTICE, true)),
				any());

		Settings stored = new Settings();
		stored.putBoolean(ConversationViewModel.SHOW_VIEW_ONCE_NOTICE, false);
		when(settingsManager.getSettings(any())).thenReturn(stored);
		viewModel.recheckFeaturesAndOnboarding(CONTACT);
		runDbTasks();
		assertFalse(viewModel.isViewOnceNoticeDue());
	}
}
