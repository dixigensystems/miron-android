package ru.dixigen.miron;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.webkit.*;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        web.clearCache(true);
        CookieManager.getInstance().setAcceptCookie(true);
        tts = new TextToSpeech(this, this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "Miron");

        web.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                if (!u.contains("miron.dixigen.ru")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                    return true;
                }
                return false;
            }
            public void onPageFinished(WebView v, String u) {
                v.evaluateJavascript("var x=document.createElement('script');x.src='https://miron.dixigen.ru/agent/mshim.js';document.head.appendChild(x);", null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(new Runnable() {
                    public void run() { r.grant(r.getResources()); }
                });
            }
        });

        String[] p = { Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA };
        boolean need = false;
        for (String x : p) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) need = true;
        if (need) requestPermissions(p, 77);

           web.loadUrl("https://miron.dixigen.ru/go.php");

        new Thread(new Runnable() { public void run() { prepareModel(); } }).start();
    }

    private void status(final String st) {
        runOnUiThread(new Runnable() {
            public void run() {
                web.evaluateJavascript("window.onMironStatus&&window.onMironStatus('" + st + "')", null);
            }
        });
    }

    private void prepareModel() {
        try {
            File dir = new File(getFilesDir(), MODEL_DIR);
            if (!dir.exists()) {
                status("model_downloading");
                File zip = new File(getCacheDir(), "vosk_model.zip");
                download(MODEL_URL, zip);
                unzip(zip, getFilesDir());
                zip.delete();
                File extracted = null;
                File[] kids = getFilesDir().listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        if (k.isDirectory() && k.getName().startsWith("vosk-model")) { extracted = k; break; }
                    }
                }
                if (extracted != null && !extracted.getName().equals(MODEL_DIR)) {
                    extracted.renameTo(dir);
                }
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

    private void download(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        InputStream in = c.getInputStream();
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
        c.disconnect();
    }

    private void unzip(File zip, File destDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null) {
            File out = new File(destDir, e.getName());
            if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath())) { zis.closeEntry(); continue; }
            if (e.isDirectory()) { out.mkdirs(); zis.closeEntry(); continue; }
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            zis.closeEntry();
        }
        zis.close();
    }

    @Override
    public void onInit(int st) {
        if (st == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("ru"));
            tts.setPitch(0.85f);
        }
    }

    public class Bridge {
        @JavascriptInterface
        public void speak(String t) {
            if (tts != null) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m1");
        }
        @JavascriptInterface
        public void stopSpeak() {
            if (tts != null) tts.stop();
        }
        @JavascriptInterface
        public void startListen() {
            runOnUiThread(new Runnable() { public void run() { startRecording(); } });
        }
        @JavascriptInterface
        public void stopListen() {
            new Thread(new Runnable() { public void run() { stopRecording(); } }).start();
        }
        @JavascriptInterface
        public boolean nativeReady() {
            return modelReady;
        }
    }

    private void startRecording() {
        if (!modelReady) { status("model_not_ready"); return; }
        if (listening) return;
        listening = true;
        try {
            recognizer = new Recognizer(model, 16000.0f);
        } catch (Exception e) {
            Log.e(TAG, "Recognizer: " + e);
            listening = false;
            return;
        }
        int bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            recorder.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord: " + e);
            listening = false;
            return;
        }
        recThread = new Thread(new Runnable() {
            public void run() {
                short[] buf = new short[1600];
                try {
                    while (listening) {
                        int n = recorder.read(buf, 0, buf.length);
                        if (n <= 0) break;
                        boolean fin = recognizer.acceptWaveForm(buf, n);
                        String raw = fin ? recognizer.getResult() : recognizer.getPartialResult();
                        JSONObject j = new JSONObject(raw);
                        final String text = (fin ? j.optString("text", "") : j.optString("partial", "")).trim();
                        final boolean isFinal = fin;
                        if (!text.isEmpty()) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (isFinal) {
                                        web.evaluateJavascript("window.onMironStream&&window.onMironStream(" + jsQuote(text) + ",true)", null);
                                        web.evaluateJavascript("window.onMironResult&&window.onMironResult(" + jsQuote(text) + ")", null);
                                    } else {
                                        web.evaluateJavascript("window.onMironStream&&window.onMironStream(" + jsQuote(text) + ",false)", null);
                                    }
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "rec loop: " + e);
                }
            }
        });
        recThread.start();
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
                            web.evaluateJavascript("window.onMironResult&&window.onMironResult(" + jsQuote(text) + ")", null);
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "final: " + e);
        }
        if (recorder != null) {
            try { recorder.stop(); recorder.release(); } catch (Exception e) {}
            recorder = null;
        }
    }

    private String jsQuote(String s) {
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
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        listening = false;
        if (recorder != null) { try { recorder.release(); } catch (Exception e) {} }
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
