package com.professor.zerion.android.vault.wallet;

import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class WalletStore
		implements com.professor.zerion.android.vault.wallet.xmr.XmrStore {

	private static final byte CONTENT_VERSION = 1;
	private static final char NAME_SEP = '\n';

	private final VaultManager vaultManager;

	@Inject
	public WalletStore(VaultManager vaultManager) {
		this.vaultManager = vaultManager;
	}

	public String createWallet(WalletCoin coin, String name, char[] mnemonic,
			@Nullable char[] password) throws Exception {
		String itemName = coin.getCode() + NAME_SEP + name;
		byte[] utf8 = toUtf8(mnemonic);
		byte[] content = new byte[1 + utf8.length];
		content[0] = CONTENT_VERSION;
		System.arraycopy(utf8, 0, content, 1, utf8.length);
		SecureMemory.shred(utf8);
		try {
			VaultItem item;
			if (password != null && password.length > 0) {
				item = vaultManager.addItemWithPassword(
						VaultItem.ItemType.WALLET, itemName, content, password);
			} else {
				item = vaultManager.addItem(
						VaultItem.ItemType.WALLET, itemName, content);
			}
			return item.id;
		} finally {
			SecureMemory.shred(content);
		}
	}

	public List<WalletRecord> listWallets() throws Exception {
		List<WalletRecord> out = new ArrayList<>();
		for (VaultItem item : vaultManager.listItems()) {
			if (item.type != VaultItem.ItemType.WALLET) {
				continue;
			}
			int sep = item.name.indexOf(NAME_SEP);
			if (sep < 0) {
				continue;
			}
			WalletCoin coin;
			try {
				coin = WalletCoin.fromCode(item.name.substring(0, sep));
			} catch (IllegalArgumentException e) {
				continue;
			}
			String label = item.name.substring(sep + 1);
			out.add(new WalletRecord(item.id, coin, label,
					item.createdTimestamp, item.hasExtraPassword));
		}
		return out;
	}

	public enum Access { REJECT, WITH_PASSWORD, NO_PASSWORD }

	/**
	 * Pure fail-closed decision for how a wallet's seed may be read. A
	 * password-protected wallet (existing/legacy included) can only ever be read
	 * through the password-verified AEAD path; an empty/absent password is
	 * rejected outright and never silently falls through to the no-password path.
	 */
	public static Access accessFor(boolean walletProtected,
			@Nullable char[] password) {
		if (walletProtected) {
			return (password == null || password.length == 0)
					? Access.REJECT : Access.WITH_PASSWORD;
		}
		return Access.NO_PASSWORD;
	}

	public String loadMnemonic(String walletId, @Nullable char[] password)
			throws Exception {
		boolean walletProtected = vaultManager.itemHasExtraPassword(walletId);
		Access access = accessFor(walletProtected, password);
		if (access == Access.REJECT) {
			throw new SecurityException("Wallet password required");
		}
		byte[] content = access == Access.WITH_PASSWORD
				? vaultManager.getItemContentWithPassword(walletId, password)
				: vaultManager.getItemContent(walletId);
		try {
			if (content.length < 1) {
				throw new IllegalStateException("Empty wallet content");
			}
			return new String(content, 1, content.length - 1,
					StandardCharsets.UTF_8);
		} finally {
			SecureMemory.shred(content);
		}
	}

	/**
	 * Same fail-closed decrypt as {@link #loadMnemonic} but returns the secret
	 * as a mutable {@code char[]} the caller must wipe, so no immutable String
	 * copy of the mnemonic is created. Used by the XMR layer; the BTC path is
	 * unchanged.
	 */
	public char[] loadMnemonicChars(String walletId, @Nullable char[] password)
			throws Exception {
		boolean walletProtected = vaultManager.itemHasExtraPassword(walletId);
		Access access = accessFor(walletProtected, password);
		if (access == Access.REJECT) {
			throw new SecurityException("Wallet password required");
		}
		byte[] content = access == Access.WITH_PASSWORD
				? vaultManager.getItemContentWithPassword(walletId, password)
				: vaultManager.getItemContent(walletId);
		try {
			if (content.length < 1) {
				throw new IllegalStateException("Empty wallet content");
			}
			java.nio.CharBuffer cb =
					StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(
							content, 1, content.length - 1));
			char[] out = new char[cb.remaining()];
			cb.get(out);
			java.util.Arrays.fill(cb.array(), '\0');
			return out;
		} finally {
			SecureMemory.shred(content);
		}
	}

	public void deleteWallet(String walletId) throws Exception {
		vaultManager.deleteItem(walletId);
	}

	private static final String CONFIG_CODE = "CFG";

	/**
	 * Guards every settings read-modify-write cycle. Settings are mutated from
	 * several executors; callers that read the settings JSON, change it and
	 * write it back must hold this lock for the whole cycle, otherwise a
	 * concurrent cycle can base its write on a stale read and silently drop
	 * the other side's change (pending-tx reservations included).
	 */
	public final Object settingsLock = new Object();

	@Override
	public Object settingsMonitor() {
		return settingsLock;
	}

	private static final String CONFIG_BASE = "cfg";

	private static long sequenceOf(VaultItem item) {
		String prefix = CONFIG_CODE + NAME_SEP + CONFIG_BASE;
		if (!item.name.startsWith(prefix)) {
			return -1;
		}
		String rest = item.name.substring(prefix.length());
		if (rest.isEmpty()) {
			return 0;
		}
		if (rest.charAt(0) != '.') {
			return -1;
		}
		try {
			return Long.parseLong(rest.substring(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Nullable
	public String readSettings() throws Exception {
		synchronized (settingsLock) {
			VaultItem newest = null;
			long newestSeq = -1;
			for (VaultItem item : vaultManager.listItems()) {
				if (item.type != VaultItem.ItemType.WALLET) {
					continue;
				}
				long seq = sequenceOf(item);
				if (seq < 0) {
					continue;
				}
				if (newest == null || seq > newestSeq
						|| (seq == newestSeq && item.createdTimestamp
						> newest.createdTimestamp)) {
					newest = item;
					newestSeq = seq;
				}
			}
			if (newest == null) return null;
			byte[] content = vaultManager.getItemContent(newest.id);
			try {
				return new String(content, StandardCharsets.UTF_8);
			} finally {
				SecureMemory.shred(content);
			}
		}
	}

	public void writeSettings(String json) throws Exception {
		synchronized (settingsLock) {
			List<VaultItem> stale = new ArrayList<>();
			long maxSeq = -1;
			for (VaultItem item : vaultManager.listItems()) {
				if (item.type != VaultItem.ItemType.WALLET) {
					continue;
				}
				long seq = sequenceOf(item);
				if (seq < 0) {
					continue;
				}
				stale.add(item);
				if (seq > maxSeq) {
					maxSeq = seq;
				}
			}
			vaultManager.addItem(VaultItem.ItemType.WALLET,
					CONFIG_CODE + NAME_SEP + CONFIG_BASE + "." + (maxSeq + 1),
					json.getBytes(StandardCharsets.UTF_8));
			for (VaultItem item : stale) {
				vaultManager.deleteItem(item.id);
			}
		}
	}

	private static final String JOURNAL_CODE = "SPENDQ";

	private static long journalSequenceOf(VaultItem item, String walletId) {
		String prefix = JOURNAL_CODE + NAME_SEP + walletId;
		if (!item.name.startsWith(prefix)) {
			return -1;
		}
		String rest = item.name.substring(prefix.length());
		if (rest.isEmpty()) {
			return 0;
		}
		if (rest.charAt(0) != '.') {
			return -1;
		}
		try {
			return Long.parseLong(rest.substring(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	@Nullable
	public String readSpendJournal(String walletId) throws Exception {
		synchronized (settingsLock) {
			VaultItem newest = null;
			long newestSeq = -1;
			for (VaultItem item : vaultManager.listItems()) {
				if (item.type != VaultItem.ItemType.WALLET) {
					continue;
				}
				long seq = journalSequenceOf(item, walletId);
				if (seq < 0) {
					continue;
				}
				if (newest == null || seq > newestSeq
						|| (seq == newestSeq && item.createdTimestamp
						> newest.createdTimestamp)) {
					newest = item;
					newestSeq = seq;
				}
			}
			if (newest == null) return null;
			byte[] content = vaultManager.getItemContent(newest.id);
			try {
				return new String(content, StandardCharsets.UTF_8);
			} finally {
				SecureMemory.shred(content);
			}
		}
	}

	@Override
	public void writeSpendJournal(String walletId, String journal)
			throws Exception {
		synchronized (settingsLock) {
			List<VaultItem> stale = new ArrayList<>();
			long maxSeq = -1;
			for (VaultItem item : vaultManager.listItems()) {
				if (item.type != VaultItem.ItemType.WALLET) {
					continue;
				}
				long seq = journalSequenceOf(item, walletId);
				if (seq < 0) {
					continue;
				}
				stale.add(item);
				if (seq > maxSeq) {
					maxSeq = seq;
				}
			}
			vaultManager.addItem(VaultItem.ItemType.WALLET,
					JOURNAL_CODE + NAME_SEP + walletId + "." + (maxSeq + 1),
					journal.getBytes(StandardCharsets.UTF_8));
			for (VaultItem item : stale) {
				vaultManager.deleteItem(item.id);
			}
		}
	}

	@Override
	public void removeSpendJournal(String walletId) throws Exception {
		synchronized (settingsLock) {
			List<VaultItem> stale = new ArrayList<>();
			for (VaultItem item : vaultManager.listItems()) {
				if (item.type != VaultItem.ItemType.WALLET) {
					continue;
				}
				if (journalSequenceOf(item, walletId) < 0) {
					continue;
				}
				stale.add(item);
			}
			for (VaultItem item : stale) {
				vaultManager.deleteItem(item.id);
			}
		}
	}

	private static byte[] toUtf8(char[] chars) {
		ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] out = new byte[bb.remaining()];
		bb.get(out);
		SecureMemory.shred(bb);
		return out;
	}
}
