module flag;
import cstr, libc, mem, os, str, vec;

/* Command-line flags (v0.20.0), as Go's flag package, with GNU's forms:
 *
 *   flag.Set *fs = flag.new("server", "serves the API");
 *   i64 *port = fs.integer("port", 8080, "the port to listen on", 'p');
 *   bool *verbose = fs.boolean("verbose", false, "log every request", 'v');
 *   u8[] *db = fs.string("db", "postgres://localhost/app", "the database");
 *   fs.parseOrExit(argc, argv);          // -h prints the help, a mistake the message and the help
 *   ... *port, *verbose, *db, fs.arg(0) ...
 *
 * --name=value, --name value, -n value, -nvalue, -name (Go's), --flag and
 * -abc for booleans, "--" to end the flags; flags and arguments may mix.
 * Subcommands: fs.command("serve", "...") gives a Set of their own, and
 * fs.chosen() says which one ran. */

/* What a flag holds */
enum Kind {
    boolean,
    integer,
    number,
    text
};

struct Flag {
    u8[] name;
    u8 short;           /* 0: none */
    Kind kind;
    u8[] usage;
    void *value;        /* a bool, i64, f64 or u8[], on the heap */
    String defText;
    bool seen;
};

pub struct Set {
    private u8[] name;
    private u8[] about;
    private vec.Vec<Flag> flags;
    private vec.Vec<u8[]> args;
    private vec.Vec<Set *> commands;
    private Set *picked;
    private Set *parent;
    private String err;
    private bool help;
};

/* A new set of flags for a program (or a subcommand) called name */
pub Set *new(u8[] name, u8[] about = "") {
    Set *s = mem.allocZero(sizeof(Set));
    s.name = name;
    s.about = about;
    return s;
}

static Flag *find(Set *s, u8[] name) {
    for (i64 i = 0; i < s.flags.len; i++) {
        if (str.equals(s.flags.data[i].name, name)) { return &s.flags.data[i]; }
    }
    return null;
}

static Flag *findShort(Set *s, u8 c) {
    for (i64 i = 0; i < s.flags.len; i++) {
        if (s.flags.data[i].short == c && c) { return &s.flags.data[i]; }
    }
    return null;
}

static void *add(Set *s, u8[] name, u8 short, Kind kind, u8[] usage, i64 size) {
    if (!name.len || name[0] == '-') { throw(1, "flag: a flag's name is a word, without dashes: `%.*s`", name.len, name.ptr); }
    if (find(s, name)) { throw(1, "flag: --%.*s is defined twice", name.len, name.ptr); }
    if (short && findShort(s, short)) { throw(1, "flag: -%c is defined twice", short); }
    Flag f;
    f.name = name;
    f.short = short;
    f.kind = kind;
    f.usage = usage;
    f.value = mem.allocZero(size);
    s.flags.push(f);
    return f.value;
}

/* A flag that is on or off: --name (or -s) sets it, --name=false clears
 * it. The pointer stays valid while the Set lives. */
pub bool *Set.boolean(u8[] name, bool def, u8[] usage, u8 short = 0) {
    bool *v = add(self, name, short, Kind.boolean, usage, sizeof(bool));
    *v = def;
    if (def) { self.flags.data[self.flags.len - 1].defText = str.from("true"); }
    return v;
}

/* A whole number */
pub i64 *Set.integer(u8[] name, i64 def, u8[] usage, u8 short = 0) {
    i64 *v = add(self, name, short, Kind.integer, usage, sizeof(i64));
    *v = def;
    self.flags.data[self.flags.len - 1].defText = str.format("%d", def);
    return v;
}

/* A number with a fraction */
pub f64 *Set.number(u8[] name, f64 def, u8[] usage, u8 short = 0) {
    f64 *v = add(self, name, short, Kind.number, usage, sizeof(f64));
    *v = def;
    self.flags.data[self.flags.len - 1].defText = str.format("%g", def);
    return v;
}

/* Text: a view of the command line's (or of def), nothing to free */
pub u8[] *Set.string(u8[] name, u8[] def, u8[] usage, u8 short = 0) {
    u8[] *v = add(self, name, short, Kind.text, usage, sizeof(u8[]));
    *v = def;
    if (def.len) { self.flags.data[self.flags.len - 1].defText = str.format("\"%.*s\"", def.len, def.ptr); }
    return v;
}

/* A subcommand (`app serve --port 80`): its own flags and arguments */
pub Set *Set.command(u8[] name, u8[] about) {
    Set *c = new(name, about);
    c.parent = self;
    self.commands.push(c);
    return c;
}

/* The subcommand on the command line, or null */
pub Set *Set.chosen() {
    return self.picked;
}

/* Was --name (or -s) on the command line? */
pub bool Set.given(u8[] name) {
    Flag *f = find(self, name);
    return f && f.seen;
}

/* The arguments that aren't flags (after a subcommand's name: its own) */
pub i64 Set.nargs() {
    return self.args.len;
}

/* Argument i ("" past the end) */
pub u8[] Set.arg(i64 i) {
    return i >= 0 && i < self.args.len ? self.args.data[i] : "";
}

/* Why parse answered false */
pub u8[] Set.error() {
    return self.err[0:];
}

/* Was -h or --help asked for? */
pub bool Set.helpAsked() {
    return self.help;
}

static bool fail(Set *s, u8 *fmt, ...args) {
    s.err.free();
    u8 *text = cstr.formatArgs(null, fmt, args);
    s.err = str.adopt(text, cstr.len(text));
    return false;
}

static bool setValue(Set *s, Flag *f, u8[] v, u8[] shown) {
    f.seen = true;
    if (f.kind == Kind.boolean) {
        bool *b = f.value;
        if (str.equals(v, "true") || str.equals(v, "1") || str.equals(v, "yes")) { *b = true; return true; }
        if (str.equals(v, "false") || str.equals(v, "0") || str.equals(v, "no")) { *b = false; return true; }
        return fail(s, "%.*s: `%.*s` isn't true or false", shown.len, shown.ptr, v.len, v.ptr);
    }
    if (f.kind == Kind.integer) {
        if (!str.toInt(v, f.value)) { return fail(s, "%.*s: `%.*s` isn't a whole number", shown.len, shown.ptr, v.len, v.ptr); }
        return true;
    }
    if (f.kind == Kind.number) {
        if (!str.toFloat(v, f.value)) { return fail(s, "%.*s: `%.*s` isn't a number", shown.len, shown.ptr, v.len, v.ptr); }
        return true;
    }
    u8[] *t = f.value;
    *t = v;
    return true;
}

/* Reads argv[1..argc): true, or false with s.error() (and s.helpAsked()
 * for -h / --help). A subcommand's name hands the rest to its Set. */
pub bool Set.parse(i32 argc, u8 **argv) {
    return self.parseFrom(argc, argv, 1);
}

static bool parseArgs(Set *s, i32 argc, u8 **argv, i64 from);

pub bool Set.parseFrom(i32 argc, u8 **argv, i64 from) {
    return parseArgs(self, argc, argv, from);
}

static bool parseArgs(Set *s, i32 argc, u8 **argv, i64 from) {
    s.err.free();
    for (i64 i = from; i < argc; i++) {
        u8[] a = cstr.view(argv[i]);
        if (str.equals(a, "--")) {
            for (i64 j = i + 1; j < argc; j++) { s.args.push(cstr.view(argv[j])); }
            return true;
        }
        if (a.len < 2 || a[0] != '-') {
            /* a subcommand's name, before any argument */
            if (!s.args.len && s.commands.len) {
                for (i64 k = 0; k < s.commands.len; k++) {
                    Set *c = s.commands.data[k];
                    if (str.equals(c.name, a)) {
                        s.picked = c;
                        bool ok = parseArgs(c, argc, argv, i + 1);
                        if (!ok) {
                            s.help = c.help;
                            s.err.free();
                            s.err = str.from(c.err[0:]);
                        }
                        return ok;
                    }
                }
                return fail(s, "unknown command `%.*s`", a.len, a.ptr);
            }
            s.args.push(a);
            continue;
        }
        if (str.equals(a, "-h") || str.equals(a, "--help") || str.equals(a, "-help")) {
            s.help = true;
            return fail(s, "help asked for");
        }
        bool dashdash = a[1] == '-';
        u8[] body = dashdash ? a[2:] : a[1:];
        i64 eq = str.find(body, "=");
        u8[] name = eq >= 0 ? body[0:eq] : body;
        Flag *f = find(s, name);
        if (!f && !dashdash && body.len >= 1) {
            /* short flags: -p 80, -p80, -abc */
            Flag *first = findShort(s, body[0]);
            if (!first) { return fail(s, "unknown flag %.*s", a.len, a.ptr); }
            if (first.kind == Kind.boolean) {
                for (i64 k = 0; k < body.len; k++) {
                    Flag *b = findShort(s, body[k]);
                    if (!b || b.kind != Kind.boolean) {
                        return fail(s, "unknown flag -%c in %.*s", body[k], a.len, a.ptr);
                    }
                    bool *v = b.value;
                    *v = true;
                    b.seen = true;
                }
                continue;
            }
            u8[] shown = a[0:2];
            if (body.len > 1) {
                if (!setValue(s, first, body[1:], shown)) { return false; }
                continue;
            }
            if (i + 1 >= argc) { return fail(s, "%.*s needs a value", shown.len, shown.ptr); }
            i++;
            if (!setValue(s, first, cstr.view(argv[i]), shown)) { return false; }
            continue;
        }
        if (!f) { return fail(s, "unknown flag %.*s", a.len, a.ptr); }
        u8[] shown = eq >= 0 ? a[0:a.len - (body.len - eq)] : a;
        if (eq >= 0) {
            if (!setValue(s, f, body[eq + 1:], shown)) { return false; }
        } else if (f.kind == Kind.boolean) {
            bool *v = f.value;
            *v = true;
            f.seen = true;
        } else {
            if (i + 1 >= argc) { return fail(s, "%.*s needs a value", shown.len, shown.ptr); }
            i++;
            if (!setValue(s, f, cstr.view(argv[i]), shown)) { return false; }
        }
    }
    return true;
}

/* The help text: a new String, yours */
pub String Set.usage() {
    String o;
    o.append("usage: ");
    if (self.parent) { o.appendFormat("%.*s ", self.parent.name.len, self.parent.name.ptr); }
    o.append(self.name);
    if (self.flags.len) { o.append(" [flags]"); }
    if (self.commands.len) { o.append(" <command>"); }
    o.append(" [arguments]\n");
    if (self.about.len) { o.appendFormat("\n%.*s\n", self.about.len, self.about.ptr); }
    if (self.commands.len) {
        o.append("\ncommands:\n");
        i64 w = 0;
        for (i64 i = 0; i < self.commands.len; i++) {
            if (self.commands.data[i].name.len > w) { w = self.commands.data[i].name.len; }
        }
        for (i64 i = 0; i < self.commands.len; i++) {
            Set *c = self.commands.data[i];
            o.appendFormat("  %-*.*s  %.*s\n", (i32)w, c.name.len, c.name.ptr, c.about.len, c.about.ptr);
        }
    }
    o.append("\nflags:\n");
    vec.Vec<String> left = vec.new<String>();
    defer left.free();
    i64 w = 10;
    for (i64 i = 0; i < self.flags.len; i++) {
        Flag *f = &self.flags.data[i];
        String l;
        if (f.short) { l.appendFormat("-%c, ", f.short); } else { l.append("    "); }
        l.appendFormat("--%.*s", f.name.len, f.name.ptr);
        if (f.kind == Kind.integer) { l.append(" int"); }
        if (f.kind == Kind.number) { l.append(" number"); }
        if (f.kind == Kind.text) { l.append(" text"); }
        if (l.len > w) { w = l.len; }
        left.push(l);
    }
    for (i64 i = 0; i < self.flags.len; i++) {
        Flag *f = &self.flags.data[i];
        String *l = left.at(i);
        o.appendFormat("  %-*.*s  %.*s", (i32)w, l.len, l.ptr, f.usage.len, f.usage.ptr);
        if (f.defText.len) { o.appendFormat(" (default %.*s)", f.defText.len, f.defText.ptr); }
        o.appendByte('\n');
    }
    o.appendFormat("  %-*s  show this help\n", (i32)w, "-h, --help");
    return o;
}

/* parse, or the program ends: -h / --help prints the help and exits 0; a
 * mistake prints it and the help to stderr and exits 2 */
pub void Set.parseOrExit(i32 argc, u8 **argv) {
    if (self.parse(argc, argv)) { return; }
    Set *shown = self.picked ? self.picked : self;
    String u = shown.usage();
    if (self.help) {
        printf("%.*s", u.len, u.ptr);
        libc.fflush(null);
        u.free();
        os.exit(0);
    }
    libc.fprintf(libc.stderr, "%.*s: %.*s\n\n%.*s", self.name.len, self.name.ptr, self.err.len, self.err.ptr, u.len, u.ptr);
    u.free();
    os.exit(2);
}

/* Frees the set, its subcommands' and the values' memory (pointers from
 * boolean, integer... dangle after) */
pub void Set.free() {
    for (i64 i = 0; i < self.flags.len; i++) {
        mem.free(self.flags.data[i].value);
        self.flags.data[i].defText.free();
    }
    self.flags.free();
    self.args.free();
    for (i64 i = 0; i < self.commands.len; i++) { self.commands.data[i].free(); }
    self.commands.free();
    self.err.free();
    mem.free(self);
}
