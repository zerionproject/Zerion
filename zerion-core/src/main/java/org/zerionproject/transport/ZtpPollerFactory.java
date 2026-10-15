package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class ZtpPollerFactory {

	private final Executor ioExecutor;
	private final TaskScheduler taskScheduler;
	private final ContactManager contactManager;
	private final TransportPropertyManager transportPropertyManager;
	private final EventBus eventBus;
	private final javax.inject.Provider<org.zerionproject.core.plugin.tor
			.B4OnionRotation> rotation;
	@Nullable
	private final org.zerionproject.core.api.connection.ConnectionRegistry
			connectionRegistry;
	private final Map<TransportId, ZtpPoller> pollers =
			new ConcurrentHashMap<>();

	public ZtpPollerFactory(Executor ioExecutor,
			TaskScheduler taskScheduler, ContactManager contactManager,
			TransportPropertyManager transportPropertyManager,
			EventBus eventBus,
			javax.inject.Provider<org.zerionproject.core.plugin.tor
					.B4OnionRotation> rotation) {
		this(ioExecutor, taskScheduler, contactManager,
				transportPropertyManager, eventBus, rotation, null);
	}

	@Inject
	public ZtpPollerFactory(@IoExecutor Executor ioExecutor,
			TaskScheduler taskScheduler, ContactManager contactManager,
			TransportPropertyManager transportPropertyManager,
			EventBus eventBus,
			javax.inject.Provider<org.zerionproject.core.plugin.tor
					.B4OnionRotation> rotation,
			@Nullable org.zerionproject.core.api.connection.ConnectionRegistry
					connectionRegistry) {
		this.connectionRegistry = connectionRegistry;
		this.ioExecutor = ioExecutor;
		this.taskScheduler = taskScheduler;
		this.contactManager = contactManager;
		this.transportPropertyManager = transportPropertyManager;
		this.eventBus = eventBus;
		this.rotation = rotation;
	}

	public ZtpPoller create(OverlayTransport transport) {
		ZtpPoller poller = new ZtpPoller(ioExecutor, taskScheduler,
				contactManager, transportPropertyManager, eventBus, transport,
				rotation);
		org.zerionproject.core.api.connection.ConnectionRegistry registry =
				connectionRegistry;
		if (registry != null) {
			poller.setConnectedCheck(c -> registry.isConnected(
					new org.zerionproject.core.api.contact.ContactId(c)));
		}
		pollers.put(transport.getTransportId(), poller);
		return poller;
	}

	@Nullable
	public ZtpPoller getPoller(TransportId t) {
		return pollers.get(t);
	}
}
