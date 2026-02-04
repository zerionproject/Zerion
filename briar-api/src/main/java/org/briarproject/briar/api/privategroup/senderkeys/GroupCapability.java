package org.briarproject.briar.api.privategroup.senderkeys;

/**
 * Capability flags for group encryption features.
 * <p>
 * Advertised in contact handshake and group join messages.
 * Mode selection is based on minimum common capability.
 */
public final class GroupCapability {

	/**
	 * Supports Sender Keys V1 encryption.
	 */
	public static final int SENDER_KEYS_V1 = 0x01;

	/**
	 * Supports post-quantum epoch refresh.
	 */
	public static final int PQ_EPOCH_V1 = 0x02;

	/**
	 * Full capability mask (all features supported).
	 */
	public static final int FULL_CAPABILITY = SENDER_KEYS_V1 | PQ_EPOCH_V1;

	private GroupCapability() {
	}

	/**
	 * Returns true if the capability set includes Sender Keys V1.
	 */
	public static boolean hasSenderKeys(int capabilities) {
		return (capabilities & SENDER_KEYS_V1) != 0;
	}

	/**
	 * Returns true if the capability set includes PQ epoch refresh.
	 */
	public static boolean hasPqEpoch(int capabilities) {
		return (capabilities & PQ_EPOCH_V1) != 0;
	}

	/**
	 * Returns the minimum common capability between two capability sets.
	 */
	public static int minimumCommon(int a, int b) {
		return a & b;
	}
}
