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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
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
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.versioning.ClientVersioningManager;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
public class SecretNoteRecoveryModelTest {

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
	private final ConversationManager conversationManager =
			mock(ConversationManager.class);
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
				autoDeleteManager, conversationManager,
				mock(VoiceMessageSendManager.class), new VoiceChunkAssembler(),
				mock(ClientVersioningManager.class),
				mock(IdentityManager.class), mock(MeshTextSender.class),
				mock(MeshAttachmentSender.class), presence,
				mock(ConnectionRegistry.class));
		viewModel.setContactId(CONTACT);
		runDbTasks();
		idle();
	}

	private PrivateMessageHeader header(MessageId id, long time) {
		return new PrivateMessageHeader(id, group, time, false, true, true,
				false, true, java.util.Collections.emptyList(),
				NO_AUTO_DELETE_TIMER);
	}

	@Test
	public void openingANoteStartsItsStoredCountdown() throws Exception {
		MessageId note = new MessageId(getRandomId());

		viewModel.startSecretNoteCountdown(note, 10);
		runDbTasks();

		verify(messagingManager).startSecretNoteCountdown(CONTACT, note,
				10_000L);
	}

	@Test
	public void aNoteOpenedBeforeARestartIsDeletedAndNeverListedAgain()
			throws Exception {
		MessageId note = new MessageId(getRandomId());
		MessageId other = earlierNoteAndAnotherMessage(note);
		when(messagingManager.deleteSecretNotesOpenedEarlier(CONTACT))
				.thenReturn(singletonList(note));

		viewModel.loadMessageHeaders();
		runDbTasks();
		idle();

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(
				messagingManager, conversationManager);
		order.verify(messagingManager).getSecretNotesOpenedEarlier(CONTACT);
		order.verify(messagingManager).deleteSecretNotesOpenedEarlier(CONTACT);
		order.verify(conversationManager).getMessageHeaders(CONTACT);
		assertListedWithout(note, other);
	}

	@Test
	public void aFailingDeleteStillListsTheChatWithoutTheNoteAndRetries()
			throws Exception {
		MessageId note = new MessageId(getRandomId());
		MessageId other = earlierNoteAndAnotherMessage(note);
		when(messagingManager.deleteSecretNotesOpenedEarlier(CONTACT))
				.thenThrow(new org.zerionproject.core.api.db.DbException());

		viewModel.loadMessageHeaders();
		runDbTasks();
		idle();

		assertListedWithout(note, other);
		assertFalse(Boolean.TRUE.equals(
				viewModel.isContactDeleted().getValue()));

		viewModel.loadMessageHeaders();
		runDbTasks();
		idle();

		verify(messagingManager, times(2))
				.deleteSecretNotesOpenedEarlier(CONTACT);
		assertListedWithout(note, other);
	}

	@Test
	public void aRemovedContactStillClosesTheChat() throws Exception {
		MessageId note = new MessageId(getRandomId());
		earlierNoteAndAnotherMessage(note);
		when(messagingManager.deleteSecretNotesOpenedEarlier(CONTACT))
				.thenThrow(new org.zerionproject.core.api.db
						.NoSuchContactException());

		viewModel.loadMessageHeaders();
		runDbTasks();
		idle();

		assertEquals(Boolean.TRUE, viewModel.isContactDeleted().getValue());
	}

	@Test
	public void aLoadWithNothingOpenedEarlierListsEverything()
			throws Exception {
		MessageId note = new MessageId(getRandomId());
		when(messagingManager.getSecretNotesOpenedEarlier(CONTACT))
				.thenReturn(java.util.Collections.emptyList());
		when(conversationManager.getMessageHeaders(CONTACT)).thenReturn(
				singletonList(header(note, 1_000L)));

		viewModel.loadMessageHeaders();
		runDbTasks();
		idle();

		assertEquals(1, viewModel.getMessageHeaders().getValue().size());
		verify(messagingManager, never())
				.deleteSecretNotesOpenedEarlier(any());
	}

	@Test
	public void openingANoteOfARemovedContactDoesNothing() throws Exception {
		MessageId note = new MessageId(getRandomId());
		when(messagingManager.startSecretNoteCountdown(any(), any(),
				anyLong())).thenThrow(new org.zerionproject.core.api.db
				.NoSuchContactException());

		viewModel.startSecretNoteCountdown(note, 10);
		runDbTasks();
		idle();

		verify(messagingManager).startSecretNoteCountdown(CONTACT, note,
				10_000L);
		assertEquals(0,
				org.robolectric.shadows.ShadowToast.shownToastCount());
	}

	private MessageId earlierNoteAndAnotherMessage(MessageId note)
			throws Exception {
		MessageId other = new MessageId(getRandomId());
		java.util.Map<MessageId, String> texts = new java.util.HashMap<>();
		texts.put(note, "SECRET:10:meet at noon");
		texts.put(other, "hello");
		when(messagingManager.getSecretNotesOpenedEarlier(CONTACT))
				.thenReturn(singletonList(note));
		when(conversationManager.getMessageHeaders(CONTACT)).thenReturn(
				Arrays.asList(header(note, 1_000L), header(other, 2_000L)));
		when(messagingManager.getMessageTexts(CONTACT)).thenReturn(texts);
		return other;
	}

	private void assertListedWithout(MessageId note, MessageId other) {
		java.util.Collection<org.zerionproject.app.api.conversation
				.ConversationMessageHeader> shown =
				viewModel.getMessageHeaders().getValue();
		assertNotNull(shown);
		assertEquals(1, shown.size());
		assertEquals(other, shown.iterator().next().getId());
		java.util.Map<MessageId, String> shownTexts =
				viewModel.getMessageTexts().getValue();
		assertNotNull(shownTexts);
		assertFalse(shownTexts.containsKey(note));
		assertTrue(shownTexts.containsKey(other));
	}
}
