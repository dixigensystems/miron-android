package ru.dixigen.miron;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.webkit.*;
import java.util.Locale;
public class MainActivity extends Activity
implements TextToSpeech.OnInitListener {
private WebView web;
private TextToSpeech tts;
protected void onCreate(Bundle b){
super.onCreate(b);
web=new WebView(this);
setContentView(web);
tts=new TextToSpeech(this,this);
WebSettings s=web
