package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface SessionParser {

	Session parseSession(BdfDictionary meta) throws FormatException;
}
