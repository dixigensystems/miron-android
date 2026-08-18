package ru.dixigen.miron;
import android.Manifest;import android.app.Activity;import android.content.Intent;import android.content.pm.PackageManager;import android.net.Uri;import android.os.Bundle;import android.speech.tts.TextToSpeech;import android.webkit.*;import java.util.Locale;
public class MainActivity extends Activity implements TextToSpeech.OnInitListener{
private WebView web;private TextToSpeech tts;
protected void onCreate(Bundle b){super.onCreate(b);web=new WebView(this);setContentView(web);web.clearCache(true);CookieManager.getInstance().setAcceptCookie(true);tts=new TextToSpeech(this,this);
WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(false);s.setCacheMode(WebSettings.LOAD_DEFAULT);
web.addJavascriptInterface(new Bridge(),"Miron");
web.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,String u){if(!u.contains("miron.dixigen.ru")){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)));return true;}return false;}
public void onPageFinished(WebView v,String u){v.evaluateJavascript("var x=document.createElement('script');x.src='https://miron.dixigen.ru/agent/mshim.js';document.head.appendChild(x);",null);}});
web.setWebChromeClient(new WebChromeClient(){public void onPermissionRequest(final PermissionRequest r){runOnUiThread(new Runnable(){public void run(){r.grant(r.getResources());}});}});
String[] p={Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA};boolean need=false;for(String x:p)if(checkSelfPermission(x)!=PackageManager.PERMISSION_GRANTED)need=true;
if(need)requestPermissions(p,77);web.loadUrl("https://miron.dixigen.ru/");}
public void onInit(int st){if(st==TextToSpeech.SUCCESS){tts.setLanguage(new Locale("ru"));tts.setPitch(0.85f);}}
public class Bridge{@JavascriptInterface public void speak(String t){if(tts!=null)tts.speak(t,TextToSpeech.QUEUE_FLUSH,null,"m1");}
@JavascriptInterface public void stopSpeak(){if(tts!=null)tts.stop();}}
public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
protected void onDestroy(){if(tts!=null)tts.shutdown();super.onDestroy();}}
