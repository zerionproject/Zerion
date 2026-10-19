package org.zerionproject.tor;

import org.bouncycastle.util.encoders.Base64;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.concurrent.Immutable;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@Immutable
@NotNullByDefault
public final class BridgeListKeys {

	public static final String PINNED_KEY =
			"bJJq5mVcoBaEVU3hOWnr+FyR/gPqQKoBk5UbNEYIfN8=";

	private static final int KEY_BYTES = 32;
	private static final Pattern KEY = Pattern.compile("[A-Za-z0-9+/]{43}=");

	private final List<byte[]> pinned;
	private final List<byte[]> localOnly;

	public BridgeListKeys(List<byte[]> pinned, List<byte[]> localOnly) {
		this.pinned = copy(pinned);
		this.localOnly = copy(localOnly);
	}

	public static BridgeListKeys pinnedOnly() {
		return new BridgeListKeys(singletonList(decodeKey(PINNED_KEY)),
				emptyList());
	}

	public static BridgeListKeys withLocalKeys(List<String> localOnlyKeys) {
		List<byte[]> local = new ArrayList<>();
		for (String k : localOnlyKeys) local.add(decodeKey(k));
		return new BridgeListKeys(singletonList(decodeKey(PINNED_KEY)), local);
	}

	public static byte[] decodeKey(String base64) {
		if (!KEY.matcher(base64).matches()) {
			throw new IllegalArgumentException();
		}
		byte[] key = Base64.decode(base64);
		if (key.length != KEY_BYTES) throw new IllegalArgumentException();
		return key;
	}

	public List<byte[]> forNetwork() {
		return copy(pinned);
	}

	public List<byte[]> forLocalFile() {
		List<byte[]> out = copy(pinned);
		out.addAll(copy(localOnly));
		return out;
	}

	public boolean hasLocalKeys() {
		return !localOnly.isEmpty();
	}

	private static List<byte[]> copy(List<byte[]> keys) {
		List<byte[]> out = new ArrayList<>(keys.size());
		for (byte[] k : keys) out.add(k.clone());
		return out;
	}
}
