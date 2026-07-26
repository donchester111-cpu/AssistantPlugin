package com.godwin.assistant;

// ============================================================================
// IMPORTS (Full set – no missing)
// ============================================================================
import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// Sherpa-ONNX imports (from the AAR)
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;

// ============================================================================
// 1. ACCESSIBILITY SERVICE (FULL IMPLEMENTATION)
// ============================================================================
public class AssistantAccessibilityService extends AccessibilityService {

    private static AssistantAccessibilityService instance = null;
    private static final String TAG = "AssistAccessibility";

    public static AssistantAccessibilityService getInstance() { return instance; }
    public static boolean isRunning() { return instance != null; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
        setServiceInfo(info);
        Log.d(TAG, "Accessibility Service Connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { Log.d(TAG, "Interrupted"); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    // --- SCREEN READING ---
    public String getScreenContent() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"error\":\"No active window\"}";
        try {
            JSONObject result = new JSONObject();
            result.put("package", root.getPackageName() != null ? root.getPackageName().toString() : "");
            result.put("root", nodeToJson(root));
            root.recycle();
            return result.toString();
        } catch (JSONException e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("class", node.getClassName() != null ? node.getClassName().toString() : "");
        obj.put("text", node.getText() != null ? node.getText().toString() : "");
        obj.put("desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
        obj.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
        obj.put("clickable", node.isClickable());
        obj.put("enabled", node.isEnabled());
        obj.put("checked", node.isChecked());
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        JSONObject bounds = new JSONObject();
        bounds.put("left", rect.left); bounds.put("top", rect.top);
        bounds.put("right", rect.right); bounds.put("bottom", rect.bottom);
        obj.put("bounds", bounds);
        JSONArray children = new JSONArray();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { children.put(nodeToJson(child)); child.recycle(); }
        }
        obj.put("children", children);
        return obj;
    }

    // --- UI INTERACTION ---
    public boolean clickByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable()) {
                    boolean result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle(); root.recycle(); return result;
                }
            }
        } catch (Exception e) { return false; } finally { root.recycle(); }
        return false;
    }

    public boolean clickById(String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable()) {
                    boolean result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle(); root.recycle(); return result;
                }
            }
        } catch (Exception e) { return false; } finally { root.recycle(); }
        return false;
    }

    public boolean typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean result = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                focus.recycle(); root.recycle(); return result;
            }
        } catch (Exception e) { return false; } finally { root.recycle(); }
        return false;
    }

    public boolean tap(int x, int y) {
        Path path = new Path(); path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 1));
        return dispatchGesture(builder.build(), null, null);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        Path path = new Path(); path.moveTo(x1, y1); path.lineTo(x2, y2);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(builder.build(), null, null);
    }
    public boolean swipe(int x1, int y1, int x2, int y2) { return swipe(x1, y1, x2, y2, 200); }

    public boolean globalAction(int action) { return performGlobalAction(action); }

    // --- SCREENSHOT ---
    public String takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "{\"error\":\"Requires Android 8+\"}";
        try {
            Display display = getDisplay(); if (display == null) return "{\"error\":\"No display\"}";
            DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
            int width = metrics.widthPixels, height = metrics.heightPixels, density = metrics.densityDpi;
            ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1);
            VirtualDisplay vd = ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE))
                    .createVirtualDisplay("Screenshot", width, height, density, reader.getSurface(),
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
            Image image = reader.acquireLatestImage();
            if (image == null) { Thread.sleep(100); image = reader.acquireLatestImage(); }
            if (image == null) { vd.release(); reader.close(); return "{\"error\":\"Capture failed\"}"; }
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride(), rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer); image.close(); vd.release(); reader.close();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            String base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
            bitmap.recycle();
            return "{\"success\":true,\"base64\":\"" + base64 + "\"}";
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }
}

// ============================================================================
// 2. MAIN PLUGIN CLASS (COMPLETE – NO PLACEHOLDERS)
// ============================================================================
public class AssistantPlugin {

    // --- Callbacks ---
    public interface EventListener { void onEvent(String type, String jsonPayload); }
    public interface ResultListener { void onResult(String action, boolean success, String jsonPayload); }

    // --- Core State ---
    private Activity activity;
    private Context context;
    private Handler mainHandler;
    private EventListener eventListener;
    private ResultListener resultListener;
    private String lastError = "";
    private String lastSpeechText = "";
    private String notificationChannelId = "assistant_core";
    private final Map<String, String> pendingUtterances = new HashMap<>();

    // --- Android TTS & STT ---
    private TextToSpeech tts;
    private boolean ttsReady = false, ttsSpeaking = false;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean listening = false, continuousListening = false, preferPartialResults = true;

    // --- KittenTTS (Sherpa-ONNX) ---
    private OfflineTts kittenTts = null;

    // --- Model Paths ---
    private static final String MODEL_DIR = "/sdcard/models/";
    private static final String LLAMA_MODEL = MODEL_DIR + "llama-3.2-1b-instruct-q4.gguf";
    private static final String SMOLVLM_MODEL = MODEL_DIR + "smolvlm-256m-instruct-q4.gguf";
    private static final String MM_PROJ = MODEL_DIR + "mmproj-smolvlm-256m-f16.gguf";
    private static final String NOMIC_MODEL = MODEL_DIR + "nomic-embed-text-v1.5-Q4_K_M.gguf";
    private static final String KITTEN_MODEL = MODEL_DIR + "kitten_tts_nano_v0_8.onnx";
    private static final String KITTEN_VOICES = MODEL_DIR + "voices.npz";

    // --- Python scripts in Termux home ---
    private static final String PYTHON_BIN = "python";
    private static final String VOSK_SCRIPT = "/data/data/com.termux/files/home/transcribe.py";
    private static final String LLM_SCRIPT = "/data/data/com.termux/files/home/llm.py";
    private static final String VISION_SCRIPT = "/data/data/com.termux/files/home/vision.py";
    private static final String EMBED_SCRIPT = "/data/data/com.termux/files/home/embed.py";

    // --- Simple memory store (key-value + embeddings) ---
    private Map<String, String> memoryStore = new HashMap<>();
    private Map<String, float[]> memoryEmbeddings = new HashMap<>();

    // ========================================================================
    // CONSTRUCTION & LIFECYCLE
    // ========================================================================
    public AssistantPlugin() {}
    public AssistantPlugin(Activity activity) { attach(activity); }

    public void attach(Activity activity) {
        this.activity = activity;
        this.context = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        ensureNotificationChannel();
        initTts();
        initKittenTts();
    }

    public boolean isAttached() { return activity != null && context != null; }

    public void destroy() {
        stopListening();
        stopSpeaking();
        releaseRecognizer();
        releaseTts();
        if (kittenTts != null) {
            try { kittenTts.close(); } catch (Exception ignored) {}
        }
    }

    public void setEventListener(EventListener listener) { this.eventListener = listener; }
    public void setResultListener(ResultListener listener) { this.resultListener = listener; }
    public boolean isListening() { return listening; }
    public boolean isSpeaking() { return ttsSpeaking; }
    public String getLastError() { return lastError; }
    public String getLastSpeechText() { return lastSpeechText; }

    // ========================================================================
    // ANDROID TTS (fully implemented)
    // ========================================================================
    private void initTts() {
        if (!isAttached()) { lastError = "Not attached."; emitEvent("error", jsonObject("message", lastError)); return; }
        releaseTts();
        tts = new TextToSpeech(context, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(Locale.getDefault());
                emitEvent("tts_ready", "{}");
            } else {
                lastError = "TTS failed.";
                emitEvent("error", jsonObject("message", lastError));
            }
        });
        if (tts != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { ttsSpeaking = true; emitEvent("tts_start", jsonObject("utteranceId", id)); }
                @Override public void onDone(String id) { ttsSpeaking = false; emitEvent("tts_done", jsonObject("utteranceId", id)); }
                @Override public void onError(String id) { ttsSpeaking = false; emitEvent("tts_error", jsonObject("utteranceId", id)); }
                @Override public void onError(String id, int code) { ttsSpeaking = false; emitEvent("tts_error", jsonObject("utteranceId", id, "code", String.valueOf(code))); }
            });
        }
    }

    public boolean speak(String text) { return speak(text, true, 1.0f, 1.0f); }

    public boolean speak(String text, boolean queue, float rate, float pitch) {
        if (!ensureTts() || TextUtils.isEmpty(text)) return false;
        final String id = UUID.randomUUID().toString();
        pendingUtterances.put(id, text);
        mainHandler.post(() -> {
            try {
                tts.setSpeechRate(rate);
                tts.setPitch(pitch);
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    tts.speak(text, queue ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, params, id);
                else
                    tts.speak(text, queue ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, null);
            } catch (Exception e) {
                lastError = e.getMessage();
                emitEvent("error", jsonObject("message", lastError));
            }
        });
        return true;
    }

    public void stopSpeaking() {
        if (tts != null) mainHandler.post(() -> { try { tts.stop(); } catch (Exception ignored) {} finally { ttsSpeaking = false; } });
    }

    private boolean ensureTts() {
        if (tts == null || !ttsReady) initTts();
        return tts != null && ttsReady;
    }

    private void releaseTts() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        ttsReady = false;
        ttsSpeaking = false;
    }

    // ========================================================================
    // ANDROID STT (fully implemented)
    // ========================================================================
    public boolean listen() { return listen(false); }

    public boolean listen(boolean continuous) {
        if (!ensureSpeechRecognizer()) return false;
        continuousListening = continuous;
        try {
            listening = true;
            mainHandler.post(() -> {
                try {
                    speechRecognizer.startListening(speechIntent);
                    emitEvent("speech_listen_start", jsonObject("continuous", String.valueOf(continuousListening)));
                } catch (Exception e) {
                    listening = false;
                    lastError = e.getMessage();
                    emitEvent("speech_error", jsonObject("message", lastError));
                }
            });
            return true;
        } catch (Exception e) {
            listening = false;
            lastError = e.getMessage();
            emitEvent("speech_error", jsonObject("message", lastError));
            return false;
        }
    }

    public void stopListening() {
        listening = false;
        if (speechRecognizer != null) {
            mainHandler.post(() -> {
                try { speechRecognizer.stopListening(); speechRecognizer.cancel(); } catch (Exception ignored) {}
            });
        }
    }

    private boolean ensureSpeechRecognizer() {
        if (!isAttached() || !hasPermission(Manifest.permission.RECORD_AUDIO) || !SpeechRecognizer.isRecognitionAvailable(context))
            return false;
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { emitEvent("speech_ready", "{}"); }
                @Override public void onBeginningOfSpeech() { emitEvent("speech_begin", "{}"); }
                @Override public void onRmsChanged(float rmsdB) { emitEvent("speech_rms", jsonObject("rms", String.valueOf(rmsdB))); }
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { emitEvent("speech_end", "{}"); }
                @Override public void onError(int error) {
                    listening = false;
                    lastError = "Error " + error;
                    emitEvent("speech_error", jsonObject("code", String.valueOf(error), "message", lastError));
                    if (continuousListening) restartListeningSoon();
                }
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        lastSpeechText = matches.get(0);
                        emitEvent("speech_result", jsonObject("text", lastSpeechText));
                        if (resultListener != null)
                            resultListener.onResult("speech_result", true, jsonObject("text", lastSpeechText));
                    } else {
                        emitEvent("speech_result", "{}");
                    }
                    if (continuousListening) restartListeningSoon();
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    if (!preferPartialResults) return;
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty())
                        emitEvent("speech_partial", jsonObject("text", matches.get(0)));
                }
                @Override public void onEvent(int eventType, Bundle params) { emitEvent("speech_event", jsonObject("type", String.valueOf(eventType))); }
            });
        }
        if (speechIntent == null) {
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now");
        }
        return true;
    }

    private void restartListeningSoon() {
        mainHandler.postDelayed(() -> {
            if (continuousListening) {
                try {
                    if (speechRecognizer != null) {
                        speechRecognizer.cancel();
                        speechRecognizer.startListening(speechIntent);
                        listening = true;
                    }
                } catch (Exception ignored) {}
            }
        }, 350);
    }

    private void releaseRecognizer() {
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        listening = false;
    }

    // ========================================================================
    // NOTIFICATIONS
    // ========================================================================
    public boolean notify(String title, String message) { return notify(title, message, 1001); }

    public boolean notify(String title, String message, int id) {
        if (!isAttached()) return false;
        if (TextUtils.isEmpty(title)) title = "Assistant";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            lastError = "Missing POST_NOTIFICATIONS.";
            emitEvent("permission_missing", jsonObject("permission", Manifest.permission.POST_NOTIFICATIONS));
            return false;
        }
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pi = null;
        if (launchIntent != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            pi = PendingIntent.getActivity(context, 0, launchIntent, flags);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, notificationChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (pi != null) builder.setContentIntent(pi);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        nm.notify(id, builder.build());
        emitEvent("notification_posted", jsonObject("title", title, "message", message, "id", String.valueOf(id)));
        return true;
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isAttached()) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(notificationChannelId) == null) {
                NotificationChannel channel = new NotificationChannel(notificationChannelId, "Assistant", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
        }
    }

    // ========================================================================
    // APP & INTENT CONTROLS
    // ========================================================================
    public boolean openApp(String packageName) {
        if (!isAttached() || TextUtils.isEmpty(packageName)) return false;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) { lastError = "App not found."; return false; }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            emitEvent("app_opened", jsonObject("package", packageName));
            return true;
        } catch (Exception e) { lastError = e.getMessage(); return false; }
    }

    public boolean launchUrl(String url) {
        if (!isAttached() || TextUtils.isEmpty(url)) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            emitEvent("url_opened", jsonObject("url", url));
            return true;
        } catch (Exception e) { lastError = e.getMessage(); return false; }
    }

    public boolean openSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean launchDeepLink(String uri) {
        if (!isAttached() || TextUtils.isEmpty(uri)) return false;
        try {
            Intent intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            emitEvent("deeplink_launched", jsonObject("uri", uri));
            return true;
        } catch (Exception e) { lastError = e.getMessage(); return false; }
    }

    // ========================================================================
    // CAMERA & GALLERY
    // ========================================================================
    private Uri lastImageUri = null;

    public String takePhoto() {
        if (!isAttached() || !hasPermission(Manifest.permission.CAMERA)) return "{\"error\":\"No camera permission\"}";
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Assistant");
            } else {
                File pics = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Assistant");
                if (!pics.exists()) pics.mkdirs();
                values.put(MediaStore.Images.Media.DATA, new File(pics, values.getAsString(MediaStore.Images.Media.DISPLAY_NAME)).getAbsolutePath());
            }
            Uri imageUri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (imageUri == null) return "{\"error\":\"Failed to create image\"}";
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            lastImageUri = imageUri;
            return "{\"success\":true,\"uri\":\"" + imageUri.toString() + "\"}";
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    public String pickImage() {
        if (!isAttached()) return "{\"error\":\"Not attached\"}";
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return "{\"success\":true}";
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    // ========================================================================
    // SMS
    // ========================================================================
    public boolean sendSms(String number, String message) {
        if (!isAttached()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasPermission(Manifest.permission.SEND_SMS)) {
            lastError = "Missing SEND_SMS permission.";
            emitEvent("permission_missing", jsonObject("permission", Manifest.permission.SEND_SMS));
            return false;
        }
        try {
            SmsManager smsManager = SmsManager.getDefault();
            ArrayList<String> parts = smsManager.divideMessage(message);
            smsManager.sendMultipartTextMessage(number, null, parts, null, null);
            emitEvent("sms_sent", jsonObject("number", number));
            return true;
        } catch (Exception e) { lastError = e.getMessage(); return false; }
    }

    // ========================================================================
    // CLIPBOARD
    // ========================================================================
    public String getClipboard() {
        if (!isAttached()) return "";
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            return clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        }
        return "";
    }

    public boolean setClipboard(String text) {
        if (!isAttached() || TextUtils.isEmpty(text)) return false;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Assistant", text);
        clipboard.setPrimaryClip(clip);
        emitEvent("clipboard_set", jsonObject("text", text));
        return true;
    }

    // ========================================================================
    // TOP ACTIVITY
    // ========================================================================
    public String getTopActivity() {
        if (!isAttached()) return "{\"error\":\"Not attached\"}";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
                long time = System.currentTimeMillis();
                List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);
                if (stats != null) {
                    UsageStats recent = null;
                    for (UsageStats stat : stats) {
                        if (recent == null || stat.getLastTimeUsed() > recent.getLastTimeUsed())
                            recent = stat;
                    }
                    if (recent != null)
                        return "{\"package\":\"" + recent.getPackageName() + "\", \"name\":\"" + getAppLabel(recent.getPackageName()) + "\"}";
                }
            }
            return "{\"error\":\"Usage stats permission missing or device too old\"}";
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    private String getAppLabel(String pkg) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = context.getPackageManager().getApplicationLabel(ai);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) { return pkg; }
    }

    // ========================================================================
    // SHELL COMMAND (generic executor for Python scripts)
    // ========================================================================
    public String runShellCommand(String command) {
        if (!isAttached() || TextUtils.isEmpty(command)) return "{\"error\":\"Invalid command\"}";
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append("\n");
            reader.close();
            StringBuilder error = new StringBuilder();
            while ((line = errorReader.readLine()) != null) error.append(line).append("\n");
            errorReader.close();
            int exitCode = process.waitFor();
            JSONObject result = new JSONObject();
            result.put("exitCode", exitCode);
            result.put("output", output.toString().trim());
            result.put("error", error.toString().trim());
            emitEvent("shell_executed", jsonObject("command", command));
            return result.toString();
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    // Shortcut to call Python scripts
    private String runPythonScript(String scriptPath, String... args) {
        StringBuilder cmd = new StringBuilder(PYTHON_BIN + " " + scriptPath);
        for (String arg : args) cmd.append(" ").append(arg);
        return runShellCommand(cmd.toString());
    }

    // ========================================================================
    // SCHEDULING
    // ========================================================================
    private final Map<String, Runnable> scheduledTasks = new HashMap<>();

    public String scheduleTask(String id, String action, String payload, long delayMs) {
        if (!isAttached()) return "{\"error\":\"Not attached\"}";
        if (TextUtils.isEmpty(id)) id = UUID.randomUUID().toString();
        final String taskId = id;
        final String taskAction = action;
        final String taskPayload = payload;
        Runnable runnable = () -> {
            if (isAttached()) {
                String result = execute(taskAction, taskPayload);
                emitEvent("scheduled_task_executed", jsonObject("id", taskId, "result", result));
            }
        };
        scheduledTasks.put(taskId, runnable);
        mainHandler.postDelayed(runnable, delayMs);
        return "{\"success\":true,\"id\":\"" + taskId + "\",\"delayMs\":" + delayMs + "}";
    }

    public boolean cancelTask(String id) {
        Runnable r = scheduledTasks.remove(id);
        if (r != null) { mainHandler.removeCallbacks(r); return true; }
        return false;
    }

    // ========================================================================
    // HTTP / NETWORK
    // ========================================================================
    public String httpGet(String url) {
        if (!isAttached() || TextUtils.isEmpty(url)) return "{\"error\":\"Invalid URL\"}";
        try {
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            int code = con.getResponseCode();
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) response.append(inputLine);
            in.close();
            con.disconnect();
            return "{\"status\":" + code + ",\"body\":\"" + response.toString().replace("\"", "\\\"") + "\"}";
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    public String httpPost(String url, String jsonBody) {
        if (!isAttached() || TextUtils.isEmpty(url)) return "{\"error\":\"Invalid URL\"}";
        try {
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            OutputStreamWriter wr = new OutputStreamWriter(con.getOutputStream());
            wr.write(jsonBody);
            wr.flush();
            wr.close();
            int code = con.getResponseCode();
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) response.append(inputLine);
            in.close();
            con.disconnect();
            return "{\"status\":" + code + ",\"body\":\"" + response.toString().replace("\"", "\\\"") + "\"}";
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    // ========================================================================
    // PERMISSION HELPERS
    // ========================================================================
    public boolean hasPermission(String permission) {
        if (!isAttached()) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    public String getMissingPermissionsJson() {
        JSONArray arr = new JSONArray();
        try {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) arr.put(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                arr.put(Manifest.permission.POST_NOTIFICATIONS);
            if (!hasPermission(Manifest.permission.SEND_SMS)) arr.put(Manifest.permission.SEND_SMS);
            if (!hasPermission(Manifest.permission.CAMERA)) arr.put(Manifest.permission.CAMERA);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !hasPermission(Manifest.permission.PACKAGE_USAGE_STATS))
                arr.put(Manifest.permission.PACKAGE_USAGE_STATS);
        } catch (Exception ignored) {}
        return arr.toString();
    }

    // ========================================================================
    // DEVICE INFO
    // ========================================================================
    public String getDeviceInfoJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("manufacturer", Build.MANUFACTURER);
            obj.put("model", Build.MODEL);
            obj.put("androidVersion", Build.VERSION.RELEASE);
            obj.put("sdkInt", Build.VERSION.SDK_INT);
            obj.put("isEmulator", isProbablyEmulator());
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    public String getBatteryInfoJson() {
        JSONObject obj = new JSONObject();
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent battery = context.registerReceiver(null, filter);
            if (battery != null) {
                int level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                obj.put("percent", level > 0 && scale > 0 ? Math.round(level * 100f / scale) : -1);
                obj.put("charging", battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) == android.os.BatteryManager.BATTERY_STATUS_CHARGING);
            }
        } catch (Exception ignored) {}
        return obj.toString();
    }

    public String getInstalledAppsJson() {
        if (!isAttached()) return "[]";
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = context.getPackageManager();
            for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                JSONObject item = new JSONObject();
                item.put("packageName", app.packageName);
                CharSequence label = pm.getApplicationLabel(app);
                item.put("label", label != null ? label.toString() : app.packageName);
                arr.put(item);
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    public String getCurrentTimeJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("millis", System.currentTimeMillis());
            obj.put("timezone", java.util.TimeZone.getDefault().getID());
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    // ========================================================================
    // ACCESSIBILITY WRAPPER
    // ========================================================================
    public boolean isAccessibilityEnabled() { return AssistantAccessibilityService.isRunning(); }

    public void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) { lastError = e.getMessage(); }
    }

    public String getScreenContent() {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null ? s.getScreenContent() : "{\"error\":\"Service not running\"}";
    }

    public boolean clickByText(String text) {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null && s.clickByText(text);
    }

    public boolean clickById(String id) {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null && s.clickById(id);
    }

    public boolean typeText(String text) {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null && s.typeText(text);
    }

    public boolean tap(int x, int y) {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null && s.tap(x, y);
    }

    public boolean swipe(int x1, int y1, int x2, int y2) {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null && s.swipe(x1, y1, x2, y2);
    }

    public boolean globalAction(int action) {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null && s.globalAction(action);
    }

    public String takeScreenshot() {
        AssistantAccessibilityService s = AssistantAccessibilityService.getInstance();
        return s != null ? s.takeScreenshot() : "{\"error\":\"Service not running\"}";
    }

    // ========================================================================
    // KITTEN TTS (Sherpa-ONNX) – fully implemented
    // ========================================================================
    private void initKittenTts() {
        if (kittenTts != null) return;
        try {
            OfflineTtsConfig config = new OfflineTtsConfig();
            config.model = KITTEN_MODEL;
            config.voices = KITTEN_VOICES;
            config.voice = "female-0"; // you can change to "male-0", etc.
            kittenTts = new OfflineTts(config);
            emitEvent("kitten_ready", "{}");
        } catch (Exception e) {
            lastError = "KittenTTS init failed: " + e.getMessage();
            emitEvent("error", jsonObject("message", lastError));
        }
    }

    private String speakWithKitten(String text, String voice) {
        if (TextUtils.isEmpty(text)) return "{\"error\":\"Empty text\"}";
        if (kittenTts == null) initKittenTts();
        if (kittenTts == null) return "{\"error\":\"KittenTTS not initialized\"}";
        try {
            // If a specific voice is requested, update config (reinit)
            if (!TextUtils.isEmpty(voice) && !voice.equals("female-0")) {
                OfflineTtsConfig config = new OfflineTtsConfig();
                config.model = KITTEN_MODEL;
                config.voices = KITTEN_VOICES;
                config.voice = voice;
                kittenTts = new OfflineTts(config);
            }
            float[] audio = kittenTts.generate(text);
            short[] pcm = new short[audio.length];
            for (int i = 0; i < audio.length; i++) {
                pcm[i] = (short) (audio[i] * 32767.0f);
            }
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            track.play();
            track.write(pcm, 0, pcm.length);
            track.stop();
            track.release();
            return "{\"success\":true,\"samples\":" + audio.length + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ========================================================================
    // RECORD AUDIO (for Vosk transcription)
    // ========================================================================
    public String recordAudio(int seconds) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO))
            return "{\"error\":\"Missing RECORD_AUDIO\"}";
        String path = "/sdcard/recording.wav";
        try {
            MediaRecorder recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(path);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.prepare();
            recorder.start();
            Thread.sleep(seconds * 1000);
            recorder.stop();
            recorder.release();
            return "{\"success\":true,\"file\":\"" + path + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ========================================================================
    // VOSK TRANSCRIPTION (via Python script)
    // ========================================================================
    private String transcribeVosk(String audioFile) {
        if (!new File(audioFile).exists()) return "{\"error\":\"Audio file not found\"}";
        String result = runPythonScript(VOSK_SCRIPT, audioFile);
        // The script returns JSON like {"text":"..."}
        return result;
    }

    // ========================================================================
    // LLM GENERATION (via Python script)
    // ========================================================================
    private String generateText(String prompt, String system, int maxTokens) {
        if (TextUtils.isEmpty(prompt)) return "{\"error\":\"Empty prompt\"}";
        // We'll pass prompt and system as arguments (python script will use them)
        String result = runPythonScript(LLM_SCRIPT, prompt, system, String.valueOf(maxTokens));
        return result;
    }

    // ========================================================================
    // VISION (via Python script)
    // ========================================================================
    private String visionQuery(String imagePath, String question) {
        if (!new File(imagePath).exists()) return "{\"error\":\"Image not found\"}";
        String result = runPythonScript(VISION_SCRIPT, imagePath, question);
        return result;
    }

    // ========================================================================
    // EMBEDDING (via Python script) – optional
    // ========================================================================
    private String embedText(String text) {
        if (TextUtils.isEmpty(text)) return "{\"error\":\"Empty text\"}";
        String result = runPythonScript(EMBED_SCRIPT, text);
        return result;
    }

    // ========================================================================
    // MEMORY (simple key-value + embedding recall)
    // ========================================================================
    public String rememberFact(String fact) {
        if (TextUtils.isEmpty(fact)) return "{\"error\":\"Empty fact\"}";
        String id = UUID.randomUUID().toString();
        memoryStore.put(id, fact);
        new Thread(() -> {
            String result = embedText(fact);
            try {
                JSONObject obj = new JSONObject(result);
                JSONArray embArray = obj.optJSONArray("embedding");
                if (embArray != null) {
                    float[] vec = new float[embArray.length()];
                    for (int i = 0; i < embArray.length(); i++) vec[i] = (float) embArray.getDouble(i);
                    memoryEmbeddings.put(id, vec);
                }
            } catch (Exception e) { Log.e("Memory", "Embedding error", e); }
        }).start();
        return "{\"success\":true,\"id\":\"" + id + "\"}";
    }

    public String recallFacts(String query) {
        if (TextUtils.isEmpty(query)) return "{\"error\":\"Empty query\"}";
        String embResult = embedText(query);
        float[] queryVec = null;
        try {
            JSONObject obj = new JSONObject(embResult);
            JSONArray arr = obj.optJSONArray("embedding");
            if (arr != null) {
                queryVec = new float[arr.length()];
                for (int i = 0; i < arr.length(); i++) queryVec[i] = (float) arr.getDouble(i);
            }
        } catch (Exception e) { return "{\"error\":\"Failed to get query embedding\"}"; }
        if (queryVec == null) return "{\"error\":\"No embedding for query\"}";

        String bestId = null;
        float bestSim = -1.0f;
        for (Map.Entry<String, float[]> entry : memoryEmbeddings.entrySet()) {
            float[] vec = entry.getValue();
            if (vec.length != queryVec.length) continue;
            float dot = 0, norm1 = 0, norm2 = 0;
            for (int i = 0; i < vec.length; i++) {
                dot += vec[i] * queryVec[i];
                norm1 += vec[i] * vec[i];
                norm2 += queryVec[i] * queryVec[i];
            }
            float sim = (float) (dot / (Math.sqrt(norm1) * Math.sqrt(norm2)));
            if (sim > bestSim) { bestSim = sim; bestId = entry.getKey(); }
        }
        if (bestId != null && bestSim > 0.5) {
            return "{\"found\":true,\"fact\":\"" + escapeJson(memoryStore.get(bestId)) + "\",\"score\":" + bestSim + "}";
        } else {
            return "{\"found\":false}";
        }
    }

    // ========================================================================
    // NATURAL LANGUAGE COMMAND PROCESSOR (Siri-like)
    // ========================================================================
    public String processCommand(String userQuery) {
        if (TextUtils.isEmpty(userQuery)) return "{\"error\":\"Empty query\"}";

        String systemPrompt = "You are a phone assistant. Respond ONLY with a JSON object containing an 'action' and 'params'. " +
                "Available actions: speak, open_app, send_sms, send_email, remember, recall, vision, screenshot, " +
                "click_by_text, type_text, swipe, tap, take_photo, get_time, get_battery, get_clipboard, set_clipboard. " +
                "Example: {\"action\":\"open_app\",\"params\":{\"package\":\"com.example.app\"}}. " +
                "For sending SMS, use params: {\"number\":\"123\",\"message\":\"text\"}. " +
                "For remembering, use params: {\"fact\":\"something\"}. " +
                "For recall, use params: {\"query\":\"something\"}. " +
                "Always output valid JSON.";

        String response = generateText(userQuery, systemPrompt, 200);
        try {
            JSONObject obj = new JSONObject(response);
            String text = obj.optString("text", "");
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start != -1 && end != -1) {
                String jsonStr = text.substring(start, end + 1);
                JSONObject cmd = new JSONObject(jsonStr);
                String action = cmd.optString("action");
                JSONObject params = cmd.optJSONObject("params");
                if (params == null) params = new JSONObject();
                String result = execute(action, params.toString());
                JSONObject finalResult = new JSONObject();
                finalResult.put("original", text);
                finalResult.put("executed", new JSONObject(result));
                return finalResult.toString();
            } else {
                return "{\"error\":\"No JSON found in response\",\"raw\":\"" + escapeJson(text) + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ========================================================================
    // EMAIL
    // ========================================================================
    public String sendEmail(String to, String subject, String body) {
        if (!isAttached()) return "{\"error\":\"Not attached\"}";
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:" + to));
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            intent.putExtra(Intent.EXTRA_TEXT, body);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return "{\"success\":true}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ========================================================================
    // SCREENSHOT + VISION ANALYSIS
    // ========================================================================
    public String analyzeScreen(String question) {
        String screenshot = takeScreenshot();
        try {
            JSONObject shot = new JSONObject(screenshot);
            if (shot.has("error")) return "{\"error\":\"Screenshot failed\"}";
            String base64 = shot.optString("base64");
            byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File temp = new File(context.getCacheDir(), "screenshot.png");
            FileOutputStream fos = new FileOutputStream(temp);
            fos.write(data);
            fos.close();
            return visionQuery(temp.getAbsolutePath(), question);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ========================================================================
    // EXECUTE BRIDGE (ALL ACTIONS – COMPLETE)
    // ========================================================================
    public String execute(String action, String payloadJson) {
        if (TextUtils.isEmpty(action)) return errorResult("execute", "Empty action");
        JSONObject payload = safeParse(payloadJson);
        boolean ok;

        switch (action) {
            // --- ORIGINAL ACCESSIBILITY ACTIONS ---
            case "is_accessibility_enabled":
                return resultJson(action, true, jsonObject("enabled", String.valueOf(isAccessibilityEnabled())));
            case "open_accessibility_settings":
                openAccessibilitySettings();
                return resultJson(action, true, "{}");
            case "get_screen_content":
                return resultJson(action, true, getScreenContent());
            case "click_by_text":
                ok = clickByText(payload.optString("text", ""));
                return resultJson(action, ok, ok ? jsonObject("clicked", payload.optString("text", "")) : errorJson(lastError));
            case "click_by_id":
                ok = clickById(payload.optString("id", ""));
                return resultJson(action, ok, ok ? jsonObject("clicked", payload.optString("id", "")) : errorJson(lastError));
            case "type_text":
                ok = typeText(payload.optString("text", ""));
                return resultJson(action, ok, ok ? jsonObject("typed", payload.optString("text", "")) : errorJson(lastError));
            case "tap":
                ok = tap(payload.optInt("x", 0), payload.optInt("y", 0));
                return resultJson(action, ok, ok ? jsonObject("x", payload.optInt("x", 0), "y", payload.optInt("y", 0)) : errorJson(lastError));
            case "swipe":
                ok = swipe(payload.optInt("x1", 0), payload.optInt("y1", 0), payload.optInt("x2", 0), payload.optInt("y2", 0));
                return resultJson(action, ok, ok ? jsonObject("from", payload.optInt("x1", 0)+","+payload.optInt("y1", 0), "to", payload.optInt("x2", 0)+","+payload.optInt("y2", 0)) : errorJson(lastError));
            case "global_back":
                ok = globalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                return resultJson(action, ok, ok ? "{}" : errorJson(lastError));
            case "global_home":
                ok = globalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                return resultJson(action, ok, ok ? "{}" : errorJson(lastError));
            case "global_recents":
                ok = globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                return resultJson(action, ok, ok ? "{}" : errorJson(lastError));
            case "take_screenshot":
                return resultJson(action, true, takeScreenshot());

            // --- ANDROID TTS & STT ---
            case "speak":
                ok = speak(payload.optString("text", ""), payload.optBoolean("queue", true),
                        (float) payload.optDouble("rate", 1.0), (float) payload.optDouble("pitch", 1.0));
                return resultJson(action, ok, ok ? jsonObject("status", "speaking") : errorJson(lastError));
            case "stop_speaking":
                stopSpeaking();
                return resultJson(action, true, "{}");
            case "listen":
                ok = listen(payload.optBoolean("continuous", false));
                return resultJson(action, ok, ok ? jsonObject("status", "listening") : errorJson(lastError));
            case "stop_listening":
                stopListening();
                return resultJson(action, true, "{}");

            // --- APPS & INTENTS ---
            case "open_app":
                ok = openApp(payload.optString("packageName", payload.optString("package", "")));
                return resultJson(action, ok, ok ? jsonObject("package", payload.optString("packageName", payload.optString("package", ""))) : errorJson(lastError));
            case "launch_url":
                ok = launchUrl(payload.optString("url", ""));
                return resultJson(action, ok, ok ? jsonObject("url", payload.optString("url", "")) : errorJson(lastError));
            case "launch_deeplink":
                ok = launchDeepLink(payload.optString("uri", ""));
                return resultJson(action, ok, ok ? jsonObject("uri", payload.optString("uri", "")) : errorJson(lastError));
            case "open_settings":
                ok = openSettings();
                return resultJson(action, ok, ok ? "{}" : errorJson(lastError));

            // --- MEDIA ---
            case "take_photo":
                return resultJson(action, true, takePhoto());
            case "pick_image":
                return resultJson(action, true, pickImage());

            // --- SMS ---
            case "send_sms":
                ok = sendSms(payload.optString("number", ""), payload.optString("message", ""));
                return resultJson(action, ok, ok ? jsonObject("number", payload.optString("number", "")) : errorJson(lastError));

            // --- CLIPBOARD ---
            case "get_clipboard":
                return resultJson(action, true, jsonObject("text", getClipboard()));
            case "set_clipboard":
                ok = setClipboard(payload.optString("text", ""));
                return resultJson(action, ok, ok ? jsonObject("set", payload.optString("text", "")) : errorJson(lastError));

            // --- SYSTEM / SHELL ---
            case "run_shell":
                return resultJson(action, true, runShellCommand(payload.optString("command", "")));

            // --- TOP ACTIVITY ---
            case "get_top_activity":
                return resultJson(action, true, getTopActivity());

            // --- SCHEDULING ---
            case "schedule_task":
                return resultJson(action, true, scheduleTask(
                        payload.optString("id", ""),
                        payload.optString("action", ""),
                        payload.optString("payload", "{}"),
                        payload.optLong("delayMs", 1000)));
            case "cancel_task":
                ok = cancelTask(payload.optString("id", ""));
                return resultJson(action, ok, ok ? jsonObject("cancelled", payload.optString("id", "")) : errorJson("Task not found"));

            // --- HTTP ---
            case "http_get":
                return resultJson(action, true, httpGet(payload.optString("url", "")));
            case "http_post":
                return resultJson(action, true, httpPost(payload.optString("url", ""), payload.optString("body", "{}")));

            // --- NOTIFICATIONS ---
            case "notify":
                ok = notify(payload.optString("title", "Assistant"),
                        payload.optString("message", payload.optString("text", "")),
                        payload.optInt("id", 1001));
                return resultJson(action, ok, ok ? jsonObject("status", "posted") : errorJson(lastError));

            // --- INFO ---
            case "get_device_info":
                return resultJson(action, true, getDeviceInfoJson());
            case "get_battery_info":
                return resultJson(action, true, getBatteryInfoJson());
            case "get_installed_apps":
                return resultJson(action, true, getInstalledAppsJson());
            case "get_current_time":
                return resultJson(action, true, getCurrentTimeJson());
            case "get_missing_permissions":
                return resultJson(action, true, getMissingPermissionsJson());

            // --- NEW OFFLINE AI ACTIONS ---
            case "generate":
                return resultJson(action, true, generateText(
                        payload.optString("prompt", ""),
                        payload.optString("system", "You are a helpful assistant."),
                        payload.optInt("max_tokens", 512)));

            case "vision":
                return resultJson(action, true, visionQuery(
                        payload.optString("image", ""),
                        payload.optString("question", "Describe this image.")));

            case "transcribe_vosk":
                return resultJson(action, true, transcribeVosk(
                        payload.optString("audio", "/sdcard/recording.wav")));

            case "embed":
                return resultJson(action, true, embedText(
                        payload.optString("text", "")));

            case "speak_kitten":
                return resultJson(action, true, speakWithKitten(
                        payload.optString("text", ""),
                        payload.optString("voice", "female-0")));

            case "record_audio":
                return resultJson(action, true, recordAudio(
                        payload.optInt("duration", 3)));

            case "remember":
                return resultJson(action, true, rememberFact(
                        payload.optString("fact", "")));

            case "recall":
                return resultJson(action, true, recallFacts(
                        payload.optString("query", "")));

            case "process":
                return resultJson(action, true, processCommand(
                        payload.optString("query", "")));

            case "send_email":
                return resultJson(action, true, sendEmail(
                        payload.optString("to", ""),
                        payload.optString("subject", ""),
                        payload.optString("body", "")));

            case "analyze_screen":
                return resultJson(action, true, analyzeScreen(
                        payload.optString("question", "Describe what you see.")));

            default:
                lastError = "Unknown action: " + action;
                emitEvent("tool_error", jsonObject("message", lastError, "action", action));
                return errorResult(action, lastError);
        }
    }

    // ========================================================================
    // JSON HELPERS
    // ========================================================================
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private JSONObject safeParse(String json) {
        try { return new JSONObject(json); } catch (Exception e) { return new JSONObject(); }
    }

    private String jsonObject(String key, String value) {
        JSONObject obj = new JSONObject();
        try { obj.put(key, value); } catch (Exception ignored) {}
        return obj.toString();
    }

    private String jsonObject(String key1, String value1, String key2, String value2) {
        JSONObject obj = new JSONObject();
        try { obj.put(key1, value1); obj.put(key2, value2); } catch (Exception ignored) {}
        return obj.toString();
    }

    private String jsonObject(String key, int value) {
        JSONObject obj = new JSONObject();
        try { obj.put(key, value); } catch (Exception ignored) {}
        return obj.toString();
    }

    private String jsonObject(String key1, String value1, String key2, int value2) {
        JSONObject obj = new JSONObject();
        try { obj.put(key1, value1); obj.put(key2, value2); } catch (Exception ignored) {}
        return obj.toString();
    }

    private String errorJson(String message) {
        return jsonObject("error", message == null ? "unknown" : message);
    }

    private String resultJson(String action, boolean success, String payloadJson) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", action);
            obj.put("success", success);
            obj.put("payload", safeParse(payloadJson));
        } catch (Exception ignored) {}
        return obj.toString();
    }

    private String errorResult(String action, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", action);
            obj.put("success", false);
            obj.put("error", message);
        } catch (Exception ignored) {}
        return obj.toString();
    }

    private void emitEvent(String type, String jsonPayload) {
        if (eventListener != null) {
            try { eventListener.onEvent(type, jsonPayload == null ? "{}" : jsonPayload); }
            catch (Exception ignored) {}
        }
    }

    private boolean isProbablyEmulator() {
        String fp = Build.FINGERPRINT;
        String model = Build.MODEL;
        String product = Build.PRODUCT;
        return (fp != null && (fp.contains("generic") || fp.contains("unknown"))) ||
                (model != null && (model.contains("google_sdk") || model.contains("Emulator") || model.contains("Android SDK built for x86"))) ||
                (product != null && (product.contains("sdk") || product.contains("emulator")));
    }
}