package com.professor.zerion.android.conversation;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.professor.zerion.android.conversation.ContactConnectionLines.Line;
import com.professor.zerion.android.navdrawer.TorPublishTracker;
import com.professor.zerion.android.viewmodel.DbViewModel;

import org.zerionproject.core.api.connection.ContactConnectionDiagnostics;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

@NotNullByDefault
public class ContactConnectionViewModel extends DbViewModel {

	static final long REFRESH_MS = 3_000;

	private final ContactConnectionDiagnostics diagnostics;
	private final TorPublishTracker torPublishTracker;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable refresh = this::refresh;
	private final MutableLiveData<List<Line>> lines =
			new MutableLiveData<List<Line>>() {
				@Override
				protected void onActive() {
					startRefreshing();
				}

				@Override
				protected void onInactive() {
					stopRefreshing();
				}
			};
	@Nullable
	private volatile ContactId contactId;
	private boolean refreshing = false;

	@Inject
	ContactConnectionViewModel(Application app,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor,
			ContactConnectionDiagnostics diagnostics,
			TorPublishTracker torPublishTracker) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.diagnostics = diagnostics;
		this.torPublishTracker = torPublishTracker;
	}

	void setContactId(ContactId c) {
		contactId = c;
	}

	LiveData<List<Line>> getLines() {
		return lines;
	}

	@UiThread
	private void startRefreshing() {
		refreshing = true;
		handler.removeCallbacks(refresh);
		refresh();
	}

	@UiThread
	private void stopRefreshing() {
		refreshing = false;
		handler.removeCallbacks(refresh);
	}

	@UiThread
	private void refresh() {
		ContactId c = contactId;
		if (!refreshing || c == null) return;
		runOnDbThread(() -> {
			ContactConnectionSnapshot s;
			try {
				s = diagnostics.getSnapshot(c);
			} catch (DbException | RuntimeException e) {
				s = null;
			}
			ContactConnectionSnapshot loaded = s;
			androidExecutor.runOnUiThread(() -> onLoaded(loaded));
		});
	}

	@UiThread
	private void onLoaded(@Nullable ContactConnectionSnapshot s) {
		if (!refreshing) return;
		if (s != null) {
			lines.setValue(ContactConnectionLines.lines(s,
					torPublishTracker.isPublished()));
		}
		handler.removeCallbacks(refresh);
		handler.postDelayed(refresh, REFRESH_MS);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		stopRefreshing();
	}
}
