package com.professor.zerion.android.profile;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.vault.crypto.VaultKeystore;
import com.professor.zerion.android.vault.storage.LegacyVaultLocation;
import com.professor.zerion.android.vault.storage.VaultLocation;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.LoginThrottle;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.Arrays.asList;
import static org.zerionproject.core.account.ProfileManager.DEFAULT_PROFILE_ID;
import static org.zerionproject.core.util.StringUtils.toHexString;

@Singleton
@NotNullByDefault
public class ProfileStorage implements ProfileManager.SessionListener {

	static final String SETTINGS_FILE = "settings" + File.separator
			+ "profile.prefs";
	static final String VAULT_DIR = "vault";
	static final String VAULT_FROM_DEVICE_MARK = "vault.legacy";
	static final String WALLET_DIR = "wallet";
	static final String STICKERS_DIR = "stickers";
	static final String DEVICE_VAULT_CLAIM = "vault.claim";

	private static final String DEVICE_STICKERS_DIR = "stickers";
	private static final String DEVICE_XMR_DIR = "xmr";
	private static final String LEGACY_CHAT_SETTINGS = "chat_settings";
	private static final String VAULT_THROTTLE_FILE = "unlock.throttle";
	private static final String SETTINGS_KEY_LABEL =
			"org.zerionproject/PROFILE_SETTINGS_KEY";
	private static final String TAG_LABEL = "org.zerionproject/PROFILE_TAG";

	static final List<String> DEVICE_SECURE_PREFIXES = asList("mute_",
			"vibration_", "timer_", "channel_draft_",
			"channel_comment_draft_");
	static final List<String> DEVICE_SECURE_KEYS = asList("autolock_timeout",
			"clipboard_clear_enabled", "clipboard_timeout",
			"hide_content_enabled", "vault_sort_mode");
	static final List<String> DEVICE_UI_KEYS =
			asList("pinned_contact_ids");
	static final List<String> DEVICE_UI_TOGGLES = asList(
			"pref_typing_indicators", "voice_calls_enabled",
			"video_calls_enabled", "pref_key_notify_quick_reply",
			"default_disappearing_timer");

	private final Context appContext;
	private final ProfileManager profileManager;
	private final AccountManager accountManager;
	private final CryptoComponent crypto;
	private final SharedPreferences deviceSecurePrefs;
	private final SharedPreferences deviceUiPrefs;
	private final ProfilePreferences preferences;
	private final Executor writer;
	private final Object lock = new Object();

	@GuardedBy("lock")
	@Nullable
	private ProfileSettingsFile settings;
	@GuardedBy("lock")
	@Nullable
	private String settingsProfile;
	@GuardedBy("lock")
	private boolean deviceDataMoved = false;
	@GuardedBy("lock")
	private boolean movingDeviceData = false;
	@GuardedBy("lock")
	@Nullable
	private VaultPlacement vaultPlacement;
	@GuardedBy("lock")
	private boolean sharedVaultUnlocked = false;
	@GuardedBy("lock")
	private boolean sharedVaultClaimOffered = false;

	@Inject
	public ProfileStorage(Application app, ProfileManager profileManager,
			AccountManager accountManager, CryptoComponent crypto,
			@AppModule.SecurePrefs SharedPreferences deviceSecurePrefs,
			@AppModule.UiPrefs SharedPreferences deviceUiPrefs) {
		this(app.getApplicationContext(), profileManager, accountManager,
				crypto, deviceSecurePrefs, deviceUiPrefs,
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "ProfileSettings");
					t.setDaemon(true);
					return t;
				}));
		profileManager.setSessionListener(this);
	}

	ProfileStorage(Context appContext, ProfileManager profileManager,
			AccountManager accountManager, CryptoComponent crypto,
			SharedPreferences deviceSecurePrefs,
			SharedPreferences deviceUiPrefs, Executor writer) {
		this.appContext = appContext;
		this.profileManager = profileManager;
		this.accountManager = accountManager;
		this.crypto = crypto;
		this.deviceSecurePrefs = deviceSecurePrefs;
		this.deviceUiPrefs = deviceUiPrefs;
		this.writer = writer;
		this.preferences = new ProfilePreferences(this);
	}

	@Override
	public void onSessionStarted(String profileId) {
		try {
			ensureDeviceDataMoved(profileId);
		} catch (RuntimeException ignored) {
		}
		try {
			placeVault(profileId);
		} catch (RuntimeException ignored) {
		}
	}

	public boolean hasSession() {
		return profileManager.getSessionProfileId() != null;
	}

	private String requireSession() {
		String id = profileManager.getSessionProfileId();
		if (id == null) throw new IllegalStateException("No profile session");
		return id;
	}

	private File root(String profileId) {
		return profileManager.getProfileRoot(profileId);
	}

	public SharedPreferences preferences() {
		return preferences;
	}

	public File stickerDir() {
		return profileDir(STICKERS_DIR);
	}

	private File profileDir(String name) {
		String id = requireSession();
		ensureDeviceDataMoved(id);
		File dir = new File(root(id), name);
		if (!dir.isDirectory()) dir.mkdirs();
		return dir;
	}

	@Nullable
	ProfileSettingsFile settingsOrNull() {
		String id = profileManager.getSessionProfileId();
		if (id == null) return null;
		ProfileSettingsFile file = openSettings(id);
		if (file != null) ensureDeviceDataMoved(id);
		return file;
	}

	@Nullable
	private ProfileSettingsFile openSettings(String profileId) {
		synchronized (lock) {
			if (settings != null && profileId.equals(settingsProfile)) {
				return settings;
			}
			SecretKey dbKey = accountManager.getDatabaseKey();
			if (dbKey == null) return null;
			SecretKey derived = crypto.deriveKey(SETTINGS_KEY_LABEL, dbKey);
			try {
				settings = ProfileSettingsFile.open(
						new File(root(profileId), SETTINGS_FILE),
						derived.getBytes(), writer);
			} finally {
				derived.clear();
			}
			settingsProfile = profileId;
			return settings;
		}
	}

	private void ensureDeviceDataMoved(String profileId) {
		synchronized (lock) {
			if (deviceDataMoved || movingDeviceData) return;
			movingDeviceData = true;
		}
		boolean done = false;
		try {
			done = !ownsDeviceData(profileId) || moveDeviceData(profileId);
		} finally {
			synchronized (lock) {
				movingDeviceData = false;
				if (done) deviceDataMoved = true;
			}
		}
	}

	private boolean ownsDeviceData(String profileId) {
		return profileId.equals(DEFAULT_PROFILE_ID)
				|| !profileManager.hasKeyFiles(DEFAULT_PROFILE_ID);
	}

	private boolean moveDeviceData(String profileId) {
		ProfileSettingsFile file = openSettings(profileId);
		if (file == null) return false;
		boolean sole = isSoleKeyHolder(profileId);
		Map<String, ?> secure = deviceSecurePrefs.getAll();
		Map<String, ?> ui = deviceUiPrefs.getAll();
		File legacyChatFile = legacyChatSettingsFile();
		Map<String, ?> legacyChat = legacyChatFile.exists()
				? appContext.getSharedPreferences(LEGACY_CHAT_SETTINGS,
						Context.MODE_PRIVATE).getAll()
				: java.util.Collections.emptyMap();
		List<String> secureMoved = new ArrayList<>();
		List<String> uiMoved = new ArrayList<>();
		SharedPreferences.Editor editor = file.edit();
		boolean changed = false;
		for (Map.Entry<String, ?> e : legacyChat.entrySet()) {
			if (belongsToProfile(e.getKey())
					&& (sole || !namesAContactOrChannel(e.getKey()))) {
				changed |= copyIfAbsent(file, editor, e.getKey(),
						e.getValue());
			}
		}
		for (Map.Entry<String, ?> e : secure.entrySet()) {
			if (!belongsToProfile(e.getKey())) continue;
			if (sole || !namesAContactOrChannel(e.getKey())) {
				changed |= copyIfAbsent(file, editor, e.getKey(),
						e.getValue());
			}
			secureMoved.add(e.getKey());
		}
		for (Map.Entry<String, ?> e : ui.entrySet()) {
			if (DEVICE_UI_KEYS.contains(e.getKey())) {
				if (sole) {
					changed |= copyIfAbsent(file, editor, e.getKey(),
							e.getValue());
				}
				uiMoved.add(e.getKey());
			} else if (DEVICE_UI_TOGGLES.contains(e.getKey())) {
				changed |= copyIfAbsent(file, editor, e.getKey(),
						e.getValue());
				uiMoved.add(e.getKey());
			}
		}
		if (changed && !editor.commit()) return false;
		if (!secureMoved.isEmpty()) {
			SharedPreferences.Editor s = deviceSecurePrefs.edit();
			for (String k : secureMoved) s.remove(k);
			if (!s.commit()) return false;
		}
		if (!uiMoved.isEmpty()) {
			SharedPreferences.Editor u = deviceUiPrefs.edit();
			for (String k : uiMoved) u.remove(k);
			if (!u.commit()) return false;
		}
		if (legacyChatFile.exists()) {
			appContext.getSharedPreferences(LEGACY_CHAT_SETTINGS,
					Context.MODE_PRIVATE).edit().clear().commit();
			SecureMemory.secureDeleteFile(legacyChatFile, 0L, false);
		}
		return sole ? moveDeviceStickers(profileId) : settleDeviceStickers();
	}

	static boolean belongsToProfile(String key) {
		if (DEVICE_SECURE_KEYS.contains(key)) return true;
		return namesAContactOrChannel(key);
	}

	static boolean namesAContactOrChannel(String key) {
		for (String p : DEVICE_SECURE_PREFIXES) {
			if (key.startsWith(p)) return true;
		}
		return false;
	}

	private static boolean copyIfAbsent(SharedPreferences target,
			SharedPreferences.Editor editor, String key, Object value) {
		if (target.contains(key)) return false;
		if (value instanceof Boolean) {
			editor.putBoolean(key, (Boolean) value);
		} else if (value instanceof Integer) {
			editor.putInt(key, (Integer) value);
		} else if (value instanceof Long) {
			editor.putLong(key, (Long) value);
		} else if (value instanceof Float) {
			editor.putFloat(key, (Float) value);
		} else if (value instanceof String) {
			editor.putString(key, (String) value);
		} else if (value instanceof Set) {
			@SuppressWarnings("unchecked")
			Set<String> set = (Set<String>) value;
			editor.putStringSet(key, set);
		} else {
			return false;
		}
		return true;
	}

	private File legacyChatSettingsFile() {
		return new File(new File(appContext.getApplicationInfo().dataDir,
				"shared_prefs"), LEGACY_CHAT_SETTINGS + ".xml");
	}

	private boolean moveDeviceStickers(String profileId) {
		File device = new File(appContext.getFilesDir(), DEVICE_STICKERS_DIR);
		if (!device.isDirectory()) return true;
		File target = new File(root(profileId), STICKERS_DIR);
		if (!target.isDirectory() && !target.mkdirs()) return false;
		File[] files = device.listFiles();
		boolean all = true;
		if (files != null) {
			for (File f : files) {
				File dest = new File(target, f.getName());
				if (dest.exists() || !f.renameTo(dest)) all = false;
			}
		}
		if (all) device.delete();
		return all;
	}

	private boolean settleDeviceStickers() {
		File device = new File(appContext.getFilesDir(), DEVICE_STICKERS_DIR);
		if (!device.isDirectory()) return true;
		if (holdsVault(LegacyVaultLocation.directoryOf(appContext))) {
			return true;
		}
		SecureMemory.secureDeleteDir(device, 0L);
		return !device.exists();
	}

	private static final class VaultPlacement {

		final File dir;
		final String keyAlias;
		final boolean shared;
		final boolean inDeviceDirectory;

		VaultPlacement(File dir, String keyAlias, boolean shared,
				boolean inDeviceDirectory) {
			this.dir = dir;
			this.keyAlias = keyAlias;
			this.shared = shared;
			this.inDeviceDirectory = inDeviceDirectory;
		}
	}

	private VaultPlacement placeVault(String profileId) {
		synchronized (lock) {
			if (vaultPlacement != null) return vaultPlacement;
			vaultPlacement = decideVaultPlacement(profileId);
			return vaultPlacement;
		}
	}

	@GuardedBy("lock")
	private VaultPlacement decideVaultPlacement(String profileId) {
		File own = new File(root(profileId), VAULT_DIR);
		File mark = new File(root(profileId), VAULT_FROM_DEVICE_MARK);
		File device = LegacyVaultLocation.directoryOf(appContext);
		String ownAlias = VaultKeystore.profileKeyAlias(tag(profileId));
		if (mark.exists()) {
			if (!moveVaultFromDevice(device, own)) {
				return new VaultPlacement(device,
						VaultKeystore.LEGACY_KEY_ALIAS, false, true);
			}
			moveDeviceWalletFiles(profileId);
			moveDeviceStickers(profileId);
			return new VaultPlacement(own, VaultKeystore.LEGACY_KEY_ALIAS,
					false, false);
		}
		if (!holdsVault(device) || holdsVault(own)) {
			return new VaultPlacement(own, ownAlias, false, false);
		}
		String claim = readClaim();
		if (claim != null && !claimHolderExists(claim)) {
			deleteClaim();
			claim = null;
		}
		if (isSoleKeyHolder(profileId) || tag(profileId).equals(claim)) {
			try {
				LoginThrottle.writeDurably(mark, new byte[0]);
			} catch (IOException e) {
				return new VaultPlacement(device,
						VaultKeystore.LEGACY_KEY_ALIAS, true, true);
			}
			if (!moveVaultFromDevice(device, own)) {
				return new VaultPlacement(device,
						VaultKeystore.LEGACY_KEY_ALIAS, false, true);
			}
			deleteClaim();
			moveDeviceWalletFiles(profileId);
			moveDeviceStickers(profileId);
			return new VaultPlacement(own, VaultKeystore.LEGACY_KEY_ALIAS,
					false, false);
		}
		if (claim != null) {
			return new VaultPlacement(own, ownAlias, false, false);
		}
		return new VaultPlacement(device, VaultKeystore.LEGACY_KEY_ALIAS,
				true, true);
	}

	private static boolean moveVaultFromDevice(File device, File own) {
		if (!holdsVault(device)) return true;
		if (holdsVault(own)) return true;
		if (own.isDirectory()) org.zerionproject.core.util.IoUtils
				.deleteFileOrDir(own);
		File parent = own.getParentFile();
		if (parent != null && !parent.isDirectory()) parent.mkdirs();
		return device.renameTo(own);
	}

	private void moveDeviceWalletFiles(String profileId) {
		File device = new File(appContext.getNoBackupFilesDir(),
				DEVICE_XMR_DIR);
		if (!device.exists()) return;
		File base = new File(root(profileId), WALLET_DIR);
		File target = new File(base, DEVICE_XMR_DIR);
		if (target.exists()) return;
		if (!base.isDirectory()) base.mkdirs();
		device.renameTo(target);
	}

	static boolean holdsVault(File dir) {
		File[] children = dir.listFiles();
		if (children == null) return false;
		for (File c : children) {
			if (c.isDirectory()) {
				if (holdsVault(c)) return true;
			} else if (!c.getName().equals(VAULT_THROTTLE_FILE)) {
				return true;
			}
		}
		return false;
	}

	private boolean isSoleKeyHolder(String profileId) {
		for (String id : profileManager.listProfileIds()) {
			if (!id.equals(profileId) && profileManager.hasKeyFiles(id)) {
				return false;
			}
		}
		return true;
	}

	private File claimFile() {
		return new File(appContext.getNoBackupFilesDir(), DEVICE_VAULT_CLAIM);
	}

	@Nullable
	private String readClaim() {
		File f = claimFile();
		if (!f.isFile() || f.length() > 128) return null;
		byte[] b = new byte[(int) f.length()];
		try (FileInputStream in = new FileInputStream(f)) {
			int read = 0;
			while (read < b.length) {
				int n = in.read(b, read, b.length - read);
				if (n < 0) return null;
				read += n;
			}
		} catch (IOException e) {
			return null;
		}
		return new String(b, StandardCharsets.UTF_8);
	}

	private void deleteClaim() {
		File f = claimFile();
		if (f.exists()) f.delete();
	}

	private boolean claimHolderExists(String claim) {
		for (String id : profileManager.listProfileIds()) {
			if (profileManager.hasKeyFiles(id) && tag(id).equals(claim)) {
				return true;
			}
		}
		return false;
	}

	private void onVaultUnlocked() {
		synchronized (lock) {
			sharedVaultUnlocked = true;
		}
	}

	public boolean offersSharedVaultClaim() {
		String id = profileManager.getSessionProfileId();
		if (id == null || !placeVault(id).shared) return false;
		synchronized (lock) {
			if (!sharedVaultUnlocked || sharedVaultClaimOffered) return false;
		}
		String claim = readClaim();
		return claim == null || !claimHolderExists(claim);
	}

	public void sharedVaultClaimOffered() {
		synchronized (lock) {
			sharedVaultClaimOffered = true;
		}
	}

	public boolean claimSharedVault() {
		String id = profileManager.getSessionProfileId();
		if (id == null || !placeVault(id).shared) return false;
		synchronized (lock) {
			if (!sharedVaultUnlocked) return false;
		}
		String claim = readClaim();
		if (claim != null && claimHolderExists(claim)) return false;
		try {
			LoginThrottle.writeDurably(claimFile(),
					tag(id).getBytes(StandardCharsets.UTF_8));
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public void forgetClaimOf(String profileId) {
		if (tag(profileId).equals(readClaim())) deleteClaim();
	}

	String tag(String profileId) {
		byte[] h = crypto.hash(TAG_LABEL,
				profileId.getBytes(StandardCharsets.UTF_8));
		return toHexString(Arrays.copyOf(h, 16)).toLowerCase(
				java.util.Locale.US);
	}

	public boolean vaultIsShared() {
		String id = profileManager.getSessionProfileId();
		return id != null && placeVault(id).shared;
	}

	public VaultLocation vaultLocation() {
		return new VaultLocation() {
			@Override
			public boolean isAvailable() {
				return hasSession();
			}

			@Override
			public File directory() {
				return placeVault(requireSession()).dir;
			}

			@Override
			public String keyAlias() {
				return placeVault(requireSession()).keyAlias;
			}

			@Override
			public List<File> allVaultDirectories() {
				List<File> dirs = new ArrayList<>();
				dirs.add(LegacyVaultLocation.directoryOf(appContext));
				for (String id : profileManager.listProfileIds()) {
					dirs.add(new File(root(id), VAULT_DIR));
				}
				return dirs;
			}

			@Override
			public void onUnlocked() {
				onVaultUnlocked();
			}
		};
	}

	public Context walletContext() {
		File base = walletBase();
		return new ContextWrapper(appContext) {
			@Override
			public Context getApplicationContext() {
				return this;
			}

			@Override
			public File getNoBackupFilesDir() {
				return base;
			}
		};
	}

	private File walletBase() {
		String id = requireSession();
		ensureDeviceDataMoved(id);
		if (placeVault(id).inDeviceDirectory) {
			return appContext.getNoBackupFilesDir();
		}
		File base = new File(root(id), WALLET_DIR);
		if (!base.isDirectory()) base.mkdirs();
		return base;
	}
}
