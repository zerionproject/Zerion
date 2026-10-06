package com.professor.zerion.android.mesh;

import android.content.Context;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshLink;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MeshBundlePrivacyTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;

	@Before
	public void setUp() {
		crypto = DaggerMeshCryptoTestComponent.create().getCryptoComponent();
	}

	@Test
	public void aBundleDoesNotCarryThePairingLinkKey() throws Exception {
		Account a = new Account(new MeshTestSettings());
		AsyncPrekeyBundle bundle = a.bundleSentTo(new ContactId(3));
		assertFalse(Arrays.equals(a.handshakePub(),
				bundle.getIdentityAgreePub()));
	}

	@Test
	public void contactsKnowOurOneTimePrekeysUnderDifferentIds()
			throws Exception {
		Account a = new Account(new MeshTestSettings());
		Set<String> toFirst = ids(a.bundleSentTo(new ContactId(3)));
		Set<String> toSecond = ids(a.bundleSentTo(new ContactId(4)));
		assertFalse(toFirst.isEmpty());
		Set<String> shared = new HashSet<>(toFirst);
		shared.retainAll(toSecond);
		assertTrue("ids known to both contacts: " + shared.size(),
				shared.isEmpty());
	}

	@Test
	public void theSignedPrekeyIdDoesNotTellAccountsApart() throws Exception {
		Account young = new Account(new MeshTestSettings());
		MeshTestSettings oldSettings = new MeshTestSettings();
		AsyncPrekeyStore oldStore = new AsyncPrekeyStore(crypto, oldSettings,
				new SystemClock());
		for (int i = 0; i < 4; i++) oldStore.rotateSignedPrekey();
		Account old = new Account(oldSettings);
		assertEquals(young.bundleSentTo(new ContactId(3)).getSignedPrekeyId(),
				old.bundleSentTo(new ContactId(3)).getSignedPrekeyId());
	}

	@Test
	public void anEnvelopeSealedToOurBundleOpensAfterThePairingKeyRotated()
			throws Exception {
		Account a = new Account(new MeshTestSettings());
		a.meshManager.start();
		a.rotateHandshakeKey();
		AsyncPrekeyBundle bundle = a.bundleSentTo(new ContactId(3));
		a.receive(sealTo(bundle));
		assertEquals(1, a.opened.size());
	}

	private byte[] sealTo(AsyncPrekeyBundle bundle) throws Exception {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		AsyncMeshDelivery.Identity sender = new AsyncMeshDelivery.Identity(
				sig.getPublic().getEncoded(), sig.getPrivate(),
				crypto.generateHybridAgreementKeyPair().getPublic()
						.getEncoded());
		AsyncMeshDelivery d = new AsyncMeshDelivery(crypto,
				new AsyncSealedSender(crypto),
				new AsyncPrekeyStore(crypto, new MeshTestSettings(),
						new SystemClock()),
				(s, t, p, ts) -> true, sender, new SystemClock());
		List<byte[]> captured = new ArrayList<>();
		MeshForwarder relay = new MeshForwarder(captured::add, random);
		MeshForwarder origin = new MeshForwarder(d, random);
		origin.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "l";
			}

			@Override
			public void broadcast(byte[] frame) {
				relay.onReceive(frame, "in");
			}
		});
		d.send(origin, bundle, MeshMessageRouter.MESH_TEXT, new byte[4096],
				3600L, System.currentTimeMillis(), false);
		return captured.get(0);
	}

	private static Set<String> ids(AsyncPrekeyBundle b) {
		Set<String> out = new HashSet<>();
		for (AsyncPrekeyBundle.OneTimePrekey p : b.getOneTimePrekeys()) {
			out.add(org.zerionproject.core.util.StringUtils.toHexString(p.id));
		}
		return out;
	}

	private final class Account {
		final IdentityManager identityManager = mock(IdentityManager.class);
		final DatabaseComponent db = mock(DatabaseComponent.class);
		final MessagingManager messagingManager = mock(MessagingManager.class);
		final List<byte[]> opened = new ArrayList<>();
		final MeshManager meshManager;
		final EventListener bundleListener;
		private KeyPair handshake;

		Account(SettingsManager settings) throws Exception {
			KeyPair sig = crypto.generateHybridSignatureKeyPair();
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) sig.getPublic();
			HybridSignaturePrivateKey priv =
					(HybridSignaturePrivateKey) sig.getPrivate();
			LocalAuthor author = new LocalAuthor(
					new AuthorId(TestUtils.getRandomId()),
					LocalAuthor.FORMAT_VERSION, "Me",
					new SignaturePublicKey(pub.getEd25519PublicKey()),
					new SignaturePrivateKey(priv.getEd25519PrivateKey()));
			when(identityManager.getLocalAuthor()).thenReturn(author);
			when(identityManager.getLocalMlDsaSigPublicKey())
					.thenReturn(pub.getMlDsaPublicKey());
			when(identityManager.getLocalMlDsaSigPrivateKey())
					.thenReturn(priv.getMlDsaPrivateKey());
			handshake = crypto.generateHybridAgreementKeyPair();
			when(identityManager.getHybridHandshakeKeys(any()))
					.thenAnswer(inv -> handshake);
			Transaction txn = new Transaction(null, true);
			when(db.transactionWithResult(anyBoolean(), any()))
					.thenAnswer(inv -> ((DbCallable<?, ?>) inv.getArgument(1))
							.call(txn));
			Context context = mock(Context.class);
			when(context.getApplicationContext()).thenReturn(context);
			meshManager = new MeshManager(context, crypto, identityManager, db,
					settings, new SystemClock(),
					new MeshManager.OpenedHandler() {
						@Override
						public boolean onOfflineMessage(byte[] sender,
								int type, byte[] payload, long ts) {
							return opened.add(payload);
						}

						@Override
						public boolean knowsSender(byte[] sender) {
							return true;
						}
					});
			EventBus eventBus = mock(EventBus.class);
			new MeshBundleManager(eventBus, mock(MeshBundleStore.class), crypto,
					mock(ContactManager.class), meshManager, messagingManager,
					Runnable::run);
			ArgumentCaptor<EventListener> listener =
					ArgumentCaptor.forClass(EventListener.class);
			verify(eventBus).addListener(listener.capture());
			bundleListener = listener.getValue();
		}

		byte[] handshakePub() {
			return handshake.getPublic().getEncoded();
		}

		void rotateHandshakeKey() {
			handshake = crypto.generateHybridAgreementKeyPair();
		}

		AsyncPrekeyBundle bundleSentTo(ContactId contactId) throws Exception {
			bundleListener.eventOccurred(new ContactConnectedEvent(contactId));
			ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
			verify(messagingManager).sendPrekeyBundle(eq(contactId),
					sent.capture());
			return AsyncPrekeyBundle.decode(sent.getValue());
		}

		void receive(byte[] envelope) throws Exception {
			Field f = MeshManager.class.getDeclaredField("delivery");
			f.setAccessible(true);
			((AsyncMeshDelivery) f.get(meshManager)).onFrame(envelope);
		}
	}
}
