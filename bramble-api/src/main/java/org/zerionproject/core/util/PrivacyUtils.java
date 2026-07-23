package org.zerionproject.core.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;

import javax.annotation.Nullable;

import static org.zerionproject.core.util.StringUtils.isNullOrEmpty;
import static org.zerionproject.core.util.StringUtils.isValidMac;
import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
public class PrivacyUtils {

	public static String scrubOnion(String onion) {
		return onion.substring(0, 3) + "[scrubbed]";
	}

	@Nullable
	public static String scrubMacAddress(@Nullable String address) {
		if (isNullOrEmpty(address) || !isValidMac(address)) return address;
		if (address.equals("02:00:00:00:00:00")) return address;
		return address.substring(0, 3) + "[scrubbed]"
				+ address.substring(14, 17);
	}

	public static String scrubInetAddress(InetAddress address) {
		if (address instanceof Inet4Address) {
			if (address.isLoopbackAddress() || address.isLinkLocalAddress() ||
					address.isSiteLocalAddress()) {
				return address.getHostAddress();
			}
			return scrubIpv4Address(address.getAddress());
		} else {
			return scrubIpv6Address(address.getAddress());
		}
	}

	private static String scrubIpv4Address(byte[] ipv4) {
		return (ipv4[0] & 0xFF) + ".[scrubbed]." + (ipv4[3] & 0xFF);
	}

	private static String scrubIpv6Address(byte[] ipv6) {
		String hex = toHexString(ipv6).toLowerCase(Locale.US);
		return hex.substring(0, 2) + "[scrubbed]" + hex.substring(30);
	}

	public static String scrubSocketAddress(InetSocketAddress address) {
		return scrubInetAddress(address.getAddress());
	}

	public static String scrubSocketAddress(SocketAddress address) {
		if (address instanceof InetSocketAddress)
			return scrubSocketAddress((InetSocketAddress) address);
		return "[scrubbed]";
	}
}
