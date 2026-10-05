package importfix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps copies of what a sync or restore is about to replace, so it can be put back.
 *
 * One backup is up to three files in the app's private storage, named after when it
 * was taken and which side it came from ("offline" or "online"):
 *   id.meta.json     small description, shown in the restore list
 *   id.save.json     the save data, if the backup has any
 *   id.history.json  the run history, if the backup has any
 * Only the newest {@link #KEEP} backups are kept.
 */
final class Backups {
    static final int KEEP = 20;
    static final String META = "meta";
    static final String SAVE = "save";
    static final String HISTORY = "history";

    private Backups() {
    }

    /**
     * Stores a backup and removes the oldest ones beyond the limit.
     *
     * @return the new backup's id, or null if it could not be stored (nothing is left behind)
     */
    static synchronized String write(File dir, String origin, String meta, String save, String history) {
        if (!"offline".equals(origin) && !"online".equals(origin)) {
            return null; // the origin becomes part of a file name
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        // Ids start with the time, so later backups sort after earlier ones; avoid reusing one.
        long time = System.currentTimeMillis();
        String id = time + "-" + origin;
        while (file(dir, id, META).exists()) {
            id = (++time) + "-" + origin;
        }
        try {
            if (save != null && !save.isEmpty()) {
                writeText(file(dir, id, SAVE), save);
            }
            if (history != null && !history.isEmpty()) {
                writeText(file(dir, id, HISTORY), history);
            }
            // The description goes last: a backup without one is not listed.
            writeText(file(dir, id, META), meta);
        } catch (IOException e) {
            delete(dir, id);
            return null;
        }
        List<String> all = ids(dir);
        for (int i = KEEP; i < all.size(); i++) {
            delete(dir, all.get(i));
        }
        return id;
    }

    /** Ids of the stored backups, newest first. */
    static synchronized List<String> ids(File dir) {
        List<String> found = new ArrayList<String>();
        String[] names = dir.list();
        String suffix = "." + META + ".json";
        if (names != null) {
            for (String name : names) {
                if (name.endsWith(suffix) && timeOf(name) > 0) {
                    found.add(name.substring(0, name.length() - suffix.length()));
                }
            }
        }
        Collections.sort(found, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Long.compare(timeOf(b), timeOf(a));
            }
        });
        return found;
    }

    /** One part of a backup as text, or "" if the backup has no such part. */
    static synchronized String read(File dir, String id, String part) {
        File file = file(dir, id, part);
        if (!file.isFile()) {
            return "";
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** When a backup was taken, in milliseconds, read from its id. 0 if the id is not one of ours. */
    static long timeOf(String id) {
        int dash = id.indexOf('-');
        if (dash <= 0) {
            return 0;
        }
        try {
            return Long.parseLong(id.substring(0, dash));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static File file(File dir, String id, String part) {
        return new File(dir, id + "." + part + ".json");
    }

    private static void delete(File dir, String id) {
        file(dir, id, META).delete();
        file(dir, id, SAVE).delete();
        file(dir, id, HISTORY).delete();
    }

    private static void writeText(File file, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } finally {
            out.close();
        }
    }
}
