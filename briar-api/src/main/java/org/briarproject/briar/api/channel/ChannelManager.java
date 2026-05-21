package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.db.DbException;
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
}
