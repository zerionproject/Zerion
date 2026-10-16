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
	private static final String HOUR_FLOOR = ".floor";

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
					parseStatus(d.getString("status")),
					"member".equals(d.getOptionalString("signer")),
					!Boolean.TRUE.equals(d.getOptionalBoolean("unconfirmed")),
					emptySince(d.getOptionalLong("emptySince")),
					Boolean.TRUE.equals(d.getOptionalBoolean("emptyReset")));
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
		if (app.signedWithMemberKey) d.put("signer", "member");
		if (!app.confirmed) d.put("unconfirmed", true);
		if (app.emptySinceMs >= 0L) d.put("emptySince", app.emptySinceMs);
		if (app.emptyClockReset) d.put("emptyReset", true);
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				encodeBase64(dictToBytes(d)));
		if (app.appliedAtHourMs > getHourFloor(channelId)) {
			out.putLong(ChannelStore.hex(channelId) + HOUR_FLOOR,
					app.appliedAtHourMs);
		}
		settingsManager.mergeSettings(out, NS);
	}

	void remove(byte[] channelId) throws DbException {
		settingsManager.deleteSettings(NS, java.util.Collections
				.singletonList(ChannelStore.hex(channelId)));
	}

	long getHourFloor(byte[] channelId) throws DbException {
		return settingsManager.getSettings(NS).getLong(
				ChannelStore.hex(channelId) + HOUR_FLOOR, -1L);
	}

	void removeAll(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		settingsManager.deleteSettings(NS,
				java.util.Arrays.asList(key, key + HOUR_FLOOR));
	}

	static final class MyApplication {
		final String displayName;
		@Nullable
		final byte[] ephemeralAgreementPriv;
		final byte[] ephemeralAgreementPub;
		final long appliedAtHourMs;
		final ApplicationStatus status;
		final boolean signedWithMemberKey;
		final boolean confirmed;
		final long emptySinceMs;
		final boolean emptyClockReset;

		MyApplication(String displayName,
				@Nullable byte[] ephemeralAgreementPriv,
				byte[] ephemeralAgreementPub, long appliedAtHourMs,
				ApplicationStatus status, boolean signedWithMemberKey) {
			this(displayName, ephemeralAgreementPriv, ephemeralAgreementPub,
					appliedAtHourMs, status, signedWithMemberKey, true);
		}

		MyApplication(String displayName,
				@Nullable byte[] ephemeralAgreementPriv,
				byte[] ephemeralAgreementPub, long appliedAtHourMs,
				ApplicationStatus status, boolean signedWithMemberKey,
				boolean confirmed) {
			this(displayName, ephemeralAgreementPriv, ephemeralAgreementPub,
					appliedAtHourMs, status, signedWithMemberKey, confirmed,
					-1L, false);
		}

		MyApplication(String displayName,
				@Nullable byte[] ephemeralAgreementPriv,
				byte[] ephemeralAgreementPub, long appliedAtHourMs,
				ApplicationStatus status, boolean signedWithMemberKey,
				boolean confirmed, long emptySinceMs,
				boolean emptyClockReset) {
			this.displayName = displayName;
			this.ephemeralAgreementPriv = ephemeralAgreementPriv;
			this.ephemeralAgreementPub = ephemeralAgreementPub;
			this.appliedAtHourMs = appliedAtHourMs;
			this.status = status;
			this.signedWithMemberKey = signedWithMemberKey;
			this.confirmed = confirmed;
			this.emptySinceMs = emptySinceMs;
			this.emptyClockReset = emptyClockReset;
		}

		MyApplication emptySince(long start, boolean clockReset) {
			return new MyApplication(displayName, ephemeralAgreementPriv,
					ephemeralAgreementPub, appliedAtHourMs, status,
					signedWithMemberKey, confirmed, start, clockReset);
		}

		MyApplication confirmed() {
			return new MyApplication(displayName, ephemeralAgreementPriv,
					ephemeralAgreementPub, appliedAtHourMs, status,
					signedWithMemberKey, true, emptySinceMs, emptyClockReset);
		}

		MyApplication approved() {
			return new MyApplication(displayName, null,
					ephemeralAgreementPub, appliedAtHourMs,
					ApplicationStatus.APPROVED, signedWithMemberKey, true);
		}
	}

	private static long emptySince(@Nullable Long at) {
		return at == null || at < 0L ? -1L : at;
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
