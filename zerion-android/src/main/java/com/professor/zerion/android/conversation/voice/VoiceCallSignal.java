package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
@NotNullByDefault
public class VoiceCallSignal {
	private static final String SIGNAL_PREFIX = "\u0000ZSIG\u0001\u0000";

	public static final String WIRE_PREFIX = SIGNAL_PREFIX;

	private static final int MAX_PAYLOAD_LENGTH = 2048;
	private static final int MAX_REASON_LENGTH = 128;

	private static final long TIMESTAMP_WINDOW_MS = 60 * 1000;

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final int HMAC_OUTPUT_LENGTH = 32;

	public enum SignalType {
		CALL_OFFER("offer"),
		CALL_ANSWER("answer"),
		CALL_REJECT("reject"),
		CALL_END("end");

		private final String wireValue;

		SignalType(String wireValue) {
			this.wireValue = wireValue;
		}

		public String getWireValue() {
			return wireValue;
		}

		@Nullable
		public static SignalType fromWireValue(String value) {
			for (SignalType type : values()) {
				if (type.wireValue.equals(value)) {
					return type;
				}
			}
			return null;
		}
	}
	private static final Pattern UUID_PATTERN = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
	private static final Pattern BASE64_PATTERN = Pattern.compile(
			"^[A-Za-z0-9+/=]{32,256}$"); // Min 32, max 256 chars
	private static final Pattern ONION_PATTERN = Pattern.compile(
			"^[a-z2-7]{56}\\.onion$");
	private final SignalType type;
	private final String callId;
	private final long timestamp;
	@Nullable private final String voiceCallKey;
	@Nullable private final String onionAddress;
	@Nullable private final Integer onionPort;
	@Nullable private final String reason;

	private VoiceCallSignal(SignalType type, String callId, long timestamp,
			@Nullable String voiceCallKey, @Nullable String onionAddress,
			@Nullable Integer onionPort, @Nullable String reason) {
		this.type = type;
		this.callId = callId;
		this.timestamp = timestamp;
		this.voiceCallKey = voiceCallKey;
		this.onionAddress = onionAddress;
		this.onionPort = onionPort;
		this.reason = reason;
	}
	public SignalType getType() { return type; }
	public String getCallId() { return callId; }
	public long getTimestamp() { return timestamp; }
	@Nullable public String getVoiceCallKey() { return voiceCallKey; }
	@Nullable public String getOnionAddress() { return onionAddress; }
	@Nullable public Integer getOnionPort() { return onionPort; }
	@Nullable public String getReason() { return reason; }

	
	public static VoiceCallSignal createOffer(String callId, String voiceCallKey) {
		validateCallId(callId);
		validateVoiceCallKey(voiceCallKey);
		return new VoiceCallSignal(SignalType.CALL_OFFER, callId,
				System.currentTimeMillis(), voiceCallKey, null, null, null);
	}

	
	public static VoiceCallSignal createAnswer(String callId, String onionAddress, int onionPort) {
		validateCallId(callId);
		validateOnionAddress(onionAddress);
		validatePort(onionPort);
		return new VoiceCallSignal(SignalType.CALL_ANSWER, callId,
				System.currentTimeMillis(), null, onionAddress, onionPort, null);
	}

	
	public static VoiceCallSignal createReject(String callId, @Nullable String reason) {
		validateCallId(callId);
		if (reason != null && reason.length() > MAX_REASON_LENGTH) {
			reason = reason.substring(0, MAX_REASON_LENGTH);
		}
		return new VoiceCallSignal(SignalType.CALL_REJECT, callId,
				System.currentTimeMillis(), null, null, null, reason);
	}

	
	public static VoiceCallSignal createEnd(String callId, @Nullable String reason) {
		validateCallId(callId);
		if (reason != null && reason.length() > MAX_REASON_LENGTH) {
			reason = reason.substring(0, MAX_REASON_LENGTH);
		}
		return new VoiceCallSignal(SignalType.CALL_END, callId,
				System.currentTimeMillis(), null, null, null, reason);
	}

	
	public String toWireFormat(byte[] hmacKey) {
		String jsonStr = toCanonicalJson();
		String hmac = computeHmac(jsonStr, hmacKey);
		return WIRE_PREFIX + jsonStr + ":" + hmac;
	}

	
	public String toWireFormat() {
		byte[] hmacKey;
		if (voiceCallKey != null) {
			hmacKey = voiceCallKey.getBytes(StandardCharsets.UTF_8);
		} else {
			hmacKey = callId.getBytes(StandardCharsets.UTF_8);
		}
		return toWireFormat(hmacKey);
	}

	
	private String toCanonicalJson() {
		StringBuilder json = new StringBuilder();
		json.append("{");
		json.append("\"c\":\"").append(escapeJson(callId)).append("\",");
		json.append("\"t\":\"").append(type.getWireValue()).append("\",");
		json.append("\"ts\":").append(timestamp);
		if (voiceCallKey != null) {
			json.append(",\"k\":\"").append(escapeJson(voiceCallKey)).append("\"");
		}
		if (onionAddress != null) {
			json.append(",\"o\":\"").append(escapeJson(onionAddress)).append("\"");
		}
		if (onionPort != null) {
			json.append(",\"p\":").append(onionPort);
		}
		if (reason != null) {
			json.append(",\"r\":\"").append(escapeJson(reason)).append("\"");
		}

		json.append("}");
		return json.toString();
	}

	
	public static boolean isSignal(String message) {
		if (message == null) {
			return false;
		}
		int minLength = WIRE_PREFIX.length() + 20;
		if (message.length() < minLength || message.length() > MAX_PAYLOAD_LENGTH) {
			return false;
		}
		return message.startsWith(WIRE_PREFIX);
	}

	
	@Nullable
	public static VoiceCallSignal fromWireFormat(String wireMessage, byte[] hmacKey) {
		if (!isSignal(wireMessage)) {
			return null;
		}

		try {
			String payload = wireMessage.substring(WIRE_PREFIX.length());
			int lastColon = payload.lastIndexOf(':');
			if (lastColon < 0 || lastColon == payload.length() - 1) {
				return null;
			}

			String jsonStr = payload.substring(0, lastColon);
			String receivedHmac = payload.substring(lastColon + 1);
			String expectedHmac = computeHmac(jsonStr, hmacKey);
			if (!constantTimeEquals(expectedHmac, receivedHmac)) {
				return null;
			}
			return parseJson(jsonStr);

		} catch (Exception e) {
			return null;
		}
	}

	
	@Nullable
	public static VoiceCallSignal fromWireFormat(String wireMessage) {
		if (!isSignal(wireMessage)) {
			return null;
		}

		try {
			String payload = wireMessage.substring(WIRE_PREFIX.length());
			int lastColon = payload.lastIndexOf(':');
			if (lastColon < 0 || lastColon == payload.length() - 1) {
				return null;
			}

			String jsonStr = payload.substring(0, lastColon);
			String receivedHmac = payload.substring(lastColon + 1);
			String voiceKey = extractJsonString(jsonStr, "k");
			String callId = extractJsonString(jsonStr, "c");

			byte[] hmacKey;
			if (voiceKey != null) {
				hmacKey = voiceKey.getBytes(StandardCharsets.UTF_8);
			} else if (callId != null) {
				hmacKey = callId.getBytes(StandardCharsets.UTF_8);
			} else {
				return null;
			}
			String expectedHmac = computeHmac(jsonStr, hmacKey);
			if (!constantTimeEquals(expectedHmac, receivedHmac)) {
				return null;
			}
			return parseJson(jsonStr);

		} catch (Exception e) {
			return null;
		}
	}

	
	@Nullable
	private static VoiceCallSignal parseJson(String json) {
		try {
			if (json.length() > MAX_PAYLOAD_LENGTH) {
				return null;
			}

			String typeStr = extractJsonString(json, "t");
			String callId = extractJsonString(json, "c");
			Long timestamp = extractJsonLong(json, "ts");

			if (typeStr == null || callId == null || timestamp == null) {
				return null;
			}

			SignalType type = SignalType.fromWireValue(typeStr);
			if (type == null) {
				return null;
			}
			if (!isValidCallId(callId)) {
				return null;
			}
			long now = System.currentTimeMillis();
			long timeDiff = Math.abs(now - timestamp);
			if (timeDiff > TIMESTAMP_WINDOW_MS) {
				return null;
			}

			String voiceCallKey = extractJsonString(json, "k");
			String onionAddress = extractJsonString(json, "o");
			Integer onionPort = extractJsonInt(json, "p");
			String reason = extractJsonString(json, "r");
			if (reason != null && reason.length() > MAX_REASON_LENGTH) {
				reason = reason.substring(0, MAX_REASON_LENGTH);
			}
			switch (type) {
				case CALL_OFFER:
					if (voiceCallKey == null || !isValidVoiceCallKey(voiceCallKey)) {
						return null;
					}
					break;
				case CALL_ANSWER:
					if (onionAddress == null || onionPort == null) {
						return null;
					}
					if (!isValidOnionAddress(onionAddress) || !isValidPort(onionPort)) {
						return null;
					}
					break;
				case CALL_REJECT:
				case CALL_END:
					break;
				default:
					return null;
			}

			return new VoiceCallSignal(type, callId, timestamp,
					voiceCallKey, onionAddress, onionPort, reason);

		} catch (Exception e) {
			return null;
		}
	}
	@Nullable
	private static String extractJsonString(String json, String key) {
		String pattern = "\"" + key + "\":\"";
		int start = json.indexOf(pattern);
		if (start < 0) return null;
		start += pattern.length();
		int end = start;
		while (end < json.length()) {
			char c = json.charAt(end);
			if (c == '"') break;
			if (c == '\\' && end + 1 < json.length()) {
				end += 2;
			} else {
				end++;
			}
		}
		if (end >= json.length()) return null;
		return unescapeJson(json.substring(start, end));
	}

	@Nullable
	private static Long extractJsonLong(String json, String key) {
		String pattern = "\"" + key + "\":";
		int start = json.indexOf(pattern);
		if (start < 0) return null;
		start += pattern.length();
		while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
			start++;
		}

		int end = start;
		if (end < json.length() && json.charAt(end) == '-') {
			end++;
		}
		while (end < json.length() && Character.isDigit(json.charAt(end))) {
			end++;
		}
		if (end == start) return null;

		try {
			return Long.parseLong(json.substring(start, end));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Nullable
	private static Integer extractJsonInt(String json, String key) {
		Long value = extractJsonLong(json, key);
		if (value == null) return null;
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return null;
		return value.intValue();
	}
	private static void validateCallId(String callId) {
		if (!isValidCallId(callId)) {
			throw new IllegalArgumentException("Invalid call ID format");
		}
	}

	private static boolean isValidCallId(String callId) {
		return callId != null && callId.length() == 36 && UUID_PATTERN.matcher(callId).matches();
	}

	private static void validateVoiceCallKey(String key) {
		if (!isValidVoiceCallKey(key)) {
			throw new IllegalArgumentException("Invalid voice call key format");
		}
	}

	private static boolean isValidVoiceCallKey(String key) {
		return key != null && key.length() >= 32 && key.length() <= 256
				&& BASE64_PATTERN.matcher(key).matches();
	}

	private static void validateOnionAddress(String address) {
		if (!isValidOnionAddress(address)) {
			throw new IllegalArgumentException("Invalid onion address format");
		}
	}

	private static boolean isValidOnionAddress(String address) {
		return address != null && address.length() == 62 && ONION_PATTERN.matcher(address).matches();
	}

	private static void validatePort(int port) {
		if (!isValidPort(port)) {
			throw new IllegalArgumentException("Invalid port number");
		}
	}

	private static boolean isValidPort(int port) {
		return port > 0 && port <= 65535;
	}

	
	private static String computeHmac(String data, byte[] key) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
			mac.init(keySpec);
			byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(HMAC_OUTPUT_LENGTH * 2);
			for (byte b : hmacBytes) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new RuntimeException("HMAC computation failed", e);
		}
	}

	
	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
		byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);

		if (aBytes.length != bBytes.length) {
			return false;
		}

		int result = 0;
		for (int i = 0; i < aBytes.length; i++) {
			result |= aBytes[i] ^ bBytes[i];
		}
		Arrays.fill(aBytes, (byte) 0);
		Arrays.fill(bBytes, (byte) 0);

		return result == 0;
	}

	
	private static String escapeJson(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\b': sb.append("\\b"); break;
				case '\f': sb.append("\\f"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.toString();
	}

	
	private static String unescapeJson(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char next = s.charAt(i + 1);
				switch (next) {
					case '"': sb.append('"'); i++; break;
					case '\\': sb.append('\\'); i++; break;
					case 'b': sb.append('\b'); i++; break;
					case 'f': sb.append('\f'); i++; break;
					case 'n': sb.append('\n'); i++; break;
					case 'r': sb.append('\r'); i++; break;
					case 't': sb.append('\t'); i++; break;
					case 'u':
						if (i + 5 < s.length()) {
							try {
								String hex = s.substring(i + 2, i + 6);
								sb.append((char) Integer.parseInt(hex, 16));
								i += 5;
							} catch (NumberFormatException e) {
								sb.append(c);
							}
						} else {
							sb.append(c);
						}
						break;
					default:
						sb.append(c);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		VoiceCallSignal that = (VoiceCallSignal) o;
		return timestamp == that.timestamp &&
				type == that.type &&
				callId.equals(that.callId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, callId, timestamp);
	}

	@Override
	public String toString() {
		return "VoiceCallSignal{type=" + type + ", callId=" + callId + "}";
	}
}
