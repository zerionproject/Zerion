package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.zerionproject.tor.TorWrapper.Observer;
import org.zerionproject.tor.TorWrapper.TorState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SC-TOR-05: the control events that reach the observer. The published
 * wrapper only reported a descriptor upload while its logger was enabled,
 * which it never is in this app; here the observer learns of every
 * upload, of every bootstrap step and of a clock skew, and of nothing
 * else.
 */
public class TorEventsTest {

	private static final String ONION =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final List<String> seen = new ArrayList<>();
	private final List<TorState> states = new ArrayList<>();

	/**
	 * The state is reported separately: the first event of any kind also
	 * reports the initial state, which is not what these tests are about.
	 */
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
}
