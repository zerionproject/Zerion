package org.zerionproject.core.api;

public interface FeatureFlags {

	boolean shouldEnableImageAttachments();

	boolean shouldEnableProfilePictures();

	boolean shouldEnableDisappearingMessages();

	boolean shouldEnablePrivateGroupsInCore();
}
