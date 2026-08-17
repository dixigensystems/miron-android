package ru.dixigen.miron;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private WebView web;
    private TextToSpeech tts;

    private static final String SHIM =
        "(function(){" +
        "if(window.__mshim||!window.Miron)return;window.__mshim=1;" +
        "function Ut(t){this.text=t||'';this.lang='ru-RU';this.onstart=null;this.onend=null;this.onerror=null;}" +
        "window.SpeechSynthesisUtterance=Ut;" +
        "window.speechSynthesis={" +
        "speaking:false,pending:false,paused:false
