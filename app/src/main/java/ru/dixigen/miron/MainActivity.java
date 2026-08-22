package ru.dixigen.miron;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.webkit.*;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final String TAG = "Miron";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
    private static final String MODEL_DIR = "vosk_model";

    private WebView web;
    private TextToSpeech tts;
    private Model model;
    private Recognizer recognizer;
    private AudioRecord recorder;
    private Thread recThread;
    private volatile boolean listening = false;
    private volatile boolean modelReady = false;
    private volatile boolean recordingReady = false;

    private static final int PERMISSION_REQUEST_CODE = 77;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        
        try {
            web = new WebView(this);
            setContentView(web);
            
            // Настройка WebView
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                s.setAllowFileAccessFromFileURLs(true);
                s.setAllowUniversalAccessFromFileURLs(true);
            }

            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String u) {
                    if (!u.contains("miron.dixigen.ru")) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                        } catch (Exception e) {
                            Log.e(TAG, "Error opening URL: " + e);
                        }
                        return true;
                    }
                    return false;
                }
                
                @Override
                public void onPageFinished(WebView v, String u) {
                    v.evaluateJavascript("var x=document.createElement('script');x.src='https://miron.dixigen.ru/agent/mshim.js';document.head.appendChild(x);", null);
                }
                
                @Override
                public void onReceivedError(WebView v, int errorCode, String description, String failingUrl) {
                    Log.e(TAG, "WebView error: " + description);
                    status("webview_error");
                }
            });

            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(final PermissionRequest r) {
                    runOnUiThread(new Runnable() {
                        public void run() { 
                            try {
                                r.grant(r.getResources()); 
                            } catch (Exception e) {
                                Log.e(TAG, "Permission request error: " + e);
                            }
                        }
                    });
                }
            });

            web.addJavascriptInterface(new Bridge(), "Miron");

            // Очистка кэша
            web.clearCache(true);
            CookieManager.getInstance().setAcceptCookie(true);
            
            // Проверка разрешений
            String[] permissions = { 
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET
            };
            
            boolean needPermission = false;
            for (String p : permissions) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needPermission = true;
                    break;
                }
            }
            
            if (needPermission) {
                requestPermissions(permissions, PERMISSION_REQUEST_CODE);
            }

            // Инициализация TTS
            tts = new TextToSpeech(this, this);
            
            // Загрузка страницы
            web.loadUrl("https://miron.dixigen.ru/go.php");

            // Загрузка модели в фоне
            new Thread(new Runnable() { 
                public void run() { 
                    prepareModel(); 
                } 
            }).start();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e);
            Toast.makeText(this, "Ошибка инициализации: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Permission denied: " + permissions[i]);
                    Toast.makeText(this, "Необходимы разрешения для работы приложения", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void status(final String st) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript("window.onMironStatus&&window.onMironStatus('" + st + "')", null);
                } catch (Exception e) {
                    Log.e(TAG, "Status error: " + e);
                }
            }
        });
    }

    private void prepareModel() {
        try {
            File dir = new File(getFilesDir(), MODEL_DIR);
            
            if (!dir.exists()) {
                status("model_downloading");
                File zip = new File(getCacheDir(), "vosk_model.zip");
                
                // Скачивание
                download(MODEL_URL, zip);
                
                // Распаковка
                unzip(zip, getFilesDir());
                zip.delete();
                
                // Поиск распакованной папки
                File extracted = null;
                File[] kids = getFilesDir().listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        if (k.isDirectory() && k.getName().startsWith("vosk-model")) {
                            extracted = k; 
                            break; 
                        }
                    }
                }
                
                if (extracted != null && !extracted.getName().equals(MODEL_DIR)) {
                    File newDir = new File(getFilesDir(), MODEL_DIR);
                    if (extracted.renameTo(newDir)) {
                        dir = newDir;
                    } else {
                        // Если не удалось переименовать, копируем
                        copyDirectory(extracted, newDir);
                        deleteDirectory(extracted);
                        dir = newDir;
                    }
                }
            }
            
            // Проверяем, что модель существует
            File modelFile = new File(dir, "am");
            if (!modelFile.exists()) {
                status("model_error");
                Log.e(TAG, "Model files not found in " + dir.getAbsolutePath());
                return;
            }
            
            model = new Model(dir.getAbsolutePath());
            modelReady = true;
            status("model_ready");
            Log.i(TAG, "Модель готова");
            
        } catch (Exception e) {
            Log.e(TAG, "Ошибка подготовки модели: " + e);
            status("model_error");
        }
    }

    private void copyDirectory(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.exists()) {
                target.mkdirs();
            }
            String[] children = source.list();
            if (children != null) {
                for (String child : children) {
                    copyDirectory(new File(source, child), new File(target, child));
                }
            }
        } else {
            FileInputStream in = new FileInputStream(source);
            FileOutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            in.close();
            out.close();
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        dir.delete();
    }

    private void download(String url, File out) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream os = null;
        
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            int responseCode = c.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("Server returned HTTP " + responseCode);
            }
            
            in = c.getInputStream();
            os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception e) {}
            if (os != null) try { os.close(); } catch (Exception e) {}
            if (c != null) try { c.disconnect(); } catch (Exception e) {}
        }
    }

    private void unzip(File zip, File destDir) throws Exception {
        ZipInputStream zis = null;
        
        try {
            zis = new ZipInputStream(new FileInputStream(zip));
            ZipEntry e;
            byte[] buf = new byte[8192];
            
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                
                // Проверка безопасности
                String canonicalPath = out.getCanonicalPath();
                String destPath = destDir.getCanonicalPath();
                if (!canonicalPath.startsWith(destPath + File.separator) && !canonicalPath.equals(destPath)) {
                    zis.closeEntry();
                    continue;
                }
                
                if (e.isDirectory()) {
                    out.mkdirs();
                    zis.closeEntry();
                    continue;
                }
                
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                
                FileOutputStream fos = new FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                zis.closeEntry();
            }
        } finally {
            if (zis != null) try { zis.close(); } catch (Exception e) {}
        }
    }

    @Override
    public void onInit(int st) {
        if (st == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("ru"));
            tts.setPitch(0.85f);
        } else {
            Log.e(TAG, "TTS initialization failed");
        }
    }

    public class Bridge {
        @JavascriptInterface
        public void speak(String t) {
            if (tts != null && t != null && !t.isEmpty()) {
                tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m1");
            }
        }
        
        @JavascriptInterface
        public void stopSpeak() {
            if (tts != null) {
                tts.stop();
            }
        }
        
        @JavascriptInterface
        public void startListen() {
            runOnUiThread(new Runnable() { 
                public void run() { 
                    startRecording(); 
                } 
            });
        }
        
        @JavascriptInterface
        public void stopListen() {
            new Thread(new Runnable() { 
                public void run() { 
                    stopRecording(); 
                } 
            }).start();
        }
        
        @JavascriptInterface
        public boolean nativeReady() {
            return modelReady;
        }
    }

    private void startRecording() {
        // Проверка разрешений
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("no_permission");
            return;
        }
        
        if (!modelReady) {
            status("model_not_ready");
            return;
        }
        
        if (listening) return;
        listening = true;
        
        try {
            recognizer = new Recognizer(model, 16000.0f);
        } catch (Exception e) {
            Log.e(TAG, "Recognizer init error: " + e);
            listening = false;
            status("recognizer_error");
            return;
        }
        
        int bufferSize = AudioRecord.getMinBufferSize(16000, 
            AudioFormat.CHANNEL_IN_MONO, 
            AudioFormat.ENCODING_PCM_16BIT);
            
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size");
            listening = false;
            status("audio_error");
            return;
        }
        
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            
            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                listening = false;
                status("audio_error");
                return;
            }
            
            recorder.startRecording();
            recordingReady = true;
            
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord error: " + e);
            listening = false;
            status("audio_error");
            return;
        }
        
        recThread = new Thread(new Runnable() {
            public void run() {
                short[] buf = new short[1600];
                try {
                    while (listening && recordingReady) {
                        int n = recorder.read(buf, 0, buf.length);
                        if (n <= 0) {
                            if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                                Log.e(TAG, "AudioRecord read error: " + n);
                                break;
                            }
                            continue;
                        }
                        
                        boolean fin = recognizer.acceptWaveForm(buf, n);
                        String raw = fin ? recognizer.getResult() : recognizer.getPartialResult();
                        JSONObject j = new JSONObject(raw);
                        final String text = (fin ? j.optString("text", "") : j.optString("partial", "")).trim();
                        final boolean isFinal = fin;
                        
                        if (!text.isEmpty()) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    try {
                                        if (isFinal) {
                                            web.evaluateJavascript("window.onMironStream&&window.onMironStream(" + jsQuote(text) + ",true)", null);
                                            web.evaluateJavascript("window.onMironResult&&window.onMironResult(" + jsQuote(text) + ")", null);
                                        } else {
                                            web.evaluateJavascript("window.onMironStream&&window.onMironStream(" + jsQuote(text) + ",false)", null);
                                        }
                                    } catch (Exception e) {
                                        Log.e(TAG, "JS eval error: " + e);
                                    }
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "rec loop: " + e);
                } finally {
                    recordingReady = false;
                }
            }
        });
        recThread.start();
        status("listening");
    }

    private void stopRecording() {
        if (!listening) return;
        listening = false;
        
        final Recognizer r = recognizer;
        recognizer = null;
        
        try {
            if (r != null) {
                JSONObject j = new JSONObject(r.getFinalResult());
                final String text = j.optString("text", "").trim();
                if (!text.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try {
                                web.evaluateJavascript("window.onMironResult&&window.onMironResult(" + jsQuote(text) + ")", null);
                            } catch (Exception e) {
                                Log.e(TAG, "JS eval error: " + e);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "final: " + e);
        }
        
        if (recorder != null) {
            try {
                if (recordingReady) {
                    recorder.stop();
                }
                recorder.release();
            } catch (Exception e) {
                Log.e(TAG, "recorder release: " + e);
            }
            recorder = null;
            recordingReady = false;
        }
        
        status("stopped");
    }

    private String jsQuote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 32) sb.append("\\u").append(String.format("%04x", (int) c));
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        listening = false;
        recordingReady = false;
        
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception e) {}
            recorder = null;
        }
        
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception e) {}
        }
        
        if (web != null) {
            try {
                web.clearHistory();
                web.clearCache(true);
                web.loadUrl("about:blank");
                web.removeAllViews();
            } catch (Exception e) {}
        }
        
        super.onDestroy();
    }
}
