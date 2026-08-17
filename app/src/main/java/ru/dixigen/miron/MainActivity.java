package ru.dixigen.miron;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private WebView web;
    private TextToSpeech tts;

    private static final String HOOK = 
        "(function(){" +
        "if(!window.Miron||window.__hooked)return;window.__hooked=1;" +
        "window.speak=function(t){var v=document.getElementById('voiceToggle');if(v&&v.classList.contains('off'))return;window.Miron.speak(t);};" +
        "var vt=document.getElementById('voiceToggle');" +
        "if(vt)vt.addEventListener('click',function(){window.Miron.stopSpeak();});" +
        "if(!(window.SpeechRecognition||window.webkitSpeechRecognition)){" +
        "var mic=document.getElementById('mic');" +
        "if(mic){mic.style.display='';mic.onclick=function(){mic.classList.add('rec');window.Miron.listen();};}" +
        "window.onNativeVoice=function(t){if(mic)mic.classList.remove('rec');if(window.ask)ask(t);};" +
        "}" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        tts = new TextToSpeech(this, this);

        var s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);

        web.addJavascriptInterface(new Bridge(), "Miron");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (!url.contains("miron.dixigen.ru")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(HOOK, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> r.grant(r.getResources()));
            }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 77);
        }

        web.loadUrl("https://miron.dixigen.ru/");
    }

    @Override
    public void onInit(int status) {
        // TTS готов
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 88 && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                String t = r.get(0).replace("\\", "\\\\").replace("'", "\\'");
                web.evaluateJavascript("if(window.onNativeVoice)onNativeVoice('" + t + "')", null);
            }
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "m1");
        }
        @JavascriptInterface
        public void stopSpeak() {
            if (tts != null) tts.stop();
        }
        @JavascriptInterface
        public void listen() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 77);
                    return;
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Слушаю…");
                try { startActivityForResult(i, 88); } catch (Exception e) { }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
