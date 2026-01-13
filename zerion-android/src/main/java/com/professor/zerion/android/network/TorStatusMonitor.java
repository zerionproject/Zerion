package com.professor.zerion.android.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.briarproject.bramble.api.plugin.TorSocksPort;
import org.briarproject.nullsafety.NotNullByDefault;

@Singleton
@NotNullByDefault
public class TorStatusMonitor {

    private static final long CHECK_INTERVAL = 5000;
    private static final long BANDWIDTH_CHECK_INTERVAL = 1000; // 1 second for smooth graph

    private final Context context;
    private final int torSocksPort;
    private final ScheduledExecutorService executor;
    private final Handler mainHandler;

    private final MutableLiveData<TorStatus> torStatus = new MutableLiveData<>();
    private final MutableLiveData<List<TorCircuit>> circuits = new MutableLiveData<>();
    private final MutableLiveData<TorStatistics> statistics = new MutableLiveData<>();
    private final MutableLiveData<BandwidthUpdate> bandwidthUpdate = new MutableLiveData<>();

    private volatile boolean isMonitoring = false;

    private long totalBytesReceived = 0;
    private long totalBytesSent = 0;
    private long monitoringStartTime = 0;
    private long connectionStartTime = 0;
    private boolean wasConnected = false;
    private long sessionStartTime = 0;
    private long peakDownloadSpeed = 0;
    private long peakUploadSpeed = 0;
    private long bandwidthSampleCount = 0;
    private long totalDownloadSpeedSum = 0;
    private long totalUploadSpeedSum = 0;

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
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", torSocksPort), 1000);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean testTorConnection() {
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", torSocksPort));

            URL url = new URL("https://check.torproject.org/api/ip");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                String response = reader.readLine();
                reader.close();
                return response != null && response.contains("\"IsTor\":true");
            }
        } catch (Exception e) {
        }
        return false;
    }

    private boolean isTorProcessRunning() {
        try {
            Process process = Runtime.getRuntime().exec("ps");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("tor") || line.contains("Tor")) {
                    return true;
                }
            }
            reader.close();
        } catch (Exception e) {
        }
        return false;
    }

    private int getBootstrapProgress() {
        try {
            File torLog = new File(context.getFilesDir(), "tor/tor.log");
            if (torLog.exists()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(torLog)));
                String line;
                int maxProgress = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Bootstrapped")) {
                        int start = line.indexOf("Bootstrapped ") + 13;
                        int end = line.indexOf("%", start);
                        if (start > 0 && end > start) {
                            try {
                                int progress = Integer.parseInt(line.substring(start, end).trim());
                                maxProgress = Math.max(maxProgress, progress);
                            } catch (NumberFormatException e) {
                            }
                        }
                    }
                }
                reader.close();
                return maxProgress;
            }
        } catch (Exception e) {
        }
        return 0;
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
                totalBytesReceived += downloadSpeed;
                totalBytesSent += uploadSpeed;
            }

            peakDownloadSpeed = Math.max(peakDownloadSpeed, downloadSpeed);
            peakUploadSpeed = Math.max(peakUploadSpeed, uploadSpeed);
            bandwidthSampleCount++;
            totalDownloadSpeedSum += downloadSpeed;
            totalUploadSpeedSum += uploadSpeed;

            final BandwidthUpdate update = new BandwidthUpdate(
                    downloadSpeed,
                    uploadSpeed,
                    totalBytesReceived,
                    totalBytesSent
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
            stats.bytesReceived = totalBytesReceived;
            stats.bytesSent = totalBytesSent;
            stats.peakDownloadSpeed = peakDownloadSpeed;
            stats.peakUploadSpeed = peakUploadSpeed;
            stats.currentDownloadSpeed = currentDownloadSpeed;
            stats.currentUploadSpeed = currentUploadSpeed;

            if (bandwidthSampleCount > 0) {
                stats.averageDownloadSpeed = totalDownloadSpeedSum / bandwidthSampleCount;
                stats.averageUploadSpeed = totalUploadSpeedSum / bandwidthSampleCount;
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

    private String getCurrentExitIp() {
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", torSocksPort));

            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();

            return ip != null ? ip : "Unknown";
        } catch (Exception e) {
            return "Unknown";
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
        totalBytesReceived = 0;
        totalBytesSent = 0;
        sessionStartTime = System.currentTimeMillis();
        peakDownloadSpeed = 0;
        peakUploadSpeed = 0;
        bandwidthSampleCount = 0;
        totalDownloadSpeedSum = 0;
        totalUploadSpeedSum = 0;
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

    /**
     * Represents a bandwidth update for the real-time graph.
     */
    public static class BandwidthUpdate {
        public final long downloadSpeed;  // bytes per second
        public final long uploadSpeed;    // bytes per second
        public final long totalDownload;  // total bytes downloaded
        public final long totalUpload;    // total bytes uploaded

        public BandwidthUpdate(long downloadSpeed, long uploadSpeed,
                               long totalDownload, long totalUpload) {
            this.downloadSpeed = downloadSpeed;
            this.uploadSpeed = uploadSpeed;
            this.totalDownload = totalDownload;
            this.totalUpload = totalUpload;
        }
    }
}