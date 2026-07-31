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

/**
 * Creates a {@link ZtpPoller} bound to a specific {@link OverlayTransport}, so
 * each registered transport drives its own polling and designated-dialer
 * decisions from its own address book. Shared dependencies are injected once;
 * the per-transport instance is built by {@link #create}.
 */
@Singleton
@NotNullByDefault
public class ZtpPollerFactory {

	private final Executor ioExecutor;
	private final TaskScheduler taskScheduler;
	private final ContactManager contactManager;
	private final TransportPropertyManager transportPropertyManager;
	private final EventBus eventBus;

	@Inject
	public ZtpPollerFactory(@IoExecutor Executor ioExecutor,
			TaskScheduler taskScheduler, ContactManager contactManager,
			TransportPropertyManager transportPropertyManager,
			EventBus eventBus) {
		this.ioExecutor = ioExecutor;
		this.taskScheduler = taskScheduler;
		this.contactManager = contactManager;
		this.transportPropertyManager = transportPropertyManager;
		this.eventBus = eventBus;
	}

	/**
	 * Creates a poller for {@code transport}. A caller must create at most one
	 * poller per (singleton) transport for the life of that transport: two live
	 * pollers on the same transport would both sweep the contact list and dial,
	 * producing duplicate connections. Today the plugin manager and the plugin's
	 * own start guard enforce a single {@code createPlugin}, so exactly one
	 * poller is created per transport.
	 */
	public ZtpPoller create(OverlayTransport transport) {
		return new ZtpPoller(ioExecutor, taskScheduler, contactManager,
				transportPropertyManager, eventBus, transport);
	}
}
