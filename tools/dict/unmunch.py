import re, sys
def parse_aff(path):
    rules = {}  # flag -> (kind, cross, [(strip, add, cond_re, contflags)])
    flagtype = 'char'; needaffix=None; keepcase=None
    for line in open(path, encoding='utf-8', errors='replace'):
        p = line.strip().split()
        if not p or p[0].startswith('#'): continue
        if p[0] == 'FLAG': flagtype = p[1]
        elif p[0] == 'NEEDAFFIX': needaffix = p[1]
        elif p[0] in ('PFX','SFX'):
            if len(p) == 4 and p[2] in ('Y','N'):
                rules[p[1]] = (p[0], p[2]=='Y', [])
            elif len(p) >= 4 and p[1] in rules:
                strip = '' if p[2]=='0' else p[2]
                add = p[3]; cont = ''
                if '/' in add: add, cont = add.split('/',1)
                if add == '0': add = ''
                cond = p[4] if len(p) > 4 else '.'
                rx = cond_regex(cond, p[0])
                rules[p[1]][2].append((strip, add, rx, cont))
    return rules, flagtype, needaffix
def cond_regex(cond, kind):
    if cond == '.': return None
    out=''; i=0
    while i < len(cond):
        c=cond[i]
        if c=='[':
            j=cond.index(']',i); out+=cond[i:j+1]; i=j+1
        elif c=='.': out+='.'; i+=1
        else: out+=re.escape(c); i+=1
    return re.compile(out+'$' if kind=='SFX' else '^'+out)
def split_flags(f, flagtype):
    if flagtype == 'long': return [f[i:i+2] for i in range(0,len(f),2)]
    if flagtype == 'num': return f.split(',')
    return list(f)
def apply(word, flag, rules, flagtype, depth=0):
    kind, cross, rl = rules[flag]
    out=[]
    for strip, add, rx, cont in rl:
        if rx and not rx.search(word): continue
        if kind == 'SFX':
            if strip and not word.endswith(strip): continue
            base = word[:len(word)-len(strip)] if strip else word
            w = base + add
        else:
            if strip and not word.startswith(strip): continue
            base = word[len(strip):] if strip else word
            w = add + base
        out.append((w, cont, cross))
    return out
def unmunch(dic, aff):
    rules, flagtype, needaffix = parse_aff(aff)
    forms=set()
    with open(dic, encoding='utf-8', errors='replace') as f:
        next(f)
        for line in f:
            line=line.strip()
            if not line or line.startswith('#'): continue
            entry=line.split('\t')[0].split(' ')[0]
            if '/' in entry: word, flags = entry.split('/',1)
            else: word, flags = entry, ''
            word=word.replace('\\/','/')
            fl=split_flags(flags, flagtype) if flags else []
            alone = not (needaffix and needaffix in fl)
            if alone: forms.add(word)
            sfx=[]; pfx=[]
            for g in fl:
                if g not in rules: continue
                if rules[g][0]=='SFX': sfx += apply(word, g, rules, flagtype)
                else: pfx += apply(word, g, rules, flagtype)
            # continuation classes on suffixes: one more level
            more=[]
            for w,cont,cross in sfx:
                for g in split_flags(cont, flagtype) if cont else []:
                    if g in rules and rules[g][0]=='SFX': more += apply(w, g, rules, flagtype)
                    elif g in rules: pfx += [(x, '', c) for x,_,c in apply(w, g, rules, flagtype)]
            sfx += more
            for w,_,_ in sfx: forms.add(w)
            for w,_,_ in pfx: forms.add(w)
            # cross product: prefix rules on suffixed forms, when both allow it
            for pw,pc,pcross in pfx:
                pass
            for g in fl:
                if g in rules and rules[g][0]=='PFX' and rules[g][1]:
                    for sw,sc,scross in sfx:
                        if scross:
                            for x,_,_ in apply(sw, g, rules, flagtype): forms.add(x)
    return forms
if __name__ == '__main__':
    forms = unmunch(sys.argv[1], sys.argv[2])
    with open(sys.argv[3],'w',encoding='utf-8') as o:
        for w in sorted(forms): o.write(w+'\n')
    print(len(forms))
