package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONObject;

/**
 * JSON-backed {@link XmrSubaddressLedger.Store} persisted in the encrypted vault
 * settings at {@code xmr.<walletId>.recv}. All access is serialized on the
 * store's settings monitor. Reads the whole settings blob and writes it back on
 * each mutation; the receive state is small, and writing on the reservation is
 * the crash-safe commit point.
 */
@NotNullByDefault
public final class XmrReceiveJsonStore implements XmrSubaddressLedger.Store {

	private final XmrStore store;
	private final String walletId;

	public XmrReceiveJsonStore(XmrStore store, String walletId) {
		this.store = store;
		this.walletId = walletId;
	}

	private JSONObject root() throws Exception {
		String json = store.readSettings();
		return json == null ? new JSONObject() : new JSONObject(json);
	}

	private JSONObject recv(JSONObject root) {
		JSONObject xmr = root.optJSONObject("xmr");
		if (xmr == null) return new JSONObject();
		JSONObject w = xmr.optJSONObject(walletId);
		if (w == null) return new JSONObject();
		JSONObject r = w.optJSONObject("recv");
		return r == null ? new JSONObject() : r;
	}

	private void write(JSONObject recv) throws Exception {
		JSONObject root = root();
		JSONObject xmr = root.optJSONObject("xmr");
		if (xmr == null) xmr = new JSONObject();
		JSONObject w = xmr.optJSONObject(walletId);
		if (w == null) w = new JSONObject();
		w.put("recv", recv);
		xmr.put(walletId, w);
		root.put("xmr", xmr);
		store.writeSettings(root.toString());
	}

	@Override
	public int getIssued() throws Exception {
		synchronized (store.settingsMonitor()) {
			return recv(root()).optInt("issued", 0);
		}
	}

	@Override
	public void setIssued(int index) throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject r = recv(root());
			r.put("issued", index);
			write(r);
		}
	}

	@Override
	public int reserveNextIndex() throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject r = recv(root());
			int next = Math.max(0, r.optInt("issued", 0)) + 1;
			r.put("issued", next);
			write(r);
			return next;
		}
	}

	@Nullable
	@Override
	public String getAddress(int index) throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject a = recv(root()).optJSONObject("addrs");
			if (a == null) return null;
			String v = a.optString(Integer.toString(index), "");
			return v.isEmpty() ? null : v;
		}
	}

	@Override
	public void putAddress(int index, String address) throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject r = recv(root());
			JSONObject a = r.optJSONObject("addrs");
			if (a == null) a = new JSONObject();
			a.put(Integer.toString(index), address);
			r.put("addrs", a);
			write(r);
		}
	}

	@Override
	public void putAddresses(java.util.Map<Integer, String> addresses)
			throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject r = recv(root());
			JSONObject a = r.optJSONObject("addrs");
			if (a == null) a = new JSONObject();
			for (java.util.Map.Entry<Integer, String> e : addresses.entrySet()) {
				a.put(Integer.toString(e.getKey()), e.getValue());
			}
			r.put("addrs", a);
			write(r);
		}
	}

	@Nullable
	@Override
	public String getLabel(int index) throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject l = recv(root()).optJSONObject("labels");
			if (l == null) return null;
			String v = l.optString(Integer.toString(index), "");
			return v.isEmpty() ? null : v;
		}
	}

	@Override
	public void putLabel(int index, @Nullable String label) throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject r = recv(root());
			JSONObject l = r.optJSONObject("labels");
			if (l == null) l = new JSONObject();
			if (label == null || label.isEmpty()) {
				l.remove(Integer.toString(index));
			} else {
				l.put(Integer.toString(index), label);
			}
			r.put("labels", l);
			write(r);
		}
	}

	@Override
	public long getDate(int index) throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject d = recv(root()).optJSONObject("dates");
			if (d == null) return 0;
			return d.optLong(Integer.toString(index), 0);
		}
	}

	@Override
	public void putDate(int index, long millis) throws Exception {
		synchronized (store.settingsMonitor()) {
			JSONObject r = recv(root());
			JSONObject d = r.optJSONObject("dates");
			if (d == null) d = new JSONObject();
			d.put(Integer.toString(index), millis);
			r.put("dates", d);
			write(r);
		}
	}
}
