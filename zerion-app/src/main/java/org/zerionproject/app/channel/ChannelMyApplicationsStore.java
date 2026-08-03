package org.zerionproject.app.channel;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.app.api.channel.ApplicationStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelMyApplicationsStore {

	private static final String NS = "zerion-channels-my-applications";

	private final SettingsManager settingsManager;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	@Inject
	ChannelMyApplicationsStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	@Nullable
	MyApplication get(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String encoded = s.get(ChannelStore.hex(channelId));
		if (encoded == null) return null;
		try {
			BdfDictionary d = bytesToDict(decodeBase64(encoded));
			return new MyApplication(
					d.getString("name"),
					d.getOptionalRaw("ephPriv"),
					d.getRaw("ephPub"),
					d.getLong("ts"),
					parseStatus(d.getString("status")));
		} catch (IOException e) {
			return null;
		}
	}

	void put(byte[] channelId, MyApplication app) throws DbException {
		BdfDictionary d = new BdfDictionary();
		d.put("name", app.displayName);
		if (app.ephemeralAgreementPriv != null) {
			d.put("ephPriv", app.ephemeralAgreementPriv);
		}
		d.put("ephPub", app.ephemeralAgreementPub);
		d.put("ts", app.appliedAtHourMs);
		d.put("status", app.status.name());
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				encodeBase64(dictToBytes(d)));
		settingsManager.mergeSettings(out, NS);
	}

	void remove(byte[] channelId) throws DbException {
		Settings cur = settingsManager.getSettings(NS);
		String key = ChannelStore.hex(channelId);
		if (!cur.containsKey(key)) return;
		Settings out = new Settings();
		out.put(key, "");
		settingsManager.mergeSettings(out, NS);
	}

	static final class MyApplication {
		final String displayName;
		@Nullable
		final byte[] ephemeralAgreementPriv;
		final byte[] ephemeralAgreementPub;
		final long appliedAtHourMs;
		final ApplicationStatus status;

		MyApplication(String displayName,
				@Nullable byte[] ephemeralAgreementPriv,
				byte[] ephemeralAgreementPub, long appliedAtHourMs,
				ApplicationStatus status) {
			this.displayName = displayName;
			this.ephemeralAgreementPriv = ephemeralAgreementPriv;
			this.ephemeralAgreementPub = ephemeralAgreementPub;
			this.appliedAtHourMs = appliedAtHourMs;
			this.status = status;
		}
	}

	private static ApplicationStatus parseStatus(String s) {
		try {
			return ApplicationStatus.valueOf(s);
		} catch (IllegalArgumentException e) {
			return ApplicationStatus.PENDING;
		}
	}

	private byte[] dictToBytes(BdfDictionary d) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeDictionary(d);
			w.flush();
		} catch (IOException e) {
			return new byte[0];
		}
		return out.toByteArray();
	}

	private BdfDictionary bytesToDict(byte[] bytes)
			throws FormatException, IOException {
		BdfReader r = readerFactory.createReader(
				new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}

	private static String encodeBase64(byte[] data) {
		return java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(data);
	}

	private static byte[] decodeBase64(String s) {
		return java.util.Base64.getDecoder().decode(s);
	}
}
