package ru.dixigen.miron;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.webkit.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.FeatConfig;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final String TAG = "Miron";
    private static final String MODEL_DIR = "sherpa_model";
    private static final String ENCODER = "encoder.int8.onnx";
    private static final String DECODER = "decoder.int8.onnx";
    private static final String JOINER  = "joiner.int8.onnx";
    private static final String TOKENS  = "tokens.txt";

    private WebView web;
    private TextToSpeech tts;
    private OnlineRecognizer recognizer;
    private AudioRecord recorder;
    private Thread recThread;
    private volatile boolean listening = false;
    private OnlineStream stream;
    private boolean modelReady = false;

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
                    public void run() {
                        r.grant(r.getResources());
                    }
                });
            }
        });

        String[] p = { Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA };
        boolean need = false;
        for (String x : p) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) need = true;
        if (need) requestPermissions(p, 77);

        web.loadUrl("https://miron.dixigen.ru/");

        new Thread(new Runnable() {
            public void run() { prepareModel(); }
        }).start();
    }

    private void prepareModel() {
        try {
            File dir = new File(getFilesDir(), MODEL_DIR);
            File fEnc = new File(dir, ENCODER);
            if (!fEnc.exists()) {
                dir.mkdirs();
                AssetManager am = getAssets();
                String[] files = am.list(MODEL_DIR);
                if (files == null) {
                    Log.e(TAG, "Папка с моделью не найдена в assets");
                    return;
                }
                for (String f : files) {
                    InputStream in = am.open(MODEL_DIR + "/" + f);
                    File out = new File(dir, f);
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    in.close();
                    os.close();
                }
            }
            OnlineModelConfig mc = new OnlineModelConfig();
            OnlineTransducerModelConfig tc = new OnlineTransducerModelConfig();
            tc.setEncoder(new File(dir, ENCODER).getAbsolutePath());
            tc.setDecoder(new File(dir, DECODER).getAbsolutePath());
            tc.setJoiner(new File(dir, JOINER).getAbsolutePath());
            mc.setTransducer(tc);
            mc.setTokens(new File(dir, TOKENS).getAbsolutePath());
            mc.setNumThreads(2);

            OnlineRecognizerConfig rc = new OnlineRecognizerConfig();
            rc.setModel(mc);
            rc.setFeat(new FeatConfig());
            rc.setSampleRate(16000);
            rc.setFeatureDim(80);
            rc.setEnableEndpoint(true);
            rc.setRule1MinTrailingSilence(2.4f);
            rc.setRule2MinTrailingSilence(1.2f);
            rc.setRule3MinUtteranceLength(20f);
            rc.setDecodingMethod("greedy_search");

            recognizer = new OnlineRecognizer(rc);
            modelReady = true;
            Log.i(TAG, "Модель готова");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка подготовки модели: " + e.getMessage());
        }
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
            runOnUiThread(new Runnable() {
                public void run() { startRecording(); }
            });
        }
        @JavascriptInterface
        public void stopListen() {
            runOnUiThread(new Runnable() {
                public void run() { stopRecording(); }
            });
        }
    }

    private void startRecording() {
        if (!modelReady) {
            Log.w(TAG, "Модель ещё не готова");
            return;
        }
        if (listening) return;
        listening = true;
        stream = recognizer.createStream();

        int bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            recorder.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "Не удалось запустить AudioRecord: " + e.getMessage());
            listening = false;
            return;
        }

        recThread = new Thread(new Runnable() {
            public void run() {
                short[] buf = new short[1600];
                String lastTmp = "";
                while (listening) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n <= 0) break;
                    float[] f = new float[n];
                    for (int i = 0; i < n; i++) f[i] = buf[i] / 32768.0f;
                    stream.acceptWaveform(f, 16000);
                    while (recognizer.isReady(stream)) recognizer.decode(stream);
                    final OnlineRecognizerResult r = recognizer.getResult(stream);
                    if (r != null && r.getText() != null && !r.getText().isEmpty()) {
                        final String tmp = r.getText().trim();
                        final boolean isEndpoint = recognizer.isEndpoint(stream);
                        if (!tmp.equals(lastTmp) || isEndpoint) {
                            lastTmp = tmp;
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    String js = "window.onMironStream&&window.onMironStream(" + jsQuote(tmp) + "," + isEndpoint + ")";
                                    web.evaluateJavascript(js, null);
                                    if (isEndpoint) {
                                        recognizer.reset(stream);
                                        web.evaluateJavascript("window.onMironResult&&window.onMironResult(" + jsQuote(tmp) + ")", null);
                                    }
                                }
                            });
                        }
                    }
                }
            }
        });
        recThread.start();
    }

    private void stopRecording() {
        listening = false;
        if (recorder != null) {
            try { recorder.stop(); recorder.release(); } catch (Exception e) {}
            recorder = null;
        }
        stream = null;
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
        stopRecording();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
