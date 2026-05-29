package com.professor.zerion.android.vault.ui;

import android.app.Application;

import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.model.PasswordEntry;
import com.professor.zerion.android.vault.model.VaultItem;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.briarproject.bramble.api.db.DatabaseExecutor;

@NotNullByDefault
public class VaultViewModel extends AndroidViewModel {

	public enum VaultState {
		NOT_CREATED,
		LOCKED,
		UNLOCKED
	}

	private final VaultManager vaultManager;
	private final Executor dbExecutor;

	private final MutableLiveData<VaultState> vaultState = new MutableLiveData<>();
	private final MutableLiveData<List<VaultItem>> vaultItems = new MutableLiveData<>();
	private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
	private final MutableLiveData<String> successMessage = new MutableLiveData<>();
	private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
	private final MutableLiveData<Integer> progressPercent = new MutableLiveData<>(0);
	private final MutableLiveData<String> progressMessage = new MutableLiveData<>();

	@Inject
	public VaultViewModel(Application application, VaultManager vaultManager,
			@DatabaseExecutor Executor dbExecutor) {
		super(application);
		this.vaultManager = vaultManager;
		this.dbExecutor = dbExecutor;
		refreshVaultState();
	}

	public LiveData<VaultState> getVaultState() {
		return vaultState;
	}

	public LiveData<List<VaultItem>> getVaultItems() {
		return vaultItems;
	}

	public LiveData<String> getErrorMessage() {
		return errorMessage;
	}

	public LiveData<String> getSuccessMessage() {
		return successMessage;
	}

	public LiveData<Boolean> getIsLoading() {
		return isLoading;
	}

	public LiveData<Integer> getProgressPercent() {
		return progressPercent;
	}

	public LiveData<String> getProgressMessage() {
		return progressMessage;
	}

	public void refreshVaultState() {
		VaultState newState;
		if (!vaultManager.vaultExists()) {
			newState = VaultState.NOT_CREATED;
		} else if (!vaultManager.isUnlocked()) {
			newState = VaultState.LOCKED;
		} else {
			newState = VaultState.UNLOCKED;
		}

		try {
			vaultState.setValue(newState);
		} catch (Exception e) {
			vaultState.postValue(newState);
		}
	}

	public void clearMessages() {
		successMessage.postValue(null);
		errorMessage.postValue(null);
	}

	public void clearSensitiveMemory() {
		vaultItems.postValue(new ArrayList<>());
		errorMessage.postValue(null);
		successMessage.postValue(null);
		progressMessage.postValue(null);
		progressPercent.postValue(0);
	}

	public void createVault(char[] password, char[] confirmPassword) {
		if (!Arrays.equals(password, confirmPassword)) {
			Arrays.fill(password, '\0');
			Arrays.fill(confirmPassword, '\0');
			errorMessage.postValue("Passwords do not match");
			return;
		}

		if (password.length < 8) {
			Arrays.fill(password, '\0');
			Arrays.fill(confirmPassword, '\0');
			errorMessage.postValue("Password must be at least 8 characters");
			return;
		}

		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.createVault(password);
				vaultState.postValue(VaultState.UNLOCKED);
				successMessage.postValue("Vault created successfully");
				loadVaultItems();
			} catch (IllegalStateException e) {
				errorMessage.postValue("Vault already exists");
			} catch (SecurityException e) {
				errorMessage.postValue("Security error");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Failed to create vault");
			} finally {
				Arrays.fill(password, '\0');
				Arrays.fill(confirmPassword, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public void unlockVault(char[] password) {
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				boolean success = vaultManager.unlockVault(password);
				if (success) {
					vaultState.postValue(VaultState.UNLOCKED);
					loadVaultItems();
				} else {
					errorMessage.postValue("Invalid password");
				}
			} catch (SecurityException e) {
				errorMessage.postValue("Invalid password");
			} catch (Exception e) {
				errorMessage.postValue("Failed to unlock vault");
			} finally{
				Arrays.fill(password, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public void lockVault() {
		vaultManager.lockVault();
		vaultState.postValue(VaultState.LOCKED);
		clearSensitiveMemory();
	}

	public void loadVaultItems() {
		if (!vaultManager.vaultExists()) {
			vaultItems.postValue(new ArrayList<>());
			return;
		}
		if (!vaultManager.isUnlocked()) {
			vaultItems.postValue(new ArrayList<>());
			return;
		}

		dbExecutor.execute(() -> {
			try {
				List<VaultItem> items = vaultManager.listItems();
				vaultItems.postValue(items);
			} catch (SecurityException e) {
				vaultItems.postValue(new ArrayList<>());
			} catch (Exception e) {
				errorMessage.postValue("Failed to load vault items");
			}
		});
	}

	public void addDocument(String fileName, byte[] content) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue("File name cannot be empty");
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue("File content is empty");
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				VaultItem item = vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				loadVaultItems();
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue("Vault is locked");
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to add document");
				isLoading.postValue(false);
			}
		});
	}

	public void addDocumentWithPassword(String fileName, byte[] content, @Nullable char[] extraPassword) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue("File name cannot be empty");
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue("File content is empty");
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				VaultItem item;
				if (extraPassword != null && extraPassword.length > 0) {
					item = vaultManager.addItemWithPassword(
							VaultItem.ItemType.DOCUMENT,
							fileName,
							content,
							extraPassword
					);
				} else {
					item = vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				}
				loadVaultItems();
				successMessage.postValue("Document saved securely");
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue("Vault is locked");
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to add document");
				isLoading.postValue(false);
			} finally {
				if (extraPassword != null) {
					Arrays.fill(extraPassword, '\0');
				}
			}
		});
	}

	public void updateDocument(String itemId, String fileName, byte[] content) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (itemId == null || itemId.trim().isEmpty()) {
			errorMessage.postValue("Invalid document ID");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue("File name cannot be empty");
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue("File content is empty");
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				vaultManager.deleteItem(itemId);
				vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				loadVaultItems();
				successMessage.postValue("Document updated");
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue("Vault is locked");
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to update document");
				isLoading.postValue(false);
			}
		});
	}

	public void savePassword(String title, String username, String password,
			String url, String notes) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (title.trim().isEmpty()) {
			errorMessage.postValue("Title cannot be empty");
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				PasswordEntry entry = new PasswordEntry(title, username, password, url, notes);

				StringBuilder json = new StringBuilder();
				json.append("{");
				json.append("\"title\":\"").append(escapeJson(title)).append("\",");
				json.append("\"username\":\"").append(escapeJson(username)).append("\",");
				json.append("\"password\":\"").append(escapeJson(password)).append("\",");
				json.append("\"url\":\"").append(escapeJson(url)).append("\",");
				json.append("\"notes\":\"").append(escapeJson(notes)).append("\"");
				json.append("}");
				byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);

				json.setLength(0);

				VaultItem item = vaultManager.addItem(VaultItem.ItemType.PASSWORD, title, content);

				loadVaultItems();
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to save password");
				isLoading.postValue(false);
			}
		});
	}

	public void getPassword(String itemId, PasswordCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContent(itemId);
				String json = new String(content, StandardCharsets.UTF_8);

				PasswordEntry entry = parsePasswordEntry(json);

				Arrays.fill(content, (byte) 0);

				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onPasswordRetrieved(entry);
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to retrieve password");
				});
			}
		});
	}

	private String escapeJson(String value) {
		if (value == null) return "";
		return value.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private PasswordEntry parsePasswordEntry(String json) {
		String title = extractJsonValue(json, "title");
		String username = extractJsonValue(json, "username");
		String password = extractJsonValue(json, "password");
		String url = extractJsonValue(json, "url");
		String notes = extractJsonValue(json, "notes");
		return new PasswordEntry(title, username, password, url, notes);
	}

	private String extractJsonValue(String json, String key) {
		String searchKey = "\"" + key + "\":\"";
		int startIdx = json.indexOf(searchKey);
		if (startIdx == -1) return "";
		startIdx += searchKey.length();
		int endIdx = json.indexOf("\"", startIdx);
		if (endIdx == -1) return "";
		String value = json.substring(startIdx, endIdx);
		return value.replace("\\\"", "\"")
				.replace("\\\\", "\\")
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t");
	}

	public interface PasswordCallback {
		void onPasswordRetrieved(PasswordEntry entry);
		void onError(String error);
	}

	public void saveNote(String title, String content, @Nullable String existingId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (title.trim().isEmpty()) {
			errorMessage.postValue("Note title cannot be empty");
			return;
		}

		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

				if (existingId != null) {
					vaultManager.deleteItem(existingId);
				}

				VaultItem item = vaultManager.addItem(
						VaultItem.ItemType.NOTE,
						title,
						contentBytes
				);

				successMessage.postValue("Saved");
				loadVaultItems();

				Arrays.fill(contentBytes, (byte) 0);

			} catch (SecurityException e) {
				errorMessage.postValue("Security error");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Failed to save note");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void saveNoteWithPassword(String title, String content, char[] password,
			@Nullable String existingNoteId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			java.util.Arrays.fill(password, '\0');
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			char[] passwordChars = password;
			try {
				byte[] salt = new byte[32];
				new java.security.SecureRandom().nextBytes(salt);

				com.professor.zerion.android.vault.crypto.Argon2 argon2 =
						new com.professor.zerion.android.vault.crypto.Argon2();
				com.professor.zerion.android.vault.crypto.Argon2.Argon2Params params =
						com.professor.zerion.android.vault.crypto.Argon2
								.Argon2Params.getDefault();
				byte[] passwordKey = argon2.deriveKey(passwordChars, salt, params);

				javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
				javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(passwordKey, "AES");
				cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec);

				byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				byte[] iv = cipher.getIV();
				byte[] encryptedContent = cipher.doFinal(contentBytes);

				byte[] combined = new byte[1 + salt.length + iv.length + encryptedContent.length];
				combined[0] = 0x02;
				System.arraycopy(salt, 0, combined, 1, salt.length);
				System.arraycopy(iv, 0, combined, 1 + salt.length, iv.length);
				System.arraycopy(encryptedContent, 0, combined, 1 + salt.length + iv.length, encryptedContent.length);

				if (existingNoteId != null) {
					vaultManager.deleteItem(existingNoteId);
				}

				String protectedTitle = "🔒 " + title;
				VaultItem item = vaultManager.addItem(VaultItem.ItemType.NOTE, protectedTitle, combined);

				successMessage.postValue("Saved");
				loadVaultItems();

				Arrays.fill(contentBytes, (byte) 0);
				Arrays.fill(passwordKey, (byte) 0);
				Arrays.fill(encryptedContent, (byte) 0);
				Arrays.fill(salt, (byte) 0);

			} catch (Exception e) {
				errorMessage.postValue("Failed to save note");
			} finally {
				Arrays.fill(passwordChars, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public LiveData<String> loadNoteContent(String noteId) {
		MutableLiveData<String> content = new MutableLiveData<>();

		if (!vaultManager.isUnlocked()) {
			content.postValue("__RETRY__");
			return content;
		}

		dbExecutor.execute(() -> {
			try {
				if (!vaultManager.isUnlocked()) {
					content.postValue("__RETRY__");
					return;
				}

				List<VaultItem> items = vaultManager.listItems();

				VaultItem targetItem = null;
				for (VaultItem item : items) {
					if (item.id.equals(noteId)) {
						targetItem = item;
						break;
					}
				}

				if (targetItem == null) {
					content.postValue(null);
					errorMessage.postValue("Note not found");
					return;
				}

				if (targetItem.name.startsWith("🔒 ")) {
					content.postValue("__PASSWORD_REQUIRED__");
				} else {
					byte[] contentBytes = vaultManager.getItemContent(noteId);
					String noteContent = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
					content.postValue(noteContent);

					Arrays.fill(contentBytes, (byte) 0);
				}

			} catch (SecurityException e) {
				content.postValue("__RETRY__");
			} catch (IOException e) {
				content.postValue(null);
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				content.postValue(null);
				errorMessage.postValue("Failed to load note");
			}
		});

		return content;
	}

	public LiveData<String> loadPasswordProtectedNote(String noteId, char[] password) {
		MutableLiveData<String> content = new MutableLiveData<>();

		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			java.util.Arrays.fill(password, '\0');
			return content;
		}

		dbExecutor.execute(() -> {
			char[] passwordChars = password;
			try {
				byte[] encryptedData = vaultManager.getItemContent(noteId);

				byte[] salt;
				byte[] iv = new byte[12];
				byte[] encryptedContent;
				byte[] passwordKey;
				boolean isV2 = encryptedData.length > 0
						&& encryptedData[0] == 0x02;
				if (isV2) {
					salt = new byte[32];
					encryptedContent = new byte[encryptedData.length - 1 - 32 - 12];
					System.arraycopy(encryptedData, 1, salt, 0, 32);
					System.arraycopy(encryptedData, 33, iv, 0, 12);
					System.arraycopy(encryptedData, 45, encryptedContent, 0,
							encryptedContent.length);
					com.professor.zerion.android.vault.crypto.Argon2 argon2 =
							new com.professor.zerion.android.vault.crypto.Argon2();
					com.professor.zerion.android.vault.crypto.Argon2.Argon2Params params =
							com.professor.zerion.android.vault.crypto.Argon2
									.Argon2Params.getDefault();
					passwordKey = argon2.deriveKey(passwordChars, salt, params);
				} else {
					salt = new byte[16];
					encryptedContent = new byte[encryptedData.length - 28];
					System.arraycopy(encryptedData, 0, salt, 0, 16);
					System.arraycopy(encryptedData, 16, iv, 0, 12);
					System.arraycopy(encryptedData, 28, encryptedContent, 0,
							encryptedContent.length);
					javax.crypto.SecretKeyFactory factory =
							javax.crypto.SecretKeyFactory.getInstance(
									"PBKDF2WithHmacSHA256");
					javax.crypto.spec.PBEKeySpec spec =
							new javax.crypto.spec.PBEKeySpec(
									passwordChars, salt, 200000, 256);
					passwordKey = factory.generateSecret(spec).getEncoded();
					spec.clearPassword();
				}

				javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
				javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(passwordKey, "AES");
				javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
				cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec);

				byte[] decryptedContent = cipher.doFinal(encryptedContent);
				String noteContent = new String(decryptedContent, java.nio.charset.StandardCharsets.UTF_8);
				content.postValue(noteContent);

				Arrays.fill(encryptedData, (byte) 0);
				Arrays.fill(passwordKey, (byte) 0);
				Arrays.fill(decryptedContent, (byte) 0);
				Arrays.fill(salt, (byte) 0);

			} catch (javax.crypto.BadPaddingException e) {
				content.postValue(null);
			} catch (SecurityException e) {
				content.postValue(null);
				errorMessage.postValue("Please unlock your vault first");
			} catch (Exception e) {
				content.postValue(null);
				errorMessage.postValue("Failed to decrypt note");
			} finally {
				Arrays.fill(passwordChars, '\0');
			}
		});

		return content;
	}

	public void addMediaToVault(VaultItem.ItemType type, String name, byte[] content, String mimeType) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.addMediaItem(type, name, content, mimeType);
				successMessage.postValue("Added to vault");
				loadVaultItems();
			} catch (SecurityException e) {
				errorMessage.postValue("Security error");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Failed to add to vault");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void deleteItem(String itemId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.deleteItem(itemId);
				loadVaultItems();
				successMessage.postValue("Item deleted");
			} catch (SecurityException e) {
				errorMessage.postValue("Vault locked");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Delete failed");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void getMediaContent(String itemId, MediaContentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContent(itemId);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError("Empty content");
					});
					return;
				}

				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onContentRetrieved(content);
				});
			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Vault is locked");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to load content");
				});
			}
		});
	}

	public interface MediaContentCallback {
		void onContentRetrieved(byte[] content);
		void onError(String error);
	}

	public void getThumbnail(String itemId, ThumbnailCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] thumbnail = vaultManager.getThumbnail(itemId);

				if (thumbnail == null) {
					thumbnail = vaultManager.getItemContent(itemId);
				}

				if (thumbnail == null || thumbnail.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError("Empty thumbnail");
					});
					return;
				}

				byte[] finalThumbnail = thumbnail;
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onThumbnailRetrieved(finalThumbnail);
				});
			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Vault is locked");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to load thumbnail");
				});
			}
		});
	}

	public interface ThumbnailCallback {
		void onThumbnailRetrieved(byte[] thumbnail);
		void onError(String error);
	}

	public interface DocumentCallback {
		void onLoaded(byte[] content, String mimeType);

		void onError(String error);
	}

	public void loadDocumentSecure(String itemId, DocumentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}

		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.loadDocumentContentSecure(itemId);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError("Document is empty");
					});
					return;
				}

				VaultItem item = null;
				for (VaultItem vaultItem : vaultManager.listItems()) {
					if (vaultItem.id.equals(itemId)) {
						item = vaultItem;
						break;
					}
				}

				String filename = item != null ? item.name : "";
				com.professor.zerion.android.vault.utils.MimeUtils.MimeType mimeType =
						com.professor.zerion.android.vault.utils.MimeUtils.detectMimeType(content, filename);

				byte[] finalContent = content;
				String finalMimeType = mimeType.mimeType;
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onLoaded(finalContent, finalMimeType);
				});

			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Vault is locked");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to load document");
				});
			}
		});
	}

	public void loadDocumentWithPassword(String itemId, @Nullable char[] extraPassword,
			DocumentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}

		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContentWithPassword(itemId, extraPassword);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError("Document is empty");
					});
					return;
				}

				VaultItem item = null;
				for (VaultItem vaultItem : vaultManager.listItems()) {
					if (vaultItem.id.equals(itemId)) {
						item = vaultItem;
						break;
					}
				}

				String filename = item != null ? item.name : "";
				com.professor.zerion.android.vault.utils.MimeUtils.MimeType mimeType =
						com.professor.zerion.android.vault.utils.MimeUtils.detectMimeType(content, filename);

				byte[] finalContent = content;
				String finalMimeType = mimeType.mimeType;
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onLoaded(finalContent, finalMimeType);
				});

			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Incorrect password");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to decrypt");
				});
			} finally {
				if (extraPassword != null) {
					Arrays.fill(extraPassword, '\0');
				}
			}
		});
	}

	public void checkAutoLock() {
		vaultManager.checkAutoLock();
	}

	public void lockIfUnlocked() {
		if (vaultManager.isUnlocked()) {
			vaultManager.lockVault();
		}
	}

	public void wipeVault() {
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.wipeVault();
				vaultState.postValue(VaultState.NOT_CREATED);
				clearSensitiveMemory();
				successMessage.postValue("Vault wiped");
			} catch (Exception e) {
				errorMessage.postValue("Failed to wipe vault");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void changePassword(char[] currentPassword, char[] newPassword) {
		if (!vaultManager.vaultExists()) {
			errorMessage.postValue("No vault exists");
			return;
		}
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.changePassword(currentPassword, newPassword);
				successMessage.postValue("Password changed successfully");
			} catch (SecurityException e) {
				errorMessage.postValue("Invalid current password");
			} catch (Exception e) {
				errorMessage.postValue("Failed to change password");
			} finally {
				Arrays.fill(currentPassword, '\0');
				Arrays.fill(newPassword, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public void exportVault(char[] exportPassword, ExportCallback callback) {
		if (!vaultManager.isUnlocked()) {
			callback.onExportError("Please unlock your vault first");
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] exportData = vaultManager.exportVault(exportPassword);
				callback.onExportSuccess(exportData);
			} catch (Exception e) {
				callback.onExportError("Export failed");
			} finally {
				Arrays.fill(exportPassword, '\0');
			}
		});
	}

	public interface ExportCallback {
		void onExportSuccess(byte[] data);
		void onExportError(String error);
	}

	private boolean isPasswordComplex(String password) {
		boolean hasUpper = false;
		boolean hasLower = false;
		boolean hasDigit = false;

		for (char c : password.toCharArray()) {
			if (Character.isUpperCase(c)) hasUpper = true;
			if (Character.isLowerCase(c)) hasLower = true;
			if (Character.isDigit(c)) hasDigit = true;
		}

		return hasUpper && hasLower && hasDigit;
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		clearSensitiveMemory();
		if (vaultManager.isUnlocked()) {
			vaultManager.lockVault();
		}
	}
}