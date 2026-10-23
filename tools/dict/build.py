import sys, struct, re, collections
lang, old_dir, out_dir, work = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
hun_files = sys.argv[5:]
MAX_WORDS = {'ro': 330000, 'en': 262000}[lang]; MAX_BI = 900000; MIN_UNSEEN_FREQ = 20
ok = re.compile(r"^[^\W\d_]+(?:'[^\W\d_]+)*$")
# ---- the dictionary shipped now (OpenSubtitles), words and pairs
b = open(f'{old_dir}/dict_{lang}.bin','rb').read()
n = struct.unpack_from('<i', b, 0)[0]; o = 4; old_words = []
for i in range(n):
    l = b[o]; w = b[o+1:o+1+l].decode(); f = struct.unpack_from('<i', b, o+1+l)[0]; o += 5 + l; old_words.append((w, f))
freq = collections.Counter()
for w, f in old_words: freq[w.lower()] += f
bb = open(f'{old_dir}/bigrams_{lang}.bin','rb').read()
m = struct.unpack_from('<i', bb, 0)[0]
bi = collections.Counter()
for i in range(m):
    v = struct.unpack_from('<q', bb, 4 + 8*i)[0]
    i1 = v >> 44; i2 = (v >> 26) & 0x3ffff; c = v & 0x3ffffff  # the shipped files still use the old packing
    bi[(old_words[i1][0].lower(), old_words[i2][0].lower())] += c
# ---- the corpora
for line in open(f'{work}/uni_{lang}.txt', encoding='utf-8'):
    c, w = line.rstrip('\n').split('\t'); freq[w] += int(c)
for line in open(f'{work}/bi_{lang}.txt', encoding='utf-8'):
    p = line.split()
    if len(p) == 3: bi[(p[1], p[2])] += int(p[0])
# ---- hunspell forms
hun = set()
for hf in hun_files:
    for line in open(hf, encoding='utf-8'):
        w = line.strip().lower()
        if ok.match(w): hun.add(w)
# A word outside Hunspell is kept only when common enough, and not when it is one slip away from a Hunspell word
# a hundred times more common (teh, recieve, thier): those are typos the corpora carry, not words.
ALPHA = "abcdefghijklmnopqrstuvwxyzăâîșț'"
def edits1(w):
    for i in range(len(w)): yield w[:i] + w[i+1:]
    for i in range(len(w)-1): yield w[:i] + w[i+1] + w[i] + w[i+2:]
    for i in range(len(w)):
        for ch in ALPHA:
            if ch != w[i]: yield w[:i] + ch + w[i+1:]
    for i in range(len(w)+1):
        for ch in ALPHA: yield w[:i] + ch + w[i:]
def typo(w):
    f = freq[w]
    for e in edits1(w):
        if e in hun and freq[e] >= 100 * f: return True
    return False
seen = set(); typos = 0
for w in freq:
    if not ok.match(w): continue
    if w in hun: seen.add(w); continue
    if freq[w] < MIN_UNSEEN_FREQ: continue
    if typo(w): typos += 1; continue
    seen.add(w)
print(lang, 'typos dropped', typos)
ranked = sorted(seen, key=lambda w: -freq[w])
stem = collections.defaultdict(int)
for w in seen:
    stem[w[:5]] = max(stem[w[:5]], freq[w])
unseen = sorted((w for w in hun if w not in seen), key=lambda w: (-stem.get(w[:5], 0), len(w), w))
words = ranked[:MAX_WORDS]
room = MAX_WORDS - len(words)
words += unseen[:room]
print(lang, 'seen', len(seen), 'hunspell', len(hun), 'unseen added', min(room, len(unseen)), 'unseen dropped', max(0, len(unseen) - room), 'final', len(words))
idx = {w: i for i, w in enumerate(words)}
pairs = [(c, a, b2) for (a, b2), c in bi.items() if a in idx and b2 in idx]
pairs.sort(reverse=True)
pairs = pairs[:MAX_BI]
print(lang, 'pairs kept', len(pairs), 'min count', pairs[-1][0] if pairs else 0)
with open(f'{out_dir}/dict_{lang}.bin', 'wb') as f:
    f.write(struct.pack('<i', len(words)))
    for w in words:
        wb = w.encode('utf-8')
        f.write(bytes([len(wb)]) + wb + struct.pack('<i', max(1, min(freq[w], 2**31 - 1))))
packed = sorted((idx[a] << 44) | (idx[b2] << 24) | min(c, 0xffffff) for c, a, b2 in pairs)
with open(f'{out_dir}/bigrams_{lang}.bin', 'wb') as f:
    f.write(struct.pack('<i', len(packed)))
    for v in packed: f.write(struct.pack('<q', v))
