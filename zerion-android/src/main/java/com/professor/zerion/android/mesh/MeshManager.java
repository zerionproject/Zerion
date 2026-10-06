package com.professor.zerion.android.mesh;

import android.content.Context;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

@NotNullByDefault
public class MeshManager {

	private static final int ONE_TIME_PREKEY_POOL = 50;
	private static final long COVER_TTL_SECONDS = 7L * 24 * 3600;
	private static final int COVER_ONE_TIME_KIND_ODDS = 4;
	private static final int MAX_COVER_PAYLOAD_BYTES = 200;

	public interface OpenedHandler {
		boolean onOfflineMessage(byte[] senderIdentitySigPub, int messageType,
				byte[] payload, long sendTimestamp);

		default boolean knowsSender(byte[] senderIdentitySigPub) {
			return true;
		}
	}

	private final Context context;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;
	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final Clock clock;
	private final OpenedHandler openedHandler;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final Object lifecycleLock = new Object();

	@Nullable
	private volatile AsyncPrekeyStore store;
	@Nullable
	private volatile AsyncMeshDelivery delivery;
	@Nullable
	private volatile MeshForwarder forwarder;
	@Nullable
	private volatile BleMeshTransport ble;
	@Nullable
	private volatile Runnable peerConnectedListener;

	public MeshManager(Context context, CryptoComponent crypto,
			IdentityManager identityManager, DatabaseComponent db,
			SettingsManager settingsManager, Clock clock,
			OpenedHandler openedHandler) {
		this.context = context.getApplicationContext();
		this.crypto = crypto;
		this.identityManager = identityManager;
		this.db = db;
		this.settingsManager = settingsManager;
		this.clock = clock;
		this.openedHandler = openedHandler;
	}

	public void start() throws DbException {
		synchronized (lifecycleLock) {
			startLocked();
		}
	}

	private void startLocked() throws DbException {
		if (!running.compareAndSet(false, true)) return;
		AsyncPrekeyStore prekeyStore =
				new AsyncPrekeyStore(crypto, settingsManager, clock);
		store = prekeyStore;
		AsyncMeshDelivery.Identity identity = loadIdentity(prekeyStore);
		AsyncSealedSender sealer = new AsyncSealedSender(crypto);
		AsyncMeshDelivery meshDelivery = new AsyncMeshDelivery(crypto, sealer,
				prekeyStore, new AsyncMeshDelivery.OpenedListener() {
					@Override
					public boolean onOpened(byte[] senderPub, int type,
							byte[] payload, long ts) {
						return openedHandler.onOfflineMessage(senderPub, type,
								payload, ts);
					}

					@Override
					public boolean knowsSender(byte[] senderPub) {
						return openedHandler.knowsSender(senderPub);
					}
				}, identity, clock);
		delivery = meshDelivery;
		MeshForwarder meshForwarder =
				new MeshForwarder(meshDelivery, new SecureRandom());
		forwarder = meshForwarder;
		prekeyStore.getSignedPrekey();
		BleMeshTransport bleTransport =
				new BleMeshTransport(context, meshForwarder,
						this::maskBluetoothName, this::notifyPeerConnected);
		ble = bleTransport;
		bleTransport.start();
	}

	public void stop() {
		synchronized (lifecycleLock) {
			stopLocked();
		}
	}

	private void stopLocked() {
		if (running.compareAndSet(true, false)) {
			BleMeshTransport b = ble;
			if (b != null) b.stop();
			ble = null;
			forwarder = null;
			delivery = null;
			store = null;
		}
		restoreBluetoothName();
	}

	private void maskBluetoothName() {
		MeshBluetoothName.Adapter adapter = bluetoothNameAdapter();
		if (adapter == null) return;
		try {
			MeshBluetoothName.mask(adapter, settingsManager);
		} catch (Exception e) {
		}
	}

	public void restoreBluetoothName() {
		MeshBluetoothName.Adapter adapter = bluetoothNameAdapter();
		if (adapter == null) return;
		try {
			MeshBluetoothName.restore(adapter, settingsManager);
		} catch (Exception e) {
		}
	}

	@Nullable
	private MeshBluetoothName.Adapter bluetoothNameAdapter() {
		if (android.os.Build.VERSION.SDK_INT
				< android.os.Build.VERSION_CODES.S) return null;
		android.bluetooth.BluetoothManager bm =
				(android.bluetooth.BluetoothManager)
						context.getSystemService(Context.BLUETOOTH_SERVICE);
		if (bm == null) return null;
		android.bluetooth.BluetoothAdapter adapter = bm.getAdapter();
		if (adapter == null) return null;
		return new MeshBluetoothName.Adapter() {
			@Nullable
			@Override
			public String getName() {
				return adapter.getName();
			}

			@Override
			public boolean setName(String name) {
				return adapter.setName(name);
			}
		};
	}

	public boolean isRunning() {
		return running.get();
	}

	public void setPeerConnectedListener(@Nullable Runnable listener) {
		this.peerConnectedListener = listener;
	}

	private void notifyPeerConnected() {
		Runnable listener = peerConnectedListener;
		if (listener != null) {
			try {
				listener.run();
			} catch (Exception e) {
			}
		}
	}

	public int getPeerCount() {
		int count = 0;
		BleMeshTransport b = ble;
		if (b != null) count += b.getPeerCount();
		return count;
	}

	public AsyncPrekeyBundle publishBundle(ContactId audience)
			throws DbException, GeneralSecurityException {
		AsyncPrekeyStore prekeyStore = store;
		if (prekeyStore == null) {
			prekeyStore = new AsyncPrekeyStore(crypto, settingsManager, clock);
		}
		List<AsyncPrekeyBundle.OneTimePrekey> otks =
				prekeyStore.topUpOneTimePrekeys(ONE_TIME_PREKEY_POOL,
						audienceOf(audience));
		AsyncPrekeyStore.SignedPrekey spk = prekeyStore.getSignedPrekey();
		AsyncMeshDelivery.Identity id = loadIdentity(prekeyStore);
		return AsyncPrekeyBundle.create(crypto, id.sigPub, id.sigPriv,
				id.agreePub, AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID,
				spk.pub, spk.expiry, otks);
	}

	private static byte[] audienceOf(ContactId contactId) {
		int id = contactId.getInt();
		return new byte[] {(byte) (id >>> 24), (byte) (id >>> 16),
				(byte) (id >>> 8), (byte) id};
	}

	public void sendOffline(AsyncPrekeyBundle recipientBundle, int messageType,
			byte[] payload, long ttlSeconds, boolean preferOneTime)
			throws GeneralSecurityException {
		AsyncMeshDelivery d = delivery;
		MeshForwarder f = forwarder;
		if (d == null || f == null) {
			throw new IllegalStateException("mesh not running");
		}
		d.send(f, recipientBundle, messageType, payload, ttlSeconds,
				sendTimestampMs(clock), preferOneTime);
	}

	public void sendCover() throws GeneralSecurityException {
		sendCover(coverRandom.nextInt(COVER_ONE_TIME_KIND_ODDS) == 0,
				COVER_TTL_SECONDS);
	}

	public void sendCover(boolean oneTimeKind, long ttlSeconds)
			throws GeneralSecurityException {
		AsyncMeshDelivery d = delivery;
		MeshForwarder f = forwarder;
		if (d == null || f == null) return;
		byte[] dummy = new byte[coverRandom.nextInt(MAX_COVER_PAYLOAD_BYTES)];
		coverRandom.nextBytes(dummy);
		d.sendCover(f, MeshPadding.pad(dummy), ttlSeconds,
				sendTimestampMs(clock), oneTimeKind);
	}

	static long sendTimestampMs(Clock clock) {
		return clock.currentTimeMillis();
	}

	private final SecureRandom coverRandom = new SecureRandom();

	private AsyncMeshDelivery.Identity loadIdentity(AsyncPrekeyStore prekeyStore)
			throws DbException {
		LocalAuthor author = identityManager.getLocalAuthor();
		byte[] ed25519Pub = author.getPublicKey().getEncoded();
		byte[] ed25519Priv = author.getPrivateKey().getEncoded();
		byte[] mlDsaSigPub = identityManager.getLocalMlDsaSigPublicKey();
		byte[] mlDsaSigPriv = identityManager.getLocalMlDsaSigPrivateKey();
		byte[] sigPub = new HybridSignaturePublicKey(ed25519Pub, mlDsaSigPub)
				.getEncoded();
		HybridSignaturePrivateKey sigPriv =
				new HybridSignaturePrivateKey(ed25519Priv, mlDsaSigPriv);
		byte[] legacyAgreePub = db.transactionWithResult(true, txn ->
				identityManager.getHybridHandshakeKeys(txn).getPublic()
						.getEncoded());
		return new AsyncMeshDelivery.Identity(sigPub, sigPriv,
				prekeyStore.getMeshAgreementPublicKey(), legacyAgreePub);
	}
}
