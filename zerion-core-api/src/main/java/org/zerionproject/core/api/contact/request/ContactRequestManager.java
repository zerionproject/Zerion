package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.db.DbException;

import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ContactRequestManager {

	InviteLink parseLink(String link) throws DbException, FormatException;

	String getZeroIdLink() throws DbException;

	void zeroIdShared() throws DbException;

	String resetZeroId() throws DbException;

	ContactLinkInfo createPermanentLink(String label)
			throws DbException, ContactLinkLimitException;

	void permanentLinkShared(byte[] linkId) throws DbException;

	void withdrawPermanentLink(byte[] linkId) throws DbException;

	void resumePermanentLink(byte[] linkId) throws DbException;

	List<ContactLinkInfo> getLinks() throws DbException;

	List<FloodNotice> getFloodNotices() throws DbException;

	void dismissFloodNotice(byte[] linkId) throws DbException;

	Collection<byte[]> getBlockedSenders(byte[] linkId) throws DbException;

	void unblock(byte[] linkId, byte[] senderTag) throws DbException;

	byte[] sendRequest(String link, String alias, String shownName,
			@Nullable String note) throws DbException, FormatException,
			OutgoingRequestLimitException;

	void cancelRequest(byte[] requestId) throws DbException;

	void dismissOutgoing(byte[] requestId) throws DbException;

	List<OutgoingRequestInfo> getOutgoingRequests() throws DbException;

	List<IncomingRequestInfo> getIncomingRequests() throws DbException;

	int getUnopenedCount() throws DbException;

	void markOpened(byte[] requestId) throws DbException;

	PendingContact accept(byte[] requestId, String alias) throws DbException;

	void decline(byte[] requestId) throws DbException;

	void block(byte[] requestId) throws DbException;

	void retryAccepted(byte[] requestId) throws DbException;

	void dismissIncoming(byte[] requestId) throws DbException;

	PendingContact addMutualFromInvite(String link, String alias,
			@Nullable String sharedZeroId)
			throws DbException, FormatException, GeneralSecurityException;

	boolean isPausedAfterRestore() throws DbException;

	void confirmOnlyActiveDevice() throws DbException;

	void beginBackupSnapshot() throws DbException;

	void endBackupSnapshot() throws DbException;
}
