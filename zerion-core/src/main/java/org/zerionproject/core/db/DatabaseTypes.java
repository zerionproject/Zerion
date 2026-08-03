package org.zerionproject.core.db;

class DatabaseTypes {

	private final String hashType, secretType, binaryType;
	private final String counterType, stringType;

	public DatabaseTypes(String hashType, String secretType, String binaryType,
			String counterType, String stringType) {
		this.hashType = hashType;
		this.secretType = secretType;
		this.binaryType = binaryType;
		this.counterType = counterType;
		this.stringType = stringType;
	}

	String replaceTypes(String s) {
		s = s.replaceAll("_HASH", hashType);
		s = s.replaceAll("_SECRET", secretType);
		s = s.replaceAll("_BINARY", binaryType);
		s = s.replaceAll("_COUNTER", counterType);
		s = s.replaceAll("_STRING", stringType);
		return s;
	}
}
