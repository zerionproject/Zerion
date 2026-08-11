package org.zerionproject.core.contact;

interface HandshakeRecordTypes {

	byte RECORD_TYPE_EPHEMERAL_PUBLIC_KEY = 0;

	byte RECORD_TYPE_PROOF_OF_OWNERSHIP = 1;

	byte RECORD_TYPE_MINOR_VERSION = 2;

	byte RECORD_TYPE_HYBRID_STATIC_KEY = 3;

	byte RECORD_TYPE_KEM_CIPHERTEXT = 4;

	byte RECORD_TYPE_MODE3_CAPABILITY = 5;
}
