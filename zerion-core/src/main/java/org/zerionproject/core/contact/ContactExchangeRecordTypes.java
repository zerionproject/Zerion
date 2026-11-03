package org.zerionproject.core.contact;

interface ContactExchangeRecordTypes {

	byte CONTACT_INFO = 0;

	/**
	 * Sent after CONTACT_INFO by releases that refuse to replace the keys of
	 * an existing contact unless both sides mean to. The payload is a BDF
	 * list with one boolean: whether this side intends to replace them.
	 * Earlier releases skip it as an unknown record type.
	 */
	byte REPLACE_KEYS = 1;
}
