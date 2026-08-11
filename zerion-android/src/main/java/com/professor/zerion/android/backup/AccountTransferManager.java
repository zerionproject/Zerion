package com.professor.zerion.android.backup;

import com.professor.zerion.android.contact.identity.ContactSafetyNumber;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.util.Base32;
import org.zerionproject.app.channel.OnionPublisher;
import org.zerionproject.app.channel.OnionPublisher.OnionHandle;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.net.SocketFactory;

import static com.professor.zerion.android.backup.TransferException.Reason.CANCELLED;
import static com.professor.zerion.android.backup.TransferException.Reason.CONNECT_FAILED;
import static com.professor.zerion.android.backup.TransferException.Reason.IO_ERROR;
import static com.professor.zerion.android.backup.TransferException.Reason.PROTOCOL;

@NotNullByDefault
public class AccountTransferManager {

	public static final String LINK_PREFIX = "zerion-transfer://";

	private static final int PUBKEY_LEN = 32;
	private static final int MAX_BUNDLE_BYTES = 512 * 1024 * 1024;
	private static final byte[] AAD =
			"Zerion-Account-Transfer-v1".getBytes(StandardCharsets.UTF_8);
	private static final String SESSION_LABEL =
			"com.professor.zerion.transfer/sessionKey";
	private static final byte GO = (byte) 0x42;
	private static final int REMOTE_PORT = 80;
	private static final int READ_TIMEOUT_MS = 180_000;
	private static final int ACCEPT_TIMEOUT_MS = 10 * 60 * 1000;
	private static final int CONNECT_RETRY_MS = 5_000;
	private static final long CONNECT_DEADLINE_MS = 120_000;

	public enum Status {
		PUBLISHING, WAITING_FOR_PEER, CONNECTING, AUTHENTICATING,
		TRANSFERRING, IMPORTING, DONE
	}

	public interface Callback {
		void onStatus(Status status);

		void onPairingReady(String qrPayload);

		boolean onSasConfirm(String safetyNumber);
	}

	private final CryptoComponent crypto;
	private final OnionPublisher onionPublisher;
	private final SocketFactory torSocketFactory;
	private final AccountBackupManager backup;
	private final VaultCrypto vaultCrypto = new VaultCrypto();

	@Nullable
	private volatile ServerSocket currentServer;
	@Nullable
	private volatile Socket currentSocket;
	private volatile boolean cancelled;

	@Inject
	AccountTransferManager(CryptoComponent crypto,
			OnionPublisher onionPublisher, SocketFactory torSocketFactory,
			AccountBackupManager backup) {
		this.crypto = crypto;
		this.onionPublisher = onionPublisher;
		this.torSocketFactory = torSocketFactory;
		this.backup = backup;
	}

	public void cancel() {
		cancelled = true;
		closeQuietly(currentServer);
		closeQuietly(currentSocket);
	}

	/**
	 * Old phone: publish a one-time onion, show its QR, accept the new phone's
	 * connection, then stream this account.
	 */
	public void send(Callback cb) throws TransferException {
		cancelled = false;
		cb.onStatus(Status.PUBLISHING);
		KeyPair myKp = crypto.generateAgreementKeyPair();
		byte[] myPub = myKp.getPublic().getEncoded();
		ServerSocket ss = null;
		OnionHandle handle = null;
		try {
			ss = new ServerSocket();
			ss.bind(new InetSocketAddress("127.0.0.1", 0));
			ss.setSoTimeout(ACCEPT_TIMEOUT_MS);
			currentServer = ss;
			handle = onionPublisher.publish(ss.getLocalPort(), null);
			cb.onPairingReady(LINK_PREFIX + Base32.encode(myPub) + ":"
					+ handle.getOnion());
			cb.onStatus(Status.WAITING_FOR_PEER);
			Socket client = ss.accept();
			currentSocket = client;
			try {
				runSend(client, myKp, myPub, cb);
			} finally {
				closeQuietly(client);
			}
		} catch (IOException e) {
			throw new TransferException(IO_ERROR);
		} finally {
			currentSocket = null;
			currentServer = null;
			if (handle != null) {
				try {
					onionPublisher.unpublish(handle.getOnion());
				} catch (IOException ignored) {
				}
			}
			closeQuietly(ss);
		}
	}

	/**
	 * New phone: scan the old phone's QR, dial it over Tor, receive + import
	 * the account under a new device password.
	 */
	public void receive(String qrPayload, char[] newPassword, Callback cb)
			throws TransferException {
		cancelled = false;
		String body = qrPayload.startsWith(LINK_PREFIX)
				? qrPayload.substring(LINK_PREFIX.length()) : qrPayload;
		int sep = body.indexOf(':');
		if (sep < 0) throw new TransferException(PROTOCOL);
		byte[] theirPub;
		try {
			theirPub = Base32.decode(body.substring(0, sep), false);
		} catch (RuntimeException e) {
			throw new TransferException(PROTOCOL);
		}
		if (theirPub.length != PUBKEY_LEN) throw new TransferException(PROTOCOL);
		String onion = body.substring(sep + 1);
		KeyPair myKp = crypto.generateAgreementKeyPair();
		byte[] myPub = myKp.getPublic().getEncoded();
		cb.onStatus(Status.CONNECTING);
		Socket s = dialWithRetry(onion);
		currentSocket = s;
		try {
			runReceive(s, myKp, myPub, theirPub, newPassword, cb);
		} finally {
			currentSocket = null;
			closeQuietly(s);
		}
	}

	private void runSend(Socket s, KeyPair myKp, byte[] myPub, Callback cb)
			throws TransferException {
		try {
			s.setSoTimeout(READ_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(s.getInputStream());
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			cb.onStatus(Status.AUTHENTICATING);
			byte[] theirPub = readFrame(in, PUBKEY_LEN, PUBKEY_LEN);
			SecretKey sessionKey = deriveSession(myKp, myPub, theirPub);
			try {
				String sas = ContactSafetyNumber.forKeys(myPub, theirPub);
				if (!cb.onSasConfirm(sas)) {
					out.write(0);
					out.flush();
					throw new TransferException(CANCELLED);
				}
				out.write(GO);
				out.flush();
				cb.onStatus(Status.TRANSFERRING);
				byte[] bundleBytes = backup.snapshotBundle();
				try {
					byte[] sealed = vaultCrypto.encrypt(bundleBytes,
							sessionKey.getBytes(), AAD).toBytes();
					writeFrame(out, sealed);
					out.flush();
				} finally {
					Arrays.fill(bundleBytes, (byte) 0);
				}
				cb.onStatus(Status.DONE);
			} finally {
				sessionKey.clear();
			}
		} catch (TransferException e) {
			throw e;
		} catch (BackupException | IOException | RuntimeException e) {
			throw new TransferException(PROTOCOL);
		}
	}

	private void runReceive(Socket s, KeyPair myKp, byte[] myPub,
			byte[] theirPub, char[] newPassword, Callback cb)
			throws TransferException {
		try {
			s.setSoTimeout(READ_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(s.getInputStream());
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			cb.onStatus(Status.AUTHENTICATING);
			writeFrame(out, myPub);
			out.flush();
			SecretKey sessionKey = deriveSession(myKp, myPub, theirPub);
			try {
				String sas = ContactSafetyNumber.forKeys(myPub, theirPub);
				if (!cb.onSasConfirm(sas)) {
					throw new TransferException(CANCELLED);
				}
				int go = in.read();
				if (go != (GO & 0xFF)) throw new TransferException(CANCELLED);
				cb.onStatus(Status.IMPORTING);
				byte[] sealed = readFrame(in, 1, MAX_BUNDLE_BYTES);
				byte[] bundleBytes = vaultCrypto.decrypt(
						VaultCrypto.EncryptedData.fromBytes(sealed),
						sessionKey.getBytes(), AAD);
				try {
					backup.provisionFromBundle(bundleBytes, newPassword);
				} finally {
					Arrays.fill(bundleBytes, (byte) 0);
				}
				cb.onStatus(Status.DONE);
			} finally {
				sessionKey.clear();
			}
		} catch (TransferException e) {
			throw e;
		} catch (BackupException | IOException | RuntimeException e) {
			throw new TransferException(PROTOCOL);
		}
	}

	private SecretKey deriveSession(KeyPair myKp, byte[] myPub, byte[] theirPub)
			throws TransferException {
		try {
			PublicKey their = crypto.getAgreementKeyParser()
					.parsePublicKey(theirPub);
			byte[] first, second;
			if (compareBytes(myPub, theirPub) <= 0) {
				first = myPub;
				second = theirPub;
			} else {
				first = theirPub;
				second = myPub;
			}
			return crypto.deriveSharedSecret(SESSION_LABEL, their, myKp,
					first, second);
		} catch (GeneralSecurityException e) {
			throw new TransferException(PROTOCOL);
		}
	}

	private Socket dialWithRetry(String onion) throws TransferException {
		long deadline = System.currentTimeMillis() + CONNECT_DEADLINE_MS;
		while (System.currentTimeMillis() < deadline) {
			if (cancelled) throw new TransferException(CANCELLED);
			try {
				return torSocketFactory.createSocket(onion + ".onion",
						REMOTE_PORT);
			} catch (IOException e) {
				try {
					Thread.sleep(CONNECT_RETRY_MS);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new TransferException(CONNECT_FAILED);
				}
			}
		}
		throw new TransferException(CONNECT_FAILED);
	}

	private byte[] readFrame(DataInputStream in, int min, int max)
			throws IOException {
		int len = in.readInt();
		if (len < min || len > max) throw new IOException("bad frame length");
		byte[] b = new byte[len];
		in.readFully(b);
		return b;
	}

	private void writeFrame(DataOutputStream out, byte[] b) throws IOException {
		out.writeInt(b.length);
		out.write(b);
	}

	private static int compareBytes(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; i++) {
			int x = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (x != 0) return x;
		}
		return a.length - b.length;
	}

	private static void closeQuietly(@Nullable Closeable c) {
		if (c == null) return;
		try {
			c.close();
		} catch (IOException ignored) {
		}
	}
}
