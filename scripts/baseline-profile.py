"""baseline-profile.py PROFILE_TXT MAPPING OUT : an ART profile dump (cmd package dump-profiles) of the
R8-built bench app -> baseline-prof.txt rules in source names. Per class, not per method: every
class with a hot/startup/post-startup method gets HSPL<class>;->**(**)**, and every class in the
classes sections gets L<class>; (loaded at start-up). Obfuscated names are mapped back with the
R8 mapping (class lines); classes the mapping doesn't know keep their name. R8's synthetic
lambda classes are left out (they don't exist before R8). How to use: docs/agents/startup.md."""
import re, sys
prof, mapping, out = sys.argv[1:4]
rev = {}
with open(mapping, errors='replace') as f:
    for line in f:
        if line[0] in ' #': continue
        m = re.match(r'^(\S+) -> (\S+):$', line.rstrip('\n'))
        if m: rev[m.group(2)] = m.group(1)
method_classes, classes = set(), set()
sec = None
meth = re.compile(r'^\t\t\S+ ([\w$.]+)\.([\w$<>-]+)\(')
for line in open(prof, errors='replace'):
    s = line.rstrip('\n')
    t = s.strip()
    if t.endswith('methods:'): sec = 'm'; continue
    if t.startswith('classes:'): sec = 'c'; continue
    if not s.startswith('\t\t') or not t: continue
    if sec == 'm':
        m = meth.match(s)
        if m: method_classes.add(m.group(1))
    elif sec == 'c':
        classes.add(t.rstrip(','))
def desc(c):
    return 'L' + rev.get(c, c).replace('.', '/') + ';'
SKIP = ('Ljava/', 'Ljavax/', 'Landroid/', 'Ldalvik/', 'Lsun/', 'Llibcore/', 'Lcom/android/')
rules = set()
for c in method_classes:
    d = desc(c)
    if not d.startswith(SKIP) and 'ExternalSynthetic' not in d: rules.add('HSP' + d + '->**(**)**')
for c in classes | method_classes:
    d = desc(c)
    if not d.startswith(SKIP) and 'ExternalSynthetic' not in d: rules.add(d)
with open(out, 'w') as f:
    f.write('\n'.join(sorted(rules)) + '\n')
print(len(method_classes), 'classes with methods,', len(classes), 'classes,', len(rules), 'rules')
