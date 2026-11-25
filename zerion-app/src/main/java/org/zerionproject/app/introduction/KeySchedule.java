package org.zerionproject.app.introduction;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class KeySchedule {

	static final int EARLIER_VERSION = 1;

	static final int SEPARATED_VERSION = 2;

	static final int CURRENT_VERSION = SEPARATED_VERSION;

	static final int MAX_VERSION = 127;

	static final TransportId ANNOUNCEMENT_ID =
			new TransportId("org.zerionproject.app.introduction");

	static final String ANNOUNCEMENT_KEY = "keySchedule";

	private KeySchedule() {
	}

	static Map<TransportId, TransportProperties> announce(
			Map<TransportId, TransportProperties> properties, int version) {
		if (version < EARLIER_VERSION || version > MAX_VERSION) {
			throw new IllegalArgumentException();
		}
		if (version < SEPARATED_VERSION) return properties;
		Map<TransportId, TransportProperties> announced =
				new HashMap<>(properties);
		TransportProperties announcement = new TransportProperties();
		announcement.put(ANNOUNCEMENT_KEY, String.valueOf(version));
		announced.put(ANNOUNCEMENT_ID, announcement);
		return announced;
	}

	static int announced(
			@Nullable Map<TransportId, TransportProperties> properties)
			throws FormatException {
		if (properties == null) return EARLIER_VERSION;
		TransportProperties announcement = properties.get(ANNOUNCEMENT_ID);
		if (announcement == null) return EARLIER_VERSION;
		String value = announcement.get(ANNOUNCEMENT_KEY);
		if (value == null) throw new FormatException();
		int version;
		try {
			version = Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new FormatException();
		}
		if (version < SEPARATED_VERSION || version > MAX_VERSION) {
			throw new FormatException();
		}
		if (!String.valueOf(version).equals(value)) {
			throw new FormatException();
		}
		return version;
	}

	static boolean separated(int localVersion, int remoteVersion) {
		return localVersion >= SEPARATED_VERSION
				&& remoteVersion >= SEPARATED_VERSION;
	}

	static boolean separated(IntroduceeSession s) throws FormatException {
		int localVersion = announced(s.getLocal().transportProperties);
		if (localVersion < SEPARATED_VERSION) return false;
		return separated(localVersion,
				announced(s.getRemote().transportProperties));
	}

	static Map<TransportId, TransportProperties> withoutAnnouncement(
			Map<TransportId, TransportProperties> properties) {
		if (!properties.containsKey(ANNOUNCEMENT_ID)) return properties;
		Map<TransportId, TransportProperties> stripped =
				new HashMap<>(properties);
		stripped.remove(ANNOUNCEMENT_ID);
		return stripped;
	}
}
