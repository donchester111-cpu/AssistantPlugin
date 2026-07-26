package com.godwin.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.Notification;
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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
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
import android.util.Log;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class AssistantPlugin {

    public interface EventListener {
        void onEvent(String type, String jsonPayload);
    }

    public interface ResultListener {
        void onResult(String action, boolean success, String jsonPayload);
    }

    private Activity activity;
    private Context context;
    private Handler mainHandler;
    private EventListener eventListener;
    private ResultListener resultListener;

    private String lastError = "";
    private String lastSpeechText = "";
    private String notificationChannelId = "assistant_core";

    private static final String POST_NOTIFICATIONS_PERMISSION =
            "android.permission.POST_NOTIFICATIONS";

    private final Map<String, String> pendingUtterances =
            new HashMap<>();

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsSpeaking = false;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean listening = false;
    private boolean continuousListening = false;
    private boolean preferPartialResults = true;

    private static final String MODEL_DIR = "/sdcard/models/";
    private static final String LLAMA_MODEL =
            MODEL_DIR + "llama-3.2-1b-instruct-q4.gguf";
    private static final String SMOLVLM_MODEL =
            MODEL_DIR + "smolvlm-256m-instruct-q4.gguf";
    private static final String MM_PROJ =
            MODEL_DIR + "mmproj-smolvlm-256m-f16.gguf";
    private static final String NOMIC_MODEL =
            MODEL_DIR + "nomic-embed-text-v1.5-Q4_K_M.gguf";

    private static final String PYTHON_BIN = "python";
    private static final String VOSK_SCRIPT =
            "/data/data/com.termux/files/home/transcribe.py";
    private static final String LLM_SCRIPT =
            "/data/data/com.termux/files/home/llm.py";
    private static final String VISION_SCRIPT =
            "/data/data/com.termux/files/home/vision.py";
    private static final String EMBED_SCRIPT =
            "/data/data/com.termux/files/home/embed.py";

    private final Map<String, String> memoryStore =
            new HashMap<>();

    private final Map<String, float[]> memoryEmbeddings =
            new HashMap<>();

    private final Map<String, Runnable> scheduledTasks =
            new HashMap<>();

    private Uri lastImageUri = null;

    public AssistantPlugin() {
    }

    public AssistantPlugin(Activity activity) {
        attach(activity);
    }

    public void attach(Activity activity) {
        this.activity = activity;
        this.context = activity;
        this.mainHandler =
                new Handler(Looper.getMainLooper());

        ensureNotificationChannel();
        initTts();
    }

    public boolean isAttached() {
        return activity != null && context != null;
    }

    public void destroy() {
        stopListening();
        stopSpeaking();
        releaseRecognizer();
        releaseTts();

        for (Runnable runnable : scheduledTasks.values()) {
            mainHandler.removeCallbacks(runnable);
        }

        scheduledTasks.clear();
    }

    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
    }

    public void setResultListener(ResultListener listener) {
        this.resultListener = listener;
    }

    public boolean isListening() {
        return listening;
    }

    public boolean isSpeaking() {
        return ttsSpeaking;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastSpeechText() {
        return lastSpeechText;
    }

    private void initTts() {
        if (!isAttached()) {
            lastError = "Not attached.";
            emitEvent("error",
                    jsonObject("message", lastError));
            return;
        }

        releaseTts();

        tts = new TextToSpeech(context, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;

            if (ttsReady) {
                int result =
                        tts.setLanguage(Locale.getDefault());

                if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    lastError = "Default language is unavailable.";
                    emitEvent("error",
                            jsonObject("message", lastError));
                }

                emitEvent("tts_ready", "{}");
            } else {
                lastError = "TTS failed.";
                emitEvent("error",
                        jsonObject("message", lastError));
            }
        });

        if (tts != null) {
            tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {

                        @Override
                        public void onStart(String id) {
                            ttsSpeaking = true;
                            emitEvent("tts_start",
                                    jsonObject("utteranceId", id));
                        }

                        @Override
                        public void onDone(String id) {
                            ttsSpeaking = false;
                            pendingUtterances.remove(id);
                            emitEvent("tts_done",
                                    jsonObject("utteranceId", id));
                        }

                        @Override
                        public void onError(String id) {
                            ttsSpeaking = false;
                            pendingUtterances.remove(id);
                            emitEvent("tts_error",
                                    jsonObject("utteranceId", id));
                        }

                        @Override
                        public void onError(
                                String id,
                                int code) {

                            ttsSpeaking = false;
                            pendingUtterances.remove(id);

                            emitEvent(
                                    "tts_error",
                                    jsonObject(
                                            "utteranceId",
                                            id,
                                            "code",
                                            String.valueOf(code)));
                        }
                    });
        }
    }

    public boolean speak(String text) {
        return speak(text, true, 1.0f, 1.0f);
    }

    public boolean speak(
            String text,
            boolean queue,
            float rate,
            float pitch) {

        if (!ensureTts() ||
                TextUtils.isEmpty(text)) {
            return false;
        }

        final String id =
                UUID.randomUUID().toString();

        pendingUtterances.put(id, text);

        mainHandler.post(() -> {
            try {
                tts.setSpeechRate(rate);
                tts.setPitch(pitch);

                Bundle params = new Bundle();

                params.putString(
                        TextToSpeech.Engine
                                .KEY_PARAM_UTTERANCE_ID,
                        id);

                tts.speak(
                        text,
                        queue
                                ? TextToSpeech.QUEUE_ADD
                                : TextToSpeech.QUEUE_FLUSH,
                        params,
                        id);

            } catch (Exception e) {
                lastError = e.getMessage();
                emitEvent("error",
                        jsonObject("message", lastError));
            }
        });

        return true;
    }

    public void stopSpeaking() {
        if (tts != null && mainHandler != null) {
            mainHandler.post(() -> {
                try {
                    tts.stop();
                } catch (Exception ignored) {
                } finally {
                    ttsSpeaking = false;
                    pendingUtterances.clear();
                }
            });
        }
    }

    private boolean ensureTts() {
        if (tts == null || !ttsReady) {
            initTts();
        }

        return tts != null && ttsReady;
    }

    private void releaseTts() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }

            tts = null;
        }

        ttsReady = false;
        ttsSpeaking = false;
    }

    public boolean listen() {
        return listen(false);
    }

    public boolean listen(boolean continuous) {
        if (!ensureSpeechRecognizer()) {
            return false;
        }

        continuousListening = continuous;

        mainHandler.post(() -> {
            try {
                listening = true;
                speechRecognizer.startListening(speechIntent);

                emitEvent(
                        "speech_listen_start",
                        jsonObject(
                                "continuous",
                                String.valueOf(
                                        continuousListening)));

            } catch (Exception e) {
                listening = false;
                lastError = e.getMessage();

                emitEvent(
                        "speech_error",
                        jsonObject("message", lastError));
            }
        });

        return true;
    }

    public void stopListening() {
        listening = false;
        continuousListening = false;

        if (speechRecognizer != null &&
                mainHandler != null) {

            mainHandler.post(() -> {
                try {
                    speechRecognizer.stopListening();
                    speechRecognizer.cancel();
                } catch (Exception ignored) {
                }
            });
        }
    }

    private boolean ensureSpeechRecognizer() {
        if (!isAttached() ||
                !hasPermission(Manifest.permission.RECORD_AUDIO) ||
                !SpeechRecognizer
                        .isRecognitionAvailable(context)) {
            lastError =
                    "Speech recognition unavailable or permission missing.";
            return false;
        }

        if (speechRecognizer == null) {
            speechRecognizer =
                    SpeechRecognizer
                            .createSpeechRecognizer(context);

            speechRecognizer.setRecognitionListener(
                    new RecognitionListener() {

                        @Override
                        public void onReadyForSpeech(
                                Bundle params) {
                            emitEvent(
                                    "speech_ready",
                                    "{}");
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            emitEvent(
                                    "speech_begin",
                                    "{}");
                        }

                        @Override
                        public void onRmsChanged(
                                float rmsdB) {
                            emitEvent(
                                    "speech_rms",
                                    jsonObject(
                                            "rms",
                                            String.valueOf(
                                                    rmsdB)));
                        }

                        @Override
                        public void onBufferReceived(
                                byte[] buffer) {
                        }

                        @Override
                        public void onEndOfSpeech() {
                            emitEvent(
                                    "speech_end",
                                    "{}");
                        }

                        @Override
                        public void onError(int error) {
                            listening = false;
                            lastError =
                                    "Speech recognition error "
                                            + error;

                            emitEvent(
                                    "speech_error",
                                    jsonObject(
                                            "code",
                                            String.valueOf(
                                                    error),
                                            "message",
                                            lastError));

                            if (continuousListening) {
                                restartListeningSoon();
                            }
                        }

                        @Override
                        public void onResults(
                                Bundle results) {

                            listening = false;

                            ArrayList<String> matches =
                                    results.getStringArrayList(
                                            SpeechRecognizer
                                                    .RESULTS_RECOGNITION);

                            if (matches != null &&
                                    !matches.isEmpty()) {

                                lastSpeechText =
                                        matches.get(0);

                                emitEvent(
                                        "speech_result",
                                        jsonObject(
                                                "text",
                                                lastSpeechText));

                                if (resultListener != null) {
                                    resultListener.onResult(
                                            "speech_result",
                                            true,
                                            jsonObject(
                                                    "text",
                                                    lastSpeechText));
                                }
                            } else {
                                emitEvent(
                                        "speech_result",
                                        "{}");
                            }

                            if (continuousListening) {
                                restartListeningSoon();
                            }
                        }

                        @Override
                        public void onPartialResults(
                                Bundle partialResults) {

                            if (!preferPartialResults) {
                                return;
                            }

                            ArrayList<String> matches =
                                    partialResults
                                            .getStringArrayList(
                                                    SpeechRecognizer
                                                            .RESULTS_RECOGNITION);

                            if (matches != null &&
                                    !matches.isEmpty()) {

                                emitEvent(
                                        "speech_partial",
                                        jsonObject(
                                                "text",
                                                matches.get(0)));
                            }
                        }

                        @Override
                        public void onEvent(
                                int eventType,
                                Bundle params) {

                            emitEvent(
                                    "speech_event",
                                    jsonObject(
                                            "type",
                                            String.valueOf(
                                                    eventType)));
                        }
                    });
        }

        if (speechIntent == null) {
            speechIntent =
                    new Intent(
                            RecognizerIntent
                                    .ACTION_RECOGNIZE_SPEECH);

            speechIntent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent
                            .LANGUAGE_MODEL_FREE_FORM);

            speechIntent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault());

            speechIntent.putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true);

            speechIntent.putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    5);

            speechIntent.putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "Speak now");
        }

        return true;
    }

    private void restartListeningSoon() {
        mainHandler.postDelayed(() -> {
            if (continuousListening &&
                    speechRecognizer != null) {

                try {
                    speechRecognizer.cancel();
                    speechRecognizer.startListening(
                            speechIntent);
                    listening = true;
                } catch (Exception ignored) {
                }
            }
        }, 350);
    }

    private void releaseRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }

            speechRecognizer = null;
        }

        listening = false;
    }

    public boolean notify(
            String title,
            String message) {

        return notify(title, message, 1001);
    }

    public boolean notify(
            String title,
            String message,
            int id) {

        if (!isAttached()) return false;

        if (TextUtils.isEmpty(title)) {
            title = "Assistant";
        }

        if (Build.VERSION.SDK_INT >=
                33 &&
                !hasPermission(
                        POST_NOTIFICATIONS_PERMISSION)) {

            lastError =
                    "Missing POST_NOTIFICATIONS.";

            emitEvent(
                    "permission_missing",
                    jsonObject(
                            "permission",
                            POST_NOTIFICATIONS_PERMISSION));

            return false;
        }

        Intent launchIntent =
                context.getPackageManager()
                        .getLaunchIntentForPackage(
                                context.getPackageName());

        PendingIntent pi = null;

        if (launchIntent != null) {
            int flags =
                    PendingIntent.FLAG_UPDATE_CURRENT;

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M) {

                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            pi = PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    flags);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(
                    context,
                    notificationChannelId);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setSmallIcon(
                        android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(
                        new Notification.BigTextStyle()
                                .bigText(message))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH);

        if (pi != null) {
            builder.setContentIntent(pi);
        }

        NotificationManager nm =
                (NotificationManager)
                        context.getSystemService(
                                Context.NOTIFICATION_SERVICE);

        if (nm == null) return false;

        nm.notify(id, builder.build());

        emitEvent(
                "notification_posted",
                jsonObject(
                        "title",
                        title,
                        "message",
                        message,
                        "id",
                        String.valueOf(id)));

        return true;
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O &&
                isAttached()) {

            NotificationManager nm =
                    (NotificationManager)
                            context.getSystemService(
                                    Context.NOTIFICATION_SERVICE);

            if (nm != null &&
                    nm.getNotificationChannel(
                            notificationChannelId) == null) {

                NotificationChannel channel =
                        new NotificationChannel(
                                notificationChannelId,
                                "Assistant",
                                NotificationManager
                                        .IMPORTANCE_HIGH);

                nm.createNotificationChannel(channel);
            }
        }
    }

    public boolean openApp(String packageName) {
        if (!isAttached() ||
                TextUtils.isEmpty(packageName)) {
            return false;
        }

        try {
            Intent launch =
                    context.getPackageManager()
                            .getLaunchIntentForPackage(
                                    packageName);

            if (launch == null) {
                lastError = "App not found.";
                return false;
            }

            launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(launch);

            emitEvent(
                    "app_opened",
                    jsonObject(
                            "package",
                            packageName));

            return true;

        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    public boolean launchUrl(String url) {
        if (!isAttached() ||
                TextUtils.isEmpty(url)) {
            return false;
        }

        try {
            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url));

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);

            emitEvent(
                    "url_opened",
                    jsonObject("url", url));

            return true;

        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    public boolean openSettings() {
        try {
            Intent intent =
                    new Intent(
                            Settings.ACTION_SETTINGS);

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);
            return true;

        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    public boolean launchDeepLink(String uri) {
        if (!isAttached() ||
                TextUtils.isEmpty(uri)) {
            return false;
        }

        try {
            Intent intent =
                    Intent.parseUri(
                            uri,
                            Intent.URI_INTENT_SCHEME);

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);

            emitEvent(
                    "deeplink_launched",
                    jsonObject("uri", uri));

            return true;

        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    public String takePhoto() {
        if (!isAttached() ||
                !hasPermission(
                        Manifest.permission.CAMERA)) {

            return "{\"error\":\"No camera permission\"}";
        }

        try {
            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "IMG_" +
                            System.currentTimeMillis() +
                            ".jpg");

            values.put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg");

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q) {

                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES +
                                "/Assistant");
            }

            Uri imageUri =
                    context.getContentResolver()
                            .insert(
                                    MediaStore.Images.Media
                                            .EXTERNAL_CONTENT_URI,
                                    values);

            if (imageUri == null) {
                return "{\"error\":\"Failed to create image\"}";
            }

            Intent intent =
                    new Intent(
                            MediaStore.ACTION_IMAGE_CAPTURE);

            intent.putExtra(
                    MediaStore.EXTRA_OUTPUT,
                    imageUri);

            intent.addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(intent);

            lastImageUri = imageUri;

            return "{\"success\":true,\"uri\":\"" +
                    escapeJson(imageUri.toString()) +
                    "\"}";

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        }
    }

    public String pickImage() {
        if (!isAttached()) {
            return "{\"error\":\"Not attached\"}";
        }

        try {
            Intent intent =
                    new Intent(
                            Intent.ACTION_GET_CONTENT);

            intent.setType("image/*");
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);

            return "{\"success\":true}";

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        }
    }

    public boolean sendSms(
            String number,
            String message) {

        if (!isAttached()) return false;

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M &&
                !hasPermission(
                        Manifest.permission.SEND_SMS)) {

            lastError =
                    "Missing SEND_SMS permission.";

            emitEvent(
                    "permission_missing",
                    jsonObject(
                            "permission",
                            Manifest.permission.SEND_SMS));

            return false;
        }

        try {
            SmsManager smsManager =
                    SmsManager.getDefault();

            ArrayList<String> parts =
                    smsManager.divideMessage(message);

            smsManager.sendMultipartTextMessage(
                    number,
                    null,
                    parts,
                    null,
                    null);

            emitEvent(
                    "sms_sent",
                    jsonObject(
                            "number",
                            number));

            return true;

        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    public String getClipboard() {
        if (!isAttached()) return "";

        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                        context.getSystemService(
                                Context.CLIPBOARD_SERVICE);

        if (clipboard != null &&
                clipboard.hasPrimaryClip() &&
                clipboard.getPrimaryClip()
                        .getItemCount() > 0) {

            CharSequence text =
                    clipboard.getPrimaryClip()
                            .getItemAt(0)
                            .getText();

            return text != null
                    ? text.toString()
                    : "";
        }

        return "";
    }

    public boolean setClipboard(String text) {
        if (!isAttached() ||
                TextUtils.isEmpty(text)) {
            return false;
        }

        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                        context.getSystemService(
                                Context.CLIPBOARD_SERVICE);

        if (clipboard == null) return false;

        android.content.ClipData clip =
                android.content.ClipData.newPlainText(
                        "Assistant",
                        text);

        clipboard.setPrimaryClip(clip);

        emitEvent(
                "clipboard_set",
                jsonObject("text", text));

        return true;
    }

    public String getTopActivity() {
        if (!isAttached()) {
            return "{\"error\":\"Not attached\"}";
        }

        try {
            UsageStatsManager usm =
                    (UsageStatsManager)
                            context.getSystemService(
                                    Context.USAGE_STATS_SERVICE);

            if (usm == null) {
                return "{\"error\":\"Usage stats unavailable\"}";
            }

            long time =
                    System.currentTimeMillis();

            List<UsageStats> stats =
                    usm.queryUsageStats(
                            UsageStatsManager.INTERVAL_DAILY,
                            time - 10000,
                            time);

            if (stats != null) {
                UsageStats recent = null;

                for (UsageStats stat : stats) {
                    if (recent == null ||
                            stat.getLastTimeUsed() >
                                    recent.getLastTimeUsed()) {
                        recent = stat;
                    }
                }

                if (recent != null) {
                    return new JSONObject()
                            .put(
                                    "package",
                                    recent.getPackageName())
                            .put(
                                    "name",
                                    getAppLabel(
                                            recent.getPackageName()))
                            .toString();
                }
            }

            return "{\"error\":\"Usage stats permission missing or no data\"}";

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        }
    }

    private String getAppLabel(String pkg) {
        try {
            ApplicationInfo ai =
                    context.getPackageManager()
                            .getApplicationInfo(pkg, 0);

            CharSequence label =
                    context.getPackageManager()
                            .getApplicationLabel(ai);

            return label != null
                    ? label.toString()
                    : pkg;

        } catch (Exception e) {
            return pkg;
        }
    }

    public String runShellCommand(String command) {
        if (!isAttached() ||
                TextUtils.isEmpty(command)) {
            return "{\"error\":\"Invalid command\"}";
        }

        Process process = null;

        try {
            process =
                    Runtime.getRuntime()
                            .exec(command);

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream()));

            BufferedReader errorReader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getErrorStream()));

            StringBuilder output =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line)
                        .append("\n");
            }

            reader.close();

            StringBuilder error =
                    new StringBuilder();

            while ((line = errorReader.readLine()) != null) {
                error.append(line)
                        .append("\n");
            }

            errorReader.close();

            int exitCode =
                    process.waitFor();

            JSONObject result =
                    new JSONObject();

            result.put("exitCode", exitCode);
            result.put(
                    "output",
                    output.toString().trim());
            result.put(
                    "error",
                    error.toString().trim());

            emitEvent(
                    "shell_executed",
                    jsonObject(
                            "command",
                            command));

            return result.toString();

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String runPythonScript(
            String scriptPath,
            String... args) {

        StringBuilder cmd =
                new StringBuilder();

        cmd.append(PYTHON_BIN)
                .append(" ")
                .append(quoteShellArgument(scriptPath));

        for (String arg : args) {
            cmd.append(" ")
                    .append(quoteShellArgument(arg));
        }

        return runShellCommand(
                cmd.toString());
    }

    private String quoteShellArgument(String value) {
        if (value == null) return "''";

        return "'" +
                value.replace("'", "'\\''") +
                "'";
    }

    public String scheduleTask(
            String id,
            String action,
            String payload,
            long delayMs) {

        if (!isAttached()) {
            return "{\"error\":\"Not attached\"}";
        }

        if (TextUtils.isEmpty(id)) {
            id = UUID.randomUUID().toString();
        }

        final String taskId = id;
        final String taskAction = action;
        final String taskPayload = payload;

        Runnable runnable = () -> {
            if (isAttached()) {
                String result =
                        execute(
                                taskAction,
                                taskPayload);

                emitEvent(
                        "scheduled_task_executed",
                        jsonObject(
                                "id",
                                taskId,
                                "result",
                                result));
            }

            scheduledTasks.remove(taskId);
        };

        scheduledTasks.put(taskId, runnable);

        mainHandler.postDelayed(
                runnable,
                Math.max(0, delayMs));

        return "{\"success\":true,\"id\":\"" +
                escapeJson(taskId) +
                "\",\"delayMs\":" +
                delayMs +
                "}";
    }

    public boolean cancelTask(String id) {
        Runnable r =
                scheduledTasks.remove(id);

        if (r != null) {
            mainHandler.removeCallbacks(r);
            return true;
        }

        return false;
    }

    public String httpGet(String url) {
        if (!isAttached() ||
                TextUtils.isEmpty(url)) {
            return "{\"error\":\"Invalid URL\"}";
        }

        HttpURLConnection con = null;

        try {
            URL obj = new URL(url);

            con =
                    (HttpURLConnection)
                            obj.openConnection();

            con.setRequestMethod("GET");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            int code =
                    con.getResponseCode();

            BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(
                                    code >= 400
                                            ? con.getErrorStream()
                                            : con.getInputStream()));

            StringBuilder response =
                    new StringBuilder();

            String inputLine;

            while ((inputLine =
                    in.readLine()) != null) {

                response.append(inputLine);
            }

            in.close();

            return new JSONObject()
                    .put("status", code)
                    .put(
                            "body",
                            response.toString())
                    .toString();

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public String httpPost(
            String url,
            String jsonBody) {

        if (!isAttached() ||
                TextUtils.isEmpty(url)) {
            return "{\"error\":\"Invalid URL\"}";
        }

        HttpURLConnection con = null;

        try {
            URL obj = new URL(url);

            con =
                    (HttpURLConnection)
                            obj.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty(
                    "Content-Type",
                    "application/json");

            con.setDoOutput(true);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            OutputStreamWriter wr =
                    new OutputStreamWriter(
                            con.getOutputStream());

            wr.write(
                    jsonBody == null
                            ? "{}"
                            : jsonBody);

            wr.flush();
            wr.close();

            int code =
                    con.getResponseCode();

            BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(
                                    code >= 400
                                            ? con.getErrorStream()
                                            : con.getInputStream()));

            StringBuilder response =
                    new StringBuilder();

            String inputLine;

            while ((inputLine =
                    in.readLine()) != null) {

                response.append(inputLine);
            }

            in.close();

            return new JSONObject()
                    .put("status", code)
                    .put(
                            "body",
                            response.toString())
                    .toString();

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public boolean hasPermission(String permission) {
        if (!isAttached()) return false;

        if (Build.VERSION.SDK_INT <
                Build.VERSION_CODES.M) {
            return true;
        }

        return context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED;
    }

    public String getMissingPermissionsJson() {
        JSONArray arr = new JSONArray();

        try {
            if (!hasPermission(
                    Manifest.permission.RECORD_AUDIO)) {
                arr.put(
                        Manifest.permission.RECORD_AUDIO);
            }

            if (Build.VERSION.SDK_INT >=
                    33 &&
                    !hasPermission(
                            POST_NOTIFICATIONS_PERMISSION)) {

                arr.put(
                        POST_NOTIFICATIONS_PERMISSION);
            }

            if (!hasPermission(
                    Manifest.permission.SEND_SMS)) {

                arr.put(
                        Manifest.permission.SEND_SMS);
            }

            if (!hasPermission(
                    Manifest.permission.CAMERA)) {

                arr.put(
                        Manifest.permission.CAMERA);
            }

        } catch (Exception ignored) {
        }

        return arr.toString();
    }

    public String getDeviceInfoJson() {
        JSONObject obj = new JSONObject();

        try {
            obj.put(
                    "manufacturer",
                    Build.MANUFACTURER);

            obj.put(
                    "model",
                    Build.MODEL);

            obj.put(
                    "androidVersion",
                    Build.VERSION.RELEASE);

            obj.put(
                    "sdkInt",
                    Build.VERSION.SDK_INT);

            obj.put(
                    "isEmulator",
                    isProbablyEmulator());

        } catch (JSONException ignored) {
        }

        return obj.toString();
    }

    public String getBatteryInfoJson() {
        JSONObject obj = new JSONObject();

        try {
            IntentFilter filter =
                    new IntentFilter(
                            Intent.ACTION_BATTERY_CHANGED);

            Intent battery =
                    context.registerReceiver(
                            null,
                            filter);

            if (battery != null) {
                int level =
                        battery.getIntExtra(
                                android.os.BatteryManager
                                        .EXTRA_LEVEL,
                                -1);

                int scale =
                        battery.getIntExtra(
                                android.os.BatteryManager
                                        .EXTRA_SCALE,
                                -1);

                obj.put(
                        "percent",
                        level > 0 &&
                                scale > 0
                                ? Math.round(
                                        level *
                                                100f /
                                                scale)
                                : -1);

                obj.put(
                        "charging",
                        battery.getIntExtra(
                                android.os.BatteryManager
                                        .EXTRA_STATUS,
                                -1) ==
                                android.os.BatteryManager
                                        .BATTERY_STATUS_CHARGING);
            }

        } catch (Exception ignored) {
        }

        return obj.toString();
    }

    public String getInstalledAppsJson() {
        if (!isAttached()) return "[]";

        JSONArray arr = new JSONArray();

        try {
            PackageManager pm =
                    context.getPackageManager();

            for (ApplicationInfo app :
                    pm.getInstalledApplications(
                            PackageManager.GET_META_DATA)) {

                JSONObject item =
                        new JSONObject();

                item.put(
                        "packageName",
                        app.packageName);

                CharSequence label =
                        pm.getApplicationLabel(app);

                item.put(
                        "label",
                        label != null
                                ? label.toString()
                                : app.packageName);

                arr.put(item);
            }

        } catch (Exception ignored) {
        }

        return arr.toString();
    }

    public String getCurrentTimeJson() {
        JSONObject obj =
                new JSONObject();

        try {
            obj.put(
                    "millis",
                    System.currentTimeMillis());

            obj.put(
                    "timezone",
                    java.util.TimeZone
                            .getDefault()
                            .getID());

        } catch (JSONException ignored) {
        }

        return obj.toString();
    }

    public boolean isAccessibilityEnabled() {
        return AssistantAccessibilityService
                .isRunning();
    }

    public void openAccessibilitySettings() {
        try {
            Intent intent =
                    new Intent(
                            Settings
                                    .ACTION_ACCESSIBILITY_SETTINGS);

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);

        } catch (Exception e) {
            lastError = e.getMessage();
        }
    }

    public String getScreenContent() {
        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null
                ? s.getScreenContent()
                : "{\"error\":\"Service not running\"}";
    }

    public boolean clickByText(String text) {
        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null &&
                s.clickByText(text);
    }

    public boolean clickById(String id) {
        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null &&
                s.clickById(id);
    }

    public boolean typeText(String text) {
        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null &&
                s.typeText(text);
    }

    public boolean tap(int x, int y) {
        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null &&
                s.tap(x, y);
    }

    public boolean swipe(
            int x1,
            int y1,
            int x2,
            int y2) {

        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null &&
                s.swipe(
                        x1,
                        y1,
                        x2,
                        y2);
    }

    public boolean globalAction(int action) {
        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null &&
                s.globalAction(action);
    }

    public String takeScreenshot() {
        AssistantAccessibilityService s =
                AssistantAccessibilityService
                        .getInstance();

        return s != null
                ? s.takeScreenshot()
                : "{\"error\":\"Service not running\"}";
    }

    public String recordAudio(int seconds) {
        if (!hasPermission(
                Manifest.permission.RECORD_AUDIO)) {

            return "{\"error\":\"Missing RECORD_AUDIO\"}";
        }

        String path =
                "/sdcard/recording.m4a";

        MediaRecorder recorder = null;

        try {
            recorder =
                    new MediaRecorder();

            recorder.setAudioSource(
                    MediaRecorder.AudioSource.MIC);

            recorder.setOutputFormat(
                    MediaRecorder.OutputFormat.MPEG_4);

            recorder.setOutputFile(path);

            recorder.setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC);

            recorder.setAudioSamplingRate(16000);

            recorder.prepare();
            recorder.start();

            Thread.sleep(
                    Math.max(1, seconds) *
                            1000L);

            recorder.stop();
            recorder.release();
            recorder = null;

            return "{\"success\":true,\"file\":\"" +
                    escapeJson(path) +
                    "\"}";

        } catch (Exception e) {
            if (recorder != null) {
                try {
                    recorder.release();
                } catch (Exception ignored) {
                }
            }

            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        }
    }

    private String transcribeVosk(
            String audioFile) {

        if (!new File(audioFile).exists()) {
            return "{\"error\":\"Audio file not found\"}";
        }

        return runPythonScript(
                VOSK_SCRIPT,
                audioFile);
    }

    private String generateText(
            String prompt,
            String system,
            int maxTokens) {

        if (TextUtils.isEmpty(prompt)) {
            return "{\"error\":\"Empty prompt\"}";
        }

        return runPythonScript(
                LLM_SCRIPT,
                prompt,
                system,
                String.valueOf(maxTokens));
    }

    private String visionQuery(
            String imagePath,
            String question) {

        if (!new File(imagePath).exists()) {
            return "{\"error\":\"Image not found\"}";
        }

        return runPythonScript(
                VISION_SCRIPT,
                imagePath,
                question);
    }

    private String embedText(String text) {
        if (TextUtils.isEmpty(text)) {
            return "{\"error\":\"Empty text\"}";
        }

        return runPythonScript(
                EMBED_SCRIPT,
                text);
    }

    public String rememberFact(String fact) {
        if (TextUtils.isEmpty(fact)) {
            return "{\"error\":\"Empty fact\"}";
        }

        String id =
                UUID.randomUUID().toString();

        synchronized (memoryStore) {
            memoryStore.put(id, fact);
        }

        new Thread(() -> {
            String result =
                    embedText(fact);

            try {
                JSONObject obj =
                        new JSONObject(result);

                JSONArray embArray =
                        obj.optJSONArray(
                                "embedding");

                if (embArray != null) {
                    float[] vec =
                            new float[
                                    embArray.length()];

                    for (int i = 0;
                            i < embArray.length();
                            i++) {

                        vec[i] =
                                (float)
                                        embArray
                                                .getDouble(i);
                    }

                    synchronized (
                            memoryEmbeddings) {

                        memoryEmbeddings
                                .put(id, vec);
                    }
                }

            } catch (Exception e) {
                Log.e(
                        "Memory",
                        "Embedding error",
                        e);
            }
        }).start();

        return "{\"success\":true,\"id\":\"" +
                escapeJson(id) +
                "\"}";
    }

    public String recallFacts(String query) {
        if (TextUtils.isEmpty(query)) {
            return "{\"error\":\"Empty query\"}";
        }

        String embResult =
                embedText(query);

        float[] queryVec = null;

        try {
            JSONObject obj =
                    new JSONObject(embResult);

            JSONArray arr =
                    obj.optJSONArray(
                            "embedding");

            if (arr != null) {
                queryVec =
                        new float[arr.length()];

                for (int i = 0;
                        i < arr.length();
                        i++) {

                    queryVec[i] =
                            (float)
                                    arr.getDouble(i);
                }
            }

        } catch (Exception e) {
            return "{\"error\":\"Failed to get query embedding\"}";
        }

        if (queryVec == null) {
            return "{\"error\":\"No embedding for query\"}";
        }

        String bestId = null;
        float bestSim = -1.0f;

        synchronized (memoryEmbeddings) {
            for (Map.Entry<String, float[]> entry :
                    memoryEmbeddings.entrySet()) {

                float[] vec =
                        entry.getValue();

                if (vec.length !=
                        queryVec.length) {
                    continue;
                }

                float dot = 0;
                float norm1 = 0;
                float norm2 = 0;

                for (int i = 0;
                        i < vec.length;
                        i++) {

                    dot +=
                            vec[i] *
                                    queryVec[i];

                    norm1 +=
                            vec[i] *
                                    vec[i];

                    norm2 +=
                            queryVec[i] *
                                    queryVec[i];
                }

                double denominator =
                        Math.sqrt(norm1) *
                                Math.sqrt(norm2);

                if (denominator == 0) {
                    continue;
                }

                float sim =
                        (float)
                                (dot /
                                        denominator);

                if (sim > bestSim) {
                    bestSim = sim;
                    bestId = entry.getKey();
                }
            }
        }

        if (bestId != null &&
                bestSim > 0.5f) {

            String fact;

            synchronized (memoryStore) {
                fact = memoryStore.get(bestId);
            }

            return "{\"found\":true,\"fact\":\"" +
                    escapeJson(fact) +
                    "\",\"score\":" +
                    bestSim +
                    "}";
        }

        return "{\"found\":false}";
    }

    public String processCommand(String userQuery) {
        if (TextUtils.isEmpty(userQuery)) {
            return "{\"error\":\"Empty query\"}";
        }

        String systemPrompt =
                "You are a phone assistant. Respond ONLY with a JSON object containing an 'action' and 'params'. " +
                "Available actions: speak, open_app, send_sms, send_email, remember, recall, vision, screenshot, " +
                "click_by_text, type_text, swipe, tap, take_photo, get_time, get_battery, get_clipboard, set_clipboard. " +
                "Example: {\"action\":\"open_app\",\"params\":{\"package\":\"com.example.app\"}}. " +
                "For sending SMS, use params: {\"number\":\"123\",\"message\":\"text\"}. " +
                "For remembering, use params: {\"fact\":\"something\"}. " +
                "For recall, use params: {\"query\":\"something\"}. " +
                "Always output valid JSON.";

        String response =
                generateText(
                        userQuery,
                        systemPrompt,
                        200);

        try {
            JSONObject obj =
                    new JSONObject(response);

            String text =
                    obj.optString(
                            "text",
                            "");

            int start =
                    text.indexOf('{');

            int end =
                    text.lastIndexOf('}');

            if (start != -1 &&
                    end != -1 &&
                    end > start) {

                String jsonStr =
                        text.substring(
                                start,
                                end + 1);

                JSONObject cmd =
                        new JSONObject(
                                jsonStr);

                String action =
                        cmd.optString(
                                "action");

                JSONObject params =
                        cmd.optJSONObject(
                                "params");

                if (params == null) {
                    params =
                            new JSONObject();
                }

                String result =
                        execute(
                                action,
                                params.toString());

                JSONObject finalResult =
                        new JSONObject();

                finalResult.put(
                        "original",
                        text);

                finalResult.put(
                        "executed",
                        new JSONObject(
                                result));

                return finalResult.toString();

            } else {
                return "{\"error\":\"No JSON found in response\",\"raw\":\"" +
                        escapeJson(text) +
                        "\"}";
            }

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        }
    }

    public String sendEmail(
            String to,
            String subject,
            String body) {

        if (!isAttached()) {
            return "{\"error\":\"Not attached\"}";
        }

        try {
            Intent intent =
                    new Intent(
                            Intent.ACTION_SENDTO);

            intent.setData(
                    Uri.parse(
                            "mailto:" +
                                    Uri.encode(to)));

            intent.putExtra(
                    Intent.EXTRA_SUBJECT,
                    subject);

            intent.putExtra(
                    Intent.EXTRA_TEXT,
                    body);

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);

            return "{\"success\":true}";

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        }
    }

    public String analyzeScreen(
            String question) {

        String screenshot =
                takeScreenshot();

        try {
            JSONObject shot =
                    new JSONObject(
                            screenshot);

            if (shot.has("error")) {
                return screenshot;
            }

            String base64 =
                    shot.optString(
                            "base64");

            byte[] data =
                    android.util.Base64
                            .decode(
                                    base64,
                                    android.util.Base64.DEFAULT);

            File temp =
                    new File(
                            context.getCacheDir(),
                            "screenshot.png");

            FileOutputStream fos =
                    new FileOutputStream(
                            temp);

            fos.write(data);
            fos.close();

            return visionQuery(
                    temp.getAbsolutePath(),
                    question);

        } catch (Exception e) {
            return "{\"error\":\"" +
                    escapeJson(e.getMessage()) +
                    "\"}";
        }
    }

    public String execute(
            String action,
            String payloadJson) {

        if (TextUtils.isEmpty(action)) {
            return errorResult(
                    "execute",
                    "Empty action");
        }

        JSONObject payload =
                safeParse(payloadJson);

        boolean ok;

        switch (action) {

            case "is_accessibility_enabled":
                return resultJson(
                        action,
                        true,
                        jsonObject(
                                "enabled",
                                String.valueOf(
                                        isAccessibilityEnabled())));

            case "open_accessibility_settings":
                openAccessibilitySettings();
                return resultJson(
                        action,
                        true,
                        "{}");

            case "get_screen_content":
                return resultJson(
                        action,
                        true,
                        getScreenContent());

            case "click_by_text":
                ok =
                        clickByText(
                                payload.optString(
                                        "text",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "clicked",
                                        payload.optString(
                                                "text",
                                                ""))
                                : errorJson(
                                        lastError));

            case "click_by_id":
                ok =
                        clickById(
                                payload.optString(
                                        "id",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "clicked",
                                        payload.optString(
                                                "id",
                                                ""))
                                : errorJson(
                                        lastError));

            case "type_text":
                ok =
                        typeText(
                                payload.optString(
                                        "text",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "typed",
                                        payload.optString(
                                                "text",
                                                ""))
                                : errorJson(
                                        lastError));

            case "tap":
                ok =
                        tap(
                                payload.optInt(
                                        "x",
                                        0),
                                payload.optInt(
                                        "y",
                                        0));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "x",
                                        payload.optInt(
                                                "x",
                                                0),
                                        "y",
                                        payload.optInt(
                                                "y",
                                                0))
                                : errorJson(
                                        lastError));

            case "swipe":
                ok =
                        swipe(
                                payload.optInt(
                                        "x1",
                                        0),
                                payload.optInt(
                                        "y1",
                                        0),
                                payload.optInt(
                                        "x2",
                                        0),
                                payload.optInt(
                                        "y2",
                                        0));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "from",
                                        payload.optInt(
                                                "x1",
                                                0) +
                                                "," +
                                                payload.optInt(
                                                        "y1",
                                                        0),
                                        "to",
                                        payload.optInt(
                                                "x2",
                                                0) +
                                                "," +
                                                payload.optInt(
                                                        "y2",
                                                        0))
                                : errorJson(
                                        lastError));

            case "global_back":
                ok =
                        globalAction(
                                AssistantAccessibilityService
                                        .GLOBAL_ACTION_BACK);

                return resultJson(
                        action,
                        ok,
                        ok
                                ? "{}"
                                : errorJson(
                                        lastError));

            case "global_home":
                ok =
                        globalAction(
                                AssistantAccessibilityService
                                        .GLOBAL_ACTION_HOME);

                return resultJson(
                        action,
                        ok,
                        ok
                                ? "{}"
                                : errorJson(
                                        lastError));

            case "global_recents":
                ok =
                        globalAction(
                                AssistantAccessibilityService
                                        .GLOBAL_ACTION_RECENTS);

                return resultJson(
                        action,
                        ok,
                        ok
                                ? "{}"
                                : errorJson(
                                        lastError));

            case "take_screenshot":
                return resultJson(
                        action,
                        false,
                        takeScreenshot());

            case "speak":
                ok =
                        speak(
                                payload.optString(
                                        "text",
                                        ""),
                                payload.optBoolean(
                                        "queue",
                                        true),
                                (float)
                                        payload.optDouble(
                                                "rate",
                                                1.0),
                                (float)
                                        payload.optDouble(
                                                "pitch",
                                                1.0));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "status",
                                        "speaking")
                                : errorJson(
                                        lastError));

            case "stop_speaking":
                stopSpeaking();

                return resultJson(
                        action,
                        true,
                        "{}");

            case "listen":
                ok =
                        listen(
                                payload.optBoolean(
                                        "continuous",
                                        false));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "status",
                                        "listening")
                                : errorJson(
                                        lastError));

            case "stop_listening":
                stopListening();

                return resultJson(
                        action,
                        true,
                        "{}");

            case "open_app":
                String packageName =
                        payload.optString(
                                "packageName",
                                payload.optString(
                                        "package",
                                        ""));

                ok =
                        openApp(
                                packageName);

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "package",
                                        packageName)
                                : errorJson(
                                        lastError));

            case "launch_url":
                ok =
                        launchUrl(
                                payload.optString(
                                        "url",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "url",
                                        payload.optString(
                                                "url",
                                                ""))
                                : errorJson(
                                        lastError));

            case "launch_deeplink":
                ok =
                        launchDeepLink(
                                payload.optString(
                                        "uri",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "uri",
                                        payload.optString(
                                                "uri",
                                                ""))
                                : errorJson(
                                        lastError));

            case "open_settings":
                ok = openSettings();

                return resultJson(
                        action,
                        ok,
                        ok
                                ? "{}"
                                : errorJson(
                                        lastError));

            case "take_photo":
                return resultJson(
                        action,
                        true,
                        takePhoto());

            case "pick_image":
                return resultJson(
                        action,
                        true,
                        pickImage());

            case "send_sms":
                ok =
                        sendSms(
                                payload.optString(
                                        "number",
                                        ""),
                                payload.optString(
                                        "message",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "number",
                                        payload.optString(
                                                "number",
                                                ""))
                                : errorJson(
                                        lastError));

            case "get_clipboard":
                return resultJson(
                        action,
                        true,
                        jsonObject(
                                "text",
                                getClipboard()));

            case "set_clipboard":
                ok =
                        setClipboard(
                                payload.optString(
                                        "text",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "set",
                                        payload.optString(
                                                "text",
                                                ""))
                                : errorJson(
                                        lastError));

            case "run_shell":
                return resultJson(
                        action,
                        true,
                        runShellCommand(
                                payload.optString(
                                        "command",
                                        "")));

            case "get_top_activity":
                return resultJson(
                        action,
                        true,
                        getTopActivity());

            case "schedule_task":
                return resultJson(
                        action,
                        true,
                        scheduleTask(
                                payload.optString(
                                        "id",
                                        ""),
                                payload.optString(
                                        "action",
                                        ""),
                                payload.optString(
                                        "payload",
                                        "{}"),
                                payload.optLong(
                                        "delayMs",
                                        1000)));

            case "cancel_task":
                ok =
                        cancelTask(
                                payload.optString(
                                        "id",
                                        ""));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "cancelled",
                                        payload.optString(
                                                "id",
                                                ""))
                                : errorJson(
                                        "Task not found"));

            case "http_get":
                return resultJson(
                        action,
                        true,
                        httpGet(
                                payload.optString(
                                        "url",
                                        "")));

            case "http_post":
                return resultJson(
                        action,
                        true,
                        httpPost(
                                payload.optString(
                                        "url",
                                        ""),
                                payload.optString(
                                        "body",
                                        "{}")));

            case "notify":
                ok =
                        notify(
                                payload.optString(
                                        "title",
                                        "Assistant"),
                                payload.optString(
                                        "message",
                                        payload.optString(
                                                "text",
                                                "")),
                                payload.optInt(
                                        "id",
                                        1001));

                return resultJson(
                        action,
                        ok,
                        ok
                                ? jsonObject(
                                        "status",
                                        "posted")
                                : errorJson(
                                        lastError));

            case "get_device_info":
                return resultJson(
                        action,
                        true,
                        getDeviceInfoJson());

            case "get_battery_info":
                return resultJson(
                        action,
                        true,
                        getBatteryInfoJson());

            case "get_installed_apps":
                return resultJson(
                        action,
                        true,
                        getInstalledAppsJson());

            case "get_current_time":
                return resultJson(
                        action,
                        true,
                        getCurrentTimeJson());

            case "get_missing_permissions":
                return resultJson(
                        action,
                        true,
                        getMissingPermissionsJson());

            case "generate":
                return resultJson(
                        action,
                        true,
                        generateText(
                                payload.optString(
                                        "prompt",
                                        ""),
                                payload.optString(
                                        "system",
                                        "You are a helpful assistant."),
                                payload.optInt(
                                        "max_tokens",
                                        512)));

            case "vision":
                return resultJson(
                        action,
                        true,
                        visionQuery(
                                payload.optString(
                                        "image",
                                        ""),
                                payload.optString(
                                        "question",
                                        "Describe this image.")));

            case "transcribe_vosk":
                return resultJson(
                        action,
                        true,
                        transcribeVosk(
                                payload.optString(
                                        "audio",
                                        "/sdcard/recording.m4a")));

            case "embed":
                return resultJson(
                        action,
                        true,
                        embedText(
                                payload.optString(
                                        "text",
                                        "")));

            case "record_audio":
                return resultJson(
                        action,
                        true,
                        recordAudio(
                                payload.optInt(
                                        "duration",
                                        3)));

            case "remember":
                return resultJson(
                        action,
                        true,
                        rememberFact(
                                payload.optString(
                                        "fact",
                                        "")));

            case "recall":
                return resultJson(
                        action,
                        true,
                        recallFacts(
                                payload.optString(
                                        "query",
                                        "")));

            case "process":
                return resultJson(
                        action,
                        true,
                        processCommand(
                                payload.optString(
                                        "query",
                                        "")));

            case "send_email":
                return resultJson(
                        action,
                        true,
                        sendEmail(
                                payload.optString(
                                        "to",
                                        ""),
                                payload.optString(
                                        "subject",
                                        ""),
                                payload.optString(
                                        "body",
                                        "")));

            case "analyze_screen":
                return resultJson(
                        action,
                        false,
                        analyzeScreen(
                                payload.optString(
                                        "question",
                                        "Describe what you see.")));

            default:
                lastError =
                        "Unknown action: " +
                                action;

                emitEvent(
                        "tool_error",
                        jsonObject(
                                "message",
                                lastError,
                                "action",
                                action));

                return errorResult(
                        action,
                        lastError);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";

        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private JSONObject safeParse(String json) {
        try {
            return new JSONObject(
                    json == null
                            ? "{}"
                            : json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String jsonObject(
            String key,
            String value) {

        JSONObject obj =
                new JSONObject();

        try {
            obj.put(key, value);
        } catch (Exception ignored) {
        }

        return obj.toString();
    }

    private String jsonObject(
            String key1,
            String value1,
            String key2,
            String value2) {

        JSONObject obj =
                new JSONObject();

        try {
            obj.put(key1, value1);
            obj.put(key2, value2);
        } catch (Exception ignored) {
        }

        return obj.toString();
    }

    private String jsonObject(
            String key,
            int value) {

        JSONObject obj =
                new JSONObject();

        try {
            obj.put(key, value);
        } catch (Exception ignored) {
        }

        return obj.toString();
    }

    private String jsonObject(
            String key1,
            String value1,
            String key2,
            int value2) {

        JSONObject obj =
                new JSONObject();

        try {
            obj.put(key1, value1);
            obj.put(key2, value2);
        } catch (Exception ignored) {
        }

        return obj.toString();
    }

    private String errorJson(String message) {
        return jsonObject(
                "error",
                message == null
                        ? "unknown"
                        : message);
    }

    private String resultJson(
            String action,
            boolean success,
            String payloadJson) {

        JSONObject obj =
                new JSONObject();

        try {
            obj.put(
                    "action",
                    action);

            obj.put(
                    "success",
                    success);

            obj.put(
                    "payload",
                    safeParse(payloadJson));

        } catch (Exception ignored) {
        }

        return obj.toString();
    }

    private String errorResult(
            String action,
            String message) {

        JSONObject obj =
                new JSONObject();

        try {
            obj.put(
                    "action",
                    action);

            obj.put(
                    "success",
                    false);

            obj.put(
                    "error",
                    message);

        } catch (Exception ignored) {
        }

        return obj.toString();
    }

    private void emitEvent(
            String type,
            String jsonPayload) {

        if (eventListener != null) {
            try {
                eventListener.onEvent(
                        type,
                        jsonPayload == null
                                ? "{}"
                                : jsonPayload);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isProbablyEmulator() {
        String fp = Build.FINGERPRINT;
        String model = Build.MODEL;
        String product = Build.PRODUCT;

        return
                (fp != null &&
                        (fp.contains("generic") ||
                                fp.contains("unknown"))) ||

                (model != null &&
                        (model.contains(
                                "google_sdk") ||
                                model.contains(
                                        "Emulator") ||
                                model.contains(
                                        "Android SDK built for x86"))) ||

                (product != null &&
                        (product.contains(
                                "sdk") ||
                                product.contains(
                                        "emulator")));
    }
}
