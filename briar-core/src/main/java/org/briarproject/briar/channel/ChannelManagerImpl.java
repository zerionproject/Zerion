package org.briarproject.briar.channel;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.channel.ChannelInviteLink;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelPost;
import org.briarproject.briar.api.channel.ChannelState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

@ThreadSafe
@NotNullByDefault
class ChannelManagerImpl
		implements ChannelManager, EventListener, OpenDatabaseHook {

	static final String PHASE_NOT_IMPLEMENTED =
			"Channels Phase 1 scaffolding only — runtime not yet wired";

	private final DatabaseComponent db;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;
	private final SettingsManager settingsManager;
	private final EventBus eventBus;
	private final Clock clock;

	@Inject
	ChannelManagerImpl(DatabaseComponent db, CryptoComponent crypto,
			IdentityManager identityManager,
			SettingsManager settingsManager, EventBus eventBus, Clock clock) {
		this.db = db;
		this.crypto = crypto;
		this.identityManager = identityManager;
		this.settingsManager = settingsManager;
		this.eventBus = eventBus;
		this.clock = clock;
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		eventBus.addListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
	}

	@Override
	public ChannelState createChannel(String name, String description,
			boolean publicChannel) throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Nullable
	@Override
	public ChannelState getChannel(byte[] channelId) throws DbException {
		return null;
	}

	@Override
	public Collection<ChannelState> getChannels() throws DbException {
		return Collections.emptyList();
	}

	@Override
	public void deleteChannel(byte[] channelId) throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Override
	public String exportInviteLink(byte[] channelId) throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Nullable
	@Override
	public ChannelInviteLink parseInviteLink(String url) {
		return null;
	}

	@Override
	public ChannelState joinChannel(ChannelInviteLink link)
			throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Override
	public void leaveChannel(byte[] channelId) throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Override
	public void publishPost(byte[] channelId, String body, long ttlSeconds)
			throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Override
	public List<ChannelPost> getRecentPosts(byte[] channelId, long limit)
			throws DbException {
		return Collections.emptyList();
	}

	@Override
	public int getUnreadCount(byte[] channelId) throws DbException {
		return 0;
	}

	@Override
	public void markChannelRead(byte[] channelId) throws DbException {
	}

	@Override
	public boolean isMirrorOptedIn(byte[] channelId) throws DbException {
		return false;
	}

	@Override
	public void setMirrorOptedIn(byte[] channelId, boolean mirror)
			throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Override
	public void rotateJoinCapability(byte[] channelId) throws DbException {
		throw new UnsupportedOperationException(PHASE_NOT_IMPLEMENTED);
	}

	@Override
	public void onOnionRotated(String newOnionAddress) throws DbException {
	}
}
