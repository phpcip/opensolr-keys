"""
Expands the hand-kept word list into the asset the keyboard reads: python3 extra.py extra_ro.txt ../../app/src/main/assets/words_ro.txt
A line is a word, or stem|ending,ending,... (an empty ending is the stem itself).
"""
import sys
out = []
for line in open(sys.argv[1], encoding='utf-8'):
    line = line.strip()
    if not line:
        continue
    if '|' in line:
        stem, ends = line.split('|', 1)
        out += [stem + e for e in ends.split(',')]
    else:
        out.append(line)
words = sorted({w.lower() for w in out if len(w) > 1 and all(c.isalpha() or c == "'" for c in w)})
open(sys.argv[2], 'w', encoding='utf-8').write('\n'.join(words) + '\n')
print(len(words), 'words')
