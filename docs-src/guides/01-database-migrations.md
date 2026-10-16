# Database migrations

Keep a database's schema in numbered SQL files and let your program bring any database up to
date with [ktm-migrate](https://github.com/ozzenerol/ktm-migrate). This guide builds a small notes
program on SQLite, step by step: the first migrations, a `migrate` command, migrating at start-up,
changes SQLite can't make with `ALTER TABLE`, failures, tests and migrations built into the
binary. Then the same program on PostgreSQL, several instances starting at once, migrations from
merged branches, and a driver of your own.

## What you need

- **ktm 0.24 or newer.** ktm-migrate v0.1.1 needs it.
- **For SQLite, a ktm built with SQLite.** The `sql` module, which the SQLite driver uses, is only
  in a ktm built from source with `--sqlite`. The release zips don't have it. You need sqlite3 and
  its headers (`libsqlite3-dev` on Debian and Ubuntu; macOS has them):

```sh
$ git clone https://github.com/ozzenerol/ktm-lang && cd ktm-lang
$ ./build.sh --sqlite --install
```

- **For PostgreSQL, any ktm.** The driver uses the [ktm-pg](https://github.com/ozzenerol/ktm-pg)
  package. The [PostgreSQL](#postgresql) section starts from here too.

On a ktm without SQLite, importing the SQLite driver stops the build with this message:

```output
Error: ktm-migrate/sqlite needs ktm built with SQLite (./build.sh --sqlite)
```

## How it works

- A **migration** is a version number, a name, an *up* script that makes a change and, optionally,
  a *down* script that undoes it.
- The database keeps the versions that ran in a table, `schema_migrations (version, name,
  applied_at)`. The runner creates it the first time.
- `up` runs every migration the table doesn't have, in version order. A database that is already
  up to date gets nothing. The same program can migrate an empty database or last month's.
- Each migration runs in a transaction together with its row in `schema_migrations`. It happens
  whole or not at all.
- A lock keeps two instances of a program that start together from both migrating.

## Create the project

```sh
$ mkdir notes && cd notes
$ ktm mod init example.com/me/notes
ktm: created ktm.mod for example.com/me/notes
$ ktm get github.com/ozzenerol/ktm-migrate
ktm: added github.com/ozzenerol/ktm-migrate v0.1.1
$ cat ktm.mod
module example.com/me/notes

ktm 0.25

require (
	github.com/ozzenerol/ktm-migrate v0.1.1
)
```

The repository has three packages:

| Import | Package name | What it is |
| --- | --- | --- |
| `"github.com/ozzenerol/ktm-migrate"` | `migrate` | the runner: loading migrations, `up`, `down`, `to`, `status`, `version` |
| `"github.com/ozzenerol/ktm-migrate/sqlite"` | `sqlite` | the driver for ktm's `sql` module |
| `"github.com/ozzenerol/ktm-migrate/postgres"` | `postgres` | the driver for ktm-pg |

A package is only compiled when the program imports it. A SQLite program never builds ktm-pg.

## The first migrations

Migrations go in a directory of their own, `migrations/`. Make two: a table of notes and a table
of tags.

```sh
notes/
  ktm.mod
  ktm.sum
  main.ktm
  migrations/
    001_create_notes.up.sql
    001_create_notes.down.sql
    002_create_tags.up.sql
    002_create_tags.down.sql
```

`migrations/001_create_notes.up.sql`:

```sql
CREATE TABLE notes (
    id    INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    body  TEXT NOT NULL DEFAULT ''
);
```

`migrations/001_create_notes.down.sql`:

```sql
DROP TABLE notes;
```

`migrations/002_create_tags.up.sql` has two statements. A script can hold any number of them, and
they all run in the migration's transaction:

```sql
CREATE TABLE tags (
    note_id INTEGER NOT NULL REFERENCES notes (id) ON DELETE CASCADE,
    tag     TEXT NOT NULL,
    PRIMARY KEY (note_id, tag)
);
CREATE INDEX tags_by_tag ON tags (tag);
```

`migrations/002_create_tags.down.sql` undoes them in reverse order:

```sql
DROP INDEX tags_by_tag;
DROP TABLE tags;
```

### Naming the files

- A file is `NNN_name.up.sql` or `NNN_name.down.sql`.
- `NNN` is the version: a whole number from 1, with any number of digits. `001`, `42` and
  `20261014120000` all work. Leading zeros only keep `ls` in order: `001` and `1` are both
  version 1.
- The name is letters, digits, `_` and `-`. A version has one name.
- Every version needs an up file. The down file is optional, but without it that migration can't
  be reverted.
- Files that don't end in `.sql`, such as a `README.md`, are left alone. A `.sql` file named
  some other way is an error, so a typo doesn't get skipped without a word.

## Apply them

`main.ktm` opens the database, loads the directory and runs everything pending:

```ktm
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/sqlite";
import sql;

i32 main() {
    sql.Db *db = sql.open("notes.db");
    if (!db) {
        printf("can't open notes.db\n");
        return 1;
    }
    defer sql.close(db);

    migrate.Migrations ms = migrate.load("migrations");
    defer ms.free();
    migrate.Driver d = sqlite.driver(db);
    i64 n = ms.up(&d);
    printf("%d migration(s) applied, version %d\n", n, migrate.version(&d));
    return 0;
}
```

```sh
$ ktm build
$ ./notes
2 migration(s) applied, version 2
$ ./notes
0 migration(s) applied, version 2
```

- `migrate.load(dir)` reads every migration in `dir`, sorted by version. The path is relative to
  the directory the program runs in, so run it from the project, or [build the migrations into
  the binary](#migrations-built-into-the-binary).
- `sqlite.driver(db)` makes a `migrate.Driver`: what the runner uses to talk to the database. The
  database stays yours, so close it yourself, after the last migration call.
- `ms.up(&d)` returns how many migrations ran. The second run has nothing to do.
- `migrate.version(&d)` is the newest applied version, `0` for an empty database.
- `ms.free()` frees what `load` read.

The `sqlite3` shell shows what happened:

```sh
$ sqlite3 notes.db 'SELECT * FROM schema_migrations'
1|create_notes|2026-10-16 01:58:02
2|create_tags|2026-10-16 01:58:02
```

## A migrate command

A real program needs more than `up`. It needs a way to see where a database is, to undo the last
change while developing, and to move to a given version. Here is the whole `main.ktm`, with a
`migrate` command for all of that:

```ktm
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/sqlite";
import cstr, sql, str, vec;

void usage() {
    printf("usage: notes migrate up | down [n] | to <version> | status | version\n");
}

/* arg as a whole number, or -1 */
i64 number(u8 *arg) {
    i64 n;
    if (!str.toInt(cstr.view(arg), &n) || n < 0) { return -1; }
    return n;
}

i32 migrateCommand(migrate.Migrations *ms, migrate.Driver *d, i32 argc, u8 **argv) {
    u8[] cmd = argc > 2 ? cstr.view(argv[2]) : "";
    if (str.equals(cmd, "up")) {
        i64 n = ms.up(d);
        printf("%d migration(s) applied, version %d\n", n, migrate.version(d));
    } else if (str.equals(cmd, "down")) {
        i64 n = argc > 3 ? number(argv[3]) : 1;
        if (n < 1) { usage(); return 2; }
        i64 r = ms.down(d, n);
        printf("%d migration(s) reverted, version %d\n", r, migrate.version(d));
    } else if (str.equals(cmd, "to") && argc > 3) {
        i64 v = number(argv[3]);
        if (v < 0) { usage(); return 2; }
        i64 r = ms.to(d, v);
        printf("%d migration(s) ran, version %d\n", r, migrate.version(d));
    } else if (str.equals(cmd, "status")) {
        vec.Vec<migrate.Status> st = ms.status(d);
        defer st.free();
        for (auto s : st) {
            printf("%4d  %-8s %.*s\n", s.version, s.applied ? "applied" : "pending",
                   s.name.len, s.name.ptr);
        }
    } else if (str.equals(cmd, "version")) {
        printf("%d\n", migrate.version(d));
    } else {
        usage();
        return 2;
    }
    return 0;
}

i32 main(i32 argc, u8 **argv) {
    if (argc < 2 || !str.equals(cstr.view(argv[1]), "migrate")) {
        usage();
        return 2;
    }
    sql.Db *db = sql.open("notes.db");
    if (!db) {
        printf("can't open notes.db\n");
        return 1;
    }
    defer sql.close(db);

    try {
        migrate.Migrations ms = migrate.load("migrations");
        defer ms.free();
        migrate.Driver d = sqlite.driver(db);
        return migrateCommand(&ms, &d, argc, argv);
    } catch (Error e) {
        printf("%s\n", e.message);
        return 1;
    }
    return 0;
}
```

Every call of the runner throws `migrate.ERR_MIGRATE` when something goes wrong, and so does
`load`. The message names the file and says what the database said, so `main` catches it and
prints it. [When a migration fails](#when-a-migration-fails) shows what the messages look like.

On a new database:

```sh
$ ktm build
$ ./notes migrate status
   1  pending  create_notes
   2  pending  create_tags
$ ./notes migrate up
2 migration(s) applied, version 2
$ ./notes migrate status
   1  applied  create_notes
   2  applied  create_tags
$ ./notes migrate version
2
$ ./notes migrate down
1 migration(s) reverted, version 1
$ ./notes migrate to 0
1 migration(s) ran, version 0
$ ./notes migrate to 2
2 migration(s) ran, version 2
$ ./notes migrate down 5
2 migration(s) reverted, version 0
$ ./notes migrate to 7
migrate: there is no migration 7
```

- `ms.status(&d)` lists every migration with `version`, `name` and `applied`. The `vec.Vec` is
  yours to free. The names are views of `ms`, so free `ms` after you are done with them.
- `ms.down(&d, n)` reverts the newest `n` applied migrations, newest first. Without `n` it reverts
  one. It stops early when nothing is left, so `down 5` on two migrations reverts two.
- `ms.to(&d, version)` goes up or down until `version` is the newest applied. `to 0` reverts
  everything. A version that isn't a migration is an error.
- A down script throws away what its up script made. `down` on `create_tags` drops the tags,
  along with every row in that table. Use it while developing, and think twice in production.

## Migrating at start-up

The usual place to migrate is when the program starts, before it serves anything. Then a new
build that needs a new column brings it along. Add a third migration, a `pinned` flag on notes.

`migrations/003_add_pinned.up.sql`:

```sql
ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
```

`migrations/003_add_pinned.down.sql`:

```sql
ALTER TABLE notes DROP COLUMN pinned;
```

`DROP COLUMN` needs SQLite 3.35 (2021) or newer.

The program gets three commands of its own, `add`, `pin` and `list`. Each runs after `ms.up`, so
it always finds the tables it expects. The `migrate` command stays as it was. `usage` changes;
`number` and `migrateCommand` are the same as in the last section and are repeated here so the
file is whole:

```ktm
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/sqlite";
import cstr, sql, str, vec;

void usage() {
    printf("usage: notes add <title> [body] | pin <id> | list\n"
           "       notes migrate up | down [n] | to <version> | status | version\n");
}

/* arg as a whole number, or -1 */
i64 number(u8 *arg) {
    i64 n;
    if (!str.toInt(cstr.view(arg), &n) || n < 0) { return -1; }
    return n;
}

i32 migrateCommand(migrate.Migrations *ms, migrate.Driver *d, i32 argc, u8 **argv) {
    u8[] cmd = argc > 2 ? cstr.view(argv[2]) : "";
    if (str.equals(cmd, "up")) {
        i64 n = ms.up(d);
        printf("%d migration(s) applied, version %d\n", n, migrate.version(d));
    } else if (str.equals(cmd, "down")) {
        i64 n = argc > 3 ? number(argv[3]) : 1;
        if (n < 1) { usage(); return 2; }
        i64 r = ms.down(d, n);
        printf("%d migration(s) reverted, version %d\n", r, migrate.version(d));
    } else if (str.equals(cmd, "to") && argc > 3) {
        i64 v = number(argv[3]);
        if (v < 0) { usage(); return 2; }
        i64 r = ms.to(d, v);
        printf("%d migration(s) ran, version %d\n", r, migrate.version(d));
    } else if (str.equals(cmd, "status")) {
        vec.Vec<migrate.Status> st = ms.status(d);
        defer st.free();
        for (auto s : st) {
            printf("%4d  %-8s %.*s\n", s.version, s.applied ? "applied" : "pending",
                   s.name.len, s.name.ptr);
        }
    } else if (str.equals(cmd, "version")) {
        printf("%d\n", migrate.version(d));
    } else {
        usage();
        return 2;
    }
    return 0;
}

i32 add(sql.Db *db, u8 *title, u8 *body) {
    sql.Param p[2];
    p[0].type = sql.TEXT;
    p[0].str = cstr.view(title);
    p[1].type = sql.TEXT;
    p[1].str = cstr.view(body);
    if (!sql.exec(db, "INSERT INTO notes (title, body) VALUES (?, ?)", p, 2)) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return 1;
    }
    return 0;
}

i32 pin(sql.Db *db, u8 *arg) {
    sql.Param p[1];
    p[0].type = sql.INT;
    p[0].intVal = number(arg);
    if (!sql.exec(db, "UPDATE notes SET pinned = 1 WHERE id = ?", p, 1)) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return 1;
    }
    return 0;
}

i32 list(sql.Db *db) {
    sql.Row row;
    if (!sql.select(db, &row, "SELECT id, pinned, title FROM notes ORDER BY pinned DESC, id")) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return 1;
    }
    while (sql.next(&row)) {
        printf("%3d %s %.*s\n", row.col[0].intVal, row.col[1].intVal ? "*" : " ",
               row.col[2].str.len, row.col[2].str.ptr);
    }
    return 0;
}

i32 main(i32 argc, u8 **argv) {
    if (argc < 2) {
        usage();
        return 2;
    }
    u8[] cmd = cstr.view(argv[1]);
    sql.Db *db = sql.open("notes.db");
    if (!db) {
        printf("can't open notes.db\n");
        return 1;
    }
    defer sql.close(db);
    sql.exec(db, "PRAGMA foreign_keys = ON");

    try {
        migrate.Migrations ms = migrate.load("migrations");
        defer ms.free();
        migrate.Driver d = sqlite.driver(db);
        if (str.equals(cmd, "migrate")) {
            return migrateCommand(&ms, &d, argc, argv);
        }
        ms.up(&d);
    } catch (Error e) {
        printf("%s\n", e.message);
        return 1;
    }

    if (str.equals(cmd, "add") && argc > 2) {
        return add(db, argv[2], argc > 3 ? argv[3] : "");
    } else if (str.equals(cmd, "pin") && argc > 2) {
        return pin(db, argv[2]);
    } else if (str.equals(cmd, "list")) {
        return list(db);
    }
    usage();
    return 2;
}
```

```sh
$ ktm build
$ ./notes add "buy milk"
$ ./notes add "call ann" "about the trip"
$ ./notes add "fix the bike"
$ ./notes pin 2
$ ./notes list
  2 * call ann
  1   buy milk
  3   fix the bike
$ ./notes migrate status
   1  applied  create_notes
   2  applied  create_tags
   3  applied  add_pinned
```

The first `add` found the database at version 2, so it applied `add_pinned` first. A copy of
the program that is still at version 2 somewhere else will do the same on its next start.

Reverting `add_pinned` drops the column and the pins with it. The next normal command applies it
again, with every pin at its default, `0`:

```sh
$ ./notes migrate down
1 migration(s) reverted, version 2
$ ./notes list
  1   buy milk
  2   call ann
  3   fix the bike
$ ./notes migrate up
0 migration(s) applied, version 3
```

- Migrate before anything else touches the database: before a server listens, before workers
  start.
- `ms.up` only does work when something is pending. On an up-to-date database it costs one query.
- SQLite checks foreign keys (`ON DELETE CASCADE` on `tags`) only when the connection turns them
  on, so `main` runs `PRAGMA foreign_keys = ON` after opening. The setting belongs to the
  connection, not the file.

## Changes SQLite can't make with ALTER TABLE

SQLite's `ALTER TABLE` can add, rename and drop columns, but it can't add a `CHECK` or a foreign
key to an existing table, or change a column's type. For those, SQLite's own instructions are to
build a new table, copy the rows over, drop the old one and rename the new one. While that runs,
foreign keys have to be off, or dropping `notes` would delete every tag through `ON DELETE
CASCADE`. But `PRAGMA foreign_keys` does nothing inside a transaction, and every migration runs
inside one.

A script whose first line is `-- migrate: no-transaction` runs without the runner's transaction.
This one turns foreign keys off, then opens its own transaction.

`migrations/004_title_limit.up.sql`:

```sql
-- migrate: no-transaction
-- SQLite can't add a CHECK to an existing table: build a new one and copy
-- the rows. Foreign keys must be off while the old table is dropped, and
-- that PRAGMA does nothing inside a transaction, so this script runs its own.
PRAGMA foreign_keys = OFF;
BEGIN;
CREATE TABLE notes_new (
    id     INTEGER PRIMARY KEY,
    title  TEXT NOT NULL CHECK (length(title) <= 200),
    body   TEXT NOT NULL DEFAULT '',
    pinned INTEGER NOT NULL DEFAULT 0
);
INSERT INTO notes_new (id, title, body, pinned) SELECT id, title, body, pinned FROM notes;
DROP TABLE notes;
ALTER TABLE notes_new RENAME TO notes;
PRAGMA foreign_key_check;
COMMIT;
PRAGMA foreign_keys = ON;
```

`migrations/004_title_limit.down.sql` does the same, without the `CHECK`:

```sql
-- migrate: no-transaction
PRAGMA foreign_keys = OFF;
BEGIN;
CREATE TABLE notes_new (
    id     INTEGER PRIMARY KEY,
    title  TEXT NOT NULL,
    body   TEXT NOT NULL DEFAULT '',
    pinned INTEGER NOT NULL DEFAULT 0
);
INSERT INTO notes_new (id, title, body, pinned) SELECT id, title, body, pinned FROM notes;
DROP TABLE notes;
ALTER TABLE notes_new RENAME TO notes;
PRAGMA foreign_key_check;
COMMIT;
PRAGMA foreign_keys = ON;
```

```sh
$ ./notes pin 2
$ ./notes migrate up
1 migration(s) applied, version 4
$ ./notes list
  2 * call ann
  1   buy milk
  3   fix the bike
$ ./notes add "$(printf 'x%.0s' $(seq 1 201))"
CHECK constraint failed: length(title) <= 200
```

The notes and the pin survived, and so does every tag: with foreign keys on, `DROP TABLE notes`
would have deleted them all. The new table has the limit.

- The marker must be the first line of the script, exactly `-- migrate: no-transaction`
  (whitespace around it doesn't matter).
- Without the runner's transaction, the script and its row in `schema_migrations` are no longer
  one step. Give the script its own `BEGIN` and `COMMIT` whenever it can have them, as this one
  does.
- When a no-transaction script fails, the error ends in `(it ran without a transaction: what ran
  before the error stays)`. Here everything after `BEGIN` is undone when the program closes the
  database without a `COMMIT`. The migration stays pending, so it can run again once the data is
  fixed.

## When a migration fails

A migration that fails is rolled back, the error names its file, and the migrations before it
stay applied. Here is a migration with a typo, `notebook` for `notebooks`:

`migrations/005_add_notebooks.up.sql`:

```sql
CREATE TABLE notebooks (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
ALTER TABLE notes ADD COLUMN notebook_id INTEGER REFERENCES notebook (id);
INSERT INTO notebook (name) VALUES ('inbox');
```

```sh
$ ./notes migrate up
migrate: 5_add_notebooks.up.sql: no such table: notebook
$ ./notes migrate status
   1  applied  create_notes
   2  applied  create_tags
   3  applied  add_pinned
   4  applied  title_limit
   5  pending  add_notebooks
$ sqlite3 notes.db "SELECT name FROM sqlite_master WHERE name = 'notebooks'"
$
```

The `notebooks` table the first statement made is gone too, since the whole migration was one
transaction. Fix the file and run `up` again. The file name in the message is built from the
version number, so `005_add_notebooks` shows as `5_add_notebooks`.

To act on the failure in code, catch the error and look at its code:

```ktm
try {
    ms.up(&d);
} catch (Error e) {
    if (e.code == migrate.ERR_MIGRATE) {
        printf("the database is not ready: %s\n", e.message);
        return 1;
    }
    throw(e);
}
```

`load` checks the files before anything runs, and throws `migrate.ERR_MIGRATE` too:

| Problem | Message |
| --- | --- |
| a `.sql` file with another kind of name | `migrate: 5-oops.up.sql: name it NNN_name.up.sql or NNN_name.down.sql (NNN a number, the name letters, digits, _ and -)` |
| a version with two names | `migrate: version 3 is add_pinned and other: one name per version` |
| a down file without its up file | `migrate: 6_x.down.sql has no 6_x.up.sql` |
| the directory isn't there | `migrate: can't read the directory migrations` |

And the runner refuses what it can't do:

| Problem | Message |
| --- | --- |
| `down` over a migration without a down file | `migrate: 4_index_titles has no down migration` |
| `to` a version that isn't a migration | `migrate: there is no migration 7` |
| `down` over a version the database has but this program doesn't | `migrate: version 5 is applied, but there is no migration 5 here` |

## Testing migrations

A down script that nobody runs is a down script that doesn't work. A test can run every
migration up, all the way down and up again, on an empty database in memory. Put it next to
`main.ktm`, in `migrations_test.ktm`:

```ktm
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/sqlite";
import sql, testing;

/* Every migration goes up, all the way down and up again, on a fresh
 * database in memory */
void testMigrationsRoundTrip(testing.T *t) {
    sql.Db *db = sql.open(":memory:");
    defer sql.close(db);
    migrate.Migrations ms = migrate.load("migrations");
    defer ms.free();
    migrate.Driver d = sqlite.driver(db);

    i64 all = ms.items.len;
    t.equal(ms.up(&d), all);
    t.equal(ms.to(&d, 0), all);
    t.equal(migrate.version(&d), 0);
    t.equal(ms.up(&d), all);
}
```

```sh
$ ktm test
ok  	.	0.00s  (1 passed)
$ ktm test -jit -Memsafe
ok  	.	0.00s  (1 passed)
```

`ms.items` is the `vec.Vec<migrate.Migration>` that `load` filled, so `ms.items.len` is how many
there are. A down script with a mistake fails the test and names the file:

```output
--- FAIL: testMigrationsRoundTrip (0.00s)
    migrate.ktm:275: threw 1835466753: migrate: 2_create_tags.down.sql: no such table: tagz
FAIL
```

A migration without a down file makes `to(&d, 0)` throw, so this test also catches a missing one.
A migration that should never be reverted needs a test of its own that stops above it.

## Migrations built into the binary

`load` reads files at run time, so the binary needs its `migrations/` directory next to it. To
ship one file instead, add the migrations in code with `ms.add(version, name, up, down)`. The
texts are copied, so they can come from anywhere.

```ktm
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/sqlite";
import sql;

/* The schema, built into the binary: nothing to ship next to it */
void schema(migrate.Migrations *ms) {
    ms.add(1, "create_notes",
           "CREATE TABLE notes (\n"
           "    id    INTEGER PRIMARY KEY,\n"
           "    title TEXT NOT NULL,\n"
           "    body  TEXT NOT NULL DEFAULT ''\n"
           ");\n",
           "DROP TABLE notes;\n");
    ms.add(2, "create_tags",
           "CREATE TABLE tags (\n"
           "    note_id INTEGER NOT NULL REFERENCES notes (id) ON DELETE CASCADE,\n"
           "    tag     TEXT NOT NULL,\n"
           "    PRIMARY KEY (note_id, tag)\n"
           ");\n"
           "CREATE INDEX tags_by_tag ON tags (tag);\n",
           "DROP INDEX tags_by_tag;\n"
           "DROP TABLE tags;\n");
    ms.add(3, "add_pinned",
           "ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;\n",
           "ALTER TABLE notes DROP COLUMN pinned;\n");
}

i32 main() {
    sql.Db *db = sql.open(":memory:");
    defer sql.close(db);
    migrate.Migrations ms;
    defer ms.free();
    schema(&ms);
    migrate.Driver d = sqlite.driver(db);
    printf("%d migration(s) applied, version %d\n", ms.up(&d), migrate.version(&d));
    return 0;
}
```

```sh
$ ktm run .
3 migration(s) applied, version 3
```

- `ms.add` may be called in any order; the migrations are kept sorted by version.
- The down script may be left out (`ms.add(4, "seed", "INSERT ...")`), and so may an empty one.
  That migration then can't be reverted.
- `ms.add` throws `migrate.ERR_MIGRATE` for a version below 1, a name with other characters, or a
  version that is already there.
- A `migrate.Migrations` declared like this starts empty. Free it with `ms.free()` the same way.
- The `-- migrate: no-transaction` marker works the same in code.

## PostgreSQL

The same program on PostgreSQL needs ktm-pg too, since `main` imports it directly. It works on
any ktm; SQLite isn't needed.

```sh
$ mkdir notes && cd notes
$ ktm mod init example.com/me/notes
ktm: created ktm.mod for example.com/me/notes
$ ktm get github.com/ozzenerol/ktm-migrate
ktm: added github.com/ozzenerol/ktm-migrate v0.1.1
$ ktm get github.com/ozzenerol/ktm-pg
ktm: added github.com/ozzenerol/ktm-pg v0.2.1
$ cat ktm.mod
module example.com/me/notes

ktm 0.25

require (
	github.com/ozzenerol/ktm-migrate v0.1.1
	github.com/ozzenerol/ktm-pg v0.2.1
)
```

Migrations are written in the database's own SQL, so they are written again for Postgres.

`migrations/001_create_notes.up.sql`:

```sql
CREATE TABLE notes (
    id         bigserial PRIMARY KEY,
    title      text NOT NULL,
    body       text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);
```

`migrations/001_create_notes.down.sql`:

```sql
DROP TABLE notes;
```

`migrations/002_create_tags.up.sql`:

```sql
CREATE TABLE tags (
    note_id bigint NOT NULL REFERENCES notes (id) ON DELETE CASCADE,
    tag     text NOT NULL,
    PRIMARY KEY (note_id, tag)
);
```

`migrations/002_create_tags.down.sql`:

```sql
DROP TABLE tags;
```

Postgres can make changes to a table in a transaction, which SQLite can't. An index on a big
table is the exception. A plain `CREATE INDEX` blocks writes to the table until it finishes, and
`CREATE INDEX CONCURRENTLY` doesn't, but Postgres refuses to run it inside a transaction. That
is what `-- migrate: no-transaction` is for.

`migrations/003_index_tags.up.sql`:

```sql
-- migrate: no-transaction
CREATE INDEX CONCURRENTLY IF NOT EXISTS tags_by_tag ON tags (tag);
```

`migrations/003_index_tags.down.sql`:

```sql
-- migrate: no-transaction
DROP INDEX CONCURRENTLY IF EXISTS tags_by_tag;
```

Without a transaction, a failed `CREATE INDEX CONCURRENTLY` isn't rolled back: it leaves an
invalid index named `tags_by_tag` behind, and `IF NOT EXISTS` would then skip it on the next run.
After such a failure, `DROP INDEX tags_by_tag` before running the migration again.

`main.ktm` connects with ktm-pg and makes the driver with `postgres.driver(c)`. `usage`, `number`
and `migrateCommand` are the same as in [A migrate command](#a-migrate-command):

```ktm
import "github.com/ozzenerol/ktm-pg";
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/postgres";
import cstr, str, vec;

void usage() {
    printf("usage: notes migrate up | down [n] | to <version> | status | version\n");
}

/* arg as a whole number, or -1 */
i64 number(u8 *arg) {
    i64 n;
    if (!str.toInt(cstr.view(arg), &n) || n < 0) { return -1; }
    return n;
}

i32 migrateCommand(migrate.Migrations *ms, migrate.Driver *d, i32 argc, u8 **argv) {
    u8[] cmd = argc > 2 ? cstr.view(argv[2]) : "";
    if (str.equals(cmd, "up")) {
        i64 n = ms.up(d);
        printf("%d migration(s) applied, version %d\n", n, migrate.version(d));
    } else if (str.equals(cmd, "down")) {
        i64 n = argc > 3 ? number(argv[3]) : 1;
        if (n < 1) { usage(); return 2; }
        i64 r = ms.down(d, n);
        printf("%d migration(s) reverted, version %d\n", r, migrate.version(d));
    } else if (str.equals(cmd, "to") && argc > 3) {
        i64 v = number(argv[3]);
        if (v < 0) { usage(); return 2; }
        i64 r = ms.to(d, v);
        printf("%d migration(s) ran, version %d\n", r, migrate.version(d));
    } else if (str.equals(cmd, "status")) {
        vec.Vec<migrate.Status> st = ms.status(d);
        defer st.free();
        for (auto s : st) {
            printf("%4d  %-8s %.*s\n", s.version, s.applied ? "applied" : "pending",
                   s.name.len, s.name.ptr);
        }
    } else if (str.equals(cmd, "version")) {
        printf("%d\n", migrate.version(d));
    } else {
        usage();
        return 2;
    }
    return 0;
}

i32 main(i32 argc, u8 **argv) {
    if (argc < 2 || !str.equals(cstr.view(argv[1]), "migrate")) {
        usage();
        return 2;
    }
    try {
        pg.Conn *c = pg.connect();      /* PGHOST, PGUSER, ... or a URL */
        defer c.close();
        migrate.Migrations ms = migrate.load("migrations");
        defer ms.free();
        migrate.Driver d = postgres.driver(c);
        return migrateCommand(&ms, &d, argc, argv);
    } catch (Error e) {
        printf("%s\n", e.message);
        return 1;
    }
    return 0;
}
```

`pg.connect()` with no URL takes the server from `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` and
`PGDATABASE`. A URL works too: `pg.connect("postgres://ann:secret@localhost:5432/notes")`. It
throws when it can't connect, so it goes inside the `try`.

```sh
$ export PGHOST=127.0.0.1 PGUSER=postgres PGPASSWORD=secret PGDATABASE=notes
$ ktm build
$ ./notes migrate status
   1  pending  create_notes
   2  pending  create_tags
   3  pending  index_tags
$ ./notes migrate up
3 migration(s) applied, version 3
$ ./notes migrate down
1 migration(s) reverted, version 2
$ ./notes migrate to 0
2 migration(s) ran, version 0
$ ./notes migrate up
3 migration(s) applied, version 3
$ PGPASSWORD=wrong ./notes migrate up
pg: FATAL: password authentication failed for user "postgres" (SQLSTATE 28P01)
$ psql -c 'SELECT * FROM schema_migrations'
 version |     name     |         applied_at
---------+--------------+----------------------------
       1 | create_notes | 2026-10-16 02:05:19.15737
       2 | create_tags  | 2026-10-16 02:05:19.161077
       3 | index_tags   | 2026-10-16 02:05:19.163924
(3 rows)
```

Errors from Postgres end with their SQLSTATE. The migration with a typo from [When a migration
fails](#when-a-migration-fails), written for Postgres as `004_add_notebooks.up.sql`:

```output
migrate: 4_add_notebooks.up.sql: relation "notebook" does not exist (SQLSTATE 42P01)
```

### With a pool

A server usually has a `pg.Pool`, not one connection. `postgres.driverPool(pool)` takes one
connection from the pool for each call (`up`, `down`, `to`, `status`, `version`) and gives it
back when the call ends, after a failure too. Only `main` changes:

```ktm
i32 main(i32 argc, u8 **argv) {
    if (argc < 2 || !str.equals(cstr.view(argv[1]), "migrate")) {
        usage();
        return 2;
    }
    try {
        pg.Pool *pool = pg.open();
        defer pool.close();
        migrate.Migrations ms = migrate.load("migrations");
        defer ms.free();
        migrate.Driver d = postgres.driverPool(pool);
        return migrateCommand(&ms, &d, argc, argv);
    } catch (Error e) {
        printf("%s\n", e.message);
        return 1;
    }
    return 0;
}
```

In a server, run `ms.up(&d)` on the pool before it starts taking requests, and keep using the
same pool afterwards. Either way, the connection or the pool stays yours to close.

## Several instances at once

Two copies of a program that start together, such as two containers of one service, must not
both apply the same migration. Each driver has a lock for that:

- **SQLite:** each migration starts with `BEGIN IMMEDIATE`, which takes the database file's write
  lock. A second program waits for it, up to 10 seconds (the driver sets `busy_timeout` to
  10000), then finds the migration already applied and skips it.
- **PostgreSQL:** the runner holds `pg_advisory_lock(postgres.LOCK_KEY)` for the whole call, so
  the instances take turns. The later ones find nothing left to do.
- Inside each migration's transaction, the runner checks again whether the migration is applied.
  A migration never runs twice.

Two programs started together on one SQLite file, with a migration that takes a few seconds:

```sh
$ ./notes migrate up & ./notes migrate up
0 migration(s) applied, version 4
4 migration(s) applied, version 4
```

On PostgreSQL there is a catch in ktm-migrate v0.1.1. When the pending migrations include a
`CREATE INDEX CONCURRENTLY`, two instances that start together deadlock. `CREATE INDEX
CONCURRENTLY` waits for every open transaction to end, including the other instance's query that
is waiting for the lock. Postgres breaks the deadlock by failing the waiting instance:

```output
deadlock detected (SQLSTATE 40P01); detail: Process 106 waits for ExclusiveLock on advisory lock [5,27508,1835886951,1]; blocked by process 105.
```

The first instance finishes the migrations. The failed one finds nothing to do when it starts
again. Until this is fixed, deploy a concurrent index build with one instance, or run `migrate
up` as a step of its own before the instances start.

## Branches and merged migrations

Two people on two branches can each add a migration. Numbering them `003` on both sides clashes
when the branches merge: `load` refuses a version with two names. Versions made from the time
(`YYYYMMDDHHMMSS`) don't clash:

```sh
migrations/
  20261001090000_create_a.up.sql
  20261002090000_create_b.up.sql     from the branch merged last
  20261003090000_create_c.up.sql
```

`up` applies every migration the database doesn't have, including one whose version is lower
than the newest applied:

```sh
$ ./notes migrate status
20261001090000  applied  create_a
20261002090000  pending  create_b
20261003090000  applied  create_c
$ ./notes migrate up
1 migration(s) applied, version 20261003090000
```

The other way round, a database can have a version this build doesn't know, because a newer
build ran it. `status` lists it with an empty name, and `down` refuses to go past it:

```sh
$ ./notes migrate status
   1  applied  create_notes
   2  applied  create_tags
   3  applied  add_pinned
   4  applied  title_limit
   5  applied
$ ./notes migrate down
migrate: version 5 is applied, but there is no migration 5 here
```

`up` leaves such a version alone, so an older build still starts against a newer database.

## A driver of your own

Any SQL engine can use the runner through a `migrate.Driver`: four functions and a few fields.

```ktm
migrate.Driver d;
d.ctx = myDb;                // yours: a connection, a database, a pool
d.exec = &myExec;            // void myExec(migrate.Driver *d, u8[] script): runs a script of statements
d.queryInts = &myQueryInts;  // void myQueryInts(migrate.Driver *d, u8[] sql, vec.Vec<i64> *out): the first column of each row
d.lock = &myLock;            // void myLock(migrate.Driver *d), or null: called when a call starts
d.unlock = &myUnlock;        // and when it ends, after a failure too
d.begin = "BEGIN";           // the statement that starts a transaction ("" is BEGIN)
```

- Each function gets the Driver, and throws on an error. The runner adds the file name to the
  message.
- `d.ctx` and `d.conn` are for the driver. A driver over a pool sets `d.conn` in `lock` to the
  connection it took, uses it in `exec` and `queryInts`, and gives it back in `unlock`.
- The runner only sends `CREATE TABLE IF NOT EXISTS`, `SELECT`, `INSERT`, `DELETE`, the begin
  statement, `COMMIT`, `ROLLBACK` and the migrations' scripts. An engine that runs those works.

A driver can also wrap another one. This one prints every statement the runner sends, then hands
it to the SQLite driver, which its `ctx` points to:

```ktm
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/sqlite";
import sql, str, vec;

/* A Driver that prints every statement the runner sends, then hands it
 * to another Driver (ctx points to it) */
void logExec(migrate.Driver *d, u8[] script) {
    migrate.Driver *inner = d.ctx;
    u8[] s = str.trim(script);
    printf("exec  %.*s\n", s.len, s.ptr);
    inner.exec(inner, script);
}

void logQueryInts(migrate.Driver *d, u8[] query, vec.Vec<i64> *out) {
    migrate.Driver *inner = d.ctx;
    printf("query %.*s\n", query.len, query.ptr);
    inner.queryInts(inner, query, out);
}

void logLock(migrate.Driver *d) {
    migrate.Driver *inner = d.ctx;
    printf("lock\n");
    if (inner.lock) { inner.lock(inner); }
}

void logUnlock(migrate.Driver *d) {
    migrate.Driver *inner = d.ctx;
    printf("unlock\n");
    if (inner.unlock) { inner.unlock(inner); }
}

migrate.Driver logged(migrate.Driver *inner) {
    migrate.Driver d;
    d.ctx = inner;
    d.exec = &logExec;
    d.queryInts = &logQueryInts;
    d.lock = &logLock;
    d.unlock = &logUnlock;
    d.begin = inner.begin;
    return d;
}

i32 main() {
    sql.Db *db = sql.open(":memory:");
    defer sql.close(db);
    migrate.Migrations ms;
    defer ms.free();
    ms.add(1, "create_notes", "CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL);",
           "DROP TABLE notes;");

    migrate.Driver inner = sqlite.driver(db);
    migrate.Driver d = logged(&inner);
    ms.up(&d);
    return 0;
}
```

```sh
$ ktm run .
lock
exec  CREATE TABLE IF NOT EXISTS schema_migrations (version bigint PRIMARY KEY, name text NOT NULL, applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP)
query SELECT version FROM schema_migrations ORDER BY version
exec  BEGIN IMMEDIATE
query SELECT version FROM schema_migrations WHERE version = 1
exec  CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL);
exec  INSERT INTO schema_migrations (version, name) VALUES (1, 'create_notes')
exec  COMMIT
unlock
```

That is everything a call of `up` does: take the lock, make the table, read what ran, and then
for each pending migration, begin, check again, run the script, record it and commit.

## Reference

| Call | What it does |
| --- | --- |
| `migrate.load(dir)` | the migrations in `dir`, sorted by version |
| `ms.add(version, name, up, down)` | adds a migration written in the program; `down` may be left out |
| `ms.up(&d)` | applies every pending migration in version order; returns how many ran |
| `ms.down(&d, n)` | reverts the newest `n` applied migrations (1 when left out); returns how many ran |
| `ms.to(&d, version)` | goes up or down until `version` is the newest applied; `0` reverts everything |
| `ms.status(&d)` | a `vec.Vec<migrate.Status>`: every migration here and every version the database has |
| `migrate.version(&d)` | the newest applied version, `0` for none |
| `ms.free()` | frees the migrations |
| `sqlite.driver(db)` | a Driver for an `sql.Db *` |
| `postgres.driver(c)` | a Driver for a `pg.Conn *` |
| `postgres.driverPool(pool)` | a Driver for a `pg.Pool *`, one connection per call |
| `migrate.ERR_MIGRATE` | the code of every error `load`, `add` and the runner throw |
| `postgres.LOCK_KEY` | the advisory lock's key |

| Type | Fields |
| --- | --- |
| `migrate.Migrations` | `items`, a `vec.Vec<migrate.Migration>` |
| `migrate.Migration` | `version`, `name`, `up`, `down` (`String`s), `hasDown` |
| `migrate.Status` | `version`, `name` (a `u8[]`, empty for a version only the database has), `applied` |
| `migrate.Driver` | `ctx`, `conn`, `exec`, `queryInts`, `lock`, `unlock`, `begin` |

## Rules of thumb

- **Never change a migration that has run anywhere.** The runner records versions, not the
  scripts' contents, so it won't notice: the databases that ran the old text keep it. Add a new
  migration that makes the change.
- **One change per migration**, with a name that says what it does. A small migration is easy to
  revert and its error is easy to read.
- **Write the down script with the up script,** and run the round-trip test, so `down` works when
  you need it.
- **Data is part of the schema too.** A migration can `INSERT` or `UPDATE` rows (filling a new
  column from an old one), in the same transaction as the change that needs them.
- **Keep `schema_migrations` to the runner.** Change it by hand only to repair a database, and
  then with the program stopped.
- **Run migrations from one place:** at start-up or as a `migrate up` command before the new
  build starts, not both on a schedule of their own.
