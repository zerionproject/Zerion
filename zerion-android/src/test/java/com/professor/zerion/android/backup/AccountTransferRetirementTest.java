package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.channel.OnionPublisher;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.util.Base32;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AccountTransferRetirementTest {

	private static final String SESSION_LABEL =
			"com.professor.zerion.transfer/sessionKey";
	private static final byte[] AAD =
			"Zerion-Account-Transfer-v2".getBytes(StandardCharsets.UTF_8);
	private static final byte[] AAD_LENGTH =
			"Zerion-Account-Transfer-v2-length"
					.getBytes(StandardCharsets.UTF_8);

	private CryptoComponent crypto;
	private final AccountRetirement senderRetirement =
			mock(AccountRetirement.class);
	private final AccountRetirement receiverRetirement =
			mock(AccountRetirement.class);
	private final AtomicReference<Integer> port = new AtomicReference<>();

	private final OnionPublisher publisher = new OnionPublisher() {
		@Override
		public OnionHandle publish(int localPort, @Nullable String privateKey) {
			port.set(localPort);
			return new OnionHandle("fakeonionfakeonionfakeonion", "");
		}

		@Override
		public void unpublish(String onion) {
		}
	};

	private final SocketFactory loopback = new SocketFactory() {
		@Override
		public Socket createSocket(String host, int p) throws IOException {
			return new Socket(InetAddress.getLoopbackAddress(), port.get());
		}

		@Override
		public Socket createSocket(String host, int p, InetAddress l,
				int lp) throws IOException {
			return createSocket(host, p);
		}

		@Override
		public Socket createSocket(InetAddress host, int p)
				throws IOException {
			return createSocket("", p);
		}

		@Override
		public Socket createSocket(InetAddress a, int p, InetAddress l,
				int lp) throws IOException {
			return createSocket("", p);
		}
	};

	@Before
	public void setUp() {
		crypto = DaggerBackupCryptoTestComponent.create().getCryptoComponent();
	}

	private static byte[] bundle() {
		byte[] b = new byte[50_000];
		new Random(4).nextBytes(b);
		return b;
	}

	private static class Answers implements AccountTransferManager.Callback {
		final CompletableFuture<String> qr = new CompletableFuture<>();
		final CompletableFuture<String> code = new CompletableFuture<>();
		@Nullable
		private final CompletableFuture<String> typed;

		Answers(@Nullable CompletableFuture<String> typed) {
			this.typed = typed;
		}

		@Override
		public void onStatus(AccountTransferManager.Status status) {
		}

		@Override
		public void onPairingReady(String qrPayload) {
			qr.complete(qrPayload);
		}

		@Override
		public void onShowConfirmationCode(String c) {
			code.complete(c);
		}

		@Override
		@Nullable
		public String onEnterConfirmationCode() {
			if (typed == null) return null;
			try {
				return typed.get(20, TimeUnit.SECONDS);
			} catch (Exception e) {
				return null;
			}
		}
	}

	@Test(timeout = 60_000)
	public void theOldPhoneStopsUsingTheAccountOnceTheNewPhoneConfirms()
			throws Exception {
		AccountBackupManager backup = mock(AccountBackupManager.class);
		when(backup.snapshotBundle()).thenReturn(bundle());
		AccountTransferManager sender = new AccountTransferManager(crypto,
				publisher, loopback, backup, senderRetirement);
		AccountTransferManager receiver = new AccountTransferManager(crypto,
				publisher, loopback, backup, receiverRetirement);
		Answers receiving = new Answers(null);
		Answers sending = new Answers(receiving.code);
		AtomicReference<Object> result = new AtomicReference<>();
		Thread s = new Thread(() -> {
			try {
				result.set(sender.send(sending));
			} catch (Throwable t) {
				result.set(t);
			}
		});
		s.start();
		receiver.receive(sending.qr.get(20, TimeUnit.SECONDS),
				"new-password".toCharArray(), receiving);
		s.join(20_000);
		assertEquals(AccountTransferManager.SendResult.MOVED, result.get());
		verify(senderRetirement, times(1)).retire();
		verify(receiverRetirement, never()).retire();
		verify(backup, times(1)).provisionFromBundle(any(), any());
	}

	@Test(timeout = 60_000)
	public void aNewPhoneThatConfirmsNothingLeavesTheOldPhoneActive()
			throws Exception {
		AccountBackupManager backup = mock(AccountBackupManager.class);
		when(backup.snapshotBundle()).thenReturn(bundle());
		AccountTransferManager sender = new AccountTransferManager(crypto,
				publisher, loopback, backup, senderRetirement);
		CompletableFuture<String> typed = new CompletableFuture<>();
		Answers sending = new Answers(typed);
		AtomicReference<Object> result = new AtomicReference<>();
		Thread s = new Thread(() -> {
			try {
				result.set(sender.send(sending));
			} catch (Throwable t) {
				result.set(t);
			}
		});
		s.start();
		String qr = sending.qr.get(20, TimeUnit.SECONDS);
		byte[] senderPub = Base32.decode(qr.substring(
				AccountTransferManager.LINK_PREFIX.length(),
				qr.lastIndexOf(':')), false);
		KeyPair mine = crypto.generateAgreementKeyPair();
		byte[] myPub = mine.getPublic().getEncoded();
		try (Socket earlier = new Socket(InetAddress.getLoopbackAddress(),
				port.get())) {
			DataOutputStream out =
					new DataOutputStream(earlier.getOutputStream());
			out.writeInt(myPub.length);
			out.write(myPub);
			out.flush();
			typed.complete(AccountTransferManager.confirmationCode(
					session(mine, myPub, senderPub)));
			DataInputStream in = new DataInputStream(earlier.getInputStream());
			assertEquals(0x42, in.read());
			byte[] header = new byte[in.readInt()];
			in.readFully(header);
			byte[] sealed = new byte[in.readInt()];
			in.readFully(sealed);
		}
		s.join(20_000);
		assertEquals(AccountTransferManager.SendResult.SENT_UNCONFIRMED,
				result.get());
		verify(senderRetirement, never()).retire();
	}

	@Test(timeout = 60_000)
	public void aNewPhoneStillImportsFromAnOldPhoneOfAnEarlierVersion()
			throws Exception {
		AccountBackupManager backup = mock(AccountBackupManager.class);
		byte[] bundle = bundle();
		KeyPair oldPhone = crypto.generateAgreementKeyPair();
		byte[] oldPub = oldPhone.getPublic().getEncoded();
		try (ServerSocket server = new ServerSocket(0, 1,
				InetAddress.getLoopbackAddress())) {
			port.set(server.getLocalPort());
			Thread earlierSender = new Thread(() -> {
				try (Socket c = server.accept()) {
					DataInputStream in = new DataInputStream(
							c.getInputStream());
					byte[] theirPub = new byte[in.readInt()];
					in.readFully(theirPub);
					SecretKey key = session(oldPhone, oldPub, theirPub);
					VaultCrypto vc = new VaultCrypto();
					byte[] sealed = vc.encrypt(bundle, key.getBytes(), AAD)
							.toBytes();
					byte[] header = vc.encrypt(new byte[] {
							(byte) (sealed.length >>> 24),
							(byte) (sealed.length >>> 16),
							(byte) (sealed.length >>> 8),
							(byte) sealed.length}, key.getBytes(),
							AAD_LENGTH).toBytes();
					DataOutputStream out = new DataOutputStream(
							c.getOutputStream());
					out.write(0x42);
					out.writeInt(header.length);
					out.write(header);
					out.writeInt(sealed.length);
					out.write(sealed);
					out.flush();
				} catch (Exception ignored) {
				}
			});
			earlierSender.start();
			AccountTransferManager receiver = new AccountTransferManager(
					crypto, publisher, loopback, backup, receiverRetirement);
			receiver.receive(AccountTransferManager.LINK_PREFIX
							+ Base32.encode(oldPub) + ":oldphone",
					"pw".toCharArray(), new Answers(null));
			earlierSender.join(10_000);
		}
		verify(backup, times(1)).provisionFromBundle(any(), any());
	}

	private SecretKey session(KeyPair mine, byte[] myPub, byte[] theirPub)
			throws Exception {
		PublicKey their =
				crypto.getAgreementKeyParser().parsePublicKey(theirPub);
		byte[] first = myPub;
		byte[] second = theirPub;
		for (int i = 0; i < Math.min(myPub.length, theirPub.length); i++) {
			int x = (myPub[i] & 0xFF) - (theirPub[i] & 0xFF);
			if (x != 0) {
				if (x > 0) {
					first = theirPub;
					second = myPub;
				}
				break;
			}
		}
		return crypto.deriveSharedSecret(SESSION_LABEL, their, mine, first,
				second);
	}
}
