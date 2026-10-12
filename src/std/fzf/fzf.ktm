module fzf;
import cstr, libc, list, mem, str;

const i64 FZF_SCORE_MATCH = 16;

const i64 FZF_SCORE_GAP_START = -3;

const i64 FZF_SCORE_GAP_EXTENTION = -1;

const i64 FZF_BONUS_BOUNDARY = (FZF_SCORE_MATCH / 2);

const i64 FZF_BONUS_NON_WORD = (FZF_SCORE_MATCH / 2);

const i64 FZF_BONUS_CAMEL_123 = (FZF_BONUS_BOUNDARY + FZF_SCORE_GAP_EXTENTION);

const i64 FZF_BONUS_CONSECUTIVE = -(FZF_SCORE_GAP_START + FZF_SCORE_GAP_EXTENTION);

const i64 FZF_BONUS_FIRST_CHAR_MULTIPLIER = (2);

const i64 FZF_CHAR_NON_WORD = 0;

const i64 FZF_CHAR_LOWER = 1;

const i64 FZF_CHAR_UPPER = 2;

const i64 FZF_CHAR_LETTER = 3;

const i64 FZF_CHAR_NUMBER = 4;

pub struct Position {
    i64 *data;
    i64 size;
    i64 cap;
    i64 score;
};

struct FzFI16 {
    i16 *data;
    i64 size;
    i64 cap;
    bool allocated;
};

struct FzFI32 {
    i32 *data;
    i64 size;
    i64 cap;
    bool allocated;
};

struct FzFResult {
    i64 start;
    i64 end;
    i64 score;
};

struct FzFSlab {
    FzFI16 v16;
    FzFI32 v32;
};

struct FzFSlabConfig {
    i64 size16;
    i64 size32;
};

struct FzFString {
    u8 *data;
    i64 size;
};

struct FzFTerm {
    fn(FzFResult *res, bool caseSensitive,
            FzFString *text, FzFString *pattern, Position *pos, FzFSlab *slab) void cb;
    bool inv;
    u8 *ptr;
    void *text;
    bool caseSensitive;
};

struct FzFTermSet {
    list.List<FzFTerm *> entries;
    i64 size;
    i64 cap;
};

struct FzFPattern {
    list.List<FzFTermSet *> entries;
    i64 size;
    i64 cap;
    bool onlyInv;
};

struct FzFStrSlice {
    u8 *data;
    i64 size;
};

void sliceStr(FzFStrSlice *s, u8 *input, i64 from, i64 to) {
    s.data = input+from;
    s.size = to-from;
}

void sliceStrRight(FzFStrSlice *s, u8 *input, i64 to) {
    sliceStr(s, input, 0, to);
}

i32 indexByte(FzFString *str, u8 b) {
    for (auto i = 0; i < str.size; i++) {
        if (str.data[i] == b) {
            return (i32)i;
        }
    }
    return -1;
}

i64 leadingWhitespaces(FzFString *strVal)
{ // XXX: extract to strings.ktm
    i64 whitespaces = 0;
    for (i64 i = 0; i < strVal.size; i++) {
        if (!str.isSpace((u8)strVal.data[i])) {
            break;
        }
        whitespaces++;
    }
    return whitespaces;
}

i64 trailingWhitespaces(FzFString *strVal)
{ // XXX: extract to strings.ktm
    i64 whitespaces = 0;
    for (i64 i = strVal.size - 1; i >= 0; i--) {
        if (!str.isSpace((u8)strVal.data[i])) {
            break;
        }
        whitespaces++;
    }
    return whitespaces;
}

bool hasSuffix(u8 *strVal, i64 len, u8 *suffix, i64 suffixLen) {
    FzFStrSlice s;
    sliceStr(&s, strVal, len - suffixLen, len);
    return len >= suffixLen && !cstr.cmpLen(s.data, suffix, suffixLen);
}

u8 *strReplaceChar(u8 *str, u8 find, u8 replace) {
    u8 *currentPos = libc.strchr(str, find);
    while (currentPos) {
        *currentPos = replace;
        currentPos = libc.strchr(currentPos, find);
    }
    return str;
}

u8 *strReplace(u8 *orig, u8 *rep, u8 *with) {
    if (!orig || !rep || !with) {
        return null;
    }

    u8 *result,*ins,*tmp;

    i64 lenRep = cstr.len(rep);
    i64 lenFront = 0;
    i64 lenOrig = cstr.len(orig);
    i64 lenWith = cstr.len(with);
    i64 count = 0;

    if (lenRep == 0) {
        return null;
    }

    ins = orig;
    for (; (tmp = libc.strstr(ins, rep)); ++count) {
        ins = tmp + lenRep;
    }

    tmp = result = mem.alloc(lenOrig + (lenWith - lenRep) * count + 1);
    if (!result) {
        return null;
    }

    while (count--) {
        ins = libc.strstr(orig, rep);
        lenFront = ins - orig;
        mem.copy(tmp, orig, lenFront);  /* C's strncpy, not StrNCpy */
        tmp += lenFront;
        cstr.copy(tmp,with);
        tmp += lenWith;
        orig += lenFront + lenRep;
        lenOrig -= lenFront + lenRep;
    }
    mem.copy(tmp,orig,lenOrig);
    tmp[lenOrig] = 0;
    return result;
}

u8 *strTolower(u8 *strVal, i64 size) {
    u8 *lower = mem.alloc((size+2)*sizeof(u8));
    for (i64 i = 0; i < size; ++i) {
        lower[i] = str.toLower(strVal[i]);
    }
    lower[size] = '\0';
    return lower;
}

i16 max16(i16 a, i16 b) {
    if (a > b) { return a; }
    return b;
}

Position *fzfPosArray(i64 len) {
    Position *pos = mem.alloc(sizeof(Position));
    pos.size = 0;
    pos.cap = len;
    if (len > 0) {
        pos.data = mem.alloc(len * sizeof(i64));
    } else {
        pos.data = null;
    }
    return pos;
}

void resizePos(Position *pos, i64 addLen, i64 comp) {
    if (!pos) {
        return;
    }
    if (pos.size + comp > pos.cap) {
        if (addLen > 0) {
            pos.cap += addLen;
        } else {
            pos.cap += 1;
        }
        pos.data = mem.realloc(pos.data, sizeof(i64) * pos.cap);
    }
}

void appendPos(Position *pos, i64 value) {
    if (pos) {
        resizePos(pos, pos.cap, 10);
        pos.data[pos.size] = value;
        pos.size++;
    }
}

void insertRange(Position *pos, i64 start, i64 end) {
    if (!pos) {
        return;
    }

    i32 diff = ((i32)end - (i32)start);
    if (diff <= 0) {
        return;
    }

    resizePos(pos, diff, diff);
    for (i64 i = start; i < end; i++) {
        pos.data[pos.size] = i;
        pos.size++;
    }
}

i32 charClsOfAscii(u8 ch) {
    if ('a' <= ch <= 'z') { return FZF_CHAR_LOWER; }
    if ('A' <= ch <= 'Z') { return FZF_CHAR_UPPER; }
    if ('0' <= ch <= '9') { return FZF_CHAR_NUMBER; }
    return FZF_CHAR_NON_WORD;
}

i32 charClsOf(u8 ch) {
    return charClsOfAscii(ch);
}

i16 bonusFor(i32 prevCls, i32 cls) {
    if (prevCls == FZF_CHAR_NON_WORD && cls != FZF_CHAR_NON_WORD) {
        return FZF_BONUS_BOUNDARY;
    }
    if ((prevCls == FZF_CHAR_LOWER && cls == FZF_CHAR_UPPER) ||
            (prevCls != FZF_CHAR_NUMBER && cls == FZF_CHAR_NUMBER)) {
        return FZF_BONUS_CAMEL_123;
    }
    if (cls == FZF_CHAR_NON_WORD) {
        return FZF_BONUS_NON_WORD;
    }
    return 0;
}

i16 bonusAt(FzFString *input, i64 idx) {
    if (idx == 0) {
        return FZF_BONUS_BOUNDARY;
    }
    return bonusFor(charClsOf(input.data[idx - 1]),
            charClsOf(input.data[idx]));
}

i32 trySkip(FzFString *input, bool caseSensitive, u8 b, i32 from) {
    FzFStrSlice slice;
    sliceStr(&slice, input.data, (i64)from, input.size);
    FzFString byteArray;
    byteArray.data = slice.data;
    byteArray.size = slice.size;
    i32 idx = indexByte(&byteArray, b);
    if (idx == 0) {
        return from;
    }

    if (!caseSensitive && ('a' <= b <= 'z')) {
        if (idx > 0) {
            FzFStrSlice tmp;
            sliceStrRight(&tmp, byteArray.data, (i64)idx);
            byteArray.data = tmp.data;
            byteArray.size = tmp.size;
        }
        i32 uidx = indexByte(&byteArray, b - 32);
        if (uidx >= 0) {
            idx = uidx;
        }
    }
    if (idx < 0) {
        return -1;
    }

    return from + idx;
}

i32 asciiFuzzyIndex(FzFString *input, u8 *pattern,
        i64 size, bool caseSensitive) {
    i32 firstIdx = 0, idx = 0;
    for (i64 pidx = 0; pidx < size; pidx++) {
        idx = trySkip(input, caseSensitive, pattern[pidx], idx);
        if (idx < 0) {
            return -1;
        }
        if (pidx == 0 && idx > 0) {
            firstIdx = idx - 1;
        }
        idx++;
    }

    return firstIdx;
}

i32 fzfCalculateScore(bool caseSensitive, FzFString *text, FzFString *pattern,
        i64 sidx, i64 eidx, Position *pos) {
    auto M = pattern.size;

    i64 pidx = 0;
    i64 score = 0, consecutive = 0;
    bool inGap = false;
    i16 firstBonus = 0;

    resizePos(pos, M, M);
    i32 prevCls = FZF_CHAR_NON_WORD;
    if (sidx > 0) {
        prevCls = charClsOf(text.data[sidx - 1]);
    }
    for (i64 idx = sidx; idx < eidx; idx++) {
        u8 c = text.data[idx];
        i32 cls = charClsOf(c);
        if (!caseSensitive) {
            c = str.toLower(c);
        }
        if (c == pattern.data[pidx]) {
            appendPos(pos, idx);
            score += FZF_SCORE_MATCH;
            i16 bonus = bonusFor(prevCls, cls);
            if (consecutive == 0) {
                firstBonus = bonus;
            } else {
                if (bonus == FZF_BONUS_BOUNDARY) {
                    firstBonus = bonus;
                }
                bonus = max16(max16(bonus, firstBonus), FZF_BONUS_CONSECUTIVE);
            }
            if (pidx == 0) {
                score += (i32)(bonus * FZF_BONUS_FIRST_CHAR_MULTIPLIER);
            } else {
                score += (i32)bonus;
            }
            inGap = false;
            consecutive++;
            pidx++;
        } else {
            if (inGap) {
                score += FZF_SCORE_GAP_EXTENTION;
            } else {
                score += FZF_SCORE_GAP_START;
            }
            inGap = true;
            consecutive = 0;
            firstBonus = 0;
        }
        prevCls = cls;
    }
    return score;
}

void fzfPrefixMatch(FzFResult *res, bool caseSensitive, FzFString *text,
        FzFString *pattern, Position *pos, FzFSlab *slab) {
    i64 M,trimmedLen,start,end;
    i32 score;
    u8 c;
    start = end = score = trimmedLen = 0;
    M = pattern.size;

    if (M == 0) { goto no_match; }

    if (!str.isSpace(pattern.data[0])) {
        trimmedLen = leadingWhitespaces(text);
    }

    if ((text.size - trimmedLen) < M) { goto no_match; }

    for (i64 i = 0; i < M; ++i) {
        c = text.data[trimmedLen + i];
        if (!caseSensitive) { c = str.toLower(c); }
        if (c != pattern.data[i]) { goto no_match; }
    }

    res.end = trimmedLen + M;
    res.start = trimmedLen;
    res.score = fzfCalculateScore(caseSensitive,text,pattern,res.start,
                                                                 res.end,null);
    insertRange(pos,res.start,res.end);
    return;

no_match:
    res.start = -1;
    res.end = -1;
    res.score = 0;
}

void fzfSuffixMatch(FzFResult *res, bool caseSensitive,
        FzFString *text, FzFString *pattern,
        Position *pos, FzFSlab *slab) {
    i64 trimmedLen = text.size, M = pattern.size,diff,start,end;
    i32 score;

    if (M == 0 || !str.isSpace(pattern.data[M - 1])) {
        trimmedLen -= trailingWhitespaces(text);
    }

    if (M == 0) { goto no_match; }

    diff = trimmedLen - M;
    if (diff < 0) { goto no_match; }

    for (i64 i = 0; i < M; ++i) {
        u8 c = text.data[i+diff];
        if (!caseSensitive) { c = str.toLower(c); }
        if (c != pattern.data[i]) { goto no_match; }
    }

    res.start = trimmedLen - M;
    res.end = trimmedLen;
    res.score = fzfCalculateScore(caseSensitive,text,pattern,res.start,
            res.end,null);

    insertRange(pos,res.start,res.end);
    return;

no_match:
    res.start = -1;
    res.end = -1;
    res.score = 0;
    return;
}

void fzfEqNaiveMatch(FzFResult *res, bool caseSensitive, FzFString *text,
        FzFString *pattern, Position *pos, FzFSlab *slab) {
    i64 M,N;
    i64 start,end,bestBonus,bonus,pidx;
    i32 bestPos,score;
    u8 c;

    M = pattern.size;
    N = text.size;
    pidx = bonus = 0;
    bestPos = bestBonus = -1;


    if (M == 0) {
        goto no_match;
    }
    if (N<M) {
        goto no_match;
    }
    if (asciiFuzzyIndex(text,pattern.data,M,caseSensitive) < 0) {
        goto no_match;
    }

    for (i64 i = 0; i < N; ++i) {
        c = text.data[i];
        if (!caseSensitive) { c = str.toLower(c); }

        if (c == pattern.data[pidx]) {
            if (pidx == 0) {
                bonus = bonusAt(text,i);
            }
            ++pidx;

            if (pidx == M) {
                if (bonus > bestBonus) {
                    bestPos = i;
                    bestBonus = bonus;
                }
                if (bonus == FZF_BONUS_BOUNDARY) { break; }

                i -= pidx-1;
                pidx = 0;
                bonus = 0;
            }
        } else {
            i -= pidx;
            pidx = 0;
            bonus = 0;
        }
    }

    if (bestPos >= 0) {
        auto bp = bestPos;
        res.start = bp - M + 1;
        res.end = bp + 1;
        res.score = fzfCalculateScore(caseSensitive,text,pattern,
                res.start,res.end,null);
        insertRange(pos,res.start,res.end);
        return;
    }

no_match:
    res.start = -1;
    res.end = -1;
    res.score = 0;
}

void fzfEqMatch(FzFResult *res, bool caseSensitive, FzFString *text,
        FzFString *pattern, Position *pos, FzFSlab *slab) {
    auto M = pattern.size;
    i64 trimmedLen,trimmedEndLen,start,end;
    i32 score;
    bool match;
    u8 c,pchar;

    if (M == 0) {
        start = end = -1;
        score = 0;
        goto out;
    }

    trimmedLen = leadingWhitespaces(text);
    trimmedEndLen = trailingWhitespaces(text);

    if ((text.size - trimmedLen - trimmedEndLen) != M) {
        start = end = -1;
        score = 0;
        goto out;
    }

    match = true;
    for (i64 i = 0; i < M; ++i) {
        pchar = pattern.data[i];
        c = text.data[trimmedLen+i];
        if (!caseSensitive) { c = str.toLower(c); }
        if (c != pchar) {
            match = false;
            break;
        }
    }

    if (match) {
        insertRange(pos,trimmedLen,trimmedLen+M);
        start = trimmedLen;
        end = trimmedLen+M;
        score = (FZF_SCORE_MATCH+FZF_BONUS_BOUNDARY) * M + 
            (FZF_BONUS_FIRST_CHAR_MULTIPLIER-1) * FZF_BONUS_BOUNDARY;
    } else {
        start = end = -1;
        score = 0;
    } 

out:
    res.start = start;
    res.end = end;
    res.score = score;
}

void fzfFuzzyMatchV1(FzFResult *res, bool caseSensitive, FzFString *text,
        FzFString *pattern, Position *pos, FzFSlab *slab) {
    auto M = pattern.size;
    auto N = text.size;
    i64 start, end;

    if (M == 0) {
        return;
    }
    if (asciiFuzzyIndex(text, pattern.data, M, caseSensitive) < 0) {
        res.start = -1;
        res.end = -1;
        res.score = 0;
        return;
    }

    i64 pidx = 0,sidx = -1, eidx = -1;

    for (i64 idx = 0; idx < N; idx++) {
        u8 c = text.data[idx];
        if (!caseSensitive) {
            c = str.toLower(c);
        }
        if (c == pattern.data[pidx]) {
            if (sidx < 0) {
                sidx = idx;
            }
            pidx++;
            if (pidx == M) {
                eidx = (i32)(idx + 1);
                break;
            }
        }
    }

    if (sidx >= 0 && eidx >= 0) {
        start = sidx;
        end = eidx;
        pidx--;
        for (i64 idx = end - 1; idx >= start; idx--) {
            u8 c = text.data[idx];
            if (!caseSensitive) {
                c = str.toLower(c);
            }
            if (c == pattern.data[pidx]) {
                pidx--;
                if (pidx < 0) {
                    start = idx;
                    break;
                }
            }
        }

        auto score = fzfCalculateScore(caseSensitive, text,
                pattern, start, end, pos);
        res.start = start;
        res.end = end;
        res.score = score;
        return;
    }
    res.start = -1;
    res.end = -1;
    res.score = 0;
    return;
}

FzFPattern *fzfParsePattern(i32 caseMode, u8 *pattern, bool fuzzy=true) {
    FzFPattern *patObj = mem.allocZero(sizeof(FzFPattern));
    patObj.entries = list.new<FzFTermSet *>();
    FzFTerm term;

    i64 patLen = cstr.len(pattern);
    if (patLen == 0) {
        return patObj;
    }

    /* Trimmed in a copy: the caller's string may be a literal. */
    u8 *patternDup = cstr.dup(pattern);
    u8[] trimmed = str.trimStart(patternDup[0:patLen]);
    pattern = trimmed.ptr;
    patLen = trimmed.len;
    while (hasSuffix(pattern, patLen, " ", 1) &&
            !hasSuffix(pattern, patLen, "\\ ", 2)) {
        pattern[patLen - 1] = 0;
        patLen--;
    }

    u8 *patternCopy = strReplace(pattern, "\\ ", "\t");
    mem.free(patternDup);
    u8 *delim = " ";
    u8 *tokSave = null;
    u8 *ptr = libc.strtok_r(patternCopy, delim, &tokSave);

    FzFTermSet *set = mem.allocZero(sizeof(FzFTermSet));
    set.entries = list.new<FzFTerm *>();

    bool switchSet = false;
    bool afterBar = false;
    while (ptr != null) {
        fn(FzFResult *res, bool _case_sensitive,
                FzFString *_text, FzFString *_pattern,
                Position *_pos, FzFSlab *_slab) void cb = &fzfFuzzyMatchV1;

        bool inv = false;

        auto len = cstr.len(ptr);
        strReplaceChar(ptr, '\t', ' ');
        u8 *text = cstr.dup(ptr);

        u8 *ogStr = text;
        u8 *lowerText = strTolower(text, len);
        bool caseSensitive = caseMode == CASE_RESPECT ||
                                                 (caseMode == CASE_SMART && 
                                                    cstr.cmp(text, lowerText) != 0);

        if (!caseSensitive) {
            if (text) {
                mem.free(text);
            }
            text = lowerText;
            ogStr = lowerText;
        } else {
            if (lowerText) {
                mem.free(lowerText);
            }
        }

        if (!fuzzy) {
            cb = &fzfEqNaiveMatch;
        }
        if (set.size > 0 && !afterBar && cstr.cmp(text, "|") == 0) {
            switchSet = false;
            afterBar = true;
            ptr = libc.strtok_r(null, delim, &tokSave);
            if (ogStr) {
                mem.free(ogStr);
            }
            continue;
        }
        afterBar = false;
        if (!cstr.cmpLen(text, "!", 1)) {
            inv = true;
            cb = &fzfEqNaiveMatch;
            text++;
            len--;
        }

        if (cstr.cmp(text, "$") != 0 && hasSuffix(text, len, "$", 1)) {
            cb = &fzfSuffixMatch;
            text[len - 1] = '\0';
            len--;
        }

        if (!cstr.cmpLen(text, "'", 1)) {
            if (fuzzy && !inv) {
                cb = &fzfEqNaiveMatch;
                text++;
                len--;
            } else {
                cb = &fzfFuzzyMatchV1;
                text++;
                len--;
            }
        } else if (!cstr.cmpLen(text, "^", 1)) {
            if (cb == &fzfSuffixMatch) {
                cb = &fzfEqMatch;
            } else {
                cb = &fzfPrefixMatch;
            }
            text++;
            len--;
        }

        if (len > 0) {
            if (switchSet) {
                patObj.entries.append(set);
                set = mem.alloc(sizeof(FzFTermSet));
                set.entries = list.new<FzFTerm *>();
                set.cap = 0;
                set.size = 0;
            }
            FzFString *textPtr = mem.alloc(sizeof(FzFString));
            textPtr.data = text;
            textPtr.size = len;
            FzFTerm *t = mem.alloc(sizeof(FzFTerm));
            t.cb = cb;
            t.inv = inv;
            t.ptr = ogStr;
            t.caseSensitive = caseSensitive;
            t.text = textPtr;

            set.entries.append(t);
            set.size++;
            switchSet = true;
        } else {
            if (ogStr) {
                mem.free(ogStr);
            }
        }

        ptr = libc.strtok_r(null, delim, &tokSave);
    }
    if (set.size > 0) {
        patObj.entries.append(set);
        patObj.size++;
    } else {
        if (set) {
            set.entries.free();
            mem.free(set);
        }
    }
    bool only = true;

    for (auto it = patObj.entries.head; it; it = it.next) {
        auto termSet = (FzFTermSet *)it.value;
        if (termSet.size > 1) {
            only = false;
            break;
        }

        bool x = termSet.entries.head.value.inv;
        if (!x) {
            only = false;
            break;
        }
    }

    patObj.onlyInv = only;
    if (patternCopy) {
        mem.free(patternCopy);
    }
    return patObj;
}

static void fzfTermRelease(void *_term) {
    FzFTerm *term = _term;
    if (term) {
        mem.free(term.ptr);
        mem.free(term.text);
        mem.free(term);
    }
}

static void fzfTermSetRelease(void *_set) {
    FzFTermSet *set = _set;
    if (set) {
        for (auto n = set.entries.head; n; n = n.next) { fzfTermRelease(n.value); }
        set.entries.free();
        mem.free(set);
    }
}

void fzfPatternRelease(FzFPattern *pattern) {
    if (pattern) {
        for (auto n = pattern.entries.head; n; n = n.next) { fzfTermSetRelease(n.value); }
        pattern.entries.free();
        mem.free(pattern);
    }
}

i64 fzfGetScore(u8 *text, FzFPattern *pattern, FzFSlab *slab) {
    if (text == null || !pattern.entries.len) { return 1; }

    auto entries = &pattern.entries;
    FzFString input;
    input.data = text;
    input.size = cstr.len(text);

    if (pattern.onlyInv) {
        i64 final = 0;
        for (auto it = entries.head; it; it = it.next) {
            FzFTermSet *termSet = it.value;
            FzFTerm *term = termSet.entries.head.value;
            FzFResult res;
            term.cb(&res,term.caseSensitive,&input,(FzFString *)term.text,null,slab);
            final += res.score;
        }
        if (final > 0) {
            return 0;
        }
        return 1;
    }

    i64 totalScore = 0;
    for (auto it = entries.head; it; it = it.next) {
        FzFTermSet *termSet = (FzFTermSet *)it.value;
        i64 currentScore = 0;
        bool matched = false;
        auto termEntries = &termSet.entries;
        for (auto ii = termEntries.head; ii; ii = ii.next) {
            FzFTerm *term = (FzFTerm *)ii.value;
            auto fzfStr = (FzFString *)term.text;

            FzFResult res;
            term.cb(&res,term.caseSensitive,&input,fzfStr,null,slab);

            if (res.start >= 0) {
                if (term.inv) {
                    continue;
                }
                currentScore = res.score;
                matched = true;
                break;
            }

            if (term.inv) {
                currentScore = 0;
                matched = true;
            }
        }
        if (matched) {
            totalScore += currentScore;
        } else {
            totalScore = 0;
            break;
        }
    }

    return totalScore;
}

pub void release(Position *pos) {
    if (pos) {
        if (pos.data) {
            mem.free(pos.data);
        }
        mem.free(pos);
    }
}

Position *fzfGetPositions(u8 *text, FzFPattern *pattern, FzFSlab *slab) {
    FzFString input;
    FzFResult res;
    Position *allPos;
    FzFTermSet *termSet;
    FzFString *fzfStr;
    FzFTerm *term;
    bool matched;

    if (text == null || !pattern.entries.len) { return null; }

    input.data = text;
    input.size = cstr.len(text);
    allPos = fzfPosArray(0);
    auto entries = &pattern.entries;

    for (auto it = entries.head; it; it = it.next) {
        termSet = (FzFTermSet *)it.value;
        matched = false;

        auto termEntries = &termSet.entries;
        for (auto ii = termEntries.head; ii; ii = ii.next) {
            term = (FzFTermSet *)ii.value;
            fzfStr = (FzFString *)term.text;

            if (term.inv) {
                term.cb(&res,term.caseSensitive,&input,fzfStr,null,slab);
                if (res.start < 0) {
                    matched = true;
                }
                continue;
            }

            term.cb(&res,term.caseSensitive,&input,fzfStr,allPos,slab);
            if (res.start >= 0) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            release(allPos);
            return null;
        }
    }
    return allPos;
}

FzFSlab *fzfSlabNew(FzFSlabConfig *config) {
    FzFSlab *slab = mem.allocZero(sizeof(FzFSlab));

    slab.v16.data = mem.allocZero(config.size16 * sizeof(i16));

    slab.v16.cap = config.size16;
    slab.v16.size = 0;
    slab.v16.allocated = true;

    slab.v32.data = mem.allocZero(config.size32 * sizeof(i32));
    slab.v32.cap = config.size32;
    slab.v32.size = 0;
    slab.v32.allocated = true;

    return slab;
}

FzFSlab *fzfDefaultSlab() {
    FzFSlabConfig config;
    config.size16 = (1<<10) * 100;
    config.size32 = 1<<11;
    return fzfSlabNew(&config);
}

void fzfSlabRelease(FzFSlab *slab) {
    if (slab) {
        mem.free(slab.v16.data);
        mem.free(slab.v32.data);
        mem.free(slab);
    }
}

pub Position *match(u8 *text, u8 *seq, i32 caseMode=CASE_SMART,
        bool fuzzy=true) {
    /* No slab: the matchers here (V1 and the exact/prefix/suffix ones)
     * never use one, and allocating the 200 KB default slab on every call
     * only cost time. Nothing shared either, so FzF is safe from threads. */
    auto pattern = fzfParsePattern(caseMode,seq,fuzzy);
    if (!pattern) {
        return null;
    }
    auto score = fzfGetScore(text,pattern,null);
    auto pos = fzfGetPositions(text,pattern,null);
    if (pos) {
        pos.score = score;
    }

    fzfPatternRelease(pattern);
    return pos;
}
