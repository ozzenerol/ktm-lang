# docs-src: the sources of the documentation site

`python3 docs-src/tools/build.py` writes the site into `docs/`, which GitHub Pages serves, and
the same chapters and library as man pages into `man/` (installed on Linux: `man ktm`).
`python3 docs-src/tools/check.py` compiles and runs every example and compares its output.
Never edit `docs/` by hand. The old site is kept, unchanged, in `docs-old/`.

```
docs-src/
  pages/index.md          the home page
  pages/releases.html     release notes (an HTML fragment, kept as it was)
  pages/known-issues.html the known bugs (an HTML fragment)
  language/NN-name.md     the language chapters, in order (NN = 01, 02, ...)
  guides/NN-name.md       the quick guides, in order (same format as a chapter)
  std/modules.json        the standard library's module groups and one-line summaries
  assets/                 style.css, site.js, std.js (copied to docs/assets)
  tools/                  build.py, md.py, gen_std.py, man.py, check.py
```

The standard library reference (`docs/std/`) is generated from `src/std`: every `pub`
declaration with the comment above it. Improve a function's documentation in its source.

## A language chapter

One topic per file, explained in depth, with many small examples. One column: never two
pieces of code side by side ("C vs ktm", "wrong vs right"). Show one, then the other, each
with a sentence.

```markdown
# Structs

A struct groups values under one name. This chapter covers declaring, initialising, copying,
nesting and laying out structs.

## Declaring a struct

Text in paragraphs. `inline code`, **bold**, *italic*, [links](03-operators.html),
[a symbol of the library](../std/#str.split).

```ktm run
struct Point { i64 x; i64 y; };

i32 main() {
    Point p = {3, 4};
    printf("%d %d\n", p.x, p.y);
    return 0;
}
```

```output
3 4
```

- lists
- of rules

| tables | work |
| --- | --- |
| yes | too |
```

Code blocks:

| info string | meaning |
| --- | --- |
| ` ```ktm run ` | a whole program: compiled and run (AOT and JIT); the next block must be ` ```output ` with exactly what it prints (stdout, then stderr) |
| ` ```ktm error ` | a whole program that must NOT compile; the next block is ` ```output ` holding a line of the error ktm prints (a substring is enough) |
| ` ```ktm ` | a fragment, shown only (not run): use it sparingly, a runnable example is better |
| ` ```output ` | what the block before it prints |
| ` ```sh ` | shell commands, shown only |

Rules for writing:

- The first line is `# Title`; the paragraph after it says what the chapter covers.
- `##` sections, `###` subsections. Each section: a few sentences, then examples, then the rules
  (`- ...`) that the examples don't make obvious.
- Prefer many short runnable examples over one long one. Every example must be real ktm that
  compiles today, with the exact output.
- Plain language, short sentences. No em dashes. No "Note:" boxes: say it in the text.
- Use the current syntax: `i64`, `u8 *`, `printf`, `if x > 0 {` or `if (x > 0) {` (both are ktm;
  pick one per chapter, the chapter on control flow shows both).
- Link to other chapters by file name (`07-structs.html`) and to the library by `../std/#module.name`.

## A quick guide

`docs-src/guides/NN-name.md`, under Quick Guides in the top bar. A guide is one task from an
empty directory to a program that works, in the same Markdown as a chapter. Every file is shown
whole, every command with what it prints. A guide's programs usually need packages (`ktm get`),
which `check.py` can't fetch, so they are ` ```ktm ` blocks: build each one in a real project and
run every command before committing, and again when a package it uses changes.
