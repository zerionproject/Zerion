package org.zerionproject.core.api.rendezvous;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface KeyMaterialSource {

	byte[] getKeyMaterial(int length);
}
