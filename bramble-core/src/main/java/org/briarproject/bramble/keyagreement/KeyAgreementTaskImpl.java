package org.briarproject.bramble.keyagreement;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyAgreementCrypto;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.keyagreement.KeyAgreementResult;
import org.briarproject.bramble.api.keyagreement.KeyAgreementTask;
import org.briarproject.bramble.api.keyagreement.Payload;
import org.briarproject.bramble.api.keyagreement.PayloadEncoder;
import org.briarproject.bramble.api.keyagreement.event.KeyAgreementAbortedEvent;
import org.briarproject.bramble.api.keyagreement.event.KeyAgreementFailedEvent;
import org.briarproject.bramble.api.keyagreement.event.KeyAgreementFinishedEvent;
import org.briarproject.bramble.api.keyagreement.event.KeyAgreementListeningEvent;
import org.briarproject.bramble.api.keyagreement.event.KeyAgreementStartedEvent;
import org.briarproject.bramble.api.keyagreement.event.KeyAgreementStoppedListeningEvent;
import org.briarproject.bramble.api.keyagreement.event.KeyAgreementWaitingEvent;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.record.RecordReaderFactory;
import org.briarproject.bramble.api.record.RecordWriterFactory;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import javax.inject.Inject;
@MethodsNotNullByDefault
@ParametersNotNullByDefault
class KeyAgreementTaskImpl extends Thread implements KeyAgreementTask,
		KeyAgreementProtocol.Callbacks, KeyAgreementConnector.Callbacks {
	private final CryptoComponent crypto;
	private final KeyAgreementCrypto keyAgreementCrypto;
	private final EventBus eventBus;
	private final PayloadEncoder payloadEncoder;
	private final KeyPair localKeyPair;
	private final KeyAgreementConnector connector;

	private Payload localPayload;
	private Payload remotePayload;

	@Inject
	KeyAgreementTaskImpl(CryptoComponent crypto,
			KeyAgreementCrypto keyAgreementCrypto, EventBus eventBus,
			PayloadEncoder payloadEncoder, PluginManager pluginManager,
			ConnectionChooser connectionChooser,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory) {
		this.crypto = crypto;
		this.keyAgreementCrypto = keyAgreementCrypto;
		this.eventBus = eventBus;
		this.payloadEncoder = payloadEncoder;
		localKeyPair = crypto.generateAgreementKeyPair();
		connector = new KeyAgreementConnector(this, keyAgreementCrypto,
				pluginManager, connectionChooser, recordReaderFactory,
				recordWriterFactory);
	}

	@Override
	public synchronized void listen() {
		if (localPayload == null) {
			localPayload = connector.listen(localKeyPair);
			eventBus.broadcast(new KeyAgreementListeningEvent(localPayload));
		}
	}

	@Override
	public synchronized void stopListening() {
		if (localPayload != null) {
			if (remotePayload == null) connector.stopListening();
			else interrupt();
			eventBus.broadcast(new KeyAgreementStoppedListeningEvent());
		}
	}

	@Override
	public synchronized void connectAndRunProtocol(Payload remotePayload) {
		if (this.localPayload == null)
			throw new IllegalStateException(
					"Must listen before connecting");
		if (this.remotePayload != null)
			throw new IllegalStateException(
					"Already provided remote payload for this task");
		this.remotePayload = remotePayload;
		start();
	}

	@Override
	public void run() {
		boolean alice = localPayload.compareTo(remotePayload) < 0;
		KeyAgreementTransport transport =
				connector.connect(remotePayload, alice);
		if (transport == null) {
			eventBus.broadcast(new KeyAgreementFailedEvent());
			return;
		}
		KeyAgreementProtocol protocol = new KeyAgreementProtocol(this, crypto,
				keyAgreementCrypto, payloadEncoder, transport, remotePayload,
				localPayload, localKeyPair, alice);
		try {
			SecretKey masterKey = protocol.perform();
			// Zero local key pair after agreement
			java.util.Arrays.fill(localKeyPair.getPublic().getEncoded(), (byte) 0);
			java.util.Arrays.fill(localKeyPair.getPrivate().getEncoded(), (byte) 0);
			KeyAgreementResult result =
					new KeyAgreementResult(masterKey, transport.getConnection(),
							transport.getTransportId(), alice);
			eventBus.broadcast(new KeyAgreementFinishedEvent(result));
		} catch (AbortException e) {
			eventBus.broadcast(new KeyAgreementAbortedEvent(e.receivedAbort));
		} catch (IOException e) {
			eventBus.broadcast(new KeyAgreementFailedEvent());
		}
	}

	@Override
	public void connectionWaiting() {
		eventBus.broadcast(new KeyAgreementWaitingEvent());
	}

	@Override
	public void initialRecordReceived() {
		eventBus.broadcast(new KeyAgreementStartedEvent());
	}
}
