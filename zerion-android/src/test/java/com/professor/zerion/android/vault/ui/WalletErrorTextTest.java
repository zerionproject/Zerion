package com.professor.zerion.android.vault.ui;

import android.content.Context;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.wallet.btc.BtcWallet;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WalletErrorTextTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void everyWalletErrorHasATranslatedText() throws Exception {
		int checked = 0;
		for (Field f : BtcWallet.class.getFields()) {
			if (!f.getName().startsWith("ERR_")
					|| !Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			assertTrue(f.getName(),
					VaultViewModel.hasWalletErrorText((String) f.get(null)));
			checked++;
		}
		assertTrue(checked >= 13);
	}

	@Test
	public void aKnownErrorIsShownInTheAppLanguage() {
		Context ctx = RuntimeEnvironment.getApplication();
		assertEquals(ctx.getString(R.string.wallet_err_insufficient),
				VaultViewModel.walletErrorText(ctx,
						new IOException(BtcWallet.ERR_INSUFFICIENT),
						R.string.wallet_send_failed));
		assertEquals(ctx.getString(R.string.wallet_err_invalid_address),
				VaultViewModel.walletErrorText(ctx,
						new IOException(BtcWallet.ERR_INVALID_ADDRESS),
						R.string.wallet_send_failed));
	}

	@Test
	public void anUnknownErrorKeepsItsDetailAfterTheTranslatedText() {
		Context ctx = RuntimeEnvironment.getApplication();
		String detail = "Tor could not reach the server (rep=4)";
		assertEquals(ctx.getString(R.string.wallet_send_failed) + ": " + detail,
				VaultViewModel.walletErrorText(ctx, new IOException(detail),
						R.string.wallet_send_failed));
		assertEquals(ctx.getString(R.string.wallet_network_failed),
				VaultViewModel.walletErrorText(ctx, new IOException(),
						R.string.wallet_network_failed));
	}
}
