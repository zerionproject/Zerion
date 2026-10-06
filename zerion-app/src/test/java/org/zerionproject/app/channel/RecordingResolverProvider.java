package org.zerionproject.app.channel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class RecordingResolverProvider extends InetAddressResolverProvider {

	static final List<String> LOOKUPS =
			Collections.synchronizedList(new ArrayList<>());

	@Override
	public InetAddressResolver get(Configuration configuration) {
		InetAddressResolver builtin = configuration.builtinResolver();
		return new InetAddressResolver() {
			@Override
			public Stream<InetAddress> lookupByName(String host,
					LookupPolicy lookupPolicy) throws UnknownHostException {
				LOOKUPS.add(host);
				String lower = host.toLowerCase(Locale.ROOT);
				if (lower.endsWith(".onion") || lower.endsWith(".invalid")) {
					throw new UnknownHostException(host);
				}
				return builtin.lookupByName(host, lookupPolicy);
			}

			@Override
			public String lookupByAddress(byte[] addr)
					throws UnknownHostException {
				return builtin.lookupByAddress(addr);
			}
		};
	}

	@Override
	public String name() {
		return "recording";
	}
}
