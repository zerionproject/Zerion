package org.zerionproject.transport;

import java.io.IOException;

public interface TorPrivacyConfigurator {

	void applyAndVerify() throws IOException;
}
