package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TorWrapperWakeHooksTest {

	private static final String ONION =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final List<String> hooks = new ArrayList<>();

	private final class RecordingTorWrapper extends TestTorWrapper {

		RecordingTorWrapper() throws IOException {
			super(tmp.newFolder(), 9050, 9051, (t, l) -> {
			});
		}

		@Override
		protected void onTorControlActivity() {
			hooks.add("activity");
		}

		@Override
		protected void onBootstrapProgress(int percentage) {
			hooks.add("bootstrap:" + percentage);
		}

		@Override
		protected void onHsDescriptorUpload(String onion) {
			hooks.add("upload:" + onion);
		}

		@Override
		protected void onHsDescriptorUploadFinished(String onion) {
			hooks.add("finished:" + onion);
		}
	}

	@Test
	public void streamEventsAreSubscribed() throws Exception {
		Field f = AbstractTorWrapper.class.getDeclaredField("EVENTS");
		f.setAccessible(true);
		List<String> events = Arrays.asList((String[]) f.get(null));
		assertTrue(events.contains("STREAM"));
		assertTrue(events.contains("CIRC"));
		assertTrue(events.contains("ORCONN"));
		assertTrue(events.contains("HS_DESC"));
	}

	@Test
	public void circuitStreamAndRelayEventsCountAsActivity()
			throws IOException {
		RecordingTorWrapper tor = new RecordingTorWrapper();
		tor.circuitStatus("LAUNCHED", "1", "");
		tor.circuitStatus("EXTENDED", "1", "$A");
		tor.circuitStatus("BUILT", "1", "$A,$B,$C");
		tor.streamStatus("NEW", "1", "x.onion:80");
		tor.streamStatus("SUCCEEDED", "1", "x.onion:80");
		tor.orConnStatus("LAUNCHED", "$A");
		tor.orConnStatus("CONNECTED", "$A");
		assertEquals(7, hooks.size());
		for (String h : hooks) assertEquals("activity", h);
	}

	@Test
	public void closingEventsDoNotCountAsActivity() throws IOException {
		RecordingTorWrapper tor = new RecordingTorWrapper();
		tor.circuitStatus("CLOSED", "1", "");
		tor.circuitStatus("FAILED", "1", "");
		tor.streamStatus("CLOSED", "1", "x.onion:80");
		tor.streamStatus("FAILED", "1", "x.onion:80");
		tor.orConnStatus("CLOSED", "$A");
		tor.orConnStatus("FAILED", "$A");
		tor.bandwidthUsed(1, 1);
		tor.message("NOTICE", "hello");
		assertTrue(hooks.isEmpty());
	}

	@Test
	public void descriptorUploadsAreTrackedPerOnion() throws IOException {
		RecordingTorWrapper tor = new RecordingTorWrapper();
		tor.unrecognized("HS_DESC",
				"UPLOAD " + ONION + " UNKNOWN $HSDIR DESCID");
		tor.unrecognized("HS_DESC", "UPLOADED " + ONION + " UNKNOWN $HSDIR");
		tor.unrecognized("HS_DESC", "FAILED " + ONION
				+ " UNKNOWN $HSDIR DESCID REASON=UPLOAD_REJECTED");
		tor.unrecognized("HS_DESC",
				"REQUESTED " + ONION + " NO_AUTH $HSDIR DESCID");
		assertEquals(Arrays.asList(
				"activity", "upload:" + ONION,
				"activity", "finished:" + ONION,
				"activity", "finished:" + ONION,
				"activity"), hooks);
	}

	@Test
	public void bootstrapProgressReachesTheHook() throws IOException {
		RecordingTorWrapper tor = new RecordingTorWrapper();
		tor.unrecognized("STATUS_CLIENT",
				"NOTICE BOOTSTRAP PROGRESS=45 TAG=requesting_descriptors"
						+ " SUMMARY=\"Loading relay descriptors\"");
		tor.unrecognized("STATUS_CLIENT",
				"NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"");
		assertEquals(Arrays.asList("bootstrap:45", "bootstrap:100"), hooks);
	}
}
