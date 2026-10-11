import test;

import mem, str;

enum Err { compress };

/* Integration test: LZW compress/decompress lifted verbatim from
 * TempleOS (TempleOS/Kernel/Compress.HC). Exercises in one file:
 *   - class self-reference without forward decl (CArcEntry, CArcCtrl)
 *   - trailing void placeholder field (CArcCompress.body)
 *   - pointer relational comparisons (src_ptr<src_limit etc.)
 *   - throw/try with a real handler around uncaught conditions
 *   - default function parameters (ArcCtrlNew)
 *   - switch[], goto labels, hash-chain pointer overlay
 * Only change: explicit casts where TempleOS converts pointers silently
 * (&c->hash[i] used as a CArcEntry whose next field is the slot, and the
 * archive as a u8 buffer), so it compiles without warnings.
 * Regression on any one of these will scramble the round-tripped bytes.
 *
 * **NOTE ON WARNINGS:**
 * The type checker emits warnings as we have type checking, however TempleOS
 * does not have typechecking. However we should not modify the implementation
 * of the below to satisfy typechecking.
 */

const i64 ARC_BITS_MAX = 12;
const i64 CT_NONE = 1;
const i64 CT_7_BIT = 2;
const i64 CT_8_BIT = 3;


u64 memMappedSpace;

const i64 U32_MAX = 4294967295;

pub struct CArcCompress {
  i64	compressedSize,expandedSize;
  u8	compressionType;
  void	body;
};

struct CArcEntry {
  CArcEntry *next;
  u16	basecode;
  u8	ch,pad;
  u32	pad;
};

pub struct CArcCtrl {
  i64	srcPos,srcSize,
    dstPos,dstSize;
  u8	*srcBuf,*dstBuf;
  i64	minBits,minTableEntry;
  CArcEntry *curEntry,*nextEntry;
  i64	curBitsInUse,nextBitsInUse;
  u8	*stkPtr,*stkBase;
  i64	freeIdx,freeLimit,
    savedBasecode,
    entryUsed,
    lastCh;
  CArcEntry compress[1<<ARC_BITS_MAX],
            *hash[1<<ARC_BITS_MAX];
};

void bFieldOrU32(u8 *bitField, i64 bitNum, u64 val) {
  u64 *q = bitField + (bitNum >> 3);
  *q |= val << (bitNum & 7);
}

u64 bFieldExtU32(u8 *bitField, i64 bitNum, i64 width) {
  /* Canonical TempleOS form: 8-byte load via u64 *. The version
   * originally pasted in scratch/compress.ktm reads via
   * `*(bit_field + ...)`, which is a u8 deref (1 byte) - any code
   * wider than (8 - bit_num%8) bits is then masked out and the LZW
   * dictionary lookup goes off the rails. */
  u64 *q = bitField + (bitNum >> 3);
  return *q >> (bitNum & 7) & ((1 << width) - 1);
}

i64 arcDetermineCompressionType(u8 *src,i64 size) {
  while (size--) {
    if (*src++ & 0x80) {
      return CT_8_BIT;
    }
  }
  return CT_7_BIT;
}

bool arcFinishCompression(CArcCtrl *c)
{//Do closing touch on archivew ctrl struct.
  if (c.dstPos+c.curBitsInUse<=c.dstSize) {
    bFieldOrU32(c.dstBuf,c.dstPos,c.savedBasecode);
    c.dstPos+=c.nextBitsInUse;
    return true;
  } else {
    return false;
  }
}

void arcEntryGet(CArcCtrl *c) {
  i64 i;
  CArcEntry *tmp,*tmp1;

  if (c.entryUsed) {
    i=c.freeIdx;

    c.entryUsed=false;
    c.curEntry=c.nextEntry;
    c.curBitsInUse=c.nextBitsInUse;
    if (c.nextBitsInUse<ARC_BITS_MAX) {
      c.nextEntry = &c.compress[i++];
      if (i==c.freeLimit) {
    c.nextBitsInUse++;
    c.freeLimit=1<<c.nextBitsInUse;
      }
    } else {
      do { if (++i==c.freeLimit) {
      i=c.minTableEntry;
      }
      } while (c.hash[i]);
      tmp=&c.compress[i];
      c.nextEntry=tmp;
      tmp1=(CArcEntry *)(&c.hash[tmp.basecode]);
      while (tmp1) {
    if (tmp1.next==tmp) {
      tmp1.next=tmp.next;
      break;
    } else {
      tmp1=tmp1.next;
    }
      }
    }
    c.freeIdx=i;
  }
}

CArcCtrl *arcCtrlNew(bool expand,i64 compressionType=CT_8_BIT)
{//MAlloc archive ctrl struct.
  CArcCtrl *c;
  c=mem.allocZero(sizeof(CArcCtrl));
  if (expand) {
    c.stkBase=mem.alloc(1<<ARC_BITS_MAX);
    c.stkPtr=c.stkBase;
  }
  if (compressionType==CT_7_BIT) {
    c.minBits=7;
  } else {
    c.minBits=8;
  }
  c.minTableEntry=1<<c.minBits;
  c.freeIdx=c.minTableEntry;
  c.nextBitsInUse=c.minBits+1;
  c.freeLimit=1<<c.nextBitsInUse;
  c.savedBasecode=U32_MAX;
  c.entryUsed=true;
  arcEntryGet(c);
  c.entryUsed=true;
  return c;
}

void arcCtrlDel(CArcCtrl *c)
{//Free archive ctrl struct.
  mem.free(c.stkBase);
  mem.free(c);
}

void arcCompressBuf(CArcCtrl *c)
{//Use $LK,"CompressBuf",A="MN:CompressBuf"$() unless doing more than one buf.
  CArcEntry *tmp,*tmp1;
  i64 ch,basecode;
  u8 *srcPtr,*srcLimit;

  srcPtr=c.srcBuf+c.srcPos;
  srcLimit=c.srcBuf+c.srcSize;

  if (c.savedBasecode==U32_MAX) {
    basecode=*srcPtr++;
  } else {
    basecode=c.savedBasecode;
  }

  while (srcPtr<srcLimit && c.dstPos+c.curBitsInUse<=c.dstSize) {
    arcEntryGet(c);
ac_start:
    if (srcPtr>=srcLimit) { goto ac_done; }
    ch=*srcPtr++;
    if (tmp=c.hash[basecode]) {
      do {
    if (tmp.ch==ch) {
      basecode=tmp-&c.compress[0];
      goto ac_start;
    }
      } while (tmp=tmp.next);
    }

    bFieldOrU32(c.dstBuf,c.dstPos,basecode);
    c.dstPos+=c.curBitsInUse;

    c.entryUsed=true;
    tmp=c.curEntry;
    tmp.basecode=basecode;
    tmp.ch=ch;
    tmp1=(CArcEntry *)(&c.hash[basecode]);
    tmp.next=tmp1.next;
    tmp1.next=tmp;

    basecode=ch;
  }
ac_done:
  c.savedBasecode=basecode;
  c.srcPos=srcPtr-c.srcBuf;
}

CArcCompress *compressBuf(u8 *src,i64 size)
{//See $LK,"::/Demo/Dsk/SerializeTree.ktm"$.
  CArcCompress *arc;
  i64 sizeOut,compressionType=arcDetermineCompressionType(src,size);
  CArcCtrl *c=arcCtrlNew(false,compressionType);
  c.srcSize=size;
  c.srcBuf=src;
  c.dstSize=(size+sizeof(CArcCompress))<<3;
  c.dstBuf=mem.allocZero(c.dstSize>>3);
  c.dstPos=sizeof(CArcCompress)<<3;
  arcCompressBuf(c);
  if (arcFinishCompression(c) && c.srcPos==c.srcSize) {
    sizeOut=(c.dstPos+7)>>3;
    arc=mem.alloc(sizeOut);
    mem.copy(arc,c.dstBuf,sizeOut);
    arc.compressionType=compressionType;
    arc.compressedSize=sizeOut;
  } else {
    arc=mem.alloc(size+sizeof(CArcCompress));
    mem.copy(&arc.body,src,size);
    arc.compressionType=CT_NONE;
    arc.compressedSize=size+sizeof(CArcCompress);
  }
  arc.expandedSize=size;
  mem.free(c.dstBuf);
  arcCtrlDel(c);
  return arc;
}

void arcExpandBuf(CArcCtrl *c)
{//Use $LK,"ExpandBuf",A="MN:ExpandBuf"$() unless you know what you're doing.
  u8 *dstPtr,*dstLimit;
  i64 basecode,lastcode,code;
  CArcEntry *tmp,*tmp1;

  dstPtr=c.dstBuf+c.dstPos;
  dstLimit=c.dstBuf+c.dstSize;

  while (dstPtr<dstLimit && c.stkPtr!=c.stkBase) {
    *dstPtr++ = * -- c.stkPtr;
  }

  if (c.stkPtr==c.stkBase && dstPtr<dstLimit) {
    if (c.savedBasecode==U32_MAX) {
      lastcode=bFieldExtU32(c.srcBuf,c.srcPos,
        c.nextBitsInUse);
      c.srcPos+=c.nextBitsInUse;
      *dstPtr++=lastcode;
      arcEntryGet(c);
      c.lastCh=lastcode;
    } else {
      lastcode=c.savedBasecode;
    }
    while (dstPtr<dstLimit && c.srcPos+c.nextBitsInUse<=c.srcSize) {
      basecode=bFieldExtU32(c.srcBuf,c.srcPos,
        c.nextBitsInUse);
      c.srcPos+=c.nextBitsInUse;
      if (c.curEntry==&c.compress[basecode]) {
    *c.stkPtr++=c.lastCh;
    code=lastcode;
      } else {
    code=basecode;
      }
      while (code>=c.minTableEntry) {
    *c.stkPtr++=c.compress[code].ch;
    code=c.compress[code].basecode;
      }
      *c.stkPtr++=code;
      c.lastCh=code;

      c.entryUsed=true;
      tmp=c.curEntry;
      tmp.basecode=lastcode;
      tmp.ch=c.lastCh;
      tmp1=(CArcEntry *)(&c.hash[lastcode]);
      tmp.next=tmp1.next;
      tmp1.next=tmp;

      arcEntryGet(c);
      while (dstPtr<dstLimit && c.stkPtr!=c.stkBase) {
    *dstPtr++ = * -- c.stkPtr;
      }
      lastcode=basecode;
    }
    c.savedBasecode=lastcode;
  }
  c.dstPos=dstPtr-c.dstBuf;
}

u8 *expandBuf(CArcCompress *arc)
{//See $LK,"::/Demo/Dsk/SerializeTree.ktm"$.
  CArcCtrl *c;
  u8 *res;

  if (!(CT_NONE<=arc.compressionType<=CT_8_BIT) || arc.expandedSize>memMappedSpace) {
    throw(Err.compress);
  }

  res=mem.alloc(arc.expandedSize+1);
  res[arc.expandedSize]=0; //terminate
  switch [arc.compressionType] {
    case CT_NONE:
      mem.copy(res,&arc.body,arc.expandedSize);
      break;
    case CT_7_BIT:
    case CT_8_BIT:
      c=arcCtrlNew(true,arc.compressionType);
      c.srcSize=arc.compressedSize<<3;
      c.srcPos=sizeof(CArcCompress)<<3;
      c.srcBuf=(u8 *)arc;
      c.dstSize=arc.expandedSize;
      c.dstBuf=res;
      c.dstPos=0;
      arcExpandBuf(c);
      arcCtrlDel(c);
      break;
  }
  return res;
}

bool roundTrip(u8 *src, i64 n) {
  CArcCompress *arc = compressBuf(src, n);
  u8 *out = expandBuf(arc);
  bool ok = arc.expandedSize == n && !str.cmpLen(out, src, n);
  mem.free(out);
  mem.free(arc);
  return ok;
}

bool shortBufferRoundTrip() {
  /* Short input falls through the CT_NONE path (compressed bits
   * wouldn't fit), exercising the &arc->body trailing-buffer trick. */
  return roundTrip("hello", 5);
}

bool repeatedBufferRoundTrip() {
  /* Highly repetitive input exercises the CT_7_BIT/CT_8_BIT LZW path. */
  u8 *src = "the quick brown fox jumps over the lazy dog. "
            "the quick brown fox jumps over the lazy dog. "
            "the quick brown fox jumps over the lazy dog. "
            "the quick brown fox jumps over the lazy dog.";
  return roundTrip(src, str.len(src));
}

i32 main() {
  printf("Test - TempleOS LZW compress/decompress: ");
  memMappedSpace = 1 << 20;
  i64 correct = 0, tests = 2;
  if (shortBufferRoundTrip()) { correct++; }
  if (repeatedBufferRoundTrip()) { correct++; }
  test.printResult(correct, tests);
  printf("====\n");
  return 0;
}
