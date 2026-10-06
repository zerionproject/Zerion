package org.zerionproject.core.account;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class PasswordChangeStorageFaultDeviceTest extends PasswordChangeDeviceFixture {

	private static final String PRIMARY = "db.key";
	private static final String BACKUP = "db.key.bak";

	private interface Writer {
		void write(File f, byte[] bytes) throws IOException;
	}

	private interface Fault {
		void write(File f, byte[] bytes, Writer real) throws IOException;
	}

	private AccountManagerImpl faulty(Fault fault) {
		return new AccountManagerImpl(config, crypto, null) {
			@Override
			protected void writeKeyFile(File f, byte[] bytes)
					throws IOException {
				fault.write(f, bytes, super::writeKeyFile);
			}
		};
	}

	private static byte[] garbageLike(byte[] bytes) {
		byte[] random = getRandomBytes(bytes.length / 2);
		random[0] = (byte) 0xFF;
		return toHexString(random).getBytes(UTF_8);
	}

	private String run(AccountManagerImpl m) {
		String reported;
		try {
			reported = change(m);
		} catch (SimulatedProcessDeath e) {
			reported = "nothing (process died)";
		}
		return "reported=" + reported + " after change: " + files()
				+ "; after restart: " + restart();
	}

	@Test
	public void aFailedPrimaryWriteWhoseRestoreFailsTooIsReportedAsUnconfirmed() {
		boolean[] broken = {false};
		String outcome = run(faulty((f, b, real) -> {
			if (broken[0]) throw new IOException("injected");
			if (f.getName().equals(PRIMARY)) {
				broken[0] = true;
				throw new IOException("injected");
			}
			real.write(f, b);
		}));
		expect("reported=KEY_REPLACEMENT_UNCERTAIN after change:"
				+ " primary=old backup=new; after restart: old unlocks,"
				+ " new rejected; primary=old backup=old", outcome);
	}

	@Test
	public void aSilentlyCorruptedPrimaryIsDetectedAndRolledBack() {
		boolean[] once = {false};
		String outcome = run(faulty((f, b, real) -> {
			if (f.getName().equals(PRIMARY) && !once[0]) {
				once[0] = true;
				real.write(f, garbageLike(b));
				return;
			}
			real.write(f, b);
		}));
		expect("reported=KEY_REPLACEMENT_FAILED after change:"
				+ " primary=old backup=old; after restart: old unlocks,"
				+ " new rejected; primary=old backup=old", outcome);
	}

	@Test
	public void anUncheckedStorageExceptionIsReportedAndRolledBack() {
		boolean[] once = {false};
		String outcome = run(faulty((f, b, real) -> {
			if (f.getName().equals(PRIMARY) && !once[0]) {
				once[0] = true;
				throw new SecurityException("injected");
			}
			real.write(f, b);
		}));
		expect("reported=KEY_REPLACEMENT_FAILED after change:"
				+ " primary=old backup=old; after restart: old unlocks,"
				+ " new rejected; primary=old backup=old", outcome);
	}

	@Test
	public void aReplacementThatTookEffectAndCannotBeUndoneIsReportedAsDone() {
		boolean[] broken = {false};
		String outcome = run(faulty((f, b, real) -> {
			if (broken[0]) throw new IOException("injected");
			real.write(f, b);
			if (f.getName().equals(PRIMARY)) {
				broken[0] = true;
				throw new IOException("injected after the rename");
			}
		}));
		expect("reported=SUCCESS after change: primary=new backup=new;"
				+ " after restart: old rejected, new unlocks;"
				+ " primary=new backup=new", outcome);
	}

	@Test
	public void aReplacementThatTookEffectButReportedAnErrorIsRolledBack() {
		boolean[] once = {false};
		String outcome = run(faulty((f, b, real) -> {
			real.write(f, b);
			if (f.getName().equals(PRIMARY) && !once[0]) {
				once[0] = true;
				throw new IOException("injected after the rename");
			}
		}));
		expect("reported=KEY_REPLACEMENT_FAILED after change:"
				+ " primary=old backup=old; after restart: old unlocks,"
				+ " new rejected; primary=old backup=old", outcome);
	}

	@Test
	public void aProcessDeathBetweenTheTwoFilesLeavesTheOldPassword() {
		String outcome = run(faulty((f, b, real) -> {
			real.write(f, b);
			if (f.getName().equals(BACKUP)) throw new SimulatedProcessDeath();
		}));
		expect("reported=nothing (process died) after change:"
				+ " primary=old backup=new; after restart: old unlocks,"
				+ " new rejected; primary=old backup=old", outcome);
	}

	@Test
	public void aProcessDeathAfterTheReplacementLeavesOnlyTheNewPassword() {
		String outcome = run(faulty((f, b, real) -> {
			real.write(f, b);
			if (f.getName().equals(PRIMARY)) throw new SimulatedProcessDeath();
		}));
		expect("reported=nothing (process died) after change:"
				+ " primary=new backup=new; after restart: old rejected,"
				+ " new unlocks; primary=new backup=new", outcome);
	}

	@Test
	public void anUnverifiableOutcomeIsReportedAsUnconfirmed() {
		boolean[] broken = {false};
		String outcome = run(faulty((f, b, real) -> {
			if (broken[0]) throw new IOException("injected");
			if (f.getName().equals(PRIMARY)) {
				broken[0] = true;
				real.write(f, garbageLike(b));
				return;
			}
			real.write(f, b);
		}));
		expect("reported=KEY_REPLACEMENT_UNCERTAIN after change:"
				+ " primary=other backup=new; after restart: old rejected,"
				+ " new unlocks; primary=new backup=new", outcome);
	}
}
