# just downloader 16KB 메모리 페이지 대응 검사 v0.1.0
# APK/AAB 안의 모든 .so 파일(ELF)의 LOAD 구역 정렬(p_align)을 읽어 16KB(0x4000) 이상인지 본다.
# 구글 플레이: 안드로이드 15+ 대상 앱은 64비트 so 가 16KB 정렬이어야 함.
# 사용: python -I _check16k.py <apk 또는 aab> [...]   결과는 이 폴더 _check16k.log 에도 남김. 하나라도 미달이면 종료 코드 1
import sys, os, zipfile, struct
here = os.path.dirname(os.path.abspath(__file__))
out, bad = [], 0
def elf_align(b):
    if b[:4] != b'\x7fELF': return None, 'not-elf'
    is64 = b[4] == 2; le = b[5] == 1; e = '<' if le else '>'
    if is64:
        phoff, = struct.unpack_from(e + 'Q', b, 0x20); phentsize, phnum = struct.unpack_from(e + 'HH', b, 0x36)
    else:
        phoff, = struct.unpack_from(e + 'I', b, 0x1C); phentsize, phnum = struct.unpack_from(e + 'HH', b, 0x2A)
    al = []
    for i in range(phnum):
        o = phoff + i * phentsize
        typ, = struct.unpack_from(e + 'I', b, o)
        if typ != 1: continue  # PT_LOAD
        a, = struct.unpack_from(e + 'Q', b, o + 0x30) if is64 else struct.unpack_from(e + 'I', b, o + 0x1C)
        al.append(a)
    return (min(al) if al else 0), ('64' if is64 else '32')
for path in sys.argv[1:]:
    out.append('== ' + path)
    with zipfile.ZipFile(path) as z:
        for n in sorted(z.namelist()):
            if not n.endswith('.so'): continue
            info = z.getinfo(n)
            a, bits = elf_align(z.read(n))
            ok = a is not None and a >= 0x4000
            if not ok and bits == '64': bad += 1
            out.append('%-4s %-6s align=%-7s zipcomp=%s %s' % ('OK' if ok else ('BAD' if bits == '64' else 'skip'), bits, hex(a) if a is not None else '-', info.compress_type, n))
out.append('RESULT: ' + ('OK' if bad == 0 else 'BAD %d (64bit so under 16KB)' % bad))
open(os.path.join(here, '_check16k.log'), 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('\n'.join(out)); sys.exit(1 if bad else 0)
