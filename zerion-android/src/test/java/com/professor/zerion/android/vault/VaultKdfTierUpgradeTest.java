package com.professor.zerion.android.vault;

import android.app.Application;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultKdfTier;
import com.professor.zerion.android.vault.model.VaultHeader;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.storage.VaultLocation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class VaultKdfTierUpgradeTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final char[] PASSWORD = "vault password 4&Rt".toCharArray();
	private static final char[] NEW_PASSWORD =
			"other vault password 6*Ux".toCharArray();
	private static final char[] WRONG = "not the password 1!Qq".toCharArray();
	private static final Argon2.Argon2Params A = VaultKdfTier.TIER_A;
	private static final Argon2.Argon2Params B = VaultKdfTier.TIER_B;
	private static final Argon2.Argon2Params C = VaultKdfTier.TIER_C;

	private final Application app = RuntimeEnvironment.getApplication();
	private final List<String> derivations = new ArrayList<>();
	private final Set<Integer> failingMemoryKb = new HashSet<>();
	private int killedAtMemoryKb = 0;
	private int allowedA = Integer.MAX_VALUE;
	private long version = 100;
	private long now = 1_800_000_000_000L;
	@javax.annotation.Nullable
	private java.util.concurrent.CountDownLatch derivationStarted = null;
	@javax.annotation.Nullable
	private java.util.concurrent.CountDownLatch releaseDerivation = null;
	private boolean nativeGone = false;
	private File dir;
	private String name = "main";

	private final VaultManager.PasswordKdf fake = (password, salt, params) -> {
		derivations.add(label(params));
		check(params);
		return fakeDerive(password, salt, params);
	};

	private final VaultManager.PasswordKdf real = (password, salt, params) -> {
		derivations.add(label(params));
		check(params);
		return new Argon2().deriveKey(password, salt, new Argon2.Argon2Params(
				Math.max(Argon2.MIN_MEMORY_KB, params.memoryKb / 256),
				params.iterations, params.parallelism, 32));
	};

	private void check(Argon2.Argon2Params params) {
		java.util.concurrent.CountDownLatch started = derivationStarted;
		java.util.concurrent.CountDownLatch release = releaseDerivation;
		if (started != null && release != null) {
			started.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
		}
		if (params.memoryKb == A.memoryKb && allowedA-- <= 0) {
			throw new OutOfMemoryError("injected");
		}
		if (nativeGone
				&& params.memoryKb > Argon2.JAVA_FALLBACK_MAX_MEMORY_KB) {
			throw new Argon2.NativeRequiredException();
		}
		if (params.memoryKb == killedAtMemoryKb) {
			try {
				File snapshot = new File(dir, "killed-" + name);
				deleteFileOrDir(snapshot);
				copyTree(vaultDir(), snapshot);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			throw new AssertionError("process killed");
		}
		if (failingMemoryKb.contains(params.memoryKb)) {
			throw new OutOfMemoryError("injected");
		}
	}

	private static String label(Argon2.Argon2Params p) {
		return p.memoryKb / 1024 + " MiB t=" + p.iterations;
	}

	private static byte[] fakeDerive(char[] password, byte[] salt,
			Argon2.Argon2Params params) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(salt, "HmacSHA256"));
			mac.update(new String(password).getBytes(StandardCharsets.UTF_8));
			return mac.doFinal(params.toBytes());
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	@Before
	public void setUp() throws Exception {
		dir = Files.createTempDirectory("vault-kdf-tier").toFile();
		clearKeyStore();
	}

	@After
	public void tearDown() throws Exception {
		clearKeyStore();
		deleteFileOrDir(dir);
	}

	private static void clearKeyStore() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		for (String alias : Collections.list(ks.aliases())) {
			ks.deleteEntry(alias);
		}
	}

	private static List<String> aliases() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		List<String> out = new ArrayList<>(Collections.list(ks.aliases()));
		Collections.sort(out);
		return out;
	}

	private File vaultDir() {
		return new File(dir, "vault-" + name);
	}

	private VaultLocation location() {
		File vaultDir = vaultDir();
		String alias = "zerion_vault_master_key_tier_" + name;
		return new VaultLocation() {
			@Override
			public boolean isAvailable() {
				return true;
			}

			@Override
			public File directory() {
				return vaultDir;
			}

			@Override
			public String keyAlias() {
				return alias;
			}

			@Override
			public List<File> allVaultDirectories() {
				return Collections.singletonList(vaultDir);
			}

			@Override
			public void onUnlocked() {
			}
		};
	}

	private VaultManager phone(Argon2.Argon2Params tier) {
		return sources(new VaultManager(app, location(), fake, () -> tier));
	}

	private VaultManager realPhone(Argon2.Argon2Params tier) {
		return sources(new VaultManager(app, location(), real, () -> tier));
	}

	private VaultManager sources(VaultManager m) {
		long v = version;
		long t = now;
		m.setKdfCapSources(() -> v, () -> t);
		return m;
	}

	private void restoreAfterKill() throws Exception {
		killedAtMemoryKb = 0;
		deleteFileOrDir(vaultDir());
		copyTree(new File(dir, "killed-" + name), vaultDir());
	}

	private void killUnlockAt(Argon2.Argon2Params tier, int memoryKb)
			throws Exception {
		killedAtMemoryKb = memoryKb;
		try {
			phone(tier).unlockVault(PASSWORD.clone());
			fail("the injected kill must stop the unlock");
		} catch (AssertionError expected) {
			assertEquals("process killed", expected.getMessage());
		}
		restoreAfterKill();
	}

	private File headerFile() {
		return new File(vaultDir(), "vault.header");
	}

	private VaultHeader header() throws Exception {
		return VaultHeader.fromBytes(Files.readAllBytes(headerFile().toPath()));
	}

	private byte[] headerBytes() throws Exception {
		return Files.readAllBytes(headerFile().toPath());
	}

	private String headerLabel() throws Exception {
		VaultHeader h = header();
		return h.kdfMemoryKb / 1024 + " MiB t=" + h.kdfIterations;
	}

	private static String opens(VaultManager m, char[] password)
			throws Exception {
		return m.unlockVault(password.clone()) ? "opens" : "refused";
	}

	private void createWithNote(Argon2.Argon2Params tier) throws Exception {
		VaultManager m = phone(tier);
		m.createVault(PASSWORD.clone());
		m.addItem(VaultItem.ItemType.NOTE, "note",
				"kept".getBytes(StandardCharsets.UTF_8));
		m.lockVault();
	}

	private static String note(VaultManager m) throws Exception {
		return new String(m.getItemContent(m.listItems().get(0).id),
				StandardCharsets.UTF_8);
	}

	@Test
	public void aNewVaultUsesThePhonesTier() throws Exception {
		StringBuilder out = new StringBuilder();
		Argon2.Argon2Params[] tiers = {A, B, C};
		String[] names = {"a", "b", "c"};
		for (int i = 0; i < tiers.length; i++) {
			name = names[i];
			phone(tiers[i]).createVault(PASSWORD.clone());
			derivations.clear();
			String result = opens(phone(tiers[i]), PASSWORD);
			out.append(names[i]).append(": ").append(headerLabel())
					.append(' ').append(result).append(' ')
					.append(derivations).append("; ");
		}
		assertEquals("a: 1024 MiB t=1 opens [1024 MiB t=1]; "
						+ "b: 512 MiB t=2 opens [512 MiB t=2]; "
						+ "c: 256 MiB t=3 opens [256 MiB t=3]; ",
				out.toString());
	}

	private String upgradeFromStrongTo(Argon2.Argon2Params tier)
			throws Exception {
		createWithNote(C);
		int slotBefore = header().keySlot;
		derivations.clear();
		VaultManager first = phone(tier);
		String firstUnlock = opens(first, PASSWORD);
		List<String> firstDerivations = new ArrayList<>(derivations);
		String firstNote = note(first);
		boolean slotMoved = header().keySlot != slotBefore;
		int keys = aliases().size();

		derivations.clear();
		VaultManager next = phone(tier);
		String nextUnlock = opens(next, PASSWORD);
		List<String> nextDerivations = new ArrayList<>(derivations);
		String nextNote = note(next);
		return "first " + firstUnlock + " " + firstDerivations + ", header "
				+ headerLabel() + ", slot " + (slotMoved ? "moved" : "same")
				+ ", keys " + keys + ", note " + firstNote + "; next "
				+ nextUnlock + " " + nextDerivations + ", note " + nextNote
				+ ", wrong password " + opens(phone(tier), WRONG);
	}

	@Test
	public void aStrongVaultMovesToTierBOnItsNextUnlock() throws Exception {
		assertEquals("first opens [256 MiB t=3, 512 MiB t=2], header 512 MiB"
						+ " t=2, slot moved, keys 2, note kept; next opens"
						+ " [512 MiB t=2], note kept, wrong password refused",
				upgradeFromStrongTo(B));
	}

	@Test
	public void aStrongVaultMovesToTierAOnItsNextUnlock() throws Exception {
		assertEquals("first opens [256 MiB t=3, 1024 MiB t=1], header 1024"
						+ " MiB t=1, slot moved, keys 2, note kept; next opens"
						+ " [1024 MiB t=1], note kept, wrong password refused",
				upgradeFromStrongTo(A));
	}

	@Test
	public void aTierBVaultMovesToTierAOnABiggerPhone() throws Exception {
		phone(B).createVault(PASSWORD.clone());
		derivations.clear();
		String first = opens(phone(A), PASSWORD);
		List<String> firstDerivations = new ArrayList<>(derivations);
		derivations.clear();
		String next = opens(phone(A), PASSWORD);
		assertEquals("opens [512 MiB t=2, 1024 MiB t=1], 1024 MiB t=1,"
						+ " opens [1024 MiB t=1]",
				first + " " + firstDerivations + ", " + headerLabel() + ", "
						+ next + " " + derivations);
	}

	@Test
	public void aStrongerHeaderIsNeverWeakened() throws Exception {
		createWithNote(A);
		byte[] before = headerBytes();
		StringBuilder out = new StringBuilder();
		Argon2.Argon2Params[] smaller = {B, C};
		for (Argon2.Argon2Params tier : smaller) {
			derivations.clear();
			VaultManager m = phone(tier);
			out.append(opens(m, PASSWORD)).append(' ').append(derivations)
					.append(' ').append(note(m)).append(' ')
					.append(Arrays.equals(before, headerBytes())
							? "unchanged" : "rewritten").append("; ");
		}
		assertEquals("opens [1024 MiB t=1] kept unchanged; "
						+ "opens [1024 MiB t=1] kept unchanged; ",
				out.toString());
	}

	@Test
	public void theRewrapDecisionTreatsThePhonesTierAsCurrent()
			throws Exception {
		Argon2.Argon2Params reduced = VaultManager.REDUCED_KDF;
		Argon2.Argon2Params[] headers = {A, B, C, reduced};
		String[] headerNames = {"A", "B", "C", "R"};
		Argon2.Argon2Params[] tiers = {A, B, C};
		String[] tierNames = {"A", "B", "C"};
		StringBuilder out = new StringBuilder();
		for (int t = 0; t < tiers.length; t++) {
			VaultManager m = phone(tiers[t]);
			out.append(tierNames[t]).append(':');
			for (int h = 0; h < headers.length; h++) {
				VaultHeader header = VaultHeader.createKeystoreFactor(
						new byte[32], headers[h].memoryKb,
						headers[h].iterations, new byte[60], new byte[16],
						new byte[32], 0L, 1, new byte[48]);
				out.append(' ').append(headerNames[h]).append('=')
						.append(m.needsRewrap(header, false) ? "up" : "ok");
				assertTrue(m.needsRewrap(header, true));
			}
			out.append("; ");
		}
		assertEquals("A: A=ok B=up C=up R=up; B: A=ok B=ok C=up R=up;"
						+ " C: A=ok B=ok C=ok R=ok; ",
				out.toString());
	}

	@Test
	public void aFailedUpgradeLeavesTheOldHeaderWorking() throws Exception {
		createWithNote(C);
		byte[] before = headerBytes();
		List<String> keysBefore = aliases();
		failingMemoryKb.add(A.memoryKb);
		failingMemoryKb.add(B.memoryKb);
		derivations.clear();
		VaultManager m = phone(A);
		String first = opens(m, PASSWORD);
		List<String> firstDerivations = new ArrayList<>(derivations);
		boolean unchanged = Arrays.equals(before, headerBytes());
		boolean sameKeys = keysBefore.equals(aliases());
		m.lockVault();
		derivations.clear();
		String again = opens(m, PASSWORD);
		List<String> againDerivations = new ArrayList<>(derivations);
		failingMemoryKb.clear();
		VaultManager later = phone(C);
		String laterUnlock = opens(later, PASSWORD);
		assertEquals("opens [256 MiB t=3, 1024 MiB t=1, 512 MiB t=2],"
						+ " header unchanged, keys unchanged, no trial file;"
						+ " same process opens [256 MiB t=3];"
						+ " later opens, note kept",
				first + " " + firstDerivations + ", header "
						+ (unchanged ? "unchanged" : "rewritten") + ", keys "
						+ (sameKeys ? "unchanged" : "changed") + ", "
						+ (new File(vaultDir(), "kdf.trial").exists()
						? "trial file left" : "no trial file")
						+ "; same process " + again + " " + againDerivations
						+ "; later " + laterUnlock + ", note " + note(later));
	}

	@Test
	public void aProcessKilledDuringTheUpgradeLeavesTheOldHeaderWorking()
			throws Exception {
		createWithNote(C);
		byte[] before = headerBytes();
		killUnlockAt(A, A.memoryKb);
		boolean unchanged = Arrays.equals(before, headerBytes());
		boolean trialLeft = new File(vaultDir(), "kdf.trial").exists();
		killUnlockAt(A, A.memoryKb);
		boolean stillUnchanged = Arrays.equals(before, headerBytes());

		derivations.clear();
		VaultManager next = phone(A);
		String unlock = opens(next, PASSWORD);
		List<String> stepped = new ArrayList<>(derivations);
		String label = headerLabel();
		String content = note(next);
		derivations.clear();
		String again = opens(phone(A), PASSWORD);
		assertEquals("header unchanged, trial file left, unchanged after a"
						+ " second kill; opens [256 MiB t=3, 512 MiB t=2],"
						+ " 512 MiB t=2, note kept, trial file gone, keys 2;"
						+ " next opens [512 MiB t=2]",
				"header " + (unchanged ? "unchanged" : "rewritten")
						+ ", trial file " + (trialLeft ? "left" : "gone")
						+ ", " + (stillUnchanged ? "unchanged" : "rewritten")
						+ " after a second kill; " + unlock + " " + stepped
						+ ", " + label + ", note " + content + ", trial file "
						+ (new File(vaultDir(), "kdf.trial").exists()
						? "left" : "gone") + ", keys " + aliases().size()
						+ "; next " + again + " " + derivations);
	}

	@Test
	public void oneInterruptionDoesNotStepDown() throws Exception {
		createWithNote(C);
		killUnlockAt(A, A.memoryKb);
		derivations.clear();
		String unlock = opens(phone(A), PASSWORD);
		assertEquals("opens [256 MiB t=3, 1024 MiB t=1] 1024 MiB t=1",
				unlock + " " + derivations + " " + headerLabel());
	}

	@Test
	public void twoInterruptionsAtTierBStepDownToTierC() throws Exception {
		createWithNote(C);
		byte[] before = headerBytes();
		killUnlockAt(B, B.memoryKb);
		killUnlockAt(B, B.memoryKb);
		derivations.clear();
		String unlock = opens(phone(B), PASSWORD);
		assertEquals("opens [256 MiB t=3] unchanged",
				unlock + " " + derivations + " "
						+ (Arrays.equals(before, headerBytes())
						? "unchanged" : "rewritten"));
	}

	@Test
	public void theStepDownEndsWithAnUpdateOrAfter30Days() throws Exception {
		createWithNote(C);
		killUnlockAt(A, A.memoryKb);
		killUnlockAt(A, A.memoryKb);
		String capped = tierOf(phone(A));
		version = 101;
		String updated = tierOf(phone(A));
		version = 100;
		writeCap(2, 0);
		now += VaultManager.KDF_CAP_LIFETIME_MS - 1;
		String inside = tierOf(phone(A));
		now += 2;
		String later = tierOf(phone(A));
		writeCap(2, 2);
		String both = tierOf(phone(A));
		assertEquals("capped 512 MiB, after an update 1024 MiB, within 30"
						+ " days 512 MiB, after 30 days 1024 MiB, both tiers"
						+ " interrupted 256 MiB",
				"capped " + capped + ", after an update " + updated
						+ ", within 30 days " + inside + ", after 30 days "
						+ later + ", both tiers interrupted " + both);
	}

	private static String tierOf(VaultManager m) {
		return m.deviceTier().memoryKb / 1024 + " MiB";
	}

	private void writeCap(int countA, int countB) throws Exception {
		Files.write(new File(vaultDir(), "kdf.cap").toPath(),
				new VaultManager.KdfCap(version, now, countA, countB)
						.toBytes());
	}

	@Test
	public void aProcessKilledWhileCreatingStepsDownAfterTheSecondKill()
			throws Exception {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < 2; i++) {
			killedAtMemoryKb = A.memoryKb;
			try {
				phone(A).createVault(PASSWORD.clone());
				fail("the injected kill must stop the creation");
			} catch (AssertionError expected) {
			}
			restoreAfterKill();
			out.append(phone(A).vaultExists() ? "a vault; " : "no vault; ");
		}
		VaultManager m = phone(A);
		m.createVault(PASSWORD.clone());
		assertEquals("no vault; no vault; 512 MiB t=2, opens",
				out + headerLabel() + ", " + opens(phone(A), PASSWORD));
	}

	@Test
	public void withoutTheNativeLibraryABigHeaderIsKeptAndNotCounted()
			throws Exception {
		createWithNote(A);
		byte[] before = headerBytes();
		List<String> keysBefore = aliases();
		nativeGone = true;
		StringBuilder out = new StringBuilder();
		Argon2.Argon2Params[] tiers = {A, C, C};
		for (Argon2.Argon2Params tier : tiers) {
			try {
				phone(tier).unlockVault(PASSWORD.clone());
				out.append("opened; ");
			} catch (Argon2.NativeRequiredException e) {
				out.append("needs native; ");
			}
		}
		boolean unchanged = Arrays.equals(before, headerBytes());
		boolean sameKeys = keysBefore.equals(aliases());
		nativeGone = false;
		VaultManager back = phone(A);
		String unlock = opens(back, PASSWORD);
		String content = note(back);
		back.lockVault();
		String wrong = opens(phone(A), WRONG);
		String throttled;
		try {
			phone(A).unlockVault(PASSWORD.clone());
			throttled = "not throttled";
		} catch (SecurityException e) {
			throttled = "throttled";
		}
		assertEquals("needs native; needs native; needs native; header"
						+ " unchanged, keys unchanged, native back: opens,"
						+ " note kept; a wrong password is refused and"
						+ " throttled",
				out + "header " + (unchanged ? "unchanged" : "rewritten")
						+ ", keys " + (sameKeys ? "unchanged" : "changed")
						+ ", native back: " + unlock + ", note " + content
						+ "; a wrong password is " + wrong + " and "
						+ throttled);
	}

	@Test
	public void creationFallsBackOneStepAtATime() throws Exception {
		StringBuilder out = new StringBuilder();
		int[][] failures = {{A.memoryKb}, {A.memoryKb, B.memoryKb},
				{A.memoryKb, B.memoryKb, C.memoryKb}};
		for (int i = 0; i < failures.length; i++) {
			name = "fallback" + i;
			failingMemoryKb.clear();
			for (int kb : failures[i]) failingMemoryKb.add(kb);
			phone(A).createVault(PASSWORD.clone());
			failingMemoryKb.clear();
			out.append(headerLabel()).append(' ')
					.append(opens(phone(A), PASSWORD))
					.append("; ");
		}
		assertEquals("512 MiB t=2 opens; 256 MiB t=3 opens;"
				+ " 128 MiB t=6 opens; ", out.toString());
	}

	@Test
	public void aReducedHeaderStaysOnTierCAndMovesUpOnTierA()
			throws Exception {
		failingMemoryKb.add(C.memoryKb);
		phone(C).createVault(PASSWORD.clone());
		failingMemoryKb.clear();
		byte[] before = headerBytes();
		derivations.clear();
		String onC = opens(phone(C), PASSWORD) + " " + derivations + " "
				+ (Arrays.equals(before, headerBytes())
				? "unchanged" : "rewritten");
		String onA = opens(phone(A), PASSWORD) + " " + headerLabel();
		assertEquals("opens [128 MiB t=6] unchanged; opens 1024 MiB t=1",
				onC + "; " + onA);
	}

	@Test
	public void exportsKeepThePortableParametersOnTierA() throws Exception {
		VaultManager m = phone(A);
		m.createVault(PASSWORD.clone());
		m.addItem(VaultItem.ItemType.NOTE, "note",
				"kept".getBytes(StandardCharsets.UTF_8));
		byte[] export = m.exportVault("export password 9(Kk".toCharArray());
		DataInputStream in = new DataInputStream(
				new ByteArrayInputStream(export));
		in.readInt();
		in.readInt();
		in.readFully(new byte[32]);
		String params = in.readInt() + " " + in.readInt() + " " + in.readInt();

		byte[] crafted = export.clone();
		ByteBuffer.wrap(crafted).putInt(40, 512 * 1024).putInt(44, 2);
		derivations.clear();
		String craftedResult;
		try {
			m.importVault(crafted, "export password 9(Kk".toCharArray(),
					false);
			craftedResult = "imported";
		} catch (IOException e) {
			craftedResult = "refused";
		}
		List<String> craftedDerivations = new ArrayList<>(derivations);

		name = "other";
		VaultManager other = phone(A);
		other.createVault(PASSWORD.clone());
		other.importVault(export, "export password 9(Kk".toCharArray(),
				false);
		assertEquals("262144 3 1; 512 MiB export refused before deriving: []"
						+ "; imported note kept",
				params + "; 512 MiB export " + craftedResult
						+ " before deriving: " + craftedDerivations
						+ "; imported note " + note(other));
	}

	@Test
	public void aPasswordChangeUsesTheTierAndNeverWeakens() throws Exception {
		createWithNote(C);
		VaultManager m = phone(A);
		m.unlockVault(PASSWORD.clone());
		m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
		String afterChange = headerLabel();
		VaultManager smaller = phone(B);
		smaller.unlockVault(NEW_PASSWORD.clone());
		smaller.changePassword(NEW_PASSWORD.clone(), PASSWORD.clone());
		VaultManager check = phone(B);
		assertEquals("1024 MiB t=1, on a smaller phone 1024 MiB t=1, opens,"
						+ " note kept, old password refused",
				afterChange + ", on a smaller phone " + headerLabel() + ", "
						+ opens(check, PASSWORD) + ", note " + note(check)
						+ ", old password "
						+ opens(phone(B), NEW_PASSWORD));
	}

	@Test
	public void theRealDerivationOpensEveryTierAtASmallSize()
			throws Exception {
		StringBuilder out = new StringBuilder();
		Argon2.Argon2Params[] tiers = {C, B, A};
		name = "real";
		VaultManager first = realPhone(C);
		first.createVault(PASSWORD.clone());
		first.addItem(VaultItem.ItemType.NOTE, "note",
				"kept".getBytes(StandardCharsets.UTF_8));
		first.lockVault();
		for (Argon2.Argon2Params tier : tiers) {
			VaultManager m = realPhone(tier);
			String unlock = opens(m, PASSWORD);
			out.append(headerLabel()).append(' ').append(unlock).append(' ')
					.append(note(m)).append("; ");
		}
		out.append("wrong password ").append(opens(realPhone(A), WRONG));
		assertEquals("256 MiB t=3 opens kept; 512 MiB t=2 opens kept; "
						+ "1024 MiB t=1 opens kept; wrong password refused",
				out.toString());
	}

	@Test
	public void aPasswordChangeNeverWeakensWhenThePhoneIsCapped()
			throws Exception {
		createWithNote(A);
		Files.write(new File(vaultDir(), "kdf.cap").toPath(),
				new VaultManager.KdfCap(version, now, 2, 2).toBytes());
		VaultManager m = phone(A);
		String capped = m.deviceTier().memoryKb / 1024 + " MiB";
		m.unlockVault(PASSWORD.clone());
		m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
		VaultManager check = phone(A);
		assertEquals("capped 256 MiB, header 1024 MiB t=1, opens, note kept",
				"capped " + capped + ", header " + headerLabel() + ", "
						+ opens(check, NEW_PASSWORD) + ", note " + note(check));
	}

	@Test
	public void aPasswordChangeFailsRatherThanWeaken() throws Exception {
		createWithNote(A);
		byte[] before = headerBytes();
		List<String> keysBefore = aliases();
		VaultManager m = phone(A);
		m.unlockVault(PASSWORD.clone());
		allowedA = 1;
		String change;
		try {
			m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
			change = "changed";
		} catch (IOException e) {
			change = "failed";
		}
		allowedA = Integer.MAX_VALUE;
		boolean unchanged = Arrays.equals(before, headerBytes());
		boolean sameKeys = keysBefore.equals(aliases());
		VaultManager check = phone(A);
		assertEquals("failed, header unchanged, keys unchanged, opens, note"
						+ " kept, new password refused",
				change + ", header " + (unchanged ? "unchanged" : "rewritten")
						+ ", keys " + (sameKeys ? "unchanged" : "changed")
						+ ", " + opens(check, PASSWORD) + ", note "
						+ note(check) + ", new password "
						+ opens(phone(A), NEW_PASSWORD));
	}

	@Test
	public void aFailedCleanupAfterThePasswordChangeKeepsTheNewPassword()
			throws Exception {
		createWithNote(C);
		VaultManager m = new VaultManager(app, location(), fake, () -> C) {
			@Override
			void deleteRekeyBackup() throws IOException {
				throw new IOException("injected");
			}
		};
		m.unlockVault(PASSWORD.clone());
		m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
		boolean backupLeft =
				new File(vaultDir(), "items_rekey_backup").exists();
		String sameProcess = note(m);
		VaultManager later = phone(C);
		String unlock = opens(later, NEW_PASSWORD);
		String content = note(later);
		boolean backupAfter =
				new File(vaultDir(), "items_rekey_backup").exists();
		assertEquals("backup left, note kept in the same process; new"
						+ " password opens, note kept, backup gone, old"
						+ " password refused",
				"backup " + (backupLeft ? "left" : "gone") + ", note "
						+ sameProcess + " in the same process; new password "
						+ unlock + ", note " + content + ", backup "
						+ (backupAfter ? "left" : "gone")
						+ ", old password " + opens(phone(C), PASSWORD));
	}

	@Test
	public void noKeyIsDeletedWhileTheDirectoryCannotBeSynced()
			throws Exception {
		createWithNote(C);
		VaultManager m = new VaultManager(app, location(), fake, () -> A) {
			@Override
			void syncVaultDirectory() throws IOException {
				throw new IOException("injected");
			}
		};
		sources(m);
		String first = opens(m, PASSWORD);
		int keysWhileUnsynced = aliases().size();
		derivations.clear();
		String later = opens(phone(A), PASSWORD);
		assertEquals("opens 1024 MiB t=1, keys 4; later opens [1024 MiB t=1],"
						+ " keys 2",
				first + " " + headerLabel() + ", keys " + keysWhileUnsynced
						+ "; later " + later + " " + derivations + ", keys "
						+ aliases().size());
	}

	@Test
	public void aLockAskedForDuringAnUnlockEndsLocked() throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		derivationStarted = new java.util.concurrent.CountDownLatch(1);
		releaseDerivation = new java.util.concurrent.CountDownLatch(1);
		boolean[] result = new boolean[1];
		Throwable[] error = new Throwable[1];
		Thread unlock = new Thread(() -> {
			try {
				result[0] = m.unlockVault(PASSWORD.clone());
			} catch (Throwable t) {
				error[0] = t;
			}
		});
		unlock.start();
		assertTrue(derivationStarted.await(10,
				java.util.concurrent.TimeUnit.SECONDS));
		boolean inProgress = m.isUnlockInProgress();
		long start = System.nanoTime();
		m.requestLock();
		long requestMs = (System.nanoTime() - start) / 1_000_000L;
		releaseDerivation.countDown();
		unlock.join(20_000);
		derivationStarted = null;
		releaseDerivation = null;
		assertEquals(null, error[0]);
		assertTrue("the request must not wait for the unlock",
				requestMs < 1000);
		assertEquals("in progress, unlock returned true, locked",
				(inProgress ? "in progress" : "idle") + ", unlock returned "
						+ result[0] + ", "
						+ (m.isUnlocked() ? "unlocked" : "locked"));
	}

	@Test
	public void aLockAskedForBeforeAQueuedUnlockStartsEndsLocked()
			throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		long mark = m.markUnlockPending();
		boolean pending = m.isUnlockInProgress();
		m.requestLock();
		boolean opened = m.unlockVault(PASSWORD.clone(), mark);
		assertEquals("pending, true, locked, idle",
				(pending ? "pending" : "idle") + ", " + opened + ", "
						+ (m.isUnlocked() ? "unlocked" : "locked") + ", "
						+ (m.isUnlockInProgress() ? "in progress" : "idle"));
	}

	@Test
	public void aLockAskedForWhileUnlockedLocksInTheBackground()
			throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		m.unlockVault(PASSWORD.clone());
		m.requestLock();
		long deadline = System.currentTimeMillis() + 10_000;
		while (m.isUnlocked() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertFalse(m.isUnlocked());
	}

	@Test
	public void aWrongPasswordIsRefusedWhileTheVaultIsOpen() throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		m.unlockVault(PASSWORD.clone());
		derivations.clear();
		boolean wrong = m.unlockVault(WRONG.clone());
		List<String> wrongDerivations = new ArrayList<>(derivations);
		String throttled;
		try {
			m.unlockVault(PASSWORD.clone());
			throttled = "not throttled";
		} catch (SecurityException e) {
			throttled = "throttled";
		}
		assertEquals("refused [256 MiB t=3], counted: throttled",
				(wrong ? "opened" : "refused") + " " + wrongDerivations
						+ ", counted: " + throttled);
	}

	@Test
	public void aWrongPasswordIsRefusedWhileALockWaitsForTheMonitor()
			throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		m.unlockVault(PASSWORD.clone());
		String itemId = m.listItems().get(0).id;
		java.util.concurrent.CountDownLatch held =
				new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release =
				new java.util.concurrent.CountDownLatch(1);
		Thread holder = new Thread(() -> {
			synchronized (m) {
				held.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		});
		holder.start();
		assertTrue(held.await(10, java.util.concurrent.TimeUnit.SECONDS));
		long generation = m.getLockGeneration();
		m.requestLock();
		boolean openAtOnce = m.isUnlocked();
		boolean generationMoved = m.getLockGeneration() != generation;
		String thumbnail;
		try {
			m.getThumbnail(itemId);
			thumbnail = "read";
		} catch (SecurityException e) {
			thumbnail = "refused";
		}
		boolean[] result = new boolean[1];
		Throwable[] error = new Throwable[1];
		Thread attacker = new Thread(() -> {
			try {
				result[0] = m.unlockVault(WRONG.clone());
			} catch (Throwable t) {
				error[0] = t;
			}
		});
		attacker.start();
		release.countDown();
		holder.join(10_000);
		attacker.join(20_000);
		awaitLocked(m);
		assertEquals(null, error[0]);
		assertEquals("locked at once, generation moved, thumbnail refused,"
						+ " wrong password refused, ends locked",
				(openAtOnce ? "open" : "locked") + " at once, generation "
						+ (generationMoved ? "moved" : "same")
						+ ", thumbnail " + thumbnail + ", wrong password "
						+ (result[0] ? "accepted" : "refused") + ", ends "
						+ (m.isUnlocked() ? "unlocked" : "locked"));
	}

	private static void awaitLocked(VaultManager m) throws Exception {
		long deadline = System.currentTimeMillis() + 10_000;
		while (m.isUnlocked() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
	}

	@Test
	public void lockingALockedVaultStillClearsOurClipboard() throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		com.professor.zerion.android.util.SecureClipboard.copySensitive(app,
				"Password", "copied-secret", 0L);
		android.content.ClipboardManager cm =
				(android.content.ClipboardManager) app.getSystemService(
						android.content.Context.CLIPBOARD_SERVICE);
		boolean before = cm.hasPrimaryClip();
		m.requestLock();
		long deadline = System.currentTimeMillis() + 10_000;
		while (cm.hasPrimaryClip() && System.currentTimeMillis() < deadline) {
			org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
					.idle();
			Thread.sleep(20);
		}
		assertEquals("copied, then empty",
				(before ? "copied" : "not copied") + ", then "
						+ (cm.hasPrimaryClip() ? "still on the clipboard"
						: "empty"));
	}

	@Test
	public void theAppLockDuringAPendingUnlockEndsLocked() throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		long mark = m.markUnlockPending();
		new com.professor.zerion.android.account.LockManagerForTests()
				.create(app, org.mockito.Mockito.mock(
						org.zerionproject.core.api.settings.SettingsManager
								.class), org.mockito.Mockito.mock(
						com.professor.zerion.android.api
								.AndroidNotificationManager.class), m)
				.setLocked(true);
		boolean opened = m.unlockVault(PASSWORD.clone(), mark);
		awaitLocked(m);
		assertEquals("true, locked, idle",
				opened + ", " + (m.isUnlocked() ? "unlocked" : "locked")
						+ ", " + (m.isUnlockInProgress() ? "pending" : "idle"));
	}

	@Test
	public void theItemBackupIsKeptWhenTheDirectoryCannotBeSynced()
			throws Exception {
		createWithNote(C);
		VaultManager m = new VaultManager(app, location(), fake, () -> C) {
			@Override
			void syncVaultDirectory() throws IOException {
				throw new IOException("injected");
			}
		};
		m.unlockVault(PASSWORD.clone());
		m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
		boolean backupLeft =
				new File(vaultDir(), "items_rekey_backup").exists();
		VaultManager later = phone(C);
		String unlock = opens(later, NEW_PASSWORD);
		String content = note(later);
		boolean backupAfter =
				new File(vaultDir(), "items_rekey_backup").exists();
		assertEquals("backup kept; new password opens, note kept, backup"
						+ " gone, old password refused",
				"backup " + (backupLeft ? "kept" : "deleted")
						+ "; new password " + unlock + ", note " + content
						+ ", backup " + (backupAfter ? "left" : "gone")
						+ ", old password " + opens(phone(C), PASSWORD));
	}

	@Test
	public void overlappingUnlocksEachSeeTheLockRequests() throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		long first = m.markUnlockPending();
		long second = m.markUnlockPending();
		m.requestLock();
		boolean firstOpened = m.unlockVault(PASSWORD.clone(), first);
		String afterFirst = (m.isUnlocked() ? "unlocked" : "locked") + " "
				+ (m.isUnlockInProgress() ? "pending" : "idle");
		boolean secondOpened = m.unlockVault(PASSWORD.clone(), second);
		String afterSecond = (m.isUnlocked() ? "unlocked" : "locked") + " "
				+ (m.isUnlockInProgress() ? "pending" : "idle");

		long beforeRequest = m.markUnlockPending();
		m.requestLock();
		long afterRequest = m.markUnlockPending();
		m.unlockVault(PASSWORD.clone(), beforeRequest);
		String early = m.isUnlocked() ? "unlocked" : "locked";
		m.unlockVault(PASSWORD.clone(), afterRequest);
		Thread.sleep(500);
		String late = m.isUnlocked() ? "unlocked" : "locked";
		assertEquals("true locked pending, true locked idle; asked before"
						+ " the lock: locked, asked after the lock: unlocked",
				firstOpened + " " + afterFirst + ", " + secondOpened + " "
						+ afterSecond + "; asked before the lock: " + early
						+ ", asked after the lock: " + late);
	}

	@Test
	public void aLockSkippedForALaterUnlockStillReleasesWhatTheLockReleases()
			throws Exception {
		createWithNote(C);
		VaultManager m = phone(C);
		m.unlockVault(PASSWORD.clone());
		java.util.concurrent.atomic.AtomicInteger listenerCalls =
				new java.util.concurrent.atomic.AtomicInteger();
		m.addLockListener(listenerCalls::incrementAndGet);
		com.professor.zerion.android.util.SecureClipboard.copySensitive(app,
				"Password", "copied-secret", 0L);
		android.content.ClipboardManager cm =
				(android.content.ClipboardManager) app.getSystemService(
						android.content.Context.CLIPBOARD_SERVICE);
		boolean copied = cm.hasPrimaryClip();
		synchronized (m) {
			m.requestLock();
			long mark = m.markUnlockPending();
			m.unlockVault(PASSWORD.clone(), mark);
		}
		long deadline = System.currentTimeMillis() + 10_000;
		while ((listenerCalls.get() == 0 || cm.hasPrimaryClip())
				&& System.currentTimeMillis() < deadline) {
			org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
					.idle();
			Thread.sleep(20);
		}
		Thread.sleep(200);
		assertEquals("copied, unlocked, clipboard empty, listener called once,"
						+ " note kept",
				(copied ? "copied" : "not copied") + ", "
						+ (m.isUnlocked() ? "unlocked" : "locked")
						+ ", clipboard "
						+ (cm.hasPrimaryClip() ? "holds the copy" : "empty")
						+ ", listener called " + (listenerCalls.get() == 1
						? "once" : listenerCalls.get() + " times")
						+ ", note " + note(m));
	}

	private boolean failSync = false;

	private VaultManager syncControlled(Argon2.Argon2Params tier) {
		return sources(new VaultManager(app, location(), fake, () -> tier) {
			@Override
			void syncVaultDirectory() throws IOException {
				if (failSync) throw new IOException("injected");
				super.syncVaultDirectory();
			}
		});
	}

	private File leaveBackup() throws Exception {
		File backup = new File(vaultDir(), "items_rekey_backup");
		assertTrue(backup.mkdirs());
		Files.write(new File(backup, "left.bin").toPath(), new byte[]{1, 2});
		return backup;
	}

	@Test
	public void aLeftoverBackupIsKeptUntilTheDirectoryIsSynced()
			throws Exception {
		createWithNote(C);
		File backup = leaveBackup();
		failSync = true;
		String unsynced = opens(syncControlled(C), PASSWORD);
		boolean keptWhileUnsynced = backup.exists();
		failSync = false;
		VaultManager later = syncControlled(C);
		String synced = opens(later, PASSWORD);
		assertEquals("opens, backup kept; opens, backup gone, note kept",
				unsynced + ", backup " + (keptWhileUnsynced ? "kept" : "gone")
						+ "; " + synced + ", backup "
						+ (backup.exists() ? "kept" : "gone") + ", note "
						+ note(later));
	}

	@Test
	public void aPasswordChangeStopsBeforeWritingWhenALeftoverBackupCannotBeSynced()
			throws Exception {
		createWithNote(C);
		VaultManager m = syncControlled(C);
		m.unlockVault(PASSWORD.clone());
		File backup = leaveBackup();
		byte[] before = headerBytes();
		List<String> keysBefore = aliases();
		failSync = true;
		String change;
		try {
			m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
			change = "changed";
		} catch (IOException e) {
			change = "failed";
		}
		failSync = false;
		boolean unchanged = Arrays.equals(before, headerBytes());
		boolean sameKeys = keysBefore.equals(aliases());
		boolean headerNew = new File(vaultDir(), "vault.header.new").exists();
		boolean backupKept = backup.exists();
		VaultManager later = phone(C);
		assertEquals("failed, header unchanged, keys unchanged, no new header,"
						+ " backup kept; old password opens, note kept",
				change + ", header " + (unchanged ? "unchanged" : "rewritten")
						+ ", keys " + (sameKeys ? "unchanged" : "changed")
						+ ", " + (headerNew ? "a new header" : "no new header")
						+ ", backup " + (backupKept ? "kept" : "gone")
						+ "; old password " + opens(later, PASSWORD)
						+ ", note " + note(later));
	}

	@Test
	public void thePortableConstantsAreUnchanged() {
		assertTrue(VaultKdfTier.sameCost(VaultManager.STRONG_KDF,
				new Argon2.Argon2Params(262144, 3, 1, 32)));
		assertTrue(VaultKdfTier.sameCost(VaultManager.REDUCED_KDF,
				new Argon2.Argon2Params(131072, 6, 1, 32)));
		assertFalse(VaultKdfTier.atLeast(VaultManager.STRONG_KDF,
				VaultKdfTier.TIER_B));
	}

	private static void copyTree(File from, File to) throws Exception {
		if (from.isDirectory()) {
			to.mkdirs();
			File[] children = from.listFiles();
			if (children == null) return;
			for (File c : children) copyTree(c, new File(to, c.getName()));
		} else {
			Files.copy(from.toPath(), to.toPath());
		}
	}
}
