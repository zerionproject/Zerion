package org.zerionproject.core.sync.validation;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.NoSuchGroupException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageContext;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.sync.validation.MessageValidator;
import org.zerionproject.core.api.sync.validation.ValidationManager;
import org.zerionproject.core.api.versioning.ClientMajorVersion;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_SHARE;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.DEFER;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.REJECT;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.api.sync.validation.MessageState.INVALID;
import static org.zerionproject.core.api.sync.validation.MessageState.PENDING;
@ThreadSafe
@NotNullByDefault
class ValidationManagerImpl implements ValidationManager, Service,
		EventListener {
	private final DatabaseComponent db;
	private final Executor dbExecutor, validationExecutor;
	private final Map<ClientMajorVersion, MessageValidator> validators;
	private final Map<ClientMajorVersion, IncomingMessageHook> hooks;
	private final AtomicBoolean used = new AtomicBoolean(false);

	@Inject
	ValidationManagerImpl(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor,
			@ValidationExecutor Executor validationExecutor) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.validationExecutor = validationExecutor;
		validators = new ConcurrentHashMap<>();
		hooks = new ConcurrentHashMap<>();
	}

	@Override
	public void startService() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		validateOutstandingMessagesAsync();
		deliverOutstandingMessagesAsync();
		shareOutstandingMessagesAsync();
	}

	@Override
	public void stopService() {
	}

	@Override
	public void registerMessageValidator(ClientId c, int majorVersion,
			MessageValidator v) {
		validators.put(new ClientMajorVersion(c, majorVersion), v);
	}

	@Override
	public void registerIncomingMessageHook(ClientId c, int majorVersion,
			IncomingMessageHook hook) {
		hooks.put(new ClientMajorVersion(c, majorVersion), hook);
	}

	private void validateOutstandingMessagesAsync() {
		dbExecutor.execute(this::validateOutstandingMessages);
	}

	@DatabaseExecutor
	private void validateOutstandingMessages() {
		try {
			Queue<MessageId> unvalidated = new LinkedList<>(
					db.transactionWithResult(true, db::getMessagesToValidate));
			validateNextMessageAsync(unvalidated);
		} catch (DbException e) {
		}
	}

	private void validateNextMessageAsync(Queue<MessageId> unvalidated) {
		if (unvalidated.isEmpty()) return;
		dbExecutor.execute(() -> validateNextMessage(unvalidated));
	}

	@DatabaseExecutor
	private void validateNextMessage(Queue<MessageId> unvalidated) {
		try {
			Pair<Message, Group> mg = db.transactionWithResult(true, txn -> {
				MessageId id = unvalidated.poll();
				if (id == null) throw new AssertionError();
				Message m = db.getMessage(txn, id);
				Group g = db.getGroup(txn, m.getGroupId());
				return new Pair<>(m, g);
			});
			validateMessageAsync(mg.getFirst(), mg.getSecond());
			validateNextMessageAsync(unvalidated);
		} catch (NoSuchMessageException e) {
			validateNextMessageAsync(unvalidated);
		} catch (NoSuchGroupException e) {
			validateNextMessageAsync(unvalidated);
		} catch (DbException e) {
		}
	}

	private void deliverOutstandingMessagesAsync() {
		dbExecutor.execute(this::deliverOutstandingMessages);
	}

	@DatabaseExecutor
	private void deliverOutstandingMessages() {
		try {
			Queue<MessageId> pending = new LinkedList<>(
					db.transactionWithResult(true, db::getPendingMessages));
			deliverNextPendingMessageAsync(pending);
		} catch (DbException e) {
		}
	}

	private void deliverNextPendingMessageAsync(Queue<MessageId> pending) {
		if (pending.isEmpty()) return;
		dbExecutor.execute(() -> deliverNextPendingMessage(pending));
	}

	@DatabaseExecutor
	private void deliverNextPendingMessage(Queue<MessageId> pending) {
		try {
			Queue<MessageId> toShare = new LinkedList<>();
			Queue<MessageId> invalidate = new LinkedList<>();
			db.transaction(false, txn -> {
				boolean anyInvalid = false, allDelivered = true;
				MessageId id = pending.poll();
				if (id == null) throw new AssertionError();
				if (db.getMessageState(txn, id) == PENDING) {
					Map<MessageId, MessageState> states =
							db.getMessageDependencies(txn, id);
					for (Entry<MessageId, MessageState> e : states.entrySet()) {
						if (e.getValue() == INVALID) anyInvalid = true;
						if (e.getValue() != DELIVERED) allDelivered = false;
					}
					if (anyInvalid) {
						invalidateMessage(txn, id);
						addDependentsToInvalidate(txn, id, invalidate);
					} else if (allDelivered) {
						Message m = db.getMessage(txn, id);
						Group g = db.getGroup(txn, m.getGroupId());
						ClientId c = g.getClientId();
						int majorVersion = g.getMajorVersion();
						Metadata meta =
								db.getMessageMetadataForValidator(txn, id);
						DeliveryAction action =
								deliverMessage(txn, m, c, majorVersion, meta);
						if (action == REJECT) {
							invalidateMessage(txn, id);
							addDependentsToInvalidate(txn, id, invalidate);
						} else if (action == ACCEPT_SHARE) {
							db.setMessageState(txn, m.getId(), DELIVERED);
							addPendingDependents(txn, id, pending);
							db.setMessageShared(txn, id);
							toShare.addAll(states.keySet());
						} else if (action == ACCEPT_DO_NOT_SHARE) {
							db.setMessageState(txn, m.getId(), DELIVERED);
							addPendingDependents(txn, id, pending);
						}
					}
				}
			});
			if (!invalidate.isEmpty()) invalidateNextMessageAsync(invalidate);
			if (!toShare.isEmpty()) shareNextMessageAsync(toShare);
			deliverNextPendingMessageAsync(pending);
		} catch (NoSuchMessageException e) {
			deliverNextPendingMessageAsync(pending);
		} catch (NoSuchGroupException e) {
			deliverNextPendingMessageAsync(pending);
		} catch (DbException e) {
		}
	}

	private void validateMessageAsync(Message m, Group g) {
		validationExecutor.execute(() -> validateMessage(m, g));
	}

	@ValidationExecutor
	private void validateMessage(Message m, Group g) {
		ClientMajorVersion cv =
				new ClientMajorVersion(g.getClientId(), g.getMajorVersion());
		MessageValidator v = validators.get(cv);
		if (v == null) {
		} else {
			try {
				MessageContext context = v.validateMessage(m, g);
				storeMessageContextAsync(m, g.getClientId(),
						g.getMajorVersion(), context);
			} catch (InvalidMessageException e) {
				Queue<MessageId> invalidate = new LinkedList<>();
				invalidate.add(m.getId());
				invalidateNextMessageAsync(invalidate);
			}
		}
	}

	private void storeMessageContextAsync(Message m, ClientId c,
			int majorVersion, MessageContext result) {
		dbExecutor.execute(() ->
				storeMessageContext(m, c, majorVersion, result));
	}

	@DatabaseExecutor
	private void storeMessageContext(Message m, ClientId c, int majorVersion,
			MessageContext context) {
		try {
			MessageId id = m.getId();
			Queue<MessageId> invalidate = new LinkedList<>();
			Queue<MessageId> pending = new LinkedList<>();
			Queue<MessageId> toShare = new LinkedList<>();
			db.transaction(false, txn -> {
				boolean anyInvalid = false, allDelivered = true;
				Collection<MessageId> dependencies = context.getDependencies();
				if (!dependencies.isEmpty()) {
					db.addMessageDependencies(txn, m, dependencies);
					Map<MessageId, MessageState> states =
							db.getMessageDependencies(txn, id);
					for (Entry<MessageId, MessageState> e : states.entrySet()) {
						if (e.getValue() == INVALID) anyInvalid = true;
						if (e.getValue() != DELIVERED) allDelivered = false;
					}
				}
				if (anyInvalid) {
					if (db.getMessageState(txn, id) != INVALID) {
						invalidateMessage(txn, id);
						addDependentsToInvalidate(txn, id, invalidate);
					}
				} else {
					Metadata meta = context.getMetadata();
					db.mergeMessageMetadata(txn, id, meta);
					if (allDelivered) {
						DeliveryAction action =
								deliverMessage(txn, m, c, majorVersion, meta);
						if (action == REJECT) {
							invalidateMessage(txn, id);
							addDependentsToInvalidate(txn, id, invalidate);
						} else if (action == DEFER) {
							db.setMessageState(txn, id, PENDING);
						} else if (action == ACCEPT_SHARE) {
							db.setMessageState(txn, id, DELIVERED);
							addPendingDependents(txn, id, pending);
							db.setMessageShared(txn, id);
							toShare.addAll(dependencies);
						} else if (action == ACCEPT_DO_NOT_SHARE) {
							db.setMessageState(txn, id, DELIVERED);
							addPendingDependents(txn, id, pending);
						}
					} else {
						db.setMessageState(txn, id, PENDING);
					}
				}
			});
			if (!invalidate.isEmpty()) invalidateNextMessageAsync(invalidate);
			if (!pending.isEmpty()) deliverNextPendingMessageAsync(pending);
			if (!toShare.isEmpty()) shareNextMessageAsync(toShare);
		} catch (NoSuchMessageException e) {
		} catch (NoSuchGroupException e) {
		} catch (DbException e) {
		}
	}

	@DatabaseExecutor
	private DeliveryAction deliverMessage(Transaction txn, Message m,
			ClientId c, int majorVersion, Metadata meta) {
		ClientMajorVersion cv = new ClientMajorVersion(c, majorVersion);
		IncomingMessageHook hook = hooks.get(cv);
		if (hook == null) return ACCEPT_DO_NOT_SHARE;
		try {
			return hook.incomingMessage(txn, m, meta);
		} catch (DbException e) {
			return DEFER;
		} catch (InvalidMessageException e) {
			return REJECT;
		}
	}

	@DatabaseExecutor
	private void addPendingDependents(Transaction txn, MessageId m,
			Queue<MessageId> pending) throws DbException {
		Map<MessageId, MessageState> states = db.getMessageDependents(txn, m);
		for (Entry<MessageId, MessageState> e : states.entrySet()) {
			if (e.getValue() == PENDING) pending.add(e.getKey());
		}
	}

	private void shareOutstandingMessagesAsync() {
		dbExecutor.execute(this::shareOutstandingMessages);
	}

	@DatabaseExecutor
	private void shareOutstandingMessages() {
		try {
			Queue<MessageId> toShare = new LinkedList<>(
					db.transactionWithResult(true, db::getMessagesToShare));
			shareNextMessageAsync(toShare);
		} catch (DbException e) {
		}
	}

	private void shareNextMessageAsync(Queue<MessageId> toShare) {
		if (toShare.isEmpty()) return;
		dbExecutor.execute(() -> shareNextMessage(toShare));
	}

	@DatabaseExecutor
	private void shareNextMessage(Queue<MessageId> toShare) {
		try {
			db.transaction(false, txn -> {
				MessageId id = toShare.poll();
				if (id == null) throw new AssertionError();
				db.setMessageShared(txn, id);
				toShare.addAll(db.getMessageDependencies(txn, id).keySet());
			});
			shareNextMessageAsync(toShare);
		} catch (NoSuchMessageException e) {
			shareNextMessageAsync(toShare);
		} catch (NoSuchGroupException e) {
			shareNextMessageAsync(toShare);
		} catch (DbException e) {
		}
	}

	private void invalidateNextMessageAsync(Queue<MessageId> invalidate) {
		if (invalidate.isEmpty()) return;
		dbExecutor.execute(() -> invalidateNextMessage(invalidate));
	}

	@DatabaseExecutor
	private void invalidateNextMessage(Queue<MessageId> invalidate) {
		try {
			db.transaction(false, txn -> {
				MessageId id = invalidate.poll();
				if (id == null) throw new AssertionError();
				if (db.getMessageState(txn, id) != INVALID) {
					invalidateMessage(txn, id);
					addDependentsToInvalidate(txn, id, invalidate);
				}
			});
			invalidateNextMessageAsync(invalidate);
		} catch (NoSuchMessageException e) {
			invalidateNextMessageAsync(invalidate);
		} catch (DbException e) {
		}
	}

	@DatabaseExecutor
	private void invalidateMessage(Transaction txn, MessageId m)
			throws DbException {
		db.setMessageState(txn, m, INVALID);
		db.deleteMessage(txn, m);
		db.deleteMessageMetadata(txn, m);
	}

	@DatabaseExecutor
	private void addDependentsToInvalidate(Transaction txn,
			MessageId m, Queue<MessageId> invalidate) throws DbException {
		Map<MessageId, MessageState> states = db.getMessageDependents(txn, m);
		for (Entry<MessageId, MessageState> e : states.entrySet()) {
			if (e.getValue() != INVALID) invalidate.add(e.getKey());
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof MessageAddedEvent) {
			MessageAddedEvent m = (MessageAddedEvent) e;
			if (m.getContactId() != null)
				loadGroupAndValidateAsync(m.getMessage());
		}
	}

	private void loadGroupAndValidateAsync(Message m) {
		dbExecutor.execute(() -> loadGroupAndValidate(m));
	}

	@DatabaseExecutor
	private void loadGroupAndValidate(Message m) {
		try {
			Group g = db.transactionWithResult(true, txn ->
					db.getGroup(txn, m.getGroupId()));
			validateMessageAsync(m, g);
		} catch (NoSuchGroupException e) {
		} catch (DbException e) {
		}
	}
}
