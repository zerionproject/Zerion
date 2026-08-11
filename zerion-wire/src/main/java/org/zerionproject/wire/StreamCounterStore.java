package org.zerionproject.wire;

/**
 * Durable persistence for the ZWF per-(contact, direction) stream high-water
 * mark. The production implementation is backed by the SQLCipher database; tests
 * use an in-memory implementation.
 *
 * <p><strong>Durability contract:</strong> {@link #storeHighWater} MUST have
 * committed the new value durably (survives process death / device power loss)
 * before it returns. {@link ZwfStreamCounter} calls it <em>before</em> handing a
 * freshly-allocated stream id to the caller, so that a crash after allocation
 * but before the stream is used can never cause the same stream id — and hence
 * the same {@code (rootKey, streamId)} chain-key seed and AEAD nonce space — to
 * be reused. Reuse would be a catastrophic keystream + tag-forgery break.
 */
public interface StreamCounterStore {

	/**
	 * Returns the persisted high-water mark for the given contact and direction,
	 * or {@code 0} if none has been stored yet.
	 */
	long loadHighWater(int contactId, int direction);

	/**
	 * Durably persists {@code highWater} for the given contact and direction.
	 * Must not return until the write is durable.
	 */
	void storeHighWater(int contactId, int direction, long highWater);
}
