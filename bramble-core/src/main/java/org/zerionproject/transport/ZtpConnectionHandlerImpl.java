package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.sync.Priority;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

/**
 * Handles transport connections to established contacts. Every connection after
 * the initial pairing resumes the contact's stored session rather than running a
 * handshake: the root key and role were fixed at pairing, and the post-quantum
 * ratchet state is restored from what was persisted when the previous connection
 * ended.
 *
 * <p>Outgoing connections carry the dialled contact id. Incoming connections are
 * anonymous, so the stream tag is peeked and recognised to a contact before the
 * session is resumed; a tag that matches no known contact is rejected (first-time
 * pairing arrives on the separate rendezvous path, not here).
 *
 * <p>The live connection is handed to the {@link ZppConnectionRunner} for the
 * duration of the session, and the evolved Mode 3-Full state is persisted when it
 * ends.
 */
@NotNullByDefault
public class ZtpConnectionHandlerImpl implements ZtpConnectionHandler {

	private final ZtpConnectionEstablisher establisher;
	private final ZtpSessionProvider sessionProvider;
	private final ZppConnectionRunner connectionRunner;
	private final ConnectionRegistry connectionRegistry;
	private final Random random = new Random();

	public ZtpConnectionHandlerImpl(ZtpConnectionEstablisher establisher,
			ZtpSessionProvider sessionProvider,
			ZppConnectionRunner connectionRunner,
			ConnectionRegistry connectionRegistry) {
		this.establisher = establisher;
		this.sessionProvider = sessionProvider;
		this.connectionRunner = connectionRunner;
		this.connectionRegistry = connectionRegistry;
	}

	@Override
	public void handleOutgoing(int contactId, InputStream in, OutputStream out)
			throws IOException {
		StoredContactSession stored = sessionProvider.getStoredSession(contactId);
		if (stored == null) throw new FormatException();
		runResumed(contactId, stored, in, out, false);
	}

	@Override
	public void handleIncoming(InputStream in, OutputStream out)
			throws IOException {
		BufferedInputStream bufferedIn = new BufferedInputStream(in);
		byte[] tag = peekTag(bufferedIn);
		int contactId = sessionProvider.recogniseIncoming(tag);
		if (contactId < 0) {
			throw new FormatException();
		}
		StoredContactSession stored = sessionProvider.getStoredSession(contactId);
		if (stored == null) throw new FormatException();
		runResumed(contactId, stored, bufferedIn, out, true);
	}

	private void runResumed(int contactId, StoredContactSession stored,
			InputStream in, OutputStream out, boolean incoming)
			throws IOException {
		ZwfDuplexConnection connection = establisher.resume(contactId,
				stored.getRootKey(), stored.isAlice(),
				stored.getMode3FullState(), in, out);
		ContactId c = new ContactId(contactId);
		InterruptibleConnection ic = new InterruptibleConnection() {
			@Override
			public void interruptOutgoingSession() {
			}

			@Override
			public void forceClose() {
			}
		};
		if (incoming) {
			connectionRegistry.registerIncomingConnection(c, TorConstants.ID, ic);
		} else {
			byte[] nonce = new byte[16];
			random.nextBytes(nonce);
			connectionRegistry.registerOutgoingConnection(c, TorConstants.ID, ic,
					new Priority(nonce));
		}
		boolean exception = false;
		try {
			connectionRunner.run(contactId, connection);
		} catch (IOException e) {
			exception = true;
			throw e;
		} finally {
			connectionRegistry.unregisterConnection(c, TorConstants.ID, ic,
					incoming, exception);
			sessionProvider.saveMode3FullState(contactId,
					connection.currentMode3FullState());
		}
	}

	/** Reads the stream tag without consuming it, so the resumed connection can
	 * re-read it. */
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
