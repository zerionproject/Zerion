package org.zerionproject.core.account;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyStrengthenerException;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class PasswordChangeFailureBoundaryTest extends PasswordChangeFixture {

	private File block(String keyFileName) {
		File tmp = keyFile(keyFileName + ".tmp");
		assertTrue(tmp.mkdir());
		return tmp;
	}

	private static void unblock(File tmp) {
		assertTrue(tmp.delete());
	}

	@Test
	public void aFailureBeforeAnyWriteIsReportedAndTheOldPasswordStillUnlocks() {
		CryptoComponent real = crypto;
		InvocationHandler failNewEncryption = (proxy, method, args) -> {
			if (method.getName().equals("encryptWithPassword")
					&& Arrays.equals((char[]) args[1], NEW.toCharArray())) {
				throw new KeyStrengthenerException(
						new IllegalStateException("injected"));
			}
			try {
				return method.invoke(real, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		};
		CryptoComponent failing = (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class}, failNewEncryption);
		String reported = change(new AccountManagerImpl(config, failing, null));
		expect("reported=KEY_STRENGTHENER_ERROR after change: primary=old"
						+ " backup=old; after restart: old unlocks, new rejected;"
						+ " primary=old backup=old",
				"reported=" + reported + " after change: " + files()
						+ "; after restart: " + restart());
	}

	@Test
	public void aBackupThatCannotBeWrittenIsReportedAndTheOldPasswordStillUnlocks() {
		File tmp = block("db.key.bak");
		String reported = change(manager());
		String atFailure = files();
		unblock(tmp);
		expect("reported=KEY_REPLACEMENT_FAILED after change: primary=old"
						+ " backup=old; after restart: old unlocks, new rejected;"
						+ " primary=old backup=old",
				"reported=" + reported + " after change: " + atFailure
						+ "; after restart: " + restart());
	}

	@Test
	public void aPrimaryThatCannotBeWrittenIsReportedAndTheOldPasswordStillUnlocks() {
		File tmp = block("db.key");
		String reported = change(manager());
		String atFailure = files();
		unblock(tmp);
		expect("reported=KEY_REPLACEMENT_FAILED after change: primary=old"
						+ " backup=old; after restart: old unlocks, new rejected;"
						+ " primary=old backup=old",
				"reported=" + reported + " after change: " + atFailure
						+ "; after restart: " + restart());
	}

	@Test
	public void noWritableKeyFileIsReportedAndTheOldPasswordStillUnlocks() {
		File a = block("db.key");
		File b = block("db.key.bak");
		String reported = change(manager());
		String atFailure = files();
		unblock(a);
		unblock(b);
		expect("reported=KEY_REPLACEMENT_FAILED after change: primary=old"
						+ " backup=old; after restart: old unlocks, new rejected;"
						+ " primary=old backup=old",
				"reported=" + reported + " after change: " + atFailure
						+ "; after restart: " + restart());
	}

	@Test
	public void aCompletedChangeIsReportedAndOnlyTheNewPasswordUnlocks() {
		String reported = change(manager());
		expect("reported=SUCCESS after change: primary=new backup=new;"
						+ " after restart: old rejected, new unlocks;"
						+ " primary=new backup=new",
				"reported=" + reported + " after change: " + files()
						+ "; after restart: " + restart());
	}
}
