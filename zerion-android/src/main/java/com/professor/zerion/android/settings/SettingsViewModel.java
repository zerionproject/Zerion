package com.professor.zerion.android.settings;

import android.app.Application;
import android.content.ContentResolver;
import android.net.Uri;
import android.widget.Toast;

import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.zerionproject.core.api.system.AndroidExecutor;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.UnsupportedMimeTypeException;
import com.professor.zerion.android.attachment.media.ImageCompressor;
import com.professor.zerion.android.viewmodel.DbViewModel;
import org.zerionproject.app.api.avatar.AvatarManager;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.identity.AuthorManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.LocationUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.AnyThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static android.widget.Toast.LENGTH_LONG;
import static java.util.Arrays.asList;
import static org.zerionproject.core.util.AndroidUtils.getSupportedImageContentTypes;
import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREEN_LOCK;
import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREEN_LOCK_TIMEOUT;
import static com.professor.zerion.android.settings.SettingsFragment.SETTINGS_NAMESPACE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class SettingsViewModel extends DbViewModel implements EventListener {

	static final String TOR_NAMESPACE = TorConstants.ID.getString();

	private final SettingsManager settingsManager;
	private final IdentityManager identityManager;
	private final EventBus eventBus;
	private final AvatarManager avatarManager;
	private final AuthorManager authorManager;
	private final ImageCompressor imageCompressor;
	private final Executor ioExecutor;
	private final FeatureFlags featureFlags;

	final SettingsStore settingsStore;
	final TorSummaryProvider torSummaryProvider;
	final ConnectionsManager connectionsManager;
	final NotificationsManager notificationsManager;

	private volatile Settings settings;

	private final MutableLiveData<OwnIdentityInfo> ownIdentityInfo =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> screenLockEnabled =
			new MutableLiveData<>();
	private final MutableLiveData<String> screenLockTimeout =
			new MutableLiveData<>();

	@Inject
	SettingsViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			SettingsManager settingsManager,
			IdentityManager identityManager,
			EventBus eventBus,
			AvatarManager avatarManager,
			AuthorManager authorManager,
			ImageCompressor imageCompressor,
			LocationUtils locationUtils,
			CircumventionProvider circumventionProvider,
			@IoExecutor Executor ioExecutor,
			FeatureFlags featureFlags) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.settingsManager = settingsManager;
		this.identityManager = identityManager;
		this.eventBus = eventBus;
		this.imageCompressor = imageCompressor;
		this.avatarManager = avatarManager;
		this.authorManager = authorManager;
		this.ioExecutor = ioExecutor;
		this.featureFlags = featureFlags;
		settingsStore = new SettingsStore(getApplication(), settingsManager,
				dbExecutor, SETTINGS_NAMESPACE);
		torSummaryProvider = new TorSummaryProvider(getApplication(),
				locationUtils, circumventionProvider);
		connectionsManager =
				new ConnectionsManager(getApplication(), settingsManager,
						dbExecutor);
		notificationsManager = new NotificationsManager(getApplication(),
				settingsManager, dbExecutor);

		eventBus.addListener(this);
		loadSettings();
		if (shouldEnableProfilePictures()) loadOwnIdentityInfo();
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	private void loadSettings() {
		runOnDbThread(() -> {
			try {
				settings = settingsManager.getSettings(SETTINGS_NAMESPACE);
				updateSettings(settings);
				connectionsManager.updateTorSettings(
						settingsManager.getSettings(TOR_NAMESPACE));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	boolean shouldEnableProfilePictures() {
		return featureFlags.shouldEnableProfilePictures();
	}

	private void loadOwnIdentityInfo() {
		runOnDbThread(() -> {
			try {
				LocalAuthor localAuthor = identityManager.getLocalAuthor();
				AuthorInfo authorInfo = authorManager.getMyAuthorInfo();
				ownIdentityInfo.postValue(
						new OwnIdentityInfo(localAuthor, authorInfo));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			String namespace = s.getNamespace();
			if (namespace.equals(SETTINGS_NAMESPACE)) {
				settings = s.getSettings();
				updateSettings(settings);
			} else if (namespace.equals(TOR_NAMESPACE)) {
				connectionsManager.updateTorSettings(s.getSettings());
			}
		}
	}

	@AnyThread
	private void updateSettings(Settings settings) {
		screenLockEnabled.postValue(settings.getBoolean(PREF_SCREEN_LOCK,
				false));
		int defaultTimeout = Integer.parseInt(getApplication()
				.getString(R.string.pref_lock_timeout_value_default));
		screenLockTimeout.postValue(String.valueOf(
				settings.getInt(PREF_SCREEN_LOCK_TIMEOUT, defaultTimeout)
		));
		notificationsManager.updateSettings(settings);
	}

	void setAvatar(Uri uri) {
		ioExecutor.execute(() -> {
			try {
				trySetAvatar(uri);
			} catch (IOException e) {
				onSetAvatarFailed();
			}
		});
	}

	private void trySetAvatar(Uri uri) throws IOException {
		ContentResolver contentResolver =
				getApplication().getContentResolver();
		String contentType = contentResolver.getType(uri);
		if (contentType == null) throw new IOException("null content type");
		if (!asList(getSupportedImageContentTypes()).contains(contentType)) {
			throw new UnsupportedMimeTypeException(contentType, uri);
		}
		InputStream is;
		try {
			is = contentResolver.openInputStream(uri);
			if (is == null) throw new IOException(
					"ContentResolver returned null when opening InputStream");
		} catch (SecurityException e) {
			throw new IOException(e);
		}
		InputStream compressed = imageCompressor.compressImage(is, contentType);

		runOnDbThread(() -> {
			try {
				avatarManager.addAvatar(ImageCompressor.MIME_TYPE, compressed);
				loadOwnIdentityInfo();
			} catch (IOException | DbException e) {
				onSetAvatarFailed();
			}
		});
	}

	@AnyThread
	private void onSetAvatarFailed() {
		androidExecutor.runOnUiThread(() -> Toast.makeText(getApplication(),
				R.string.change_profile_picture_failed_message, LENGTH_LONG)
				.show());
	}

	LiveData<OwnIdentityInfo> getOwnIdentityInfo() {
		return ownIdentityInfo;
	}

	LiveData<Boolean> getScreenLockEnabled() {
		return screenLockEnabled;
	}

	LiveData<String> getScreenLockTimeout() {
		return screenLockTimeout;
	}

	private final com.professor.zerion.android.viewmodel.MutableLiveEvent<String> myFingerprint =
			new com.professor.zerion.android.viewmodel.MutableLiveEvent<>();

	public com.professor.zerion.android.viewmodel.LiveEvent<String> getMyFingerprint() {
		return myFingerprint;
	}

	public void loadMyFingerprint() {
		runOnDbThread(() -> {
			try {
				byte[] localPub = identityManager.getLocalAuthor()
						.getPublicKey().getEncoded();
				String fp = com.professor.zerion.android.contact.identity
						.IdentityFingerprint.forSigningPub(localPub);
				myFingerprint.postEvent(fp);
			} catch (org.zerionproject.core.api.db.DbException e) {
				handleException(e);
			}
		});
	}

}
