package org.zerionproject.core.api.sync;

import java.io.IOException;

public class InvalidMessageException extends IOException {

	public InvalidMessageException() {
		super();
	}

	public InvalidMessageException(String str) {
		super(str);
	}

	public InvalidMessageException(Throwable t) {
		super(t);
	}

}
