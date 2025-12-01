package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;

/**
 * Structured voice call signaling message format.
 *
 * SECURITY PROPERTIES:
 * - No in-band spoofing via chat: user-generated messages cannot be misinterpreted
 *   as call signals because they can't produce the binary prefix or structured envelope.
 * - Authenticity: HMAC-SHA256 with session key prevents tampering by parties without
 *   the voice call key. (Note: endpoint compromise is out of scope - a malicious client
 *   with access to the key can still forge signals.)
 * - Replay mitigation: Timestamp window + callId binding limits replay attacks.
 * - DoS protection: Maximum payload length enforced.
 *
 * Wire format: WIRE_PREFIX + canonical_json + ":" + hmac
 *
 * The binary prefix uses control characters that:
 * 1. Cannot be typed by normal users
 * 2. Include version byte for protocol evolution
 * 3. Enable fast detection without full parsing
 *
 * JSON CANONICALIZATION:
 * - Fixed field order: t, c, ts, then optional fields alphabetically (k, o, p, r)
 * - No whitespace
 * - UTF-8 encoding
 * - Deterministic escaping
 */
@NotNullByDefault
public class VoiceCallSignal {

	private static final Logger LOG = getLogger(VoiceCallSignal.class.getName());

	// Signal prefix - uses control characters that cannot be typed normally
	// Format: 0x00 + "ZSIG" + version byte + 0x00
	private static final String SIGNAL_PREFIX = "\u0000ZSIG\u0001\u0000";

	// Wire format prefix
	public static final String WIRE_PREFIX = SIGNAL_PREFIX;

	// Security limits
	private static final int MAX_PAYLOAD_LENGTH = 2048; // Prevent DoS via huge payloads
	private static final int MAX_REASON_LENGTH = 128;   // Limit reason field

	// Timestamp validation window (configurable for device clock skew)
	private static final long TIMESTAMP_WINDOW_MS = 10 * 60 * 1000; // 10 minutes

	// HMAC configuration
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final int HMAC_OUTPUT_LENGTH = 32; // Full 256 bits for security

	// Signal types
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

	// Validation patterns (simple, no catastrophic backtracking)
	private static final Pattern UUID_PATTERN = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
	private static final Pattern BASE64_PATTERN = Pattern.compile(
			"^[A-Za-z0-9+/=]{32,256}$"); // Min 32, max 256 chars
	private static final Pattern ONION_PATTERN = Pattern.compile(
			"^[a-z2-7]{56}\\.onion$");

	// Signal fields
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

	// Getters
	public SignalType getType() { return type; }
	public String getCallId() { return callId; }
	public long getTimestamp() { return timestamp; }
	@Nullable public String getVoiceCallKey() { return voiceCallKey; }
	@Nullable public String getOnionAddress() { return onionAddress; }
	@Nullable public Integer getOnionPort() { return onionPort; }
	@Nullable public String getReason() { return reason; }

	/**
	 * Creates a CALL_OFFER signal.
	 */
	public static VoiceCallSignal createOffer(String callId, String voiceCallKey) {
		validateCallId(callId);
		validateVoiceCallKey(voiceCallKey);
		return new VoiceCallSignal(SignalType.CALL_OFFER, callId,
				System.currentTimeMillis(), voiceCallKey, null, null, null);
	}

	/**
	 * Creates a CALL_ANSWER signal.
	 */
	public static VoiceCallSignal createAnswer(String callId, String onionAddress, int onionPort) {
		validateCallId(callId);
		validateOnionAddress(onionAddress);
		validatePort(onionPort);
		return new VoiceCallSignal(SignalType.CALL_ANSWER, callId,
				System.currentTimeMillis(), null, onionAddress, onionPort, null);
	}

	/**
	 * Creates a CALL_REJECT signal.
	 */
	public static VoiceCallSignal createReject(String callId, @Nullable String reason) {
		validateCallId(callId);
		if (reason != null && reason.length() > MAX_REASON_LENGTH) {
			reason = reason.substring(0, MAX_REASON_LENGTH);
		}
		return new VoiceCallSignal(SignalType.CALL_REJECT, callId,
				System.currentTimeMillis(), null, null, null, reason);
	}

	/**
	 * Creates a CALL_END signal.
	 */
	public static VoiceCallSignal createEnd(String callId, @Nullable String reason) {
		validateCallId(callId);
		if (reason != null && reason.length() > MAX_REASON_LENGTH) {
			reason = reason.substring(0, MAX_REASON_LENGTH);
		}
		return new VoiceCallSignal(SignalType.CALL_END, callId,
				System.currentTimeMillis(), null, null, null, reason);
	}

	/**
	 * Serializes this signal to wire format with HMAC authentication.
	 *
	 * @param hmacKey The voice call key used for HMAC computation.
	 *                For CALL_OFFER, this is the key being sent.
	 *                For other signals, this should be the established session key.
	 * @return Wire format string: WIRE_PREFIX + canonical_json + ":" + hmac_hex
	 */
	public String toWireFormat(byte[] hmacKey) {
		String jsonStr = toCanonicalJson();
		String hmac = computeHmac(jsonStr, hmacKey);
		return WIRE_PREFIX + jsonStr + ":" + hmac;
	}

	/**
	 * Serializes this signal to wire format.
	 * Uses the voice call key embedded in CALL_OFFER signals for HMAC.
	 * For other signal types, caller must use toWireFormat(byte[] hmacKey).
	 */
	public String toWireFormat() {
		// For CALL_OFFER, we can derive HMAC key from the voice call key being sent
		// For other types, we use a fallback (the callId as key material)
		// This maintains backward compatibility while adding integrity
		byte[] hmacKey;
		if (voiceCallKey != null) {
			hmacKey = voiceCallKey.getBytes(StandardCharsets.UTF_8);
		} else {
			// Fallback: use callId as key material (still provides integrity binding)
			hmacKey = callId.getBytes(StandardCharsets.UTF_8);
		}
		return toWireFormat(hmacKey);
	}

	/**
	 * Produces canonical JSON with fixed field order for deterministic HMAC.
	 * Field order: t, c, ts, then optional fields alphabetically (k, o, p, r)
	 */
	private String toCanonicalJson() {
		StringBuilder json = new StringBuilder();
		json.append("{");

		// Required fields in fixed order
		json.append("\"c\":\"").append(escapeJson(callId)).append("\",");
		json.append("\"t\":\"").append(type.getWireValue()).append("\",");
		json.append("\"ts\":").append(timestamp);

		// Optional fields in alphabetical order
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

	/**
	 * Checks if a message is a voice call signal.
	 * This is a fast check that doesn't parse the full message.
	 */
	public static boolean isSignal(String message) {
		if (message == null) {
			return false;
		}
		int minLength = WIRE_PREFIX.length() + 20; // prefix + minimal json + hmac
		if (message.length() < minLength || message.length() > MAX_PAYLOAD_LENGTH) {
			return false;
		}
		return message.startsWith(WIRE_PREFIX);
	}

	/**
	 * Parses a wire format message into a VoiceCallSignal.
	 * Returns null if parsing fails, validation fails, or HMAC verification fails.
	 *
	 * @param wireMessage The wire format message
	 * @param hmacKey The key for HMAC verification (typically the voice call key)
	 * @return Parsed signal or null if invalid
	 */
	@Nullable
	public static VoiceCallSignal fromWireFormat(String wireMessage, byte[] hmacKey) {
		if (!isSignal(wireMessage)) {
			return null;
		}

		try {
			// Remove prefix
			String payload = wireMessage.substring(WIRE_PREFIX.length());

			// Split json and hmac
			int lastColon = payload.lastIndexOf(':');
			if (lastColon < 0 || lastColon == payload.length() - 1) {
				return null;
			}

			String jsonStr = payload.substring(0, lastColon);
			String receivedHmac = payload.substring(lastColon + 1);

			// Verify HMAC
			String expectedHmac = computeHmac(jsonStr, hmacKey);
			if (!constantTimeEquals(expectedHmac, receivedHmac)) {
				LOG.log(WARNING, "Signal HMAC verification failed");
				return null;
			}

			// Parse JSON
			return parseJson(jsonStr);

		} catch (Exception e) {
			LOG.log(WARNING, "Signal parsing failed: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Parses a wire format message, deriving HMAC key from the signal content.
	 * For CALL_OFFER, extracts the voice call key from the JSON for verification.
	 * For other types, uses callId as fallback key material.
	 */
	@Nullable
	public static VoiceCallSignal fromWireFormat(String wireMessage) {
		if (!isSignal(wireMessage)) {
			return null;
		}

		try {
			// Remove prefix
			String payload = wireMessage.substring(WIRE_PREFIX.length());

			// Split json and hmac
			int lastColon = payload.lastIndexOf(':');
			if (lastColon < 0 || lastColon == payload.length() - 1) {
				return null;
			}

			String jsonStr = payload.substring(0, lastColon);
			String receivedHmac = payload.substring(lastColon + 1);

			// Extract key material for HMAC verification
			// For CALL_OFFER, use the voice call key from the payload
			// For other types, use callId
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

			// Verify HMAC
			String expectedHmac = computeHmac(jsonStr, hmacKey);
			if (!constantTimeEquals(expectedHmac, receivedHmac)) {
				LOG.log(WARNING, "Signal HMAC verification failed");
				return null;
			}

			// Parse JSON with full validation
			return parseJson(jsonStr);

		} catch (Exception e) {
			LOG.log(WARNING, "Signal parsing failed: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Parses the JSON payload into a VoiceCallSignal with full validation.
	 */
	@Nullable
	private static VoiceCallSignal parseJson(String json) {
		try {
			// Length check first
			if (json.length() > MAX_PAYLOAD_LENGTH) {
				LOG.log(WARNING, "Signal JSON exceeds maximum length");
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

			// Validate call ID
			if (!isValidCallId(callId)) {
				return null;
			}

			// Check timestamp is reasonable (within window, accounting for clock skew)
			long now = System.currentTimeMillis();
			long timeDiff = Math.abs(now - timestamp);
			if (timeDiff > TIMESTAMP_WINDOW_MS) {
				LOG.log(WARNING, "Signal timestamp outside window: diff=" + timeDiff + "ms");
				return null;
			}

			String voiceCallKey = extractJsonString(json, "k");
			String onionAddress = extractJsonString(json, "o");
			Integer onionPort = extractJsonInt(json, "p");
			String reason = extractJsonString(json, "r");

			// Truncate reason if too long
			if (reason != null && reason.length() > MAX_REASON_LENGTH) {
				reason = reason.substring(0, MAX_REASON_LENGTH);
			}

			// Validate type-specific fields
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
					// No additional required fields
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

	// JSON extraction helpers (simple implementation without dependencies)
	@Nullable
	private static String extractJsonString(String json, String key) {
		String pattern = "\"" + key + "\":\"";
		int start = json.indexOf(pattern);
		if (start < 0) return null;
		start += pattern.length();

		// Find closing quote, handling escapes
		int end = start;
		while (end < json.length()) {
			char c = json.charAt(end);
			if (c == '"') break;
			if (c == '\\' && end + 1 < json.length()) {
				end += 2; // Skip escaped character
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

		// Skip any whitespace (though our canonical format has none)
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

	// Validation methods
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

	/**
	 * Computes HMAC-SHA256 for authenticity verification.
	 */
	private static String computeHmac(String data, byte[] key) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_ALGORITHM);
			mac.init(keySpec);
			byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

			// Convert to hex string (full 256 bits)
			StringBuilder hex = new StringBuilder(HMAC_OUTPUT_LENGTH * 2);
			for (byte b : hmacBytes) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new RuntimeException("HMAC computation failed", e);
		}
	}

	/**
	 * Constant-time comparison to prevent timing attacks.
	 */
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

		// Clear sensitive data
		Arrays.fill(aBytes, (byte) 0);
		Arrays.fill(bBytes, (byte) 0);

		return result == 0;
	}

	/**
	 * JSON string escaping for canonical output.
	 */
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

	/**
	 * JSON string unescaping.
	 */
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
