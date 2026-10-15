package com.professor.zerion.android.vault.ui;

import android.app.Application;
import android.os.Looper;

import com.professor.zerion.android.vault.InMemoryVault;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.net.TorSocksGate;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

final class VaultViewModelRig {

	static final String[] EXECUTORS =
			{"CRYPTO_EXECUTOR", "WALLET_EXECUTOR", "DERIVE_EXECUTOR"};

	final Application app = ApplicationProvider.getApplicationContext();
	final InMemoryVault vault;
	final WalletStore store;
	final TorSocksGate gate = mock(TorSocksGate.class);

	VaultViewModelRig() throws Exception {
		this(new InMemoryVault());
	}

	VaultViewModelRig(InMemoryVault vault) {
		this.vault = vault;
		this.store = new WalletStore(vault.manager);
		when(gate.port()).thenReturn(1);
	}

	VaultViewModel newModel() {
		return new VaultViewModel(app, vault.manager, Runnable::run, store,
				gate);
	}

	static void settle() throws Exception {
		for (int round = 0; round < 4; round++) {
			for (String name : EXECUTORS) {
				Field f = VaultViewModel.class.getDeclaredField(name);
				f.setAccessible(true);
				Executor e = (Executor) f.get(null);
				CountDownLatch done = new CountDownLatch(1);
				e.execute(done::countDown);
				assertTrue(name + " did not drain",
						done.await(120, TimeUnit.SECONDS));
			}
			shadowOf(Looper.getMainLooper()).idle();
		}
	}

	static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.currentTimeMillis() + 120_000;
		while (!condition.getAsBoolean()) {
			assertTrue("condition not reached in time",
					System.currentTimeMillis() < deadline);
			shadowOf(Looper.getMainLooper()).idle();
			Thread.sleep(10);
		}
	}

	JSONObject settings() throws Exception {
		String json = store.readSettings();
		return json == null ? new JSONObject() : new JSONObject(json);
	}

	void writeSettings(JSONObject o) throws Exception {
		store.writeSettings(o.toString());
	}

	String createWallet(VaultViewModel vm, String name) throws Exception {
		vm.createBtcWallet(name, null);
		settle();
		for (WalletRecord r : store.listWallets()) {
			if (r.name.equals(name)) return r.id;
		}
		throw new AssertionError("wallet " + name + " not created");
	}

	String importWallet(VaultViewModel vm, String name, String words)
			throws Exception {
		vm.importBtcWallet(name, words.toCharArray(), null);
		settle();
		for (WalletRecord r : store.listWallets()) {
			if (r.name.equals(name)) return r.id;
		}
		throw new AssertionError("wallet " + name + " not imported");
	}

	void unlockWalletSection(VaultViewModel vm, String credential)
			throws Exception {
		vm.setupWalletAuth("PASSWORD", credential.toCharArray());
		settle();
		assertTrue(vm.walletSessionValid());
	}

	void open(VaultViewModel vm, String walletId) throws Exception {
		vm.openBtcWallet(walletId, null);
		settle();
		await(() -> vm.getWalletOpened().getValue() != null);
	}

	static Object field(Object target, String name) throws Exception {
		Class<?> c = target.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(target);
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	static Object invoke(Object target, String name, Class<?>[] types,
			Object... args) throws Exception {
		Method m = target.getClass().getDeclaredMethod(name, types);
		m.setAccessible(true);
		return m.invoke(target, args);
	}

	List<VaultItem> items() {
		return vault.list();
	}
}
