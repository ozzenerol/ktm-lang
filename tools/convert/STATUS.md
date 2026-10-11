# tools/convert: status (S1 of R1)

Branch feat/convert, worked on by ktm-lang-76. Rebased on rc/0.2.0 as it moves.

Done (each a commit, with corpus cases in corpus/, `./check.sh` all pass):
- stage 1 (types, true/false/null, struct/pub, casts, printf, calls, main, file-scope code)
- dry run: `dry-run.sh TREE > report.txt` (`--report` per file)
- 1a fn pointer declarators -> fn(P) R; 1b old (...) -> ...args; 1c throw('X')/Fs->except_ch
  -> enum Err + catch (Error e); 1d #define -> const (typed by the lexer's folding);
  1e switch: last break dropped, `fallthrough;` added; 1g braces on every body;
  1h keyword/type-named names get `_` (listed for src/std); 1i `--map stdlib-modules.tsv`
  (stdlib calls/types/macros into modules, c.x, prelude, container methods, imports,
  vec.qsort args); 1j `--names` (+ `--renames renames.tsv --renames renames-locals.tsv`)
  naming rule; 1k `--layout` (`->` to `.`, braces on the header line, 4-space indents)
Checks used: convert a copy of src/tests with all flags but --map and compile every test
(-S): 410, only 327 fails before B1's `fallthrough;`. Run check (JIT, converted vs original
result line): the differences are tests that compile programs from strings and check
error text/columns, the switch tests (behave after S3), 156/385 (old file-scope order).

Dry run: ~/opensource/ktm-scratch/dry-run-s1.txt (tools/convert/dry-run.sh . ~/opensource/hcc-builds).
- module files (S2): in a file that says `module x;`, x's own stdlib names stay bare (no `x.` and
  no self-import); `import`/`enum Err` lines go after the file's `module`/`import`/#include header.
  Convert a module's files through a root that imports it: `--root main.ktm dir/x/*.ktm`.
Open: --map/--names on src/std itself is S2 (ktm-lang-ef, area a).
