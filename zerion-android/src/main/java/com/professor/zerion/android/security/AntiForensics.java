package com.professor.zerion.android.security;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.briarproject.nullsafety.NotNullByDefault;

@Singleton
@NotNullByDefault
public class AntiForensics {

	private static final List<String> FORENSIC_INDICATORS = Arrays.asList(
			"cellebrite",
			"ufed",
			"oxygen",
			"xry",
			"mobilyze",
			"lantern",
			"paraben",
			"blackbag",
			"graykey",
			"msab",
			"magnet",
			"axiom"
	);

	private static final String USB_STATE_PATH = "/sys/class/android_usb/android0/state";
	private static final String USB_FUNCTION_PATH = "/sys/class/android_usb/android0/functions";

	private final Context context;
	private FileObserver usbObserver;
	private volatile boolean isUnderAttack = false;
	private volatile Runnable usbPanicAction = null;
	private final SecureRandom secureRandom = new SecureRandom();

	@Inject
	public AntiForensics(Context context) {
		this.context = context.getApplicationContext();
		initializeProtections();
	}

	private void initializeProtections() {
		startUsbMonitoring();
		protectMemory();
	}

	public boolean detectForensicTools() {
		if (checkRunningProcesses()) {
			return true;
		}

		if (checkForensicFiles()) {
			return true;
		}

		if (isUsbDataTransferActive()) {
			return true;
		}

		if (checkSystemAnomalies()) {
			return true;
		}

		return false;
	}

	private boolean checkRunningProcesses() {
		Process process = null;
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			ProcessBuilder pb = new ProcessBuilder("ps");
			pb.redirectErrorStream(true);
			process = pb.start();
			final Process p = process;
			Future<Boolean> future = executor.submit(() -> {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(p.getInputStream(),
								StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						String lower = line.toLowerCase();
						for (String indicator : FORENSIC_INDICATORS) {
							if (lower.contains(indicator)) return Boolean.TRUE;
						}
					}
				}
				return Boolean.FALSE;
			});
			return future.get(3, TimeUnit.SECONDS);
		} catch (Exception e) {
			return false;
		} finally {
			executor.shutdownNow();
			if (process != null) process.destroyForcibly();
		}
	}

	private boolean checkForensicFiles() {
		List<String> paths = Arrays.asList(
				"/data/local/tmp/",
				"/sdcard/",
				Environment.getExternalStorageDirectory().getPath()
		);

		for (String path : paths) {
			File dir = new File(path);
			if (dir.exists() && dir.isDirectory()) {
				if (scanDirectoryForForensicFiles(dir)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean scanDirectoryForForensicFiles(File dir) {
		try {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					String fileName = file.getName().toLowerCase();
					for (String indicator : FORENSIC_INDICATORS) {
						if (fileName.contains(indicator)) {
							return true;
						}
					}
				}
			}
		} catch (Exception e) {
		}
		return false;
	}

	private void startUsbMonitoring() {
		try {
			File usbState = new File(USB_STATE_PATH);
			if (usbState.exists()) {
				usbObserver = new FileObserver(USB_STATE_PATH, FileObserver.MODIFY) {
					@Override
					public void onEvent(int event, String path) {
						if (event == FileObserver.MODIFY) {
							checkUsbState();
						}
					}
				};
				usbObserver.startWatching();
			}
		} catch (Exception e) {
		}
	}

	private boolean isUsbDataTransferActive() {
		try {
			File functionFile = new File(USB_FUNCTION_PATH);
			if (functionFile.exists()) {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream(functionFile),
								StandardCharsets.UTF_8))) {
					String function = reader.readLine();
					if (function != null && (function.contains("mtp") ||
							function.contains("ptp") || function.contains("adb"))) {
						return true;
					}
				}
			}

			File stateFile = new File(USB_STATE_PATH);
			if (stateFile.exists()) {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream(stateFile),
								StandardCharsets.UTF_8))) {
					String state = reader.readLine();
					if (state != null && state.contains("CONFIGURED")) {
						return true;
					}
				}
			}
		} catch (Exception e) {
		}
		return false;
	}

	private void checkUsbState() {
		if (isUsbDataTransferActive() && !isUnderAttack) {
			isUnderAttack = true;
			handleForensicAttack();
		}
	}

	private boolean checkSystemAnomalies() {
		if (isSafeMode()) {
			return true;
		}

		if (hasDeviceIdChanged()) {
			return true;
		}

		if (hasDebugBridge()) {
			return true;
		}

		return false;
	}

	private boolean isSafeMode() {
		try {
			String safeMode = System.getProperty("persist.sys.safemode", "");
			if ("1".equals(safeMode)) {
				return true;
			}

			return false;
		} catch (Exception e) {
			return false;
		}
	}

	private boolean hasDeviceIdChanged() {
		try {
			String androidId = Settings.Secure.getString(
					context.getContentResolver(),
					Settings.Secure.ANDROID_ID);

			String storedId = getStoredDeviceId();
			if (storedId != null && !storedId.equals(androidId)) {
				return true;
			}

			storeDeviceId(androidId);
		} catch (Exception e) {
		}
		return false;
	}

	private boolean hasDebugBridge() {
		try {
			String jdwp = System.getProperty("java.compiler");
			if (jdwp != null && jdwp.contains("JDWP")) {
				return true;
			}

			try (java.net.Socket socket = new java.net.Socket()) {
				socket.connect(
						new java.net.InetSocketAddress("127.0.0.1", 5555), 1000);
				return true;
			}
		} catch (Exception e) {
		}
		return false;
	}

	private void handleForensicAttack() {
		wipeSensitiveMemory();
		corruptTemporaryFiles();
		Runnable panic = usbPanicAction;
		if (panic != null) {
			try {
				panic.run();
			} catch (Exception ignored) {
			}
		} else {
			notifySecurityBreach();
		}
	}

	private void wipeSensitiveMemory() {
		try {
			System.gc();
			System.runFinalization();
			System.gc();

			byte[] wiper = new byte[1024 * 1024];
			secureRandom.nextBytes(wiper);
			Arrays.fill(wiper, (byte) 0);
		} catch (OutOfMemoryError e) {
		}
	}

	public void wipeCachesOnLogout() {
		try {
			corruptTemporaryFiles();
		} catch (Exception ignored) {
		}
	}

	public void armUsbPanic(Runnable wipeAction) {
		this.usbPanicAction = wipeAction;
	}

	public void disarmUsbPanic() {
		this.usbPanicAction = null;
	}

	private void corruptTemporaryFiles() {
		try {
			File tempDir = context.getCacheDir();
			if (tempDir.exists()) {
				corruptDirectory(tempDir);
			}

			File externalCache = context.getExternalCacheDir();
			if (externalCache != null && externalCache.exists()) {
				corruptDirectory(externalCache);
			}
		} catch (Exception e) {
		}
	}

	private void corruptDirectory(File dir) {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					corruptDirectory(file);
				} else {
					corruptFile(file);
				}
			}
		}
	}

	private void corruptFile(File file) {
		try {
			if (file.exists() && file.canWrite()) {
				long fileLen = file.length();
				if (fileLen == 0) return;
				try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
					byte[] chunk = new byte[(int) Math.min(fileLen, 65536)];
					long remaining = fileLen;
					while (remaining > 0) {
						int toWrite = (int) Math.min(remaining, chunk.length);
						secureRandom.nextBytes(chunk);
						raf.write(chunk, 0, toWrite);
						remaining -= toWrite;
					}
					raf.getFD().sync();
				}
			}
		} catch (Exception e) {
		}
	}

	private void protectMemory() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			try {
				Class<?> processClass = Class.forName("android.os.Process");
				java.lang.reflect.Method method = processClass.getDeclaredMethod(
						"setMemoryProtection", int.class);
				method.setAccessible(true);
				method.invoke(null, 1);
			} catch (NoSuchMethodException e) {
			} catch (ClassNotFoundException e) {
			} catch (Exception e) {
			}
		}
	}

	private void storeDeviceId(String deviceId) {
		try {
			File file = new File(context.getFilesDir(), ".device_id");
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(deviceId.getBytes(StandardCharsets.UTF_8));
			try (FileOutputStream fos = new FileOutputStream(file)) {
				fos.write(hash);
			}
		} catch (Exception e) {
		}
	}

	private String getStoredDeviceId() {
		try {
			File file = new File(context.getFilesDir(), ".device_id");
			if (!file.exists()) {
				return null;
			}
			byte[] hash = new byte[32];
			int read;
			try (FileInputStream fis = new FileInputStream(file)) {
				read = fis.read(hash);
			}
			if (read == 32) {
				return bytesToHex(hash);
			}
		} catch (Exception e) {
		}
		return null;
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder result = new StringBuilder();
		for (byte b : bytes) {
			result.append(String.format("%02x", b));
		}
		return result.toString();
	}

	private void notifySecurityBreach() {
		try {
			Intent lockIntent = new Intent(context,
					com.professor.zerion.android.ZerionService.class);
			lockIntent.setAction("com.professor.zerion.android.LOCK");
			lockIntent.putExtra("pid", android.os.Process.myPid());
			context.startService(lockIntent);
		} catch (Exception ignored) {
		}
	}

	public void cleanup() {
		if (usbObserver != null) {
			usbObserver.stopWatching();
			usbObserver = null;
		}
	}

	public int getForensicThreatLevel() {
		int threatLevel = 0;

		if (isUsbDataTransferActive()) threatLevel += 3;
		if (checkRunningProcesses()) threatLevel += 3;
		if (checkForensicFiles()) threatLevel += 2;
		if (checkSystemAnomalies()) threatLevel += 2;

		return Math.min(threatLevel, 10);
	}
}
