package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * Per-sender key state for Sender Keys group encryption.
 * <p>
 * Each member maintains their own outbound SenderKey, and caches all
 * other members' SenderKeys for decrypting incoming messages.
 * <p>
 * Structure per design:
 * <pre>
 * SenderKey {
 *     chainKey:       byte[32]    // Current chain key
 *     messageIndex:   uint32      // Messages sent with this key
 *     epoch:          uint32      // Key generation epoch
 *     createdAt:      uint64      // Timestamp of creation
 *     authorId:       AuthorId    // Owner of this SenderKey
 * }
 * </pre>
 */
@Immutable
@NotNullByDefault
public class SenderKey {

	private final GroupId groupId;
	private final AuthorId authorId;
	private final SecretKey chainKey;
	private final int epoch;
	private final int messageIndex;
	private final long createdAt;
	private final boolean isLocal;
	private final SenderKeyState state;

	public SenderKey(
			GroupId groupId,
			AuthorId authorId,
			SecretKey chainKey,
			int epoch,
			int messageIndex,
			long createdAt,
			boolean isLocal,
			SenderKeyState state
	) {
		this.groupId = groupId;
		this.authorId = authorId;
		this.chainKey = chainKey;
		this.epoch = epoch;
		this.messageIndex = messageIndex;
		this.createdAt = createdAt;
		this.isLocal = isLocal;
		this.state = state;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public AuthorId getAuthorId() {
		return authorId;
	}

	public SecretKey getChainKey() {
		return chainKey;
	}

	public int getEpoch() {
		return epoch;
	}

	public int getMessageIndex() {
		return messageIndex;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	/**
	 * Returns true if this is our own outbound SenderKey.
	 */
	public boolean isLocal() {
		return isLocal;
	}

	public SenderKeyState getState() {
		return state;
	}

	/**
	 * Returns a new SenderKey with the message index incremented.
	 */
	public SenderKey withIncrementedIndex() {
		return new SenderKey(
				groupId, authorId, chainKey, epoch,
				messageIndex + 1, createdAt, isLocal, state
		);
	}

	/**
	 * Returns a new SenderKey with a new chain key and reset message index.
	 */
	public SenderKey withNewChainKey(SecretKey newChainKey, int newEpoch, long rotationTime) {
		return new SenderKey(
				groupId, authorId, newChainKey, newEpoch,
				0, rotationTime, isLocal, SenderKeyState.ACTIVE
		);
	}

	/**
	 * Returns a new SenderKey with the specified state.
	 */
	public SenderKey withState(SenderKeyState newState) {
		return new SenderKey(
				groupId, authorId, chainKey, epoch,
				messageIndex, createdAt, isLocal, newState
		);
	}

	/**
	 * Returns a new SenderKey with an advanced chain key and message index.
	 * Used after encrypting or decrypting a message.
	 */
	public SenderKey withAdvancedChain(SecretKey newChainKey, int newMessageIndex) {
		return new SenderKey(
				groupId, authorId, newChainKey, epoch,
				newMessageIndex, createdAt, isLocal, state
		);
	}
}
