package org.zerionproject.core.api.db;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.TransportKeySet;
import org.zerionproject.core.api.transport.TransportKeys;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public interface DatabaseComponent extends TransactionManager {

	long NO_CLEANUP_DEADLINE = -1;

	long TIMER_NOT_STARTED = -1;

	boolean open(SecretKey key, @Nullable MigrationListener listener)
			throws DbException;

	void close() throws DbException;

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified) throws DbException;

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum)
			throws DbException;

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum,
			boolean pcsEnabled) throws DbException;

	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum,
			boolean pcsEnabled,
			@Nullable byte[] mlDsaSigPublicKey) throws DbException;

	void addGroup(Transaction txn, Group g) throws DbException;

	void addIdentity(Transaction txn, Identity i) throws DbException;

	void addLocalMessage(Transaction txn, Message m, Metadata meta,
			boolean shared, boolean temporary) throws DbException;

	void addPendingContact(Transaction txn, PendingContact p, AuthorId local)
			throws DbException;

	void addTransport(Transaction txn, TransportId t, long maxLatency)
			throws DbException;

	KeySetId addTransportKeys(Transaction txn, ContactId c, TransportKeys k)
			throws DbException;

	KeySetId addTransportKeys(Transaction txn, PendingContactId p,
			TransportKeys k) throws DbException;

	boolean containsAcksToSend(Transaction txn, ContactId c) throws DbException;

	boolean containsContact(Transaction txn, AuthorId remote, AuthorId local)
			throws DbException;

	boolean containsGroup(Transaction txn, GroupId g) throws DbException;

	boolean containsIdentity(Transaction txn, AuthorId a) throws DbException;

	boolean containsMessagesToSend(Transaction txn, ContactId c,
			long maxLatency, boolean eager) throws DbException;

	boolean containsPendingContact(Transaction txn, PendingContactId p)
			throws DbException;

	boolean containsTransportKeys(Transaction txn, ContactId c, TransportId t)
			throws DbException;

	void deleteMessage(Transaction txn, MessageId m) throws DbException;

	void deleteMessageMetadata(Transaction txn, MessageId m) throws DbException;

	@Nullable
	Ack generateAck(Transaction txn, ContactId c, int maxMessages)
			throws DbException;

	@Nullable
	Collection<Message> generateBatch(Transaction txn, ContactId c,
			long capacity, long maxLatency) throws DbException;

	@Nullable
	Offer generateOffer(Transaction txn, ContactId c, int maxMessages,
			long maxLatency) throws DbException;

	@Nullable
	Request generateRequest(Transaction txn, ContactId c, int maxMessages)
			throws DbException;

	@Nullable
	Collection<Message> generateRequestedBatch(Transaction txn, ContactId c,
			long capacity, long maxLatency) throws DbException;

	Contact getContact(Transaction txn, ContactId c) throws DbException;

	Collection<Contact> getContacts(Transaction txn) throws DbException;

	Collection<Contact> getContactsByAuthorId(Transaction txn, AuthorId remote)
			throws DbException;

	Collection<ContactId> getContacts(Transaction txn, AuthorId local)
			throws DbException;

	Group getGroup(Transaction txn, GroupId g) throws DbException;

	GroupId getGroupId(Transaction txn, MessageId m) throws DbException;

	Metadata getGroupMetadata(Transaction txn, GroupId g) throws DbException;

	Collection<Group> getGroups(Transaction txn, ClientId c, int majorVersion)
			throws DbException;

	Visibility getGroupVisibility(Transaction txn, ContactId c, GroupId g)
			throws DbException;

	Identity getIdentity(Transaction txn, AuthorId a) throws DbException;

	Collection<Identity> getIdentities(Transaction txn) throws DbException;

	Message getMessage(Transaction txn, MessageId m) throws DbException;

	Collection<MessageId> getMessageIds(Transaction txn, GroupId g)
			throws DbException;

	Collection<MessageId> getAllMessageIds(Transaction txn, GroupId g)
			throws DbException;

	Collection<MessageId> getMessageIds(Transaction txn, GroupId g,
			Metadata query) throws DbException;

	Collection<MessageId> getMessagesToAck(Transaction txn, ContactId c)
			throws DbException;

	Collection<MessageId> getMessagesToSend(Transaction txn, ContactId c,
			long capacity, long maxLatency) throws DbException;

	Collection<MessageId> getMessagesToValidate(Transaction txn)
			throws DbException;

	Collection<MessageId> getPendingMessages(Transaction txn)
			throws DbException;

	Collection<MessageId> getMessagesToShare(Transaction txn)
			throws DbException;

	Map<GroupId, Collection<MessageId>> getMessagesToDelete(Transaction txn)
			throws DbException;

	Map<MessageId, Metadata> getMessageMetadata(Transaction txn, GroupId g)
			throws DbException;

	Map<MessageId, Metadata> getMessageMetadata(Transaction txn, GroupId g,
			Metadata query) throws DbException;

	Metadata getMessageMetadata(Transaction txn, MessageId m)
			throws DbException;

	Metadata getMessageMetadataForValidator(Transaction txn, MessageId m)
			throws DbException;

	Collection<MessageStatus> getMessageStatus(Transaction txn, ContactId c,
			GroupId g) throws DbException;

	Map<MessageId, MessageState> getMessageDependencies(Transaction txn,
			MessageId m) throws DbException;

	Map<MessageId, MessageState> getMessageDependents(Transaction txn,
			MessageId m) throws DbException;

	MessageState getMessageState(Transaction txn, MessageId m)
			throws DbException;

	MessageStatus getMessageStatus(Transaction txn, ContactId c, MessageId m)
			throws DbException;

	@Nullable
	Message getMessageToSend(Transaction txn, ContactId c, MessageId m,
			long maxLatency, boolean markAsSent) throws DbException;

	Collection<MessageId> getUnackedMessagesToSend(Transaction txn,
			ContactId c) throws DbException;

	void resetUnackedMessagesToSend(Transaction txn, ContactId c)
			throws DbException;

	long getUnackedMessageBytesToSend(Transaction txn, ContactId c)
			throws DbException;

	long getNextCleanupDeadline(Transaction txn) throws DbException;

	long getNextSendTime(Transaction txn, ContactId c, long maxLatency)
			throws DbException;

	PendingContact getPendingContact(Transaction txn, PendingContactId p)
			throws DbException;

	Collection<PendingContact> getPendingContacts(Transaction txn)
			throws DbException;

	Settings getSettings(Transaction txn, String namespace) throws DbException;

	List<Byte> getSyncVersions(Transaction txn, ContactId c) throws DbException;

	Collection<TransportKeySet> getTransportKeys(Transaction txn, TransportId t)
			throws DbException;

	Map<ContactId, Collection<TransportId>> getTransportsWithKeys(
			Transaction txn) throws DbException;

	void incrementStreamCounter(Transaction txn, TransportId t, KeySetId k)
			throws DbException;

	void mergeGroupMetadata(Transaction txn, GroupId g, Metadata meta)
			throws DbException;

	void mergeMessageMetadata(Transaction txn, MessageId m, Metadata meta)
			throws DbException;

	void mergeSettings(Transaction txn, Settings s, String namespace)
			throws DbException;

	void receiveAck(Transaction txn, ContactId c, Ack a) throws DbException;

	void receiveMessage(Transaction txn, ContactId c, Message m)
			throws DbException;

	void receiveOffer(Transaction txn, ContactId c, Offer o) throws DbException;

	void receiveRequest(Transaction txn, ContactId c, Request r)
			throws DbException;

	void removeContact(Transaction txn, ContactId c) throws DbException;

	void removeGroup(Transaction txn, Group g) throws DbException;

	void removeIdentity(Transaction txn, AuthorId a) throws DbException;

	void removeMessage(Transaction txn, MessageId m) throws DbException;

	void removeAllGroupMessages(Transaction txn, GroupId g)
			throws DbException;

	void removePendingContact(Transaction txn, PendingContactId p)
			throws DbException;

	void removeTemporaryMessages(Transaction txn) throws DbException;

	void removeTransport(Transaction txn, TransportId t) throws DbException;

	void removeTransportKeys(Transaction txn, TransportId t, KeySetId k)
			throws DbException;

	void setAckSent(Transaction txn, ContactId c, Collection<MessageId> acked)
			throws DbException;

	void setCleanupTimerDuration(Transaction txn, MessageId m, long duration)
			throws DbException;

	void setContactVerified(Transaction txn, ContactId c) throws DbException;

	void setContactAlias(Transaction txn, ContactId c, @Nullable String alias)
			throws DbException;

	void setContactPcsEnabled(Transaction txn, ContactId c, boolean pcsEnabled)
			throws DbException;

	void setGroupVisibility(Transaction txn, ContactId c, GroupId g,
			Visibility v) throws DbException;

	void setMessagePermanent(Transaction txn, MessageId m) throws DbException;

	void setMessageNotShared(Transaction txn, MessageId m) throws DbException;

	void setMessageShared(Transaction txn, MessageId m) throws DbException;

	void setMessageState(Transaction txn, MessageId m, MessageState state)
			throws DbException;

	void setMessagesSent(Transaction txn, ContactId c,
			Collection<MessageId> sent, long maxLatency) throws DbException;

	void addMessageDependencies(Transaction txn, Message dependent,
			Collection<MessageId> dependencies) throws DbException;

	void setHandshakeKeyPair(Transaction txn, AuthorId local,
			PublicKey publicKey, PrivateKey privateKey) throws DbException;

	void setHybridHandshakeKeyPair(Transaction txn, AuthorId local,
			PublicKey publicKey, PrivateKey privateKey) throws DbException;

	void setMlDsaSigKeyPair(Transaction txn, AuthorId local,
			byte[] publicKey, byte[] privateKey) throws DbException;

	void setContactMlDsaSigPublicKey(Transaction txn, ContactId c,
			byte[] publicKey) throws DbException;

	void setReorderingWindow(Transaction txn, KeySetId k, TransportId t,
			long timePeriod, long base, byte[] bitmap) throws DbException;

	void setSyncVersions(Transaction txn, ContactId c, List<Byte> supported)
			throws DbException;

	void setTransportKeysActive(Transaction txn, TransportId t, KeySetId k)
			throws DbException;

	long startCleanupTimer(Transaction txn, MessageId m) throws DbException;

	void stopCleanupTimer(Transaction txn, MessageId m) throws DbException;

	void updateTransportKeys(Transaction txn, Collection<TransportKeySet> keys)
			throws DbException;

	int PCS_DIRECTION_SEND = 0;

	int PCS_DIRECTION_RECEIVE = 1;

	void setPcsSessionState(Transaction txn, ContactId c, int direction,
			SecretKey chainKey, int messageNumber, int previousChainLength)
			throws DbException;

	@Nullable
	Object[] getPcsSessionState(Transaction txn, ContactId c, int direction)
			throws DbException;

	boolean containsPcsSessionState(Transaction txn, ContactId c)
			throws DbException;

	void addPcsSkippedKey(Transaction txn, ContactId c, int direction,
			int messageNumber, SecretKey messageKey, long timestamp)
			throws DbException;

	@Nullable
	SecretKey getPcsSkippedKey(Transaction txn, ContactId c, int direction,
			int messageNumber) throws DbException;

	int getPcsSkippedKeyCount(Transaction txn, ContactId c, int direction)
			throws DbException;

	int prunePcsSkippedKeys(Transaction txn, long maxAge) throws DbException;

	void removePcsState(Transaction txn, ContactId c) throws DbException;

	void setPcsMode2SessionState(Transaction txn, ContactId c, int direction,
			SecretKey chainKey, int messageNumber, int previousChainLength,
			@Nullable SecretKey rootKey, @Nullable PrivateKey dhPrivateKey,
			@Nullable PublicKey dhPublicKey, @Nullable PublicKey dhRemotePublicKey,
			boolean mode2Enabled,
			@Nullable byte[] mode3FullStateBlob) throws DbException;

	@Nullable
	Object[] getPcsMode2SessionState(Transaction txn, ContactId c, int direction)
			throws DbException;

	void addPcsMode2SkippedKey(Transaction txn, byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp) throws DbException;

	@Nullable
	SecretKey getPcsMode2SkippedKey(Transaction txn, byte[] chainId,
			int messageNumber) throws DbException;

	void setPqRatchetState(Transaction txn, ContactId c, long currentEpoch,
			long epochStartTime, int messagesSinceEpoch, int state,
			boolean isInitiator, int chunksSent, int chunksReceived,
			@Nullable byte[] ourEkSeed, @Nullable byte[] ourEkVector,
			@Nullable byte[] ourDecapsKey, @Nullable byte[] theirEkSeed,
			@Nullable byte[] theirEkHash, @Nullable byte[] theirEkVector,
			@Nullable byte[] ciphertext, @Nullable byte[] pendingChunks)
			throws DbException;

	@Nullable
	Object[] getPqRatchetState(Transaction txn, ContactId c) throws DbException;

	boolean containsPqRatchetState(Transaction txn, ContactId c)
			throws DbException;

	void removePqRatchetState(Transaction txn, ContactId c) throws DbException;
}
