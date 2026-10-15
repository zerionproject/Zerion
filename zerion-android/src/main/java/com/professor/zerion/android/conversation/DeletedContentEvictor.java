package com.professor.zerion.android.conversation;

import com.professor.zerion.android.conversation.voice.VoiceChunkAssembler;
import com.professor.zerion.android.grouptr.GroupTrMediaCache;

import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.PrivateMessageEditedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

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
			if (g.getKind() == GroupTrLocalStateChangedEvent.Kind.REMOVED) {
				GroupTrMediaCache.evictGroup(g.getGroupId());
			}
		}
	}
}
