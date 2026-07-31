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

	boolean isCreator(byte[] groupId, byte[] pubKey) throws DbException;

	boolean isMember(byte[] groupId, byte[] pubKey) throws DbException;

	long getEpoch(byte[] groupId) throws DbException;

	boolean isDissolved(byte[] groupId) throws DbException;

	void inviteContactToGroup(byte[] grouptrGroupId, ContactId contactId,
			byte[] contactPubKey, String contactName) throws DbException;

	void acceptInvite(byte[] grouptrGroupId) throws DbException;

	void declineInvite(byte[] grouptrGroupId) throws DbException;

	void removeFromDevice(byte[] groupId) throws DbException;

	void sendGroupPost(byte[] groupId, byte[] body, long autoDeleteTimerMs)
			throws DbException;

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

	int getUnreadCount(byte[] groupId);

	void markGroupRead(byte[] groupId);
}
