package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;
import org.zerionproject.core.contact.request.InboxClient.Outcome;
import org.zerionproject.core.contact.request.InboxClient.Result;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.data.BdfForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_WITHDRAW;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;

public class InboxProtocolTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;

	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final ContactRequestCrypto c = new ContactRequestCrypto(crypto);
	private final SettableClock clock = new SettableClock(NOW);
	private final KeyPair link = crypto.generateHybridAgreementKeyPair();
	private final byte[] linkId =
			crypto.hash(HYBRID_COMMITMENT_LABEL, link.getPublic().getEncoded());
	private final byte[] onion = getRandomBytes(32);
	private final InboxPuzzle puzzle =
			new InboxPuzzle(c, getRandomBytes(32), linkId);
	private final InboxSlots slots = new InboxSlots();
	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final InboxSession.Receiver receiver = new InboxSession.Receiver() {
		@Override
		public boolean isFull() {
			return false;
		}

		@Override
		public byte[] handle(RequestPlaintext p, SecretKey mk, byte[] nonce,
				long receivedAt, long admittedAt) {
			return c.ack(mk, p.requestId, p.kind);
		}
	};

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	private Outcome exchange(@Nullable InboxClient.Puzzle solved)
			throws Exception {
		try (ServerSocket server = new ServerSocket(0, 5,
				InetAddress.getByName("127.0.0.1"))) {
			Future<?> serving = exec.submit(() -> {
				try {
					Socket s = server.accept();
					new InboxSession(c, BdfForTests.readers(), clock,
							(task, delay) -> () -> {
							}, new InboxSession.Inbox(linkId, onion, link,
							puzzle, slots), receiver)
							.run(InboxService.connection(s));
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			});
			Outcome o;
			try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"),
					server.getLocalPort())) {
				s.setSoTimeout(20_000);
				byte[] x25519 = Arrays.copyOf(link.getPublic().getEncoded(),
						32);
				byte[] requestId = getRandomBytes(16);
				o = new InboxClient(c, new Random()).run(
						s.getInputStream(), s.getOutputStream(),
						new InboxClient.Target(linkId, x25519, onion), solved,
						new InboxClient.Payload() {
							@Override
							public byte[] plaintext(byte[] helloNonce) {
								return RequestPlaintext.withdraw(requestId,
										linkId).encode(BdfForTests.writers());
							}

							@Override
							public byte[] requestId() {
								return requestId;
							}

							@Override
							public int kind() {
								return KIND_WITHDRAW;
							}
						});
			}
			serving.get(20, TimeUnit.SECONDS);
			return o;
		}
	}

	private void raise() {
		for (int i = 0; i < 10; i++) {
			puzzle.admit(new Solution(NOW / 600_000, 3, getRandomBytes(8)),
					NOW);
		}
	}

	@Test
	public void levelZeroIsAnsweredInTheSameConnection() throws Exception {
		assertEquals(Result.ACKED, exchange(null).result);
	}

	@Test
	public void aRaisedLevelAsksForASolutionAndAcceptsItPipelined()
			throws Exception {
		raise();
		Outcome first = exchange(null);
		assertEquals(Result.NEED_SOLUTION, first.result);
		byte[] x = c.solve(first.hello.nonce, linkId, first.hello.difficulty,
				new Random(), () -> false);
		InboxClient.Puzzle solved = new InboxClient.Puzzle(new Solution(
				first.hello.epoch, first.hello.level, x), first.hello.nonce,
				first.hello.difficulty, NOW);
		assertEquals(Result.ACKED, exchange(solved).result);
		assertEquals("an admitted solution is spent", Result.REFUSED,
				exchange(solved).result);
	}

	@Test
	public void aDifferentNonceForTheSameEpochAndLevelIsARestart()
			throws Exception {
		raise();
		Outcome first = exchange(null);
		InboxClient.Puzzle stale = new InboxClient.Puzzle(new Solution(
				first.hello.epoch, first.hello.level, getRandomBytes(8)),
				getRandomBytes(32), first.hello.difficulty, NOW);
		assertEquals(Result.RESTART, exchange(stale).result);
	}

	private Outcome againstHello(ContactRequestRecords.Hello hello,
			@Nullable InboxClient.Puzzle solved) throws Exception {
		try (ServerSocket server = new ServerSocket(0, 5,
				InetAddress.getByName("127.0.0.1"))) {
			Future<?> serving = exec.submit(() -> {
				try (Socket s = server.accept()) {
					ContactRequestRecords.write(s.getOutputStream(),
							1,
							hello.encode());
					InputStream in = s.getInputStream();
					s.setSoTimeout(5_000);
					try {
						while (in.read() != -1) {
						}
					} catch (IOException ignored) {
					}
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			});
			Outcome o;
			try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"),
					server.getLocalPort())) {
				s.setSoTimeout(20_000);
				o = new InboxClient(c, new Random()).run(s.getInputStream(),
						s.getOutputStream(), new InboxClient.Target(linkId,
								Arrays.copyOf(link.getPublic().getEncoded(), 32),
								onion), solved, new InboxClient.Payload() {
							@Override
							public byte[] plaintext(byte[] helloNonce) {
								throw new AssertionError();
							}

							@Override
							public byte[] requestId() {
								return new byte[16];
							}

							@Override
							public int kind() {
								return KIND_WITHDRAW;
							}
						});
			}
			serving.get(20, TimeUnit.SECONDS);
			return o;
		}
	}

	@Test
	public void aRestartAskingForUnboundedWorkIsRefused() throws Exception {
		long e = NOW / 600_000;
		InboxClient.Puzzle held = new InboxClient.Puzzle(
				new Solution(e, 1, getRandomBytes(8)), getRandomBytes(32), 16,
				NOW);
		ContactRequestRecords.Hello greedy = new ContactRequestRecords.Hello(
				1, 1, 255, e, getRandomBytes(32));
		assertEquals(Result.TOO_HARD, againstHello(greedy, held).result);
		assertEquals(Result.TOO_HARD, againstHello(greedy, null).result);
	}

	@Test
	public void aLevelAndDifficultyThatDoNotMatchAreRefused()
			throws Exception {
		long e = NOW / 600_000;
		int[][] bad = {{1, 8}, {0, 16}, {4, 24}, {3, 20}, {255, 0}};
		for (int[] kd : bad) {
			assertEquals(kd[0] + "/" + kd[1], Result.TOO_HARD, againstHello(
					new ContactRequestRecords.Hello(1, kd[0], kd[1], e,
							getRandomBytes(32)), null).result);
		}
		assertEquals(Result.NEED_SOLUTION, againstHello(
				new ContactRequestRecords.Hello(1, 3, 24, e,
						getRandomBytes(32)), null).result);
	}

	@Test
	public void theSolverGivesUpAboveTheCapAndAfterItsIterationBound() {
		assertEquals(1L << 30, ContactRequestCrypto.SOLVE_ITERATION_LIMIT);
		assertEquals(null, c.solve(getRandomBytes(32), linkId, 25,
				new Random(), () -> false));
		assertEquals(null, c.solve(getRandomBytes(32), linkId, 255,
				new Random(), () -> false));
	}

	@Test
	public void aFirstRecordThatIsNotASolutionCountsAsZeroWork()
			throws Exception {
		for (int i = 0; i < 31; i++) {
			try (ServerSocket server = new ServerSocket(0, 5,
					InetAddress.getByName("127.0.0.1"))) {
				Future<?> serving = exec.submit(() -> {
					try {
						Socket s = server.accept();
						new InboxSession(c, BdfForTests.readers(), clock,
								(task, delay) -> () -> {
								}, new InboxSession.Inbox(linkId, onion,
								link, puzzle, slots), receiver)
								.run(InboxService.connection(s));
					} catch (IOException e) {
						throw new AssertionError(e);
					}
				});
				try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"),
						server.getLocalPort())) {
					OutputStream out = s.getOutputStream();
					out.write(new byte[] {1, 6, 0, 0, 0, 32});
					out.flush();
					InputStream in = s.getInputStream();
					while (in.read() != -1) {
					}
				}
				serving.get(20, TimeUnit.SECONDS);
			}
		}
		assertEquals(1, puzzle.getLevel(NOW));
	}
}
