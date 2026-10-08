package com.professor.zerion.android.vault;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import javax.inject.Inject;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;
import com.professor.zerion.android.vault.crypto.VaultKeystore;
import com.professor.zerion.android.vault.model.VaultHeader;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.storage.LegacyVaultLocation;
import com.professor.zerion.android.vault.storage.SecureFileIO;
import com.professor.zerion.android.vault.storage.VaultLocation;
import com.professor.zerion.android.vault.utils.MetadataStripper;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.zerionproject.core.account.PasswordNormalizer;

@NotNullByDefault
public class VaultManager
		implements com.professor.zerion.android.vault.wallet.xmr.VaultGate {
	public static final String TOO_MANY_ATTEMPTS = "Too many failed attempts";
	private static final String HEADER_FILE = "vault.header";
	private static final String HEADER_NEW_FILE = "vault.header.new";
	private static final String ITEMS_DIR = "items";
	private static final String ITEMS_BACKUP_DIR = "items_rekey_backup";
	private static final String ITEMS_TEMP_DIR = "items_rekey_temp";
	private static final byte[] EXPORT_META_AAD =
			"zerion-vault-export-meta".getBytes(
					java.nio.charset.StandardCharsets.UTF_8);
	private static final long AUTO_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

	private final Context context;
	private final VaultLocation location;
	private final VaultKeystore keystore;
	private final VaultCrypto crypto;
	private final Argon2 argon2;
	private final SecureFileIO fileIO;
	private final MetadataStripper metadataStripper;

	private VaultHeader currentHeader;
	private byte[] vaultMasterKey;
	private volatile long lastActivityTime;
	private volatile boolean isUnlocked = false;
	private final UnlockThrottle unlockThrottle;
	private volatile long lockGeneration = 0;
	private volatile Runnable onLockListener = null;

	private volatile List<VaultItem> cachedItems = null;
	private volatile long cacheTimestamp = 0;
	private static final long CACHE_VALIDITY_MS = 10000;

	private static final String UNLOCK_THROTTLE_FILE = "unlock.throttle";
	private static final String KEK_INFO = "vault kek v2";
	private static final byte[] MASTER_AAD = "zerion-vault-master-v2"
			.getBytes(StandardCharsets.UTF_8);

	public static final Argon2.Argon2Params STRONG_KDF =
			Argon2.Argon2Params.getDefault();
	public static final Argon2.Argon2Params REDUCED_KDF =
			new Argon2.Argon2Params(128 * 1024, 6, 1, 32);

	public interface PasswordKdf {
		byte[] derive(char[] password, byte[] salt,
				Argon2.Argon2Params params);
	}

	private final PasswordKdf kdf;

	@Inject
	public VaultManager(Context context) {
		this(context, new LegacyVaultLocation(context));
	}

	public VaultManager(Context context, VaultLocation location) {
		this(context, location, null);
	}

	public VaultManager(Context context, VaultLocation location,
			@Nullable PasswordKdf kdf) {
		this.context = context.getApplicationContext();
		this.location = location;
		try {
			this.keystore = new VaultKeystore(context, location);
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize vault keystore", e);
		}
		this.crypto = new VaultCrypto();
		this.argon2 = new Argon2();
		this.kdf = kdf != null ? kdf : argon2::deriveKey;
		this.fileIO = new SecureFileIO(context, location);
		this.metadataStripper = new MetadataStripper(context);
		this.unlockThrottle = new UnlockThrottle();

		this.lastActivityTime = android.os.SystemClock.elapsedRealtime();
	}

	private final class UnlockThrottle {

		@Nullable
		private org.zerionproject.core.account.LoginThrottle throttle;

		private synchronized org.zerionproject.core.account.LoginThrottle get() {
			if (throttle == null) {
				throttle = new org.zerionproject.core.account.LoginThrottle(
						org.zerionproject.core.account.LoginThrottle.fileStore(
								new java.io.File(fileIO.getVaultDir(),
										UNLOCK_THROTTLE_FILE)),
						android.os.SystemClock::elapsedRealtime,
						org.zerionproject.core.account.LoginThrottle
								.linuxBootId(),
						org.zerionproject.core.account.LoginThrottle.VAULT);
			}
			return throttle;
		}

		long remainingLockoutMs() {
			return get().remainingLockoutMs();
		}

		void recordFailure() {
			get().recordFailure();
		}

		void reset() {
			get().reset();
		}
	}

	public boolean vaultExists() {
		if (!location.isAvailable()) return false;
		return fileIO.exists(HEADER_FILE);
	}

	public synchronized void createVault(char[] typed) throws Exception {
		if (vaultExists()) {
			throw new IllegalStateException("Vault already exists");
		}
		char[] password = PasswordNormalizer.normalize(typed);
		byte[] masterKey = crypto.generateKey();
		try {
			VaultHeader header = sealMasterKey(masterKey, password,
					VaultKeystore.SLOT_A, argon2.generateSalt(16),
					System.currentTimeMillis());
			fileIO.writeSecure(HEADER_FILE, header.toBytes());
			fileIO.createDirectory(ITEMS_DIR);
			this.currentHeader = header;
			this.vaultMasterKey = masterKey;
			masterKey = null;
			this.isUnlocked = true;
			updateActivity();
		} finally {
			if (masterKey != null) SecureMemory.shred(masterKey);
			java.util.Arrays.fill(password, '\0');
		}
	}

	private static final class DerivedKey {

		final byte[] key;
		final Argon2.Argon2Params params;

		DerivedKey(byte[] key, Argon2.Argon2Params params) {
			this.key = key;
			this.params = params;
		}
	}

	private DerivedKey deriveForNewHeader(char[] password, byte[] salt) {
		try {
			return new DerivedKey(kdf.derive(password, salt, STRONG_KDF),
					STRONG_KDF);
		} catch (OutOfMemoryError | RuntimeException e) {
			return new DerivedKey(kdf.derive(password, salt, REDUCED_KDF),
					REDUCED_KDF);
		}
	}

	private VaultHeader sealMasterKey(byte[] masterKey, char[] password,
			int slot, byte[] bioSalt, long created) throws Exception {
		byte[] salt = argon2.generateSalt();
		keystore.createSlot(slot);
		byte[] randomSecret = crypto.generateKey();
		byte[] passwordKey = null;
		byte[] hashed = null;
		byte[] combined = null;
		byte[] kek = null;
		try {
			SecretKey wrapKey = keystore.wrapKey(slot);
			if (wrapKey == null) throw new IOException("Vault key missing");
			byte[] wrappedSecret = keystore.wrapSecret(randomSecret, wrapKey);
			DerivedKey derived = deriveForNewHeader(password, salt);
			passwordKey = derived.key;
			hashed = keystore.hmac(slot, passwordKey);
			combined = crypto.xor(hashed, randomSecret);
			kek = crypto.hkdfSha256(combined, salt, KEK_INFO, 32);
			byte[] wrappedMaster = crypto.encrypt(masterKey, kek,
					masterAad(salt)).toBytes();
			return VaultHeader.createKeystoreFactor(salt,
					derived.params.memoryKb, derived.params.iterations,
					wrappedSecret, bioSalt,
					crypto.computePasswordVerificationMac(masterKey), created,
					slot, wrappedMaster);
		} catch (Exception | OutOfMemoryError e) {
			keystore.deleteSlot(slot);
			throw e instanceof Exception ? (Exception) e
					: new IOException("Key derivation failed");
		} finally {
			SecureMemory.shredAll(randomSecret);
			if (passwordKey != null) SecureMemory.shred(passwordKey);
			if (hashed != null) SecureMemory.shred(hashed);
			if (combined != null) SecureMemory.shred(combined);
			if (kek != null) SecureMemory.shred(kek);
		}
	}

	private static byte[] masterAad(byte[] salt) {
		byte[] aad = new byte[MASTER_AAD.length + salt.length];
		System.arraycopy(MASTER_AAD, 0, aad, 0, MASTER_AAD.length);
		System.arraycopy(salt, 0, aad, MASTER_AAD.length, salt.length);
		return aad;
	}

	@Nullable
	private byte[] openMasterKey(VaultHeader header, char[] password)
			throws Exception {
		Argon2.Argon2Params params = new Argon2.Argon2Params(
				header.kdfMemoryKb, header.kdfIterations,
				header.kdfParallelism, 32);
		if (header.version < VaultHeader.VERSION_KEYSTORE_FACTOR) {
			return openVersionOneMasterKey(header, password, params);
		}
		SecretKey wrapKey = keystore.wrapKey(header.keySlot);
		if (wrapKey == null) throw new IOException("Vault key missing");
		byte[] randomSecret = keystore.unwrapSecret(
				header.wrappedKeystoreBlob, wrapKey);
		byte[] passwordKey = kdf.derive(password, header.salt, params);
		byte[] hashed = null;
		byte[] combined = null;
		byte[] kek = null;
		try {
			hashed = keystore.hmac(header.keySlot, passwordKey);
			combined = crypto.xor(hashed, randomSecret);
			kek = crypto.hkdfSha256(combined, header.salt, KEK_INFO, 32);
			byte[] master;
			try {
				master = crypto.decrypt(VaultCrypto.EncryptedData.fromBytes(
						header.wrappedMasterKey), kek, masterAad(header.salt));
			} catch (RuntimeException wrongPassword) {
				return null;
			}
			if (!crypto.verifyPasswordMac(master,
					header.passwordVerificationMac)) {
				SecureMemory.shred(master);
				return null;
			}
			return master;
		} finally {
			SecureMemory.shredAll(randomSecret, passwordKey);
			if (hashed != null) SecureMemory.shred(hashed);
			if (combined != null) SecureMemory.shred(combined);
			if (kek != null) SecureMemory.shred(kek);
		}
	}

	@Nullable
	private byte[] openVersionOneMasterKey(VaultHeader header,
			char[] password, Argon2.Argon2Params params) throws Exception {
		SecretKey keystoreKey = keystore.getOrCreateVaultKey();
		byte[] randomSecret = keystore.unwrapSecret(
				header.wrappedKeystoreBlob, keystoreKey);
		byte[] passwordKey = kdf.derive(password, header.salt, params);
		byte[] combined = crypto.xor(passwordKey, randomSecret);
		byte[] derivedKey = crypto.hkdfSha256(combined, header.salt,
				"vault master", 32);
		SecureMemory.shredAll(passwordKey, randomSecret, combined);
		if (header.passwordVerificationMac != null
				&& header.passwordVerificationMac.length > 0) {
			if (crypto.verifyPasswordMac(derivedKey,
					header.passwordVerificationMac)) {
				return derivedKey;
			}
			SecureMemory.shred(derivedKey);
			return null;
		}
		if (opensAnItem(derivedKey)) return derivedKey;
		SecureMemory.shred(derivedKey);
		return null;
	}

	private boolean opensAnItem(byte[] derivedKey) {
		if (!fileIO.exists(ITEMS_DIR)) return false;
		String[] itemDirs = fileIO.listFiles(ITEMS_DIR);
		Arrays.sort(itemDirs);
		int attempts = 0;
		for (String itemId : itemDirs) {
			if (itemId.startsWith(".")) continue;
			if (attempts >= 3) break;
			attempts++;
			try {
				String itemDir = ITEMS_DIR + "/" + itemId;
				if (!fileIO.exists(itemDir + "/header.bin")) continue;
				byte[] encryptedMetadata =
						fileIO.readSecure(itemDir + "/header.bin");
				byte[] metadataPlain = crypto.decrypt(
						VaultCrypto.EncryptedData.fromBytes(encryptedMetadata),
						derivedKey, itemId.getBytes());
				try {
					VaultItem.deserializeMetadata(metadataPlain);
					return true;
				} catch (Exception notAnItem) {
				} finally {
					SecureMemory.shred(metadataPlain);
				}
			} catch (Exception e) {
			}
		}
		return false;
	}

	private static final class OpenedVault {

		final byte[] masterKey;
		final boolean legacyForm;

		OpenedVault(byte[] masterKey, boolean legacyForm) {
			this.masterKey = masterKey;
			this.legacyForm = legacyForm;
		}
	}

	@Nullable
	private OpenedVault openWithEitherForm(VaultHeader header, char[] typed)
			throws Exception {
		char[] normal = PasswordNormalizer.normalize(typed);
		char[] legacy = PasswordNormalizer.legacyForm(typed, normal);
		try {
			byte[] master = openMasterKey(header, normal);
			if (master != null) return new OpenedVault(master, false);
			if (legacy == null) return null;
			master = openMasterKey(header, legacy);
			return master == null ? null : new OpenedVault(master, true);
		} finally {
			java.util.Arrays.fill(normal, '\0');
			if (legacy != null) java.util.Arrays.fill(legacy, '\0');
		}
	}

	boolean needsRewrap(VaultHeader header, boolean legacyForm) {
		if (legacyForm) return true;
		if (header.version < VaultHeader.VERSION_KEYSTORE_FACTOR) return true;
		return !isWrittenParams(header, STRONG_KDF)
				&& !isWrittenParams(header, REDUCED_KDF);
	}

	private static boolean isWrittenParams(VaultHeader header,
			Argon2.Argon2Params params) {
		return header.kdfMemoryKb == params.memoryKb
				&& header.kdfIterations == params.iterations
				&& header.kdfParallelism == params.parallelism;
	}

	private void rewrapHeader(byte[] masterKey, char[] typed) {
		char[] password = PasswordNormalizer.normalize(typed);
		VaultHeader old = currentHeader;
		int slot = old.version < VaultHeader.VERSION_KEYSTORE_FACTOR
				? VaultKeystore.SLOT_A : VaultKeystore.otherSlot(old.keySlot);
		try {
			VaultHeader header = sealMasterKey(masterKey, password, slot,
					old.biometricTokenSalt, old.createdTimestamp);
			fileIO.writeSecure(HEADER_FILE, header.toBytes());
			currentHeader = header;
			deleteKeysNotNamedBy(header);
		} catch (Exception | OutOfMemoryError e) {
		} finally {
			java.util.Arrays.fill(password, '\0');
		}
	}

	private void deleteKeysNotNamedBy(VaultHeader header) {
		if (header.version < VaultHeader.VERSION_KEYSTORE_FACTOR) {
			keystore.deleteSlot(VaultKeystore.SLOT_A);
			keystore.deleteSlot(VaultKeystore.SLOT_B);
			return;
		}
		keystore.deleteVersionOneKey();
		keystore.deleteSlot(VaultKeystore.otherSlot(header.keySlot));
	}

	@Nullable
	public synchronized Argon2.Argon2Params kdfParameters() {
		VaultHeader h = currentHeader;
		if (h == null) return null;
		return new Argon2.Argon2Params(h.kdfMemoryKb, h.kdfIterations,
				h.kdfParallelism, 32);
	}

	public synchronized boolean unlockVault(char[] password) throws Exception {
		if (!vaultExists()) {
			throw new IllegalStateException("No vault exists");
		}

		reconcileRekeyIfNeeded();

		long unlockStartRealtime = android.os.SystemClock.elapsedRealtime();
		try {
			long waitMs = unlockThrottle.remainingLockoutMs();
			if (waitMs > 0) {
				long waitSeconds = (waitMs + 999) / 1000;
				throw new SecurityException(
						TOO_MANY_ATTEMPTS + ". Wait " + waitSeconds
								+ " seconds");
			}
			if (currentHeader == null) {
				loadVaultHeader();
			}

			OpenedVault opened = openWithEitherForm(currentHeader, password);
			if (opened == null) {
				return registerFailedUnlock();
			}
			this.vaultMasterKey = opened.masterKey;
			if (needsRewrap(currentHeader, opened.legacyForm)) {
				rewrapHeader(opened.masterKey, password);
			} else {
				deleteKeysNotNamedBy(currentHeader);
			}

			unlockThrottle.reset();
			isUnlocked = true;
			updateActivity();

			invalidateCache();

			try {
				location.onUnlocked();
			} catch (RuntimeException ignored) {
			}

			return true;

		} catch (SecurityException e) {
			throw e;
		} catch (Exception e) {
			return registerFailedUnlock();
		} finally {
			enforceUnlockTimeFloor(unlockStartRealtime);
		}
	}

	private boolean registerFailedUnlock() {
		unlockThrottle.recordFailure();
		return false;
	}

	private static final long UNLOCK_TIME_FLOOR_MS = 1500L;

	private static void enforceUnlockTimeFloor(long startRealtime) {
		long elapsed = android.os.SystemClock.elapsedRealtime() - startRealtime;
		long sleep = UNLOCK_TIME_FLOOR_MS - elapsed;
		if (sleep > 0) {
			try {
				Thread.sleep(sleep);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public synchronized void lockVault() {
		if (vaultMasterKey != null) {
			SecureMemory.shred(vaultMasterKey);
			vaultMasterKey = null;
		}
		cachedItems = null;
		cacheTimestamp = 0;
		if (isUnlocked) {
			lockGeneration++;
		}
		isUnlocked = false;
		com.professor.zerion.android.util.SecureClipboard.clearIfOurs(context);
		com.professor.zerion.android.vault.share.VaultShareRegistry
				.releaseAll();
		com.professor.zerion.android.util.CacheSweeper
				.sweepDirAsync(context, "vault_share");

		SecureMemory.forceGarbageCollection();

		Runnable listener = onLockListener;
		if (listener != null) {
			try {
				listener.run();
			} catch (Throwable ignored) {
			}
		}
		for (Runnable r : lockListeners) {
			try {
				r.run();
			} catch (Throwable ignored) {
			}
		}
	}

	public void setOnLockListener(Runnable listener) {
		onLockListener = listener;
	}

	private final java.util.List<Runnable> lockListeners =
			new java.util.concurrent.CopyOnWriteArrayList<>();

	public void addLockListener(Runnable listener) {
		lockListeners.add(listener);
	}

	public long getLockGeneration() {
		return lockGeneration;
	}

	public synchronized void checkAutoLock() {
		if (isUnlocked && android.os.SystemClock.elapsedRealtime()
				- lastActivityTime > AUTO_LOCK_TIMEOUT_MS) {
			lockVault();
		}
	}

	public synchronized void updateActivity() {
		lastActivityTime = android.os.SystemClock.elapsedRealtime();
	}

	public synchronized <T> T withoutActivityRefresh(
			java.util.concurrent.Callable<T> access) throws Exception {
		long saved = lastActivityTime;
		try {
			return access.call();
		} finally {
			lastActivityTime = saved;
		}
	}

	public synchronized boolean isUnlocked() {
		if (this.isUnlocked && android.os.SystemClock.elapsedRealtime()
				- lastActivityTime > AUTO_LOCK_TIMEOUT_MS) {
			lockVault();
			return false;
		}
		return this.isUnlocked;
	}

	public VaultItem addMediaItem(VaultItem.ItemType type, String name, byte[] content, String mimeType)
			throws Exception {
		requireUnlocked();

		byte[] cleanedContent = metadataStripper.stripMetadata(content, mimeType);

		return addItemInternal(type, name, cleanedContent, null);
	}

	public VaultItem addItem(VaultItem.ItemType type, String name, byte[] content)
			throws Exception {
		return addItemInternal(type, name, content, null);
	}

	public VaultItem addItemWithPassword(VaultItem.ItemType type, String name,
			byte[] content, char[] extraPassword) throws Exception {
		if (extraPassword == null || extraPassword.length == 0) {
			throw new IllegalArgumentException("Extra password cannot be empty");
		}
		return addItemInternal(type, name, content, extraPassword);
	}

	private synchronized VaultItem addItemInternal(VaultItem.ItemType type, String name, byte[] content,
			char[] extraPassword) throws Exception {
		requireUnlocked();

		byte[] itemKey = null;
		byte[] passwordKey = null;
		byte[] passwordSalt = null;
		byte[] contentToEncrypt = content;
		Argon2.Argon2Params walletParams = extraPasswordParams();

		try {
			itemKey = crypto.generateKey();
			byte[] nonce = crypto.generateNonce();

			if (extraPassword != null && extraPassword.length > 0) {
				passwordSalt = new byte[32];
				new SecureRandom().nextBytes(passwordSalt);

				passwordKey = argon2.deriveKey(extraPassword, passwordSalt,
						walletParams);

				VaultCrypto.EncryptedData passwordEncrypted = crypto.encrypt(
						content, passwordKey, name.getBytes(StandardCharsets.UTF_8)
				);
				contentToEncrypt = passwordEncrypted.toBytes();
			}

			VaultCrypto.EncryptedData encryptedContent = crypto.encrypt(
					contentToEncrypt, itemKey, name.getBytes(StandardCharsets.UTF_8)
			);

			VaultCrypto.EncryptedData encryptedKey = crypto.encrypt(
					itemKey, vaultMasterKey, new byte[0]
			);

			VaultItem item;
			if (passwordSalt != null) {
				item = VaultItem.createNewWithPassword(
						type, name, content.length,
						encryptedKey.toBytes(), nonce, passwordSalt,
						walletParams.memoryKb, walletParams.iterations,
						walletParams.parallelism
				);
			} else {
				item = VaultItem.createNew(
						type, name, content.length,
						encryptedKey.toBytes(), nonce
				);
			}

			if (!fileIO.exists(ITEMS_DIR)) {
				fileIO.createDirectory(ITEMS_DIR);
			}

			String itemDir = ITEMS_DIR + "/" + item.id;
			fileIO.createDirectory(itemDir);

			byte[] contentBytes = encryptedContent.toBytes();
			int padSize = 4096;
			while (padSize < contentBytes.length + 4) padSize *= 2;
			byte[] paddedContent = new byte[padSize];
			new SecureRandom().nextBytes(paddedContent);
			paddedContent[0] = (byte) (contentBytes.length >> 24);
			paddedContent[1] = (byte) (contentBytes.length >> 16);
			paddedContent[2] = (byte) (contentBytes.length >> 8);
			paddedContent[3] = (byte) contentBytes.length;
			System.arraycopy(contentBytes, 0, paddedContent, 4, contentBytes.length);
			fileIO.writeSecure(itemDir + "/content.bin", paddedContent);
			Arrays.fill(paddedContent, (byte) 0);

			byte[] metadataPlain = item.serializeMetadata();
			VaultCrypto.EncryptedData encryptedMetadata = crypto.encrypt(
					metadataPlain, vaultMasterKey, item.id.getBytes()
			);
			fileIO.writeSecure(itemDir + "/header.bin", encryptedMetadata.toBytes());

			invalidateCache();

			return item;

		} finally {
			if (itemKey != null) SecureMemory.shred(itemKey);
			if (passwordKey != null) SecureMemory.shred(passwordKey);
			if (contentToEncrypt != content && contentToEncrypt != null) {
				SecureMemory.shred(contentToEncrypt);
			}
		}
	}

	private synchronized VaultItem importProtectedItem(VaultItem src,
			byte[] innerBlob) throws Exception {
		requireUnlocked();

		byte[] itemKey = null;
		try {
			itemKey = crypto.generateKey();
			byte[] nonce = crypto.generateNonce();

			VaultCrypto.EncryptedData encryptedContent = crypto.encrypt(
					innerBlob, itemKey,
					src.name.getBytes(StandardCharsets.UTF_8));
			VaultCrypto.EncryptedData encryptedKey = crypto.encrypt(
					itemKey, vaultMasterKey, new byte[0]);

			VaultItem item = VaultItem.createNewWithPassword(
					src.type, src.name, src.size,
					encryptedKey.toBytes(), nonce, src.extraPasswordSalt,
					src.extraPasswordMemoryKb, src.extraPasswordIterations,
					src.extraPasswordParallelism);

			if (!fileIO.exists(ITEMS_DIR)) {
				fileIO.createDirectory(ITEMS_DIR);
			}
			String itemDir = ITEMS_DIR + "/" + item.id;
			fileIO.createDirectory(itemDir);

			byte[] metadataPlain = item.serializeMetadata();
			VaultCrypto.EncryptedData encryptedMetadata = crypto.encrypt(
					metadataPlain, vaultMasterKey, item.id.getBytes());
			SecureMemory.shred(metadataPlain);
			fileIO.writeSecure(itemDir + "/header.bin",
					encryptedMetadata.toBytes());

			byte[] contentBytes = encryptedContent.toBytes();
			int padSize = 4096;
			while (padSize < contentBytes.length + 4) padSize *= 2;
			byte[] paddedContent = new byte[padSize];
			new SecureRandom().nextBytes(paddedContent);
			paddedContent[0] = (byte) (contentBytes.length >> 24);
			paddedContent[1] = (byte) (contentBytes.length >> 16);
			paddedContent[2] = (byte) (contentBytes.length >> 8);
			paddedContent[3] = (byte) contentBytes.length;
			System.arraycopy(contentBytes, 0, paddedContent, 4,
					contentBytes.length);
			fileIO.writeSecure(itemDir + "/content.bin", paddedContent);
			Arrays.fill(paddedContent, (byte) 0);

			invalidateCache();

			return item;
		} finally {
			if (itemKey != null) SecureMemory.shred(itemKey);
		}
	}

	public synchronized boolean itemHasExtraPassword(String itemId)
			throws Exception {
		requireUnlocked();
		String itemDir = ITEMS_DIR + "/" + itemId;
		byte[] metadataPlain = null;
		try {
			byte[] encryptedMetadata = fileIO.readSecure(itemDir + "/header.bin");
			VaultCrypto.EncryptedData metadataWrapper =
					VaultCrypto.EncryptedData.fromBytes(encryptedMetadata);
			metadataPlain = crypto.decrypt(
					metadataWrapper, vaultMasterKey, itemId.getBytes());
			VaultItem item = VaultItem.deserializeMetadata(metadataPlain);
			return item.hasExtraPassword;
		} finally {
			if (metadataPlain != null) SecureMemory.shred(metadataPlain);
		}
	}

	public synchronized byte[] getItemContent(String itemId) throws Exception {
		requireUnlocked();

		String itemDir = ITEMS_DIR + "/" + itemId;
		byte[] metadataPlain = null;
		byte[] itemKey = null;

		try {
			byte[] encryptedMetadata = fileIO.readSecure(itemDir + "/header.bin");
			VaultCrypto.EncryptedData metadataWrapper =
					VaultCrypto.EncryptedData.fromBytes(encryptedMetadata);
			metadataPlain = crypto.decrypt(
					metadataWrapper, vaultMasterKey, itemId.getBytes()
			);

			VaultItem item = VaultItem.deserializeMetadata(metadataPlain);

			VaultCrypto.EncryptedData encryptedKey =
					VaultCrypto.EncryptedData.fromBytes(item.encryptedKey);
			itemKey = crypto.decrypt(encryptedKey, vaultMasterKey, new byte[0]);

			byte[] rawContent = fileIO.readSecure(itemDir + "/content.bin");
			byte[] encryptedContent = stripPadding(rawContent);
			VaultCrypto.EncryptedData contentWrapper =
					VaultCrypto.EncryptedData.fromBytes(encryptedContent);
			byte[] content = crypto.decrypt(contentWrapper, itemKey, item.name.getBytes(StandardCharsets.UTF_8));

			return content;

		} finally {
			if (metadataPlain != null) SecureMemory.shred(metadataPlain);
			if (itemKey != null) SecureMemory.shred(itemKey);
		}
	}

	public byte[] getThumbnail(String itemId) throws Exception {
		requireUnlocked();

		String itemDir = ITEMS_DIR + "/" + itemId;
		String thumbPath = itemDir + "/thumb.bin";

		if (!fileIO.exists(thumbPath)) {
			return null;
		}

		byte[] metadataPlain = null;
		byte[] thumbnailKey = null;

		try {
			byte[] encryptedMetadata = fileIO.readSecure(itemDir + "/header.bin");
			VaultCrypto.EncryptedData metadataWrapper =
					VaultCrypto.EncryptedData.fromBytes(encryptedMetadata);
			metadataPlain = crypto.decrypt(
					metadataWrapper, vaultMasterKey, itemId.getBytes()
			);

			VaultItem item = VaultItem.deserializeMetadata(metadataPlain);

			if (item.thumbnailKey != null && item.thumbnailKey.length > 0) {
				VaultCrypto.EncryptedData encryptedThumbKey =
						VaultCrypto.EncryptedData.fromBytes(item.thumbnailKey);
				thumbnailKey = crypto.decrypt(encryptedThumbKey, vaultMasterKey, new byte[0]);
			} else {
				VaultCrypto.EncryptedData encryptedKey =
						VaultCrypto.EncryptedData.fromBytes(item.encryptedKey);
				thumbnailKey = crypto.decrypt(encryptedKey, vaultMasterKey, new byte[0]);
			}

			byte[] encryptedThumb = fileIO.readSecure(thumbPath);
			VaultCrypto.EncryptedData thumbWrapper =
					VaultCrypto.EncryptedData.fromBytes(encryptedThumb);
			byte[] thumbnail = crypto.decrypt(thumbWrapper, thumbnailKey,
					("thumb_" + item.name).getBytes(StandardCharsets.UTF_8));

			return thumbnail;

		} finally {
			if (metadataPlain != null) SecureMemory.shred(metadataPlain);
			if (thumbnailKey != null) SecureMemory.shred(thumbnailKey);
		}
	}

	public byte[] loadDocumentContentSecure(String itemId) throws Exception {
		return getItemContent(itemId);
	}

	public synchronized byte[] getItemContentWithPassword(String itemId, char[] extraPassword) throws Exception {
		requireUnlocked();

		if (extraPassword == null || extraPassword.length == 0) {
			throw new IllegalArgumentException("Password cannot be empty");
		}

		updateActivity();

		String itemDir = ITEMS_DIR + "/" + itemId;

		byte[] metadataPlain = null;
		byte[] itemKey = null;
		byte[] passwordKey = null;
		byte[] nestedContent = null;

		try {
			byte[] encryptedMetadata = fileIO.readSecure(itemDir + "/header.bin");
			VaultCrypto.EncryptedData metadataWrapper =
					VaultCrypto.EncryptedData.fromBytes(encryptedMetadata);
			metadataPlain = crypto.decrypt(
					metadataWrapper, vaultMasterKey, itemId.getBytes()
			);

			VaultItem item = VaultItem.deserializeMetadata(metadataPlain);

			if (!item.hasExtraPassword) {
				throw new SecurityException("Item does not have password protection");
			}

			VaultCrypto.EncryptedData encryptedKey =
					VaultCrypto.EncryptedData.fromBytes(item.encryptedKey);
			itemKey = crypto.decrypt(encryptedKey, vaultMasterKey, new byte[0]);

			byte[] rawContent = fileIO.readSecure(itemDir + "/content.bin");
			byte[] encryptedContent = stripPadding(rawContent);
			VaultCrypto.EncryptedData contentWrapper =
					VaultCrypto.EncryptedData.fromBytes(encryptedContent);
			nestedContent = crypto.decrypt(contentWrapper, itemKey, item.name.getBytes(StandardCharsets.UTF_8));

			passwordKey = argon2.deriveKey(extraPassword, item.extraPasswordSalt,
					new Argon2.Argon2Params(item.extraPasswordMemoryKb,
							item.extraPasswordIterations,
							item.extraPasswordParallelism, 32));

			VaultCrypto.EncryptedData passwordWrapper =
					VaultCrypto.EncryptedData.fromBytes(nestedContent);
			byte[] plainContent = crypto.decrypt(passwordWrapper, passwordKey,
					item.name.getBytes(StandardCharsets.UTF_8));

			return plainContent;

		} catch (RuntimeException e) {
			Throwable cause = e.getCause();
			if (cause instanceof javax.crypto.BadPaddingException) {
				throw new SecurityException("Incorrect password");
			}
			String msg = e.getMessage();
			if (msg != null && (msg.contains("Tag mismatch") ||
				msg.contains("mac check") || msg.contains("Decryption failed"))) {
				throw new SecurityException("Incorrect password");
			}
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to retrieve item content", e);
		} finally {
			if (metadataPlain != null) SecureMemory.shred(metadataPlain);
			if (itemKey != null) SecureMemory.shred(itemKey);
			if (passwordKey != null) SecureMemory.shred(passwordKey);
			if (nestedContent != null) SecureMemory.shred(nestedContent);
		}
	}

	public synchronized List<VaultItem> listItems() throws Exception {
		requireUnlocked();

		long now = System.currentTimeMillis();
		if (cachedItems != null && (now - cacheTimestamp) < CACHE_VALIDITY_MS) {
			return new ArrayList<>(cachedItems);
		}

		List<VaultItem> items = new ArrayList<>();

		if (!fileIO.exists(ITEMS_DIR)) {
			fileIO.createDirectory(ITEMS_DIR);
			cachedItems = items;
			cacheTimestamp = now;
			return items;
		}

		String[] itemDirs = fileIO.listFiles(ITEMS_DIR);

		for (String itemId : itemDirs) {
			try {
				String itemDir = ITEMS_DIR + "/" + itemId;
				byte[] encryptedMetadata = fileIO.readSecure(itemDir + "/header.bin");
				VaultCrypto.EncryptedData metadataWrapper =
						VaultCrypto.EncryptedData.fromBytes(encryptedMetadata);
				byte[] metadataPlain = crypto.decrypt(
						metadataWrapper, vaultMasterKey, itemId.getBytes()
				);
				VaultItem item = VaultItem.deserializeMetadata(metadataPlain);
				items.add(item);

				SecureMemory.shred(metadataPlain);
			} catch (Exception e) {
			}
		}

		cachedItems = new ArrayList<>(items);
		cacheTimestamp = now;

		return items;
	}

	public synchronized void invalidateCache() {
		cachedItems = null;
		cacheTimestamp = 0;
	}

	public void deleteItem(String itemId) throws Exception {
		requireUnlocked();

		String itemDir = ITEMS_DIR + "/" + itemId;

		fileIO.deleteDirectory(itemDir);

		invalidateCache();
	}

	public void wipeVault() throws Exception {
		lockVault();
		if (!location.isAvailable()) {
			for (java.io.File dir : location.allVaultDirectories()) {
				SecureFileIO.wipeVaultAt(context, dir);
			}
			VaultKeystore.deleteAllVaultKeys();
			currentHeader = null;
			return;
		}
		fileIO.wipeVault();
		keystore.deleteVaultKeys();
		currentHeader = null;
	}

	private synchronized boolean verifyPassword(char[] candidate)
			throws Exception {
		if (unlockThrottle.remainingLockoutMs() > 0) {
			throw new SecurityException(TOO_MANY_ATTEMPTS);
		}
		long start = android.os.SystemClock.elapsedRealtime();
		boolean ok = false;
		try {
			ok = verifyPasswordUnthrottled(candidate);
			return ok;
		} finally {
			if (ok) unlockThrottle.reset();
			else unlockThrottle.recordFailure();
			enforceUnlockTimeFloor(start);
		}
	}

	private boolean verifyPasswordUnthrottled(char[] candidate)
			throws Exception {
		if (currentHeader == null) loadVaultHeader();
		OpenedVault opened = openWithEitherForm(currentHeader, candidate);
		if (opened == null) return false;
		SecureMemory.shred(opened.masterKey);
		return true;
	}

	public synchronized boolean verifyMasterPassword(char[] candidate) {
		try {
			return verifyPassword(candidate);
		} catch (Exception e) {
			return false;
		}
	}

	public synchronized void changePassword(char[] oldPassword,
			char[] newPassword) throws Exception {
		if (!verifyPassword(oldPassword)) {
			throw new SecurityException("Invalid current password");
		}
		char[] password = PasswordNormalizer.normalize(newPassword);
		byte[] newMasterKey = crypto.generateKey();
		VaultHeader old = currentHeader;
		int slot = old.version < VaultHeader.VERSION_KEYSTORE_FACTOR
				? VaultKeystore.SLOT_A : VaultKeystore.otherSlot(old.keySlot);
		boolean committed = false;
		try {
			VaultHeader newHeader = sealMasterKey(newMasterKey, password, slot,
					old.biometricTokenSalt, old.createdTimestamp);
			commitRekey(vaultMasterKey, newMasterKey, newHeader);
			committed = true;
			this.currentHeader = newHeader;
			SecureMemory.shred(vaultMasterKey);
			this.vaultMasterKey = newMasterKey;
			deleteKeysNotNamedBy(newHeader);
		} catch (Exception e) {
			if (!committed) keystore.deleteSlot(slot);
			throw e;
		} finally {
			if (!committed) SecureMemory.shred(newMasterKey);
			java.util.Arrays.fill(password, '\0');
		}

		invalidateCache();
	}

	private void commitRekey(byte[] oldKey, byte[] newKey, VaultHeader newHeader)
			throws Exception {
		boolean staged = prepareRekeyTemp(oldKey, newKey);

		java.io.File vaultDir = fileIO.getVaultDir();
		java.io.File items = new java.io.File(vaultDir, ITEMS_DIR);
		java.io.File backupDir = new java.io.File(vaultDir, ITEMS_BACKUP_DIR);
		java.io.File tempDir = new java.io.File(vaultDir, ITEMS_TEMP_DIR);
		java.io.File headerNew = new java.io.File(vaultDir, HEADER_NEW_FILE);
		java.io.File header = new java.io.File(vaultDir, HEADER_FILE);

		if (fileIO.exists(ITEMS_BACKUP_DIR)) {
			fileIO.deleteDirectory(ITEMS_BACKUP_DIR);
		}
		fileIO.writeSecure(HEADER_NEW_FILE, newHeader.toBytes());

		try {
			if (staged) {
				if (items.exists() && !items.renameTo(backupDir)) {
					throw new IOException("rekey: could not stage current items");
				}
				if (!tempDir.renameTo(items)) {
					if (backupDir.exists()) {
						backupDir.renameTo(items);
					}
					throw new IOException(
							"rekey: could not activate re-encrypted items");
				}
				fileIO.fsyncVaultDir();
			}
			atomicReplace(headerNew, header);
			fileIO.fsyncVaultDir();
		} catch (Exception commitFailed) {
			if (staged && backupDir.exists()) {
				if (items.exists()) {
					fileIO.deleteDirectory(ITEMS_DIR);
				}
				backupDir.renameTo(items);
			}
			if (fileIO.exists(ITEMS_TEMP_DIR)) {
				fileIO.deleteDirectory(ITEMS_TEMP_DIR);
			}
			if (fileIO.exists(HEADER_NEW_FILE)) {
				fileIO.secureDelete(HEADER_NEW_FILE);
			}
			throw commitFailed;
		}

		if (fileIO.exists(ITEMS_BACKUP_DIR)) {
			fileIO.deleteDirectory(ITEMS_BACKUP_DIR);
		}
	}

	private void atomicReplace(java.io.File src, java.io.File dst)
			throws IOException {
		if (!src.renameTo(dst)) {
			java.nio.file.Files.move(src.toPath(), dst.toPath(),
					java.nio.file.StandardCopyOption.ATOMIC_MOVE,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private boolean prepareRekeyTemp(byte[] oldKey, byte[] newKey)
			throws Exception {
		if (!fileIO.exists(ITEMS_DIR)) {
			return false;
		}

		String[] itemDirs = fileIO.listFiles(ITEMS_DIR);

		try {
			if (fileIO.exists(ITEMS_TEMP_DIR)) {
				fileIO.deleteDirectory(ITEMS_TEMP_DIR);
			}
			fileIO.createDirectory(ITEMS_TEMP_DIR);

			int rekeyedCount = 0;
			List<String> failedItems = new ArrayList<>();

			for (String itemId : itemDirs) {
				if (itemId.startsWith(".")) {
					continue;
				}

				try {
					String itemDir = ITEMS_DIR + "/" + itemId;
					String tempItemDir = ITEMS_TEMP_DIR + "/" + itemId;

					byte[] encryptedMetadata = fileIO.readSecure(itemDir + "/header.bin");
					VaultCrypto.EncryptedData metadataWrapper =
							VaultCrypto.EncryptedData.fromBytes(encryptedMetadata);
					byte[] metadataPlain = crypto.decrypt(
							metadataWrapper, oldKey, itemId.getBytes()
					);

					VaultItem item = VaultItem.deserializeMetadata(metadataPlain);

					VaultCrypto.EncryptedData encryptedKey =
							VaultCrypto.EncryptedData.fromBytes(item.encryptedKey);
					byte[] itemKey = crypto.decrypt(encryptedKey, oldKey, new byte[0]);

					VaultCrypto.EncryptedData newEncryptedKey = crypto.encrypt(
							itemKey, newKey, new byte[0]
					);

					VaultItem updatedItem = new VaultItem(
							item.id,
							item.type,
							item.name,
							item.createdTimestamp,
							item.modifiedTimestamp,
							item.size,
							item.thumbnailKey,
							item.hasExtraPassword,
							item.extraPasswordSalt,
							item.extraPasswordMemoryKb,
							item.extraPasswordIterations,
							item.extraPasswordParallelism,
							newEncryptedKey.toBytes(),
							item.nonce,
							item.version
					);

					byte[] newMetadataPlain = updatedItem.serializeMetadata();
					VaultCrypto.EncryptedData newEncryptedMetadata = crypto.encrypt(
							newMetadataPlain, newKey, itemId.getBytes()
					);

					fileIO.createDirectory(tempItemDir);
					fileIO.writeSecure(tempItemDir + "/header.bin", newEncryptedMetadata.toBytes());

					byte[] content = null;
					if (fileIO.exists(itemDir + "/content.bin")) {
						content = fileIO.readSecure(itemDir + "/content.bin");
						fileIO.writeSecure(tempItemDir + "/content.bin", content);
					}

					if (content != null) {
						SecureMemory.shred(content);
					}
					SecureMemory.shredAll(itemKey, metadataPlain, newMetadataPlain);

					rekeyedCount++;

				} catch (Exception e) {
					failedItems.add(itemId);
				}
			}

			if (!failedItems.isEmpty()) {
				fileIO.deleteDirectory(ITEMS_TEMP_DIR);
				throw new Exception("Failed to rekey " + failedItems.size() + " items. Rekey aborted, vault unchanged.");
			}

			return true;

		} catch (Exception e) {
			if (fileIO.exists(ITEMS_TEMP_DIR)) {
				fileIO.deleteDirectory(ITEMS_TEMP_DIR);
			}
			throw e;
		}
	}

	private void reconcileRekeyIfNeeded() {
		try {
			boolean headerNew = fileIO.exists(HEADER_NEW_FILE);
			boolean backup = fileIO.exists(ITEMS_BACKUP_DIR);
			boolean temp = fileIO.exists(ITEMS_TEMP_DIR);
			if (!headerNew && !backup && !temp) {
				return;
			}
			java.io.File vaultDir = fileIO.getVaultDir();
			java.io.File items = new java.io.File(vaultDir, ITEMS_DIR);
			java.io.File backupDir = new java.io.File(vaultDir, ITEMS_BACKUP_DIR);
			if (headerNew) {
				if (backup) {
					if (items.exists()) {
						fileIO.deleteDirectory(ITEMS_DIR);
					}
					backupDir.renameTo(items);
				}
				if (fileIO.exists(ITEMS_TEMP_DIR)) {
					fileIO.deleteDirectory(ITEMS_TEMP_DIR);
				}
				fileIO.secureDelete(HEADER_NEW_FILE);
			} else if (backup) {
				fileIO.deleteDirectory(ITEMS_BACKUP_DIR);
				if (temp) {
					fileIO.deleteDirectory(ITEMS_TEMP_DIR);
				}
			} else {
				fileIO.deleteDirectory(ITEMS_TEMP_DIR);
			}
			fileIO.fsyncVaultDir();
		} catch (Exception ignored) {
		}
	}

	private byte[] stripPadding(byte[] raw) {
		if (raw.length < 4) return raw;
		int realLength = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
				| ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
		if (realLength <= 0 || realLength > raw.length - 4) return raw;
		byte[] result = new byte[realLength];
		System.arraycopy(raw, 4, result, 0, realLength);
		return result;
	}

	private void requireUnlocked() {
		if (!vaultExists()) {
			throw new SecurityException("Please create a vault first");
		}
		if (!isUnlocked()) {
			throw new SecurityException("Please unlock your vault first");
		}
		updateActivity();
	}

	private void loadVaultHeader() throws IOException {
		if (fileIO.exists(HEADER_FILE)) {
			byte[] headerData = fileIO.readSecure(HEADER_FILE);
			currentHeader = VaultHeader.fromBytes(headerData);
		}
	}

	private Argon2.Argon2Params extraPasswordParams() {
		return Argon2.Argon2Params.getWalletPassword();
	}

	public byte[] exportVault(char[] exportPassword) throws Exception {
		requireUnlocked();

		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

		dos.writeInt(3);

		dos.writeInt(currentHeader.version);

		byte[] exportSalt = new byte[32];
		new SecureRandom().nextBytes(exportSalt);
		dos.write(exportSalt);

		char[] normalExportPassword =
				PasswordNormalizer.normalize(exportPassword);
		DerivedKey derived;
		try {
			derived = deriveForNewHeader(normalExportPassword, exportSalt);
		} finally {
			java.util.Arrays.fill(normalExportPassword, '\0');
		}
		Argon2.Argon2Params params = derived.params;
		dos.writeInt(params.memoryKb);
		dos.writeInt(params.iterations);
		dos.writeInt(params.parallelism);
		byte[] exportKey = derived.key;

		List<VaultItem> items = listItems();
		dos.writeInt(items.size());

		for (VaultItem item : items) {
			byte[] content = getItemContent(item.id);

			byte[] metadata = item.serializeMetadata();
			byte[] encryptedMetadata = crypto.encrypt(
					metadata, exportKey, EXPORT_META_AAD).toBytes();
			SecureMemory.shred(metadata);
			dos.writeInt(encryptedMetadata.length);
			dos.write(encryptedMetadata);

			VaultCrypto.EncryptedData encryptedContent = crypto.encrypt(
					content, exportKey, item.id.getBytes()
			);
			byte[] encryptedBytes = encryptedContent.toBytes();
			dos.writeInt(encryptedBytes.length);
			dos.write(encryptedBytes);

			SecureMemory.shred(content);
		}

		dos.close();
		byte[] exportData = baos.toByteArray();

		SecureMemory.shred(exportKey);

		return exportData;
	}

	public void importVault(byte[] exportData, char[] exportPassword,
			boolean replaceExisting) throws Exception {
		requireUnlocked();
		char[] normal = PasswordNormalizer.normalize(exportPassword);
		char[] legacy = PasswordNormalizer.legacyForm(exportPassword, normal);
		try {
			try {
				importVaultWith(exportData, normal, replaceExisting);
			} catch (FirstItemNotOpened e) {
				if (legacy == null) throw e.failure;
				try {
					importVaultWith(exportData, legacy, replaceExisting);
				} catch (FirstItemNotOpened again) {
					throw again.failure;
				}
			}
		} finally {
			java.util.Arrays.fill(normal, '\0');
			if (legacy != null) java.util.Arrays.fill(legacy, '\0');
		}
	}

	private static final class FirstItemNotOpened extends Exception {

		final RuntimeException failure;

		FirstItemNotOpened(RuntimeException failure) {
			this.failure = failure;
		}
	}

	private void importVaultWith(byte[] exportData, char[] exportPassword,
			boolean replaceExisting) throws Exception {

		java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(exportData);
		java.io.DataInputStream dis = new java.io.DataInputStream(bais);

		int exportFormatVersion = dis.readInt();
		if (exportFormatVersion != 2 && exportFormatVersion != 3) {
			throw new IOException(
					"Unsupported export version: " + exportFormatVersion +
							" (re-export with current Zerion version)");
		}
		boolean encryptedMetadata = exportFormatVersion >= 3;

		int vaultCryptoVersion = dis.readInt();

		byte[] exportSalt = new byte[32];
		dis.readFully(exportSalt);

		int memoryKb = dis.readInt();
		int iterations = dis.readInt();
		int parallelism = dis.readInt();
		try {
			Argon2.requireSaneParams(memoryKb, iterations, parallelism);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid Argon2 params in export header");
		}
		Argon2.Argon2Params params = new Argon2.Argon2Params(memoryKb,
				iterations, parallelism, 32);
		byte[] exportKey = kdf.derive(exportPassword, exportSalt, params);

		int itemCount = dis.readInt();
		if (itemCount < 0 || itemCount > 10000) {
			throw new IOException("Invalid item count: " + itemCount);
		}
		int imported = 0;
		boolean opened = false;

		final int MAX_METADATA_SIZE = 64 * 1024;
		final int MAX_CONTENT_SIZE = 100 * 1024 * 1024;

		for (int i = 0; i < itemCount; i++) {
			int metadataLen = dis.readInt();
			if (metadataLen < 0 || metadataLen > MAX_METADATA_SIZE) {
				throw new IOException("Invalid metadata size: " + metadataLen);
			}
			byte[] metadataBytes = new byte[metadataLen];
			dis.readFully(metadataBytes);
			byte[] metadata;
			if (encryptedMetadata) {
				try {
					metadata = crypto.decrypt(
							VaultCrypto.EncryptedData.fromBytes(metadataBytes),
							exportKey, EXPORT_META_AAD);
				} catch (RuntimeException e) {
					if (!opened) {
						SecureMemory.shred(exportKey);
						throw new FirstItemNotOpened(e);
					}
					throw e;
				}
				opened = true;
			} else {
				metadata = metadataBytes;
			}
			VaultItem item = VaultItem.deserializeMetadata(metadata);

			if (!replaceExisting && fileIO.exists(ITEMS_DIR + "/" + item.id)) {
				int contentLen = dis.readInt();
				if (contentLen < 0 || contentLen > MAX_CONTENT_SIZE) {
					throw new IOException("Invalid content size: " + contentLen);
				}
				long remaining = contentLen;
				while (remaining > 0) {
					long skipped = dis.skip(remaining);
					if (skipped <= 0) break;
					remaining -= skipped;
				}
				continue;
			}

			int encryptedLen = dis.readInt();
			if (encryptedLen < 0 || encryptedLen > MAX_CONTENT_SIZE) {
				throw new IOException("Invalid encrypted content size: " + encryptedLen);
			}
			byte[] encryptedBytes = new byte[encryptedLen];
			dis.readFully(encryptedBytes);

			VaultCrypto.EncryptedData encryptedContent =
					VaultCrypto.EncryptedData.fromBytes(encryptedBytes);
			byte[] content;
			try {
				content = crypto.decrypt(encryptedContent, exportKey,
						item.id.getBytes());
			} catch (RuntimeException e) {
				if (!opened) {
					SecureMemory.shred(exportKey);
					throw new FirstItemNotOpened(e);
				}
				throw e;
			}
			opened = true;

			if (item.hasExtraPassword) {
				importProtectedItem(item, content);
			} else {
				addItem(item.type, item.name, content);
			}
			imported++;

			SecureMemory.shred(content);
		}

		SecureMemory.shred(exportKey);

		invalidateCache();

	}
}