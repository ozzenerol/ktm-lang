#!/usr/bin/env bash
# Build the whole thing: the ktm compiler (with JIT), the libktm standard
# library, and optionally run the test suites.
#
# Everything is built into a local prefix (./build/prefix) so nothing in
# /usr/local is touched or relied upon.
#
# Usage: ./build.sh [--debug] [--no-sqlite] [--test] [--install] [--clean]
#   --debug      Debug build instead of Release
#   --no-sqlite  Build libktm without sqlite3 (no sql module); by default
#                it is linked against sqlite3 (--sqlite is still accepted)
#   --test       Run unit, JIT and LSP tests after building
#   --install    Also install ktm + libktm into $INSTALL_PREFIX (default
#                /usr/local), using sudo for the copy only if needed
#   --clean      Remove ./build and ./ktm before building
#
# Run as your normal user, not with sudo.
#
# Env overrides: CC, JOBS (make and test parallelism), INSTALL_PREFIX,
# KTM_TEST_TIMEOUT (seconds before a test is killed, default 60, 0 = none)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT/build"
PREFIX="$BUILD_DIR/prefix"
CC="${CC:-gcc}"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)}"
INSTALL_PREFIX="${INSTALL_PREFIX:-/usr/local}"

BUILD_TYPE=Release
SQLITE=1
RUN_TESTS=0
DO_INSTALL=0
DO_CLEAN=0

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }

for arg in "$@"; do
    case "$arg" in
        --debug)   BUILD_TYPE=Debug ;;
        --sqlite)  SQLITE=1 ;;
        --no-sqlite) SQLITE=0 ;;
        --test)    RUN_TESTS=1 ;;
        --install) DO_INSTALL=1 ;;
        --clean)   DO_CLEAN=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $arg (see --help)" >&2; exit 1 ;;
    esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Building as root via sudo leaves root-owned files in the tree that break
# every later normal build, so only the install copy is ever elevated.
if [ "$(id -u)" = 0 ] && [ -n "${SUDO_USER:-}" ]; then
    die "don't run build.sh with sudo. Run it as yourself; --install will
       ask for sudo only to copy files into $INSTALL_PREFIX."
fi

for tool in cmake make "$CC"; do
    command -v "$tool" >/dev/null || die "missing required tool: $tool"
done

# Files left behind by an earlier root build can't be overwritten.
uid="$(id -u)"
if [ "$uid" != 0 ]; then
    foreign="$(find "$BUILD_DIR" "$ROOT/ktm" "$ROOT/src/std" "$ROOT/src/tests" \
        ! -user "$uid" 2>/dev/null | head -5 || true)"
    if [ -n "$foreign" ]; then
        die "some build files are owned by another user (left over from a sudo build?):
$foreign
Fix with:  sudo chown -R $(id -un) \"$BUILD_DIR\" \"$ROOT/ktm\" \"$ROOT/src\""
    fi
fi

# Switching compilers on an existing build dir leaves stale objects that
# fail to link, so start over when CC changes.
cached_cc="$(sed -n 's/^CMAKE_C_COMPILER:[A-Z]*=//p' "$BUILD_DIR/CMakeCache.txt" 2>/dev/null || true)"
if [ -n "$cached_cc" ] && [ "$cached_cc" != "$(command -v "$CC")" ] && [ "$DO_CLEAN" = 0 ]; then
    echo "compiler changed ($cached_cc -> $CC), doing a clean build"
    DO_CLEAN=1
fi

# Debug builds use AddressSanitizer, whose leak checker makes ktm exit
# non-zero on every compile.
if [ "$BUILD_TYPE" = Debug ]; then
    export ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=0}"
fi

if [ "$DO_CLEAN" = 1 ]; then
    step "Cleaning"
    rm -rf "$BUILD_DIR" "$ROOT/ktm"
fi

step "Configuring ktm ($BUILD_TYPE, CC=$CC)"
cmake_args=(
    -S "$ROOT/src" -B "$BUILD_DIR" -G 'Unix Makefiles'
    -DCMAKE_C_COMPILER="$CC"
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
    -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX"
    -DCMAKE_C_FLAGS='-Wextra -Wall -Wpedantic'
    -DCMAKE_EXPORT_COMPILE_COMMANDS=on
    -DKTM_ENABLE_JIT=on
)
# CMakeLists checks `DEFINED KTM_LINK_SQLITE3`, so turning it off means
# removing it from the cache rather than setting it to OFF.
if [ "$SQLITE" = 1 ]; then
    cmake_args+=(-DKTM_LINK_SQLITE3=1)
else
    cmake_args+=(-UKTM_LINK_SQLITE3)
fi
cmake "${cmake_args[@]}"

step "Building ktm (-j$JOBS)"
make -C "$BUILD_DIR" -j"$JOBS"
KTM="$ROOT/ktm"
[ -x "$KTM" ] || die "ktm binary not produced at $KTM"

step "Building libktm into $PREFIX"
rm -rf "$PREFIX"
mkdir -p "$PREFIX/lib" "$PREFIX/modules"
# the stdlib's modules (the prelude and the runtime among them), compiled
# as one tree into lib/libktm.a and the shared object the JIT loads; each
# module directory gets a .ktmlib marker pointing at them
cp -R "$ROOT/src/std/." "$PREFIX/modules/"
# net.tls stays source (no .ktmlib): only the programs that import it
# compile it and link libktmtls (#link <ktmtls>), so every other program
# stays as small as before
# (crypto, the same: it calls into libktmtls)
SOURCE_MODULES="net/tls net/https crypto"
for m in $SOURCE_MODULES; do rm -rf "$PREFIX/modules/$m"; done
"$KTM" -fPIC -lib ktm --install-dir="$PREFIX" "$PREFIX/modules"
for m in $SOURCE_MODULES; do cp -R "$ROOT/src/std/$m" "$PREFIX/modules/$m"; done
# libktmtls (mbedTLS and ktm's layer over it), built by cmake: the
# archive for compiled programs, the shared object for the JIT. The
# shared one gets only its versioned name, so `-lktmtls` always takes
# the archive and a program needs no libktmtls at run time.
cp "$BUILD_DIR/libktmtls.a" "$PREFIX/lib/libktmtls.a"
if [ "$(uname -s)" = Darwin ]; then
    cp "$BUILD_DIR/libktmtls.dylib" "$PREFIX/lib/libktmtls.0.0.1.dylib"
else
    cp "$BUILD_DIR/libktmtls.so" "$PREFIX/lib/libktmtls.so.0.0.1"
fi

if [ "$RUN_TESTS" = 1 ]; then
    # The test runners otherwise compile each test against /usr/local.
    export KTM_INSTALL_DIR="$PREFIX"
    # How many tests the runners run at once
    export JOBS

    step "Running unit tests"
    (cd "$ROOT/src/tests" && "$KTM" --install-dir="$PREFIX" ./run.ktm -o test-runner && ./test-runner)

    step "Running JIT unit tests"
    (cd "$ROOT/src/tests" && "$KTM" --install-dir="$PREFIX" ./run_jit.ktm -o test-runner-jit && ./test-runner-jit)

    # coro's loop has several backends (io_uring, epoll, kqueue, poll):
    # the runs above used the default one, this one runs every test that
    # imports coro again on the next one down
    if [ "$(uname -s)" = Darwin ]; then other=poll; else other=epoll; fi
    step "Running the coro tests on the $other backend"
    (cd "$ROOT/src/tests" && KTM_CORO_BACKEND=$other KTM_TEST_MODULE=coro ./test-runner)

    step "Running LSP tests"
    (cd "$ROOT/src/tests/lsp" && "$KTM" --install-dir="$PREFIX" ./run_lsp_tests.ktm -o lsp-test-runner && ./lsp-test-runner)

    step "Checking the Vim syntax"
    "$ROOT/src/syntax-highlighting/test/run.sh"
fi

if [ "$DO_INSTALL" = 1 ]; then
    step "Installing into $INSTALL_PREFIX"
    # Copy what was already built rather than `make install`, which re-runs
    # ktm (as root, under sudo) inside the source tree.
    as=()
    for d in "$INSTALL_PREFIX" "$INSTALL_PREFIX/bin" "$INSTALL_PREFIX/lib" "$INSTALL_PREFIX/modules"; do
        while [ ! -e "$d" ]; do d="$(dirname "$d")"; done
        if [ ! -w "$d" ] && [ "$uid" != 0 ]; then
            as=(sudo)
            echo "$d is not writable, using sudo"
            break
        fi
    done
    ${as[@]+"${as[@]}"} mkdir -p "$INSTALL_PREFIX/bin" "$INSTALL_PREFIX/lib"
    # A new file renamed over the old one, never the old file rewritten:
    # running programs (a JIT run maps libktm.so, and the compiler is a
    # running binary) keep the copy they mapped. Rewriting it in place
    # crashed them, at random spots, whenever an install landed mid-run.
    put() {
        ${as[@]+"${as[@]}"} cp -PR "$1" "$2.new.$$" &&
        ${as[@]+"${as[@]}"} mv -f "$2.new.$$" "$2"
    }
    put "$KTM" "$INSTALL_PREFIX/bin/ktm"
    # the old header-based stdlib, which an earlier install left
    ${as[@]+"${as[@]}"} rm -f "$INSTALL_PREFIX/include/std.ktmh"
    # the stdlib's modules, replaced whole (other modules there stay)
    ${as[@]+"${as[@]}"} mkdir -p "$INSTALL_PREFIX/modules"
    for d in "$PREFIX"/modules/*/; do
        d="$(basename "$d")"
        ${as[@]+"${as[@]}"} rm -rf "$INSTALL_PREFIX/modules/$d"
        ${as[@]+"${as[@]}"} cp -R "$PREFIX/modules/$d" "$INSTALL_PREFIX/modules/$d"
    done
    for f in "$PREFIX"/lib/*; do
        put "$f" "$INSTALL_PREFIX/lib/$(basename "$f")"
    done
    # Vim/Neovim support: `set rtp+=$INSTALL_PREFIX/share/ktm/vim`
    ${as[@]+"${as[@]}"} rm -rf "$INSTALL_PREFIX/share/ktm/vim"
    ${as[@]+"${as[@]}"} mkdir -p "$INSTALL_PREFIX/share/ktm"
    ${as[@]+"${as[@]}"} cp -R "$ROOT/src/syntax-highlighting/vim" "$INSTALL_PREFIX/share/ktm/vim"
    # The guide and the library as man pages, `man ktm` (Linux only for now):
    # every ktm page replaced, so a module or chapter that went leaves none behind
    if [ "$(uname -s)" = Linux ]; then
        for s in 3 7; do
            ${as[@]+"${as[@]}"} mkdir -p "$INSTALL_PREFIX/share/man/man$s"
            ${as[@]+"${as[@]}"} rm -f "$INSTALL_PREFIX/share/man/man$s/ktm.$s" "$INSTALL_PREFIX/share/man/man$s"/ktm-*.$s
            ${as[@]+"${as[@]}"} cp "$ROOT/man/man$s"/*.$s "$INSTALL_PREFIX/share/man/man$s/"
        done
    fi
fi

step "Done"
echo "compiler: $KTM"
echo "libktm:   $PREFIX/lib"
if [ "$DO_INSTALL" = 1 ]; then
    echo "installed into $INSTALL_PREFIX - compile with: ktm file.ktm -o out"
else
    echo "compile with: $KTM --install-dir=$PREFIX file.ktm -o out"
fi
if [ "$BUILD_TYPE" = Debug ]; then
    echo "debug ktm is built with AddressSanitizer: run it with ASAN_OPTIONS=detect_leaks=0"
fi
