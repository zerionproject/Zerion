package org.zerionproject.tor;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLock;
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static android.os.Build.SUPPORTED_ABIS;
import static android.os.Build.VERSION.SDK_INT;
import static java.util.Arrays.asList;

/**
 * The Tor wrapper for Android. The executables are the native libraries
 * the package manager installed from the APK, run in place where the
 * platform allows it, and are verified against the build-time pins before
 * every start.
 */
@NotNullByDefault
public class AndroidTorWrapper extends AbstractTorWrapper {

	private static final List<String> LIBRARY_ARCHITECTURES =
			asList("armeabi-v7a", "arm64-v8a", "x86", "x86_64");

	static final String TOR_LIB_NAME = "libtor.so";
	static final String LYREBIRD_LIB_NAME = "liblyrebird.so";

	private final Application app;
	private final AndroidWakeLock wakeLock;
	private final File torLib, lyrebirdLib;

	/**
	 * @param ioExecutor runs IO tasks, some for the life of the wrapper, so
	 * it needs an unbounded pool
	 * @param eventExecutor calls the observer; a single thread keeps events
	 * in order
	 * @param architecture the architecture of the Tor and lyrebird
	 * executables
	 * @param torDirectory where the Tor process keeps its state
	 */
	public AndroidTorWrapper(Application app,
			AndroidWakeLockManager wakeLockManager, Executor ioExecutor,
			Executor eventExecutor, String architecture, File torDirectory,
			int torSocksPort, int torControlPort,
			TorBinaryVerifier verifier) {
		super(ioExecutor, eventExecutor, architecture, torDirectory,
				torSocksPort, torControlPort, verifier);
		this.app = app;
		wakeLock = wakeLockManager.createWakeLock("TorPlugin");
		String nativeLibDir = app.getApplicationInfo().nativeLibraryDir;
		torLib = new File(nativeLibDir, TOR_LIB_NAME);
		lyrebirdLib = new File(nativeLibDir, LYREBIRD_LIB_NAME);
	}

	@Override
	protected int getProcessId() {
		return android.os.Process.myPid();
	}

	@Override
	protected long getLastUpdateTime() {
		try {
			PackageManager pm = app.getPackageManager();
			PackageInfo pi = pm.getPackageInfo(app.getPackageName(), 0);
			return pi.lastUpdateTime;
		} catch (NameNotFoundException e) {
			throw new AssertionError(e);
		}
	}

	@Override
	public InputStream getResourceInputStream(String name, String extension) {
		Resources res = app.getResources();
		int resId = res.getIdentifier(name, "raw", app.getPackageName());
		return res.openRawResource(resId);
	}

	@Override
	public void enableNetwork(boolean enable) throws IOException {
		if (enable) wakeLock.acquire();
		try {
			super.enableNetwork(enable);
		} finally {
			if (!enable) wakeLock.release();
		}
	}

	@Override
	public void stop() throws IOException, InterruptedException {
		try {
			super.stop();
		} finally {
			wakeLock.release();
		}
	}

	@Override
	protected File getTorExecutableFile() {
		return torLib.exists() ? torLib : super.getTorExecutableFile();
	}

	@Override
	public File getLyrebirdExecutableFile() {
		return lyrebirdLib.exists() ? lyrebirdLib
				: super.getLyrebirdExecutableFile();
	}

	@Override
	protected void installTorExecutable() throws IOException {
		installExecutable(super.getTorExecutableFile(), torLib,
				TOR_LIB_NAME);
	}

	@Override
	protected void installLyrebirdExecutable() throws IOException {
		installExecutable(super.getLyrebirdExecutableFile(), lyrebirdLib,
				LYREBIRD_LIB_NAME);
	}

	/**
	 * With the library installed by the package manager any copy an
	 * earlier version extracted is removed; the copy is never the file that
	 * is verified and run while the installed library exists, so a copy
	 * that cannot be removed is inert. Without the installed library the
	 * executable is extracted from the APK on platforms that still permit
	 * executing it from the app's data, and verified before it runs.
	 */
	private void installExecutable(File extracted, File lib, String libName)
			throws IOException {
		if (lib.exists()) {
			if (extracted.exists()) extracted.delete();
		} else if (SDK_INT < 29) {
			extractLibraryFromApk(libName, extracted);
		} else {
			throw new FileNotFoundException(lib.getAbsolutePath());
		}
	}

	private void extractLibraryFromApk(String libName, File dest)
			throws IOException {
		File sourceDir = new File(app.getApplicationInfo().sourceDir);
		if (sourceDir.isFile()) {
			File parent = sourceDir.getParentFile();
			if (parent != null) sourceDir = parent;
		}
		List<String> libPaths = getSupportedLibraryPaths(libName);
		for (File apk : findApkFiles(sourceDir)) {
			ZipInputStream zin = new ZipInputStream(new FileInputStream(apk));
			try {
				for (ZipEntry e = zin.getNextEntry(); e != null;
						e = zin.getNextEntry()) {
					if (libPaths.contains(e.getName())) {
						extract(zin, dest);
						return;
					}
				}
			} finally {
				zin.close();
			}
		}
		throw new FileNotFoundException(libName);
	}

	private List<File> findApkFiles(File root) {
		List<File> files = new ArrayList<>();
		findApkFiles(root, files);
		return files;
	}

	private void findApkFiles(File f, List<File> files) {
		if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) {
			files.add(f);
		} else if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children != null) {
				for (File child : children) findApkFiles(child, files);
			}
		}
	}

	private List<String> getSupportedLibraryPaths(String libName) {
		List<String> architectures = new ArrayList<>();
		for (String abi : SUPPORTED_ABIS) {
			if (LIBRARY_ARCHITECTURES.contains(abi)) {
				architectures.add("lib/" + abi + "/" + libName);
			}
		}
		return architectures;
	}
}
