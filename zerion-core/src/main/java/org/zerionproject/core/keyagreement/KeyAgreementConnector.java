package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.crypto.KeyAgreementCrypto;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.keyagreement.Payload;
import org.zerionproject.core.api.keyagreement.TransportDescriptor;
import org.zerionproject.core.api.plugin.LanTcpConstants;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.CONNECTION_TIMEOUT;

@NotNullByDefault
class KeyAgreementConnector {

	interface Callbacks {
		void connectionWaiting();
	}
	private static final List<TransportId> PREFERRED_TRANSPORTS =
			java.util.Arrays.asList(
					org.zerionproject.core.api.plugin.BluetoothConstants.ID,
					LanTcpConstants.ID);

	private final Callbacks callbacks;
	private final KeyAgreementCrypto keyAgreementCrypto;
	private final PluginManager pluginManager;
	private final ConnectionChooser connectionChooser;
	private final RecordReaderFactory recordReaderFactory;
	private final RecordWriterFactory recordWriterFactory;

	private final List<KeyAgreementListener> listeners =
			new CopyOnWriteArrayList<>();
	private final CountDownLatch aliceLatch = new CountDownLatch(1);
	private final AtomicBoolean waitingSent = new AtomicBoolean(false);

	private volatile boolean alice = false, stopped = false;

	KeyAgreementConnector(Callbacks callbacks,
			KeyAgreementCrypto keyAgreementCrypto, PluginManager pluginManager,
			ConnectionChooser connectionChooser,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory) {
		this.callbacks = callbacks;
		this.keyAgreementCrypto = keyAgreementCrypto;
		this.pluginManager = pluginManager;
		this.connectionChooser = connectionChooser;
		this.recordReaderFactory = recordReaderFactory;
		this.recordWriterFactory = recordWriterFactory;
	}

	Payload listen(KeyPair localKeyPair) {
		byte[] commitment = keyAgreementCrypto.deriveKeyCommitment(
				localKeyPair.getPublic());
		List<TransportDescriptor> descriptors = new ArrayList<>();
		for (DuplexPlugin plugin : pluginManager.getKeyAgreementPlugins()) {
			KeyAgreementListener l =
					plugin.createKeyAgreementListener(commitment);
			if (l != null) {
				TransportId id = plugin.getId();
				descriptors.add(new TransportDescriptor(id, l.getDescriptor()));
				listeners.add(l);
				connectionChooser.submit(new ReadableTask(l::accept));
			}
		}
		return new Payload(commitment, descriptors);
	}

	void stopListening() {
		stopped = true;
		aliceLatch.countDown();
		for (KeyAgreementListener l : listeners) l.close();
		connectionChooser.stop();
	}

	@Nullable
	public KeyAgreementTransport connect(Payload remotePayload, boolean alice) {
		this.alice = alice;
		aliceLatch.countDown();
		Map<TransportId, TransportDescriptor> descriptors = new HashMap<>();
		for (TransportDescriptor d : remotePayload.getTransportDescriptors()) {
			descriptors.put(d.getId(), d);
		}
		List<Pair<DuplexPlugin, BdfList>> transports = new ArrayList<>();
		for (TransportId id : PREFERRED_TRANSPORTS) {
			TransportDescriptor d = descriptors.get(id);
			Plugin p = pluginManager.getPlugin(id);
			if (d != null && p instanceof DuplexPlugin) {
				transports.add(new Pair<>((DuplexPlugin) p,
						d.getDescriptor()));
			}
		}

		if (!transports.isEmpty()) {
			byte[] commitment = remotePayload.getCommitment();
			connectionChooser.submit(new ReadableTask(new ConnectorTask(
					transports, commitment)));
		}
		try {
			KeyAgreementConnection chosen =
					connectionChooser.poll(CONNECTION_TIMEOUT);
			if (chosen == null) return null;
			return new KeyAgreementTransport(recordReaderFactory,
					recordWriterFactory, chosen);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (IOException e) {
			return null;
		} finally {
			stopListening();
		}
	}

	private void waitingForAlice() {
		if (!waitingSent.getAndSet(true)) callbacks.connectionWaiting();
	}

	private class ConnectorTask implements Callable<KeyAgreementConnection> {

		private final List<Pair<DuplexPlugin, BdfList>> transports;
		private final byte[] commitment;

		private ConnectorTask(List<Pair<DuplexPlugin, BdfList>> transports,
				byte[] commitment) {
			this.transports = transports;
			this.commitment = commitment;
		}

		@Nullable
		@Override
		public KeyAgreementConnection call() throws Exception {
			while (!stopped) {
				for (Pair<DuplexPlugin, BdfList> pair : transports) {
					if (stopped) return null;
					DuplexPlugin plugin = pair.getFirst();
					BdfList descriptor = pair.getSecond();
					DuplexTransportConnection conn =
							plugin.createKeyAgreementConnection(commitment,
									descriptor);
					if (conn != null) {
						return new KeyAgreementConnection(
								conn, plugin.getId());
					}
				}
				Thread.sleep(2000);
			}
			return null;
		}
	}

	private class ReadableTask implements Callable<KeyAgreementConnection> {

		private final Callable<KeyAgreementConnection> connectionTask;

		private ReadableTask(Callable<KeyAgreementConnection> connectionTask) {
			this.connectionTask = connectionTask;
		}

		@Nullable
		@Override
		public KeyAgreementConnection call() throws Exception {
			KeyAgreementConnection c = connectionTask.call();
			if (c == null) return null;
			aliceLatch.await();
			if (alice || stopped) return c;
			InputStream in = c.getConnection().getReader().getInputStream();
			while (!stopped && in.available() == 0) {
				Thread.sleep(500);
			}
			return c;
		}
	}
}
