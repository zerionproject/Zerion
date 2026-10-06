package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Priority;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

@NotNullByDefault
public class ZtpConnectionHandlerImpl implements ZtpConnectionHandler {

	private final ZtpConnectionEstablisher establisher;
	private final ZtpSessionProvider sessionProvider;
	private final ZppConnectionRunner connectionRunner;
	private final ConnectionRegistry connectionRegistry;
	private final org.zerionproject.core.api.plugin.OnionClientAuthManager
			inboundPolicy;
	private final SecureRandom random = new SecureRandom();
	@Nullable
	private final RootEvolutionManager evolutions;

	private final Map<Integer, TransportSession> liveSessions =
			new ConcurrentHashMap<>();

	public ZtpConnectionHandlerImpl(ZtpConnectionEstablisher establisher,
			ZtpSessionProvider sessionProvider,
			ZppConnectionRunner connectionRunner,
			ConnectionRegistry connectionRegistry,
			org.zerionproject.core.api.plugin.OnionClientAuthManager
					inboundPolicy) {
		this(establisher, sessionProvider, connectionRunner,
				connectionRegistry, inboundPolicy, null);
	}

	public ZtpConnectionHandlerImpl(ZtpConnectionEstablisher establisher,
			ZtpSessionProvider sessionProvider,
			ZppConnectionRunner connectionRunner,
			ConnectionRegistry connectionRegistry,
			org.zerionproject.core.api.plugin.OnionClientAuthManager
					inboundPolicy,
			@Nullable RootEvolutionManager evolutions) {
		this.inboundPolicy = inboundPolicy;
		this.establisher = establisher;
		this.sessionProvider = sessionProvider;
		this.connectionRunner = connectionRunner;
		this.connectionRegistry = connectionRegistry;
		this.evolutions = evolutions;
	}

	private static final class TransportSession {
		private final TransportId transportId;
		private int count;

		private TransportSession(TransportId transportId) {
			this.transportId = transportId;
			this.count = 1;
		}
	}

	boolean acquireSession(int contactId, TransportId transportId) {
		synchronized (liveSessions) {
			TransportSession s = liveSessions.get(contactId);
			if (s == null) {
				liveSessions.put(contactId, new TransportSession(transportId));
				return true;
			}
			if (s.transportId.equals(transportId) && s.count < 2) {
				s.count++;
				return true;
			}
			return false;
		}
	}

	void releaseSession(int contactId, TransportId transportId) {
		synchronized (liveSessions) {
			TransportSession s = liveSessions.get(contactId);
			if (s != null && s.transportId.equals(transportId)
					&& --s.count <= 0) {
				liveSessions.remove(contactId);
			}
		}
	}

	@Override
	public void handleOutgoing(TransportId transportId, int contactId,
			InputStream in, OutputStream out) throws IOException {
		StoredContactSession stored = sessionProvider.getStoredSession(contactId);
		if (stored == null) throw new FormatException();
		runResumed(transportId, contactId, stored, in, out, false,
				dialEpoch(contactId, stored.getRootKeys()), false);
	}

	private long dialEpoch(int contactId, ContactRootKeys keys) {
		if (evolutions == null) return keys.getSendEpoch();
		return evolutions.dialEpoch(new ContactId(contactId), keys);
	}

	@Override
	public void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out) throws IOException {
		handleIncoming(transportId, in, out, false);
	}

	@Override
	public void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out, boolean viaAuthorizedService)
			throws IOException {
		BufferedInputStream bufferedIn = new BufferedInputStream(in);
		byte[] tag = peekTag(bufferedIn);
		int contactId = sessionProvider.recogniseIncoming(tag);
		if (contactId < 0) {
			throw new FormatException();
		}
		boolean tor = org.zerionproject.core.api.plugin.TorConstants.ID
				.equals(transportId);
		if (tor) {
			ContactId c = new ContactId(contactId);
			if (!inboundPolicy.acceptsInbound(c, viaAuthorizedService)) {
				throw new FormatException();
			}
		}
		StoredContactSession stored = sessionProvider.getStoredSession(contactId);
		if (stored == null) throw new FormatException();
		ContactRootKeys keys = stored.getRootKeys();
		long epoch = establisher.epochOfTag(contactId, keys, stored.isAlice(),
				tag);
		if (epoch < 0) epoch = keys.getSendEpoch();
		runResumed(transportId, contactId, stored, bufferedIn, out, true,
				epoch, tor && viaAuthorizedService);
	}

	@Override
	public void handlePaired(TransportId transportId, int contactId,
			boolean incoming, InputStream in, OutputStream out)
			throws IOException {
		StoredContactSession stored = sessionProvider.getStoredSession(contactId);
		if (stored == null) throw new FormatException();
		runResumed(transportId, contactId, stored, in, out, incoming,
				incoming ? stored.getRootKeys().getSendEpoch()
						: dialEpoch(contactId, stored.getRootKeys()), false);
	}

	private void runResumed(TransportId transportId, int contactId,
			StoredContactSession stored, InputStream in, OutputStream out,
			boolean incoming, long sendEpoch, boolean viaAuthorizedService)
			throws IOException {
		if (!acquireSession(contactId, transportId)) {
			wipe(stored.getRootKeys());
			return;
		}
		ZwfDuplexConnection connection = null;
		AtomicBoolean registered = new AtomicBoolean(false);
		try {
			connection = establisher.resume(contactId, stored.getRootKeys(),
					sendEpoch, stored.isAlice(), stored.getGeneration(), in,
					out);
			ContactId c = new ContactId(contactId);
			AtomicBoolean closed = new AtomicBoolean(false);
			InterruptibleConnection ic = new InterruptibleConnection() {
				@Override
				public void interruptOutgoingSession() {
					forceClose();
				}

				@Override
				public void forceClose() {
					if (!closed.compareAndSet(false, true)) return;
					try {
						in.close();
					} catch (IOException ignored) {
					}
					try {
						out.close();
					} catch (IOException ignored) {
					}
				}
			};
			connection.setFirstFrameListener(epoch -> {
				if (viaAuthorizedService) {
					inboundPolicy.inboundViaAuthorizedService(c);
				}
				if (incoming) {
					connectionRegistry.registerIncomingConnection(c,
							transportId, ic);
				} else {
					byte[] nonce = new byte[16];
					random.nextBytes(nonce);
					connectionRegistry.registerOutgoingConnection(c,
							transportId, ic, new Priority(nonce));
				}
				registered.set(true);
				sessionProvider.sessionEstablished(contactId);
			});
			if (evolutions != null) {
				connection.setControlHandler(
						evolutions.newEvolution(c, stored.isAlice()));
			}
			boolean exception = false;
			try {
				connectionRunner.run(contactId, connection);
			} catch (IOException e) {
				exception = true;
				throw e;
			} finally {
				if (registered.get()) {
					connectionRegistry.unregisterConnection(c, transportId,
							ic, incoming, exception);
				}
				sessionProvider.sessionClosed(contactId);
			}
		} finally {
			if (connection != null) connection.destroyKeyMaterial();
			wipe(stored.getRootKeys());
			releaseSession(contactId, transportId);
			if (!incoming && evolutions != null) {
				evolutions.dialEnded(new ContactId(contactId),
						registered.get());
			}
		}
	}

	private static void wipe(ContactRootKeys keys) {
		keys.getCurrent().clear();
		SecretKey pending = keys.getPending();
		if (pending != null) pending.clear();
	}

	private static byte[] peekTag(BufferedInputStream in) throws IOException {
		in.mark(TAG_LENGTH);
		byte[] tag = new byte[TAG_LENGTH];
		int off = 0;
		while (off < TAG_LENGTH) {
			int r = in.read(tag, off, TAG_LENGTH - off);
			if (r == -1) throw new EOFException();
			off += r;
		}
		in.reset();
		return tag;
	}
}
