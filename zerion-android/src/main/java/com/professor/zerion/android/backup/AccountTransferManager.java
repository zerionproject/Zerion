package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.util.Base32;
import org.zerionproject.app.channel.OnionPublisher;
import org.zerionproject.app.channel.OnionPublisher.OnionHandle;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.net.SocketFactory;

import static com.professor.zerion.android.backup.TransferException.Reason.CANCELLED;
import static com.professor.zerion.android.backup.TransferException.Reason.CODE_MISMATCH;
import static com.professor.zerion.android.backup.TransferException.Reason.CONNECT_FAILED;
import static com.professor.zerion.android.backup.TransferException.Reason.IO_ERROR;
import static com.professor.zerion.android.backup.TransferException.Reason.PROTOCOL;

@NotNullByDefault
public class AccountTransferManager {

	public static final String LINK_PREFIX = "zerion-transfer://";

	private static final int PUBKEY_LEN = 32;
	private static final int MAX_BUNDLE_BYTES = 512 * 1024 * 1024;
	private static final int MAX_LENGTH_HEADER_BYTES = 256;
	private static final byte[] AAD =
			"Zerion-Account-Transfer-v2".getBytes(StandardCharsets.UTF_8);
	private static final byte[] AAD_LENGTH =
			"Zerion-Account-Transfer-v2-length".getBytes(StandardCharsets.UTF_8);
	private static final byte[] CODE_LABEL =
			"Zerion-Account-Transfer-v2-code".getBytes(StandardCharsets.UTF_8);
	private static final byte[] AAD_IMPORTED =
			"Zerion-Account-Transfer-v2-imported"
					.getBytes(StandardCharsets.UTF_8);
	private static final int MAX_CONFIRMATION_BYTES = 256;
	static final int IMPORT_CONFIRM_TIMEOUT_MS = 15 * 60 * 1000;
	public static final int CODE_DIGITS = 6;
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

	public enum SendResult {
		MOVED,
		SENT_UNCONFIRMED
	}

	public interface Callback {
		void onStatus(Status status);

		void onPairingReady(String qrPayload);

		void onShowConfirmationCode(String code);

		@Nullable
		String onEnterConfirmationCode();
	}

	private final CryptoComponent crypto;
	private final OnionPublisher onionPublisher;
	private final SocketFactory torSocketFactory;
	private final AccountBackupManager backup;
	private final AccountRetirement retirement;
	private final VaultCrypto vaultCrypto = new VaultCrypto();

	@Nullable
	private volatile OnionTargetListener currentServer;
	@Nullable
	private volatile Socket currentSocket;
	private volatile boolean cancelled;

	@Inject
	AccountTransferManager(CryptoComponent crypto,
			OnionPublisher onionPublisher, SocketFactory torSocketFactory,
			AccountBackupManager backup, AccountRetirement retirement) {
		this.crypto = crypto;
		this.onionPublisher = onionPublisher;
		this.torSocketFactory = torSocketFactory;
		this.backup = backup;
		this.retirement = retirement;
	}

	public void cancel() {
		cancelled = true;
		closeQuietly(currentServer);
		closeQuietly(currentSocket);
	}

	public SendResult send(Callback cb) throws TransferException {
		cancelled = false;
		cb.onStatus(Status.PUBLISHING);
		KeyPair myKp = crypto.generateAgreementKeyPair();
		byte[] myPub = myKp.getPublic().getEncoded();
		OnionTargetListener ss = null;
		OnionHandle handle = null;
		Thread acceptDeadline = null;
		try {
			ss = onionPublisher.openTarget();
			currentServer = ss;
			handle = onionPublisher.publish(ss.getTorTarget(), null);
			cb.onPairingReady(LINK_PREFIX + Base32.encode(myPub) + ":"
					+ handle.getOnion());
			cb.onStatus(Status.WAITING_FOR_PEER);
			acceptDeadline = closeAfter(ss, ACCEPT_TIMEOUT_MS);
			Socket client = ss.accept();
			acceptDeadline.interrupt();
			currentSocket = client;
			try {
				return runSend(client, myKp, myPub, cb);
			} finally {
				closeQuietly(client);
			}
		} catch (IOException e) {
			throw new TransferException(IO_ERROR);
		} finally {
			if (acceptDeadline != null) acceptDeadline.interrupt();
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

	private static Thread closeAfter(OnionTargetListener listener,
			long timeoutMs) {
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(timeoutMs);
			} catch (InterruptedException e) {
				return;
			}
			closeQuietly(listener);
		}, "TransferAcceptDeadline");
		t.setDaemon(true);
		t.start();
		return t;
	}

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

	private SendResult runSend(Socket s, KeyPair myKp, byte[] myPub,
			Callback cb) throws TransferException {
		try {
			s.setSoTimeout(READ_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(s.getInputStream());
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			cb.onStatus(Status.AUTHENTICATING);
			byte[] theirPub = readFrame(in, PUBKEY_LEN, PUBKEY_LEN);
			SecretKey sessionKey = deriveSession(myKp, myPub, theirPub);
			try {
				String expected = confirmationCode(sessionKey);
				String typed = cb.onEnterConfirmationCode();
				if (typed == null) {
					out.write(0);
					out.flush();
					throw new TransferException(CANCELLED);
				}
				if (!codesMatch(expected, typed)) {
					out.write(0);
					out.flush();
					throw new TransferException(CODE_MISMATCH);
				}
				out.write(GO);
				out.flush();
				cb.onStatus(Status.TRANSFERRING);
				byte[] bundleBytes = backup.snapshotBundle();
				byte[] digest;
				try {
					byte[] sealed = vaultCrypto.encrypt(bundleBytes,
							sessionKey.getBytes(), AAD).toBytes();
					digest = sha256(sealed);
					writeFrame(out, vaultCrypto.encrypt(int32(sealed.length),
							sessionKey.getBytes(), AAD_LENGTH).toBytes());
					writeFrame(out, sealed);
					out.flush();
				} finally {
					Arrays.fill(bundleBytes, (byte) 0);
				}
				SendResult result = SendResult.SENT_UNCONFIRMED;
				cb.onStatus(Status.IMPORTING);
				if (importConfirmed(s, in, sessionKey, digest)) {
					try {
						retirement.retire();
						result = SendResult.MOVED;
					} catch (DbException e) {
						result = SendResult.SENT_UNCONFIRMED;
					}
				}
				cb.onStatus(Status.DONE);
				return result;
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
				cb.onShowConfirmationCode(confirmationCode(sessionKey));
				int go = in.read();
				if (go != (GO & 0xFF)) throw new TransferException(CANCELLED);
				cb.onStatus(Status.IMPORTING);
				byte[] lengthHeader = readFrame(in, 1, MAX_LENGTH_HEADER_BYTES);
				int length = int32(vaultCrypto.decrypt(
						VaultCrypto.EncryptedData.fromBytes(lengthHeader),
						sessionKey.getBytes(), AAD_LENGTH));
				if (length < 1 || length > MAX_BUNDLE_BYTES) {
					throw new TransferException(PROTOCOL);
				}
				byte[] sealed = readFrame(in, length, length);
				byte[] bundleBytes = vaultCrypto.decrypt(
						VaultCrypto.EncryptedData.fromBytes(sealed),
						sessionKey.getBytes(), AAD);
				try {
					backup.provisionFromBundle(bundleBytes, newPassword);
				} finally {
					Arrays.fill(bundleBytes, (byte) 0);
				}
				confirmImport(out, sessionKey, sha256(sealed));
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

	private boolean importConfirmed(Socket s, DataInputStream in,
			SecretKey sessionKey, byte[] digest) {
		try {
			s.setSoTimeout(IMPORT_CONFIRM_TIMEOUT_MS);
			byte[] frame = readFrame(in, 1, MAX_CONFIRMATION_BYTES);
			byte[] body = vaultCrypto.decrypt(
					VaultCrypto.EncryptedData.fromBytes(frame),
					sessionKey.getBytes(), AAD_IMPORTED);
			return java.security.MessageDigest.isEqual(body, digest);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private void confirmImport(DataOutputStream out, SecretKey sessionKey,
			byte[] digest) {
		try {
			writeFrame(out, vaultCrypto.encrypt(digest, sessionKey.getBytes(),
					AAD_IMPORTED).toBytes());
			out.flush();
		} catch (IOException | RuntimeException ignored) {
		}
	}

	private static byte[] sha256(byte[] data) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256")
					.digest(data);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String confirmationCode(SecretKey sessionKey) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(sessionKey.getBytes(),
					"HmacSHA256"));
			byte[] h = mac.doFinal(CODE_LABEL);
			long v = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16)
					| ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
			String digits = Long.toString(v % 1_000_000L);
			StringBuilder sb = new StringBuilder(CODE_DIGITS);
			for (int i = digits.length(); i < CODE_DIGITS; i++) sb.append('0');
			return sb.append(digits).toString();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean codesMatch(String expected, String typed) {
		StringBuilder sb = new StringBuilder(CODE_DIGITS);
		for (int i = 0; i < typed.length(); i++) {
			char c = typed.charAt(i);
			if (c >= '0' && c <= '9') sb.append(c);
		}
		byte[] a = expected.getBytes(StandardCharsets.US_ASCII);
		byte[] b = sb.toString().getBytes(StandardCharsets.US_ASCII);
		return java.security.MessageDigest.isEqual(a, b);
	}

	private static byte[] int32(int v) {
		return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16),
				(byte) (v >>> 8), (byte) v};
	}

	private static int int32(byte[] b) throws TransferException {
		if (b.length != 4) throw new TransferException(PROTOCOL);
		return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
				| ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
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
