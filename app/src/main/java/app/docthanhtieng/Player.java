package app.docthanhtieng;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the speech engine and walks the queue. Lives as long as the app process. */
final class Player {
    interface Listener {
        void onChange();
    }

    private static Player inst;

    static synchronized Player get(Context c) {
        if (inst == null) inst = new Player(c.getApplicationContext());
        return inst;
    }

    final Context app;
    final Store store;
    final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;
    private final AudioAttributes attrs;
    private final AudioManager am;
    private final AudioFocusRequest afr;
    private final PowerManager.WakeLock wl;
    final MediaSession session;

    private TextToSpeech tts;
    private boolean ready;
    private boolean viMissing;
    private Runnable pending;
    private JSONArray voicesJson = new JSONArray();

    private String curId;
    private String loadedId;
    private List<Splitter.Seg> segs = new ArrayList<>();
    private int index;
    private boolean playing;
    private boolean ended;
    private boolean resumeOnGain;
    private int gen;
    private int enq;
    private float rate;
    private String voice;
    private boolean autoLang;
    private String activeLang;
    private String sleepMode = "off";
    private long sleepUntil;
    private final Runnable sleepTask;
    private final List<Pattern> replPat = new ArrayList<>();
    private final List<String> replTo = new ArrayList<>();
    private String replJson = "[]";

    Listener ui;
    Listener svc;

    private Player(Context c) {
        app = c;
        store = Store.get(c);
        sp = c.getSharedPreferences("player", Context.MODE_PRIVATE);
        rate = sp.getFloat("rate", 1f);
        voice = sp.getString("voice", "");
        autoLang = sp.getBoolean("autoLang", true);
        Splitter.clean = sp.getBoolean("clean", true);
        loadRepl(sp.getString("repl", "[]"));
        sleepTask = () -> {
            sleepMode = "off";
            pause();
        };
        curId = sp.getString("cur", null);
        Store.Item it = store.find(curId);
        if (it == null) curId = null;
        else index = it.pos;

        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(this::onFocus, main)
                .build();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "docthanhtieng:read");
        wl.setReferenceCounted(false);

        session = new MediaSession(c, "docthanhtieng");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (!playing) toggle();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                nextItem();
            }

            @Override
            public void onSkipToPrevious() {
                prevSentence();
            }
        });
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(app, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS) {
                viMissing = true;
                changed();
                return;
            }
            int r = tts.setLanguage(new Locale("vi", "VN"));
            viMissing = r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setAudioAttributes(attrs);
            tts.setSpeechRate(rate);
            loadVoices();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    main.post(() -> started(id));
                }

                @Override
                public void onDone(String id) {
                    main.post(() -> done(id));
                }

                @Override
                public void onError(String id) {
                    main.post(() -> failed(id));
                }

                @Override
                public void onError(String id, int code) {
                    main.post(() -> failed(id));
                }
            });
            ready = true;
            if (pending != null) {
                Runnable p = pending;
                pending = null;
                p.run();
            }
            changed();
        }));
    }

    private void loadVoices() {
        JSONArray a = new JSONArray();
        try {
            Set<Voice> all = tts.getVoices();
            List<Voice> vi = new ArrayList<>();
            if (all != null) {
                for (Voice v : all) {
                    if (v.getLocale() != null && "vi".equalsIgnoreCase(v.getLocale().getLanguage())) vi.add(v);
                }
            }
            Collections.sort(vi, (x, y) -> x.getName().compareTo(y.getName()));
            for (Voice v : vi) {
                JSONObject o = new JSONObject();
                o.put("name", v.getName());
                o.put("net", v.isNetworkConnectionRequired());
                a.put(o);
            }
            if (!vi.isEmpty()) viMissing = false;
        } catch (Exception ignored) {
        }
        voicesJson = a;
    }

    private static int[] parse(String id) {
        try {
            int c = id.indexOf(':');
            return new int[]{Integer.parseInt(id.substring(0, c)), Integer.parseInt(id.substring(c + 1))};
        } catch (Exception e) {
            return new int[]{-1, -1};
        }
    }

    private void started(String id) {
        int[] k = parse(id);
        if (k[0] != gen || !playing) return;
        index = k[1];
        store.setPos(curId, index, false);
        while (enq < index + 2 && enq + 1 < segs.size()) enqueue(enq + 1, TextToSpeech.QUEUE_ADD);
        changed();
    }

    private void done(String id) {
        int[] k = parse(id);
        if (k[0] != gen || !playing) return;
        if (k[1] >= segs.size() - 1) finishItem();
    }

    private void failed(String id) {
        int[] k = parse(id);
        if (k[0] != gen || !playing) return;
        pause();
    }

    private void loadRepl(String json) {
        replPat.clear();
        replTo.clear();
        JSONArray keep = new JSONArray();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray one = a.getJSONArray(i);
                String from = one.optString(0, "").trim();
                String to = one.optString(1, "").trim();
                if (from.isEmpty()) continue;
                replPat.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(from) + "(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                replTo.add(Matcher.quoteReplacement(to));
                JSONArray k = new JSONArray();
                k.put(from);
                k.put(to);
                keep.put(k);
            }
        } catch (Exception ignored) {
        }
        replJson = keep.toString();
    }

    private String speakText(String s) {
        for (int i = 0; i < replPat.size(); i++) s = replPat.get(i).matcher(s).replaceAll(replTo.get(i));
        return s.trim();
    }

    private void applyVoice() {
        if (voice.isEmpty()) return;
        try {
            Set<Voice> all = tts.getVoices();
            if (all == null) return;
            for (Voice v : all) {
                if (v.getName().equals(voice)) {
                    tts.setVoice(v);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void applyLang(Store.Item it) {
        String lang = "vi";
        if (it != null && autoLang) {
            lang = it.lang;
            if (lang == null || lang.isEmpty()) {
                lang = Splitter.detect(store.text(it.id));
                store.setLang(it.id, lang);
            }
        }
        if (lang.equals(activeLang)) return;
        try {
            tts.setLanguage("en".equals(lang) ? Locale.US : new Locale("vi", "VN"));
            if ("vi".equals(lang)) applyVoice();
        } catch (Exception ignored) {
        }
        activeLang = lang;
    }

    private void enqueue(int i, int mode) {
        if (i < 0 || i >= segs.size()) return;
        tts.speak(speakText(segs.get(i).t), mode, null, gen + ":" + i);
        enq = i;
    }

    private void speakFrom(int i) {
        gen++;
        tts.stop();
        tts.setSpeechRate(rate);
        enq = i - 1;
        enqueue(i, TextToSpeech.QUEUE_FLUSH);
        enqueue(i + 1, TextToSpeech.QUEUE_ADD);
    }

    private void ensureLoaded(String id) {
        if (id.equals(loadedId)) return;
        segs = Splitter.split(store.text(id));
        loadedId = id;
    }

    private void setCurrent(String id, int i) {
        curId = id;
        index = i;
        sp.edit().putString("cur", id).apply();
    }

    void play(String id, int i) {
        if (store.find(id) == null) return;
        if (!ready) {
            pending = () -> play(id, i);
            return;
        }
        ensureLoaded(id);
        ended = false;
        if (segs.isEmpty()) {
            setCurrent(id, 0);
            playing = true;
            finishItem();
            return;
        }
        int at = Math.max(0, Math.min(segs.size() - 1, i));
        setCurrent(id, at);
        store.setPos(id, at, false);
        playing = true;
        resumeOnGain = false;
        am.requestAudioFocus(afr);
        if (!wl.isHeld()) wl.acquire(6 * 60 * 60 * 1000L);
        session.setActive(true);
        applyLang(store.find(id));
        try {
            app.startForegroundService(new Intent(app, ReaderService.class));
        } catch (Exception ignored) {
        }
        speakFrom(at);
        changed();
    }

    private void halt(boolean keepFocus) {
        playing = false;
        gen++;
        if (tts != null) tts.stop();
        if (wl.isHeld()) wl.release();
        if (!keepFocus) am.abandonAudioFocusRequest(afr);
        changed();
    }

    void pause() {
        resumeOnGain = false;
        halt(false);
    }

    void toggle() {
        if (playing) {
            pause();
            return;
        }
        Store.Item it = store.find(curId);
        if (it == null) it = store.first();
        if (it == null) return;
        play(it.id, it.id.equals(curId) ? index : it.pos);
    }

    void prevSentence() {
        step(-1);
    }

    void nextSentence() {
        step(1);
    }

    private void step(int d) {
        if (store.find(curId) == null) return;
        ensureLoaded(curId);
        if (segs.isEmpty()) return;
        int i = Math.max(0, Math.min(segs.size() - 1, index + d));
        if (playing) {
            play(curId, i);
        } else {
            index = i;
            store.setPos(curId, i, false);
            changed();
        }
    }

    void seek(String id, int i) {
        Store.Item it = store.find(id);
        if (it == null) return;
        if (playing) {
            play(id, i);
            return;
        }
        int at = Math.max(0, Math.min(Math.max(0, it.count - 1), i));
        setCurrent(id, at);
        store.setPos(id, at, false);
        changed();
    }

    void nextItem() {
        Store.Item n = store.after(curId);
        if (n == null) return;
        if (playing) {
            play(n.id, n.pos);
        } else {
            setCurrent(n.id, n.pos);
            changed();
        }
    }

    private void finishItem() {
        String was = curId;
        store.setPos(was, 0, true);
        Store.Item n = store.after(was);
        if ("end".equals(sleepMode)) {
            sleepMode = "off";
            if (n != null) setCurrent(n.id, n.done ? 0 : n.pos);
            else index = 0;
            pause();
            return;
        }
        if (n != null) {
            play(n.id, n.done ? 0 : n.pos);
            return;
        }
        index = 0;
        ended = true;
        pause();
    }

    void deleted(String id) {
        if (id != null && id.equals(curId)) {
            pause();
            curId = null;
            loadedId = null;
            segs = new ArrayList<>();
            index = 0;
            sp.edit().remove("cur").apply();
        }
        changed();
    }

    void setRate(float r) {
        rate = Math.max(0.5f, Math.min(4f, r));
        sp.edit().putFloat("rate", rate).apply();
        if (ready && playing) speakFrom(index);
        changed();
    }

    void setVoice(String name) {
        voice = name == null ? "" : name;
        sp.edit().putString("voice", voice).apply();
        activeLang = null;
        if (ready && playing) {
            applyLang(store.find(curId));
            speakFrom(index);
        }
        changed();
    }

    void setSleep(String mode) {
        main.removeCallbacks(sleepTask);
        sleepMode = mode == null ? "off" : mode;
        if (!"off".equals(sleepMode) && !"end".equals(sleepMode)) {
            try {
                long ms = Integer.parseInt(sleepMode) * 60000L;
                sleepUntil = SystemClock.elapsedRealtime() + ms;
                main.postDelayed(sleepTask, ms);
            } catch (Exception e) {
                sleepMode = "off";
            }
        }
        changed();
    }

    void setAutoLang(boolean on) {
        autoLang = on;
        sp.edit().putBoolean("autoLang", on).apply();
        activeLang = null;
        if (ready && playing) {
            applyLang(store.find(curId));
            speakFrom(index);
        }
        changed();
    }

    void setClean(boolean on) {
        Splitter.clean = on;
        sp.edit().putBoolean("clean", on).apply();
        store.recount();
        loadedId = null;
        if (playing && curId != null) play(curId, index);
        else changed();
    }

    void setRepl(String json) {
        loadRepl(json);
        sp.edit().putString("repl", replJson).apply();
        if (ready && playing) speakFrom(index);
        changed();
    }

    private void onFocus(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnGain && !playing) {
                resumeOnGain = false;
                toggle();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (playing) {
                halt(true);
                resumeOnGain = true;
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            if (playing) pause();
        }
    }

    boolean isPlaying() {
        return playing;
    }

    boolean hasEnded() {
        return ended;
    }

    String title() {
        Store.Item it = store.find(curId);
        return it == null ? "" : it.title;
    }

    String progress() {
        Store.Item it = store.find(curId);
        if (it == null || it.count == 0) return "";
        return "Câu " + Math.min(it.count, index + 1) + "/" + it.count;
    }

    void changed() {
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title())
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, progress())
                    .build());
        } catch (Exception ignored) {
        }
        if (ui != null) ui.onChange();
        if (svc != null) svc.onChange();
    }

    String stateJson(boolean systemDark) {
        JSONObject o = new JSONObject();
        try {
            o.put("items", store.toJson());
            o.put("currentId", curId == null ? JSONObject.NULL : curId);
            o.put("index", index);
            o.put("playing", playing);
            o.put("rate", rate);
            o.put("voice", voice);
            o.put("voices", voicesJson);
            o.put("viMissing", viMissing);
            o.put("ready", ready);
            o.put("systemDark", systemDark);
            o.put("autoLang", autoLang);
            o.put("clean", Splitter.clean);
            o.put("repl", new JSONArray(replJson));
            o.put("sleepMode", sleepMode);
            long left = sleepUntil - SystemClock.elapsedRealtime();
            o.put("sleepLeft", left > 0 ? (int) Math.ceil(left / 60000.0) : 0);
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}
