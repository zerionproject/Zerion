package org.zerionproject.app.api.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;

@NotNullByDefault
public interface GroupTrManager {

	@Nullable
	GroupTrState getGroup(byte[] groupId) throws DbException;

	Collection<GroupTrState> getGroups() throws DbException;

	Collection<GroupTrPendingInvite> getPendingInvites() throws DbException;

	GroupTrState createGroup(String name) throws DbException;

	GroupTrState createGroup(String name, @Nullable String incognitoName)
			throws DbException;

	boolean isCreator(byte[] groupId, byte[] pubKey) throws DbException;

	boolean isMember(byte[] groupId, byte[] pubKey) throws DbException;

	byte[] getLocalMemberKey(byte[] groupId) throws DbException;

	java.util.Map<String, ContactId> getMemberContacts(byte[] groupId)
			throws DbException;

	java.util.Map<String, byte[]> getMemberMlDsaKeys(byte[] groupId)
			throws DbException;

	boolean canJoinGroup(byte[] groupId, ContactId contactId)
			throws DbException;

	long getEpoch(byte[] groupId) throws DbException;

	boolean isDissolved(byte[] groupId) throws DbException;

	void inviteContactToGroup(byte[] grouptrGroupId, ContactId contactId,
			byte[] contactPubKey, String contactName) throws DbException;

	void acceptInvite(byte[] grouptrGroupId) throws DbException;

	void acceptInvite(byte[] grouptrGroupId, @Nullable String incognitoName)
			throws DbException;

	boolean canAcceptIncognito(byte[] grouptrGroupId, boolean memberInvite)
			throws DbException;

	@Nullable
	String getIncognitoName(byte[] groupId) throws DbException;

	void declineInvite(byte[] grouptrGroupId) throws DbException;

	void removeFromDevice(byte[] groupId) throws DbException;

	int sendGroupPost(byte[] groupId, byte[] body, long autoDeleteTimerMs)
			throws DbException;

	GroupTrSendResult sendGroupPostReporting(byte[] groupId, byte[] body,
			long autoDeleteTimerMs) throws DbException;

	int countMembersOnOlderVersion(byte[] groupId) throws DbException;

	int sendGroupReply(byte[] groupId, byte[] targetPostId, String text,
			long autoDeleteTimerMs) throws DbException;

	GroupTrSendResult sendGroupReplyReporting(byte[] groupId,
			byte[] targetPostId, String text, long autoDeleteTimerMs)
			throws DbException;

	int countMembersWithPostActions(byte[] groupId) throws DbException;

	int countMembersWithoutPostActions(byte[] groupId) throws DbException;

	int deletePostsForEveryone(byte[] groupId,
			Collection<byte[]> postIds) throws DbException;

	void deletePostsForMe(byte[] groupId, Collection<byte[]> postIds)
			throws DbException;

	java.util.List<GroupTrMember> getMembersOutOfReach(byte[] groupId)
			throws DbException;

	java.util.Map<String, GroupTrPostDelivery> getOwnPostDeliveries(
			byte[] groupId) throws DbException;

	java.util.Map<String, GroupTrPostDelivery> getOwnPostDeliveries(
			byte[] groupId, Collection<byte[]> postIds) throws DbException;

	Collection<GroupTrSentInvite> getSentInvites(byte[] groupId)
			throws DbException;

	void revokeInvite(byte[] groupId, ContactId contactId)
			throws DbException;

	long getGroupPermissions(byte[] groupId) throws DbException;

	int setMembersMayInvite(byte[] groupId, boolean allowed)
			throws DbException;

	MemberInviteAvailability getMemberInviteAvailability(byte[] groupId)
			throws DbException;

	boolean canReceiveMemberInvite(ContactId contactId) throws DbException;

	boolean canReceiveMemberInvite(byte[] groupId, ContactId contactId)
			throws DbException;

	boolean hasInviterGrant(byte[] groupId, byte[] memberPubKey)
			throws DbException;

	boolean canReceiveInviterGrant(byte[] groupId, byte[] memberPubKey)
			throws DbException;

	int setInviterGrant(byte[] groupId, byte[] memberPubKey, boolean granted)
			throws DbException;

	void inviteContactAsMember(byte[] groupId, ContactId contactId)
			throws DbException;

	void acceptMemberInvite(byte[] groupId, ContactId inviter)
			throws DbException;

	void acceptMemberInvite(byte[] groupId, ContactId inviter,
			@Nullable String incognitoName) throws DbException;

	void declineMemberInvite(byte[] groupId, ContactId inviter)
			throws DbException;

	boolean isWaitingForAdmin(byte[] groupId) throws DbException;

	void setMeshSink(GroupTrMeshSink sink);

	void addMember(byte[] groupId, byte[] addedPubKey, String addedName)
			throws DbException;

	void removeMember(byte[] groupId, byte[] removedPubKey)
			throws DbException;

	void leaveGroup(byte[] groupId) throws DbException;

	void dissolveGroup(byte[] groupId) throws DbException;

	void promoteToAdmin(byte[] groupId, byte[] targetPubKey)
			throws DbException;

	void demoteToMember(byte[] groupId, byte[] targetPubKey)
			throws DbException;

	void sendMemberListSnapshot(byte[] groupId) throws DbException;

	void setGroupAutoDeleteTimer(byte[] groupId, long ms) throws DbException;

	@Nullable
	String getStealthName(byte[] groupId) throws DbException;

	void setStealthName(byte[] groupId, @Nullable String alias)
			throws DbException;

	boolean isLocalScreenshotBlocked(byte[] groupId) throws DbException;

	void setLocalScreenshotBlocked(byte[] groupId, boolean blocked)
			throws DbException;

	java.util.List<GroupTrPost> getRecentPosts(byte[] groupId);

	@Nullable
	GroupTrPostSummary getLatestPostSummary(byte[] groupId)
			throws DbException;

	int getUnreadCount(byte[] groupId);

	void markGroupRead(byte[] groupId);
}
