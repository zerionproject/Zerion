package org.zerionproject.core.api.client;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageContext;
import org.zerionproject.core.api.sync.validation.MessageValidator;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;
import javax.annotation.concurrent.Immutable;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;

@Immutable
@NotNullByDefault
public abstract class BdfMessageValidator implements MessageValidator {
	protected final ClientHelper clientHelper;
	protected final MetadataEncoder metadataEncoder;
	protected final Clock clock;
	protected final boolean canonical;

	@Deprecated
	protected BdfMessageValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock, boolean canonical) {
		this.clientHelper = clientHelper;
		this.metadataEncoder = metadataEncoder;
		this.clock = clock;
		this.canonical = canonical;
	}

	protected BdfMessageValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		this(clientHelper, metadataEncoder, clock, true);
	}

	protected abstract BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws InvalidMessageException, FormatException;

	@Override
	public MessageContext validateMessage(Message m, Group g)
			throws InvalidMessageException {
		long now = clock.currentTimeMillis();
		if (m.getTimestamp() - now > MAX_CLOCK_DIFFERENCE) {
			throw new InvalidMessageException(
					"Timestamp is too far in the future");
		}
		try {
			BdfList bodyList = clientHelper.toList(m, canonical);
			BdfMessageContext result = validateMessage(m, g, bodyList);
			Metadata meta = metadataEncoder.encode(result.getDictionary());
			return new MessageContext(meta, result.getDependencies());
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
	}
}
