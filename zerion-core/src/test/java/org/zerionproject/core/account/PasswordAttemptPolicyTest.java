package org.zerionproject.core.account;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.test.TestDatabaseConfig;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_PASSWORD;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class PasswordAttemptPolicyTest {

	private static final String RIGHT = "right password 3#Mn";
	private static final String WRONG = "wrong password 3#Mn";

	private static final class SimulatedProcessDeath extends Error {
		SimulatedProcessDeath() {
			super("injected process death");
		}
	}

	private interface Attempt {
		void run() throws DecryptionException;
	}

	private final File testDir = getTestDirectory();
	private final DatabaseConfig config = new TestDatabaseConfig(testDir);
	private final AtomicBoolean dieInDerivation = new AtomicBoolean(false);
	private final AtomicLong clock = new AtomicLong(1_000_000L);
	private final CryptoComponent crypto = (CryptoComponent)
			Proxy.newProxyInstance(CryptoComponent.class.getClassLoader(),
					new Class<?>[] {CryptoComponent.class},
					(proxy, method, args) -> {
						switch (method.getName()) {
							case "encryptWithPassword":
								return seal((byte[]) args[0],
										(char[]) args[1]);
							case "decryptWithPassword":
								if (dieInDerivation.get()) {
									throw new SimulatedProcessDeath();
								}
								return open((byte[]) args[0],
										(char[]) args[1]);
							case "isEncryptedWithStrengthenedKey":
							case "isEncryptedWithLegacyKdf":
								return false;
							case "strengtheningGeneration":
								return -1;
							case "generateSecretKey":
								return new SecretKey(getRandomBytes(
										SecretKey.LENGTH));
							default:
								throw new UnsupportedOperationException(
										method.getName());
						}
					});

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private static byte[] tag(char[] password) {
		try {
			return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(
					new String(password).getBytes(StandardCharsets.UTF_8)), 8);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] seal(byte[] plaintext, char[] password) {
		byte[] t = tag(password);
		byte[] out = new byte[t.length + plaintext.length];
		System.arraycopy(t, 0, out, 0, t.length);
		System.arraycopy(plaintext, 0, out, t.length, plaintext.length);
		return out;
	}

	private static byte[] open(byte[] ciphertext, char[] password)
			throws DecryptionException {
		byte[] t = tag(password);
		if (!Arrays.equals(t, Arrays.copyOf(ciphertext, t.length))) {
			throw new DecryptionException(INVALID_PASSWORD);
		}
		return Arrays.copyOfRange(ciphertext, t.length, ciphertext.length);
	}

	private AccountManagerImpl process() {
		IdentityManager identities = (IdentityManager) Proxy.newProxyInstance(
				IdentityManager.class.getClassLoader(),
				new Class<?>[] {IdentityManager.class}, (p, m, a) -> null);
		return new AccountManagerImpl(config, crypto, identities) {
			@Override
			protected LoginThrottle createLoginThrottle(File stateFile) {
				return new LoginThrottle(LoginThrottle.fileStore(stateFile),
						clock::get, () -> "boot", LoginThrottle.SIGN_IN);
			}
		};
	}

	private AccountManagerImpl processErasingAfterSix() {
		AccountManagerImpl m = process();
		m.setErasePolicy(failures -> failures >= 6);
		return m;
	}

	private String attempt(Attempt a) {
		clock.addAndGet(86_400_001L);
		try {
			a.run();
			return "opens";
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		}
	}

	private File keyFile() {
		return new File(config.getDatabaseKeyDirectory(), "db.key");
	}

	private String afterwards(AccountManagerImpl m) {
		AccountManagerImpl restarted = process();
		return "erase " + (restarted.isEraseRequested() ? "recorded"
				: "not recorded") + ", key file "
				+ (keyFile().exists() ? "kept" : "gone") + ", "
				+ (m.hasDatabaseKey() ? "key loaded" : "no key loaded");
	}

	@Test
	public void anAttemptStoppedWhileThePasswordIsCheckedStaysCounted() {
		process().createAccount("me", RIGHT.toCharArray());
		dieInDerivation.set(true);
		String outcome;
		try {
			process().signIn(WRONG.toCharArray());
			outcome = "returned";
		} catch (SimulatedProcessDeath e) {
			outcome = "process died";
		} catch (DecryptionException e) {
			outcome = "refused";
		}
		dieInDerivation.set(false);
		assertEquals("process died, counted after restart 1",
				outcome + ", counted after restart "
						+ process().failedSignInAttempts());
	}

	@Test
	public void failuresAreForgottenOnlyByASuccessfulSignIn() {
		process().createAccount("me", RIGHT.toCharArray());
		AccountManagerImpl m = process();
		attempt(() -> m.signIn(WRONG.toCharArray()));
		attempt(() -> m.signIn(WRONG.toCharArray()));
		clock.addAndGet(30L * 86_400_000L);
		int afterAMonth = process().failedSignInAttempts();
		attempt(() -> m.signIn(RIGHT.toCharArray()));
		assertEquals("after a quiet month 2, after signing in 0",
				"after a quiet month " + afterAMonth + ", after signing in "
						+ process().failedSignInAttempts());
	}

	@Test
	public void eraseIsDecidedAtSignInBeforeTheRefusalIsReported() {
		process().createAccount("me", RIGHT.toCharArray());
		AccountManagerImpl m = processErasingAfterSix();
		for (int i = 0; i < 5; i++) {
			attempt(() -> m.signIn(WRONG.toCharArray()));
		}
		boolean afterFive = process().isEraseRequested();
		String sixth = attempt(() -> m.signIn(WRONG.toCharArray()));
		AccountManagerImpl restarted = process();
		assertEquals("after five not recorded, sixth INVALID_PASSWORD, erase"
						+ " recorded, key file gone, no key loaded, right"
						+ " password afterwards INVALID_CIPHERTEXT",
				"after five " + (afterFive ? "recorded" : "not recorded")
						+ ", sixth " + sixth + ", " + afterwards(m)
						+ ", right password afterwards "
						+ attempt(() -> restarted.signIn(RIGHT.toCharArray())));
	}

	@Test
	public void eraseIsDecidedAtAPasswordCheckWhileSignedIn()
			throws Exception {
		process().createAccount("me", RIGHT.toCharArray());
		AccountManagerImpl m = processErasingAfterSix();
		m.signIn(RIGHT.toCharArray());
		for (int i = 0; i < 6; i++) {
			attempt(() -> m.verifyPassword(WRONG.toCharArray()));
		}
		assertEquals("erase recorded, key file gone, no key loaded",
				afterwards(m));
	}

	@Test
	public void eraseIsDecidedForTheCurrentPasswordOfAChange()
			throws Exception {
		process().createAccount("me", RIGHT.toCharArray());
		AccountManagerImpl m = processErasingAfterSix();
		m.signIn(RIGHT.toCharArray());
		for (int i = 0; i < 6; i++) {
			attempt(() -> m.changePassword(WRONG.toCharArray(),
					"new password 7^Pq".toCharArray()));
		}
		assertEquals("erase recorded, key file gone, no key loaded",
				afterwards(m));
	}

	@Test
	public void fiveWrongPasswordsAndTheRightOneEraseNothing() {
		process().createAccount("me", RIGHT.toCharArray());
		AccountManagerImpl m = processErasingAfterSix();
		for (int i = 0; i < 5; i++) {
			attempt(() -> m.signIn(WRONG.toCharArray()));
		}
		String right = attempt(() -> m.signIn(RIGHT.toCharArray()));
		assertEquals("right password opens, erase not recorded, key file"
						+ " kept, key loaded",
				"right password " + right + ", " + afterwards(m));
	}

	@Test
	public void aKeyStoredUnderTheTextAsTypedOpensAndIsStoredAgain() {
		String typed = "pässword 9(Lk";
		String hex = toHexString(seal(getRandomBytes(SecretKey.LENGTH),
				typed.toCharArray()));
		AccountManagerImpl writer = process();
		synchronized (writer.stateChangeLock) {
			writer.storeEncryptedDatabaseKey(hex);
		}
		String asTyped = attempt(() -> process().signIn(typed.toCharArray()));
		String composed = attempt(() -> process().signIn(
				"pässword 9(Lk".toCharArray()));
		assertEquals("as typed opens, composed form opens",
				"as typed " + asTyped + ", composed form " + composed);
	}

	@Test
	public void theSameVisiblePasswordOpensHoweverTheKeyboardEnteredIt() {
		process().createAccount("me", "zero​width 5)Rs".toCharArray());
		assertEquals("opens", attempt(() -> process().signIn(
				"zerowidth 5)Rs".toCharArray())));
	}
}
