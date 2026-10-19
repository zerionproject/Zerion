package com.professor.zerion.android.conversation;

import com.professor.zerion.android.conversation.voice.VoiceChunkAssembler;
import com.professor.zerion.android.grouptr.GroupTrMediaCache;
import com.professor.zerion.android.message.PendingSends;

import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.channel.event.ChannelPostReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelPostsRemovedEvent;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.app.api.messaging.event.PrivateMessageEditedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
public class DeletedContentEvictor implements EventListener {

	private final VoiceChunkAssembler voiceAssembler;
	private final Runnable clearImageMemory;

	public DeletedContentEvictor(VoiceChunkAssembler voiceAssembler,
			Runnable clearImageMemory) {
		this.voiceAssembler = voiceAssembler;
		this.clearImageMemory = clearImageMemory;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ConversationMessagesDeletedEvent) {
			ConversationMessagesDeletedEvent d =
					(ConversationMessagesDeletedEvent) e;
			ConversationCache cache = ConversationCache.getInstance();
			for (MessageId id : d.getMessageIds()) {
				cache.removeMessage(d.getContactId(), id);
			}
			voiceAssembler.forgetContact(d.getContactId());
			ImageViewHolder.evictVideoThumbs(d.getMessageIds());
			clearImageMemory.run();
		} else if (e instanceof PrivateMessageEditedEvent) {
			ConversationCache.getInstance().invalidate(
					((PrivateMessageEditedEvent) e).getContactId());
		} else if (e instanceof ContactRemovedEvent) {
			ContactId c = ((ContactRemovedEvent) e).getContactId();
			ConversationCache.getInstance().invalidate(c);
			voiceAssembler.forgetContact(c);
			ImageViewHolder.clearVideoThumbCache();
			clearImageMemory.run();
		} else if (e instanceof GroupTrLocalStateChangedEvent) {
			GroupTrLocalStateChangedEvent g = (GroupTrLocalStateChangedEvent) e;
			if (g.getKind() == GroupTrLocalStateChangedEvent.Kind.CREATED) {
				MediaViewer.forgetRemoval(MediaViewer.groupScope(
						g.getGroupId()));
			}
			if (g.getKind() == GroupTrLocalStateChangedEvent.Kind.REMOVED) {
				GroupTrMediaCache.evictGroup(g.getGroupId());
				MediaViewer.forget(MediaViewer.groupScope(g.getGroupId()));
				PendingSends.get().removeGroup(g.getGroupId());
			}
		} else if (e instanceof ChannelStateChangedEvent) {
			ChannelStateChangedEvent c = (ChannelStateChangedEvent) e;
			if (c.getKind() == ChannelStateChangedEvent.Kind.CREATED
					|| c.getKind() == ChannelStateChangedEvent.Kind.JOINED) {
				MediaViewer.forgetRemoval(MediaViewer.channelScope(
						c.getChannelId()));
			}
			if (c.getKind() == ChannelStateChangedEvent.Kind.LEFT) {
				PendingSends.get().removeChannel(c.getChannelId());
				MediaViewer.forget(MediaViewer.channelScope(c.getChannelId()));
			}
		} else if (e instanceof GroupTrPostsRemovedEvent) {
			GroupTrPostsRemovedEvent r = (GroupTrPostsRemovedEvent) e;
			List<String> posts = new ArrayList<>();
			for (byte[] id : r.getPostIds()) {
				posts.add(MediaViewer.groupPost(r.getGroupId(),
						toHexString(id)));
			}
			MediaViewer.forgetPosts(posts);
		} else if (e instanceof ChannelPostReceivedEvent) {
			ChannelPostReceivedEvent r = (ChannelPostReceivedEvent) e;
			MediaViewer.forgetPostRemoval(MediaViewer.channelPost(
					r.getChannelId(), r.getSeqNum()));
		} else if (e instanceof ChannelPostsRemovedEvent) {
			ChannelPostsRemovedEvent r = (ChannelPostsRemovedEvent) e;
			List<String> posts = new ArrayList<>();
			for (long seq : r.getSeqNums()) {
				posts.add(MediaViewer.channelPost(r.getChannelId(), seq));
			}
			MediaViewer.forgetPosts(posts);
		}
	}
}
