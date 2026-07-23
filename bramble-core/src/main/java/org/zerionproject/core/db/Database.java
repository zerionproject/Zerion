package org.zerionproject.core.db;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DataTooNewException;
import org.zerionproject.core.api.db.DataTooOldException;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.MessageDeletedException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.MigrationListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.TransportKeySet;
import org.zerionproject.core.api.transport.TransportKeys;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
interface Database<T> {

	boolean open(SecretKey key, @Nullable MigrationListener listener)
			throws DbException;

	void close() throws DbException;

	boolean wasDirtyOnInitialisation();

	T startTransaction() throws DbException;

	void abortTransaction(T txn);

	void commitTransaction(T txn) throws DbException;

	ContactId addContact(T txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified) throws DbException;

	ContactId addContact(T txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum)
			throws DbException;

	ContactId addContact(T txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum,
			boolean pcsEnabled) throws DbException;

	ContactId addContact(T txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum,
			boolean pcsEnabled,
			@Nullable byte[] mlDsaSigPublicKey) throws DbException;

	void addGroup(T txn, Group g) throws DbException;

	void addGroupVisibility(T txn, ContactId c, GroupId g, boolean shared)
			throws DbException;

	void addIdentity(T txn, Identity i) throws DbException;

	void addMessage(T txn, Message m, MessageState state, boolean shared,
			boolean temporary, @Nullable ContactId sender) throws DbException;

	void addMessageDependency(T txn, Message dependent, MessageId dependency,
			MessageState dependentState) throws DbException;

	void addOfferedMessage(T txn, ContactId c, MessageId m) throws DbException;

	void addPendingContact(T txn, PendingContact p) throws DbException;

	void addTransport(T txn, TransportId t, long maxLatency)
			throws DbException;

	KeySetId addTransportKeys(T txn, ContactId c, TransportKeys k)
			throws DbException;

	KeySetId addTransportKeys(T txn, PendingContactId p, TransportKeys k)
			throws DbException;

	boolean containsAcksToSend(T txn, ContactId c) throws DbException;

	boolean containsContact(T txn, AuthorId remote, AuthorId local)
			throws DbException;

	boolean containsContact(T txn, ContactId c) throws DbException;

	boolean containsGroup(T txn, GroupId g) throws DbException;

	boolean containsIdentity(T txn, AuthorId a) throws DbException;

	boolean containsMessage(T txn, MessageId m) throws DbException;

	boolean containsMessagesToSend(T txn, ContactId c, long maxLatency,
			boolean eager) throws DbException;

	boolean containsPendingContact(T txn, PendingContactId p)
			throws DbException;

	boolean containsTransport(T txn, TransportId t) throws DbException;

	boolean containsTransportKeys(T txn, ContactId c, TransportId t)
			throws DbException;

	boolean containsVisibleMessage(T txn, ContactId c, MessageId m)
			throws DbException;

	int countOfferedMessages(T txn, ContactId c) throws DbException;

	void deleteMessage(T txn, MessageId m) throws DbException;

	void deleteMessageMetadata(T txn, MessageId m) throws DbException;

	Contact getContact(T txn, ContactId c) throws DbException;

	Collection<Contact> getContacts(T txn) throws DbException;

	Collection<Contact> getContactsByAuthorId(T txn, AuthorId remote)
			throws DbException;

	Collection<ContactId> getContacts(T txn, AuthorId local) throws DbException;

	@Nullable
	Contact getContact(T txn, PublicKey handshakePublicKey, AuthorId local)
			throws DbException;

	Group getGroup(T txn, GroupId g) throws DbException;

	GroupId getGroupId(T txn, MessageId m) throws DbException;

	Metadata getGroupMetadata(T txn, GroupId g) throws DbException;

	Collection<Group> getGroups(T txn, ClientId c, int majorVersion)
			throws DbException;

	Visibility getGroupVisibility(T txn, ContactId c, GroupId g)
			throws DbException;

	Map<ContactId, Boolean> getGroupVisibility(T txn, GroupId g)
			throws DbException;

	Identity getIdentity(T txn, AuthorId a) throws DbException;

	Collection<Identity> getIdentities(T txn) throws DbException;

	Message getMessage(T txn, MessageId m) throws DbException;

	Map<MessageId, MessageState> getMessageDependencies(T txn, MessageId m)
			throws DbException;

	Map<MessageId, MessageState> getMessageDependents(T txn, MessageId m)
			throws DbException;

	Collection<MessageId> getMessageIds(T txn, GroupId g) throws DbException;

	Collection<MessageId> getAllMessageIds(T txn, GroupId g)
			throws DbException;

	Collection<MessageId> getMessageIds(T txn, GroupId g, Metadata query)
			throws DbException;

	int getMessageLength(T txn, MessageId m) throws DbException;

	Map<MessageId, Metadata> getMessageMetadata(T txn, GroupId g)
			throws DbException;

	Map<MessageId, Metadata> getMessageMetadata(T txn, GroupId g,
			Metadata query) throws DbException;

	Metadata getMessageMetadataForValidator(T txn, MessageId m)
			throws DbException;

	Metadata getMessageMetadata(T txn, MessageId m) throws DbException;

	MessageState getMessageState(T txn, MessageId m) throws DbException;

	Collection<MessageStatus> getMessageStatus(T txn, ContactId c, GroupId g)
			throws DbException;

	@Nullable
	MessageStatus getMessageStatus(T txn, ContactId c, MessageId m)
			throws DbException;

	Collection<MessageId> getMessagesToAck(T txn, ContactId c, int maxMessages)
			throws DbException;

	Collection<MessageId> getMessagesToOffer(T txn, ContactId c,
			int maxMessages, long maxLatency) throws DbException;

	Collection<MessageId> getMessagesToRequest(T txn, ContactId c,
			int maxMessages) throws DbException;

	Collection<MessageId> getMessagesToSend(T txn, ContactId c, long capacity,
			long maxLatency) throws DbException;

	Collection<MessageId> getUnackedMessagesToSend(T txn, ContactId c)
			throws DbException;

	long getUnackedMessageBytesToSend(T txn, ContactId c) throws DbException;

	Collection<MessageId> getMessagesToValidate(T txn) throws DbException;

	Collection<MessageId> getPendingMessages(T txn) throws DbException;

	Collection<MessageId> getMessagesToShare(T txn) throws DbException;

	Map<GroupId, Collection<MessageId>> getMessagesToDelete(T txn)
			throws DbException;

	long getNextCleanupDeadline(T txn) throws DbException;

	long getNextSendTime(T txn, ContactId c, long maxLatency)
			throws DbException;

	PendingContact getPendingContact(T txn, PendingContactId p)
			throws DbException;

	Collection<PendingContact> getPendingContacts(T txn) throws DbException;

	Collection<MessageId> getRequestedMessagesToSend(T txn, ContactId c,
			long capacity, long maxLatency) throws DbException;

	Settings getSettings(T txn, String namespace) throws DbException;

	List<Byte> getSyncVersions(T txn, ContactId c) throws DbException;

	Collection<TransportKeySet> getTransportKeys(T txn, TransportId t)
			throws DbException;

	Map<ContactId, Collection<TransportId>> getTransportsWithKeys(T txn)
			throws DbException;

	void incrementStreamCounter(T txn, TransportId t, KeySetId k)
			throws DbException;

	void lowerAckFlag(T txn, ContactId c, Collection<MessageId> acked)
			throws DbException;

	void lowerRequestedFlag(T txn, ContactId c, Collection<MessageId> requested)
			throws DbException;

	void mergeGroupMetadata(T txn, GroupId g, Metadata meta)
			throws DbException;

	void mergeMessageMetadata(T txn, MessageId m, Metadata meta)
			throws DbException;

	void mergeSettings(T txn, Settings s, String namespace) throws DbException;

	void raiseAckFlag(T txn, ContactId c, MessageId m) throws DbException;

	void raiseRequestedFlag(T txn, ContactId c, MessageId m) throws DbException;

	boolean raiseSeenFlag(T txn, ContactId c, MessageId m) throws DbException;

	void removeContact(T txn, ContactId c) throws DbException;

	void removeGroup(T txn, GroupId g) throws DbException;

	void removeGroupVisibility(T txn, ContactId c, GroupId g)
			throws DbException;

	void removeIdentity(T txn, AuthorId a) throws DbException;

	void removeMessage(T txn, MessageId m) throws DbException;

	void removeAllGroupMessages(T txn, GroupId g) throws DbException;

	void removeOfferedMessages(T txn, ContactId c,
			Collection<MessageId> requested) throws DbException;

	void removePendingContact(T txn, PendingContactId p) throws DbException;

	void removeTemporaryMessages(T txn) throws DbException;

	void removeTransport(T txn, TransportId t) throws DbException;

	void removeTransportKeys(T txn, TransportId t, KeySetId k)
			throws DbException;

	void resetExpiryTime(T txn, ContactId c, MessageId m) throws DbException;

	void resetUnackedMessagesToSend(T txn, ContactId c) throws DbException;

	void setCleanupTimerDuration(T txn, MessageId m, long duration)
			throws DbException;

	void setContactVerified(T txn, ContactId c) throws DbException;

	void setContactAlias(T txn, ContactId c, @Nullable String alias)
			throws DbException;

	void setContactPcsEnabled(T txn, ContactId c, boolean pcsEnabled)
			throws DbException;

	void setGroupVisibility(T txn, ContactId c, GroupId g, boolean shared)
			throws DbException;

	void setHandshakeKeyPair(T txn, AuthorId local, PublicKey publicKey,
			PrivateKey privateKey) throws DbException;

	void setHybridHandshakeKeyPair(T txn, AuthorId local, PublicKey publicKey,
			PrivateKey privateKey) throws DbException;

	void setMlDsaSigKeyPair(T txn, AuthorId local, byte[] publicKey,
			byte[] privateKey) throws DbException;

	void setContactMlDsaSigPublicKey(T txn, ContactId c, byte[] publicKey)
			throws DbException;

	void setMessagePermanent(T txn, MessageId m) throws DbException;

	void setMessageShared(T txn, MessageId m, boolean shared)
			throws DbException;

	void setMessageState(T txn, MessageId m, MessageState state)
			throws DbException;

	void setReorderingWindow(T txn, KeySetId k, TransportId t,
			long timePeriod, long base, byte[] bitmap) throws DbException;

	void setSyncVersions(T txn, ContactId c, List<Byte> supported)
			throws DbException;

	void setTransportKeysActive(T txn, TransportId t, KeySetId k)
			throws DbException;

	long startCleanupTimer(T txn, MessageId m) throws DbException;

	void stopCleanupTimer(T txn, MessageId m) throws DbException;

	void updateRetransmissionData(T txn, ContactId c, MessageId m,
			long maxLatency) throws DbException;

	void updateTransportKeys(T txn, TransportKeySet ks) throws DbException;

	int PCS_DIRECTION_SEND = 0;
	int PCS_DIRECTION_RECEIVE = 1;

	void setPcsSessionState(T txn, ContactId c, int direction,
			SecretKey chainKey, int messageNumber, int previousChainLength)
			throws DbException;

	@Nullable
	Object[] getPcsSessionState(T txn, ContactId c, int direction)
			throws DbException;

	boolean containsPcsSessionState(T txn, ContactId c) throws DbException;

	void addPcsSkippedKey(T txn, ContactId c, int direction,
			int messageNumber, SecretKey messageKey, long timestamp)
			throws DbException;

	@Nullable
	SecretKey getPcsSkippedKey(T txn, ContactId c, int direction,
			int messageNumber) throws DbException;

	int getPcsSkippedKeyCount(T txn, ContactId c, int direction)
			throws DbException;

	int prunePcsSkippedKeys(T txn, long maxAge) throws DbException;

	void removePcsState(T txn, ContactId c) throws DbException;

	void setPcsMode2SessionState(T txn, ContactId c, int direction,
			SecretKey chainKey, int messageNumber, int previousChainLength,
			@Nullable SecretKey rootKey, @Nullable PrivateKey dhPrivateKey,
			@Nullable PublicKey dhPublicKey, @Nullable PublicKey dhRemotePublicKey,
			boolean mode2Enabled,
			@Nullable byte[] mode3FullStateBlob) throws DbException;

	@Nullable
	Object[] getPcsMode2SessionState(T txn, ContactId c, int direction)
			throws DbException;

	void addPcsMode2SkippedKey(T txn, byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp) throws DbException;

	@Nullable
	SecretKey getPcsMode2SkippedKey(T txn, byte[] chainId, int messageNumber)
			throws DbException;

	void setPqRatchetState(T txn, ContactId c, long currentEpoch,
			long epochStartTime, int messagesSinceEpoch, int state,
			boolean isInitiator, int chunksSent, int chunksReceived,
			@Nullable byte[] ourEkSeed, @Nullable byte[] ourEkVector,
			@Nullable byte[] ourDecapsKey, @Nullable byte[] theirEkSeed,
			@Nullable byte[] theirEkHash, @Nullable byte[] theirEkVector,
			@Nullable byte[] ciphertext, @Nullable byte[] pendingChunks)
			throws DbException;

	@Nullable
	Object[] getPqRatchetState(T txn, ContactId c) throws DbException;

	boolean containsPqRatchetState(T txn, ContactId c) throws DbException;

	void removePqRatchetState(T txn, ContactId c) throws DbException;
}
