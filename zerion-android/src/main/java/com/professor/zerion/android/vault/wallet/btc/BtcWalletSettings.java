package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.wallet.WalletStore;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyStore;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class BtcWalletSettings {

	public interface PrivacyMutation {
		void apply(JSONObject walletPrivacy) throws Exception;
	}

	public static final class CachedPrice {

		public final String ratesJson;
		public final long at;

		CachedPrice(String ratesJson, long at) {
			this.ratesJson = ratesJson;
			this.at = at;
		}
	}

	private static final long PENDING_RETENTION_MS = 7L * 24 * 3600 * 1000;
	private static final String[] WALLET_SECTIONS =
			{"privacy", "recv", "chg", "pending", "btc"};

	private final WalletStore store;

	public BtcWalletSettings(WalletStore store) {
		this.store = store;
	}

	public Object lock() {
		return store.settingsLock;
	}

	public JSONObject read() throws Exception {
		String json = store.readSettings();
		return json == null ? new JSONObject() : new JSONObject(json);
	}

	@Nullable
	public JSONObject readIfPresent() throws Exception {
		String json = store.readSettings();
		return json == null ? null : new JSONObject(json);
	}

	public JSONObject readQuiet() throws Exception {
		String json = store.readSettingsQuiet();
		return json == null ? new JSONObject() : new JSONObject(json);
	}

	public void write(JSONObject o) throws Exception {
		store.writeSettings(o.toString());
	}

	public void writeQuiet(JSONObject o) throws Exception {
		store.writeSettingsQuiet(o.toString());
	}

	public String currency() {
		String cur = "EUR";
		try {
			JSONObject o = readIfPresent();
			if (o != null) {
				cur = o.optString("currency", "EUR");
			}
		} catch (Throwable ignored) {
		}
		return cur;
	}

	public void saveCurrency(String cur) {
		try {
			synchronized (store.settingsLock) {
				JSONObject o = read();
				o.put("currency", cur);
				write(o);
			}
		} catch (Throwable ignored) {
		}
	}

	public CachedPrice cachedPrice() throws Exception {
		JSONObject o = read();
		return new CachedPrice(o.optString("priceRates", ""),
				o.optLong("priceAt", 0));
	}

	public void saveRates(BtcPrice.Rates r) {
		try {
			synchronized (store.settingsLock) {
				JSONObject o = read();
				o.put("priceRates", r.toJson());
				o.put("priceAt", System.currentTimeMillis());
				write(o);
			}
		} catch (Throwable ignored) {
		}
	}

	public int receiveIndex(String walletId) {
		return walletIndex("recv", walletId);
	}

	public int changeIndex(String walletId) {
		return walletIndex("chg", walletId);
	}

	public void raiseReceiveIndex(String walletId, int index) {
		raiseWalletIndex("recv", walletId, index);
	}

	public void raiseChangeIndex(String walletId, int index) {
		raiseWalletIndex("chg", walletId, index);
	}

	private int walletIndex(String section, String walletId) {
		try {
			JSONObject indexes = read().optJSONObject(section);
			if (indexes != null) {
				return Math.max(0, indexes.optInt(walletId, 0));
			}
		} catch (Throwable ignored) {
		}
		return 0;
	}

	private void raiseWalletIndex(String section, String walletId,
			int index) {
		try {
			synchronized (store.settingsLock) {
				JSONObject o = read();
				JSONObject indexes = o.optJSONObject(section);
				if (indexes == null) {
					indexes = new JSONObject();
				}
				if (index > indexes.optInt(walletId, 0)) {
					indexes.put(walletId, index);
					o.put(section, indexes);
					write(o);
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public String displayName(String walletId, String fallback) {
		try {
			JSONObject btc = read().optJSONObject("btc");
			if (btc != null) {
				JSONObject w = btc.optJSONObject(walletId);
				if (w != null) {
					String nm = w.optString("nm", "");
					if (!nm.isEmpty()) {
						return nm;
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return fallback;
	}

	public void saveDisplayName(String walletId, String name)
			throws Exception {
		synchronized (store.settingsLock) {
			JSONObject o = read();
			JSONObject btc = o.optJSONObject("btc");
			if (btc == null) {
				btc = new JSONObject();
			}
			JSONObject w = btc.optJSONObject(walletId);
			if (w == null) {
				w = new JSONObject();
			}
			w.put("nm", name);
			btc.put(walletId, w);
			o.put("btc", btc);
			write(o);
		}
	}

	public JSONObject privacy(String walletId) {
		try {
			JSONObject priv = read().optJSONObject("privacy");
			if (priv != null) {
				JSONObject w = priv.optJSONObject(walletId);
				if (w != null) {
					return w;
				}
			}
		} catch (Throwable ignored) {
		}
		return new JSONObject();
	}

	public void mutatePrivacy(String walletId, PrivacyMutation mutation) {
		try {
			synchronized (store.settingsLock) {
				JSONObject o = read();
				JSONObject priv = o.optJSONObject("privacy");
				if (priv == null) {
					priv = new JSONObject();
				}
				JSONObject w = priv.optJSONObject(walletId);
				if (w == null) {
					w = new JSONObject();
				}
				mutation.apply(w);
				priv.put(walletId, w);
				o.put("privacy", priv);
				write(o);
			}
		} catch (Throwable ignored) {
		}
	}

	public PrivacyStore privacyStore(String walletId) {
		return new PrivacyStore() {
			@Override
			public Set<String> frozen() {
				Set<String> s = new HashSet<>();
				JSONArray a = privacy(walletId).optJSONArray("frozen");
				if (a != null) {
					for (int i = 0; i < a.length(); i++) {
						s.add(a.optString(i));
					}
				}
				return s;
			}

			@Override
			public Map<String, String> labels() {
				return toMap(privacy(walletId).optJSONObject("labels"));
			}

			@Override
			public Map<String, String> originHints() {
				return toMap(privacy(walletId).optJSONObject("originHints"));
			}

			@Override
			public void setFrozen(String outpoint, boolean frozen) {
				mutatePrivacy(walletId, w -> {
					JSONArray a = w.optJSONArray("frozen");
					JSONArray next = new JSONArray();
					if (a != null) {
						for (int i = 0; i < a.length(); i++) {
							if (!a.optString(i).equals(outpoint)) {
								next.put(a.optString(i));
							}
						}
					}
					if (frozen) {
						next.put(outpoint);
					}
					w.put("frozen", next);
				});
			}

			@Override
			public void setLabel(String outpoint, @Nullable String label) {
				mutatePrivacy(walletId, w -> {
					JSONObject labels = w.optJSONObject("labels");
					if (labels == null) {
						labels = new JSONObject();
					}
					if (label == null || label.isEmpty()) {
						labels.remove(outpoint);
					} else {
						labels.put(outpoint, label);
					}
					w.put("labels", labels);
				});
			}

			@Override
			public void putOriginHint(String address, String clusterId) {
				mutatePrivacy(walletId, w -> {
					JSONObject hints = w.optJSONObject("originHints");
					if (hints == null) {
						hints = new JSONObject();
					}
					hints.put(address, clusterId);
					w.put("originHints", hints);
				});
			}
		};
	}

	private static Map<String, String> toMap(@Nullable JSONObject o) {
		Map<String, String> m = new HashMap<>();
		if (o != null) {
			Iterator<String> it = o.keys();
			while (it.hasNext()) {
				String k = it.next();
				m.put(k, o.optString(k));
			}
		}
		return m;
	}

	private static JSONObject pendingToJson(PendingTx tx)
			throws JSONException {
		JSONObject r = new JSONObject();
		r.put("id", tx.id);
		r.put("txid", tx.txid);
		r.put("rawHex", tx.rawHex);
		r.put("state", tx.state);
		r.put("createdAt", tx.createdAt);
		r.put("netSat", tx.netSat);
		JSONArray ops = new JSONArray();
		for (String op : tx.outpoints) {
			ops.put(op);
		}
		r.put("outpoints", ops);
		return r;
	}

	public PendingLog pendingLog(String walletId) {
		return new PendingLog() {
			@Override
			public List<PendingTx> all() {
				List<PendingTx> out = new ArrayList<>();
				try {
					JSONObject byWallet = read().optJSONObject("pending");
					if (byWallet == null) {
						return out;
					}
					JSONArray arr = byWallet.optJSONArray(walletId);
					if (arr == null) {
						return out;
					}
					for (int i = 0; i < arr.length(); i++) {
						JSONObject r = arr.getJSONObject(i);
						List<String> ops = new ArrayList<>();
						JSONArray oa = r.optJSONArray("outpoints");
						if (oa != null) {
							for (int j = 0; j < oa.length(); j++) {
								ops.add(oa.getString(j));
							}
						}
						out.add(new PendingTx(r.optString("id"),
								r.optString("txid"), r.optString("rawHex"), ops,
								r.optString("state"), r.optLong("createdAt"),
								r.optLong("netSat")));
					}
				} catch (Throwable ignored) {
				}
				return out;
			}

			@Override
			public void put(PendingTx tx) {
				try {
					synchronized (store.settingsLock) {
						JSONObject o = read();
						JSONObject byWallet = o.optJSONObject("pending");
						if (byWallet == null) {
							byWallet = new JSONObject();
						}
						JSONArray arr = byWallet.optJSONArray(walletId);
						JSONArray next = new JSONArray();
						long cutoff = System.currentTimeMillis()
								- PENDING_RETENTION_MS;
						boolean replaced = false;
						if (arr != null) {
							for (int i = 0; i < arr.length(); i++) {
								JSONObject r = arr.getJSONObject(i);
								if (tx.id.equals(r.optString("id"))) {
									next.put(pendingToJson(tx));
									replaced = true;
									continue;
								}
								String st = r.optString("state");
								boolean resolved = PendingTx.SETTLED.equals(st)
										|| "failed".equals(st);
								if (resolved && r.optLong("createdAt") < cutoff) {
									continue;
								}
								next.put(r);
							}
						}
						if (!replaced) {
							next.put(pendingToJson(tx));
						}
						byWallet.put(walletId, next);
						o.put("pending", byWallet);
						write(o);
					}
				} catch (Throwable e) {
					throw new RuntimeException("pending persist failed", e);
				}
			}
		};
	}

	public void moveWalletRecords(String oldId, String newId)
			throws Exception {
		synchronized (store.settingsLock) {
			JSONObject o = read();
			boolean changed = false;
			for (String section : WALLET_SECTIONS) {
				JSONObject sub = o.optJSONObject(section);
				if (sub != null && sub.has(oldId)) {
					sub.put(newId, sub.get(oldId));
					sub.remove(oldId);
					o.put(section, sub);
					changed = true;
				}
			}
			if (changed) {
				write(o);
			}
		}
	}

	public void removeWalletRecords(String walletId) {
		try {
			synchronized (store.settingsLock) {
				JSONObject o = read();
				boolean changed = false;
				for (String section : WALLET_SECTIONS) {
					JSONObject sub = o.optJSONObject(section);
					if (sub != null && sub.has(walletId)) {
						sub.remove(walletId);
						o.put(section, sub);
						changed = true;
					}
				}
				if (changed) {
					write(o);
				}
			}
		} catch (Throwable ignored) {
		}
	}
}
