package com.professor.zerion.android.update;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.professor.zerion.BuildConfig;
import com.professor.zerion.android.AppModule;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.net.SocketFactory;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

@Singleton
@NotNullByDefault
public class UpdateChecker implements EventListener {

	public enum Outcome {
		UP_TO_DATE, AVAILABLE, NOT_VERIFIED, UNREACHABLE, TOR_NOT_READY,
		STORE_INSTALL
	}

	public static final class Result {
		public final Outcome outcome;
		@Nullable
		public final ReleaseAnnouncement announcement;

		Result(Outcome outcome, @Nullable ReleaseAnnouncement announcement) {
			this.outcome = outcome;
			this.announcement = announcement;
		}
	}

	static final String PREF_AUTO = "update_check_auto";
	static final String PREF_NEXT = "update_check_next_ms";
	static final String PREF_DISMISSED_CODE = "update_dismissed_code";
	static final String PREF_DISMISSED_AT = "update_dismissed_at_ms";

	interface Clock {
		long now();
	}

	private final PluginManager pluginManager;
	private final Executor ioExecutor;
	private final Provider<SharedPreferences> prefs;
	private final AnnouncementSource source;
	private final Supplier<List<ReleaseAnnouncements.Signer>> signers;
	private final Supplier<String> storeLabel;
	private final Clock clock;
	private final String releasesUrl;
	private final long installedCode;
	private final String installedName;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final MutableLiveData<ReleaseAnnouncement> popup =
			new MutableLiveData<>();
	private final AtomicBoolean automaticRunning = new AtomicBoolean(false);

	@Inject
	public UpdateChecker(Application app, EventBus eventBus,
			PluginManager pluginManager, SocketFactory torSockets,
			@IoExecutor Executor ioExecutor,
			@AppModule.SecurePrefs Provider<SharedPreferences> prefs) {
		this(eventBus, pluginManager, ioExecutor, prefs,
				AnnouncementSource.overTor(app, torSockets,
						BuildConfig.UPDATE_MANIFEST_URL),
				() -> ReleaseAnnouncements.signersFrom(
						InstallSource.ownSigners(app)),
				() -> InstallSource.storeLabel(app),
				System::currentTimeMillis, BuildConfig.UPDATE_RELEASES_URL,
				BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
	}

	UpdateChecker(EventBus eventBus, PluginManager pluginManager,
			Executor ioExecutor, Provider<SharedPreferences> prefs,
			AnnouncementSource source,
			Supplier<List<ReleaseAnnouncements.Signer>> signers,
			Supplier<String> storeLabel, Clock clock, String releasesUrl,
			long installedCode, String installedName) {
		this.pluginManager = pluginManager;
		this.ioExecutor = ioExecutor;
		this.prefs = prefs;
		this.source = source;
		this.signers = signers;
		this.storeLabel = storeLabel;
		this.clock = clock;
		this.releasesUrl = releasesUrl;
		this.installedCode = installedCode;
		this.installedName = installedName;
		eventBus.addListener(this);
	}

	public LiveData<ReleaseAnnouncement> getPopup() {
		return popup;
	}

	public String installedVersionName() {
		return installedName;
	}

	@Nullable
	public String storeName() {
		return storeLabel.get();
	}

	public boolean isAutomaticEnabled() {
		return prefs.get().getBoolean(PREF_AUTO, true);
	}

	public void setAutomaticEnabled(boolean enabled) {
		prefs.get().edit().putBoolean(PREF_AUTO, enabled).apply();
		if (enabled) maybeCheckAutomatically();
	}

	public void onAppForeground() {
		maybeCheckAutomatically();
	}

	public void popupShown(ReleaseAnnouncement a) {
		prefs.get().edit()
				.putLong(PREF_DISMISSED_CODE, a.versionCode)
				.putLong(PREF_DISMISSED_AT, clock.now())
				.apply();
		if (popup.getValue() == a) popup.setValue(null);
	}

	public void checkNow(Consumer<Result> onResult) {
		if (storeLabel.get() != null) {
			onResult.accept(new Result(Outcome.STORE_INSTALL, null));
			return;
		}
		if (!torActive()) {
			onResult.accept(new Result(Outcome.TOR_NOT_READY, null));
			return;
		}
		ioExecutor.execute(() -> {
			Result r = runCheck();
			mainHandler.post(() -> onResult.accept(r));
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof TransportStateEvent) {
			TransportStateEvent t = (TransportStateEvent) e;
			if (t.getTransportId().equals(TorConstants.ID)
					&& t.getState() == Plugin.State.ACTIVE) {
				mainHandler.post(this::maybeCheckAutomatically);
			}
		}
	}

	private void maybeCheckAutomatically() {
		SharedPreferences p = prefs.get();
		if (!UpdatePolicy.dueForAutomaticCheck(isAutomaticEnabled(),
				storeLabel.get() != null, torActive(), clock.now(),
				p.getLong(PREF_NEXT, 0))) {
			return;
		}
		if (!automaticRunning.compareAndSet(false, true)) return;
		ioExecutor.execute(() -> {
			try {
				Result r = runCheck();
				if (r.outcome == Outcome.AVAILABLE && r.announcement != null
						&& UpdatePolicy.shouldPopUp(r.announcement,
						installedCode, p.getLong(PREF_DISMISSED_CODE, 0),
						p.getLong(PREF_DISMISSED_AT, 0), clock.now())) {
					ReleaseAnnouncement a = r.announcement;
					mainHandler.post(() -> popup.setValue(a));
				}
			} finally {
				automaticRunning.set(false);
			}
		});
	}

	private Result runCheck() {
		Result r = fetchAndVerify();
		prefs.get().edit().putLong(PREF_NEXT,
				UpdatePolicy.nextCheckAfter(r.outcome, clock.now())).apply();
		return r;
	}

	private Result fetchAndVerify() {
		AnnouncementSource.Fetched f;
		try {
			f = source.fetch();
		} catch (IOException | RuntimeException e) {
			return new Result(Outcome.UNREACHABLE, null);
		}
		if (f.manifest == null || f.signature == null) {
			return new Result(Outcome.NOT_VERIFIED, null);
		}
		String signer = ReleaseAnnouncements.verifiedSigner(f.manifest,
				f.signature, signers.get());
		if (signer == null) return new Result(Outcome.NOT_VERIFIED, null);
		ReleaseAnnouncement a = ReleaseAnnouncements.parse(f.manifest,
				releasesUrl, signer);
		if (a == null) return new Result(Outcome.NOT_VERIFIED, null);
		return new Result(a.versionCode > installedCode
				? Outcome.AVAILABLE : Outcome.UP_TO_DATE, a);
	}

	private boolean torActive() {
		Plugin p = pluginManager.getPlugin(TorConstants.ID);
		return p != null && p.getState() == Plugin.State.ACTIVE;
	}
}
