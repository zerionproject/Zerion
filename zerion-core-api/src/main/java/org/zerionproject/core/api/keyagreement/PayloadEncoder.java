package org.zerionproject.core.api.keyagreement;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PayloadEncoder {

	byte[] encode(Payload p);
}
