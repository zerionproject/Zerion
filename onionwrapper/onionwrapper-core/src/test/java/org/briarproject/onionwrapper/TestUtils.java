package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.Arrays.asList;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.onionwrapper.util.OsUtils.isLinux;
import static org.briarproject.onionwrapper.util.OsUtils.isMac;
import static org.briarproject.onionwrapper.util.OsUtils.isWindows;

@ThreadSafe
@NotNullByDefault
public class TestUtils {

	private static final Logger LOG = getLogger(TestUtils.class.getName());

	private static final AtomicInteger nextTestDir = new AtomicInteger(0);

	public static File getTestDirectory() {
		return new File("test.tmp/" + nextTestDir.getAndIncrement());
	}

	public static void deleteTestDirectory(File testDir) {
		deleteFileOrDir(testDir);
		// Delete test.tmp if empty
		testDir.getParentFile().delete();
	}

	public static void deleteFileOrDir(File f) {
		if (f.isFile()) {
			delete(f);
		} else if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children == null) {
				if (LOG.isLoggable(WARNING)) {
					LOG.warning("Could not list files in " + f.getAbsolutePath());
				}
			} else {
				for (File child : children) deleteFileOrDir(child);
			}
			delete(f);
		}
	}

	public static void delete(File f) {
		if (!f.delete() && LOG.isLoggable(WARNING)) {
			LOG.warning("Could not delete " + f.getAbsolutePath());
		}
	}

	@Nullable
	public static String getArchitectureForTorBinary() {
		String arch = System.getProperty("os.arch");
		if (arch == null) return null;
		if (isLinux()) {
			//noinspection IfCanBeSwitch
			if (arch.equals("amd64")) return "x86_64";
			else if (arch.equals("aarch64")) return "aarch64";
			else if (arch.equals("arm")) return "armhf";
		} else if (isWindows()) {
			if (arch.equals("amd64")) return "x86_64";
		} else if (isMac()) {
			if (arch.equals("amd64")) return "x86_64";
			else if (arch.equals("aarch64")) return "aarch64";
		}
		return null;
	}

	public static boolean isOptionalTestEnabled(Class<?> testClass) {
		String optionalTests = System.getenv("OPTIONAL_TESTS");
		return optionalTests != null &&
				asList(optionalTests.split(",")).contains(testClass.getName());
	}
}
