package org.briarproject.bramble.plugin.tcp;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.briarproject.bramble.api.lifecycle.ShutdownManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.ThreadSafe;
import javax.xml.parsers.ParserConfigurationException;
import static org.briarproject.bramble.util.PrivacyUtils.scrubInetAddress;

@ThreadSafe
@MethodsNotNullByDefault
@ParametersNotNullByDefault
class PortMapperImpl implements PortMapper {
	private final ShutdownManager shutdownManager;
	private final AtomicBoolean started = new AtomicBoolean(false);

	private volatile GatewayDevice gateway = null;

	PortMapperImpl(ShutdownManager shutdownManager) {
		this.shutdownManager = shutdownManager;
	}

	@Override
	public MappingResult map(int port) {
		if (!started.getAndSet(true)) start();
		if (gateway == null) return null;
		InetAddress internal = gateway.getLocalAddress();
		if (internal == null) return null;
		InetAddress external = null;
		boolean succeeded = false;
		try {
			succeeded = gateway.addPortMapping(port, port,
					getHostAddress(internal), "TCP", "TCP");
			if (succeeded) {
				shutdownManager.addShutdownHook(() -> deleteMapping(port));
			}
			String externalString = gateway.getExternalIPAddress();
			if (externalString == null) {
			} else {
				external = InetAddress.getByName(externalString);
			}
		} catch (IOException | SAXException e) {
		}
		return new MappingResult(internal, external, port, succeeded);
	}

	private String getHostAddress(InetAddress a) {
		String addr = a.getHostAddress();
		int percent = addr.indexOf('%');
		if (percent == -1) return addr;
		return addr.substring(0, percent);
	}

	private void start() {
		GatewayDiscover d = new GatewayDiscover();
		try {
			d.discover();
		} catch (IOException | SAXException | ParserConfigurationException e) {
		}
		gateway = d.getValidGateway();
	}

	private void deleteMapping(int port) {
		try {
			gateway.deletePortMapping(port, "TCP");
		} catch (IOException | SAXException e) {
		}
	}
}
