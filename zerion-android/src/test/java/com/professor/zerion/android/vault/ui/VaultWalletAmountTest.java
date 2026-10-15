package com.professor.zerion.android.vault.ui;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.wallet.btc.BtcPrice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultWalletAmountTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final VaultWalletFragment fragment = new VaultWalletFragment();

	private void rates(@Nullable String json, String currency)
			throws Exception {
		Field r = VaultWalletFragment.class.getDeclaredField("rates");
		r.setAccessible(true);
		r.set(fragment, json == null ? null : BtcPrice.Rates.fromJson(json));
		Field c = VaultWalletFragment.class.getDeclaredField("fiatCurrency");
		c.setAccessible(true);
		c.set(fragment, currency);
	}

	private Object call(String name, Class<?>[] types, Object... args)
			throws Exception {
		Method m = VaultWalletFragment.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		return m.invoke(fragment, args);
	}

	private long sats(int unit, String typed) throws Exception {
		return (Long) call("computeSats", new Class<?>[] {int.class,
				String.class}, unit, typed);
	}

	private String equiv(int unit, String typed) throws Exception {
		return (String) call("sendEquivText", new Class<?>[] {int.class,
				String.class}, unit, typed);
	}

	private String btc(long sat) throws Exception {
		return (String) call("formatBtc", new Class<?>[] {long.class}, sat);
	}

	private String symbol(String cur) throws Exception {
		return (String) call("symbolFor", new Class<?>[] {String.class}, cur);
	}

	private String unit(int u) throws Exception {
		return (String) call("unitName", new Class<?>[] {int.class}, u);
	}

	@Test
	public void bitcoinAmountsParseToSatoshis() throws Exception {
		rates(null, "EUR");
		assertEquals(150_000_000L, sats(0, "1.5"));
		assertEquals(1L, sats(0, "0.00000001"));
		assertEquals(-1L, sats(0, "abc"));
		assertEquals(-1L, sats(1, "10"));
		assertEquals(-1L, sats(2, "10"));
	}

	@Test
	public void fiatAmountsConvertAtTheChosenRateAndRoundDown()
			throws Exception {
		rates("{\"EUR\":50000.0,\"USD\":40000.0,\"GBP\":45000.0}", "GBP");
		assertEquals(20_000L, sats(1, "10"));
		assertEquals(25_000L, sats(2, "10"));
		assertEquals(33L, sats(1, "0.0167"));
		assertEquals(-1L, sats(1, "-5"));
		assertEquals(-1L, sats(2, "ten"));
		assertEquals(20_000L, sats(1, " 10 "));
	}

	@Test
	public void theEquivalentLineUsesTheDisplayCurrency() throws Exception {
		rates("{\"EUR\":50000.0,\"USD\":40000.0,\"GBP\":45000.0}", "GBP");
		assertEquals("≈ £22500.00", equiv(0, "0.5"));
		assertEquals("≈ 0.00020000 BTC", equiv(1, "10"));
		assertEquals("≈ 0.00025000 BTC", equiv(2, "10"));
		assertEquals("", equiv(0, " "));
		assertEquals("", equiv(0, "x"));
		rates(null, "EUR");
		assertEquals("", equiv(0, "1"));
		assertEquals("", equiv(1, "1"));
	}

	@Test
	public void bitcoinIsFormattedWithoutTrailingZeros() throws Exception {
		assertEquals("0 BTC", btc(0));
		assertEquals("1 BTC", btc(100_000_000L));
		assertEquals("0.00000001 BTC", btc(1));
		assertEquals("0.0015 BTC", btc(150_000));
		assertEquals("-0.5 BTC", btc(-50_000_000L));
		assertEquals("21000000 BTC", btc(2_100_000_000_000_000L));
	}

	@Test
	public void currencySymbolsAndUnitNames() throws Exception {
		assertEquals("$", symbol("USD"));
		assertEquals("£", symbol("GBP"));
		assertEquals("¥", symbol("JPY"));
		assertEquals("CA$", symbol("CAD"));
		assertEquals("A$", symbol("AUD"));
		assertEquals("CHF ", symbol("CHF"));
		assertEquals("€", symbol("EUR"));
		assertEquals("SEK ", symbol("SEK"));
		assertEquals("BTC", unit(0));
		assertEquals("EUR", unit(1));
		assertEquals("USD", unit(2));
		assertEquals("BTC", unit(7));
	}
}
