package org.zerionproject.core.api;

public interface FeatureFlags {

	boolean shouldEnableImageAttachments();

	boolean shouldEnableProfilePictures();

	boolean shouldEnableDisappearingMessages();

	boolean shouldEnablePrivateGroupsInCore();

	/** Whether the I2P transport is registered alongside Tor. Off until an I2P
	 * router is bundled, so the shipped Tor-only build is unaffected. */
	boolean shouldEnableI2p();
}
