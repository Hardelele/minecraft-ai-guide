package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What leaves the computer in a share archive is cleaned first (no game classes): players' names become pseudonyms
 * (player1, player2 ... in the order they were told), server addresses and IP addresses go, paths with a user's
 * folder lose the user's name, e-mail addresses and UUIDs go. The names and the hosts are told by the game (the ones
 * it knows); the patterns catch the rest.
 */
final class Scrub {
    private final Map<String, String> names = new LinkedHashMap<String, String>();   // lower case -> pseudonym
    private final List<Pattern> nameRx = new ArrayList<Pattern>();
    private final List<String> nameAs = new ArrayList<String>();
    private final List<String[]> literal = new ArrayList<String[]>();                 // { text, replacement }, longest first
    int replaced;   // replacements made (all kinds)

    // a Windows user's folder: the name up to the next slash (it may have spaces: more is cut rather than less)
    static final Pattern USER_DIR = Pattern.compile("(?i)([A-Z]:[\\\\/]+(?:Users|Documents and Settings)[\\\\/]+)([^\\\\/\"'<>|:*?\\r\\n\\t]+)");
    static final Pattern UNIX_HOME = Pattern.compile("(/(?:Users|home)/)([^/\\s\"'<>]+)");
    static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(:\\d{1,5})?(?![\\d.])");
    // the full form (5 to 8 groups), or a short one with groups on both sides of "::" (fe80::1; "::1" is no one's)
    static final Pattern IPV6 = Pattern.compile("(?<![0-9a-zA-Z:])(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}(?![0-9a-zA-Z:])"
            + "|(?<![0-9a-zA-Z:])[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*::[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*(?![0-9a-zA-Z:])");
    static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\\b");

    /** a player's name (or the computer's user name): replaced as a whole word, any case */
    Scrub name(String n) {
        if (n == null) return this;
        n = n.trim();
        if (n.length() < 2 || names.containsKey(n.toLowerCase(Locale.ROOT))) return this;
        String as = "player" + (names.size() + 1);
        names.put(n.toLowerCase(Locale.ROOT), as);
        nameRx.add(Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(n) + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        nameAs.add(as);
        return this;
    }

    /** a server's address (host or host:port) */
    Scrub host(String h) {
        if (h == null || h.trim().isEmpty()) return this;
        h = h.trim();
        add(h, "<server>");
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) add(h.substring(0, colon), "<server>");
        return this;
    }

    /** a literal path (the game's folder, the user's home): replaced with its slashes either way */
    Scrub path(String p, String as) {
        if (p == null || p.length() < 3) return this;
        add(p, as);
        add(p.replace('\\', '/'), as);
        add(p.replace('/', '\\'), as);
        return this;
    }

    private void add(String text, String as) {
        for (String[] l : literal) if (l[0].equalsIgnoreCase(text)) return;
        literal.add(new String[] { text, as });
        java.util.Collections.sort(literal, (a, b) -> b[0].length() - a[0].length());
    }

    String pseudonym(String n) { return n == null ? null : names.get(n.trim().toLowerCase(Locale.ROOT)); }

    int names() { return names.size(); }

    /** the text cleaned */
    String text(String s) {
        if (s == null || s.isEmpty()) return s;
        for (String[] l : literal) s = replaceLiteral(s, l[0], l[1]);
        s = sub(USER_DIR, s, "$1<user>");
        s = sub(UNIX_HOME, s, "$1<user>");
        s = sub(EMAIL, s, "<email>");
        s = sub(UUID, s, "<uuid>");
        Matcher m = IPV4.matcher(s);
        StringBuffer b = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            boolean ip = true;
            for (int g = 1; g <= 4; g++) if (Integer.parseInt(m.group(g)) > 255) ip = false;
            m.appendReplacement(b, Matcher.quoteReplacement(ip ? "<ip>" : m.group()));
            if (ip) { replaced++; any = true; }
        }
        if (any) { m.appendTail(b); s = b.toString(); }
        s = sub(IPV6, s, "<ip>");
        for (int i = 0; i < nameRx.size(); i++) s = sub(nameRx.get(i), s, Matcher.quoteReplacement(nameAs.get(i)));
        return s;
    }

    private String sub(Pattern p, String s, String with) {
        Matcher m = p.matcher(s);
        if (!m.find()) return s;
        StringBuffer b = new StringBuffer();
        do { m.appendReplacement(b, with); replaced++; } while (m.find());
        m.appendTail(b);
        return b.toString();
    }

    private String replaceLiteral(String s, String what, String with) {
        String low = s.toLowerCase(Locale.ROOT), w = what.toLowerCase(Locale.ROOT);
        int i = low.indexOf(w);
        if (i < 0) return s;
        StringBuilder b = new StringBuilder();
        int from = 0;
        while (i >= 0) {
            b.append(s, from, i).append(with);
            replaced++;
            from = i + what.length();
            i = low.indexOf(w, from);
        }
        return b.append(s.substring(from)).toString();
    }

    /** a JSON value with every string in it cleaned (the keys too: a map by name is keyed by names) */
    JsonElement json(JsonElement e) {
        if (e == null || e.isJsonNull()) return e;
        if (e.isJsonPrimitive()) return e.getAsJsonPrimitive().isString() ? new JsonPrimitive(text(e.getAsString())) : e;
        if (e.isJsonArray()) {
            JsonArray a = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) a.add(json(x));
            return a;
        }
        JsonObject o = new JsonObject();
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) o.add(text(x.getKey()), json(x.getValue()));
        return o;
    }

    /**
     * A world recording's log (WorldRec, the records Replay reads) copied with its strings cleaned (an entity type
     * "player:<name>" becomes "player:playerN"); every other record is copied as it is. A log cut short ends where the
     * last whole record does. Streams as they are (the gzip around them is the caller's).
     */
    void rec(DataInputStream in, DataOutputStream out) throws IOException {
        int magic = in.readInt();
        if (magic != 0x41495245) throw new IOException("not a recording");
        out.writeInt(magic);
        out.writeInt(in.readInt());
        List<String> strings = new ArrayList<String>();
        java.io.ByteArrayOutputStream rb = new java.io.ByteArrayOutputStream();
        DataOutputStream r = new DataOutputStream(rb);
        try {
            for (; ; ) {
                rb.reset();
                int k = in.readByte();
                r.writeByte(k);
                if (k == 0) { out.write(rb.toByteArray()); break; }
                if (k == 6) {
                    int i = in.readShort();
                    String s = in.readUTF();
                    while (strings.size() <= i) strings.add(null);
                    strings.set(i, s);
                    r.writeShort(i);
                    r.writeUTF(text(s));
                } else if (k == 1) { r.writeInt(in.readInt()); r.writeLong(in.readLong()); r.writeBoolean(in.readBoolean()); }
                else if (k == 2) {
                    r.writeInt(in.readInt());
                    int type = in.readShort();
                    r.writeShort(type);
                    for (int i = 0; i < 3; i++) r.writeDouble(in.readDouble());
                    for (int i = 0; i < 3; i++) r.writeFloat(in.readFloat());
                    r.writeByte(in.readByte()); r.writeFloat(in.readFloat()); r.writeByte(in.readByte());
                    int item = in.readShort();
                    r.writeShort(item);
                    if (item >= 0) { r.writeShort(in.readShort()); r.writeByte(in.readByte()); }
                    if (type >= 0 && type < strings.size() && "agent".equals(strings.get(type))) {
                        r.writeShort(in.readShort()); r.writeByte(in.readByte()); r.writeInt(in.readInt()); r.writeShort(in.readShort()); r.writeInt(in.readInt());
                    }
                } else if (k == 3) { r.writeInt(in.readInt()); r.writeShort(in.readShort()); r.writeInt(in.readInt()); r.writeShort(in.readShort()); r.writeByte(in.readByte()); }
                else if (k == 4) { r.writeInt(in.readInt()); r.writeInt(in.readInt()); r.writeShort(in.readShort()); r.writeInt(in.readInt()); r.writeByte(in.readByte()); }
                else if (k == 5) { r.writeShort(in.readShort()); for (int i = 0; i < 5; i++) r.writeFloat(in.readFloat()); }
                else throw new IOException("bad record " + k);
                out.write(rb.toByteArray());
            }
        } catch (EOFException eof) {
            // cut short: the part record in rb is left out
        }
    }
}
