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
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
private WebView web;
private TextToSpeech tts;
protected void onCreate(Bundle b){
super.onCreate(b);
webweb=new WebView(this);
setContentView(web);
tts=new TextToSpeech(this,this);
WebSettings s=web.getSettings();
s.setJavaScriptEnabled(true);
s.setDomStorageEnabled(true);
s.setMediaPlaybackRequiresUserGesture(false);
web.addJavascriptInterface(new Bridge(),"Miron");
web.setWebViewClient(new WebViewClient(){
public boolean shouldOverrideUrlLoading(WebView v,String u){
if(!u.contains("miron.dixigen.ru")){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)));return true;}
return false;}
public void onPageFinished(WebView v,String u){
v.evaluateJavascript("var x=document.createElement('script');x.src='
