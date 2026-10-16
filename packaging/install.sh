#!/bin/sh
# Install ktm + libktm from a release archive.
#
# Mirrors what a source build installs (build.sh --install), namely:
#   <prefix>/bin/ktm
#   <prefix>/modules/<module>/       (the standard library's modules,
#                                     prelude and rt among them, each
#                                     with a .ktmlib marker pointing at
#                                     ../../lib)
#   <prefix>/lib/libktm.a
#   <prefix>/lib/libktm.so.0.0.1   (Linux)
#   <prefix>/lib/libktm.0.0.1.dylib (macOS)
#   <prefix>/lib/libktmtls.a, libktmtls.so.0.0.1 / libktmtls.0.0.1.dylib
#                                    (net.tls: mbedTLS, for the programs
#                                     that import it)
#   <prefix>/share/ktm/vim/         (Vim/Neovim syntax, filetype, ftplugin)
#   <prefix>/share/man/man3, man7   (the guide and the library as man
#                                     pages, ktm*.3 and ktm*.7: Linux)
#
# Two deliberate details, both load-bearing:
#
#  1. No unversioned libktm.so / libktm.dylib is installed. AOT links
#     with `-lktm` and MUST resolve to the static archive: libktm is
#     built -Bsymbolic, so a dynamically linked executable gets a copy
#     relocation for exported data (e.g. the `Fs` exception object)
#     while libktm keeps writing its own copy - two `Fs` objects, and
#     the first `throw` null-derefs. With no unversioned shared object
#     beside it, `-lktm` finds the archive.
#
#  2. The shared library is installed under its VERSIONED name, which
#     is what jitLoadLibtos (src/jit-common.c) probes for and what lets
#     -jit and -repl find the stdlib. It is not a name `-lktm` will
#     ever pick, so (1) is preserved.

set -eu

PREFIX=/usr/local
UNINSTALL=0

usage() {
    cat <<EOF
Usage: ./install.sh [--prefix=DIR] [--uninstall]

  --prefix=DIR   Install root (default: /usr/local)
  --uninstall    Remove a previous install from --prefix
  --help         Show this message
EOF
}

for arg in "$@"; do
    case "$arg" in
        --prefix=*)  PREFIX=${arg#--prefix=} ;;
        --uninstall) UNINSTALL=1 ;;
        --help|-h)   usage; exit 0 ;;
        *) echo "install.sh: unknown option '$arg'" >&2; usage >&2; exit 2 ;;
    esac
done

SRC=$(cd -- "$(dirname -- "$0")" && pwd)

# Work out which platform's archive this is from the payload itself
# rather than from uname - the archive is the authority on what it
# contains.
if [ -f "$SRC/lib/libktm.so.0.0.1" ]; then
    SHLIB=libktm.so.0.0.1
    TLSLIB=libktmtls.so.0.0.1
elif [ -f "$SRC/lib/libktm.0.0.1.dylib" ]; then
    SHLIB=libktm.0.0.1.dylib
    TLSLIB=libktmtls.0.0.1.dylib
elif [ "$UNINSTALL" -eq 1 ]; then
    SHLIB=
    TLSLIB=
else
    echo "install.sh: no libktm shared library in $SRC/lib - is this archive complete?" >&2
    exit 1
fi

if [ "$UNINSTALL" -eq 1 ]; then
    for f in "$PREFIX/bin/ktm" \
             "$PREFIX/include/std.ktmh" \
             "$PREFIX/lib/libktm.a" \
             "$PREFIX/lib/libktm.so.0.0.1" \
             "$PREFIX/lib/libktm.0.0.1.dylib" \
             "$PREFIX/lib/libktmtls.a" \
             "$PREFIX/lib/libktmtls.so.0.0.1" \
             "$PREFIX/lib/libktmtls.0.0.1.dylib"; do
        if [ -e "$f" ]; then
            rm -f "$f" && echo "removed $f"
        fi
    done
    if [ -d "$PREFIX/share/ktm/vim" ]; then
        rm -rf "$PREFIX/share/ktm/vim" && echo "removed $PREFIX/share/ktm/vim"
    fi
    if [ -e "$PREFIX/share/man/man7/ktm.7" ]; then
        rm -f "$PREFIX"/share/man/man3/ktm-*.3 "$PREFIX"/share/man/man7/ktm.7 "$PREFIX"/share/man/man7/ktm-*.7
        echo "removed the ktm man pages from $PREFIX/share/man"
    fi
    # the standard library's modules (the ones this archive has; other
    # modules under $PREFIX/modules stay)
    for d in "$SRC"/modules/*/; do
        [ -d "$d" ] || continue
        d=$(basename "$d")
        if [ -d "$PREFIX/modules/$d" ]; then
            rm -rf "$PREFIX/modules/$d" && echo "removed $PREFIX/modules/$d"
        fi
    done
    exit 0
fi

# Fail early with a useful message rather than part-way through with a
# permission error part-way through.
for d in bin lib modules; do
    if ! mkdir -p "$PREFIX/$d" 2>/dev/null; then
        echo "install.sh: cannot create $PREFIX/$d - try: sudo ./install.sh --prefix=$PREFIX" >&2
        exit 1
    fi
    if [ ! -w "$PREFIX/$d" ]; then
        echo "install.sh: $PREFIX/$d is not writable - try: sudo ./install.sh --prefix=$PREFIX" >&2
        exit 1
    fi
done

# A new file renamed over the old one: a running ktm, or a JIT run that
# mapped the old shared library, keeps its copy (rewriting the file in
# place crashed them)
install_file() {
    src=$1 dst=$2 mode=$3
    cp "$src" "$dst.new.$$"
    chmod "$mode" "$dst.new.$$"
    mv -f "$dst.new.$$" "$dst"
    echo "installed $dst"
}

install_file "$SRC/ktm"             "$PREFIX/bin/ktm"           755
# the old header-based stdlib, which an earlier install left
if [ -e "$PREFIX/include/std.ktmh" ]; then
    rm -f "$PREFIX/include/std.ktmh" && echo "removed $PREFIX/include/std.ktmh"
fi
# the standard library's modules, each replaced whole (its .ktmlib
# marker included)
for d in "$SRC"/modules/*/; do
    d=$(basename "$d")
    rm -rf "$PREFIX/modules/$d"
    cp -R "$SRC/modules/$d" "$PREFIX/modules/$d"
    chmod -R u=rwX,go=rX "$PREFIX/modules/$d"
    echo "installed $PREFIX/modules/$d"
done
install_file "$SRC/lib/libktm.a"    "$PREFIX/lib/libktm.a"      644
install_file "$SRC/lib/$SHLIB"      "$PREFIX/lib/$SHLIB"        755
install_file "$SRC/lib/libktmtls.a" "$PREFIX/lib/libktmtls.a"   644
install_file "$SRC/lib/$TLSLIB"     "$PREFIX/lib/$TLSLIB"       755
if [ -d "$SRC/vim" ] && mkdir -p "$PREFIX/share/ktm" 2>/dev/null; then
    rm -rf "$PREFIX/share/ktm/vim"
    cp -R "$SRC/vim" "$PREFIX/share/ktm/vim"
    chmod -R u=rwX,go=rX "$PREFIX/share/ktm/vim"
    echo "installed $PREFIX/share/ktm/vim"
fi
# the man pages (a Linux archive has them): every ktm page replaced, so a
# module or chapter that went leaves none behind
if [ -d "$SRC/man" ] && mkdir -p "$PREFIX/share/man/man3" "$PREFIX/share/man/man7" 2>/dev/null; then
    for s in 3 7; do
        rm -f "$PREFIX/share/man/man$s/ktm.$s" "$PREFIX/share/man/man$s"/ktm-*.$s
        cp "$SRC/man/man$s"/*.$s "$PREFIX/share/man/man$s/"
        chmod 644 "$PREFIX/share/man/man$s"/ktm*.$s
    done
    echo "installed the man pages into $PREFIX/share/man (man ktm)"
fi

# Downloaded archives carry com.apple.quarantine, which makes the
# binary unlaunchable until Gatekeeper is placated. Clearing it here
# saves a confusing "cannot be opened" dialog.
if [ "$SHLIB" != "${SHLIB%.dylib}" ] && command -v xattr >/dev/null 2>&1; then
    xattr -d com.apple.quarantine "$PREFIX/bin/ktm" 2>/dev/null || true
    xattr -d com.apple.quarantine "$PREFIX/lib/$SHLIB" 2>/dev/null || true
    xattr -d com.apple.quarantine "$PREFIX/lib/$TLSLIB" 2>/dev/null || true
fi

echo
echo "ktm installed to $PREFIX"

# The binary bakes INSTALL_PREFIX in at compile time (release builds
# use /usr/local) and looks for <prefix>/modules there. A
# non-default prefix therefore needs --install-dir on every call.
if [ "$PREFIX" != "/usr/local" ]; then
    echo
    echo "NOTE: this build defaults to /usr/local. Because you installed to"
    echo "      $PREFIX, pass --install-dir=$PREFIX to every ktm invocation:"
    echo "        ktm --install-dir=$PREFIX ./hello.ktm -o hello"
fi

case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) echo
       echo "NOTE: $PREFIX/bin is not on your PATH." ;;
esac
