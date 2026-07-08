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

import javax.crypto.SecretKey;
import javax.inject.Inject;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;
import com.professor.zerion.android.vault.crypto.VaultKeystore;
import com.professor.zerion.android.vault.model.VaultHeader;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.storage.SecureFileIO;
import com.professor.zerion.android.vault.utils.MetadataStripper;
import com.professor.zerion.android.vault.utils.SecureMemory;

@NotNullByDefault
public class VaultManager {
	private static final String HEADER_FILE = "vault.header";
	private static final String ITEMS_DIR = "items";
	private static final byte[] EXPORT_META_AAD =
			"zerion-vault-export-meta".getBytes(
					java.nio.charset.StandardCharsets.UTF_8);
	private static final long AUTO_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

	private final Context context;
	private final VaultKeystore keystore;
	private final VaultCrypto crypto;
	private final Argon2 argon2;
	private final SecureFileIO fileIO;
	private final MetadataStripper metadataStripper;

	private VaultHeader currentHeader;
	private byte[] vaultMasterKey;
	private volatile long lastActivityTime;
	private volatile boolean isUnlocked = false;
	private volatile int failedAttempts = 0;
	private volatile long backoffUntil = 0;

	private volatile List<VaultItem> cachedItems = null;
	private volatile long cacheTimestamp = 0;
	private static final long CACHE_VALIDITY_MS = 10000;

	private static final int MAX_FAILED_ATTEMPTS = 10;
	private static final long INITIAL_BACKOFF_MS = 1000;
	private static final long BACKOFF_RESET_MS = 60000;

	@Inject
	public VaultManager(Context context) {
		this.context = context.getApplicationContext();
		try {
			this.keystore = new VaultKeystore(context);
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize vault keystore", e);
		}
		this.crypto = new VaultCrypto();
		this.argon2 = new Argon2();
		this.fileIO = new SecureFileIO(context);
		this.metadataStripper = new MetadataStripper(context);

		this.lastActivityTime = System.currentTimeMillis();
	}

	private void ensureHeaderLoaded() {
		if (currentHeader == null && fileIO.exists(HEADER_FILE)) {
			try {
				loadVaultHeader();
			} catch (Exception e) {
			}
		}
	}

	public boolean vaultExists() {
		return fileIO.exists(HEADER_FILE);
	}

	public synchronized void createVault(char[] password) throws Exception {
		if (vaultExists()) {
			throw new IllegalStateException("Vault already exists");
		}

		byte[] salt = argon2.generateSalt();
		byte[] bioSalt = argon2.generateSalt(16);

		SecretKey keystoreKey = keystore.getOrCreateVaultKey();

		byte[] randomSecret = crypto.generateKey();
		byte[] wrappedSecret = keystore.wrapSecret(randomSecret, keystoreKey);
		Argon2.Argon2Params params = getArgon2Params();
		byte[] passwordKey = argon2.deriveKey(password, salt, params);

		byte[] combined = crypto.xor(passwordKey, randomSecret);
		this.vaultMasterKey = crypto.hkdfSha256(combined, salt, "vault master",
				32);

		byte[] passwordVerificationMac =
				crypto.computePasswordVerificationMac(this.vaultMasterKey);

		SecureMemory.shredAll(passwordKey, randomSecret, combined);

		VaultHeader header = VaultHeader.createNew(
				salt,
				params.memoryKb,
				params.iterations,
				wrappedSecret,
				bioSalt,
				passwordVerificationMac
		);

		fileIO.writeSecure(HEADER_FILE, header.toBytes());
		fileIO.createDirectory(ITEMS_DIR);

		this.currentHeader = header;
		this.isUnlocked = true;
		updateActivity();
	}

	public synchronized boolean unlockVault(char[] password) throws Exception {
		if (!vaultExists()) {
			throw new IllegalStateException("No vault exists");
		}

		long unlockStartRealtime = android.os.SystemClock.elapsedRealtime();
		try {
			long now = System.currentTimeMillis();
			if (backoffUntil > 0 && now - backoffUntil > BACKOFF_RESET_MS) {
				failedAttempts = 0;
				backoffUntil = 0;
			}

			if (now < backoffUntil) {
				long waitSeconds = (backoffUntil - now) / 1000;
				throw new SecurityException(
						"Too many failed attempts. Wait " + waitSeconds
								+ " seconds");
			}
			if (currentHeader == null) {
				loadVaultHeader();
			}

			SecretKey keystoreKey = keystore.getOrCreateVaultKey();

			byte[] randomSecret = keystore.unwrapSecret(
					currentHeader.wrappedKeystoreBlob, keystoreKey);

			Argon2.Argon2Params params = new Argon2.Argon2Params(
					currentHeader.kdfMemoryKb,
					currentHeader.kdfIterations,
					currentHeader.kdfParallelism,
					32
			);
			byte[] passwordKey = argon2.deriveKey(password, currentHeader.salt, params);

			byte[] combined = crypto.xor(passwordKey, randomSecret);
			byte[] derivedKey = crypto.hkdfSha256(combined,
					currentHeader.salt, "vault master", 32);

			if (currentHeader.passwordVerificationMac != null &&
					currentHeader.passwordVerificationMac.length > 0) {
				boolean macValid = crypto.verifyPasswordMac(derivedKey, currentHeader.passwordVerificationMac);
				if (!macValid) {
					Arrays.fill(derivedKey, (byte) 0);
					Arrays.fill(passwordKey, (byte) 0);
					Arrays.fill(randomSecret, (byte) 0);
					Arrays.fill(combined, (byte) 0);
					return false;
				}
			} else {
				if (!fileIO.exists(ITEMS_DIR)) {
					Arrays.fill(derivedKey, (byte) 0);
					Arrays.fill(passwordKey, (byte) 0);
					Arrays.fill(randomSecret, (byte) 0);
					Arrays.fill(combined, (byte) 0);
					return false;
				} else {
					String[] itemDirs = fileIO.listFiles(ITEMS_DIR);
					if (itemDirs.length > 0) {
						Arrays.sort(itemDirs);

						boolean decryptSuccess = false;
						int attempts = 0;
						final int MAX_VERIFY_ATTEMPTS = 3;

						for (String itemId : itemDirs) {
							if (itemId.startsWith(".")) {
								continue;
							}
							if (attempts >= MAX_VERIFY_ATTEMPTS) {
								break;
							}
							attempts++;

							try {
								String itemDir = ITEMS_DIR + "/" + itemId;

								if (!fileIO.exists(itemDir + "/header.bin")) {
									continue;
								}

								byte[] encryptedMetadata = fileIO.readSecure(itemDir + "/header.bin");
								VaultCrypto.EncryptedData metadataWrapper =
										VaultCrypto.EncryptedData.fromBytes(encryptedMetadata);
								byte[] metadataPlain = crypto.decrypt(
										metadataWrapper, derivedKey, itemId.getBytes()
								);

								try {
									VaultItem.deserializeMetadata(metadataPlain);
									decryptSuccess = true;
								} catch (Exception deserializeError) {
									decryptSuccess = false;
								}

								SecureMemory.shred(metadataPlain);

								if (decryptSuccess) {
									break;
								}
							} catch (Exception decryptError) {
								continue;
							}
						}

						if (!decryptSuccess) {
							Arrays.fill(derivedKey, (byte) 0);
							Arrays.fill(passwordKey, (byte) 0);
							Arrays.fill(randomSecret, (byte) 0);
							Arrays.fill(combined, (byte) 0);
							return false;
						}
					} else {
						Arrays.fill(derivedKey, (byte) 0);
						Arrays.fill(passwordKey, (byte) 0);
						Arrays.fill(randomSecret, (byte) 0);
						Arrays.fill(combined, (byte) 0);
						return false;
					}
				}
			}

			this.vaultMasterKey = derivedKey;

			if (currentHeader.passwordVerificationMac == null ||
					currentHeader.passwordVerificationMac.length == 0) {
				try {
					byte[] newPasswordVerificationMac = crypto.computePasswordVerificationMac(derivedKey);

					VaultHeader newHeader = VaultHeader.createNew(
							currentHeader.salt,
							currentHeader.kdfMemoryKb,
							currentHeader.kdfIterations,
							currentHeader.wrappedKeystoreBlob,
							currentHeader.biometricTokenSalt,
							newPasswordVerificationMac
					);

					fileIO.writeSecure(HEADER_FILE, newHeader.toBytes());
					this.currentHeader = newHeader;
				} catch (Exception e) {
				}
			}

			SecureMemory.shredAll(passwordKey, randomSecret, combined);

			failedAttempts = 0;
			backoffUntil = 0;
			isUnlocked = true;
			updateActivity();

			invalidateCache();

			return true;

		} catch (SecurityException e) {
			throw e;
		} catch (Exception e) {
			failedAttempts++;
			long backoffMs = INITIAL_BACKOFF_MS * (2L * failedAttempts - 1L);
			backoffUntil = System.currentTimeMillis() + backoffMs;
			return false;
		} finally {
			enforceUnlockTimeFloor(unlockStartRealtime);
		}
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
		isUnlocked = false;

		SecureMemory.forceGarbageCollection();
	}

	public synchronized void checkAutoLock() {
		if (isUnlocked && System.currentTimeMillis() - lastActivityTime > AUTO_LOCK_TIMEOUT_MS) {
			lockVault();
		}
	}

	public synchronized void updateActivity() {
		lastActivityTime = System.currentTimeMillis();
	}

	public synchronized boolean isUnlocked() {
		if (this.isUnlocked && System.currentTimeMillis() - lastActivityTime > AUTO_LOCK_TIMEOUT_MS) {
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

		try {
			itemKey = crypto.generateKey();
			byte[] nonce = crypto.generateNonce();

			if (extraPassword != null && extraPassword.length > 0) {
				passwordSalt = new byte[32];
				new SecureRandom().nextBytes(passwordSalt);

				passwordKey = argon2.deriveKey(extraPassword, passwordSalt,
						extraPasswordParams());

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
						encryptedKey.toBytes(), nonce, passwordSalt
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

			byte[] metadataPlain = item.serializeMetadata();
			VaultCrypto.EncryptedData encryptedMetadata = crypto.encrypt(
					metadataPlain, vaultMasterKey, item.id.getBytes()
			);

			fileIO.writeSecure(itemDir + "/header.bin", encryptedMetadata.toBytes());

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
					extraPasswordParams());

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
			if (extraPassword != null) java.util.Arrays.fill(extraPassword, '\0');
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
		fileIO.wipeVault();
		keystore.deleteVaultKeys();
		currentHeader = null;
	}

	private synchronized boolean verifyPassword(char[] candidate)
			throws Exception {
		if (currentHeader == null) loadVaultHeader();
		if (currentHeader.passwordVerificationMac == null
				|| currentHeader.passwordVerificationMac.length == 0) {
			return false;
		}
		SecretKey keystoreKey = keystore.getOrCreateVaultKey();
		byte[] randomSecret = keystore.unwrapSecret(
				currentHeader.wrappedKeystoreBlob, keystoreKey);
		Argon2.Argon2Params params = new Argon2.Argon2Params(
				currentHeader.kdfMemoryKb,
				currentHeader.kdfIterations,
				currentHeader.kdfParallelism,
				32);
		byte[] passwordKey = argon2.deriveKey(candidate, currentHeader.salt,
				params);
		byte[] combined = crypto.xor(passwordKey, randomSecret);
		byte[] derivedKey = crypto.hkdfSha256(combined, currentHeader.salt,
				"vault master", 32);
		boolean ok = crypto.verifyPasswordMac(derivedKey,
				currentHeader.passwordVerificationMac);
		java.util.Arrays.fill(derivedKey, (byte) 0);
		java.util.Arrays.fill(passwordKey, (byte) 0);
		java.util.Arrays.fill(randomSecret, (byte) 0);
		java.util.Arrays.fill(combined, (byte) 0);
		return ok;
	}

	public synchronized void changePassword(char[] oldPassword,
			char[] newPassword) throws Exception {
		if (!verifyPassword(oldPassword)) {
			throw new SecurityException("Invalid current password");
		}

		byte[] newSalt = argon2.generateSalt();
		Argon2.Argon2Params params = getArgon2Params();
		byte[] newPasswordKey = argon2.deriveKey(newPassword, newSalt, params);

		SecretKey keystoreKey = keystore.getOrCreateVaultKey();
		byte[] randomSecret = keystore.unwrapSecret(
				currentHeader.wrappedKeystoreBlob, keystoreKey);

		byte[] combined = crypto.xor(newPasswordKey, randomSecret);
		byte[] newMasterKey = crypto.hkdfSha256(combined, newSalt,
				"vault master", 32);

		byte[] newPasswordVerificationMac = crypto.computePasswordVerificationMac(newMasterKey);

		rekeyVault(vaultMasterKey, newMasterKey);

		VaultHeader newHeader = VaultHeader.createNew(
				newSalt,
				params.memoryKb,
				params.iterations,
				currentHeader.wrappedKeystoreBlob,
				currentHeader.biometricTokenSalt,
				newPasswordVerificationMac
		);

		fileIO.writeSecure(HEADER_FILE, newHeader.toBytes());

		this.currentHeader = newHeader;
		this.vaultMasterKey = newMasterKey;

		SecureMemory.shredAll(newPasswordKey, randomSecret, combined);

		invalidateCache();

	}

	private void rekeyVault(byte[] oldKey, byte[] newKey) throws Exception {
		if (!fileIO.exists(ITEMS_DIR)) {
			return;
		}

		String[] itemDirs = fileIO.listFiles(ITEMS_DIR);
		final String ITEMS_TEMP_DIR = "items_rekey_temp";
		final String ITEMS_BACKUP_DIR = "items_rekey_backup";

		try {
			if (fileIO.exists(ITEMS_TEMP_DIR)) {
				fileIO.secureDelete(ITEMS_TEMP_DIR);
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
				fileIO.secureDelete(ITEMS_TEMP_DIR);
				throw new Exception("Failed to rekey " + failedItems.size() + " items. Rekey aborted, vault unchanged.");
			}

			if (fileIO.exists(ITEMS_BACKUP_DIR)) {
				fileIO.secureDelete(ITEMS_BACKUP_DIR);
			}

			java.io.File itemsDir = new java.io.File(fileIO.getVaultDir(), ITEMS_DIR);
			java.io.File backupDir = new java.io.File(fileIO.getVaultDir(), ITEMS_BACKUP_DIR);
			java.io.File tempDir = new java.io.File(fileIO.getVaultDir(), ITEMS_TEMP_DIR);

			if (!itemsDir.renameTo(backupDir)) {
				throw new IOException("Failed to backup current items directory");
			}

			if (!tempDir.renameTo(itemsDir)) {
				backupDir.renameTo(itemsDir);
				throw new IOException("Failed to activate re-encrypted items directory");
			}

			fileIO.secureDelete(ITEMS_BACKUP_DIR);

		} catch (Exception e) {
			if (fileIO.exists(ITEMS_TEMP_DIR)) {
				fileIO.secureDelete(ITEMS_TEMP_DIR);
			}
			throw e;
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

	private Argon2.Argon2Params getArgon2Params() {
		Runtime runtime = Runtime.getRuntime();
		long maxMemory = runtime.maxMemory();
		long availableMemory = maxMemory - (runtime.totalMemory() - runtime.freeMemory());

		if (maxMemory > 4L * 1024 * 1024 * 1024) {
			return new Argon2.Argon2Params(
					256 * 1024,
					3,
					1,
					32
			);
		}
		else if (availableMemory > 512 * 1024 * 1024) {
			return Argon2.Argon2Params.getDefault();
		}
		else {
			return Argon2.Argon2Params.getLowMemory();
		}
	}

	private Argon2.Argon2Params extraPasswordParams() {
		return Argon2.Argon2Params.getDefault();
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

		Argon2.Argon2Params params = getArgon2Params();
		dos.writeInt(params.memoryKb);
		dos.writeInt(params.iterations);
		dos.writeInt(params.parallelism);
		byte[] exportKey = argon2.deriveKey(exportPassword, exportSalt, params);

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
		if (memoryKb < 1024 || iterations < 1 || parallelism < 1) {
			throw new IOException("Invalid Argon2 params in export header");
		}
		Argon2.Argon2Params params = new Argon2.Argon2Params(memoryKb,
				iterations, parallelism, 32);
		byte[] exportKey = argon2.deriveKey(exportPassword, exportSalt, params);

		int itemCount = dis.readInt();
		if (itemCount < 0 || itemCount > 10000) {
			throw new IOException("Invalid item count: " + itemCount);
		}
		int imported = 0;

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
				metadata = crypto.decrypt(
						VaultCrypto.EncryptedData.fromBytes(metadataBytes),
						exportKey, EXPORT_META_AAD);
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
			byte[] content = crypto.decrypt(encryptedContent, exportKey, item.id.getBytes());

			addItem(item.type, item.name, content);
			imported++;

			SecureMemory.shred(content);
		}

		SecureMemory.shred(exportKey);

		invalidateCache();

	}
}