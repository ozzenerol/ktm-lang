module term;
import fs, libc, mem, os, proc, str, vec;

/* Reading lines a person types, as GNU readline does:
 *
 *   term.historyFile(".app_history");           // optional: kept between runs
 *   scoped String line;
 *   while (term.readLine("[bold]>[/] ", &line)) {
 *       ...                                     // Ctrl-D ends the loop
 *   }
 *
 * On a terminal the line can be edited: Left/Right (Ctrl-B/F), Home/End
 * (Ctrl-A/E), Alt-B/F and Ctrl-Left/Right a word at a time, Backspace and
 * Delete, Ctrl-U and Ctrl-K cut to the start or the end, Ctrl-W the word
 * before, Up/Down (Ctrl-P/N) go through the history, Ctrl-L clears the
 * screen, Ctrl-C drops the line and asks again, Ctrl-D on an empty line
 * ends. A line longer than the terminal scrolls sideways. When stdin is
 * not a terminal (a pipe, a file) lines are read as they are. */

extern "c" i32 tcgetattr(i32 fd, void *t);
extern "c" i32 tcsetattr(i32 fd, i32 when, void *t);
extern "c" void cfmakeraw(void *t);
extern "c" i32 ioctl(i32 fd, u64 request, ...);

#ifdef IS_MACOS
const u64 TERM_TIOCGWINSZ = 0x40087468;
#else
const u64 TERM_TIOCGWINSZ = 0x5413;
#endif
/* tcsetattr after the output is written, keeping typed-ahead input */
const i32 TERM_TCSADRAIN = 1;

struct WinSize { u16 rows; u16 cols; u16 xpixel; u16 ypixel; };

/* struct termios, as opaque bytes: its layout differs between systems */
static u8 gSaved[256];
static vec.Vec<String> gHist;
static i64 gHistMax = 1000;
static String gHistPath;
static bool gHistManual;

static i64 termCols() {
    WinSize ws;
    if (ioctl(1, TERM_TIOCGWINSZ, &ws) == 0 && ws.cols) { return ws.cols; }
    return 80;
}

static bool rawOn() {
    if (tcgetattr(0, gSaved) != 0) { return false; }
    u8 raw[256];
    mem.copy(raw, gSaved, 256);
    cfmakeraw(raw);
    return tcsetattr(0, TERM_TCSADRAIN, raw) == 0;
}

static void rawOff() {
    tcsetattr(0, TERM_TCSADRAIN, gSaved);
}

/* stdin is a terminal that can be driven */
static bool editable() {
    if (libc.isatty(0) != 1 || libc.isatty(1) != 1) { return false; }
    scoped String t = proc.getenv("TERM");
    return !str.equals(t[0:], "dumb");
}

/* One byte from stdin, -1 at the end */
static i32 readByte() {
    while (true) {
        u8 c;
        i64 n = libc.read(0, &c, 1);
        if (n == 1) { return c; }
        if (n < 0 && os.errno() == libc.EINTR) { continue; }
        return -1;
    }
}

static void put(u8[] s) {
    i64 done = 0;
    while (done < s.len) {
        i64 n = libc.write(1, s.ptr + done, s.len - done);
        if (n <= 0) {
            if (n < 0 && os.errno() == libc.EINTR) { continue; }
            return;
        }
        done += n;
    }
}

/* ---- history ---- */

static void histPush(u8[] line) {
    gHist.push(str.from(line));
    while (gHist.len > gHistMax && gHist.len) {
        String old = gHist.data[0];
        old.free();
        gHist.removeAt(0);
    }
}

/* Adds line to the history (and to the history file), unless it is empty
 * or the same as the last one. readLine does it for every line it returns,
 * unless historyAuto(false) was called. */
pub void historyAdd(u8[] line) {
    if (!line.len) { return; }
    if (gHist.len && str.equals(gHist.data[gHist.len - 1][0:], line)) { return; }
    if (str.find(line, "\n") >= 0) { return; }
    histPush(line);
    if (gHistPath.len) {
        String rec = str.from(line);
        rec.appendByte('\n');
        fs.writeAll(gHistPath[0:], rec[0:], libc.O_CREAT | libc.O_WRONLY | libc.O_APPEND);
        rec.free();
    }
}

/* Keeps the history in a file: reads the lines in it now (none when it
 * doesn't exist yet) and appends every line added from now on */
pub void historyFile(u8[] path) {
    gHistPath.free();
    gHistPath = str.from(path);
    scoped String all = fs.readAll(path);
    if (!all.ptr) { return; }
    u8[] rest = all[0:];
    while (rest.len) {
        i64 nl = str.find(rest, "\n");
        u8[] line = nl >= 0 ? rest[0:nl] : rest;
        if (line.len && line[line.len - 1] == '\r') { line = line[0:line.len - 1]; }
        if (line.len) { histPush(line); }
        if (nl < 0) { break; }
        rest = rest[nl + 1:];
    }
    /* more lines than kept: write back only the ones kept */
    String kept;
    i64 lines = 0;
    for (i64 i = 0; i < all.len; i++) {
        if (all.ptr[i] == '\n') { lines++; }
    }
    if (lines > gHistMax) {
        for (i64 i = 0; i < gHist.len; i++) {
            kept.append(gHist.data[i][0:]);
            kept.appendByte('\n');
        }
        fs.writeAll(path, kept[0:]);
    }
    kept.free();
}

/* How many lines the history keeps (1000 at first); the oldest go first */
pub void historyMax(i64 n) {
    gHistMax = n < 1 ? 1 : n;
    while (gHist.len > gHistMax) {
        String old = gHist.data[0];
        old.free();
        gHist.removeAt(0);
    }
}

/* false: readLine doesn't add lines to the history, historyAdd does */
pub void historyAuto(bool on) {
    gHistManual = !on;
}

/* How many lines the history holds */
pub i64 historyLen() {
    return gHist.len;
}

/* History line i, 0 the oldest ("" past the end) */
pub u8[] historyAt(i64 i) {
    if (i < 0 || i >= gHist.len) { return ""; }
    return gHist.data[i][0:];
}

/* Empties the history in memory (the file keeps its lines) */
pub void historyClear() {
    for (i64 i = 0; i < gHist.len; i++) { gHist.data[i].free(); }
    gHist.clear();
}

/* ---- the screen ---- */

/* Clears the terminal and puts the cursor at the top; nothing when stdout
 * isn't a terminal */
pub void clear() {
    if (libc.isatty(1) != 1) { return; }
    libc.fflush(libc.stdout);
    put("\x1b[H\x1b[2J");
}

/* ---- editing ---- */

struct Edit {
    String buf;
    i64 pos;            /* the cursor, a byte offset in buf */
    i64 off;            /* the first rune shown, when the line scrolls */
    String prompt;      /* rendered, with its escape codes */
    i64 promptW;
    vec.Vec<String> hist;   /* copies of the history and the new line, edited freely */
    i64 hidx;
};

static i64 nextRune(String *s, i64 i) {
    if (i >= s.len) { return s.len; }
    i++;
    while (i < s.len && (s.ptr[i] & 0xC0) == 0x80) { i++; }
    return i;
}

static i64 prevRune(String *s, i64 i) {
    if (i <= 0) { return 0; }
    i--;
    while (i > 0 && (s.ptr[i] & 0xC0) == 0x80) { i--; }
    return i;
}

/* The byte offset of rune n */
static i64 runeAt(String *s, i64 n) {
    i64 i = 0;
    while (n > 0 && i < s.len) {
        i = nextRune(s, i);
        n--;
    }
    return i;
}

static bool isWordByte(u8 c) {
    return c >= 0x80 || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') ||
           (c >= 'A' && c <= 'Z') || c == '_';
}

static i64 wordLeft(String *s, i64 i) {
    while (i > 0 && !isWordByte(s.ptr[i - 1])) { i--; }
    while (i > 0 && isWordByte(s.ptr[i - 1])) { i--; }
    return i;
}

static i64 wordRight(String *s, i64 i) {
    while (i < s.len && !isWordByte(s.ptr[i])) { i++; }
    while (i < s.len && isWordByte(s.ptr[i])) { i++; }
    return i;
}

static void refresh(Edit *e) {
    i64 avail = termCols() - e.promptW - 1;
    if (avail < 1) { avail = 1; }
    i64 cur = str.runeCount(e.buf[0:e.pos]);
    if (cur < e.off) { e.off = cur; }
    if (cur - e.off > avail) { e.off = cur - avail; }
    i64 from = runeAt(&e.buf, e.off);
    i64 to = from;
    for (i64 k = 0; k < avail && to < e.buf.len; k++) { to = nextRune(&e.buf, to); }
    String o;
    o.append("\r");
    o.append(e.prompt[0:]);
    o.append(e.buf[from:to]);
    o.append("\x1b[0K\r");
    i64 col = e.promptW + cur - e.off;
    if (col > 0) { o.appendFormat("\x1b[%dC", col); }
    put(o[0:]);
    o.free();
}

static void setLine(Edit *e, u8[] text) {
    e.buf.clear();
    e.buf.append(text);
    e.pos = e.buf.len;
    e.off = 0;
}

/* Up (-1) or down (+1) in the history copies, keeping the edits */
static void histMove(Edit *e, i64 dir) {
    i64 to = e.hidx + dir;
    if (to < 0 || to >= e.hist.len) { return; }
    String *slot = e.hist.at(e.hidx);
    slot.clear();
    slot.append(e.buf[0:]);
    e.hidx = to;
    setLine(e, e.hist.data[to][0:]);
}

/* An escape sequence after ESC: arrows, Home/End, Delete, word moves */
static void escape(Edit *e) {
    i32 c = readByte();
    if (c == 'b') { e.pos = wordLeft(&e.buf, e.pos); return; }
    if (c == 'f') { e.pos = wordRight(&e.buf, e.pos); return; }
    if (c == 127 || c == 8) {
        i64 w = wordLeft(&e.buf, e.pos);
        e.buf.remove(w, e.pos);
        e.pos = w;
        return;
    }
    if (c != '[' && c != 'O') { return; }
    u8 params[16];
    i64 np = 0;
    i32 k = readByte();
    while (k >= '0' && k <= '9' || k == ';') {
        if (np < 15) { params[np++] = (u8)k; }
        k = readByte();
    }
    params[np] = 0;
    u8[] p = params[0:np];
    bool ctrl = str.endsWith(p, ";5") || str.endsWith(p, ";3");
    if (k == 'A') { histMove(e, -1); }
    else if (k == 'B') { histMove(e, 1); }
    else if (k == 'C') { e.pos = ctrl ? wordRight(&e.buf, e.pos) : nextRune(&e.buf, e.pos); }
    else if (k == 'D') { e.pos = ctrl ? wordLeft(&e.buf, e.pos) : prevRune(&e.buf, e.pos); }
    else if (k == 'H') { e.pos = 0; }
    else if (k == 'F') { e.pos = e.buf.len; }
    else if (k == '~') {
        if (str.equals(p, "3")) { e.buf.remove(e.pos, nextRune(&e.buf, e.pos)); }
        else if (str.equals(p, "1") || str.equals(p, "7")) { e.pos = 0; }
        else if (str.equals(p, "4") || str.equals(p, "8")) { e.pos = e.buf.len; }
    }
}

/* 1: Enter, 0: end of input (Ctrl-D or EOF), keeps going otherwise */
static i64 edit(Edit *e) {
    refresh(e);
    while (true) {
        i32 c = readByte();
        if (c < 0) { return e.buf.len ? 1 : 0; }
        if (c == 13 || c == 10) { return 1; }
        if (c == 3) {                               /* Ctrl-C: drop the line */
            put("^C\r\n");
            setLine(e, "");
            e.hidx = e.hist.len - 1;
            e.hist.data[e.hidx].clear();
        } else if (c == 4) {                        /* Ctrl-D */
            if (!e.buf.len) { return 0; }
            e.buf.remove(e.pos, nextRune(&e.buf, e.pos));
        } else if (c == 127 || c == 8) {
            i64 b = prevRune(&e.buf, e.pos);
            e.buf.remove(b, e.pos);
            e.pos = b;
        } else if (c == 1) { e.pos = 0; }
        else if (c == 5) { e.pos = e.buf.len; }
        else if (c == 2) { e.pos = prevRune(&e.buf, e.pos); }
        else if (c == 6) { e.pos = nextRune(&e.buf, e.pos); }
        else if (c == 16) { histMove(e, -1); }
        else if (c == 14) { histMove(e, 1); }
        else if (c == 21) { e.buf.remove(0, e.pos); e.pos = 0; }
        else if (c == 11) { e.buf.remove(e.pos, e.buf.len); }
        else if (c == 23) {
            i64 w = wordLeft(&e.buf, e.pos);
            e.buf.remove(w, e.pos);
            e.pos = w;
        } else if (c == 12) { put("\x1b[H\x1b[2J"); }
        else if (c == 27) { escape(e); }
        else if (c >= 32) {
            u8 rune[4];
            i64 n = 1;
            rune[0] = (u8)c;
            i64 more = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC0 ? 1 : 0;
            for (i64 k = 0; k < more; k++) {
                i32 d = readByte();
                if (d < 0) { break; }
                rune[n++] = (u8)d;
            }
            e.buf.insert(e.pos, rune[0:n]);
            e.pos += n;
        }
        refresh(e);
    }
}

/* A line from stdin as it comes, without editing: false at the end */
static bool plainLine(String *out) {
    out.clear();
    bool any = false;
    while (true) {
        i32 c = readByte();
        if (c < 0) { return any; }
        any = true;
        if (c == '\n') { break; }
        out.appendByte((u8)c);
    }
    if (out.len && out.ptr[out.len - 1] == '\r') { out.remove(out.len - 1, out.len); }
    return true;
}

/* Shows prompt (markup allowed) and reads one line into *out (without its
 * newline), replacing what *out held. false when the input ends: Ctrl-D on
 * an empty line, or the end of a pipe. */
pub bool readLine(u8[] prompt, String *out) {
    libc.fflush(libc.stdout);
    Edit e;
    render(&e.prompt, prompt, colorOn(1));
    if (!editable()) {
        put(e.prompt[0:]);
        e.prompt.free();
        bool ok = plainLine(out);
        if (ok && !gHistManual) { historyAdd((*out)[0:]); }
        return ok;
    }
    /* a prompt of several lines: the ones before the last are written
     * once, and only the last is redrawn with the line */
    i64 nl = str.findLast(e.prompt[0:], "\n");
    if (nl >= 0) {
        put(e.prompt[0:nl + 1]);
        e.prompt.remove(0, nl + 1);
    }
    if (!rawOn()) {
        put(e.prompt[0:]);
        e.prompt.free();
        bool ok = plainLine(out);
        if (ok && !gHistManual) { historyAdd((*out)[0:]); }
        return ok;
    }
    String plain;
    render(&plain, prompt, false);
    i64 pnl = str.findLast(plain[0:], "\n");
    e.promptW = str.runeCount(plain[pnl + 1:]);
    plain.free();
    for (i64 i = 0; i < gHist.len; i++) { e.hist.push(gHist.data[i].clone()); }
    e.hist.push(str.from(""));
    e.hidx = e.hist.len - 1;
    i64 r = edit(&e);
    e.pos = e.buf.len;
    refresh(&e);
    put("\r\n");
    rawOff();
    out.free();
    *out = e.buf;
    for (i64 i = 0; i < e.hist.len; i++) { e.hist.data[i].free(); }
    e.hist.free();
    e.prompt.free();
    if (r == 0) { return false; }
    if (!gHistManual) { historyAdd((*out)[0:]); }
    return true;
}

/* readLine for a secret: nothing is shown as it is typed and the line
 * never goes into the history. false at the end of the input or on
 * Ctrl-C. */
pub bool readPassword(u8[] prompt, String *out) {
    libc.fflush(libc.stdout);
    String p;
    render(&p, prompt, colorOn(1));
    put(p[0:]);
    p.free();
    if (!editable() || !rawOn()) { return plainLine(out); }
    out.clear();
    bool ok = true;
    while (true) {
        i32 c = readByte();
        if (c == 13 || c == 10) { break; }
        if (c < 0 || c == 3 || (c == 4 && !out.len)) { ok = false; break; }
        if (c == 127 || c == 8) {
            i64 b = prevRune(out, out.len);
            out.remove(b, out.len);
        } else if (c == 21) {
            out.clear();
        } else if (c >= 32) {
            out.appendByte((u8)c);
        }
    }
    put("\r\n");
    rawOff();
    if (!ok) { out.clear(); }
    return ok;
}
