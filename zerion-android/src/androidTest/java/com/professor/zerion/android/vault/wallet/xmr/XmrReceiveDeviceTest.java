package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.professor.zerion.android.contact.add.remote.QrCodeUtils;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.ui.Event;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * XMR-P3 on-device receive/subaddress privacy tests over the real vault store
 * (JSON-backed) and, where a native wallet is present, the real engine. Isolated
 * non-funded environment only.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class XmrReceiveDeviceTest {

	private static final char[] VAULT_PW = "vault-recv-771".toCharArray();
	private static final char[] W_PW = "wallet-recv-552".toCharArray();

	private Context ctx;
	private VaultManager vault;
	private WalletStore store;

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		vault = new VaultManager(ctx);
		if (!safeUnlock()) {
			vault.createVault(VAULT_PW.clone());
			assertTrue(safeUnlock());
		}
		store = new WalletStore(vault);
	}

	private boolean safeUnlock() {
		try {
			return vault.unlockVault(VAULT_PW.clone());
		} catch (Exception e) {
			return false;
		}
	}

	private XmrSubaddressLedger ledgerFor(String walletId) {
		return new XmrSubaddressLedger(new XmrReceiveJsonStore(store, walletId));
	}

	@Test
	public void a_jsonLedgerFreshIsIndexOneAndMonotonic() throws Exception {
		String id = "recv-a-" + System.nanoTime();
		XmrSubaddressLedger l = ledgerFor(id);
		assertEquals(0, l.issuedCount());
		assertEquals(1, l.reserveNext(1L));
		assertEquals(2, l.reserveNext(2L));
	}

	@Test
	public void b_processDeathAcrossRealStoreNeverReuses() throws Exception {
		String id = "recv-b-" + System.nanoTime();
		int first = ledgerFor(id).reserveNext(1L);

		XmrSubaddressLedger after = ledgerFor(id);
		assertEquals(first, after.issuedCount());
		assertTrue(after.reserveNext(2L) != first);
		assertEquals(first + 1, after.issuedCount());
	}

	@Test
	public void c_walletAbIsolationInRealStore() throws Exception {
		String a = "recv-a-iso-" + System.nanoTime();
		String b = "recv-b-iso-" + System.nanoTime();
		ledgerFor(a).reserveNext(1L);
		ledgerFor(a).reserveNext(2L);
		assertEquals("B is not affected by A", 0, ledgerFor(b).issuedCount());
		assertEquals("B issues its own index 1", 1, ledgerFor(b).reserveNext(3L));
		assertEquals("A unchanged by B", 2, ledgerFor(a).issuedCount());
	}

	@Test
	public void d_freshReceiveIsSubaddressNotPrimary() throws Exception {
		if (!NativeMonero.isAvailable()) return;
		XmrWalletManager m = new XmrWalletManager(ctx, vault, store,
				new NativeMoneroEngine());
		char[] seed = awaitSeed(m, () -> m.createWallet("Recv-D", W_PW.clone()));
		assertNotNull(seed);
		String id = firstXmrId();
		awaitSessionOpen(m, () -> m.openWallet(id, W_PW.clone()));

		XmrSubaddressLedger led = ledgerFor(id);
		main(() -> m.newReceiveAddress(id));
		assertTrue("newReceiveAddress reserves index 1",
				pollIssued(led, 1, 40000));
		assertTrue(led.cachedAddress(1).startsWith("8"));
		main(() -> m.newReceiveAddress(id));
		assertTrue("New address reserves index 2", pollIssued(led, 2, 40000));
		assertFalse(led.cachedAddress(1).equals(led.cachedAddress(2)));
		m.closeSession();
		java.util.Arrays.fill(seed, '\0');
	}

	@Test
	public void e_qrPayloadIsExactMoneroUri() throws Exception {
		String addr = "8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
		String uri = "monero:" + addr;
		android.graphics.Bitmap bmp = QrCodeUtils.generateQrCode(uri);
		assertNotNull("QR renders with no network call", bmp);
		assertTrue(bmp.getWidth() > 0 && bmp.getHeight() > 0);
	}

	private String firstXmrId() throws Exception {
		for (com.professor.zerion.android.vault.wallet.WalletRecord w :
				store.listWallets()) {
			if (w.coin == com.professor.zerion.android.vault.wallet.WalletCoin.XMR) {
				return w.id;
			}
		}
		return null;
	}

	private boolean pollIssued(XmrSubaddressLedger led, int target,
			long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (led.issuedCount() >= target) return true;
			Thread.sleep(200);
		}
		return led.issuedCount() >= target;
	}

	private interface Action {
		void run();
	}

	private char[] awaitSeed(XmrWalletManager m, Action a) {
		AtomicReference<String> out = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		observe(m.getSeedReveal(), latch, out);
		main(a);
		await(latch);
		String id = out.get();
		return id == null ? null : m.takePendingSeed(id);
	}

	private XmrReceiveAddress awaitReceive(XmrWalletManager m, Action a) {
		AtomicReference<XmrReceiveAddress> out = new AtomicReference<>();
		AtomicReference<XmrError> err = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		observe(m.getReceiveAddress(), latch, out);
		observe(m.getError(), latch, err);
		main(a);
		await(latch);
		if (err.get() != null) {
			throw new AssertionError("newReceiveAddress error: " + err.get());
		}
		return out.get();
	}

	private String awaitSessionOpen(XmrWalletManager m, Action a) {
		AtomicReference<String> out = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		observe(m.getSessionOpened(), latch, out);
		main(a);
		await(latch);
		return out.get();
	}

	private <T> void observe(androidx.lifecycle.LiveData<Event<T>> ld,
			CountDownLatch latch, AtomicReference<T> out) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Observer<Event<T>> ob = ev -> {
				if (ev == null) return;
				T c = ev.getIfNotHandled();
				if (c != null) {
					out.set(c);
					latch.countDown();
				}
			};
			ld.observeForever(ob);
		});
	}

	private void main(Action a) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(a::run);
	}

	private void await(CountDownLatch latch) {
		try {
			assertTrue("timed out", latch.await(60, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
