package app.docthanhtieng;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
    }

    private static Store inst;

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private final File dir;
    private final File indexFile;
    private final List<Item> items = new ArrayList<>();

    private Store(Context c) {
        dir = new File(c.getFilesDir(), "texts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        indexFile = new File(c.getFilesDir(), "queue.json");
        load();
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
        Item it = new Item();
        it.id = UUID.randomUUID().toString();
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            int nl = text.indexOf('\n');
            t = (nl > 0 ? text.substring(0, nl) : text).trim();
            if (t.length() > 40) t = t.substring(0, 40).trim() + "…";
        }
        it.title = t;
        it.len = text.length();
        it.count = Splitter.split(text).size();
        try (FileOutputStream f = new FileOutputStream(new File(dir, it.id + ".txt"))) {
            f.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        items.add(it);
        save();
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
        items.remove(it);
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".txt").delete();
        save();
    }

    synchronized void move(String id, int delta) {
        Item it = find(id);
        if (it == null) return;
        int i = items.indexOf(it);
        int j = Math.max(0, Math.min(items.size() - 1, i + delta));
        if (i == j) return;
        items.remove(i);
        items.add(j, it);
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
