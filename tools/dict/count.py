import sys, re, collections
lang=sys.argv[1]; uni_out=sys.argv[2]; files=sys.argv[3:]
tok=re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")
dia=re.compile('[ăâîșțĂÂÎȘȚ]')
ced=str.maketrans({'ş':'ș','ţ':'ț','Ş':'Ș','Ţ':'Ț'})
uni=collections.Counter(); out=sys.stdout
for path in files:
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            s=line.split('\t',1)[1] if '\t' in line else line
            if lang=='ro':
                s=s.translate(ced)
                if not dia.search(s): continue
            words=[w.lower() for w in tok.findall(s)]
            uni.update(words)
            for a,b in zip(words, words[1:]): out.write(a+' '+b+'\n')
with open(uni_out,'w',encoding='utf-8') as o:
    for w,c in uni.most_common(): o.write(f'{c}\t{w}\n')
print(lang, 'distinct', len(uni), file=sys.stderr)
