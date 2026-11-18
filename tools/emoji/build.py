"""
Builds the emoji assets: emoji.txt (emoji, category) and emoji_index.txt (keyword, its emoji, best first).

  python3 build.py <cldr_dir> <emojilib.json> <emoji-test.txt> <assets_dir>

cldr_dir holds the CLDR annotation files en.xml, ro.xml (annotations) and end.xml, rod.xml (annotationsDerived).
emoji-test.txt is the Unicode list of every emoji (unicode.org/Public/emoji/latest): the emoji, their order and groups.
Only the default form of each emoji is kept: the forms with a skin tone are left out.
base.tsv holds the gemoji keywords; extra.tsv the words and emoticons picked by hand, which rank first.
"""
import sys, re, json, os, unicodedata, collections

cldr, emojilib_path, test_path, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
here = os.path.dirname(os.path.abspath(__file__))
MAX_PER_KEY = 24
STOP = set('with the of and a an in on for to at or face cu de si la pe un o fata care din al sau ale'.split())


def plain(s):
    s = unicodedata.normalize('NFD', s.lower())
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')


def bare(e):
    return e.replace('️', '')


def words(text):
    return [w for w in re.split(r"[^0-9a-z]+", plain(text)) if w]


TONES = set('\U0001F3FB\U0001F3FC\U0001F3FD\U0001F3FE\U0001F3FF')
gemoji = {}
for line in open(os.path.join(here, 'base.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 3:
        gemoji[bare(p[0])] = p[2].split(',')

# every emoji Unicode lists, in its order and group, in its default form only
base = []; names = {}; group = ''
for line in open(test_path, encoding='utf-8'):
    if line.startswith('# group:'):
        group = line.split(':', 1)[1].strip()
    m = re.match(r'^([0-9A-F ]+); fully-qualified\s+# (\S+) E[0-9.]+ (.+)$', line)
    if not m or group == 'Component':
        continue
    e = ''.join(chr(int(c, 16)) for c in m.group(1).split())
    if TONES & set(e):
        continue
    base.append((e, group, gemoji.get(bare(e), []))); names[e] = m.group(3)
order = {e: i for i, (e, _, _) in enumerate(base)}
by_bare = {bare(e): e for e, _, _ in base}

# keyword -> emoji -> tier (lower is better)
index = collections.defaultdict(dict)


def add(key, emoji, tier):
    if len(key) < 2 or key in STOP:
        return
    cur = index[key].get(emoji)
    if cur is None or tier < cur:
        index[key][emoji] = tier


def add_name(name, emoji):
    ws = words(name)
    if not ws:
        return
    joined = ''.join(ws)
    index[joined][emoji] = min(index[joined].get(emoji, 9), 1)
    if len(ws) > 1:
        for w in ws:
            add(w, emoji, 2)


for e, _, kws in base:
    add_name(names[e], e)
    for k in kws:
        if '_' in k:
            add_name(k.replace('_', ' '), e)
        else:
            for w in words(k):
                add(w, e, 3)

ann = re.compile(r'<annotation cp="([^"]+)"( type="tts")?>([^<]*)</annotation>')
for f in ('en.xml', 'ro.xml', 'end.xml', 'rod.xml'):
    for cp, tts, text in ann.findall(open(os.path.join(cldr, f), encoding='utf-8').read()):
        e = by_bare.get(bare(cp))
        if e is None:
            continue
        text = text.replace('&amp;', '&')
        if tts:
            add_name(text, e)
        else:
            for kw in text.split('|'):
                ws = words(kw)
                if len(ws) == 1:
                    add(ws[0], e, 3)
                else:
                    for w in ws:
                        add(w, e, 4)

for cp, kws in json.load(open(emojilib_path, encoding='utf-8')).items():
    e = by_bare.get(bare(cp))
    if e is None:
        continue
    for k in kws:
        ws = words(k.replace('_', ' '))
        if len(ws) > 1:
            index[''.join(ws)].setdefault(e, 4)
        for w in ws:
            add(w, e, 5)

# hand-picked words and emoticons: exactly these emoji first, in this order
emoticons = {}
picked = collections.defaultdict(list)
for line in open(os.path.join(here, 'extra.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) != 2:
        continue
    emojis = p[1].split(' ')
    for e in emojis:
        if e not in order:
            sys.exit(f'extra.tsv: {e} is not in the emoji list ({p[0]})')
    for key in p[0].split(','):
        if re.fullmatch(r'[0-9a-z]+', key) and not key.isdigit():
            picked[key] += [e for e in emojis if e not in picked[key]]
        else:
            emoticons[key] = emojis

with open(os.path.join(out, 'emoji.txt'), 'w', encoding='utf-8') as f:
    for e, cat, _ in base:
        f.write(f'{e}\t{cat}\n')

n = 0
with open(os.path.join(out, 'emoji_index.txt'), 'w', encoding='utf-8') as f:
    for key in sorted(set(index) | set(picked)):
        first = picked.get(key, [])
        rest = sorted((e for e in index.get(key, {}) if e not in first), key=lambda e: (index[key][e], order[e]))
        f.write(key + '\t' + ' '.join((first + rest)[:MAX_PER_KEY]) + '\n')
        n += 1
    for key in sorted(emoticons):
        f.write(key + '\t' + ' '.join(emoticons[key]) + '\n')
print(len(base), 'emoji,', n, 'keywords,', len(emoticons), 'emoticons')
