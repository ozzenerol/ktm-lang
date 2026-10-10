<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo-dark.svg">
    <img src="assets/logo.svg" alt="ktm" width="280">
  </picture>
</p>

ktm is a small compiled language for systems programming and networking:
REST microservices, network daemons and command-line tools. The compiler,
written in C, builds native x86_64 and AArch64 binaries ahead of time, runs
programs directly with a built-in JIT, and comes with a REPL, a language server
and a standard library (strings, containers, JSON, files, sockets and TCP/UDP
helpers, processes, signals, coroutines, threads, dates, optional SQLite).

```c
U0 Main()
{
  "Hello world\n";
}
```

Sources are `.ktm` files, headers `.ktmh`.

**Documentation: [ozzenerol.github.io/ktm-lang](https://ozzenerol.github.io/ktm-lang/)**

## What's in it

- **Compiler** with two native back ends (x86_64, AArch64), Linux and macOS, plus a JIT
  (`ktm -jit file.ktm`) and a REPL that keep the same semantics as compiled code.
- **Standard library** for writing servers and tools: sockets with timeouts, IPv4/IPv6,
  UDP, `poll`, processes without a shell (`ProcSpawn`), `sigaction` and clean shutdown,
  coroutines, threads and a thread pool, JSON, hash maps, vectors, dates.
- **Diagnostics** that point at the right place, and recover instead of cascading.
- **Tests everywhere**: about 400 numbered tests run both compiled and in the JIT on
  Linux x86_64, Linux arm64 and macOS, LSP tests, and every example in the docs is
  compiled and run.
- **Documentation** at **[ozzenerol.github.io/ktm-lang](https://ozzenerol.github.io/ktm-lang/)** (built from `docs/`):
  getting started, the language, the library, tools, examples and a
  [known-issues](https://ozzenerol.github.io/ktm-lang/known-issues.html) page.

## Building

Requirements: a C compiler (gcc or clang), `make`, `cmake`, and Python 3 for
the docs checks. Linux (x86_64, arm64) and macOS (arm64) are supported and tested;
macOS on Intel is not supported. On Windows
use [WSL2](https://learn.microsoft.com/en-us/windows/wsl/install).

```sh
./build.sh                 # ktm + libktm into ./build/prefix, ./ktm in the repo root
./build.sh --test          # ... and run the unit, JIT and LSP test suites
./build.sh --install       # ... and install into /usr/local (sudo only for the copy)
```

Other flags: `--debug` (AddressSanitizer build), `--sqlite` (link libktm with
SQLite), `--clean`. Environment overrides: `CC`, `JOBS`, `INSTALL_PREFIX`.
Run it as your normal user, not with sudo.

Prebuilt binaries for Linux x86_64/aarch64 and macOS (Apple Silicon) are attached to
[releases](https://github.com/ozzenerol/ktm-lang/releases); install one with
`packaging/install.sh`. What changed in each version is on the
[Releases page](https://ozzenerol.github.io/ktm-lang/releases.html).

## Using the compiler

```sh
ktm main.ktm -o main && ./main      # compile ahead of time
ktm -jit main.ktm                   # compile in memory and run
ktm -repl                          # interactive session
ktm -lsp                           # language server (see below)
ktm --help                         # everything else
```

When working from a source build without installing, point ktm at the local
prefix: `./ktm --install-dir=build/prefix main.ktm`.

### REPL
`ktm -repl` keeps functions, classes, globals and `#define`s as you type,
executes statements immediately and echoes the value of a trailing expression.
It has line editing, persistent history (`~/.ktm_repl_history`) and tab
completion. `Uf("Name");` disassembles a JIT-compiled function and
`ReplDel("name");` removes a definition so it can be redefined.
`~/.ktm_rc.ktm` is included at start-up (`#define KTM_NO_REPL_HELLO` there
silences the greeting). `@ <command>` runs a shell command.

### Shared libraries: `#link`
```c
#link "./mylib.so"   /* a path, relative to the working directory */
#link <sdl2>         /* a library name, like -lsdl2 */

extern "c" I64 MyLibAdd(I64 a, I64 b);
```
AOT builds pass these to the linker; the JIT and the REPL `dlopen` them.

### JIT or AOT: `#ifjit` / `#ifaot`
Shorthand for `#ifdef __KTM_JIT__` / `#ifdef __KTM_AOT__`: exactly one is
always defined.

### Language server
`ktm -lsp` gives diagnostics, go-to-definition, hover, completion and rename.
Neovim setup:

```lua
vim.filetype.add({ extension = { ktm = "ktm", ktmh = "ktm" } })
vim.api.nvim_create_autocmd("FileType", {
  pattern = "ktm",
  callback = function(ev)
    vim.lsp.start({
      name = "ktm",
      cmd = { "ktm", "-lsp" },
      root_dir = vim.fs.root(ev.buf, { ".git" })
                 or vim.fs.dirname(vim.api.nvim_buf_get_name(ev.buf)),
    })
  end,
})
```

### Other tools
`-cfg` / `-cfg-png` / `-cfg-svg` draw a function's control-flow graph
(needs [graphviz](https://graphviz.org/)); `-transpile` turns ktm into C
(experimental and frozen: no new features). See [Tools](https://ozzenerol.github.io/ktm-lang/tools.html).

## Testing

```sh
./build.sh --test                      # x86_64/arm64 native: unit (AOT + JIT), LSP
python3 docs/tools/check-examples.py   # every runnable example in the docs
docs/examples/run.sh                   # the example projects, compiled and JIT
docker/test.sh                         # x86_64 + arm64 Linux in containers
docker/test.sh --full                  # every build configuration (gcc, clang, ASan, sqlite, install)
```

Tests are `src/tests/NN_name.ktm`; the runners pick up every numbered file and run
it both ahead of time and in the JIT, `JOBS` at a time (default: one per CPU),
each in its own working directory and `TMPDIR`. A test still running after
`KTM_TEST_TIMEOUT` seconds (default 60, `0` for no limit) is killed with everything
it started and reported as `FAILED ... timed out`. A bug fix comes with a test that
fails before the fix and passes after it.

## Language features
- `F32`, for C libraries that use `float`.
- `auto` type inference and `typeof(expr)` (a compile-time string).
- Range-based `for` over arrays and over any class with `entries` and `size`.
- `extern "c"` to call any C function, and `#link` for shared libraries.
- See the [language page](https://ozzenerol.github.io/ktm-lang/language.html) for the full list.

## Known issues
Open bugs, each with a reproduction and a workaround, are on the
[known-issues page](https://ozzenerol.github.io/ktm-lang/known-issues.html).

## History and credits
ktm started as a fork of [Jamesbarford/holyc-lang](https://github.com/Jamesbarford/holyc-lang)
(BSD-2-Clause), a compiler for HolyC, the language Terry A. Davis created for
TempleOS. Thanks to both: to [Terry A. Davis](https://en.wikipedia.org/wiki/Terry_A._Davis)
for HolyC and [TempleOS](https://templeos.org/), and to
[James Barford-Evans](https://github.com/Jamesbarford), who wrote the compiler this
one grew from. The fork was renamed ktm after hcc v0.19.3 (ktm v0.1.0) and keeps the
original license (see [COPYING](COPYING)). James's inspirations and resources:
[8cc](https://github.com/rui314/8cc), [tcc](http://bellard.org/tcc/),
[cc65](https://cc65.github.io/), [shecc](https://github.com/sysprog21/shecc),
[CaptCC](https://github.com/Captainarash/CaptCC),
[linenoise](https://github.com/antirez/linenoise).
