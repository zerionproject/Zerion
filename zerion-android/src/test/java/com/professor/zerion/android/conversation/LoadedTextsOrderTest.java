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
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.DeletionResult;
import org.zerionproject.app.api.identity.AuthorManager;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.event.PrivateMessageEditedEvent;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.versioning.ClientVersioningManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LoadedTextsOrderTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final ContactId CONTACT = new ContactId(7);

	private final Queue<Runnable> dbTasks = new ArrayDeque<>();
	private final MessagingManager messagingManager =
			mock(MessagingManager.class);
	private final ConversationManager conversationManager =
			mock(ConversationManager.class);
	private final MessageId shown = new MessageId(getRandomId());
	private final MessageId arrived = new MessageId(getRandomId());
	private final MessageId gone = new MessageId(getRandomId());
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

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private void runDbTasks() {
		Runnable r;
		while ((r = dbTasks.poll()) != null) r.run();
	}

	@Before
	public void setUp() throws Exception {
		MeshPresenceTracker presence = mock(MeshPresenceTracker.class);
		when(presence.isPresent(CONTACT)).thenReturn(false);
		viewModel = new ConversationViewModel(
				(Application) RuntimeEnvironment.getApplication(),
				dbTasks::add, mock(LifecycleManager.class),
				mock(TransactionManager.class), mainLooper(),
				mock(EventBus.class), messagingManager,
				mock(ContactManager.class), mock(AuthorManager.class),
				mock(SettingsManager.class), mock(PrivateMessageFactory.class),
				mock(AttachmentRetriever.class), mock(AttachmentCreator.class),
				mock(AutoDeleteManager.class), conversationManager,
				mock(VoiceMessageSendManager.class), new VoiceChunkAssembler(),
				mock(ClientVersioningManager.class),
				mock(IdentityManager.class), mock(MeshTextSender.class),
				mock(MeshAttachmentSender.class), presence,
				mock(ConnectionRegistry.class));
		viewModel.setContactId(CONTACT);
		dbTasks.clear();
		idle();
		Map<MessageId, String> first = new HashMap<>();
		first.put(shown, "Hey Allfs");
		first.put(gone, "secret plan");
		when(messagingManager.getMessageTexts(CONTACT)).thenReturn(first);
		viewModel.loadMessageHeaders();
		runDbTasks();
		idle();
		assertEquals(first, viewModel.getMessageTexts().getValue());
	}

	private void startReloadThatFinds() throws Exception {
		Map<MessageId, String> loaded = new HashMap<>();
		loaded.put(shown, "Hey Allfs");
		loaded.put(gone, "secret plan");
		loaded.put(arrived, "see you there");
		when(messagingManager.getMessageTexts(CONTACT)).thenReturn(loaded);
		viewModel.loadMessageHeaders();
		runDbTasks();
	}

	@Test
	public void anEditDuringALoadKeepsTheLoadedTextsAndTheEdit()
			throws Exception {
		startReloadThatFinds();

		viewModel.eventOccurred(new PrivateMessageEditedEvent(CONTACT, shown,
				"Hey all", false));
		idle();

		Map<MessageId, String> texts = viewModel.getMessageTexts().getValue();
		assertNotNull(texts);
		assertEquals("see you there", texts.get(arrived));
		assertEquals("Hey all", texts.get(shown));
		assertFalse(texts.containsValue("Hey Allfs"));
	}

	@Test
	public void aDeletionDuringALoadKeepsTheLoadedTextsWithoutTheDeleted()
			throws Exception {
		startReloadThatFinds();

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				singletonList(gone)));
		idle();

		Map<MessageId, String> texts = viewModel.getMessageTexts().getValue();
		assertNotNull(texts);
		assertEquals("see you there", texts.get(arrived));
		assertEquals("Hey Allfs", texts.get(shown));
		assertFalse(texts.containsKey(gone));
	}

	@Test
	public void aDeletionOffTheMainThreadDuringALoadKeepsTheLoadedTexts()
			throws Exception {
		when(conversationManager.deleteMessages(eq(CONTACT), any()))
				.thenReturn(new DeletionResult());
		startReloadThatFinds();
		viewModel.deleteMessages(singletonList(gone));

		Thread db = new Thread(this::runDbTasks);
		db.start();
		db.join();
		idle();

		Map<MessageId, String> texts = viewModel.getMessageTexts().getValue();
		assertNotNull(texts);
		assertEquals("see you there", texts.get(arrived));
		assertFalse(texts.containsKey(gone));
	}

	@Test
	public void aPartialDeletionReloadsFromTheMainThread() throws Exception {
		DeletionResult partial = new DeletionResult();
		partial.addInvitationNotAllSelected();
		when(conversationManager.deleteMessages(eq(CONTACT), any()))
				.thenReturn(partial);
		viewModel.deleteMessages(singletonList(gone));
		java.util.concurrent.atomic.AtomicReference<Throwable> failure =
				new java.util.concurrent.atomic.AtomicReference<>();
		Thread db = new Thread(() -> {
			try {
				runDbTasks();
			} catch (Throwable t) {
				failure.set(t);
			}
		});
		db.start();
		db.join();
		assertEquals(null, failure.get());
		idle();
		runDbTasks();
		idle();
		assertNotNull(viewModel.getMessageTexts().getValue());
	}

	@Test
	public void aQuoteStoredAfterAnEditShowsTheEditedText() throws Exception {
		viewModel.eventOccurred(new PrivateMessageEditedEvent(CONTACT, shown,
				"Hey all", false));
		idle();
		MessageId reply = new MessageId(getRandomId());
		java.lang.reflect.Method store = ConversationViewModel.class
				.getDeclaredMethod("storeReplyContext", MessageId.class,
						MessageId.class, String.class);
		store.setAccessible(true);
		store.invoke(viewModel, reply, shown, "Hey Allfs");
		assertEquals("Hey all", viewModel.getReplyContext(reply).getSecond());
	}

	@Test
	public void aDeletionDuringAReactionLoadDoesNotBringTheReactionsBack()
			throws Exception {
		Map<MessageId, Map<String, Integer>> loaded = new HashMap<>();
		loaded.put(shown, singletonMap("thumbs", 1));
		loaded.put(gone, singletonMap("heart", 1));
		when(messagingManager.getReactions(CONTACT)).thenReturn(loaded);
		viewModel.loadReactions();
		runDbTasks();

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				singletonList(gone)));
		idle();

		Map<MessageId, Map<String, Integer>> reactions =
				viewModel.getReactionsMap().getValue();
		assertNotNull(reactions);
		assertTrue(reactions.containsKey(shown));
		assertFalse(reactions.containsKey(gone));
	}

	@Test
	public void aDeletionDuringALinkPreviewLoadDoesNotBringThePreviewBack()
			throws Exception {
		Map<MessageId, LinkPreview> loaded = new HashMap<>();
		loaded.put(shown, new LinkPreview("https://example.org/a", "A",
				null, null));
		loaded.put(gone, new LinkPreview("https://example.org/b", "B",
				null, null));
		when(messagingManager.getLinkPreviews(CONTACT)).thenReturn(loaded);
		viewModel.loadLinkPreviews();
		runDbTasks();

		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				singletonList(gone)));
		idle();

		Map<MessageId, LinkPreview> previews =
				viewModel.getLinkPreviewsMap().getValue();
		assertNotNull(previews);
		assertTrue(previews.containsKey(shown));
		assertFalse(previews.containsKey(gone));
	}

	@Test
	public void anEditWhileAQuoteIsStoredOffTheMainThreadIsNotLost()
			throws Exception {
		CountDownLatch reading = new CountDownLatch(1);
		CountDownLatch edited = new CountDownLatch(1);
		Map<MessageId, String> texts = new ConcurrentHashMap<MessageId, String>() {
			@Override
			public String get(Object key) {
				String text = super.get(key);
				if (Looper.getMainLooper().getThread()
						!= Thread.currentThread()) {
					reading.countDown();
					try {
						edited.await(500, MILLISECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				return text;
			}
		};
		Field editedTexts =
				ConversationViewModel.class.getDeclaredField("editedTexts");
		editedTexts.setAccessible(true);
		editedTexts.set(viewModel, texts);
		viewModel.eventOccurred(new PrivateMessageEditedEvent(CONTACT, shown,
				"Hey all", false));
		idle();
		MessageId reply = new MessageId(getRandomId());
		Method store = ConversationViewModel.class.getDeclaredMethod(
				"storeReplyContext", MessageId.class, MessageId.class,
				String.class);
		store.setAccessible(true);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread db = new Thread(() -> {
			try {
				store.invoke(viewModel, reply, shown, "Hey Allfs");
			} catch (Throwable t) {
				failure.set(t);
			}
		});

		db.start();
		assertTrue(reading.await(10, SECONDS));
		viewModel.eventOccurred(new PrivateMessageEditedEvent(CONTACT, shown,
				"Hey all, see you there", false));
		edited.countDown();
		db.join();
		idle();

		assertNull(failure.get());
		assertEquals("Hey all, see you there",
				viewModel.getReplyContext(reply).getSecond());
	}

	@Test
	public void aQuoteOfAMessageDeletedMeanwhileIsNotStored() throws Exception {
		viewModel.eventOccurred(new ConversationMessagesDeletedEvent(CONTACT,
				singletonList(gone)));
		idle();
		MessageId reply = new MessageId(getRandomId());
		Method store = ConversationViewModel.class.getDeclaredMethod(
				"storeReplyContext", MessageId.class, MessageId.class,
				String.class);
		store.setAccessible(true);

		store.invoke(viewModel, reply, gone, "secret plan");

		assertNull(viewModel.getReplyContext(reply));
	}
}
