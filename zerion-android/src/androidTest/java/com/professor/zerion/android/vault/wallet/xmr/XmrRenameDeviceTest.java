package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.ui.Event;
import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-device reproduction and regression for the rename identity model: the
 * wallet's display name must have zero cryptographic effect, so renaming a
 * wallet must never change the password that opens it. Uses the real
 * VaultManager, WalletStore and native engine on a throwaway vault in app data.
 * No network and no value transaction.
 */
@RunWith(AndroidJUnit4.class)
public class XmrRenameDeviceTest {

	private static final char[] VAULT_PW = "vault-master-pass".toCharArray();
	private static final char[] W_PW = "wallet-spend-pass".toCharArray();

	private Context ctx;
	private VaultManager vault;
	private WalletStore store;

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		assertTrue(NativeMonero.isAvailable());
		deleteTree(new com.professor.zerion.android.vault.storage.SecureFileIO(ctx)
				.getVaultDir());
		vault = new VaultManager(ctx);
		if (!safeUnlock()) {
			vault.createVault(VAULT_PW.clone());
			assertTrue(safeUnlock());
		}
		store = new WalletStore(vault);
	}

	private static void deleteTree(java.io.File f) {
		if (f == null || !f.exists()) return;
		java.io.File[] kids = f.listFiles();
		if (kids != null) for (java.io.File k : kids) deleteTree(k);
		f.delete();
	}

	private boolean safeUnlock() {
		try {
			return vault.unlockVault(VAULT_PW.clone());
		} catch (Exception e) {
			return false;
		}
	}

	private XmrWalletManager newManager() {
		return new XmrWalletManager(ctx, vault, store, new NativeMoneroEngine());
	}

	private interface Action {
		void run();
	}

	private <T> T await(LiveData<Event<T>> ok, LiveData<Event<XmrError>> err,
			Action action, AtomicReference<XmrError> errOut) {
		AtomicReference<T> out = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		Observer<Event<T>> obOk = ev -> {
			if (ev == null) return;
			T c = ev.getIfNotHandled();
			if (c != null) {
				out.set(c);
				latch.countDown();
			}
		};
		Observer<Event<XmrError>> obErr = ev -> {
			if (ev == null) return;
			XmrError e = ev.getIfNotHandled();
			if (e != null) {
				errOut.set(e);
				latch.countDown();
			}
		};
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ok.observeForever(obOk);
			err.observeForever(obErr);
		});
		InstrumentationRegistry.getInstrumentation().runOnMainSync(action::run);
		try {
			assertTrue("timed out", latch.await(90, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				ok.removeObserver(obOk);
				err.removeObserver(obErr);
			});
		}
		return out.get();
	}

	private String createWallet(XmrWalletManager m, String name) {
		AtomicReference<XmrError> err = new AtomicReference<>();
		String seedId = await(m.getSeedReveal(), m.getError(),
				() -> m.createWallet(name, W_PW.clone()), err);
		assertNotNull("create failed: " + err.get(), seedId);
		m.takePendingSeed(seedId);
		return idOf(name);
	}

	private String idOf(String label) {
		try {
			for (WalletRecord w : store.listWallets()) {
				if (w.coin == WalletCoin.XMR && label.equals(w.name)) {
					return w.id;
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	private void openExpectingSuccess(XmrWalletManager m, String id, String msg) {
		AtomicReference<XmrError> err = new AtomicReference<>();
		String opened = await(m.getSessionOpened(), m.getError(),
				() -> m.openWallet(id, W_PW.clone()), err);
		assertNull(msg + " (got error " + err.get() + ")", err.get());
		assertNotNull(msg, opened);
	}

	private static void assertNull(String msg, Object o) {
		if (o != null) throw new AssertionError(msg + " but was " + o);
	}

	private void rename(XmrWalletManager m, String id, String newName) {
		CountDownLatch latch = new CountDownLatch(1);
		Observer<List<WalletRecord>> ob = list -> {
			if (list == null) return;
			for (WalletRecord w : list) {
				if (w.coin == WalletCoin.XMR && newName.equals(w.name)) {
					latch.countDown();
					return;
				}
			}
		};
		InstrumentationRegistry.getInstrumentation().runOnMainSync(
				() -> m.getWallets().observeForever(ob));
		InstrumentationRegistry.getInstrumentation().runOnMainSync(
				() -> m.renameWallet(id, newName, W_PW.clone()));
		try {
			assertTrue("rename timed out", latch.await(90, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(
					() -> m.getWallets().removeObserver(ob));
		}
	}

	@Test
	public void sameWalletPasswordOpensAfterRename() {
		XmrWalletManager m = newManager();
		String idA = createWallet(m, "RenameA-" + System.nanoTime() % 100000);
		assertNotNull(idA);
		openExpectingSuccess(m, idA, "password opens before rename");

		String newName = "RenameB-" + System.nanoTime() % 100000;
		rename(m, idA, newName);
		assertEquals("the walletId is immutable across a rename; only the "
				+ "display name changes", newName, displayNameOf(m, idA));

		openExpectingSuccess(m, idA,
				"the exact same wallet password must open after rename");
	}

	private String displayNameOf(XmrWalletManager m, String id) {
		List<WalletRecord> ws = m.getWallets().getValue();
		if (ws != null) {
			for (WalletRecord w : ws) {
				if (w.id.equals(id)) return w.name;
			}
		}
		return null;
	}

	@Test
	public void recoversWalletZeroSealedByPreFixRename() {
		XmrWalletManager m = newManager();
		AtomicReference<XmrError> err = new AtomicReference<>();
		String origLabel = "Orig-" + System.nanoTime() % 100000;
		String seedId = await(m.getSeedReveal(), m.getError(),
				() -> m.createWallet(origLabel, W_PW.clone()), err);
		assertNotNull("create failed: " + err.get(), seedId);
		char[] seed = m.takePendingSeed(seedId);
		assertNotNull("captured seed", seed);
		assertEquals(25, new String(seed).trim().split("\\s+").length);

		String corruptedLabel = "Corrupt-" + System.nanoTime() % 100000;
		char[] zeroPw = new char[W_PW.length];
		String corruptedId;
		try {
			corruptedId = store.createWallet(WalletCoin.XMR, corruptedLabel,
					seed, zeroPw);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		java.util.Arrays.fill(seed, '\0');

		openExpectingSuccess(m, corruptedId,
				"a wallet zero-sealed by the pre-fix rename recovers with the "
				+ "real password");

		String recoveredId = idOf(corruptedLabel);
		assertNotNull("recovered wallet keeps its display name", recoveredId);
		openExpectingSuccess(m, recoveredId,
				"the recovered wallet opens again with the same password");
	}

	@Test
	public void samePasswordSurvivesMultipleRenames() {
		XmrWalletManager m = newManager();
		String id = createWallet(m, "Multi-" + System.nanoTime() % 100000);
		assertNotNull(id);
		openExpectingSuccess(m, id, "opens initially");
		for (int i = 0; i < 3; i++) {
			String nn = "Multi" + i + "-" + System.nanoTime() % 100000;
			rename(m, id, nn);
			assertEquals("the walletId is stable across every rename", nn,
					displayNameOf(m, id));
			openExpectingSuccess(m, id, "same password opens after rename " + i);
		}
	}
}
