package com.professor.zerion.android.security;

import android.content.Context;
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
		try {
			Process process = Runtime.getRuntime().exec("ps");
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				String lowerLine = line.toLowerCase();
				for (String indicator : FORENSIC_INDICATORS) {
					if (lowerLine.contains(indicator)) {
						return true;
					}
				}
			}
			reader.close();
		} catch (Exception e) {
		}
		return false;
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
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream(functionFile)));
				String function = reader.readLine();
				reader.close();

				if (function != null && (function.contains("mtp") ||
						function.contains("ptp") || function.contains("adb"))) {
					return true;
				}
			}

			File stateFile = new File(USB_STATE_PATH);
			if (stateFile.exists()) {
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream(stateFile)));
				String state = reader.readLine();
				reader.close();

				if (state != null && state.contains("CONFIGURED")) {
					return true;
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

			java.net.Socket socket = new java.net.Socket("127.0.0.1", 5555);
			socket.close();
			return true;
		} catch (Exception e) {
		}
		return false;
	}

	private void handleForensicAttack() {
		wipeSensitiveMemory();
		corruptTemporaryFiles();
		notifySecurityBreach();
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
				RandomAccessFile raf = new RandomAccessFile(file, "rw");
				byte[] random = new byte[(int) Math.min(file.length(), 1024)];
				secureRandom.nextBytes(random);
				raf.write(random);
				raf.close();
			}
		} catch (Exception e) {
		}
	}

	private void protectMemory() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			try {
				Class<?> processClass = Class.forName("android.os.Process");
				java.lang.reflect.Method method = processClass.getMethod(
						"setMemoryProtection", int.class);
				method.invoke(null, 1);
			} catch (Exception e) {
			}
		}
	}

	private void storeDeviceId(String deviceId) {
		try {
			File file = new File(context.getFilesDir(), ".device_id");
			FileOutputStream fos = new FileOutputStream(file);

			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(deviceId.getBytes(StandardCharsets.UTF_8));

			fos.write(hash);
			fos.close();
		} catch (Exception e) {
		}
	}

	private String getStoredDeviceId() {
		try {
			File file = new File(context.getFilesDir(), ".device_id");
			if (!file.exists()) {
				return null;
			}

			FileInputStream fis = new FileInputStream(file);
			byte[] hash = new byte[32];
			int read = fis.read(hash);
			fis.close();

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
