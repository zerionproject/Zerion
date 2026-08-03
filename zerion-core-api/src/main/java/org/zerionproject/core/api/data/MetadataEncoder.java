package org.zerionproject.core.api.data;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.db.Metadata;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface MetadataEncoder {

	Metadata encode(BdfDictionary d) throws FormatException;
}
