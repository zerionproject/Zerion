package org.briarproject.bramble.api.rendezvous;

import org.briarproject.bramble.api.contact.PendingContactId;

public interface RendezvousPoller {

	long getLastPollTime(PendingContactId p);
}
