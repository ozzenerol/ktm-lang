#!/usr/bin/env bash
# Checks the Vim syntax (src/syntax-highlighting/vim) in a headless Neovim:
# every probe in check.lua against sample.ktm. Skipped without nvim.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if ! command -v nvim >/dev/null; then
    echo "syntax: skipped (no nvim)"
    exit 0
fi
cd "$here"
nvim --headless --clean -u NONE --cmd "set rtp^=$here/../vim" \
    -c 'filetype plugin on' -c 'syntax on' -c 'edit sample.ktm' \
    -c 'if &filetype !=# "ktm" | echo "filetype is " . &filetype | cquit 1 | endif' \
    -c 'luafile check.lua' -c 'qa!' 2>&1
