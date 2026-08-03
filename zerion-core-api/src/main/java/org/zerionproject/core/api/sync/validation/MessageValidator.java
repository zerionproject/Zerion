package org.zerionproject.core.api.sync.validation;

import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageContext;

public interface MessageValidator {

	MessageContext validateMessage(Message m, Group g)
			throws InvalidMessageException;
}
