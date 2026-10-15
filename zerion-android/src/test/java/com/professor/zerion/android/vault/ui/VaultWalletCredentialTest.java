package com.professor.zerion.android.vault.ui;

import android.os.Looper;
import android.util.Base64;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.InMemoryVault;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.time.Duration;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.vault.ui.VaultViewModelRig.settle;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultWalletCredentialTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static byte[] pbkdf2(String pw, byte[] salt, int iter, int len)
			throws Exception {
		return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
				.generateSecret(new PBEKeySpec(pw.toCharArray(), salt, iter,
						len * 8)).getEncoded();
	}

	private static void advance(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	@Test
	public void setupStoresASaltedPbkdf2RecordAndOpensTheSection()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.beginWalletAccess();
		settle();
		assertEquals("setup", vm.getWalletAuthState().getValue());
		assertFalse(vm.walletSessionValid());

		vm.setupWalletAuth("PIN", "4711".toCharArray());
		settle();
		JSONObject o = rig.settings();
		assertEquals("PIN", o.getString("authType"));
		assertEquals(120_000, o.getInt("authIter"));
		byte[] salt = Base64.decode(o.getString("authSalt"), Base64.NO_WRAP);
		byte[] hash = Base64.decode(o.getString("authHash"), Base64.NO_WRAP);
		assertEquals(16, salt.length);
		assertArrayEquals(pbkdf2("4711", salt, 120_000, 32), hash);
		assertFalse(o.getString("authSalt").contains("\n"));
		assertEquals(Boolean.TRUE, vm.getWalletGateGranted().getValue());
		assertTrue(vm.walletSessionValid());
		assertTrue(vm.isWalletAuthPin());

		vm.clearWalletGate();
		vm.beginWalletAccess();
		settle();
		assertEquals(Boolean.TRUE, vm.getWalletGateGranted().getValue());
	}

	@Test
	public void aPasswordCredentialIsNotAPin() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		assertFalse(vm.isWalletAuthPin());
		rig.unlockWalletSection(vm, "long passphrase");
		assertFalse(vm.isWalletAuthPin());
		assertEquals("PASSWORD", rig.settings().getString("authType"));
	}

	@Test
	public void verifyGrantsOnlyTheRightCredentialAndCountsFailures()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		rig.unlockWalletSection(rig.newModel(), "right");
		VaultViewModel vm = rig.newModel();
		vm.beginWalletAccess();
		settle();
		assertEquals("verify", vm.getWalletAuthState().getValue());

		vm.verifyWalletAuth("wrong".toCharArray());
		settle();
		assertEquals(Boolean.FALSE, vm.getWalletGateGranted().getValue());
		assertFalse(vm.walletSessionValid());
		assertEquals(1, rig.settings().getInt("authFailures"));
		assertNull(vm.getWalletError().getValue());

		vm.verifyWalletAuth("right".toCharArray());
		settle();
		assertEquals(Boolean.TRUE, vm.getWalletGateGranted().getValue());
		assertTrue(vm.walletSessionValid());
		assertEquals(0, rig.settings().getInt("authFailures"));
	}

	@Test
	public void theThirdFailureThrottlesAndRelocks() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "right");
		for (int i = 0; i < 2; i++) {
			vm.verifyWalletAuth("wrong".toCharArray());
			settle();
			assertNull(vm.getWalletError().getValue());
		}
		assertEquals(2, rig.settings().getInt("authFailures"));
		assertTrue("two failures keep an open session",
				vm.walletSessionValid());
		vm.verifyWalletAuth("wrong".toCharArray());
		settle();
		assertEquals(3, rig.settings().getInt("authFailures"));
		assertFalse("the third failure relocks", vm.walletSessionValid());
		assertEquals(rig.app.getString(R.string.wallet_auth_too_many),
				vm.getWalletError().getValue().getIfNotHandled());

		vm.verifyWalletAuth("right".toCharArray());
		settle();
		assertEquals("a throttled check refuses even the right credential",
				Boolean.FALSE, vm.getWalletGateGranted().getValue());
		assertEquals("a throttled check is not counted",
				3, rig.settings().getInt("authFailures"));
		assertEquals(rig.app.getString(R.string.wallet_auth_too_many),
				vm.getWalletError().getValue().getIfNotHandled());

		advance(1_500);
		vm.verifyWalletAuth("right".toCharArray());
		settle();
		assertEquals(Boolean.TRUE, vm.getWalletGateGranted().getValue());
		assertEquals(0, rig.settings().getInt("authFailures"));
	}

	@Test
	public void failuresSurviveANewModel() throws Exception {
		InMemoryVault vault = new InMemoryVault();
		VaultViewModelRig rig = new VaultViewModelRig(vault);
		VaultViewModel first = rig.newModel();
		rig.unlockWalletSection(first, "right");
		first.verifyWalletAuth("wrong".toCharArray());
		first.verifyWalletAuth("wrong".toCharArray());
		settle();
		assertEquals(2, rig.settings().getInt("authFailures"));

		VaultViewModel second = rig.newModel();
		second.verifyWalletAuth("wrong".toCharArray());
		settle();
		assertEquals(3, rig.settings().getInt("authFailures"));
		assertEquals(rig.app.getString(R.string.wallet_auth_too_many),
				second.getWalletError().getValue().getIfNotHandled());

		VaultViewModel third = rig.newModel();
		third.verifyWalletAuth("right".toCharArray());
		settle();
		assertEquals("restored failures throttle a fresh model",
				Boolean.FALSE, third.getWalletGateGranted().getValue());
	}

	@Test
	public void aVaultLockEndsTheWalletSession() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "right");
		rig.vault.lockGeneration++;
		assertFalse(vm.walletSessionValid());
		vm.beginWalletAccess();
		settle();
		assertEquals("verify", vm.getWalletAuthState().getValue());
		vm.verifyWalletAuth("right".toCharArray());
		settle();
		assertTrue(vm.walletSessionValid());
		rig.vault.unlocked = false;
		assertFalse(vm.walletSessionValid());
	}

	@Test
	public void deletingWithAWrongCredentialCountsAsAFailure()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "right");
		vm.deleteWalletWithAuth("missing", "wrong".toCharArray());
		settle();
		assertEquals(1, rig.settings().getInt("authFailures"));
		vm.deleteWalletWithAuth("missing", "right".toCharArray());
		settle();
		assertEquals(0, rig.settings().getInt("authFailures"));
	}
}
