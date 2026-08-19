package com.professor.zerion.android.contact.add.nearby;

import android.app.Application;
import android.graphics.Bitmap;
import android.util.Base64;

import com.professor.zerion.android.contact.add.remote.QrCodeUtils;
import com.professor.zerion.android.viewmodel.DbViewModel;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.keyagreement.KeyAgreementResult;
import org.zerionproject.core.api.keyagreement.KeyAgreementTask;
import org.zerionproject.core.api.keyagreement.Payload;
import org.zerionproject.core.api.keyagreement.PayloadEncoder;
import org.zerionproject.core.api.keyagreement.PayloadParser;
import org.zerionproject.core.api.keyagreement.event.KeyAgreementAbortedEvent;
import org.zerionproject.core.api.keyagreement.event.KeyAgreementFailedEvent;
import org.zerionproject.core.api.keyagreement.event.KeyAgreementFinishedEvent;
import org.zerionproject.core.api.keyagreement.event.KeyAgreementListeningEvent;
import org.zerionproject.core.api.keyagreement.event.KeyAgreementStartedEvent;
import org.zerionproject.core.api.keyagreement.event.KeyAgreementWaitingEvent;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.connection.ConnectionManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.zerionproject.core.util.StringUtils.ISO_8859_1;

@NotNullByDefault
public class AddNearbyContactViewModel extends DbViewModel
		implements EventListener {

	public enum PairingState {
		SHOW_QR, SCANNED, CONNECTING, EXCHANGING, SUCCESS, FAILED
	}

	private final Provider<KeyAgreementTask> taskProvider;
	private final PayloadEncoder payloadEncoder;
	private final PayloadParser payloadParser;
	private final ContactExchangeManager contactExchangeManager;
	private final ConnectionManager connectionManager;
	private final EventBus eventBus;
	private final Executor ioExecutor;

	private final MutableLiveData<PairingState> state = new MutableLiveData<>();
	private final MutableLiveData<Bitmap> qrCode = new MutableLiveData<>();
	private final MutableLiveData<String> contactName = new MutableLiveData<>();
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean scanned = new AtomicBoolean(false);

	@Nullable
	private volatile KeyAgreementTask task;

	@Inject
	AddNearbyContactViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor,
			Provider<KeyAgreementTask> taskProvider,
			PayloadEncoder payloadEncoder, PayloadParser payloadParser,
			ContactExchangeManager contactExchangeManager,
			ConnectionManager connectionManager, EventBus eventBus,
			@IoExecutor Executor ioExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.taskProvider = taskProvider;
		this.payloadEncoder = payloadEncoder;
		this.payloadParser = payloadParser;
		this.contactExchangeManager = contactExchangeManager;
		this.connectionManager = connectionManager;
		this.eventBus = eventBus;
		this.ioExecutor = ioExecutor;
		eventBus.addListener(this);
	}

	public void startListening() {
		if (!started.compareAndSet(false, true)) return;
		// Nearby pairing is Bluetooth-only. If Bluetooth is off / unsupported,
		// fail fast so the UI can prompt the user instead of showing a QR that
		// can never pair and hanging on "connecting" forever.
		if (!isBluetoothReadyForPairing()) {
			started.set(false);
			state.postValue(PairingState.FAILED);
			return;
		}
		KeyAgreementTask t = taskProvider.get();
		task = t;
		ioExecutor.execute(t::listen);
	}

	private boolean isBluetoothReadyForPairing() {
		try {
			android.bluetooth.BluetoothManager bm =
					(android.bluetooth.BluetoothManager) getApplication()
							.getSystemService(android.content.Context
									.BLUETOOTH_SERVICE);
			if (bm == null) return false;
			android.bluetooth.BluetoothAdapter adapter = bm.getAdapter();
			return adapter != null && adapter.isEnabled()
					&& adapter.getBluetoothLeAdvertiser() != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
		KeyAgreementTask t = task;
		if (t != null) t.stopListening();
	}

	public void onQrScanned(String scannedText) {
		if (!scanned.compareAndSet(false, true)) return;
		state.postValue(PairingState.SCANNED);
		ioExecutor.execute(() -> {
			try {
				byte[] raw = Base64.decode(scannedText, Base64.NO_WRAP);
				Payload remote = payloadParser.parse(new String(raw, ISO_8859_1));
				KeyAgreementTask t = task;
				if (t != null) t.connectAndRunProtocol(remote);
			} catch (IOException | IllegalArgumentException e) {
				postFailed();
			}
		});
	}

	private void postFailed() {
		scanned.set(false);
		state.postValue(PairingState.FAILED);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof KeyAgreementListeningEvent) {
			Payload local = ((KeyAgreementListeningEvent) e).getLocalPayload();
			onLocalPayload(local);
		} else if (e instanceof KeyAgreementWaitingEvent) {
			state.postValue(PairingState.CONNECTING);
		} else if (e instanceof KeyAgreementStartedEvent) {
			state.postValue(PairingState.EXCHANGING);
		} else if (e instanceof KeyAgreementFinishedEvent) {
			KeyAgreementResult r = ((KeyAgreementFinishedEvent) e).getResult();
			ioExecutor.execute(() -> exchangeContacts(r));
		} else if (e instanceof KeyAgreementFailedEvent) {
			postFailed();
		} else if (e instanceof KeyAgreementAbortedEvent) {
			postFailed();
		}
	}

	private void onLocalPayload(Payload local) {
		try {
			String qr = Base64.encodeToString(payloadEncoder.encode(local),
					Base64.NO_WRAP);
			Bitmap bitmap = QrCodeUtils.generateQrCode(qr);
			qrCode.postValue(bitmap);
			state.postValue(PairingState.SHOW_QR);
		} catch (RuntimeException ex) {
			state.postValue(PairingState.FAILED);
		}
	}

	private void exchangeContacts(KeyAgreementResult r) {
		DuplexTransportConnection conn = r.getConnection();
		SecretKey masterKey = r.getMasterKey();
		try {
			Contact contact = contactExchangeManager.exchangeContacts(conn,
					masterKey, r.wasAlice(), true);
			connectionManager.manageOutgoingConnection(contact.getId(),
					r.getTransportId(), conn);
			contactName.postValue(contact.getAuthor().getName());
			state.postValue(PairingState.SUCCESS);
		} catch (Exception ex) {
			try {
				conn.getReader().dispose(true, false);
				conn.getWriter().dispose(true);
			} catch (Exception ignored) {
			}
			state.postValue(PairingState.FAILED);
		}
	}

	@UiThread
	public LiveData<PairingState> getState() {
		return state;
	}

	@UiThread
	public LiveData<Bitmap> getQrCode() {
		return qrCode;
	}

	@UiThread
	public LiveData<String> getContactName() {
		return contactName;
	}
}
