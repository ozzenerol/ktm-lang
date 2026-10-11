# S2 (a): src/std into module directories, and the build

Branch feat/conversion, area (a) by ktm-lang-ef. (b)/(c) (tests, docs) by ktm-lang-6e on conv-ae.
Deleted before the final merge.

Steps (plan approved by asuslab-33):
- A1 split (done): tools/convert/split.py (feat/convert) put every item of the old src/std/*.ktm,
  std.ktmh and defs.ktmh into src/std/<module>/ per stdlib-modules.tsv: extern "c" declarations,
  C structs and constants to c/, the runtime (KTM_*, Fs, HCFs, CatchFrame) to rt/, helpers that
  leave the API private to their module, the dropped names removed (Dir, IsDotC/Z, Write, CH_*,
  RM_VERBOSE, TRUE/FALSE/NULL, __BUFSIZ__), the prelude's names left to src/std/prelude.
  The files are still in the OLD syntax: the tree does not build from here until A2-A4.
- A2 (raw done): tools/convert/convert-std.sh OLD_STD src/std (feat/convert 918ad88): split + one ktm-convert run through a
  root importing all of them (`#define __KTM_LINK_SQLITE3__` + `import <m>;`), --map --names --renames
  --layout, 0 errors; notes in ~/opensource/ktm-scratch/conv-notes/std.err. To regenerate: split.py
  from the old src/std (3585f56), then the same run. Hand fixes next (Write -> c.write, the #if
  branches converted from text only, public -> pub check, ...).
- A2 (plan) convert each module with ktm-convert (--map --names --renames --layout), through a root
  that imports every module; hand fixes (Write -> c.write, Fs in coro, ...).
- A3 compiler: std.ktmh optional; runtime calls to rt.__ktm*; -Memsafe and the printf check on
  the module names.
- A4 build: build.sh, install, packaging, release.yml: `ktm -lib ktm <prefix>/modules`.
- A5 check: stdlib builds, no import cycles, 421-440, then (b)'s tests compile.

## 2026-10-11 WIP (feat/convert aee6f9e)
- A3 done in the compiler: the runtime is called as rt.__ktm* (astRuntimeName/astRuntimeSymbol), the
  format check knows str.format & co. and counts `...args` (count + array), %D/%T/%F are compile
  errors naming the replacement, -Memsafe also overrides mem's symbols, libktm without a header
  exports every non-static symbol (weak).
- std-fixes 03 (coro's asm calls coroMain by its mangled symbol) and 04 (date.format(d, fmt)).
- The whole tree builds into one libktm: `ktm -fPIC -lib ktm <prefix>/modules`, 0 errors, and programs
  link against it.
- BLOCKER (asked asuslab-33): Y8 removed the arms' last breaks but S2 still falls through, so the
  stdlib misbehaves at run time (str.format segfaults). Proposal: keep the breaks in S2.
- Left: A4 build scripts, A5 checks.
