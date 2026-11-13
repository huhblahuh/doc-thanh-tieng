package app.docthanhtieng;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** The queue: a small index file plus one text file per item. */
final class Store {
    static final class Item {
        String id;
        String title;
        int len;
        int count;
        int pos;
        boolean done;
        String lang = "";
        List<Integer> marks = new ArrayList<>();
    }

    private static Store inst;

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private final File dir;
    private final File indexFile;
    private final List<Item> items = new ArrayList<>();

    /** One step the user can take back: a change to the queue, or a jump to another sentence. */
    static final class Step {
        String kind;
        String label;
        JSONArray before;
        JSONArray after;
        String fromId;
        int from;
        String toId;
        int to;
    }

    private static final int MAX_STEPS = 60;
    private final List<Step> past = new ArrayList<>();
    private final List<Step> future = new ArrayList<>();

    private Store(Context c) {
        dir = new File(c.getFilesDir(), "texts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        indexFile = new File(c.getFilesDir(), "queue.json");
        load();
        cleanup();
    }

    /** Text files left behind by removed items are deleted at the next start. */
    private void cleanup() {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(".txt")) continue;
                if (find(n.substring(0, n.length() - 4)) == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void record(JSONArray before, String label) {
        Step s = new Step();
        s.kind = "list";
        s.label = label;
        s.before = before;
        s.after = toJson();
        push(s);
    }

    private void push(Step s) {
        past.add(s);
        while (past.size() > MAX_STEPS) past.remove(0);
        future.clear();
    }

    synchronized void recordJump(String fromId, int from, String toId, int to) {
        if (fromId == null || toId == null) return;
        if (fromId.equals(toId) && from == to) return;
        Step s = new Step();
        s.kind = "jump";
        s.label = "Tua";
        s.fromId = fromId;
        s.from = from;
        s.toId = toId;
        s.to = to;
        push(s);
    }

    synchronized boolean canUndo() {
        return !past.isEmpty();
    }

    synchronized boolean canRedo() {
        return !future.isEmpty();
    }

    /** Takes back the last step. A jump is handed back for the player to carry out. */
    synchronized Step undo() {
        if (past.isEmpty()) return null;
        Step s = past.remove(past.size() - 1);
        if ("list".equals(s.kind)) restore(s.before);
        future.add(s);
        return s;
    }

    synchronized Step redo() {
        if (future.isEmpty()) return null;
        Step s = future.remove(future.size() - 1);
        if ("list".equals(s.kind)) restore(s.after);
        past.add(s);
        return s;
    }

    /** Puts the queue back in a saved order. Reading progress of items still present is kept. */
    private void restore(JSONArray snap) {
        List<Item> next = new ArrayList<>();
        try {
            for (int i = 0; i < snap.length(); i++) {
                JSONObject o = snap.getJSONObject(i);
                String id = o.getString("id");
                if (!new File(dir, id + ".txt").exists()) continue;
                Item it = find(id);
                if (it == null) {
                    it = new Item();
                    it.id = id;
                    it.title = o.optString("title", "");
                    it.len = o.optInt("len", 0);
                    it.count = o.optInt("count", 0);
                    it.pos = o.optInt("pos", 0);
                    it.done = o.optBoolean("done", false);
                    it.lang = o.optString("lang", "");
                }
                it.marks = new ArrayList<>();
                JSONArray m = o.optJSONArray("marks");
                if (m != null) for (int k = 0; k < m.length(); k++) it.marks.add(m.getInt(k));
                next.add(it);
            }
        } catch (Exception ignored) {
            return;
        }
        items.clear();
        items.addAll(next);
        save();
    }

    /** An item with exactly this text, if there is one. */
    synchronized Item same(String text) {
        if (text == null) return null;
        String t = text.trim();
        for (Item it : items) {
            if (it.len == t.length() && text(it.id).equals(t)) return it;
        }
        return null;
    }

    static String makeTitle(String text) {
        String t = text.trim();
        int nl = t.indexOf('\n');
        t = (nl > 0 ? t.substring(0, nl) : t).trim();
        t = t.replaceAll("^[#>*\\-\\s]+", "").replace("*", "").trim();
        if (t.length() > 40) t = t.substring(0, 40).trim() + "\u2026";
        return t;
    }

    private void load() {
        try {
            if (!indexFile.exists()) return;
            String s = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getString("id");
                it.title = o.optString("title", "");
                it.len = o.optInt("len", 0);
                it.count = o.optInt("count", 0);
                it.pos = o.optInt("pos", 0);
                it.done = o.optBoolean("done", false);
                it.lang = o.optString("lang", "");
                JSONArray m = o.optJSONArray("marks");
                if (m != null) for (int k = 0; k < m.length(); k++) it.marks.add(m.getInt(k));
                if (new File(dir, it.id + ".txt").exists()) items.add(it);
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            File tmp = new File(indexFile.getPath() + ".tmp");
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                f.write(toJson().toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(indexFile)) {
                try (FileOutputStream f = new FileOutputStream(indexFile)) {
                    f.write(toJson().toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
        }
    }

    synchronized JSONArray toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("title", it.title);
                o.put("len", it.len);
                o.put("count", it.count);
                o.put("pos", it.pos);
                o.put("done", it.done);
                o.put("lang", it.lang);
                o.put("marks", new JSONArray(it.marks));
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a;
    }

    synchronized Item find(String id) {
        if (id == null) return null;
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    synchronized Item first() {
        for (Item it : items) if (!it.done) return it;
        return items.isEmpty() ? null : items.get(0);
    }

    synchronized Item after(String id) {
        if (id == null) return first();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) return i + 1 < items.size() ? items.get(i + 1) : null;
        }
        return null;
    }

    synchronized String add(String title, String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;
        JSONArray before = toJson();
        Item it = new Item();
        it.id = UUID.randomUUID().toString();
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) t = makeTitle(text);
        if (t.isEmpty()) t = "B\u00e0i kh\u00f4ng t\u00ean";
        it.title = t;
        it.len = text.length();
        it.count = Splitter.split(text).size();
        it.lang = Splitter.detect(text);
        try (FileOutputStream f = new FileOutputStream(new File(dir, it.id + ".txt"))) {
            f.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        items.add(it);
        save();
        record(before, "Th\u00eam \u201c" + it.title + "\u201d");
        return it.id;
    }

    String text(String id) {
        try {
            return new String(Files.readAllBytes(new File(dir, id + ".txt").toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    synchronized void delete(String id) {
        Item it = find(id);
        if (it == null) return;
        JSONArray before = toJson();
        items.remove(it);
        save();
        record(before, "X\u00f3a \u201c" + it.title + "\u201d");
    }

    synchronized void move(String id, int delta) {
        Item it = find(id);
        if (it == null) return;
        int i = items.indexOf(it);
        int j = Math.max(0, Math.min(items.size() - 1, i + delta));
        if (i == j) return;
        JSONArray before = toJson();
        items.remove(i);
        items.add(j, it);
        save();
        record(before, "\u0110\u1ed5i th\u1ee9 t\u1ef1");
    }

    synchronized void setLang(String id, String lang) {
        Item it = find(id);
        if (it == null) return;
        it.lang = lang;
        save();
    }

    synchronized void toggleMark(String id, int i) {
        Item it = find(id);
        if (it == null) return;
        JSONArray before = toJson();
        Integer v = i;
        boolean removed = it.marks.remove(v);
        if (!removed) {
            it.marks.add(v);
            Collections.sort(it.marks);
        }
        save();
        record(before, removed ? "B\u1ecf \u0111\u00e1nh d\u1ea5u" : "\u0110\u00e1nh d\u1ea5u");
    }

    synchronized void recount() {
        for (Item it : items) it.count = Splitter.split(text(it.id)).size();
        save();
    }

    synchronized void setPos(String id, int pos, boolean done) {
        Item it = find(id);
        if (it == null) return;
        it.pos = pos;
        it.done = done;
        save();
    }
}
