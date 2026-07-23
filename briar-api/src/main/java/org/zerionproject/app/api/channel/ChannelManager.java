package org.zerionproject.app.api.channel;

import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ChannelManager {

	ChannelState createChannel(String name, String description,
			boolean publicChannel) throws DbException;

	@Nullable
	ChannelState getChannel(byte[] channelId) throws DbException;

	Collection<ChannelState> getChannels() throws DbException;

	void deleteChannel(byte[] channelId) throws DbException;

	String exportInviteLink(byte[] channelId) throws DbException;

	@Nullable
	ChannelInviteLink parseInviteLink(String url);

	ChannelState joinChannel(ChannelInviteLink link) throws DbException;

	void leaveChannel(byte[] channelId) throws DbException;

	void publishPost(byte[] channelId, String body, long ttlSeconds)
			throws DbException;

	List<ChannelPost> getRecentPosts(byte[] channelId, long limit)
			throws DbException;

	int getUnreadCount(byte[] channelId) throws DbException;

	void markChannelRead(byte[] channelId) throws DbException;

	boolean isMirrorOptedIn(byte[] channelId) throws DbException;

	void setMirrorOptedIn(byte[] channelId, boolean mirror)
			throws DbException;

	void rotateJoinCapability(byte[] channelId) throws DbException;

	void onOnionRotated(String newOnionAddress) throws DbException;

	ChannelDelegationCert delegatePublisher(byte[] channelId,
			byte[] delegateeEd25519PubKey, byte[] delegateeMlDsaPubKey,
			long validUntilHourMs) throws DbException;

	void revokeDelegation(byte[] channelId, long delegationSeq)
			throws DbException;

	java.util.List<ChannelDelegationCert> listActiveDelegations(
			byte[] channelId) throws DbException;

	void purgeExpiredPosts() throws DbException;

	void bootstrapChannel(byte[] channelId) throws DbException;

	void refreshChannel(byte[] channelId) throws DbException;

	void refreshChannelReachability(byte[] channelId);

	void pinPost(byte[] channelId, long seqNum) throws DbException;

	void unpinPost(byte[] channelId) throws DbException;

	void deletePost(byte[] channelId, long seqNum) throws DbException;

	void publishPostWithAttachments(byte[] channelId, String body,
			long ttlSeconds, List<AttachmentSpec> attachments)
			throws DbException;

	@Nullable
	AttachmentBlob fetchAttachment(byte[] channelId, long postSeqNum,
			byte[] blobHash) throws DbException, java.io.IOException;

	@Nullable
	byte[] decryptAttachmentThumbnail(byte[] channelId, long postSeqNum,
			byte[] blobHash) throws DbException;

	void reactToPost(byte[] channelId, long postSeqNum, String emoji)
			throws DbException;

	List<ChannelReaction> getReactions(byte[] channelId, long postSeqNum)
			throws DbException;

	List<ChannelReaction> getAllReactions(byte[] channelId)
			throws DbException;

	void announceMyself(byte[] channelId, String displayName)
			throws DbException;

	List<ChannelSubscriber> getAnnouncedSubscribers(byte[] channelId)
			throws DbException;

	void banSubscriber(byte[] channelId, byte[] ed25519PubKey)
			throws DbException;

	void postComment(byte[] channelId, long parentPostSeqNum, String body)
			throws DbException;

	List<ChannelComment> getComments(byte[] channelId, long parentPostSeqNum)
			throws DbException;

	List<ChannelComment> getAllComments(byte[] channelId)
			throws DbException;

	boolean areDiscussionsEnabled(byte[] channelId) throws DbException;

	void setDiscussionsEnabled(byte[] channelId, boolean enabled)
			throws DbException;

	ChannelState createChannel(String name, String description,
			boolean publicChannel, boolean requiresApproval)
			throws DbException;

	void setRequiresApproval(byte[] channelId, boolean required)
			throws DbException;

	void applyToJoin(byte[] channelId, String displayName)
			throws DbException;

	List<ChannelApplication> listPendingApplications(byte[] channelId)
			throws DbException;

	List<ChannelApplication> listAllApplications(byte[] channelId)
			throws DbException;

	void approveApplication(byte[] channelId, byte[] applicantEd25519)
			throws DbException;

	void denyApplication(byte[] channelId, byte[] applicantEd25519)
			throws DbException;

	ApplicationStatus getMyApplicationStatus(byte[] channelId)
			throws DbException;
}
