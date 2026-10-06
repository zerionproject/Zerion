package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
final class CallSignalPayloads {

	static final String AGREEMENT_PREFIX = "XK1:";
	static final String VIDEO_FLAG = "VIDEO";
	static final int KEY_BYTES = 32;

	private CallSignalPayloads() {
	}

	static final class Offer {

		final byte[] key;
		@Nullable
		final byte[] contribution;
		@Nullable
		final byte[] agreementKey;
		final boolean video;

		private Offer(byte[] key, @Nullable byte[] contribution,
				@Nullable byte[] agreementKey, boolean video) {
			this.key = key;
			this.contribution = contribution;
			this.agreementKey = agreementKey;
			this.video = video;
		}

		void wipe() {
			Arrays.fill(key, (byte) 0);
			if (contribution != null) Arrays.fill(contribution, (byte) 0);
		}
	}

	static final class Answer {

		final String onion;
		final int port;
		@Nullable
		final byte[] contribution;
		@Nullable
		final byte[] agreementKey;

		private Answer(String onion, int port, @Nullable byte[] contribution,
				@Nullable byte[] agreementKey) {
			this.onion = onion;
			this.port = port;
			this.contribution = contribution;
			this.agreementKey = agreementKey;
		}
	}

	static String formatOffer(String keyHex, @Nullable byte[] contribution,
			@Nullable byte[] agreementKey, boolean video) {
		StringBuilder sb = new StringBuilder(keyHex);
		if (contribution != null) {
			sb.append('|').append(CallHex.bytesToHex(contribution));
		}
		if (agreementKey != null) {
			sb.append('|').append(AGREEMENT_PREFIX)
					.append(CallHex.bytesToHex(agreementKey));
		}
		if (video) sb.append('|').append(VIDEO_FLAG);
		return sb.toString();
	}

	@Nullable
	static Offer parseOffer(@Nullable String payload) {
		if (payload == null) return null;
		String[] parts = payload.split("\\|", -1);
		byte[] key = hex32(parts[0]);
		if (key == null) return null;
		boolean video = parts.length >= 2
				&& VIDEO_FLAG.equals(parts[parts.length - 1]);
		int end = video ? parts.length - 1 : parts.length;
		byte[] contribution = null;
		byte[] agreementKey = null;
		for (int i = 1; i < end; i++) {
			String p = parts[i];
			if (p.startsWith(AGREEMENT_PREFIX)) {
				if (agreementKey != null) return refuse(key, contribution);
				agreementKey = hex32(p.substring(AGREEMENT_PREFIX.length()));
				if (agreementKey == null) return refuse(key, contribution);
			} else if (i == 1) {
				contribution = hex32(p);
				if (contribution == null) return refuse(key, null);
			}
		}
		return new Offer(key, contribution, agreementKey, video);
	}

	@Nullable
	private static Offer refuse(byte[] key, @Nullable byte[] contribution) {
		Arrays.fill(key, (byte) 0);
		if (contribution != null) Arrays.fill(contribution, (byte) 0);
		return null;
	}

	static String formatAnswer(String onion, int port,
			@Nullable byte[] contribution, @Nullable byte[] agreementKey) {
		StringBuilder sb = new StringBuilder(onion).append(':').append(port);
		if (contribution != null) {
			sb.append('|').append(CallHex.bytesToHex(contribution));
			if (agreementKey != null) {
				sb.append('|').append(AGREEMENT_PREFIX)
						.append(CallHex.bytesToHex(agreementKey));
			}
		}
		return sb.toString();
	}

	@Nullable
	static Answer parseAnswer(@Nullable String payload) {
		if (payload == null) return null;
		int bar = payload.indexOf('|');
		String connection = bar < 0 ? payload : payload.substring(0, bar);
		String[] hostPort = connection.split(":");
		if (hostPort.length != 2 || hostPort[0].isEmpty()) return null;
		int port;
		try {
			port = Integer.parseInt(hostPort[1]);
		} catch (NumberFormatException e) {
			return null;
		}
		if (port < 1 || port > 65535) return null;
		byte[] contribution = null;
		byte[] agreementKey = null;
		if (bar >= 0) {
			String[] rest = payload.substring(bar + 1).split("\\|", -1);
			contribution = hex32(rest[0]);
			if (contribution == null) return null;
			for (int i = 1; i < rest.length; i++) {
				String p = rest[i];
				if (!p.startsWith(AGREEMENT_PREFIX) || agreementKey != null) {
					return null;
				}
				agreementKey = hex32(p.substring(AGREEMENT_PREFIX.length()));
				if (agreementKey == null) return null;
			}
		}
		return new Answer(hostPort[0], port, contribution, agreementKey);
	}

	@Nullable
	private static byte[] hex32(String hex) {
		if (hex.length() != KEY_BYTES * 2) return null;
		try {
			return CallHex.hexToBytes(hex);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
