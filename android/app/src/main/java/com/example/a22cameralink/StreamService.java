package com.example.a22cameralink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StreamService extends Service {
    public static final String EXTRA_GAS_URL = "gas_url";
    public static final String EXTRA_ROOM = "room";
    private static final String CHANNEL_ID = "a22_stream_channel";
    private static final int NOTIFICATION_ID = 7201;
    private static final long POLL_INTERVAL_MS = 1400L;

    private String gasUrl;
    private String room;
    private String turnConfig = "[]";
    private long lastMessageId = 0;
    private boolean running = false;
    private boolean remoteDescriptionReady = false;

    private HandlerThread workerThread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private SurfaceTextureHelper surfaceTextureHelper;
    private CameraVideoCapturer cameraCapturer;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private final List<IceCandidate> pendingCandidates = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        gasUrl = intent.getStringExtra(EXTRA_GAS_URL);
        room = intent.getStringExtra(EXTRA_ROOM);
        if (gasUrl == null || room == null || !room.matches("[0-9]{12}")) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!running) {
            running = true;
            acquireWakeLock();
            workerThread = new HandlerThread("A22WebRtcWorker");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
            worker.post(() -> {
                try {
                    initializeMedia();
                    pollSignals();
                } catch (Exception error) {
                    stopWithError("初期化失敗: " + error.getMessage());
                }
            });
        }
        return START_NOT_STICKY;
    }

    private void acquireWakeLock() {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager != null) {
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "A22CameraLink:Streaming");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
            // Streaming can still run without a wake lock.
        }
    }

    private void initializeMedia() throws Exception {
        PeerConnectionFactory.InitializationOptions options =
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("a22_audio", audioSource);
        audioTrack.setEnabled(true);

        videoSource = factory.createVideoSource(false);
        videoTrack = factory.createVideoTrack("a22_video", videoSource);
        videoTrack.setEnabled(true);

        cameraCapturer = createCameraCapturer();
        if (cameraCapturer == null) throw new IllegalStateException("利用できるカメラがありません");
        surfaceTextureHelper = SurfaceTextureHelper.create("A22CameraCapture", eglBase.getEglBaseContext());
        cameraCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
        cameraCapturer.startCapture(640, 480, 20);
        updateNotification("カメラ・マイク使用中／iPadからの接続待ち");
    }

    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(this)
                ? new Camera2Enumerator(this) : new Camera1Enumerator(true);
        String selected = null;
        for (String name : enumerator.getDeviceNames()) {
            if (!enumerator.isFrontFacing(name)) {
                selected = name;
                break;
            }
        }
        if (selected == null) {
            String[] names = enumerator.getDeviceNames();
            if (names.length > 0) selected = names[0];
        }
        if (selected == null) return null;
        return enumerator.createCapturer(selected, new CameraVideoCapturer.CameraEventsHandler() {
            @Override public void onCameraError(String errorDescription) { updateNotification("カメラエラー: " + errorDescription); }
            @Override public void onCameraDisconnected() { updateNotification("カメラが切断されました"); }
            @Override public void onCameraFreezed(String errorDescription) { updateNotification("カメラ映像が停止しました"); }
            @Override public void onCameraOpening(String cameraName) { updateNotification("カメラを起動中"); }
            @Override public void onFirstFrameAvailable() { updateNotification("カメラ・マイク使用中／接続待ち"); }
            @Override public void onCameraClosed() { }
        });
    }

    private void pollSignals() {
        if (!running || worker == null) return;
        try {
            String query = "?action=poll&room=" + URLEncoder.encode(room, "UTF-8")
                    + "&after=" + lastMessageId + "&from=android";
            JSONObject response = new JSONObject(httpGet(gasUrl + query));
            JSONArray messages = response.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message != null) handleSignal(message);
                }
            }
            lastMessageId = Math.max(lastMessageId, response.optLong("next", lastMessageId));
        } catch (Exception error) {
            updateNotification("シグナリング再接続中");
        }
        if (running && worker != null) worker.postDelayed(this::pollSignals, POLL_INTERVAL_MS);
    }

    private void handleSignal(JSONObject message) {
        String type = message.optString("type", "");
        Object data = message.opt("data");
        if ("iceconfig".equals(type)) {
            turnConfig = data == null ? "[]" : String.valueOf(data);
            return;
        }
        if ("offer".equals(type)) {
            try {
                JSONObject sdpJson = data instanceof JSONObject ? (JSONObject) data : new JSONObject(String.valueOf(data));
                createPeerConnection();
                SessionDescription offer = new SessionDescription(
                        SessionDescription.Type.OFFER, sdpJson.getString("sdp"));
                peerConnection.setRemoteDescription(new EmptySdpObserver() {
                    @Override public void onSetSuccess() {
                        remoteDescriptionReady = true;
                        flushPendingCandidates();
                        peerConnection.createAnswer(new EmptySdpObserver() {
                            @Override public void onCreateSuccess(SessionDescription answer) {
                                peerConnection.setLocalDescription(new EmptySdpObserver() {
                                    @Override public void onSetSuccess() {
                                        try {
                                            JSONObject payload = new JSONObject();
                                            payload.put("type", answer.type.canonicalForm());
                                            payload.put("sdp", answer.description);
                                            sendSignal("answer", payload);
                                            updateNotification("iPadへ接続応答を送信しました");
                                        } catch (Exception ignored) { }
                                    }
                                }, answer);
                            }
                        }, new MediaConstraints());
                    }
                    @Override public void onSetFailure(String error) { updateNotification("Offer設定失敗: " + error); }
                }, offer);
            } catch (Exception error) {
                updateNotification("Offer処理失敗: " + error.getMessage());
            }
            return;
        }
        if ("candidate".equals(type)) {
            try {
                JSONObject candidateJson = data instanceof JSONObject ? (JSONObject) data : new JSONObject(String.valueOf(data));
                IceCandidate candidate = new IceCandidate(
                        candidateJson.optString("sdpMid", "0"),
                        candidateJson.optInt("sdpMLineIndex", 0),
                        candidateJson.getString("candidate"));
                if (remoteDescriptionReady && peerConnection != null) peerConnection.addIceCandidate(candidate);
                else pendingCandidates.add(candidate);
            } catch (Exception ignored) { }
        } else if ("hangup".equals(type)) {
            closePeerConnection();
            updateNotification("iPadから切断されました／待機中");
        }
    }

    private void createPeerConnection() throws Exception {
        closePeerConnection();
        List<PeerConnection.IceServer> servers = buildIceServers();
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(servers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peerConnection = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    updateNotification("✅ iPadと接続中／カメラ・マイク送信中");
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    updateNotification("ICE接続失敗。TURN設定を確認してください");
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    updateNotification("接続が一時切断されました");
                }
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
            @Override public void onIceCandidate(IceCandidate candidate) {
                try {
                    JSONObject value = new JSONObject();
                    value.put("sdpMid", candidate.sdpMid);
                    value.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    value.put("candidate", candidate.sdp);
                    sendSignal("candidate", value);
                } catch (Exception ignored) { }
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(org.webrtc.MediaStream stream) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
            @Override public void onDataChannel(org.webrtc.DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }
        });
        if (peerConnection == null) throw new IllegalStateException("PeerConnectionを作成できません");
        peerConnection.addTrack(videoTrack, Collections.singletonList("a22_stream"));
        peerConnection.addTrack(audioTrack, Collections.singletonList("a22_stream"));
    }

    private List<PeerConnection.IceServer> buildIceServers() {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        result.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        result.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        result.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer());
        try {
            JSONArray servers = new JSONArray(turnConfig);
            for (int i = 0; i < servers.length(); i++) {
                JSONObject item = servers.optJSONObject(i);
                if (item == null) continue;
                Object urlsValue = item.opt("urls");
                List<String> urls = new ArrayList<>();
                if (urlsValue instanceof JSONArray) {
                    JSONArray array = (JSONArray) urlsValue;
                    for (int j = 0; j < array.length(); j++) urls.add(array.getString(j));
                } else if (urlsValue != null) {
                    urls.add(String.valueOf(urlsValue));
                }
                for (String uri : urls) {
                    PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(uri);
                    String username = item.optString("username", "");
                    String credential = item.optString("credential", "");
                    if (!username.isEmpty()) builder.setUsername(username);
                    if (!credential.isEmpty()) builder.setPassword(credential);
                    result.add(builder.createIceServer());
                }
            }
        } catch (Exception ignored) {
            // Continue with STUN servers if optional TURN JSON is invalid.
        }
        return result;
    }

    private void flushPendingCandidates() {
        if (peerConnection == null) return;
        for (IceCandidate candidate : pendingCandidates) peerConnection.addIceCandidate(candidate);
        pendingCandidates.clear();
    }

    private void sendSignal(String type, Object data) {
        if (worker == null || !running) return;
        worker.post(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("room", room);
                payload.put("from", "android");
                payload.put("type", type);
                payload.put("data", data);
                httpPost(gasUrl, payload.toString());
            } catch (Exception ignored) { }
        });
    }

    private String httpGet(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(true);
        try {
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            reader.close();
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    private void httpPost(String address, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
        try {
            OutputStream output = connection.getOutputStream();
            output.write(body.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input != null) input.close();
        } finally {
            connection.disconnect();
        }
    }

    private void closePeerConnection() {
        remoteDescriptionReady = false;
        pendingCandidates.clear();
        if (peerConnection != null) {
            try { peerConnection.close(); } catch (Exception ignored) { }
            try { peerConnection.dispose(); } catch (Exception ignored) { }
            peerConnection = null;
        }
    }

    private void stopWithError(String message) {
        updateNotification(message);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "カメラ・マイク配信", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("配信中であることを表示し、停止操作を提供します");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, StreamService.class);
        stopIntent.setAction("STOP");
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPending = PendingIntent.getService(this, 7202, stopIntent, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("A22 Camera Link：配信中")
                .setContentText("カメラ・マイクを使用中。タップしてアプリを開けます")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "配信停止", stopPending).build())
                .build();
    }

    private void updateNotification(String text) {
        if (!running) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent stopIntent = new Intent(this, StreamService.class);
        stopIntent.setAction("STOP");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPending = PendingIntent.getService(this, 7202, stopIntent, flags);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("A22 Camera Link：配信中")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "配信停止", stopPending).build())
                .build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        closePeerConnection();

        if (cameraCapturer != null) {
            try { cameraCapturer.stopCapture(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try { cameraCapturer.dispose(); } catch (Exception ignored) { }
            cameraCapturer = null;
        }
        if (surfaceTextureHelper != null) { surfaceTextureHelper.dispose(); surfaceTextureHelper = null; }
        if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
        if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (workerThread != null) { workerThread.quitSafely(); workerThread = null; }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private abstract static class EmptySdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }
}