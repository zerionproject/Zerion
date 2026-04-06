package com.professor.zerion.android.conversation;

import android.app.Application;
import android.net.Uri;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.sync.event.MessagesAckedEvent;
import org.briarproject.bramble.api.sync.event.MessagesSentEvent;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.plugin.event.ContactConnectedEvent;
import org.briarproject.bramble.api.plugin.event.ContactDisconnectedEvent;
import org.briarproject.bramble.api.versioning.event.ClientVersionUpdatedEvent;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import com.professor.zerion.android.attachment.AttachmentCreator;
import com.professor.zerion.android.attachment.AttachmentManager;
import com.professor.zerion.android.attachment.AttachmentResult;
import com.professor.zerion.android.attachment.AttachmentRetriever;
import com.professor.zerion.android.contact.ContactItem;
import com.professor.zerion.android.util.UiUtils;
import com.professor.zerion.android.view.TextSendController.SendState;
import com.professor.zerion.android.viewmodel.DbViewModel;
import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.autodelete.UnexpectedTimerException;
import org.briarproject.briar.api.autodelete.event.AutoDeleteTimerMirroredEvent;
import org.briarproject.briar.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.briarproject.briar.api.avatar.event.AvatarUpdatedEvent;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.briarproject.briar.api.messaging.PrivateMessageFormat;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.messaging.event.AttachmentReceivedEvent;
import org.briarproject.briar.api.messaging.LinkPreview;
import org.briarproject.briar.api.messaging.event.ReactionReceivedEvent;
import org.briarproject.briar.api.messaging.event.TypingIndicatorReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static androidx.lifecycle.Transformations.map;
import static java.util.Objects.requireNonNull;
import static com.professor.zerion.android.settings.SettingsFragment.SETTINGS_NAMESPACE;
import static com.professor.zerion.android.util.UiUtils.observeForeverOnce;
import static com.professor.zerion.android.view.TextSendController.SendState.ERROR;
import static com.professor.zerion.android.view.TextSendController.SendState.SENT;
import static com.professor.zerion.android.view.TextSendController.SendState.UNEXPECTED_TIMER;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.autodelete.AutoDeleteManager.DEFAULT_TIMER_DURATION;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_IMAGES;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_ONLY;

@NotNullByDefault
public class ConversationViewModel extends DbViewModel
		implements EventListener, AttachmentManager {

	private static final String SHOW_ONBOARDING_IMAGE =
			"showOnboardingImage";
	private static final String SHOW_ONBOARDING_INTRODUCTION =
			"showOnboardingIntroduction";

	private final TransactionManager db;
	private final EventBus eventBus;
	private final MessagingManager messagingManager;
	private final ContactManager contactManager;
	private final AuthorManager authorManager;
	private final SettingsManager settingsManager;
	private final PrivateMessageFactory privateMessageFactory;
	private final AttachmentRetriever attachmentRetriever;
	private final AttachmentCreator attachmentCreator;
	private final AutoDeleteManager autoDeleteManager;
	private final ConversationManager conversationManager;

	@Nullable
	private ContactId contactId = null;
	private final MutableLiveData<ContactItem> contactItem =
			new MutableLiveData<>();
	private final LiveData<String> contactName = map(contactItem, c ->
			UiUtils.getContactDisplayName(c.getContact()));
	private final LiveData<GroupId> messagingGroupId;
	private final MutableLiveData<PrivateMessageFormat> privateMessageFormat =
			new MutableLiveData<>();
	private final MutableLiveEvent<Boolean> showImageOnboarding =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> showIntroductionOnboarding =
			new MutableLiveEvent<>();
	private final MutableLiveData<Boolean> showIntroductionAction =
			new MutableLiveData<>();
	private final MutableLiveData<Long> autoDeleteTimer =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> contactDeleted =
			new MutableLiveData<>(false);
	private final MutableLiveEvent<PrivateMessageHeader> addedHeader =
			new MutableLiveEvent<>();
	private final java.util.concurrent.ConcurrentHashMap<MessageId, Pair<MessageId, String>> replyContextMap =
			new java.util.concurrent.ConcurrentHashMap<>();
	private final MutableLiveData<ConversationItem> replyTarget =
			new MutableLiveData<>();
	private final MutableLiveData<Collection<ConversationMessageHeader>> messageHeaders =
			new MutableLiveData<>();
	private final MutableLiveData<Map<MessageId, String>> messageTexts =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> messagesLoading = new MutableLiveData<>(false);
	private final MutableLiveEvent<Pair<MessageId, String>> messageTextLoaded =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> chatCleared = new MutableLiveEvent<>();
	private final MutableLiveEvent<Collection<MessageId>> messagesDeleted =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<MarkMessagesEvent> messagesMarked =
			new MutableLiveEvent<>();
	private final MutableLiveData<Boolean> contactConnected =
			new MutableLiveData<>();
	private final MutableLiveEvent<ConversationMessageHeader> newMessageReceived =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<ClientId> clientVersionUpdated =
			new MutableLiveEvent<>();
	private final MutableLiveData<Map<MessageId, Map<String, Integer>>> reactionsMap =
			new MutableLiveData<>();
	private final MutableLiveEvent<ReactionReceivedEvent> reactionReceived =
			new MutableLiveEvent<>();
	private final MutableLiveData<Boolean> contactTyping =
			new MutableLiveData<>(false);
	private final MutableLiveData<Map<MessageId, LinkPreview>> linkPreviewsMap =
			new MutableLiveData<>();
	private final MutableLiveData<LinkPreview> pendingLinkPreview =
			new MutableLiveData<>();
	static class MarkMessagesEvent {
		final Collection<MessageId> messageIds;
		final boolean sent;
		final boolean seen;

		MarkMessagesEvent(Collection<MessageId> messageIds, boolean sent, boolean seen) {
			this.messageIds = messageIds;
			this.sent = sent;
			this.seen = seen;
		}
	}

	@Inject
	ConversationViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			EventBus eventBus,
			MessagingManager messagingManager,
			ContactManager contactManager,
			AuthorManager authorManager,
			SettingsManager settingsManager,
			PrivateMessageFactory privateMessageFactory,
			AttachmentRetriever attachmentRetriever,
			AttachmentCreator attachmentCreator,
			AutoDeleteManager autoDeleteManager,
			ConversationManager conversationManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.db = db;
		this.eventBus = eventBus;
		this.messagingManager = messagingManager;
		this.contactManager = contactManager;
		this.authorManager = authorManager;
		this.settingsManager = settingsManager;
		this.privateMessageFactory = privateMessageFactory;
		this.attachmentRetriever = attachmentRetriever;
		this.attachmentCreator = attachmentCreator;
		this.autoDeleteManager = autoDeleteManager;
		this.conversationManager = conversationManager;
		messagingGroupId = map(contactItem, c ->
				messagingManager.getContactGroup(c.getContact()).getId());
		eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		attachmentCreator.cancel();
		eventBus.removeListener(this);
		zeroizeVoiceRecordingState();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof AttachmentReceivedEvent) {
			AttachmentReceivedEvent a = (AttachmentReceivedEvent) e;
			if (a.getContactId().equals(contactId)) {
				runOnDbThread(() -> attachmentRetriever
						.loadAttachmentItem(a.getMessageId()));
			}
		} else if (e instanceof AutoDeleteTimerMirroredEvent) {
			AutoDeleteTimerMirroredEvent a = (AutoDeleteTimerMirroredEvent) e;
			if (a.getContactId().equals(contactId)) {
				autoDeleteTimer.postValue(a.getNewTimer());
			}
		} else if (e instanceof AvatarUpdatedEvent) {
			AvatarUpdatedEvent a = (AvatarUpdatedEvent) e;
			if (a.getContactId().equals(contactId)) {
				updateAvatar(a);
			}
		} else if (e instanceof MessagesSentEvent) {
			MessagesSentEvent m = (MessagesSentEvent) e;
			if (m.getContactId().equals(contactId)) {
				markMessages(m.getMessageIds(), true, false);
			}
		} else if (e instanceof MessagesAckedEvent) {
			MessagesAckedEvent m = (MessagesAckedEvent) e;
			if (m.getContactId().equals(contactId)) {
				markMessages(m.getMessageIds(), true, true);
			}
		} else if (e instanceof ConversationMessagesDeletedEvent) {
			ConversationMessagesDeletedEvent m = (ConversationMessagesDeletedEvent) e;
			if (m.getContactId().equals(contactId)) {
				for (MessageId id : m.getMessageIds()) {
					ConversationCache.getInstance().removeMessage(contactId, id);
				}
				messagesDeleted.postEvent(m.getMessageIds());
			}
		} else if (e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if (c.getContactId().equals(contactId)) {
				contactDeleted.postValue(true);
			}
		} else if (e instanceof ContactConnectedEvent) {
			ContactConnectedEvent c = (ContactConnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				contactConnected.postValue(true);
			}
		} else if (e instanceof ContactDisconnectedEvent) {
			ContactDisconnectedEvent c = (ContactDisconnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				contactConnected.postValue(false);
			}
		} else if (e instanceof ClientVersionUpdatedEvent) {
			ClientVersionUpdatedEvent c = (ClientVersionUpdatedEvent) e;
			if (c.getContactId().equals(contactId)) {
				clientVersionUpdated.postEvent(c.getClientVersion().getClientId());
			}
		} else if (e instanceof ReactionReceivedEvent) {
			ReactionReceivedEvent r = (ReactionReceivedEvent) e;
			if (r.getContactId().equals(contactId)) {
				reactionReceived.postEvent(r);
			}
		} else if (e instanceof TypingIndicatorReceivedEvent) {
			TypingIndicatorReceivedEvent t = (TypingIndicatorReceivedEvent) e;
			if (t.getContactId().equals(contactId)) {
				contactTyping.postValue(t.isTyping());
			}
		} else if (e instanceof org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent) {
			org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent<?> p =
					(org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent<?>) e;
			if (p.getContactId().equals(contactId)) {
				newMessageReceived.postEvent(p.getMessageHeader());
			}
		}
	}

	@UiThread
	private void updateAvatar(AvatarUpdatedEvent a) {
		observeForeverOnce(contactItem, oldContactItem -> {
			requireNonNull(oldContactItem);

			AuthorInfo oldAuthorInfo = oldContactItem.getAuthorInfo();

			AuthorInfo newAuthorInfo = new AuthorInfo(oldAuthorInfo.getStatus(),
					oldAuthorInfo.getAlias(), a.getAttachmentHeader());
			ContactItem newContactItem =
					new ContactItem(oldContactItem.getContact(), newAuthorInfo);

			contactItem.setValue(newContactItem);
		});
	}

	void setContactId(ContactId contactId) {
		if (this.contactId == null) {
			this.contactId = contactId;
			loadContact(contactId);
		} else if (!contactId.equals(this.contactId)) {
			throw new IllegalStateException();
		}
	}

	private void loadContact(ContactId contactId) {
		runOnDbThread(() -> {
			try {
				Contact c = contactManager.getContact(contactId);
				AuthorInfo authorInfo = authorManager.getAuthorInfo(c);
				contactItem.postValue(new ContactItem(c, authorInfo));
				long timer = db.transactionWithResult(true, txn ->
						autoDeleteManager.getAutoDeleteTimer(txn, contactId));
				autoDeleteTimer.postValue(timer);
				checkFeaturesAndOnboarding(contactId);
			} catch (NoSuchContactException e) {
				contactDeleted.postValue(true);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void markMessageRead(GroupId g, MessageId m) {
		runOnDbThread(() -> {
			try {
				conversationManager.setReadFlag(g, m, true);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void setContactAlias(String alias) {
		runOnDbThread(() -> {
			try {
				contactManager.setContactAlias(requireNonNull(contactId),
						alias.isEmpty() ? null : alias);
				loadContact(contactId);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@Override
	@UiThread
	public LiveData<AttachmentResult> storeAttachments(Collection<Uri> uris,
			boolean restart) {
		if (restart) {
			return attachmentCreator.getLiveAttachments();
		} else {
			PrivateMessageFormat format = privateMessageFormat.getValue();
			if (format == null) {
				format = TEXT_IMAGES;
			}
			return attachmentCreator.storeAttachments(messagingGroupId, uris, format);
		}
	}

	@Override
	@UiThread
	public List<AttachmentHeader> getAttachmentHeadersForSending() {
		return attachmentCreator.getAttachmentHeadersForSending();
	}

	@Override
	@UiThread
	public void cancel() {
		attachmentCreator.cancel();
	}

	@Override
	@UiThread
	public boolean hasValidAttachments() {
		return attachmentCreator.hasValidAttachments();
	}

	@DatabaseExecutor
	private void checkFeaturesAndOnboarding(ContactId c) throws DbException {
		PrivateMessageFormat format = db.transactionWithResult(true, txn ->
				messagingManager.getContactMessageFormat(txn, c));
		privateMessageFormat.postValue(format);

		Collection<Contact> contacts = contactManager.getContacts();
		boolean introductionSupported = contacts.size() > 1;
		showIntroductionAction.postValue(introductionSupported);

		Settings settings = settingsManager.getSettings(SETTINGS_NAMESPACE);
		if (format != TEXT_ONLY &&
				settings.getBoolean(SHOW_ONBOARDING_IMAGE, true)) {
			onOnboardingShown(SHOW_ONBOARDING_IMAGE);
			showImageOnboarding.postEvent(true);
		} else if (introductionSupported &&
				settings.getBoolean(SHOW_ONBOARDING_INTRODUCTION, true)) {
			onOnboardingShown(SHOW_ONBOARDING_INTRODUCTION);
			showIntroductionOnboarding.postEvent(true);
		}
	}

	@DatabaseExecutor
	private void onOnboardingShown(String key) throws DbException {
		Settings settings = new Settings();
		settings.putBoolean(key, false);
		settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
	}

	@UiThread
	LiveData<SendState> sendMessage(@Nullable String text,
			List<AttachmentHeader> headers, long expectedTimer,
			@Nullable ConversationItem replyToItem) {
		MutableLiveData<SendState> liveData = new MutableLiveData<>();
		boolean hasText = text != null && !text.trim().isEmpty();
		boolean hasAttachments = headers != null && !headers.isEmpty();
		if (!hasText && !hasAttachments) {
			liveData.setValue(ERROR);
			return liveData;
		}
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn -> {
					MessageId replyToId = replyToItem != null ?
							replyToItem.getId() : null;
					PrivateMessage m = createMessage(txn, text, headers,
							expectedTimer, replyToId);
					messagingManager.addLocalMessage(txn, m);
					Message message = m.getMessage();
					PrivateMessageHeader h = new PrivateMessageHeader(
							message.getId(), message.getGroupId(),
							message.getTimestamp(), true, true, false, false,
							m.hasText(), m.getAttachmentHeaders(),
							m.getAutoDeleteTimer(), replyToId);
					MessageId id = message.getId();

					if (replyToItem != null) {
						storeReplyContext(id, replyToItem.getId(),
								replyToItem.getText());
					}

					txn.attach(() -> {
						attachmentCreator.onAttachmentsSent(id);
						liveData.setValue(SENT);
						addedHeader.setEvent(h);
					});
				});
			} catch (UnexpectedTimerException e) {
				liveData.postValue(UNEXPECTED_TIMER);
			} catch (DbException e) {
				liveData.postValue(ERROR);
			}
		});
		return liveData;
	}

	private PrivateMessage createMessage(Transaction txn, @Nullable String text,
			List<AttachmentHeader> headers, long expectedTimer,
			@Nullable MessageId replyToId)
			throws DbException {
		Contact contact = requireNonNull(contactItem.getValue()).getContact();
		GroupId groupId = messagingManager.getContactGroup(contact).getId();
		PrivateMessageFormat format =
				requireNonNull(privateMessageFormat.getValue());
		long timestamp = conversationManager
				.getTimestampForOutgoingMessage(txn, requireNonNull(contactId));
		try {
			if (format == TEXT_ONLY) {
				return privateMessageFactory.createLegacyPrivateMessage(
						groupId, timestamp, requireNonNull(text));
			} else if (format == TEXT_IMAGES) {
				return privateMessageFactory.createPrivateMessage(groupId,
						timestamp, text, headers);
			} else {
				long conversationTimer = autoDeleteManager
						.getAutoDeleteTimer(txn, contactId, timestamp);
				if (expectedTimer == NO_AUTO_DELETE_TIMER &&
						conversationTimer != NO_AUTO_DELETE_TIMER) {
					throw new UnexpectedTimerException();
				}
				return privateMessageFactory.createPrivateMessage(groupId,
						timestamp, text, headers, expectedTimer, replyToId);
			}
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	@UiThread
	void sendSecretNote(String text, long timerMs) {
		if (text.trim().isEmpty()) return;
		String secretText = ConversationSecretNoteItem.wrapContent(text.trim());
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn -> {
					Contact contact =
							requireNonNull(contactItem.getValue()).getContact();
					GroupId groupId =
							messagingManager.getContactGroup(contact).getId();
					PrivateMessageFormat format =
							requireNonNull(privateMessageFormat.getValue());
					long timestamp = conversationManager
							.getTimestampForOutgoingMessage(txn,
									requireNonNull(contactId));
					PrivateMessage m;
					try {
						if (format == TEXT_ONLY) {
							m = privateMessageFactory.createLegacyPrivateMessage(
									groupId, timestamp, secretText);
						} else if (format == TEXT_IMAGES) {
							m = privateMessageFactory.createPrivateMessage(
									groupId, timestamp, secretText,
									java.util.Collections.emptyList());
						} else {
							m = privateMessageFactory.createPrivateMessage(
									groupId, timestamp, secretText,
									java.util.Collections.emptyList(),
									timerMs, null);
						}
					} catch (FormatException e) {
						throw new AssertionError(e);
					}
					messagingManager.addLocalMessage(txn, m);
					Message message = m.getMessage();
					PrivateMessageHeader h = new PrivateMessageHeader(
							message.getId(), message.getGroupId(),
							message.getTimestamp(), true, true, false, false,
							true, java.util.Collections.emptyList(),
							m.getAutoDeleteTimer(), null);
					String finalText = secretText;
					txn.attach(() -> {
						messageTextLoaded.setEvent(
								new Pair<>(message.getId(), finalText));
						addedHeader.setEvent(h);
					});
				});
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	private void storeReplyContext(MessageId sentMessageId,
			MessageId replyToId, @Nullable String replyToText) {
		replyContextMap.put(sentMessageId,
				new Pair<>(replyToId, replyToText != null ? replyToText : "[No text]"));
	}

	@Nullable
	Pair<MessageId, String> getReplyContext(MessageId messageId) {
		return replyContextMap.get(messageId);
	}
	private final java.util.List<byte[]> encryptedVoiceChunks = new java.util.ArrayList<>();
	private final java.util.List<byte[]> encryptedChunkTags = new java.util.ArrayList<>();
	private byte[] currentIv;
	private byte[] wrappedKey;
	private long voiceRecordingStartTime;
	private GroupId voiceMessageGroupId;

	@UiThread
	GroupId prepareVoiceRecording() {
		try {
			Contact contact = requireNonNull(contactItem.getValue()).getContact();
			GroupId groupId = messagingManager.getContactGroup(contact).getId();
			voiceMessageGroupId = groupId;
			return groupId;
		} catch (Exception e) {
			throw new RuntimeException("Failed to get conversation group ID", e);
		}
	}

	@UiThread
	void onEncryptionInit(byte[] iv, byte[] sessionKey) {
		synchronized (encryptedVoiceChunks) {
			currentIv = java.util.Arrays.copyOf(iv, iv.length);
			wrappedKey = java.util.Arrays.copyOf(sessionKey, sessionKey.length);
			voiceRecordingStartTime = System.currentTimeMillis();
		}
	}

	@UiThread
	void appendEncryptedAudioChunk(byte[] encrypted, int len, byte[] tagPart) {
		synchronized (encryptedVoiceChunks) {
			byte[] chunk = java.util.Arrays.copyOf(encrypted, len);
			encryptedVoiceChunks.add(chunk);
			encryptedChunkTags.add(java.util.Arrays.copyOf(tagPart, tagPart.length));
		}
	}

	@UiThread
	void finalizeEncryptedVoiceMessage(byte[] globalMAC, int totalDurationMs, int chunkCount) {
		final byte[] ivCopy;
		final byte[] wrappedKeyCopy;
		final java.util.List<byte[]> chunksCopy;
		final java.util.List<byte[]> tagsCopy;

		synchronized (encryptedVoiceChunks) {
			if (currentIv == null || wrappedKey == null || encryptedVoiceChunks.isEmpty()) {
				zeroizeVoiceRecordingState();
				return;
			}

			ivCopy = java.util.Arrays.copyOf(currentIv, currentIv.length);
			wrappedKeyCopy = java.util.Arrays.copyOf(wrappedKey, wrappedKey.length);
			chunksCopy = new java.util.ArrayList<>(encryptedVoiceChunks.size());
			for (byte[] chunk : encryptedVoiceChunks) {
				chunksCopy.add(java.util.Arrays.copyOf(chunk, chunk.length));
			}
			tagsCopy = new java.util.ArrayList<>(encryptedChunkTags.size());
			for (byte[] tag : encryptedChunkTags) {
				tagsCopy.add(java.util.Arrays.copyOf(tag, tag.length));
			}
			zeroizeVoiceRecordingState();
		}

		final byte[] globalMACCopy = java.util.Arrays.copyOf(globalMAC, globalMAC.length);

		runOnDbThread(() -> {
			try {
				storeEncryptedVoiceMessage(
					ivCopy,
					wrappedKeyCopy,
					chunksCopy,
					tagsCopy,
					totalDurationMs,
					globalMACCopy
				);
			} catch (Exception e) {
				handleException(e);
			}
		});
	}

	@UiThread
	void cancelVoiceRecording() {
		synchronized (encryptedVoiceChunks) {
			zeroizeVoiceRecordingState();
		}
	}

	private void zeroizeVoiceRecordingState() {
		if (currentIv != null) {
			java.util.Arrays.fill(currentIv, (byte) 0);
			currentIv = null;
		}
		if (wrappedKey != null) {
			java.util.Arrays.fill(wrappedKey, (byte) 0);
			wrappedKey = null;
		}
		for (byte[] chunk : encryptedVoiceChunks) {
			java.util.Arrays.fill(chunk, (byte) 0);
		}
		for (byte[] tag : encryptedChunkTags) {
			java.util.Arrays.fill(tag, (byte) 0);
		}
		encryptedVoiceChunks.clear();
		encryptedChunkTags.clear();
		voiceRecordingStartTime = 0;
		voiceMessageGroupId = null;
	}

	@DatabaseExecutor
	private void storeEncryptedVoiceMessage(byte[] iv,
	                                        byte[] encryptedKey,
	                                        java.util.List<byte[]> chunks,
	                                        java.util.List<byte[]> tags,
	                                        int durationMs,
	                                        byte[] globalMAC) {
		byte[] payload = null;
		try {
			payload = com.professor.zerion.android.conversation.voice.VoiceMessagePayloadBuilder.build(
				iv, encryptedKey, chunks, tags, durationMs, globalMAC);

			Contact contact = requireNonNull(contactItem.getValue()).getContact();
			GroupId groupId = messagingManager.getContactGroup(contact).getId();

			long timestamp = db.transactionWithResult(false, txn ->
				conversationManager.getTimestampForOutgoingMessage(txn, requireNonNull(contactId)));
			String messageText = com.professor.zerion.android.conversation.voice.VoiceMessageFormat
				.format(durationMs, payload);

			PrivateMessage pm;
			try {
				pm = privateMessageFactory.createLegacyPrivateMessage(
					groupId, timestamp, messageText);
			} catch (FormatException e) {
				throw new AssertionError("Failed to create voice message", e);
			}

			db.transaction(false, txn -> {
				messagingManager.addLocalMessage(txn, pm);

				Message message = pm.getMessage();
				PrivateMessageHeader header = new PrivateMessageHeader(
					message.getId(), message.getGroupId(),
					message.getTimestamp(), true, true, false, false,
					true, pm.getAttachmentHeaders(),
					pm.getAutoDeleteTimer());

				txn.attach(() -> addedHeader.postEvent(header));
			});

		} catch (DbException e) {
			handleException(e);
		} catch (Exception e) {
			handleException(new DbException(e));
		} finally {
			if (payload != null) {
				java.util.Arrays.fill(payload, (byte) 0);
			}
			if (iv != null) java.util.Arrays.fill(iv, (byte) 0);
			if (encryptedKey != null) java.util.Arrays.fill(encryptedKey, (byte) 0);
			for (byte[] chunk : chunks) {
				java.util.Arrays.fill(chunk, (byte) 0);
			}
			for (byte[] tag : tags) {
				java.util.Arrays.fill(tag, (byte) 0);
			}
		}
	}

	
	@UiThread
	LiveData<AttachmentResult> storeVoiceAttachment(android.net.Uri audioUri) {
		java.util.Collection<android.net.Uri> uris = java.util.Collections.singleton(audioUri);
		PrivateMessageFormat format = privateMessageFormat.getValue();
		if (format == null) {
			format = TEXT_IMAGES;
		}
		return attachmentCreator.storeAttachments(messagingGroupId, uris, format);
	}

	
	@UiThread
	LiveData<SendState> sendVoiceAttachment(long expectedTimer) {
		java.util.List<AttachmentHeader> headers = attachmentCreator.getAttachmentHeadersForSending();
		if (headers.isEmpty()) {
			MutableLiveData<SendState> errorResult = new MutableLiveData<>();
			errorResult.setValue(SendState.ERROR);
			return errorResult;
		}
		return sendMessage(null, headers, expectedTimer, null);
	}

	
	@UiThread
	void cancelVoiceAttachment() {
		attachmentCreator.cancel();
	}

	
	void loadMessageHeaders() {
		if (contactId == null) return;
		messagesLoading.setValue(true);
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				Collection<ConversationMessageHeader> headers =
						conversationManager.getMessageHeaders(c);
				Map<MessageId, String> texts = messagingManager.getMessageTexts(c);
				messageTexts.postValue(texts);
				messageHeaders.postValue(headers);
			} catch (NoSuchContactException e) {
				contactDeleted.postValue(true);
			} catch (DbException e) {
				handleException(e);
			} finally {
				messagesLoading.postValue(false);
			}
		});
	}

	
	void loadMessageText(MessageId messageId) {
		runOnDbThread(() -> {
			try {
				String text = messagingManager.getMessageText(messageId);
				if (text != null) {
					messageTextLoaded.postEvent(new Pair<>(messageId, text));
				}
			} catch (org.briarproject.bramble.api.db.NoSuchMessageException e) {
				// Message was deleted (auto-delete timer, cleanup) — ignore
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	
	void deleteMessages(Collection<MessageId> messageIds) {
		if (contactId == null || messageIds.isEmpty()) return;
		final ContactId c = contactId;
		messagesDeleted.postEvent(messageIds);
		runOnDbThread(() -> {
			try {
				conversationManager.deleteMessages(c, messageIds);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	
	void clearChat() {
		if (contactId == null) return;
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				conversationManager.deleteAllMessages(c);
				messageHeaders.postValue(
						new java.util.ArrayList<>());
				messageTexts.postValue(
						new java.util.HashMap<>());
				chatCleared.postEvent(true);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	
	void removeContact() {
		if (contactId == null) return;
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				contactManager.removeContact(c);
				contactDeleted.postValue(true);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	
	void markMessages(Collection<MessageId> messageIds, boolean sent, boolean seen) {
		messagesMarked.postEvent(new MarkMessagesEvent(messageIds, sent, seen));
	}

	LiveData<Collection<ConversationMessageHeader>> getMessageHeaders() {
		return messageHeaders;
	}

	LiveData<Map<MessageId, String>> getMessageTexts() {
		return messageTexts;
	}

	LiveData<Boolean> isMessagesLoading() {
		return messagesLoading;
	}

	LiveEvent<Pair<MessageId, String>> getMessageTextLoaded() {
		return messageTextLoaded;
	}

	LiveEvent<Boolean> getChatCleared() {
		return chatCleared;
	}

	LiveEvent<Collection<MessageId>> getMessagesDeleted() {
		return messagesDeleted;
	}

	LiveEvent<MarkMessagesEvent> getMessagesMarked() {
		return messagesMarked;
	}

	void cleanupVoiceCallMessages(String callId) {
	}

	void setAutoDeleteTimerEnabled(boolean enabled) {
		long timer = enabled ? DEFAULT_TIMER_DURATION : NO_AUTO_DELETE_TIMER;
		setAutoDeleteTimer(timer);
	}

	void setAutoDeleteTimer(long timer) {
		final ContactId c = requireNonNull(contactId);
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn ->
						autoDeleteManager.setAutoDeleteTimer(txn, c, timer));
				autoDeleteTimer.postValue(timer);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	AttachmentRetriever getAttachmentRetriever() {
		return attachmentRetriever;
	}

	LiveData<ContactItem> getContactItem() {
		return contactItem;
	}

	LiveData<String> getContactDisplayName() {
		return contactName;
	}

	LiveData<PrivateMessageFormat> getPrivateMessageFormat() {
		return privateMessageFormat;
	}

	LiveEvent<Boolean> showImageOnboarding() {
		return showImageOnboarding;
	}

	LiveEvent<Boolean> showIntroductionOnboarding() {
		return showIntroductionOnboarding;
	}

	LiveData<Boolean> showIntroductionAction() {
		return showIntroductionAction;
	}

	LiveData<Long> getAutoDeleteTimer() {
		return autoDeleteTimer;
	}

	LiveData<Boolean> isContactDeleted() {
		return contactDeleted;
	}

	LiveEvent<PrivateMessageHeader> getAddedPrivateMessage() {
		return addedHeader;
	}

	@UiThread
	void recheckFeaturesAndOnboarding(ContactId contactId) {
		runOnDbThread(() -> {
			try {
				checkFeaturesAndOnboarding(contactId);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@UiThread
	void setReplyTarget(@Nullable ConversationItem item) {
		replyTarget.setValue(item);
	}

	LiveData<ConversationItem> getReplyTarget() {
		return replyTarget;
	}

	@UiThread
	void clearReplyTarget() {
		replyTarget.setValue(null);
	}

	LiveData<Boolean> isContactConnected() {
		return contactConnected;
	}

	LiveEvent<ConversationMessageHeader> getNewMessageReceived() {
		return newMessageReceived;
	}

	LiveEvent<ClientId> getClientVersionUpdated() {
		return clientVersionUpdated;
	}

	
	void checkConnectionStatus(org.briarproject.bramble.api.connection.ConnectionRegistry registry) {
		if (contactId != null) {
			contactConnected.postValue(registry.isConnected(contactId));
		}
	}

	void sendReaction(MessageId targetMessageId, String emoji) {
		if (contactId == null) return;
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				messagingManager.addLocalReaction(c, targetMessageId, emoji);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveEvent<ReactionReceivedEvent> getReactionReceived() {
		return reactionReceived;
	}

	LiveData<Map<MessageId, Map<String, Integer>>> getReactionsMap() {
		return reactionsMap;
	}

	void loadReactions() {
		if (contactId == null) return;
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				Map<MessageId, Map<String, Integer>> reactions =
						messagingManager.getReactions(c);
				reactionsMap.postValue(reactions);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void sendTypingIndicator(boolean isTyping) {
		if (contactId == null) return;
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				messagingManager.sendTypingIndicator(c, isTyping);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<Boolean> isContactTyping() {
		return contactTyping;
	}

	void loadContactsForForward(
			androidx.arch.core.util.Function<List<Contact>, Void> callback) {
		runOnDbThread(() -> {
			try {
				Collection<Contact> contacts = contactManager.getContacts();
				List<Contact> filtered = new ArrayList<>();
				for (Contact c : contacts) {
					if (!c.getId().equals(contactId)) {
						filtered.add(c);
					}
				}
				androidExecutor.runOnUiThread(
						() -> callback.apply(filtered));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void forwardMessage(ContactId recipientId, String text,
			Runnable onSuccess, Runnable onFailure) {
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn -> {
					GroupId groupId = messagingManager
							.getConversationId(txn, recipientId);
					long timestamp = conversationManager
							.getTimestampForOutgoingMessage(txn,
									recipientId);
					PrivateMessage pm = privateMessageFactory
							.createLegacyPrivateMessage(groupId,
									timestamp, text);
					messagingManager.addLocalMessage(txn, pm);
				});
				androidExecutor.runOnUiThread(onSuccess);
			} catch (DbException e) {
				handleException(e);
				androidExecutor.runOnUiThread(onFailure);
			} catch (FormatException e) {
				androidExecutor.runOnUiThread(onFailure);
			}
		});
	}

	void loadLinkPreviews() {
		if (contactId == null) return;
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				Map<MessageId, LinkPreview> previews =
						messagingManager.getLinkPreviews(c);
				linkPreviewsMap.postValue(previews);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<Map<MessageId, LinkPreview>> getLinkPreviewsMap() {
		return linkPreviewsMap;
	}

	LiveData<LinkPreview> getPendingLinkPreview() {
		return pendingLinkPreview;
	}

	void fetchLinkPreview(String url, int torSocksPort) {
		runOnDbThread(() -> {
			com.professor.zerion.android.conversation.linkpreview
					.LinkPreviewFetcher fetcher =
					new com.professor.zerion.android.conversation.linkpreview
							.LinkPreviewFetcher(torSocksPort);
			LinkPreview preview = fetcher.fetch(url);
			pendingLinkPreview.postValue(preview);
		});
	}

	void clearPendingLinkPreview() {
		pendingLinkPreview.postValue(null);
	}

	@UiThread
	LiveData<SendState> sendMessageWithPreview(@Nullable String text,
			long expectedTimer, LinkPreview preview) {
		MutableLiveData<SendState> liveData = new MutableLiveData<>();
		if (contactId == null) {
			liveData.setValue(ERROR);
			return liveData;
		}
		final ContactId c = contactId;
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn -> {
					messagingManager.addLocalLinkPreviewMessage(
							txn, c, text, preview);
					txn.attach(() -> liveData.setValue(SENT));
				});
			} catch (DbException e) {
				liveData.postValue(ERROR);
			}
		});
		return liveData;
	}
}
