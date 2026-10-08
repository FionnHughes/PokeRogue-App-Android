package importfix;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What a backup holds, in words: the save data's totals, each run in progress (mode,
 * wave, biome, money, party) and the newest finished runs. Reads the game's own JSON;
 * names come from tables.js (species, biomes). Nothing here touches Android.
 */
final class BackupDetails {
    /** Formats a time for the reader. */
    interface Clock {
        String format(long millis);
    }

    private static final String[] MODES = {"Classic", "Endless", "Spliced Endless", "Daily Run", "Challenge"};
    private static final int NEWEST_RUNS = 5;

    private final JSONObject species;
    private final JSONObject biomes;
    private final Clock clock;

    /** tables: the object tables.js assigns to window.__saveTables, or null to show ids. */
    BackupDetails(JSONObject tables, Clock clock) {
        this.species = tables == null ? null : tables.optJSONObject("species");
        this.biomes = tables == null ? null : tables.optJSONObject("biomes");
        this.clock = clock;
    }

    /** The tables from the text of tables.js, or null if it cannot be read. */
    static JSONObject tablesFrom(String script) {
        try {
            return new JSONObject(script.substring(script.indexOf('{'), script.lastIndexOf('}') + 1));
        } catch (Exception e) { // not a table, or not JSON
            return null;
        }
    }

    /** The save data's totals, or null if the text is not a save. */
    String save(String text) {
        JSONObject d = parse(text);
        if (d == null) {
            return null;
        }
        JSONObject stats = d.optJSONObject("gameStats");
        if (stats == null) {
            stats = new JSONObject();
        }
        int caught = 0;
        int seen = 0;
        JSONObject dex = d.optJSONObject("dexData");
        for (Iterator<String> it = dex == null ? null : dex.keys(); it != null && it.hasNext();) {
            JSONObject entry = dex.optJSONObject(it.next());
            if (entry == null) {
                continue;
            }
            if (nonZero(entry.opt("caughtAttr"))) {
                caught++;
            }
            if (nonZero(entry.opt("seenAttr"))) {
                seen++;
            }
        }
        JSONArray eggs = d.optJSONArray("eggs");
        StringBuilder out = new StringBuilder();
        out.append("Last saved: ").append(when(d.optLong("timestamp")));
        out.append("\nPokédex: ").append(caught).append(" caught, ").append(seen).append(" seen");
        out.append("\nPlay time: ").append(duration(stats.optLong("playTime")));
        out.append("\nClassic runs: ").append(stats.optInt("sessionsWon")).append(" won of ")
                .append(stats.optInt("classicSessionsPlayed")).append(" played");
        if (stats.optInt("highestEndlessWave") > 0) {
            out.append("\nHighest Endless wave: ").append(stats.optInt("highestEndlessWave"));
        }
        out.append("\nEggs waiting to hatch: ").append(eggs == null ? 0 : eggs.length());
        return out.toString();
    }

    /** One run in progress over a few lines, or null if the slot is empty. */
    String session(int slot, String text) {
        JSONObject d = parse(text);
        if (d == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        out.append("Slot ").append(slot + 1).append(": ").append(mode(d.optInt("gameMode", -1)))
                .append(", wave ").append(d.optInt("waveIndex"));
        JSONObject arena = d.optJSONObject("arena");
        String biome = arena == null ? "" : name(biomes, arena.optInt("biome", -1));
        if (!biome.isEmpty()) {
            out.append(" in ").append(biome);
        }
        out.append("\n    Saved ").append(when(d.optLong("timestamp")))
                .append(", run time ").append(duration(d.optLong("playTime")))
                .append(", ₽").append(NumberFormat.getIntegerInstance(Locale.UK).format(d.optLong("money")));
        String party = party(d.optJSONArray("party"));
        if (!party.isEmpty()) {
            out.append("\n    Party: ").append(party);
        }
        return out.toString();
    }

    /** How many finished runs there are and the newest few, or null if there are none. */
    String history(String text) {
        JSONObject runs = parse(text);
        if (runs == null || runs.length() == 0) {
            return null;
        }
        List<String> keys = new ArrayList<String>();
        for (Iterator<String> it = runs.keys(); it.hasNext();) {
            String key = it.next();
            if (key.matches("[0-9]{10,16}")) {
                keys.add(key);
            }
        }
        Collections.sort(keys, (a, b) -> Long.compare(Long.parseLong(b), Long.parseLong(a)));
        StringBuilder out = new StringBuilder();
        out.append(keys.size()).append(keys.size() == 1 ? " finished run" : " finished runs");
        if (keys.size() > NEWEST_RUNS) {
            out.append(", newest ").append(NEWEST_RUNS).append(":");
        }
        for (int i = 0; i < keys.size() && i < NEWEST_RUNS; i++) {
            JSONObject run = runs.optJSONObject(keys.get(i));
            JSONObject entry = run == null ? null : run.optJSONObject("entry");
            if (entry == null) {
                continue;
            }
            out.append("\n  ").append(when(Long.parseLong(keys.get(i)))).append(": ")
                    .append(mode(entry.optInt("gameMode", -1)))
                    .append(run.optBoolean("isVictory") ? ", won at wave " : ", ended at wave ")
                    .append(entry.optInt("waveIndex"));
        }
        return out.toString();
    }

    private String party(JSONArray members) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; members != null && i < members.length(); i++) {
            JSONObject p = members.optJSONObject(i);
            if (p == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append("Lv ").append(p.optInt("level")).append(' ').append(name(species, p.optInt("species", -1)));
            int fused = p.optInt("fusionSpecies", 0);
            if (fused > 0) {
                out.append('/').append(name(species, fused));
            }
            if (p.optBoolean("shiny")) {
                out.append(" ★");
            }
        }
        return out.toString();
    }

    private static String mode(int index) {
        return index >= 0 && index < MODES.length ? MODES[index] : "Run";
    }

    private static String name(JSONObject table, int id) {
        if (id < 0) {
            return "";
        }
        String known = table == null ? "" : table.optString(String.valueOf(id), "");
        return known.isEmpty() ? "#" + id : known;
    }

    private String when(long millis) {
        return millis > 0 ? clock.format(millis) : "unknown";
    }

    static String duration(long seconds) {
        long minutes = Math.round(seconds / 60.0);
        return minutes / 60 + " h " + minutes % 60 + " min";
    }

    private static boolean nonZero(Object value) {
        return value != null && !JSONObject.NULL.equals(value) && !"0".equals(String.valueOf(value))
                && !"false".equals(String.valueOf(value));
    }

    private static JSONObject parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }
}
