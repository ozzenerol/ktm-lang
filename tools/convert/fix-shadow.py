#!/usr/bin/env python3
"""Hand fix (re-runnable): in a function where a local or parameter is named
like a module (vec, json, sql, str, ...) and the function also uses that
module, the local becomes <name>Val (Go-exact shadowing would make
`vec.ptrNew()` a member of the local). A use `mod.x` with x one of the
module's names (stdlib map) stays the module; any other use is the local.
  fix-shadow.py TSV [GLOB...]   (run from the tree's root; default: the
  tests and the examples; convert-std.sh runs it on src/std)"""
import re, sys, glob
tsv = sys.argv[1]
globs = sys.argv[2:] or ['src/tests/**/*.ktm', 'docs/examples/**/*.ktm']
names = {}
for line in open(tsv):
    c = line.rstrip('\n').split('\t')
    if len(c) < 4 or c[0] == 'kind': continue
    if c[0] in ('cfunctions', 'ctypes', 'cconstants'):
        for w in c[3].split(): names.setdefault('libc', set()).add(w)
    else:
        names.setdefault(c[2], set()).add(c[3])
names.setdefault('test', set()).update({'printResult', 'heapInUse'})
mods = sorted(names)

def code_mask(t):
    """True for each char of t that is code (not comment or string)"""
    m = [True] * len(t); i = 0; n = len(t)
    while i < n:
        if t.startswith('//', i):
            j = t.find('\n', i); j = n if j < 0 else j
            for k in range(i, j): m[k] = False
            i = j; continue
        if t.startswith('/*', i):
            j = t.find('*/', i + 2); j = n if j < 0 else j + 2
            for k in range(i, j): m[k] = False
            i = j; continue
        if t[i] in '"\'':
            q = t[i]; j = i + 1
            while j < n and t[j] != q and not (q == "'" and t[j] == '\n'):
                if t[j] == '\\': j += 1
                j += 1
            for k in range(i, min(j + 1, n)): m[k] = False
            i = j + 1; continue
        i += 1
    return m

total = 0
for f in [x for g in globs for x in sorted(glob.glob(g, recursive=True))]:
    t = open(f).read()
    mask = code_mask(t)
    edits = []
    for m in re.finditer(r'^[^\s#/*][^\n;{]*\)\s*\n?\s*\{', t, re.M):
        if not mask[m.start()]: continue
        i = m.end(); depth = 1
        while i < len(t) and depth:
            if mask[i]:
                if t[i] == '{': depth += 1
                elif t[i] == '}': depth -= 1
            i += 1
        s, e = m.start(), i
        body = t[s:e]
        for mod in mods:
            decl = re.search(r'(?:[\w.]+\s*\**\s+|\*\s*)\b%s\b\s*(?:=|;|,|\[|\))' % mod, body)
            if not decl or not mask[s + decl.start()]: continue
            uses_mod = any(mask[s + u.start()] for u in re.finditer(r'(?<![\w.])%s\.(\w+)' % mod, body)
                           if u.group(1) in names[mod])
            if not uses_mod: continue
            for u in re.finditer(r'(?<![\w.])%s\b' % mod, body):
                p = s + u.start()
                if not mask[p]: continue
                after = re.match(r'\.(\w+)', body[u.end():])
                if after and after.group(1) in names[mod]: continue      # the module
                edits.append((p, p + len(mod), mod + 'Val'))
    if edits:
        edits = sorted(set(edits), reverse=True)
        for a, b, w in edits: t = t[:a] + w + t[b:]
        open(f, 'w').write(t); total += 1
        print(f, len(edits))
print(total, 'files')
