package org.zerionproject.core.api.rendezvous;

import org.zerionproject.core.api.contact.PendingContactId;

public interface RendezvousPoller {

	long getLastPollTime(PendingContactId p);
}
