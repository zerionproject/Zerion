package org.briarproject.bramble.api.sync.validation;

import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageContext;

public interface MessageValidator {

	MessageContext validateMessage(Message m, Group g)
			throws InvalidMessageException;
}
