package org.zerionproject.core.api.identity;

import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_SIGNATURE_BYTES;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_SIGNATURE_PUBLIC_KEY_BYTES;

public interface AuthorConstants {

	int MAX_AUTHOR_NAME_LENGTH = 50;

	int MAX_PUBLIC_KEY_LENGTH = MAX_SIGNATURE_PUBLIC_KEY_BYTES;

	int MAX_SIGNATURE_LENGTH = MAX_SIGNATURE_BYTES;
}
