package com.professor.zerion.android.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.zerionproject.core.api.plugin.TorSocksPort;
import org.briarproject.nullsafety.NotNullByDefault;

@Singleton
@NotNullByDefault
public class TorStatusMonitor {

    private static final long CHECK_INTERVAL = 5000;
    private static final long BANDWIDTH_CHECK_INTERVAL = 1000;

    private final Context context;
    private final int torSocksPort;
    private final ScheduledExecutorService executor;
    private final Handler mainHandler;

    private final MutableLiveData<TorStatus> torStatus = new MutableLiveData<>();
    private final MutableLiveData<List<TorCircuit>> circuits = new MutableLiveData<>();
    private final MutableLiveData<TorStatistics> statistics = new MutableLiveData<>();
    private final MutableLiveData<BandwidthUpdate> bandwidthUpdate = new MutableLiveData<>();

    private volatile boolean isMonitoring = false;
    private volatile int cachedBootstrapProgress = 0;

    private final AtomicLong totalBytesReceived = new AtomicLong();
    private final AtomicLong totalBytesSent = new AtomicLong();
    private volatile long monitoringStartTime = 0;
    private volatile long connectionStartTime = 0;
    private volatile boolean wasConnected = false;
    private volatile long sessionStartTime = 0;
    private final AtomicLong peakDownloadSpeed = new AtomicLong();
    private final AtomicLong peakUploadSpeed = new AtomicLong();
    private final AtomicLong bandwidthSampleCount = new AtomicLong();
    private final AtomicLong totalDownloadSpeedSum = new AtomicLong();
    private final AtomicLong totalUploadSpeedSum = new AtomicLong();

    @Inject
    public TorStatusMonitor(Context context, @TorSocksPort int torSocksPort) {
        this.context = context.getApplicationContext();
        this.torSocksPort = torSocksPort;
        this.executor = Executors.newScheduledThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());

        torStatus.setValue(new TorStatus(false, "Initializing", 0));
        circuits.setValue(new ArrayList<>());
        statistics.setValue(new TorStatistics());
    }

    public void startMonitoring() {
        if (isMonitoring) return;
        isMonitoring = true;
        monitoringStartTime = System.currentTimeMillis();
        if (sessionStartTime == 0) {
            sessionStartTime = monitoringStartTime;
        }

        executor.scheduleWithFixedDelay(this::checkTorStatus, 0, CHECK_INTERVAL, TimeUnit.MILLISECONDS);

        executor.scheduleWithFixedDelay(this::updateCircuitInfo, 1000, 10000, TimeUnit.MILLISECONDS);

        executor.scheduleWithFixedDelay(this::updateBandwidth, 0, BANDWIDTH_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void stopMonitoring() {
        isMonitoring = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void checkTorStatus() {
        if (!isMonitoring) return;

        try {
            boolean isConnected = false;
            String statusMessage = "Disconnected";
            int bootstrapProgress = 0;

            if (isTorSocksActive()) {
                if (testTorConnection()) {
                    isConnected = true;
                    statusMessage = "Connected";
                    bootstrapProgress = 100;
                } else {
                    statusMessage = "Connecting...";
                    bootstrapProgress = getBootstrapProgress();
                }
            } else {
                if (isTorProcessRunning()) {
                    statusMessage = "Starting...";
                    bootstrapProgress = getBootstrapProgress();
                }
            }

            if (isConnected && !wasConnected) {
                connectionStartTime = System.currentTimeMillis();
            } else if (!isConnected) {
                connectionStartTime = 0;
            }
            wasConnected = isConnected;

            final TorStatus status = new TorStatus(isConnected, statusMessage, bootstrapProgress);
            mainHandler.post(() -> torStatus.setValue(status));

        } catch (Exception e) {
            mainHandler.post(() -> torStatus.setValue(new TorStatus(false, "Error", 0)));
        }
    }

    private boolean isTorSocksActive() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", torSocksPort), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean testTorConnection() {
        java.net.Socket socket = null;
        try {
            socket = new java.net.Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", torSocksPort), 2000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();

            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] response = new byte[2];
            int read = in.read(response);

            return read == 2 && response[0] == 0x05 && response[1] == 0x00;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean isTorProcessRunning() {

        return isTorSocksActive();
    }

    private int getBootstrapProgress() {

        if (cachedBootstrapProgress >= 100) return 100;

        try {
            File torLog = new File(context.getFilesDir(), "tor/tor.log");
            if (!torLog.exists()) return 0;

            int maxProgress = 0;
            try (RandomAccessFile raf = new RandomAccessFile(torLog, "r")) {
                long length = raf.length();
                long tailSize = 4096;
                if (length > tailSize) {
                    raf.seek(length - tailSize);
                    raf.readLine();
                }
                String line;
                while ((line = raf.readLine()) != null) {
                    if (line.contains("Bootstrapped")) {
                        int start = line.indexOf("Bootstrapped ") + 13;
                        int end = line.indexOf("%", start);
                        if (start > 0 && end > start) {
                            try {
                                int progress = Integer.parseInt(
                                        line.substring(start, end).trim());
                                maxProgress = Math.max(maxProgress, progress);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
            cachedBootstrapProgress = maxProgress;
            return maxProgress;
        } catch (Exception e) {
            return cachedBootstrapProgress;
        }
    }

    private void updateCircuitInfo() {
        if (!isMonitoring) return;

        try {
            List<TorCircuit> circuitList = new ArrayList<>();

            TorStatus status = torStatus.getValue();
            if (status != null && status.isConnected) {
                circuitList.add(new TorCircuit(
                        1,
                        "BUILT",
                        Arrays.asList(
                                new TorNode("Guard", "Unknown", ""),
                                new TorNode("Middle", "Unknown", ""),
                                new TorNode("Exit", "Unknown", "")
                        ),
                        connectionStartTime > 0 ? connectionStartTime : System.currentTimeMillis()
                ));
            }

            mainHandler.post(() -> circuits.setValue(circuitList));

        } catch (Exception e) {
        }
    }

    private void updateBandwidth() {
        if (!isMonitoring) return;

        try {
            long[] bandwidth = getNetworkBandwidth();
            long downloadSpeed = bandwidth[0];
            long uploadSpeed = bandwidth[1];

            if (downloadSpeed > 0 || uploadSpeed > 0) {
                totalBytesReceived.addAndGet(downloadSpeed);
                totalBytesSent.addAndGet(uploadSpeed);
            }

            peakDownloadSpeed.updateAndGet(v -> Math.max(v, downloadSpeed));
            peakUploadSpeed.updateAndGet(v -> Math.max(v, uploadSpeed));
            bandwidthSampleCount.incrementAndGet();
            totalDownloadSpeedSum.addAndGet(downloadSpeed);
            totalUploadSpeedSum.addAndGet(uploadSpeed);

            final BandwidthUpdate update = new BandwidthUpdate(
                    downloadSpeed,
                    uploadSpeed,
                    totalBytesReceived.get(),
                    totalBytesSent.get()
            );

            mainHandler.post(() -> bandwidthUpdate.setValue(update));

            updateStatisticsRealtime(downloadSpeed, uploadSpeed);

        } catch (Exception e) {
        }
    }

    private final int appUid = android.os.Process.myUid();
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastBandwidthCheck = 0;

    private long[] getNetworkBandwidth() {
        try {
            long currentRx = android.net.TrafficStats.getUidRxBytes(appUid);
            long currentTx = android.net.TrafficStats.getUidTxBytes(appUid);
            long currentTime = System.currentTimeMillis();

            if (currentRx == android.net.TrafficStats.UNSUPPORTED ||
                currentTx == android.net.TrafficStats.UNSUPPORTED) {
                return new long[]{0, 0};
            }

            if (lastBandwidthCheck == 0) {
                lastRxBytes = currentRx;
                lastTxBytes = currentTx;
                lastBandwidthCheck = currentTime;
                return new long[]{0, 0};
            }

            long timeDelta = currentTime - lastBandwidthCheck;
            if (timeDelta <= 0) {
                return new long[]{0, 0};
            }

            long rxDelta = currentRx - lastRxBytes;
            long txDelta = currentTx - lastTxBytes;

            long downloadSpeed = (rxDelta * 1000) / timeDelta;
            long uploadSpeed = (txDelta * 1000) / timeDelta;

            lastRxBytes = currentRx;
            lastTxBytes = currentTx;
            lastBandwidthCheck = currentTime;

            TorStatus status = torStatus.getValue();
            if (status == null || !status.isConnected) {
                return new long[]{0, 0};
            }

            return new long[]{Math.max(0, downloadSpeed), Math.max(0, uploadSpeed)};

        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private void updateStatisticsRealtime(long currentDownloadSpeed, long currentUploadSpeed) {
        try {
            TorStatistics stats = new TorStatistics();
            TorStatus status = torStatus.getValue();

            stats.sessionStartTime = sessionStartTime;
            stats.bytesReceived = totalBytesReceived.get();
            stats.bytesSent = totalBytesSent.get();
            stats.peakDownloadSpeed = peakDownloadSpeed.get();
            stats.peakUploadSpeed = peakUploadSpeed.get();
            stats.currentDownloadSpeed = currentDownloadSpeed;
            stats.currentUploadSpeed = currentUploadSpeed;

            long samples = bandwidthSampleCount.get();
            if (samples > 0) {
                stats.averageDownloadSpeed = totalDownloadSpeedSum.get() / samples;
                stats.averageUploadSpeed = totalUploadSpeedSum.get() / samples;
            }

            if (status != null && status.isConnected && connectionStartTime > 0) {
                List<TorCircuit> currentCircuits = circuits.getValue();
                stats.circuitsBuilt = currentCircuits != null ? currentCircuits.size() : 0;
                stats.circuitsFailed = 0;
                stats.uptimeSeconds = (System.currentTimeMillis() - connectionStartTime) / 1000;
                stats.connectedSince = connectionStartTime;
                stats.currentExitIp = "Hidden";
            } else {
                stats.uptimeSeconds = 0;
            }

            mainHandler.post(() -> statistics.setValue(stats));
        } catch (Exception e) {
        }
    }

    public LiveData<TorStatus> getTorStatus() {
        return torStatus;
    }

    public LiveData<List<TorCircuit>> getCircuits() {
        return circuits;
    }

    public LiveData<TorStatistics> getStatistics() {
        return statistics;
    }

    public LiveData<BandwidthUpdate> getBandwidthUpdate() {
        return bandwidthUpdate;
    }

    public void resetStatistics() {
        totalBytesReceived.set(0);
        totalBytesSent.set(0);
        sessionStartTime = System.currentTimeMillis();
        peakDownloadSpeed.set(0);
        peakUploadSpeed.set(0);
        bandwidthSampleCount.set(0);
        totalDownloadSpeedSum.set(0);
        totalUploadSpeedSum.set(0);
        lastRxBytes = 0;
        lastTxBytes = 0;
        lastBandwidthCheck = 0;
        mainHandler.post(() -> {
            bandwidthUpdate.setValue(new BandwidthUpdate(0, 0, 0, 0));
            statistics.setValue(new TorStatistics());
        });
    }

    public static class TorStatus {
        public final boolean isConnected;
        public final String statusMessage;
        public final int bootstrapProgress;

        public TorStatus(boolean isConnected, String statusMessage, int bootstrapProgress) {
            this.isConnected = isConnected;
            this.statusMessage = statusMessage;
            this.bootstrapProgress = bootstrapProgress;
        }
    }

    public static class TorCircuit {
        public final int id;
        public final String status;
        public final List<TorNode> nodes;
        public final long createdAt;

        public TorCircuit(int id, String status, List<TorNode> nodes, long createdAt) {
            this.id = id;
            this.status = status;
            this.nodes = nodes;
            this.createdAt = createdAt;
        }
    }

    public static class TorNode {
        public final String type;
        public final String country;
        public final String ip;

        public TorNode(String type, String country, String ip) {
            this.type = type;
            this.country = country;
            this.ip = ip;
        }
    }

    public static class TorStatistics {
        public long bytesReceived = 0;
        public long bytesSent = 0;
        public int circuitsBuilt = 0;
        public int circuitsFailed = 0;
        public long uptimeSeconds = 0;
        public long connectedSince = System.currentTimeMillis();
        public String currentExitIp = "Unknown";
        public long sessionStartTime = 0;
        public long peakDownloadSpeed = 0;
        public long peakUploadSpeed = 0;
        public long averageDownloadSpeed = 0;
        public long averageUploadSpeed = 0;
        public long currentDownloadSpeed = 0;
        public long currentUploadSpeed = 0;
    }

    public void cleanup() {
        stopMonitoring();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private static class Arrays {
        @SafeVarargs
        public static <T> List<T> asList(T... items) {
            List<T> list = new ArrayList<>();
            for (T item : items) {
                list.add(item);
            }
            return list;
        }
    }

    public static class BandwidthUpdate {
        public final long downloadSpeed;
        public final long uploadSpeed;
        public final long totalDownload;
        public final long totalUpload;

        public BandwidthUpdate(long downloadSpeed, long uploadSpeed,
                               long totalDownload, long totalUpload) {
            this.downloadSpeed = downloadSpeed;
            this.uploadSpeed = uploadSpeed;
            this.totalDownload = totalDownload;
            this.totalUpload = totalUpload;
        }
    }
}