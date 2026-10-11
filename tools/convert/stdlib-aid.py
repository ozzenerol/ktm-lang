#!/usr/bin/env python3
"""Converter build aid for S2 (never installed): make the converter's prefix
std.ktmh declare what the old stdlib's module files use but no header did,
so the old code of src/std/<module>/ parses before it is converted:
  stdlib-aid.py PREFIX OLD_STD_DIR
- every C function an old src/std/*.ktm declared privately (not sqlite's,
  the sql module keeps its own), as `public extern "c"`;
- rt's coroutine stack cache, which coro uses (split.py's FORCE).
Run it again after each rebuild of the converter's tree (build.sh recreates
the prefix); it does nothing when the prefix has it already."""
import sys, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from items import items

prefix, old = sys.argv[1:3]
H = os.path.join(prefix, 'include', 'std.ktmh')
text = open(H).read()
MARK = '/* converter aid (S2)'
if MARK in text:
    sys.exit(0)
have = set(i['name'] for i in items(H) if i['kind'] == 'cfunc')
add = []
for f in sorted(os.listdir(old)):
    if not f.endswith('.ktm') or f == 'all.ktm':
        continue
    t = open(os.path.join(old, f)).read()
    for it in items(os.path.join(old, f)):
        if it['kind'] != 'cfunc' or it['name'] in have or it['name'].startswith('sqlite3'):
            continue
        have.add(it['name'])
        body = re.sub(r'^(\s*(?:/\*.*?\*/|//[^\n]*)\s*)*', '', t[it['s']:it['e']], flags=re.S)
        add.append('public ' + re.sub(r'^(public|static)\s+', '', body).strip())
add.append('public class CoroCached\n{\n  CoroCached *next;\n  I64 len;\n};')
add.append('public U0 CoroStacksFree(HCFs *fs);')
open(H, 'a').write('\n' + MARK + ': C functions the old stdlib declared privately; rt\'s coroutine cache */\n'
                   + '\n'.join(add) + '\n')
print(len(add), 'declarations added to', H)
