package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelHistoricalRevocationDeviceTest {

	private static final long HOUR = 3_600_000L;

	private final Random random = new Random(1105);
	private CryptoComponent crypto;
	private ChannelCodec codec;
	private ChannelPullCodec pullCodec;
	private ChannelSignatures signatures;
	private ChannelChainVerifier chain;
	private KeyPair publisher, delegate, stranger;
	private byte[] salt, channelId;
	private ChannelDelegationCert cert;
	private ChannelPost p0, d1, p2, p3, p4;

	@Before
	public void setUp() throws Exception {
		crypto = org.zerionproject.core.crypto.CryptoForTests.create();
		codec = new ChannelCodec(crypto);
		pullCodec = new ChannelPullCodec(org.zerionproject.core.data.BdfForTests.readers(),
				org.zerionproject.core.data.BdfForTests.writers());
		signatures = new ChannelSignatures(crypto);
		chain = new ChannelChainVerifier(codec);
		publisher = crypto.generateHybridSignatureKeyPair();
		delegate = crypto.generateHybridSignatureKeyPair();
		stranger = crypto.generateHybridSignatureKeyPair();
		salt = new byte[32];
		random.nextBytes(salt);
		channelId = crypto.hash("org.zerionproject/CHANNEL_ID",
				publisher.getPublic().getEncoded(), salt);
		cert = cert(publisher, delegate, HOUR, 100 * HOUR, 1L);
		p0 = signed(publisher, 0, new byte[ChannelConstants.PREV_HASH_BYTES],
				"p0", null);
		d1 = signed(delegate, 1, chain.hashOf(p0), "d1", delegate);
		p2 = signed(publisher, 2, chain.hashOf(d1), "p2", null);
		p3 = signed(publisher, 3, chain.hashOf(p2), "p3", null);
		p4 = signed(publisher, 4, chain.hashOf(p3), "p4", null);
	}

	private final class Subscriber {
		final File root = new File(androidx.test.platform.app
				.InstrumentationRegistry.getInstrumentation().getTargetContext()
				.getCacheDir(), "revocation-" + System.nanoTime());
		final org.zerionproject.core.api.crypto.SecretKey dbKey;
		org.zerionproject.core.api.db.DatabaseComponent db;
		SettingsManager settings;
		final Deque<byte[]> responses = new ArrayDeque<>();
		ChannelStore store;
		ChannelManagerImpl manager;

		Subscriber() throws Exception {
			byte[] k = new byte[org.zerionproject.core.api.crypto.SecretKey
					.LENGTH];
			random.nextBytes(k);
			dbKey = new org.zerionproject.core.api.crypto.SecretKey(k);
			open();
			org.zerionproject.core.api.db.DatabaseComponent first = db;
			first.transaction(false, txn -> first.addIdentity(txn,
					org.zerionproject.core.test.TestUtils.getIdentity()));
			subscribers.add(this);
			start();
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) publisher.getPublic();
			store.putChannel(new ChannelState(channelId, salt,
					pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
					"name", "description", null, HOUR, true, null,
					"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx",
					0L, false, -1L, null, null,
					Collections.<ChannelDelegationCert>emptyList(),
					Collections.<Long>emptyList(), 10L, null,
					ChannelState.NO_PINNED_POST, false,
					Collections.<ChannelDelegationCert>emptyList()));
		}

		void start() {
			store = new ChannelStore(settings, org.zerionproject.core.data.BdfForTests.readers(),
					org.zerionproject.core.data.BdfForTests.writers());
			ChannelPostValidator validator =
					new ChannelPostValidator(codec, signatures, chain);
			ChannelPullProtocol protocol = new ChannelPullProtocol(codec,
					pullCodec, new ChannelHmacChallenge(crypto),
					new ChannelContentKey(crypto), validator, signatures,
					crypto);
			ChannelTransport transport = (ChannelTransport)
					Proxy.newProxyInstance(
							ChannelTransport.class.getClassLoader(),
							new Class<?>[] {ChannelTransport.class},
							(proxy, method, args) -> {
								if (method.getName().equals(
										"requestFromOnion")) {
									return responses.removeFirst();
								}
								if (method.getReturnType() == boolean.class) {
									return true;
								}
								return null;
							});
			manager = new ChannelManagerImpl(crypto, inert(EventBus.class),
					clock(), codec, signatures, chain, store,
					new ChannelContentKey(crypto), validator, protocol,
					transport, new ChannelBlobStore(config(), settings, crypto),
					new ChannelReactionStore(settings,
							org.zerionproject.core.data.BdfForTests.readers(),
							org.zerionproject.core.data.BdfForTests.writers()),
					new ChannelSubscriberStore(settings,
							org.zerionproject.core.data.BdfForTests.readers(),
							org.zerionproject.core.data.BdfForTests.writers()),
					new ChannelCommentStore(settings, org.zerionproject.core.data.BdfForTests.readers(),
							org.zerionproject.core.data.BdfForTests.writers()),
					new ChannelDiscussionStore(settings),
					new ChannelApplicationStore(settings,
							org.zerionproject.core.data.BdfForTests.readers(),
							org.zerionproject.core.data.BdfForTests.writers()),
					new ChannelMyApplicationsStore(settings,
							org.zerionproject.core.data.BdfForTests.readers(),
							org.zerionproject.core.data.BdfForTests.writers()),
					new ChannelTombstoneStore(settings),
					new ChannelPostTombstoneStore(settings),
					new ChannelSelfAnnounceStore(settings),
					inert(IdentityManager.class), inert(TaskScheduler.class),
					Runnable::run);
			manager.readerFactory = org.zerionproject.core.data.BdfForTests.readers();
			manager.writerFactory = org.zerionproject.core.data.BdfForTests.writers();
		}

		void open() throws Exception {
			db = org.zerionproject.core.db.SqlCipherDatabaseForTests.open(root,
					dbKey);
			settings = org.zerionproject.core.settings.SettingsManagerForTests
					.create(db);
		}

		void restart() throws Exception {
			db.close();
			open();
			start();
		}

		void close() throws Exception {
			db.close();
			deleteRecursively(root);
		}

		void pull(BdfDictionary manifest, ChannelPost... posts)
				throws Exception {
			responses.add(pullCodec.encodePullResponse(manifest,
					Arrays.asList(posts), null, Collections.<String>emptyList(),
					Collections.<ChannelReaction>emptyList(),
					Collections.<ChannelComment>emptyList()));
			try {
				manager.refreshChannel(channelId);
			} catch (DbException refused) {
			}
		}

		String view() throws DbException {
			StringBuilder sb = new StringBuilder();
			for (ChannelPost p : store.getPosts(channelId)) {
				if (sb.length() > 0) sb.append(' ');
				sb.append(p.getBody());
				if (p.isWithheld()) sb.append("(withheld)");
			}
			return sb.toString();
		}
	}

	private final List<Subscriber> subscribers = new java.util.ArrayList<>();

	@org.junit.After
	public void tearDown() throws Exception {
		for (Subscriber s : subscribers) s.close();
	}

	private static void deleteRecursively(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) deleteRecursively(c);
		}
		f.delete();
	}

	private BdfDictionary manifest1() throws Exception {
		return manifest(1L, Collections.singletonList(cert),
				Collections.<Long>emptyList());
	}

	private BdfDictionary manifest2() throws Exception {
		return manifest(2L, Collections.<ChannelDelegationCert>emptyList(),
				Collections.singletonList(1L));
	}

	@Test
	public void beforeTheRevocationEveryPostIsShown() throws Exception {
		Subscriber s = new Subscriber();
		s.pull(manifest1(), p0, d1, p2, p3);
		assertEquals("p0 d1 p2 p3", s.view());
	}

	@Test
	public void aSynchronizedSubscriberKeepsLaterPublisherPostsAcrossTheRevocationAndARestart()
			throws Exception {
		Subscriber s = new Subscriber();
		s.pull(manifest1(), p0, d1);
		assertEquals("p0 d1", s.view());
		s.pull(manifest2(), p2, p3);
		assertEquals("p0 d1(withheld) p2 p3", s.view());
		s.restart();
		assertEquals("p0 d1(withheld) p2 p3", s.view());
		s.pull(manifest2(), p4);
		assertEquals("p0 d1(withheld) p2 p3 p4", s.view());
	}

	@Test
	public void aLaggingSubscriberReachesLaterPublisherPostsAcrossTheRevocationAndARestart()
			throws Exception {
		Subscriber s = new Subscriber();
		s.pull(manifest1(), p0);
		assertEquals("p0", s.view());
		s.pull(manifest2(), d1, p2, p3);
		assertEquals("p0 d1(withheld) p2 p3", s.view());
		s.restart();
		s.pull(manifest2(), p4);
		assertEquals("p0 d1(withheld) p2 p3 p4", s.view());
	}

	@Test
	public void aNewSubscriberReachesLaterPublisherPostsAfterTheRevocationAndARestart()
			throws Exception {
		Subscriber s = new Subscriber();
		s.pull(manifest2(), p0, d1, p2, p3);
		assertEquals("p0 d1(withheld) p2 p3", s.view());
		s.restart();
		s.pull(manifest2(), p4);
		assertEquals("p0 d1(withheld) p2 p3 p4", s.view());
	}

	@Test
	public void aTamperedRecordIsRejectedAndTheHonestChainStillArrives()
			throws Exception {
		Subscriber s = new Subscriber();
		s.pull(manifest1(), p0);
		ChannelPost tampered = new ChannelPost(channelId, 1, d1.getPrevHash(),
				d1.getTimestampHourMs(), "altered", d1.getAttachments(),
				d1.getTtlMs(), d1.getSignature(), false,
				d1.getDelegateSignerEd25519PubKey(),
				d1.getDelegateSignerMlDsaPubKey());
		s.pull(manifest2(), tampered, p2, p3);
		assertEquals("a tampered record admits nothing after it", "p0",
				s.view());
		s.pull(manifest2(), d1, p2, p3);
		assertEquals("p0 d1(withheld) p2 p3", s.view());
	}

	@Test
	public void aPostUnderAForgedCertificateIsRejected() throws Exception {
		Subscriber s = new Subscriber();
		ChannelDelegationCert forged = cert(stranger, delegate, HOUR,
				100 * HOUR, 1L);
		s.pull(manifest(1L, Collections.singletonList(forged),
				Collections.<Long>emptyList()), p0, d1, p2);
		String view = s.view();
		assertTrue("nothing from the forged certificate on is admitted: "
				+ view, !view.contains("d1") && !view.contains("p2"));
	}

	private BdfDictionary manifest(long manifestSeq,
			List<ChannelDelegationCert> active, List<Long> revoked)
			throws Exception {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		byte[] input = codec.manifestSignedInput(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, "", manifestSeq,
				null, active, revoked, ChannelState.NO_PINNED_POST, false,
				true);
		byte[] sig = signatures.signManifest(input,
				(HybridSignaturePrivateKey) publisher.getPrivate());
		return pullCodec.encodeManifest(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, "", manifestSeq,
				null, active, revoked, ChannelState.NO_PINNED_POST, false,
				true, sig);
	}

	private ChannelDelegationCert cert(KeyPair issuer, KeyPair delegatee,
			long from, long until, long seq) throws GeneralSecurityException {
		HybridSignaturePublicKey dPub =
				(HybridSignaturePublicKey) delegatee.getPublic();
		byte[] input = codec.delegationSignedInput(channelId,
				dPub.getEd25519PublicKey(), dPub.getMlDsaPublicKey(), from,
				until, seq);
		byte[] sig = signatures.signDelegation(input,
				(HybridSignaturePrivateKey) issuer.getPrivate());
		return new ChannelDelegationCert(channelId, dPub.getEd25519PublicKey(),
				dPub.getMlDsaPublicKey(), from, until, seq, sig);
	}

	private ChannelPost signed(KeyPair signer, long seq, byte[] prev,
			String body, @Nullable KeyPair claimedDelegate)
			throws GeneralSecurityException {
		long ts = HOUR * (seq + 2);
		List<ChannelPost.ChannelAttachment> none =
				Collections.<ChannelPost.ChannelAttachment>emptyList();
		byte[] input = codec.postSignedInput(channelId, seq, prev, ts, body,
				codec.attachmentsHash(none), 0L);
		byte[] sig = signatures.signPost(input,
				(HybridSignaturePrivateKey) signer.getPrivate());
		if (claimedDelegate == null) {
			return new ChannelPost(channelId, seq, prev, ts, body, none, 0L,
					sig, false);
		}
		HybridSignaturePublicKey dPub =
				(HybridSignaturePublicKey) claimedDelegate.getPublic();
		return new ChannelPost(channelId, seq, prev, ts, body, none, 0L, sig,
				false, dPub.getEd25519PublicKey(), dPub.getMlDsaPublicKey());
	}

	private static Clock clock() {
		return new Clock() {
			@Override
			public long currentTimeMillis() {
				return 50 * HOUR;
			}

			@Override
			public void sleep(long ms) {
			}
		};
	}

	private static DatabaseConfig config() {
		File root = new File(System.getProperty("java.io.tmpdir"),
				"zt-f06-" + System.nanoTime());
		return new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return new File(root, "db");
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return new File(root, "key");
			}

			@Override
			public org.zerionproject.core.api.crypto.KeyStrengthener
			getKeyStrengthener() {
				return null;
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static <T> T inert(Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					Class<?> r = method.getReturnType();
					if (r == boolean.class) return false;
					if (r == int.class) return 0;
					if (r == long.class) return 0L;
					return null;
				});
	}
}
