package com.professor.zerion.android.backup;

import org.zerionproject.app.channel.OnionPublisher;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.util.Base32;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2-AND-03: the account leaves the old phone only for the peer whose
 * confirmation code the user typed, and the new phone reads the bundle
 * length only from an authenticated header, so neither the one-time address
 * nor a hostile endpoint is enough to receive the account or to make the
 * receiver allocate for an unauthenticated frame.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AccountTransferManagerTest {

	private CryptoComponent crypto;
	private final AtomicReference<Integer> publishedPort =
			new AtomicReference<>();
	private final AtomicReference<Integer> dialPort = new AtomicReference<>();

	private final OnionPublisher publisher = new OnionPublisher() {
		@Override
		public OnionHandle publish(int localPort, @Nullable String privateKey) {
			publishedPort.set(localPort);
			dialPort.compareAndSet(null, localPort);
			return new OnionHandle("fakeonionfakeonionfakeonion", "");
		}

		@Override
		public void unpublish(String onion) {
		}
	};

	private final SocketFactory loopback = new SocketFactory() {
		@Override
		public Socket createSocket(String host, int port) throws IOException {
			return new Socket(InetAddress.getLoopbackAddress(), dialPort.get());
		}

		@Override
		public Socket createSocket(String host, int port,
				InetAddress localHost, int localPort) throws IOException {
			return createSocket(host, port);
		}

		@Override
		public Socket createSocket(InetAddress host, int port)
				throws IOException {
			return createSocket("", port);
		}

		@Override
		public Socket createSocket(InetAddress address, int port,
				InetAddress localAddress, int localPort) throws IOException {
			return createSocket("", port);
		}
	};

	@Before
	public void setUp() {
		crypto = DaggerBackupCryptoTestComponent.create().getCryptoComponent();
	}

	private static byte[] bundle() {
		byte[] b = new byte[70_000];
		new Random(3).nextBytes(b);
		return b;
	}

	@Test(timeout = 60_000)
	public void theAccountReachesThePhoneWhoseCodeWasTyped()
			throws Exception {
		AccountBackupManager backup = mock(AccountBackupManager.class);
		byte[] bundle = bundle();
		when(backup.snapshotBundle()).thenReturn(bundle.clone());
		AtomicReference<byte[]> received = new AtomicReference<>();
		AtomicReference<char[]> receivedPassword = new AtomicReference<>();
		org.mockito.Mockito.doAnswer(inv -> {
			received.set(((byte[]) inv.getArgument(0)).clone());
			receivedPassword.set(((char[]) inv.getArgument(1)).clone());
			return null;
		}).when(backup).provisionFromBundle(any(), any());
		AccountTransferManager sender = new AccountTransferManager(crypto,
				publisher, loopback, backup);
		AccountTransferManager receiver = new AccountTransferManager(crypto,
				publisher, loopback, backup);
		CompletableFuture<String> qr = new CompletableFuture<>();
		CompletableFuture<String> shownCode = new CompletableFuture<>();
		AtomicReference<Throwable> senderFailure = new AtomicReference<>();
		Thread s = new Thread(() -> {
			try {
				sender.send(new AccountTransferManager.Callback() {
					@Override
					public void onStatus(AccountTransferManager.Status status) {
					}

					@Override
					public void onPairingReady(String qrPayload) {
						qr.complete(qrPayload);
					}

					@Override
					public void onShowConfirmationCode(String code) {
					}

					@Override
					public String onEnterConfirmationCode() {
						try {
							return shownCode.get(20, TimeUnit.SECONDS);
						} catch (Exception e) {
							return null;
						}
					}
				});
			} catch (Throwable t) {
				senderFailure.set(t);
			}
		});
		s.start();
		String payload = qr.get(20, TimeUnit.SECONDS);
		receiver.receive(payload, "new-password".toCharArray(),
				new AccountTransferManager.Callback() {
					@Override
					public void onStatus(AccountTransferManager.Status status) {
					}

					@Override
					public void onPairingReady(String qrPayload) {
					}

					@Override
					public void onShowConfirmationCode(String code) {
						assertEquals(AccountTransferManager.CODE_DIGITS,
								code.length());
						shownCode.complete(code);
					}

					@Override
					public String onEnterConfirmationCode() {
						return null;
					}
				});
		s.join(20_000);
		if (senderFailure.get() != null) throw new AssertionError(
				senderFailure.get());
		assertNotNull(received.get());
		assertArrayEquals(bundle, received.get());
		assertArrayEquals("new-password".toCharArray(), receivedPassword.get());
	}

	@Test(timeout = 60_000)
	public void aStrangerWhoReachesTheAddressFirstGetsNothing()
			throws Exception {
		AccountBackupManager backup = mock(AccountBackupManager.class);
		AccountTransferManager sender = new AccountTransferManager(crypto,
				publisher, loopback, backup);
		CompletableFuture<String> qr = new CompletableFuture<>();
		AtomicReference<Throwable> senderFailure = new AtomicReference<>();
		Thread s = new Thread(() -> {
			try {
				sender.send(new AccountTransferManager.Callback() {
					@Override
					public void onStatus(AccountTransferManager.Status status) {
					}

					@Override
					public void onPairingReady(String qrPayload) {
						qr.complete(qrPayload);
					}

					@Override
					public void onShowConfirmationCode(String code) {
					}

					@Override
					public String onEnterConfirmationCode() {
						return "000000";
					}
				});
				senderFailure.set(new AssertionError("sent to a stranger"));
			} catch (Throwable t) {
				senderFailure.set(t);
			}
		});
		s.start();
		qr.get(20, TimeUnit.SECONDS);
		byte[] strangerPub = new byte[32];
		new Random(9).nextBytes(strangerPub);
		try (Socket stranger = new Socket(InetAddress.getLoopbackAddress(),
				publishedPort.get())) {
			DataOutputStream out = new DataOutputStream(
					stranger.getOutputStream());
			out.writeInt(32);
			out.write(strangerPub);
			out.flush();
			DataInputStream in = new DataInputStream(stranger.getInputStream());
			assertEquals("the stranger is refused, never told GO", 0, in.read());
		}
		s.join(20_000);
		Throwable t = senderFailure.get();
		if (!(t instanceof TransferException)) throw new AssertionError(t);
		assertEquals(TransferException.Reason.CODE_MISMATCH,
				((TransferException) t).reason);
		verify(backup, never()).snapshotBundle();
	}

	@Test(timeout = 60_000)
	public void theReceiverRefusesAnUnauthenticatedLength() throws Exception {
		AccountBackupManager backup = mock(AccountBackupManager.class);
		byte[] hostilePub = new byte[32];
		new Random(11).nextBytes(hostilePub);
		try (ServerSocket hostile = new ServerSocket(0, 1,
				InetAddress.getLoopbackAddress())) {
			dialPort.set(hostile.getLocalPort());
			Thread h = new Thread(() -> {
				try (Socket c = hostile.accept()) {
					DataInputStream in = new DataInputStream(c.getInputStream());
					int len = in.readInt();
					byte[] theirPub = new byte[len];
					in.readFully(theirPub);
					DataOutputStream out = new DataOutputStream(
							c.getOutputStream());
					out.write(0x42);
					out.writeInt(64);
					byte[] junk = new byte[64];
					new Random(5).nextBytes(junk);
					out.write(junk);
					out.writeInt(512 * 1024 * 1024);
					out.flush();
					Thread.sleep(2_000);
				} catch (Exception ignored) {
				}
			});
			h.setDaemon(true);
			h.start();
			AccountTransferManager receiver = new AccountTransferManager(
					crypto, publisher, loopback, backup);
			String payload = AccountTransferManager.LINK_PREFIX
					+ Base32.encode(hostilePub) + ":hostile";
			try {
				receiver.receive(payload, "pw".toCharArray(),
						new AccountTransferManager.Callback() {
							@Override
							public void onStatus(
									AccountTransferManager.Status status) {
							}

							@Override
							public void onPairingReady(String qrPayload) {
							}

							@Override
							public void onShowConfirmationCode(String code) {
							}

							@Override
							public String onEnterConfirmationCode() {
								return null;
							}
						});
				fail();
			} catch (TransferException expected) {
				assertEquals(TransferException.Reason.PROTOCOL,
						expected.reason);
			}
		}
		verify(backup, never()).provisionFromBundle(any(), any());
	}

	@Test
	public void theCodeHasSixDigitsAndSpacesAreIgnored() {
		org.zerionproject.core.api.crypto.SecretKey k =
				new org.zerionproject.core.api.crypto.SecretKey(new byte[32]);
		String code = AccountTransferManager.confirmationCode(k);
		assertEquals(6, code.length());
		for (char c : code.toCharArray()) {
			if (c < '0' || c > '9') fail(code);
		}
		org.junit.Assert.assertTrue(AccountTransferManager.codesMatch(code,
				code.substring(0, 3) + " " + code.substring(3)));
		org.junit.Assert.assertFalse(AccountTransferManager.codesMatch(code,
				"000000".equals(code) ? "000001" : "000000"));
	}
}
