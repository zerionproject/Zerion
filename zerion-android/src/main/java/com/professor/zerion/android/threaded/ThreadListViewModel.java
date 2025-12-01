package com.professor.zerion.android.threaded;

import android.app.Application;

import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchGroupException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import com.professor.zerion.android.sharing.SharingController;
import com.professor.zerion.android.sharing.SharingController.SharingInfo;
import com.professor.zerion.android.viewmodel.DbViewModel;
import com.professor.zerion.android.viewmodel.LiveResult;
import com.professor.zerion.android.api.AndroidNotificationManager;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.MessageTree;
import org.briarproject.briar.client.MessageTreeImpl;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import androidx.annotation.CallSuper;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.INFO;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class ThreadListViewModel<I extends ThreadItem>
		extends DbViewModel implements EventListener {


	protected final IdentityManager identityManager;
	protected final AndroidNotificationManager notificationManager;
	protected final SharingController sharingController;
	protected final Executor cryptoExecutor;
	protected final Clock clock;
	private final MessageTracker messageTracker;
	private final EventBus eventBus;

	private final MessageTree<I> messageTree = new MessageTreeImpl<>();
	private final MutableLiveData<LiveResult<List<I>>> items =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> groupRemoved =
			new MutableLiveData<>();
	private final AtomicReference<MessageId> scrollToItem =
			new AtomicReference<>();

	protected volatile GroupId groupId;
	@Nullable
	private MessageId replyId;
	private final AtomicReference<MessageId> storedMessageId =
			new AtomicReference<>();

	public ThreadListViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			IdentityManager identityManager,
			AndroidNotificationManager notificationManager,
			SharingController sharingController,
			@CryptoExecutor Executor cryptoExecutor,
			Clock clock,
			MessageTracker messageTracker,
			EventBus eventBus) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.identityManager = identityManager;
		this.notificationManager = notificationManager;
		this.cryptoExecutor = cryptoExecutor;
		this.clock = clock;
		this.sharingController = sharingController;
		this.messageTracker = messageTracker;
		this.eventBus = eventBus;
		this.eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
		sharingController.onCleared();
	}

	public final void setGroupId(GroupId groupId) {
		boolean needsInitialLoad = this.groupId == null;
		this.groupId = groupId;
		if (needsInitialLoad) performInitialLoad();
	}

	@CallSuper
	protected void performInitialLoad() {
		loadStoredMessageId();
		loadItems();
		loadSharingContacts();
	}

	protected abstract void clearNotifications();

	void blockAndClearNotifications() {
		notificationManager.blockNotification(groupId);
		clearNotifications();
	}

	void unblockNotifications() {
		notificationManager.unblockNotification(groupId);
	}

	@Override
	@CallSuper
	public void eventOccurred(Event e) {
		if (e instanceof GroupRemovedEvent) {
			GroupRemovedEvent s = (GroupRemovedEvent) e;
			if (s.getGroup().getId().equals(groupId)) {
				groupRemoved.setValue(true);
			}
		}
	}

	private void loadStoredMessageId() {
		runOnDbThread(() -> {
			try {
				storedMessageId
						.set(messageTracker.loadStoredMessageId(groupId));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	public abstract void loadItems();

	public abstract void createAndStoreMessage(String text,
			@Nullable MessageId parentMessageId);

	protected abstract void loadSharingContacts();

	@UiThread
	protected void setItems(LiveResult<List<I>> items) {
		if (items.hasError()) {
			this.items.setValue(items);
		} else {
			messageTree.clear();
			messageTree.add(requireNonNull(items.getResultOrNull()));
			LiveResult<List<I>> result =
					new LiveResult<>(messageTree.depthFirstOrder());
			this.items.setValue(result);
		}
	}

	@UiThread
	protected void addItem(I item, boolean scrollToItem) {
		if (items.getValue() == null) return;

		messageTree.add(item);
		if (scrollToItem) this.scrollToItem.set(item.getId());
		items.setValue(new LiveResult<>(messageTree.depthFirstOrder()));
	}

	@UiThread
	void setReplyId(@Nullable MessageId id) {
		replyId = id;
	}

	@UiThread
	@Nullable
	MessageId getReplyId() {
		return replyId;
	}

	@UiThread
	void storeMessageId(@Nullable MessageId messageId) {
		if (messageId != null) {
			runOnDbThread(() -> {
				try {
					messageTracker.storeMessageId(groupId, messageId);
				} catch (NoSuchGroupException e) {
				} catch (DbException e) {
					handleException(e);
				}
			});
		}
	}

	protected abstract void markItemRead(I item);

	@Nullable
	MessageId getAndResetRestoredMessageId() {
		return storedMessageId.getAndSet(null);
	}

	LiveData<LiveResult<List<I>>> getItems() {
		return items;
	}

	LiveData<SharingInfo> getSharingInfo() {
		return sharingController.getSharingInfo();
	}

	LiveData<Boolean> getGroupRemoved() {
		return groupRemoved;
	}

	@Nullable
	MessageId getAndResetScrollToItem() {
		return scrollToItem.getAndSet(null);
	}

}
