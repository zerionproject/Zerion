package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.zerionproject.tor.TorWrapper.Observer;
import org.zerionproject.tor.TorWrapper.TorState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TorEventsTest {

	private static final String ONION =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final List<String> seen = new ArrayList<>();
	private final List<TorState> states = new ArrayList<>();

	private final Observer observer = new Observer() {
		@Override
		public void onState(TorState s) {
			states.add(s);
		}

		@Override
		public void onBootstrapPercentage(int percentage) {
			seen.add("bootstrap:" + percentage);
		}

		@Override
		public void onHsDescriptorUpload(String onion) {
			seen.add("uploaded:" + onion);
		}

		@Override
		public void onClockSkewDetected(long skewSeconds) {
			seen.add("skew:" + skewSeconds);
		}

		@Override
		public void onEntryRelayFailing() {
			seen.add("entry relay failing");
		}
	};

	private TestTorWrapper wrapper() throws Exception {
		TestTorWrapper w = new TestTorWrapper(tmp.newFolder(), 9050, 9051,
				(t, l) -> {
				});
		w.setObserver(observer);
		return w;
	}

	@Test
	public void aDescriptorUploadReachesTheObserver() throws Exception {
		TestTorWrapper w = wrapper();
		w.unrecognized("HS_DESC", "UPLOADED " + ONION + " UNKNOWN $ABC");
		assertEquals(Arrays.asList("uploaded:" + ONION), seen);
	}

	@Test
	public void otherDescriptorEventsDoNot() throws Exception {
		TestTorWrapper w = wrapper();
		w.unrecognized("HS_DESC", "RECEIVED " + ONION + " NO_AUTH $ABC");
		w.unrecognized("HS_DESC", "FAILED " + ONION + " NO_AUTH $ABC");
		w.unrecognized("HS_DESC", "UPLOADED");
		w.unrecognized("HS_DESC", "UPLOAD_FAILED " + ONION);
		w.unrecognized("SOMETHING", "UPLOADED " + ONION);
		assertTrue(seen.toString(), seen.isEmpty());
	}

	@Test
	public void directoryLookupsAndTheirAnswersReachTheObserver()
			throws Exception {
		TestTorWrapper w = wrapper();
		w.setObserver(new Observer() {
			@Override
			public void onState(TorState s) {
			}

			@Override
			public void onBootstrapPercentage(int percentage) {
			}

			@Override
			public void onHsDescriptorUpload(String onion) {
				seen.add("uploaded");
			}

			@Override
			public void onClockSkewDetected(long skewSeconds) {
			}

			@Override
			public void onHsDirectoryRequest() {
				seen.add("request");
			}

			@Override
			public void onHsDirectoryAnswer() {
				seen.add("answer");
			}
		});
		String dir = " NO_AUTH $0123456789ABCDEF0123456789ABCDEF01234567~relay";
		w.unrecognized("HS_DESC", "REQUESTED " + ONION + dir
				+ " BLINDED HSDIR_INDEX=00");
		w.unrecognized("HS_DESC", "FAILED " + ONION + dir
				+ " BLINDED REASON=NOT_FOUND");
		w.unrecognized("HS_DESC", "RECEIVED " + ONION + dir + " BLINDED");
		w.unrecognized("HS_DESC", "UPLOAD " + ONION + dir
				+ " BLINDED HSDIR_INDEX=00");
		w.unrecognized("HS_DESC", "UPLOADED " + ONION + dir);
		w.unrecognized("HS_DESC", "FAILED " + ONION + dir
				+ " BLINDED REASON=UPLOAD_REJECTED");
		w.unrecognized("HS_DESC", "FAILED " + ONION + dir);
		w.unrecognized("HS_DESC", "FAILED " + ONION
				+ " NO_AUTH UNKNOWN REASON=QUERY_NO_HSDIR");
		w.unrecognized("HS_DESC", "CREATED " + ONION + " UNKNOWN UNKNOWN X");
		w.unrecognized("HS_DESC", "REQUESTEDX " + ONION);
		w.unrecognized("STATUS_CLIENT", "REQUESTED " + ONION);
		assertEquals(Arrays.asList("request", "answer", "answer", "request",
				"uploaded", "answer"), seen);
	}

	@Test
	public void everyBootstrapStepReachesTheObserverOnce() throws Exception {
		TestTorWrapper w = wrapper();
		w.unrecognized("STATUS_CLIENT",
				"NOTICE BOOTSTRAP PROGRESS=10 TAG=conn SUMMARY=\"x\"");
		w.unrecognized("STATUS_CLIENT",
				"NOTICE BOOTSTRAP PROGRESS=10 TAG=conn SUMMARY=\"x\"");
		w.unrecognized("STATUS_CLIENT",
				"NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"");
		assertEquals(Arrays.asList("bootstrap:10", "bootstrap:100"), seen);
	}

	@Test
	public void aMalformedBootstrapLineIsNotAStep() throws Exception {
		TestTorWrapper w = wrapper();
		w.unrecognized("STATUS_CLIENT", "NOTICE BOOTSTRAP PROGRESS=abc");
		w.unrecognized("STATUS_CLIENT", "NOTICE BOOTSTRAP PROGRESS=");
		assertTrue(seen.toString(), seen.isEmpty());
	}

	@Test
	public void aClockSkewReachesTheObserver() throws Exception {
		TestTorWrapper w = wrapper();
		w.unrecognized("STATUS_GENERAL",
				"WARN CLOCK_SKEW SKEW=-90 MIN_SKEW=-45 SOURCE=DIRSERV:1.2.3.4:443");
		w.unrecognized("STATUS_GENERAL", "WARN CLOCK_SKEW SKEW=x");
		w.unrecognized("STATUS_GENERAL", "NOTICE CLOCK_JUMPED TIME=120");
		assertEquals(Arrays.asList("skew:-90"), seen);
	}

	@Test
	public void aFailingEntryRelayReachesTheObserver() throws Exception {
		TestTorWrapper w = wrapper();
		w.message("WARN", "Guard HacklabMondoviPL ($B9A9979A78C8B9AE267594590211"
				+ "BBE2D23AA367) is failing a very large amount of circuits. Most"
				+ " likely this means the Tor network is overloaded, but it"
				+ " could also mean an attack against you or potentially the"
				+ " guard itself.");
		w.message("WARN", "Guard HacklabMondoviPL ($B9A9979A78C8B9AE267594590211"
				+ "BBE2D23AA367) is failing an extremely large amount of"
				+ " circuits. This could indicate a route manipulation attack,"
				+ " network overload, bad local network connectivity, or a"
				+ " bug.");
		w.message("WARN", "Guard X ($0123456789ABCDEF0123456789ABCDEF01234567)"
				+ " is failing to carry an extremely large amount of streams"
				+ " on its circuits.");
		w.message("NOTICE", "Guard X ($0123456789ABCDEF0123456789ABCDEF01234567)"
				+ " is failing a very large amount of circuits.");
		w.message("WARN", "Received http status code 404 (\"Guard X ($0123456789"
				+ "ABCDEF0123456789ABCDEF01234567) is failing a very large"
				+ " amount of circuits\") from server 192.0.2.1:443");
		w.message("WARN", "No circuits are opened. Relaxed timeout for circuit"
				+ " 8 to 60000ms.");
		assertEquals(Arrays.asList("entry relay failing",
				"entry relay failing", "entry relay failing"), seen);
	}

	@Test
	public void torLogMessagesReachNothing() throws Exception {
		TestTorWrapper w = wrapper();
		w.message("NOTICE", "Bootstrapped 100% (done): Done");
		w.message("WARN", "Something about " + ONION + ".onion");
		w.orConnStatus("CONNECTED", "$ABC");
		w.streamStatus("NEW", "1", ONION + ".onion:80");
		w.bandwidthUsed(1, 2);
		assertTrue(seen.toString(), seen.isEmpty());
	}

	@Test
	public void theStateReportedBeforeAStartIsNotStarted() throws Exception {
		TestTorWrapper w = wrapper();
		w.unrecognized("STATUS_CLIENT",
				"NOTICE BOOTSTRAP PROGRESS=10 TAG=conn SUMMARY=\"x\"");
		assertEquals(Arrays.asList(TorState.NOT_STARTED), states);
		assertEquals(TorState.NOT_STARTED, w.getTorState());
	}

	@Test
	public void orConnectionResultsReachTheObserverWhileTheNetworkIsOn()
			throws Exception {
		TestTorWrapper w = wrapper();
		List<String> relays = new ArrayList<>();
		w.setObserver(new Observer() {
			@Override
			public void onState(TorState s) {
			}

			@Override
			public void onBootstrapPercentage(int percentage) {
			}

			@Override
			public void onHsDescriptorUpload(String onion) {
			}

			@Override
			public void onClockSkewDetected(long skewSeconds) {
			}

			@Override
			public void onRelayConnection(String fingerprint,
					boolean connected) {
				relays.add(fingerprint + (connected ? " up" : " down"));
			}
		});
		String fp = "0123456789ABCDEF0123456789ABCDEF01234567";
		w.orConnStatus("FAILED", "$" + fp + "~bridge");
		assertTrue("the network is off, so nothing counts", relays.isEmpty());
		try {
			w.enableNetwork(true);
		} catch (IOException expected) {
		}
		w.orConnStatus("LAUNCHED", "$" + fp);
		w.orConnStatus("CONNECTED", "$" + fp + "~bridge");
		w.orConnStatus("FAILED", "$" + fp.toLowerCase(java.util.Locale.US));
		w.orConnStatus("CLOSED", "$" + fp);
		w.orConnStatus("FAILED", "45.33.1.1:443");
		w.orConnStatus("FAILED", "$" + fp.substring(2));
		assertEquals(Arrays.asList(fp + " up", fp + " down"), relays);
		try {
			w.enableNetwork(false);
		} catch (IOException expected) {
		}
		w.orConnStatus("FAILED", "$" + fp);
		assertEquals("failures while the network goes off are not counted",
				2, relays.size());
	}
}
