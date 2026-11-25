package org.zerionproject.app.introduction;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.introduction.IntroduceeSession.Local;
import org.zerionproject.app.introduction.IntroduceeSession.Remote;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.plugin.TransportId.MAX_TRANSPORT_ID_LENGTH;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MAX_PROPERTY_LENGTH;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getTransportPropertiesMap;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_AUTH;
import static org.zerionproject.app.introduction.KeySchedule.ANNOUNCEMENT_ID;
import static org.zerionproject.app.introduction.KeySchedule.ANNOUNCEMENT_KEY;
import static org.zerionproject.app.introduction.KeySchedule.CURRENT_VERSION;
import static org.zerionproject.app.introduction.KeySchedule.EARLIER_VERSION;
import static org.zerionproject.app.introduction.KeySchedule.MAX_VERSION;
import static org.zerionproject.app.introduction.KeySchedule.SEPARATED_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KeyScheduleTest extends BrambleTestCase {

	private final Map<TransportId, TransportProperties> properties =
			getTransportPropertiesMap(3);

	private static Map<TransportId, TransportProperties> withAnnouncement(
			Map<TransportId, TransportProperties> properties,
			@Nullable String key, @Nullable String value) {
		Map<TransportId, TransportProperties> changed =
				new HashMap<>(properties);
		TransportProperties announcement = new TransportProperties();
		if (key != null && value != null) announcement.put(key, value);
		changed.put(ANNOUNCEMENT_ID, announcement);
		return changed;
	}

	private static IntroduceeSession session(
			@Nullable Map<TransportId, TransportProperties> local,
			@Nullable Map<TransportId, TransportProperties> remote) {
		Local l = new Local(true, null, -1, null, null, local, -1, null,
				null);
		Remote r = new Remote(false, getAuthor(), null, null, remote, -1,
				null, null);
		return new IntroduceeSession(new SessionId(getRandomId()), AWAIT_AUTH,
				1L, new GroupId(getRandomId()), getAuthor(), l, r, null,
				null);
	}

	@Test
	public void theCurrentVersionIsTheSeparatedKeySchedule() {
		assertEquals(2, SEPARATED_VERSION);
		assertEquals(SEPARATED_VERSION, CURRENT_VERSION);
		assertEquals(1, EARLIER_VERSION);
		assertEquals("org.zerionproject.app.introduction",
				ANNOUNCEMENT_ID.getString());
		assertEquals("keySchedule", ANNOUNCEMENT_KEY);
	}

	@Test
	public void theAnnouncementIsOneEntryBesideTheTransports()
			throws Exception {
		Map<TransportId, TransportProperties> copy =
				new HashMap<>(properties);
		Map<TransportId, TransportProperties> announced =
				KeySchedule.announce(properties, SEPARATED_VERSION);
		assertEquals(copy, properties);
		assertNotSame(properties, announced);
		assertEquals(properties.size() + 1, announced.size());
		TransportProperties announcement = announced.get(ANNOUNCEMENT_ID);
		assertEquals(1, announcement.size());
		assertEquals("2", announcement.get(ANNOUNCEMENT_KEY));
		assertEquals(SEPARATED_VERSION, KeySchedule.announced(announced));
		assertEquals(properties, KeySchedule.withoutAnnouncement(announced));
		assertSame(properties, KeySchedule.withoutAnnouncement(properties));
	}

	@Test
	public void theEarlierVersionAnnouncesNothing() throws Exception {
		assertSame(properties,
				KeySchedule.announce(properties, EARLIER_VERSION));
		assertEquals(EARLIER_VERSION, KeySchedule.announced(properties));
		assertEquals(EARLIER_VERSION, KeySchedule.announced(null));
		assertEquals(EARLIER_VERSION,
				KeySchedule.announced(new HashMap<>()));
	}

	@Test
	public void theAnnouncementPassesTheTransportPropertyRules()
			throws Exception {
		IntroductionIntegrationTestComponent component =
				DaggerIntroductionIntegrationTestComponent.builder().build();
		IntroductionIntegrationTestComponent.Helper
				.injectEagerSingletons(component);
		ClientHelper clientHelper = component.getClientHelper();
		Map<TransportId, TransportProperties> announced =
				KeySchedule.announce(properties, CURRENT_VERSION);
		assertEquals(announced,
				clientHelper.parseAndValidateTransportPropertiesMap(
						clientHelper.toDictionary(announced)));
		Map<TransportId, TransportProperties> alone =
				KeySchedule.announce(new HashMap<>(), CURRENT_VERSION);
		assertEquals(1, alone.size());
		assertEquals(alone,
				clientHelper.parseAndValidateTransportPropertiesMap(
						clientHelper.toDictionary(alone)));
		int idLength =
				StringUtils.toUtf8(ANNOUNCEMENT_ID.getString()).length;
		assertTrue(idLength >= 1 && idLength <= MAX_TRANSPORT_ID_LENGTH);
		assertTrue(ANNOUNCEMENT_KEY.length() <= MAX_PROPERTY_LENGTH);
		assertTrue(String.valueOf(MAX_VERSION).length()
				<= MAX_PROPERTY_LENGTH);
	}

	@Test
	public void laterVersionsAreReadAsAnnounced() throws Exception {
		assertEquals(3, KeySchedule.announced(
				withAnnouncement(properties, ANNOUNCEMENT_KEY, "3")));
		assertEquals(MAX_VERSION, KeySchedule.announced(withAnnouncement(
				properties, ANNOUNCEMENT_KEY, String.valueOf(MAX_VERSION))));
		Map<TransportId, TransportProperties> extra =
				withAnnouncement(properties, ANNOUNCEMENT_KEY, "2");
		extra.get(ANNOUNCEMENT_ID).put("later", "value");
		assertEquals(2, KeySchedule.announced(extra));
	}

	@Test
	public void aMalformedAnnouncementIsRefused() {
		String[] values = {
				"1", "0", "-1", "-2", "+2", "02", "2 ", " 2", "2.0", "128",
				"99999999999", "x", "two", "\u0662", "2\u0000"
		};
		for (String value : values) {
			try {
				KeySchedule.announced(withAnnouncement(properties,
						ANNOUNCEMENT_KEY, value));
				fail(value);
			} catch (FormatException expected) {
			}
		}
		try {
			KeySchedule.announced(withAnnouncement(properties, null, null));
			fail();
		} catch (FormatException expected) {
		}
		try {
			KeySchedule.announced(
					withAnnouncement(properties, "keyschedule", "2"));
			fail();
		} catch (FormatException expected) {
		}
	}

	@Test
	public void announcingOutsideTheKnownRangeIsRefused() {
		for (int version : new int[] {0, -1, MAX_VERSION + 1}) {
			try {
				KeySchedule.announce(properties, version);
				fail();
			} catch (IllegalArgumentException expected) {
			}
		}
	}

	@Test
	public void theSeparatedFormNeedsBothAnnouncements() throws Exception {
		Map<TransportId, TransportProperties> announced =
				KeySchedule.announce(properties, SEPARATED_VERSION);
		Map<TransportId, TransportProperties> later =
				withAnnouncement(properties, ANNOUNCEMENT_KEY, "3");
		assertTrue(KeySchedule.separated(session(announced, announced)));
		assertTrue(KeySchedule.separated(session(announced, later)));
		assertTrue(KeySchedule.separated(session(later, announced)));
		assertFalse(KeySchedule.separated(session(announced, properties)));
		assertFalse(KeySchedule.separated(session(properties, announced)));
		assertFalse(KeySchedule.separated(session(properties, properties)));
		assertFalse(KeySchedule.separated(session(null, null)));
		assertFalse(KeySchedule.separated(session(announced, null)));
		assertTrue(KeySchedule.separated(2, 2));
		assertTrue(KeySchedule.separated(2, 3));
		assertFalse(KeySchedule.separated(1, 2));
		assertFalse(KeySchedule.separated(2, 1));
		assertFalse(KeySchedule.separated(1, 1));
	}

	@Test
	public void aSideThatAnnouncesRefusesAMalformedAnnouncementOfThePeer()
			throws Exception {
		Map<TransportId, TransportProperties> announced =
				KeySchedule.announce(properties, SEPARATED_VERSION);
		Map<TransportId, TransportProperties> malformed =
				withAnnouncement(properties, ANNOUNCEMENT_KEY, "x");
		try {
			KeySchedule.separated(session(announced, malformed));
			fail();
		} catch (FormatException expected) {
		}
		try {
			KeySchedule.separated(session(malformed, announced));
			fail();
		} catch (FormatException expected) {
		}
		assertFalse(KeySchedule.separated(session(properties, malformed)));
	}
}
