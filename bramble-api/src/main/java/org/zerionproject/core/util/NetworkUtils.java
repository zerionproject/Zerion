package org.zerionproject.core.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.List;
import static java.util.Collections.emptyList;
import static java.util.Collections.list;
@NotNullByDefault
public class NetworkUtils {
	public static List<NetworkInterface> getNetworkInterfaces() {
		try {
			Enumeration<NetworkInterface> ifaces =
					NetworkInterface.getNetworkInterfaces();
			return ifaces == null ? emptyList() : list(ifaces);
		} catch (SocketException | NullPointerException e) {
			return emptyList();
		}
	}
}
