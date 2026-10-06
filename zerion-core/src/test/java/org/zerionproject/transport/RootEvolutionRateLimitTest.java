package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class RootEvolutionRateLimitTest {

	private static final int ALICE_SEES_BOB = 2;
	private static final int BOB_SEES_ALICE = 1;

	private RootEvolutionTestBed bed;
	private SecretKey pairingRoot;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		pairingRoot = bed.randomKey();
	}

	private final class OfferingManager extends RootEvolutionManager {

		OfferingManager() {
			super(new RootEvolutionTestBed.MemRootKeyStore(), bed.crypto,
					bed.mlKem, bed.clock::get);
		}

		@Override
		public ZwfControlHandler newEvolution(ContactId c, boolean alice) {
			return new ZwfControlHandler() {
				private Sender sender;

				@Override
				public void start(Sender s) {
					sender = s;
					sender.send(RootEvolutionRecord.hello(0,
							new byte[RootEvolutionRecord.MAC_LENGTH], (byte) 0));
				}

				@Override
				public void onRecord(byte[] payload) {
					RootEvolutionRecord r;
					try {
						r = RootEvolutionRecord.decode(payload);
					} catch (FormatException e) {
						return;
					}
					if (r == null || r.kind != RootEvolutionRecord.KIND_HELLO) {
						return;
					}
					KeyPair x = bed.crypto.generateAgreementKeyPair();
					MlKemKeyPair kem = bed.mlKem.generateKeyPair();
					sender.send(RootEvolutionRecord.init(0, r.a,
							x.getPublic().getEncoded(),
							kem.getEncapsulationKey()));
				}

				@Override
				public void onPeerStreamAuthenticated(long epoch) {
				}

				@Override
				public void close() {
				}
			};
		}
	}

	@Test(timeout = 120_000)
	public void aRootHolderCannotMakeTheResponderStoreRootsOnEveryConnection()
			throws Exception {
		Device bob = bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
		Device mallory = bed.new Device("mallory", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true,
				new OfferingManager());
		int connections = 6;
		for (int i = 0; i < connections; i++) {
			int inits = count(bob, RootEvolutionRecord.KIND_INIT);
			int bobBefore = bob.received.size();
			bed.connect(mallory, bob, () -> bob.received.size() > bobBefore
					&& count(bob, RootEvolutionRecord.KIND_INIT) > inits);
		}
		int stored = bob.store.pendingStores.get();
		assertTrue("stored " + stored,
				stored <= RootEvolutionManager.MAX_ANSWERS_PER_INTERVAL);
		assertTrue(stored >= 1);
	}

	private static int count(Device d, byte kind) {
		int n = 0;
		synchronized (d.receivedKinds) {
			for (byte b : d.receivedKinds) if (b == kind) n++;
		}
		return n;
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
