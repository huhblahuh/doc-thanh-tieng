package app.docthanhtieng;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private Player player;
    private Store store;
    private SharedPreferences sp;
    private boolean pageReady;
    private final Player.Listener listener = this::push;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        player = Player.get(this);
        store = Store.get(this);
        sp = getSharedPreferences("ui", Context.MODE_PRIVATE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                push();
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        intent.setAction(Intent.ACTION_MAIN);
        if (text == null) return;
        store.add(subject, text.toString());
        push();
    }

    @Override
    protected void onStart() {
        super.onStart();
        player.ui = listener;
        push();
    }

    @Override
    protected void onStop() {
        if (player.ui == listener) player.ui = null;
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        push();
    }

    private boolean systemDark() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private void push() {
        runOnUiThread(() -> {
            if (!pageReady) return;
            web.evaluateJavascript("window.onNative&&window.onNative(" + player.stateJson(systemDark()) + ")", null);
        });
    }

    @Override
    public void onBackPressed() {
        if (!pageReady) {
            moveTaskToBack(true);
            return;
        }
        web.evaluateJavascript("window.onBack?window.onBack():false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    public final class Bridge {
        @JavascriptInterface
        public String getState() {
            return player.stateJson(systemDark());
        }

        @JavascriptInterface
        public String getSegments(String id) {
            JSONArray a = new JSONArray();
            List<Splitter.Seg> segs = Splitter.split(store.text(id));
            for (Splitter.Seg s : segs) {
                JSONArray one = new JSONArray();
                one.put(s.t);
                one.put(s.p);
                a.put(one);
            }
            return a.toString();
        }

        @JavascriptInterface
        public String addItem(String title, String text) {
            String id = store.add(title, text);
            push();
            return id == null ? "" : id;
        }

        @JavascriptInterface
        public void deleteItem(String id) {
            runOnUiThread(() -> {
                store.delete(id);
                player.deleted(id);
            });
        }

        @JavascriptInterface
        public void moveItem(String id, int delta) {
            store.move(id, delta);
            push();
        }

        @JavascriptInterface
        public void play(String id, int index) {
            runOnUiThread(() -> player.play(id, index));
        }

        @JavascriptInterface
        public void toggle() {
            runOnUiThread(() -> player.toggle());
        }

        @JavascriptInterface
        public void prevSentence() {
            runOnUiThread(() -> player.prevSentence());
        }

        @JavascriptInterface
        public void nextSentence() {
            runOnUiThread(() -> player.nextSentence());
        }

        @JavascriptInterface
        public void nextItem() {
            runOnUiThread(() -> player.nextItem());
        }

        @JavascriptInterface
        public void setRate(float r) {
            runOnUiThread(() -> player.setRate(r));
        }

        @JavascriptInterface
        public void setVoice(String name) {
            runOnUiThread(() -> player.setVoice(name));
        }

        @JavascriptInterface
        public String getPrefs() {
            return sp.getString("prefs", "{}");
        }

        @JavascriptInterface
        public void setPrefs(String json) {
            sp.edit().putString("prefs", json).apply();
        }

        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData d = cm.getPrimaryClip();
                if (d == null || d.getItemCount() == 0) return "";
                CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void setBars(String color, boolean light) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(color);
                    getWindow().setStatusBarColor(c);
                    getWindow().setNavigationBarColor(c);
                    View d = getWindow().getDecorView();
                    int f = d.getSystemUiVisibility();
                    int bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    d.setSystemUiVisibility(light ? (f | bits) : (f & ~bits));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void installVoice() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }
}
