#!/usr/bin/env python3
"""Top-level items of the old-syntax stdlib (src/std/*.ktm, std.ktmh, defs.ktmh):
each item is a function, struct/union/class, global, #define, extern "c"
declaration or #if block, with the comments just above it. Prints one line per
item: file, start line, end line, kind, name, module (from stdlib-modules.tsv)."""
import re, sys, os, json

def load_map(tsv):
    m = {}
    for line in open(tsv):
        c = line.rstrip('\n').split('\t')
        if len(c) < 4 or c[0] == 'kind':
            continue
        if c[0] in ('cfunctions', 'ctypes', 'cconstants'):
            for w in c[3].split():
                m[w] = ('c', 'c', w)
        else:
            m[c[1]] = (c[0], c[2], c[3])
    return m

def items(path):
    t = open(path).read()
    n = len(t)
    out = []
    i = 0
    depth = 0
    start = None          # start of the current item (incl. its leading comment)
    pending_comment = None
    line_of = lambda k: t.count('\n', 0, k) + 1
    def skip_ws(k):
        while k < n and t[k] in ' \t\r\n':
            k += 1
        return k
    k = 0
    while True:
        k = skip_ws(k)
        if k >= n:
            break
        # comments at top level: attach to the next item
        if t.startswith('//', k):
            e = t.find('\n', k); e = n if e < 0 else e
            if pending_comment is None: pending_comment = k
            k = e; continue
        if t.startswith('/*', k):
            e = t.find('*/', k + 2); e = n if e < 0 else e + 2
            if pending_comment is None: pending_comment = k
            k = e; continue
        s = pending_comment if pending_comment is not None else k
        pending_comment = None
        if t[k] == '#':
            # a directive; #if..#endif at top level is one item
            e = t.find('\n', k); e = n if e < 0 else e
            while e > 0 and t[e - 1] == '\\':
                e2 = t.find('\n', e + 1); e = n if e2 < 0 else e2
            d = t[k:e]
            m = re.match(r'#\s*(\w+)', d)
            word = m.group(1) if m else ''
            if word in ('if', 'ifdef', 'ifndef'):
                lvl = 0; p = k
                while p < n:
                    q = t.find('\n', p); q = n if q < 0 else q
                    dl = t[p:q].lstrip()
                    mm = re.match(r'#\s*(\w+)', dl)
                    if mm and mm.group(1) in ('if', 'ifdef', 'ifndef'): lvl += 1
                    if mm and mm.group(1) == 'endif':
                        lvl -= 1
                        if lvl == 0: e = q; break
                    p = q + 1
                out.append((s, e, 'ifblock', d.strip()))
            elif word == 'define':
                nm = re.match(r'#\s*define\s+(\w+)', d)
                out.append((s, e, 'define', nm.group(1) if nm else '?'))
            else:
                out.append((s, e, 'directive', d.strip()))
            k = e; continue
        # a declaration: up to `;` at depth 0, or a `}` at depth 0 not
        # followed by `;` (a function body)
        p = k; depth = 0; body = False
        while p < n:
            c = t[p]
            if c in '"\'':
                q = p + 1
                while q < n and t[q] != c:
                    if t[q] == '\\': q += 1
                    q += 1
                p = q + 1; continue
            if t.startswith('//', p):
                q = t.find('\n', p); p = n if q < 0 else q; continue
            if t.startswith('/*', p):
                q = t.find('*/', p + 2); p = n if q < 0 else q + 2; continue
            if c in '({[': depth += 1
            elif c in ')}]':
                depth -= 1
                if depth == 0 and c == '}':
                    q = skip_ws(p + 1)
                    head = t[k:p]
                    is_type = re.match(r'\s*(public\s+|static\s+)?(class|union|struct|enum)\b', head)
                    h0 = head.split('{')[0]; d0 = 0; is_init = False
                    for ch in h0:
                        if ch in '([': d0 += 1
                        elif ch in ')]': d0 -= 1
                        elif ch == '=' and d0 == 0: is_init = True
                    if not is_type and not is_init:
                        p += 1; body = True; break
            elif c == ';' and depth == 0:
                p += 1; break
            p += 1
        text = t[k:p]
        head = text.split('{')[0].split('=')[0]
        kind = 'decl'
        name = '?'
        m = re.match(r'\s*(?:public\s+|static\s+|extern\s+"c"\s+|_extern\s+\w+\s+|inline\s+)*(?:[A-Z]\w*\s+)?(class|union|struct|enum)\s+(\w+)', text)
        if m:
            kind, name = 'type', m.group(2)
        elif 'extern "c"' in head[:20] or text.lstrip().startswith('extern "c"'):
            m2 = re.search(r'(\w+)\s*\(', head)
            kind, name = 'cfunc', (m2.group(1) if m2 else '?')
        else:
            # `T (*name)(...)` is a function pointer variable only when that
            # `(` is the first one; in `T F(U0 (*cb)(U0 *))` it is a parameter
            m2 = re.match(r'[^(]*\(\s*\*+\s*(\w+)\s*\)\s*\(', head)
            m3 = re.search(r'(\w+)\s*\(', head)
            if body or (m3 and not m2):
                kind, name = ('func' if body else 'proto'), (m3.group(1) if m3 else '?')
            else:
                m4 = re.findall(r'(\w+)\s*(?:\[[^\]]*\])?\s*$', head.strip().rstrip(';'))
                kind, name = 'global', (m2.group(1) if m2 else (m4[-1] if m4 else '?'))
        out.append((s, p, kind, name))
        k = p
    res = []
    for s, e, kind, name in out:
        res.append(dict(file=os.path.basename(path), start=line_of(s), end=line_of(max(s, e - 1)),
                        s=s, e=e, kind=kind, name=name))
    return res

if __name__ == '__main__':
    tsv = sys.argv[1]
    mp = load_map(tsv)
    allit = []
    for p in sys.argv[2:]:
        for it in items(p):
            row = mp.get(it['name'])
            it['module'] = row[1] if row else '?'
            allit.append(it)
    json.dump(allit, sys.stdout, indent=0)
