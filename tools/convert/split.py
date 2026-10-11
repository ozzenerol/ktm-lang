#!/usr/bin/env python3
"""Split the old-syntax stdlib into module directories (S2a step 1).
  split.py TSV SRCDIR OUTDIR
Each item of SRCDIR's .ktm/.ktmh files (items.py) goes to OUTDIR/<module>/<file>.ktm:
its module from the stdlib map; private helpers to their file's main module;
extern "c" declarations (once each) to c/; prototypes of functions defined in
a .ktm and the prelude's names are dropped; a public name's definition gets
`public`. Prints what it did not place."""
import sys, os, re, json, collections
sys.path.insert(0, os.path.dirname(__file__))
from items import items, load_map

tsv, src, out = sys.argv[1:4]
mp = load_map(tsv)
files = sorted(f for f in os.listdir(src) if f.endswith('.ktm') and f != 'all.ktm')
headers = ['std.ktmh', 'defs.ktmh']
allit = {f: items(os.path.join(src, f)) for f in files + headers}
text = {f: open(os.path.join(src, f)).read() for f in files + headers}
defined = set(i['name'] for f in files for i in allit[f] if i['kind'] in ('func', 'type', 'global'))
# a ktm prototype of a function some file declares extern "c" (the header's
# `public cDIR *opendir(...)` next to dir.ktm's extern) is the extern's
defined |= set(i['name'] for f in files for i in allit[f] if i['kind'] == 'cfunc')
DROP = {'Dir', 'IsDotZ', 'IsDotC', 'Write', 'CH_SPACE', 'CH_SHIFT_SPACE', 'RM_VERBOSE', 'TRUE', 'FALSE', 'NULL',
        '__BUFSIZ__'}
# names that leave the API (module `-` in the map) but the code still needs:
# the runtime's, and the header's implementation details, private to the
# module that uses them
KEEP_IN = {'HCFs': 'rt', 'Fs': 'rt', 'HT_DELETED': 'map', 'STR_LEN': 'str',
           'PRTF_DECIMAL': 'str', 'PRTF_LEFT_JUSTIFY': 'str', 'PRTF_PAD_ZERO': 'str',
           'PRTF_SLASH': 'str', 'PRTF_TRUNCATE': 'str', '_SS_PAD1SIZE': 'libc', '_SS_PAD2SIZE': 'libc'}

# placed by hand: coroutine stack caching is the runtime's thread state
# (rt frees it when a thread ends; coro uses rt, so rt can't use coro)
FORCE = {'CoroStacksFree': 'rt', 'CoroCached': 'rt'}
# a tiny private helper both its users need a copy of
COPY = {'RBIsNil': ['map', 'set']}

def home_of(f):
    c = collections.Counter(mp[i['name']][1] for i in allit[f]
                            if i['name'] in mp and i['kind'] in ('func', 'type', 'global', 'proto')
                            and mp[i['name']][1] not in ('libc', '-', 'prelude'))
    return c.most_common(1)[0][0] if c else None

# Private helpers go where they are used: a helper (no module of its own in
# the map) lands in the module whose items use it most, so a module never
# calls another one's private helper (set.ktm's RB* helpers are map's,
# strings.ktm's date helpers date's). Repeated so helpers of helpers settle.
words = {(f, i): set(re.findall(r'\w+', text[f][it['s']:it['e']]))
         for f in files + headers for i, it in enumerate(allit[f])}

def mapped_module(it):
    row = mp.get(it['name'])
    if row and row[1] not in ('-', 'prelude', None):
        return row[1]
    return None

helper_mod = {}
for f in files:
    for it in allit[f]:
        if it['kind'] in ('func', 'global', 'define', 'type') and mapped_module(it) is None \
                and it['name'] not in DROP and it['name'] not in KEEP_IN:
            helper_mod[it['name']] = None
def libc_item(f, it):
    # an item split.py sends to libc: a C declaration or a C name of the map
    row = mp.get(it['name'])
    return it['kind'] == 'cfunc' or (row is not None and (row[0] == 'c' or row[1] == 'libc'))

libc_words = set()
kept_c = set()        # a C function declared twice: the first (the header's) is kept
for f in headers + files:
    for i, it in enumerate(allit[f]):
        if libc_item(f, it) and it['name'] not in DROP:
            if it['kind'] == 'cfunc':
                if it['name'] in kept_c or it['name'].startswith('sqlite3'):
                    continue
                kept_c.add(it['name'])
            libc_words |= words[(f, i)]

for _ in range(4):
    for h in helper_mod:
        if h in libc_words:
            helper_mod[h] = 'libc'     # libc imports nothing: its types live there
            continue
        refs = collections.Counter()
        for f in files:
            for i, it in enumerate(allit[f]):
                if it['name'] == h or h not in words[(f, i)]:
                    continue
                m = mapped_module(it) or helper_mod.get(it['name'])
                if m and m != 'libc':
                    refs[m] += 1
        if refs:
            helper_mod[h] = refs.most_common(1)[0][0]

# `auto F(...)` definitions get the return type the header declared
hdr_ret = {}
for it in allit['std.ktmh']:
    if it['kind'] == 'proto':
        head = text['std.ktmh'][it['s']:it['e']]
        head = re.sub(r'^(\s*(?:/\*.*?\*/|//[^\n]*)\s*)*', '', head, flags=re.S)
        m = re.match(r'(?:public\s+)?([\w\s\*]+?)\b' + re.escape(it['name']) + r'\s*\(', head)
        if m:
            hdr_ret[it['name']] = m.group(1).strip()

# a platform #if block of a .ktm file goes where the helpers it defines are
# used (os's ErrnoPtr is in system.ktm's #ifdef IS_LINUX)
def block_defs(body):
    return set(re.findall(r'^\s*(?:static\s+|public\s+)?(?:_extern\s+\S+\s+)?[A-Za-z_]\w*[\s\*]+(\w+)\s*\(',
                          body, re.M)) - {'if', 'while', 'for', 'switch', 'return'}

# a .ktm's platform block that only repeats the header's C types and
# constants (threads.ktm's pthread_* layouts): the header's are kept
hdr_text = text['std.ktmh'] + text['defs.ktmh']
hdr_types = set(re.findall(r'\b(?:class|union|struct)\s+(\w+)', hdr_text))
hdr_defines = set(re.findall(r'#\s*define\s+(\w+)', hdr_text))

def repeats_header(body):
    types = set(re.findall(r'\b(?:class|union|struct)\s+(\w+)', body))
    defines = set(re.findall(r'#\s*define\s+(\w+)', body))
    return (types or defines) and types <= hdr_types and defines <= hdr_defines and \
        not block_defs(body) - types

def block_module(f, idx, body):
    defs = block_defs(body)
    refs = collections.Counter()
    for g in files:
        for j, it in enumerate(allit[g]):
            if (g, j) == (f, idx) or not (defs & words[(g, j)]):
                continue
            m = mapped_module(it) or helper_mod.get(it['name'])
            if m and m != 'libc':
                refs[m] += 1
    return refs.most_common(1)[0][0] if refs else None

outputs = collections.defaultdict(list)     # (module, file) -> [text]
seen_c = set()
seen_defines = set()
unplaced = []
dropped = []
for f in headers + files:
    home = home_of(f) or 'libc'   # dir.ktm: nothing but C bindings
    for idx, it in enumerate(allit[f]):
        k, name = it['kind'], it['name']
        body = text[f][it['s']:it['e']]
        if k == 'directive':
            continue
        if name in FORCE and k != 'proto':
            lead = re.match(r'(\s*(?:/\*.*?\*/|//[^\n]*)\s*)*', body, re.S).end()
            rest = re.sub(r'^(public|static)\s+', '', body[lead:])
            outputs[(FORCE[name], f)].append(body[:lead] + 'public ' + rest)
            continue
        if name in FORCE or (k == 'proto' and name in defined):
            continue          # FORCE's forward declarations; see also below
        if name in COPY:
            for m in COPY[name]:
                outputs[(m, f)].append(body)
            continue
        if k == 'func' and name in hdr_ret:
            body = re.sub(r'\bauto(\s+' + re.escape(name) + r'\s*\()', hdr_ret[name] + r'\1', body, count=1)
        row = mp.get(name)
        mod = row[1] if row else None
        if name in DROP:
            dropped.append((f, it['start'], k, name))
            continue
        if k == 'cfunc' or mod == 'libc' or (row and row[0] == 'c'):
            if name in seen_c:
                continue
            seen_c.add(name)
            # sqlite's C functions are the sql module's (optional, like it)
            target = 'sql' if name.startswith('sqlite3') else 'libc'
            if target == 'libc':
                # everything in c is pub: it is the C library, for every module
                lead = re.match(r'(\s*(?:/\*.*?\*/|//[^\n]*)\s*)*', body, re.S).end()
                if not re.match(r'(public|static)\b', body[lead:]):
                    body = body[:lead] + 'public ' + body[lead:]
            outputs[(target, f)].append(body)
            continue
        if mod == 'prelude':
            dropped.append((f, it['start'], k, name + ' (prelude)'))
            continue
        if k == 'proto' and name in defined:
            continue          # a forward declaration: a module's names need none
        if f in headers and k == 'global' and name in defined:
            continue                                 # declared again by its definition
        if k == 'define':
            if name in seen_defines:
                continue      # defs.ktmh and std.ktmh both have it
            seen_defines.add(name)
        if f in headers and k == 'type' and name in defined:
            continue
        if mod == '-':
            if name in KEEP_IN:
                mod = KEEP_IN[name]
            elif f not in headers:
                # a helper that leaves the API: private to the module using it
                mod = helper_mod.get(name) or home
            else:
                dropped.append((f, it['start'], k, name))
                continue
            lead = re.match(r'(\s*(?:/\*.*?\*/|//[^\n]*)\s*)*', body, re.S).end()
            rest = re.sub(r'^public\s+', '', body[lead:])
            # the runtime's are used by other stdlib modules (coro): pub in rt
            if mod == 'rt' and k in ('func', 'type', 'proto'):
                rest = 'public ' + rest
            body = body[:lead] + rest
        if k == 'ifblock' and '__KTM_PRELUDE__' in name:
            dropped.append((f, it['start'], k, name + ' (the prelude has them)'))
            continue
        if mod is None and f in headers and k == 'ifblock' and 'SQLITE3' in name:
            mod = 'sql'          # the sql module's own C bindings, optional like it
        if mod is None and f in headers and k == 'ifblock':
            # a platform block of the header: C declarations, unless it
            # holds the ktm names of one module
            mods = set(mp[w][1] for w in re.findall(r'\w+', body) if w in mp
                       and mp[w][1] not in ('libc', '-', 'prelude') and mp[w][0] != 'c')
            if len(mods) == 1:
                mod = mods.pop()
            else:
                if mods:
                    unplaced.append((f, it['start'], k, name + ' (several modules: %s, put in c)' % ','.join(sorted(mods))))
                mod = 'libc'
        if mod is None and k == 'ifblock' and f not in headers:
            if repeats_header(body):
                dropped.append((f, it['start'], k, name + ' (repeats the header)'))
                continue
            mod = block_module(f, idx, body)
        if mod is None:
            if f in headers:
                unplaced.append((f, it['start'], k, name))
                mod = 'rt'
            else:
                mod = helper_mod.get(name) or home
        # a header block's ktm prototypes of C functions (`public cDIR
        # *opendir(...)`, with the extern in dir.ktm) are C declarations
        if mod == 'libc' and k == 'ifblock':
            body = re.sub(r'^(\s*)public\s+(?!extern\b|_extern\b|class\b|union\b|struct\b|enum\b)'
                          r'([^;{]*\([^;{]*\)\s*;)', r'\1public extern "c" \2', body, flags=re.M)
        # everything in libc is pub: it is the C library, for every module
        if mod == 'libc' and k in ('type', 'global', 'func', 'proto'):
            lead = re.match(r'(\s*(?:/\*.*?\*/|//[^\n]*)\s*)*', body, re.S).end()
            if not re.match(r'(public|static)\b', body[lead:]):
                body = body[:lead] + 'public ' + body[lead:]
        # a public name's definition says so
        if row and row[0] in ('function', 'type') and k in ('func', 'type', 'proto') and mod == row[1]:
            lead = re.match(r'(\s*(?:/\*.*?\*/|//[^\n]*)\s*)*', body, re.S).end()
            rest = body[lead:]
            if not re.match(r'(public|static)\b', rest):
                body = body[:lead] + 'public ' + rest
        outputs[(mod, f)].append(body)

for (mod, f), parts in sorted(outputs.items()):
    d = os.path.join(out, *mod.split('.'))
    os.makedirs(d, exist_ok=True)
    # what came from the headers is the module's declarations
    name = 'decls.ktm' if f in headers else os.path.splitext(f)[0] + '.ktm'
    path = os.path.join(d, name)
    fresh = not os.path.exists(path)
    with open(path, 'a') as w:
        if fresh:
            w.write('module %s;\n\n' % mod)
        else:
            w.write('\n\n')
        w.write('\n\n'.join(p.strip('\n') for p in parts))
        w.write('\n')
for d in dropped:
    print('dropped:', *d)
for u in unplaced:
    print('unplaced (to rt):', *u)
print('modules:', ' '.join(sorted(set(m for m, _ in outputs))))
