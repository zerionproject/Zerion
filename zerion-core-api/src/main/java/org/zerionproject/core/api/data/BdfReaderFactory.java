package org.zerionproject.core.api.data;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

@NotNullByDefault
public interface BdfReaderFactory {

	BdfReader createReader(InputStream in);

	@Deprecated
	BdfReader createReader(InputStream in, boolean canonical);

	BdfReader createReader(InputStream in, int nestedLimit,
			int maxBufferSize, boolean canonical);
}
