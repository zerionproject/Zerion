package com.professor.zerion.android.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
public class VoiceCallKeyHolder {

	public static final long OFFER_LIFETIME_MS = 90_000;

	static final int MAX_PENDING_OFFERS = 8;

	private static final class Offer {

		private final String callId;
		private final long receivedAt;
		@Nullable
		private SecretKey key;
		@Nullable
		private byte[] remoteEphemeral;
		@Nullable
		private byte[] agreementKey;

		private Offer(String callId, long receivedAt, SecretKey key,
				@Nullable byte[] remoteEphemeral,
				@Nullable byte[] agreementKey) {
			this.callId = callId;
			this.receivedAt = receivedAt;
			this.key = key;
			this.remoteEphemeral = remoteEphemeral;
			this.agreementKey = agreementKey;
		}

		private void wipe() {
			if (key != null) key.clear();
			if (remoteEphemeral != null) {
				Arrays.fill(remoteEphemeral, (byte) 0);
			}
			key = null;
			remoteEphemeral = null;
			agreementKey = null;
		}
	}

	private static final Map<Integer, Offer> offers = new LinkedHashMap<>();

	private static String normalise(@Nullable String callId) {
		return callId == null ? "" : callId;
	}

	@Nullable
	private static Offer find(int contactId, @Nullable String callId) {
		purgeExpired(System.currentTimeMillis());
		Offer o = offers.get(contactId);
		if (o == null || !o.callId.equals(normalise(callId))) return null;
		return o;
	}

	public static synchronized void setOffer(int contactId,
			@Nullable String callId, SecretKey key,
			@Nullable byte[] remoteEphemeral) {
		setOffer(contactId, callId, key, remoteEphemeral, null);
	}

	static synchronized Object setOffer(int contactId,
			@Nullable String callId, SecretKey key,
			@Nullable byte[] remoteEphemeral, @Nullable byte[] agreementKey) {
		long now = System.currentTimeMillis();
		purgeExpired(now);
		Offer previous = offers.remove(contactId);
		if (previous != null) previous.wipe();
		Offer held = new Offer(normalise(callId), now, key, remoteEphemeral,
				agreementKey);
		offers.put(contactId, held);
		Iterator<Offer> it = offers.values().iterator();
		while (offers.size() > MAX_PENDING_OFFERS && it.hasNext()) {
			it.next().wipe();
			it.remove();
		}
		return held;
	}

	public static void holdOffer(int contactId, @Nullable String callId,
			SecretKey key, @Nullable byte[] remoteEphemeral,
			@Nullable byte[] agreementKey, android.os.Handler handler) {
		Object token = setOffer(contactId, callId, key, remoteEphemeral,
				agreementKey);
		handler.postDelayed(() -> expireOffer(contactId, token),
				OFFER_LIFETIME_MS);
	}

	public static boolean holdOfferPayload(int contactId,
			@Nullable String callId, @Nullable String payload,
			android.os.Handler handler) {
		CallSignalPayloads.Offer offer =
				CallSignalPayloads.parseOffer(payload);
		if (offer == null) {
			clearContact(contactId);
			return false;
		}
		holdOffer(contactId, callId, new SecretKey(offer.key),
				offer.contribution, offer.agreementKey, handler);
		return true;
	}

	static synchronized void expireOffer(int contactId, Object token) {
		Offer o = offers.get(contactId);
		if (o != null && o == token) {
			o.wipe();
			offers.remove(contactId);
		}
	}

	static synchronized boolean isHeld(int contactId,
			@Nullable String callId) {
		Offer o = offers.get(contactId);
		return o != null && o.callId.equals(normalise(callId))
				&& o.key != null;
	}

	@Nullable
	public static synchronized SecretKey consumeKey(int contactId,
			@Nullable String callId) {
		Offer o = find(contactId, callId);
		if (o == null) return null;
		SecretKey key = o.key;
		o.key = null;
		return key;
	}

	@Nullable
	public static synchronized byte[] consumeRemoteEphemeral(int contactId,
			@Nullable String callId) {
		Offer o = find(contactId, callId);
		if (o == null) return null;
		byte[] eph = o.remoteEphemeral;
		o.remoteEphemeral = null;
		return eph;
	}

	@Nullable
	public static synchronized byte[] consumeAgreementKey(int contactId,
			@Nullable String callId) {
		Offer o = find(contactId, callId);
		if (o == null) return null;
		byte[] k = o.agreementKey;
		o.agreementKey = null;
		return k;
	}

	public static synchronized void clearFor(int contactId,
			@Nullable String callId) {
		Offer o = offers.get(contactId);
		if (o != null && o.callId.equals(normalise(callId))) {
			o.wipe();
			offers.remove(contactId);
		}
	}

	public static synchronized void clearContact(int contactId) {
		Offer o = offers.remove(contactId);
		if (o != null) o.wipe();
	}

	public static synchronized void clear() {
		for (Offer o : offers.values()) o.wipe();
		offers.clear();
	}

	public static void expireOffers() {
		purgeExpired(System.currentTimeMillis());
	}

	static synchronized void purgeExpired(long now) {
		Iterator<Offer> it = offers.values().iterator();
		while (it.hasNext()) {
			Offer o = it.next();
			if (now - o.receivedAt > OFFER_LIFETIME_MS
					|| now < o.receivedAt) {
				o.wipe();
				it.remove();
			}
		}
	}
}
