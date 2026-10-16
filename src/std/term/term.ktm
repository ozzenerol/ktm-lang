module term;
import cstr, date, libc, mem, proc, reflect, str, vec;

/* Output for command-line programs: colors written as markup, status
 * lines, tables and a progress bar.
 *
 *   term.print("[green]ok[/] saved [bold]%s[/]\n", name);
 *   term.success("deployed %d services", n);     // a green ✓, then the line
 *   term.error("can't read %s", path);            // a red ✗, to stderr
 *
 *   term.Table *t = term.table("NAME", "STATUS", "CPU");
 *   t.row("web", "[green]running[/]", 12.5);
 *   t.print();
 *
 * Colors are on when the stream is a terminal, and off for a pipe or a
 * file, when NO_COLOR is set or TERM is dumb; FORCE_COLOR turns them on.
 * With colors off the markup is removed and the text stays. */

/* How a table column lines up. Without Table.align, a column of numbers
 * is on the right and any other on the left. */
pub enum Align { left, right, center };

/* How a table is drawn: plain columns, lines around every cell, or a
 * Markdown table */
pub enum Border { none, box, markdown };

/* The markup's colors: SGR 30 + index (gray is 90) */
static u8[] gColors[9] = { "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white",
                           "gray" };

/* 0: not looked at yet, 1: on, 2: off; [1] is stdout, [2] stderr */
static i64 gColor[3];
/* 0: as detected, 1: forced on, 2: forced off */
static i64 gForced;

static bool envSet(u8[] name) {
    scoped String v = proc.getenv(name);
    return v.len > 0;
}

static bool detect(i32 fd) {
    if (envSet("NO_COLOR")) { return false; }
    scoped String force = proc.getenv("FORCE_COLOR");
    if (force.len && !str.equals(force[0:], "0")) { return true; }
    scoped String t = proc.getenv("TERM");
    if (str.equals(t[0:], "dumb")) { return false; }
    return libc.isatty(fd) == 1;
}

static bool colorOn(i32 fd) {
    if (gForced) { return gForced == 1; }
    if (!gColor[fd]) { gColor[fd] = detect(fd) ? 1 : 2; }
    return gColor[fd] == 1;
}

/* Does stdout get colors? */
pub bool colors() {
    return colorOn(1);
}

/* Colors on or off for stdout and stderr, whatever they are */
pub void setColors(bool on) {
    gForced = on ? 1 : 2;
}

static i64 colorIndex(u8[] w) {
    for (i64 i = 0; i < 9; i++) {
        if (str.equals(w, gColors[i])) { return i; }
    }
    return -1;
}

/* The SGR codes for the words of one tag ("bold red", "white on blue"),
 * appended to codes as ";n;m"; false when a word isn't markup */
static bool tagCodes(u8[] tag, String *codes) {
    i64 i = 0;
    bool any = false, bg = false;
    while (i < tag.len) {
        while (i < tag.len && tag[i] == ' ') { i++; }
        i64 s = i;
        while (i < tag.len && tag[i] != ' ') { i++; }
        if (s == i) { continue; }
        u8[] w = tag[s:i];
        i64 c = colorIndex(w);
        if (c >= 0) {
            i64 n = c == 8 ? (bg ? 100 : 90) : (bg ? 40 : 30) + c;
            codes.appendFormat(";%d", n);
            bg = false;
            any = true;
            continue;
        }
        if (bg) { return false; }
        if (str.equals(w, "on")) { bg = true; continue; }
        if (str.equals(w, "bold")) { codes.append(";1"); }
        else if (str.equals(w, "dim")) { codes.append(";2"); }
        else if (str.equals(w, "italic")) { codes.append(";3"); }
        else if (str.equals(w, "underline")) { codes.append(";4"); }
        else if (str.equals(w, "reverse")) { codes.append(";7"); }
        else if (str.equals(w, "strike")) { codes.append(";9"); }
        else { return false; }
        any = true;
    }
    return any && !bg;
}

/* Appends text to out with its markup turned into escape codes (color) or
 * removed. A tag is [words]; [/] closes the last one; [[ is a [. Brackets
 * around anything else stay as they are. */
static void render(String *out, u8[] text, bool color) {
    vec.Vec<String> open = vec.new<String>();
    i64 i = 0;
    while (i < text.len) {
        u8 c = text[i];
        if (c != '[') {
            out.appendByte(c);
            i++;
            continue;
        }
        if (i + 1 < text.len && text[i + 1] == '[') {
            out.appendByte('[');
            i += 2;
            continue;
        }
        i64 end = -1;
        for (i64 j = i + 1; j < text.len; j++) {
            if (text[j] == ']') { end = j; break; }
            if (text[j] == '[') { break; }
        }
        if (end < 0) {
            out.appendByte(c);
            i++;
            continue;
        }
        u8[] tag = text[i + 1:end];
        if (str.equals(tag, "/")) {
            if (open.len) {
                String last = open.pop();
                last.free();
                if (color) {
                    out.append("\x1b[0m");
                    for (i64 k = 0; k < open.len; k++) {
                        String *o = open.at(k);
                        out.appendFormat("\x1b[%.*sm", o.len - 1, o.ptr + 1);
                    }
                }
                i = end + 1;
                continue;
            }
            out.append(text[i:end + 1]);
            i = end + 1;
            continue;
        }
        String codes;
        if (!tagCodes(tag, &codes)) {
            codes.free();
            out.appendByte(c);
            i++;
            continue;
        }
        if (color) { out.appendFormat("\x1b[%.*sm", codes.len - 1, codes.ptr + 1); }
        open.push(codes);
        i = end + 1;
    }
    if (open.len && color) { out.append("\x1b[0m"); }
    for (i64 k = 0; k < open.len; k++) { open.at(k).free(); }
    open.free();
}

/* fmt's markup rendered for the stream, then formatted with args */
static String build(i32 fd, u8[] prefix, u8 *fmt, u64[] args, u8[] suffix) {
    String markup = str.from(prefix);
    markup.append(cstr.view(fmt));
    String pattern;
    render(&pattern, markup[0:], colorOn(fd));
    markup.free();
    pattern.append(suffix);
    u8 *text = cstr.formatArgs(null, pattern.cstr(), args);
    pattern.free();
    return str.adopt(text, cstr.len(text));
}

static void write(i32 fd, String s) {
    libc.FILE *f = fd == 2 ? libc.stderr : libc.stdout;
    libc.fprintf(f, "%.*s", s.len, s.ptr);
    s.free();
}

pub struct Progress;
/* The progress bar drawn on the terminal now, or null */
static Progress *gBar;
static void draw(Progress *p, bool final);

/* Writes s; a progress bar on the terminal is taken off first and drawn
 * again under it */
static void emit(i32 fd, String s) {
    if (!gBar) {
        write(fd, s);
        return;
    }
    libc.fprintf(libc.stderr, "\r\x1b[2K");
    write(fd, s);
    libc.fflush(libc.stdout);
    draw(gBar, false);
}

/* printf to stdout, with markup in fmt: "[red]failed[/] %s\n". The
 * values in args are written as they are, never read as markup. */
pub void print(u8 *fmt, ...args) {
    emit(1, build(1, "", fmt, args, ""));
}

/* print, to stderr */
pub void eprint(u8 *fmt, ...args) {
    emit(2, build(2, "", fmt, args, ""));
}

/* print into a new String (yours to free), with colors when stdout has
 * them */
pub String format(u8 *fmt, ...args) {
    return build(1, "", fmt, args, "");
}

/* One line on stdout after a green ✓ (the newline is added) */
pub void success(u8 *fmt, ...args) {
    emit(1, build(1, "[green]✓[/] ", fmt, args, "\n"));
}

/* One line on stdout after a blue • */
pub void info(u8 *fmt, ...args) {
    emit(1, build(1, "[blue]•[/] ", fmt, args, "\n"));
}

/* One line on stderr after a yellow ! */
pub void warn(u8 *fmt, ...args) {
    emit(2, build(2, "[yellow]![/] ", fmt, args, "\n"));
}

/* One line on stderr after a red ✗ */
pub void error(u8 *fmt, ...args) {
    emit(2, build(2, "[red]✗[/] ", fmt, args, "\n"));
}

/* A bold line on stdout, to start a part of the output */
pub void title(u8 *fmt, ...args) {
    emit(1, build(1, "[bold]", fmt, args, "\n"));
}

/* The columns text takes on a terminal: its characters without the
 * markup */
pub i64 width(u8[] text) {
    String plain;
    render(&plain, text, false);
    i64 n = str.runeCount(plain[0:]);
    plain.free();
    return n;
}

/* A table: rows of cells under a header, in lined-up columns */
pub struct Table {
    Border border;                  /* none (the default), box or markdown */
    private vec.Vec<String> cells;  /* every row's cells, one after the other */
    private vec.Vec<i64> starts;    /* where each row starts in cells; row 0 is the header */
    private vec.Vec<i64> kinds;     /* per column: 0 empty so far, 1 numbers, 2 text */
    private vec.Vec<i64> aligns;    /* per column: 0 not set, else an Align + 1 */
    private i64 ncols;
};

/* A table with these column headers (none for a table without a header) */
pub Table *table(u8 * ...headers) {
    Table *t = mem.allocZero(sizeof(Table));
    t.starts.push(0);
    for (i64 i = 0; i < headers.len; i++) {
        t.cells.push(str.from(cstr.view(headers[i])));
    }
    t.ncols = headers.len;
    return t;
}

static i64 rowLen(Table *t, i64 r) {
    i64 end = r + 1 < t.starts.len ? t.starts.data[r + 1] : t.cells.len;
    return end - t.starts.data[r];
}

static String *cell(Table *t, i64 r, i64 c) {
    if (c >= rowLen(t, r)) { return null; }
    return &t.cells.data[t.starts.data[r] + c];
}

static void noteKind(Table *t, i64 col, i64 kind) {
    while (t.kinds.len <= col) { t.kinds.push(0); }
    if (t.kinds.data[col] != 2) { t.kinds.data[col] = kind; }
}

/* A cell's text from a value of any type */
static String cellText(reflect.Value v, i64 *kind) {
    *kind = 2;
    reflect.Kind k = v.type.kind;
    if (k == reflect.Kind.int) {
        *kind = 1;
        if (!v.type.isSigned && v.type.size == 8) { return str.format("%u", *(u64 *)v.ptr); }
        return str.format("%d", reflect.getInt(v));
    }
    if (k == reflect.Kind.float) {
        *kind = 1;
        return str.format("%g", reflect.getFloat(v));
    }
    if (k == reflect.Kind.boolean) { return str.from(cstr.view(reflect.getInt(v) ? "true" : "false")); }
    if (k == reflect.Kind.enumType) { return str.from(reflect.nameOf(v.type, reflect.getInt(v))); }
    if (k == reflect.Kind.string) { return str.from((*(String *)v.ptr)[0:]); }
    if (k == reflect.Kind.slice && v.type.elem && v.type.elem.size == 1) { return str.from(*(u8[] *)v.ptr); }
    if (k == reflect.Kind.pointer && v.type.elem && v.type.elem.size == 1 &&
        v.type.elem.kind == reflect.Kind.int) {
        u8 *p = *(u8 **)v.ptr;
        return str.from(p ? cstr.view(p) : "");
    }
    return str.from(v.type.name);
}

/* Adds a row: text, numbers, bools, enums or Strings. Text may hold
 * markup. A column whose cells are all numbers lines up on the right. */
pub void Table.row(any ...cells) {
    self.starts.push(self.cells.len);
    for (i64 i = 0; i < cells.len; i++) {
        i64 kind;
        self.cells.push(cellText(cells[i], &kind));
        noteKind(self, i, kind);
    }
    if (cells.len > self.ncols) { self.ncols = cells.len; }
}

/* Lines column col (from 0) up on the left, the right or the center */
pub void Table.align(i64 col, Align a) {
    while (self.aligns.len <= col) { self.aligns.push(0); }
    self.aligns.data[col] = (i64)a + 1;
}

/* How many rows were added (the header isn't one) */
pub i64 Table.rows() {
    return self.starts.len - 1;
}

static Align alignOf(Table *t, i64 col) {
    if (col < t.aligns.len && t.aligns.data[col]) { return (Align)(t.aligns.data[col] - 1); }
    if (col < t.kinds.len && t.kinds.data[col] == 1) { return Align.right; }
    return Align.left;
}

static void repeat(String *o, u8[] s, i64 n) {
    for (i64 i = 0; i < n; i++) { o.append(s); }
}

/* One cell padded to w columns; the last cell of a plain row isn't padded
 * on the right */
static void putCell(String *o, String *s, i64 w, Align a, bool last, bool color, bool header) {
    u8[] text = "";
    if (s) { text = (*s)[0:]; }
    i64 pad = w - width(text);
    i64 before = a == Align.right ? pad : a == Align.center ? pad / 2 : 0;
    repeat(o, " ", before);
    if (header && color) { o.append("\x1b[1m"); }
    render(o, text, color);
    if (header && color) { o.append("\x1b[0m"); }
    if (!last) { repeat(o, " ", pad - before); }
}

static void rule(String *o, i64[] w, u8[] left, u8[] mid, u8[] right) {
    o.append(left);
    for (i64 c = 0; c < w.len; c++) {
        repeat(o, "─", w[c] + 2);
        o.append(c + 1 < w.len ? mid : right);
    }
    o.appendByte('\n');
}

/* The table as text: a new String, yours. With colors, the header is bold
 * and the markup becomes escape codes; without, the markup is removed. */
pub String Table.format(bool color = false) {
    String o;
    i64 n = self.ncols;
    if (!n) { return o; }
    bool header = rowLen(self, 0) > 0;
    if (self.border == Border.markdown) { color = false; }
    i64 *wp = mem.allocZero(n * sizeof(i64));
    i64[] w = wp[0:n];
    for (i64 r = 0; r < self.starts.len; r++) {
        for (i64 c = 0; c < n; c++) {
            String *s = cell(self, r, c);
            if (s) {
                i64 cw = width((*s)[0:]);
                if (cw > w[c]) { w[c] = cw; }
            }
        }
    }
    if (self.border == Border.markdown) {
        for (i64 c = 0; c < n; c++) {
            if (w[c] < 3) { w[c] = 3; }
        }
    }
    if (self.border == Border.box) { rule(&o, w, "╭", "┬", "╮"); }
    for (i64 r = header ? 0 : 1; r < self.starts.len; r++) {
        bool isHeader = r == 0;
        if (self.border == Border.none) {
            for (i64 c = 0; c < n; c++) {
                if (c) { o.append("  "); }
                putCell(&o, cell(self, r, c), w[c], alignOf(self, c), c + 1 == n, color, isHeader);
            }
            i64 keep = o.len;
            while (keep && o.ptr[keep - 1] == ' ') { keep--; }
            o.remove(keep, o.len);
        } else {
            u8[] bar = "|";
            if (self.border == Border.box) { bar = "│"; }
            o.append(bar);
            for (i64 c = 0; c < n; c++) {
                o.appendByte(' ');
                putCell(&o, cell(self, r, c), w[c], alignOf(self, c), false, color, isHeader);
                o.appendByte(' ');
                o.append(bar);
            }
        }
        o.appendByte('\n');
        if (isHeader && self.border == Border.box) { rule(&o, w, "├", "┼", "┤"); }
        if (isHeader && self.border == Border.markdown) {
            o.appendByte('|');
            for (i64 c = 0; c < n; c++) {
                o.appendByte(' ');
                repeat(&o, "-", w[c] - 1);
                o.appendByte(alignOf(self, c) == Align.left ? '-' : ':');
                o.append(" |");
            }
            o.appendByte('\n');
        }
    }
    if (self.border == Border.box) { rule(&o, w, "╰", "┴", "╯"); }
    mem.free(wp);
    return o;
}

/* Writes the table to stdout */
pub void Table.print() {
    emit(1, self.format(colorOn(1)));
}

/* Frees the table and its cells */
pub void Table.free() {
    for (i64 i = 0; i < self.cells.len; i++) { self.cells.data[i].free(); }
    self.cells.free();
    self.starts.free();
    self.kinds.free();
    self.aligns.free();
    mem.free(self);
}

/* A progress bar on stderr, redrawn in place on a terminal. Lines
 * written with term's functions while it runs go above it. */
pub struct Progress {
    private u8[] label;
    private i64 total;
    private i64 count;
    private i64 started;    /* date.monoMs() at the start */
    private i64 drawn;      /* when it was last drawn */
    private i64 shownPct;
    private bool live;      /* stderr is a terminal */
};

/* A progress bar for total steps of work called label */
pub Progress *progress(u8[] label, i64 total) {
    Progress *p = mem.allocZero(sizeof(Progress));
    p.label = label;
    p.total = total;
    p.started = date.monoMs();
    p.shownPct = -1;
    scoped String t = proc.getenv("TERM");
    p.live = libc.isatty(2) == 1 && !str.equals(t[0:], "dumb");
    if (p.live) { gBar = p; }
    return p;
}

static void draw(Progress *p, bool final) {
    i64 pct = p.total > 0 ? p.count * 100 / p.total : 100;
    if (pct > 100) { pct = 100; }
    bool color = colorOn(2);
    String o;
    if (p.live) { o.append("\r\x1b[2K"); }
    o.append(p.label);
    o.append("  ");
    i64 filled = pct * 30 / 100;
    if (color) { o.append(cstr.view(final ? "\x1b[32m" : "\x1b[36m")); }
    repeat(&o, "█", filled);
    if (color) { o.append("\x1b[0m\x1b[2m"); }
    repeat(&o, "░", 30 - filled);
    if (color) { o.append("\x1b[0m"); }
    o.appendFormat("  %3d%%  %d/%d", pct, p.count, p.total);
    if (final) {
        i64 ms = date.monoMs() - p.started;
        o.appendFormat("  %d.%ds\n", ms / 1000, ms % 1000 / 100);
    }
    write(2, o);
}

/* Sets how many steps are done, and redraws the bar when its percent
 * changed or 100 ms went by */
pub void Progress.set(i64 n) {
    self.count = n;
    if (!self.live) { return; }
    i64 now = date.monoMs();
    i64 pct = self.total > 0 ? n * 100 / self.total : 0;
    if (pct == self.shownPct && now - self.drawn < 100) { return; }
    self.shownPct = pct;
    self.drawn = now;
    draw(self, false);
}

/* n more steps done */
pub void Progress.add(i64 n = 1) {
    self.set(self.count + n);
}

/* Draws the last line, with the time taken, and frees the bar. Not on a
 * terminal, this line is the only one written. */
pub void Progress.done() {
    if (gBar == self) { gBar = null; }
    draw(self, true);
    mem.free(self);
}
