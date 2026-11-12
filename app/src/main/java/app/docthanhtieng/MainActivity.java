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
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private Player player;
    private Store store;
    private SharedPreferences sp;
    private static final int REQ_FILES = 7;
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
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        List<String[]> files = new ArrayList<>();
        for (Uri u : uris) {
            String text = readText(u);
            if (text.trim().isEmpty()) continue;
            String name = fileName(u).replaceFirst("(?i)\\.(md|markdown|txt)$", "");
            files.add(new String[]{name, text});
        }
        Collections.sort(files, (x, y) -> natural(x[0], y[0]));
        for (String[] f : files) store.add(f[0], f[1]);
        push();
    }

    private String fileName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "" : last;
    }

    private String readText(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) break;
            }
            String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (s.startsWith("\uFEFF")) s = s.substring(1);
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /** Orders "Chapter 2" before "Chapter 10". */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String da = a.substring(si, i).replaceFirst("^0+(?=\\d)", "");
                String db = b.substring(sj, j).replaceFirst("^0+(?=\\d)", "");
                if (da.length() != db.length()) return da.length() - db.length();
                int c = da.compareTo(db);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
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
        public void seek(String id, int index) {
            runOnUiThread(() -> player.seek(id, index));
        }

        @JavascriptInterface
        public void pickFiles() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, REQ_FILES);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void setSleep(String mode) {
            runOnUiThread(() -> player.setSleep(mode));
        }

        @JavascriptInterface
        public void setAutoLang(boolean on) {
            runOnUiThread(() -> player.setAutoLang(on));
        }

        @JavascriptInterface
        public void setClean(boolean on) {
            runOnUiThread(() -> player.setClean(on));
        }

        @JavascriptInterface
        public void setRepl(String json) {
            runOnUiThread(() -> player.setRepl(json));
        }

        @JavascriptInterface
        public void toggleMark(String id, int index) {
            store.toggleMark(id, index);
            push();
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
