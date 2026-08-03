package org.zerionproject.app.channel;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.app.api.channel.ChannelApplication;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelApplicationStore {

	private static final String NS = "zerion-channels-applications";

	private final SettingsManager settingsManager;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	@Inject
	ChannelApplicationStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	List<ChannelApplication> getApplications(byte[] channelId)
			throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String encoded = s.get(ChannelStore.hex(channelId));
		if (encoded == null) return new ArrayList<>();
		try {
			BdfList list = bytesToList(decodeBase64(encoded));
			List<ChannelApplication> out = new ArrayList<>(list.size());
			for (Object o : list) {
				if (!(o instanceof BdfDictionary)) continue;
				BdfDictionary d = (BdfDictionary) o;
				out.add(new ChannelApplication(
						d.getString("name"),
						d.getRaw("ed"),
						d.getRaw("ml"),
						d.getRaw("eph"),
						d.getLong("ts"),
						parseStatus(d.getString("status")),
						d.getOptionalRaw("kem"),
						d.getOptionalRaw("env")));
			}
			return out;
		} catch (IOException e) {
			return new ArrayList<>();
		}
	}

	@Nullable
	ChannelApplication findByApplicant(byte[] channelId,
			byte[] applicantEd) throws DbException {
		for (ChannelApplication a : getApplications(channelId)) {
			if (Arrays.equals(a.getApplicantEd25519(), applicantEd)) {
				return a;
			}
		}
		return null;
	}

	void putApplication(byte[] channelId, ChannelApplication app)
			throws DbException {
		List<ChannelApplication> existing = getApplications(channelId);
		List<ChannelApplication> out = new ArrayList<>(existing.size() + 1);
		boolean replaced = false;
		for (ChannelApplication a : existing) {
			if (Arrays.equals(a.getApplicantEd25519(),
					app.getApplicantEd25519())) {
				out.add(app);
				replaced = true;
			} else {
				out.add(a);
			}
		}
		if (!replaced) out.add(app);
		write(channelId, out);
	}

	void removeApplication(byte[] channelId, byte[] applicantEd)
			throws DbException {
		List<ChannelApplication> existing = getApplications(channelId);
		List<ChannelApplication> out = new ArrayList<>(existing.size());
		for (ChannelApplication a : existing) {
			if (!Arrays.equals(a.getApplicantEd25519(), applicantEd)) {
				out.add(a);
			}
		}
		write(channelId, out);
	}

	void removeAll(byte[] channelId) throws DbException {
		write(channelId, new ArrayList<>());
	}

	private void write(byte[] channelId, List<ChannelApplication> apps)
			throws DbException {
		BdfList list = new BdfList();
		for (ChannelApplication a : apps) {
			BdfDictionary d = new BdfDictionary();
			d.put("name", a.getDisplayName());
			d.put("ed", a.getApplicantEd25519());
			d.put("ml", a.getApplicantMlDsa());
			d.put("eph", a.getApplicantEphemeralAgreementPub());
			d.put("ts", a.getAppliedAtHourMs());
			d.put("status", a.getStatus().name());
			if (a.getKemCiphertext() != null) {
				d.put("kem", a.getKemCiphertext());
			}
			if (a.getEnvelope() != null) {
				d.put("env", a.getEnvelope());
			}
			list.add(d);
		}
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				encodeBase64(listToBytes(list)));
		settingsManager.mergeSettings(out, NS);
	}

	private static ChannelApplication.Status parseStatus(String s) {
		try {
			return ChannelApplication.Status.valueOf(s);
		} catch (IllegalArgumentException e) {
			return ChannelApplication.Status.PENDING;
		}
	}

	private byte[] listToBytes(BdfList l) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeList(l);
			w.flush();
		} catch (IOException e) {
			return new byte[0];
		}
		return out.toByteArray();
	}

	private BdfList bytesToList(byte[] bytes)
			throws FormatException, IOException {
		BdfReader r = readerFactory.createReader(
				new ByteArrayInputStream(bytes));
		return r.readList();
	}

	private static String encodeBase64(byte[] data) {
		return java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(data);
	}

	private static byte[] decodeBase64(String s) {
		return java.util.Base64.getDecoder().decode(s);
	}
}
