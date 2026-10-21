# just downloader(VideoRef 모바일) 번역 점검 v0.1.0
# 코틀린 소스의 화면 문구(T("…"), T(if … "a" else "b"), 실패 단계 이름, 변수로 넘어가는 enum 이름)를 모아
# assets/lang/<코드>.json 9개와 비교: 빠진 키 / 남는 키 / 자리표시({0}) 불일치 / 줄바꿈 수 불일치 / 한글이 남은 번역.
# 사용: python -I _i18n_check.py   (이 파일 위치 기준 경로) — 문제 있으면 종료 코드 1, 결과는 _i18n_check.log 에도 남김
import re, json, glob, os, sys
here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, 'app', 'src', 'main', 'java', 'com', 'videoref', 'app')
langd = os.path.join(here, 'app', 'src', 'main', 'assets', 'lang')
lit = r'"((?:[^"\\$]|\\.|\$(?!\{))*)"'
keys = []
def add(k):
    if k not in keys: keys.append(k)
for f in sorted(glob.glob(os.path.join(src, '*.kt'))):
    t = open(f, encoding='utf-8').read()
    for m in re.finditer(r'\bT\(\s*' + lit, t): add(m.group(1))
    for m in re.finditer(r'\bT\(\s*if\s*\([^)]*\)\s*' + lit + r'\s*else\s*' + lit, t): add(m.group(1)); add(m.group(2))
    for m in re.finditer(r'FailureDiagnosis\(\s*' + lit, t): add(m.group(1))
    for m in re.finditer(r'enum class (JobState|Kind|Filter)\b[^{]*\{([^}]*)\}', t):
        for k in re.findall(r'\w+\("([^"]+)"', m.group(2)): add(k)
keys = [k.replace('\\n', '\n').replace('\\"', '"') for k in keys if re.search('[가-힣]', k)]
ph = lambda s: sorted(re.findall(r'\{\d+\}', s))
out = ['keys in source: %d' % len(keys)]
bad = 0
for code in ['en', 'zh_CN', 'zh_TW', 'ja', 'es', 'de', 'fr', 'pt_BR', 'ru']:
    p = os.path.join(langd, code + '.json')
    if not os.path.exists(p): out.append('%-6s MISSING FILE' % code); bad += 1; continue
    d = json.load(open(p, encoding='utf-8'))
    miss = [k for k in keys if k not in d]
    extra = [k for k in d if not k.startswith('_') and k not in keys]
    phbad = [k for k in keys if k in d and ph(k) != ph(d[k])]
    nlbad = [k for k in keys if k in d and k.count('\n') != d[k].count('\n')]
    hangul = [k for k in keys if k in d and re.search('[가-힣]', d[k])]
    out.append('%-6s keys=%d missing=%d extra=%d placeholder=%d newline=%d hangul_left=%d' % (code, len(d) - 1, len(miss), len(extra), len(phbad), len(nlbad), len(hangul)))
    for k in miss[:20]: out.append('   missing: ' + k.replace('\n', '\\n'))
    for k in phbad[:20]: out.append('   placeholder: ' + k)
    for k in nlbad[:20]: out.append('   newline: ' + k.replace('\n', '\\n'))
    for k in hangul[:20]: out.append('   hangul: ' + k + ' => ' + d[k])
    for k in extra[:20]: out.append('   extra(unused): ' + k.replace('\n', '\\n'))
    bad += len(miss) + len(phbad) + len(nlbad) + len(hangul)
out.append('RESULT: ' + ('OK' if bad == 0 else 'PROBLEMS %d' % bad))
open(os.path.join(here, '_i18n_check.log'), 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('\n'.join(out))
sys.exit(1 if bad else 0)