package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

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

	@Inject
	public ZtpPollerFactory(@IoExecutor Executor ioExecutor,
			TaskScheduler taskScheduler, ContactManager contactManager,
			TransportPropertyManager transportPropertyManager,
			EventBus eventBus,
			javax.inject.Provider<org.zerionproject.core.plugin.tor
					.B4OnionRotation> rotation) {
		this.ioExecutor = ioExecutor;
		this.taskScheduler = taskScheduler;
		this.contactManager = contactManager;
		this.transportPropertyManager = transportPropertyManager;
		this.eventBus = eventBus;
		this.rotation = rotation;
	}

	public ZtpPoller create(OverlayTransport transport) {
		return new ZtpPoller(ioExecutor, taskScheduler, contactManager,
				transportPropertyManager, eventBus, transport, rotation);
	}
}
