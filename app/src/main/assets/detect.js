// VideoRef detect.js v0.3.0 - 문서 시작 때 모든 프레임(모든 출처)에 주입된다.
// 1) 프레임마다: 처음 인사(hello), 로드 끝나면 / 앱이 물으면 '이미 불러온 주소 목록' + video 태그 주소를 앱으로 보낸다.
// 2) v0.2.0: 화면에 보이는 영상(가로 120px 이상) 오른쪽 위에 '받기' 버튼을 띄운다(그림자 DOM — 사이트 CSS 와 안 섞임).
//    누르면 이 프레임의 목록을 먼저 보내고(grab 직전 scan) grab 을 보낸다 -> 앱이 이 프레임의 가장 좋은 후보를 받는다.
//    상시 타이머 없음: 스크롤·크기 변경·재생 이벤트·새 요소 추가 때만 위치를 다시 잡는다.
(function () {
  if (window.__vrDetect) return;
  window.__vrDetect = 1;
  try { performance.setResourceTimingBufferSize(5000); } catch (e) {}

  function send(obj) {
    obj.href = location.href;
    obj.top = (window === window.top);
    try { VRBridge.postMessage(JSON.stringify(obj)); } catch (e) {}
  }

  function collect(reason) {
    var res = [];
    try { performance.getEntriesByType('resource').forEach(function (e) { res.push(e.name); }); } catch (e) {}
    var vids = [];
    try {
      document.querySelectorAll('video,audio,source').forEach(function (v) {
        var s = v.currentSrc || v.src;
        if (s) vids.push(s);
      });
    } catch (e) {}
    send({ t: 'scan', reason: reason, title: document.title, res: res, vids: vids });
  }

  // ---- 영상 위 받기 버튼 ----
  var host = null, root = null, items = [];   // items: {v: video, b: button}
  function ensureRoot() {
    if (host || !document.body) return !!host;
    // v0.6.0: 플레이판(window.__vrL.noyt)은 유튜브 페이지에 버튼을 안 띄운다
    if ((window.__vrL || {}).noyt && /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|youtubekids\.com)$/.test(location.hostname)) return false;
    host = document.createElement('div');
    host.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;';
    root = host.attachShadow ? host.attachShadow({ mode: 'closed' }) : host;
    var st = document.createElement('style');
    st.textContent = 'button{position:fixed;display:none;align-items:center;gap:4px;padding:6px 12px;border:0;border-radius:18px;' +
      'background:rgba(63,81,181,.92);color:#fff;font:600 13px/1 sans-serif;box-shadow:0 2px 6px rgba(0,0,0,.35);pointer-events:auto;}' +
      'button:active{background:rgba(40,53,147,.95)}';
    root.appendChild(st);
    document.documentElement.appendChild(host);
    return true;
  }
  function place() {
    for (var i = 0; i < items.length; i++) {
      var it = items[i], r = it.v.getBoundingClientRect();
      var ok = it.v.isConnected && r.width >= 120 && r.height >= 68 && r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
      it.b.style.display = ok ? 'flex' : 'none';
      if (ok) { it.b.style.top = Math.max(r.top + 8, 4) + 'px'; var bw = (it.b.offsetWidth || 84) + 8; it.b.style.left = Math.max(Math.min(r.right - bw, innerWidth - bw), 4) + 'px'; }   // v0.5.12: 실제 버튼 폭 기준(긴 번역이 영상 밖으로 나갔음)
    }
  }
  var queued = false;
  function schedule() { if (queued) return; queued = true; requestAnimationFrame(function () { queued = false; place(); }); }
  function attach(v) {
    if (v.__vrBtn || !ensureRoot()) return;
    var b = document.createElement('button');
    // v0.3.0: 앞에 VideoRef 아이콘(앱 아이콘과 같은 모양: 둥근 남색 바탕 + 재생 삼각형 + 아래 화살표) + '받기'
    // v0.5.0: 앱 언어로(앱이 window.__vrL 을 먼저 넣어 줌). 없으면 한국어
    var L = window.__vrL || {};
    var LBL = L.get || '받기', OK = L.ok || '보냄';    // 받기, 보냄
    // innerHTML 은 보안 정책(Trusted Types)이 있는 사이트에서 막히므로 요소를 직접 만든다
    var NS = 'http://www.w3.org/2000/svg';
    function icon() {
      var s = document.createElementNS(NS, 'svg');
      s.setAttribute('width', '20'); s.setAttribute('height', '20'); s.setAttribute('viewBox', '0 0 108 108'); s.style.flex = 'none';
      var c = document.createElementNS(NS, 'circle'); c.setAttribute('cx', '54'); c.setAttribute('cy', '54'); c.setAttribute('r', '54'); c.setAttribute('fill', '#fff'); s.appendChild(c);
      ['M38,24 L38,58 L64,41 Z', 'M50,60 L58,60 L58,70 L68,70 L54,86 L40,70 L50,70 Z'].forEach(function (d) {
        var p = document.createElementNS(NS, 'path'); p.setAttribute('d', d); p.setAttribute('fill', '#3F51B5'); s.appendChild(p);
      });
      return s;
    }
    function label(t) {
      while (b.firstChild) b.removeChild(b.firstChild);
      b.appendChild(icon());
      var sp = document.createElement('span'); sp.textContent = t; b.appendChild(sp);
    }
    label(LBL);
    b.setAttribute('aria-label', '\u2B07 ' + LBL);   // v0.5.12: 화면 낭독 설명도 앱 언어로(전엔 한국어 고정)
    b.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation();
      collect('grab');
      send({ t: 'grab', src: v.currentSrc || v.src || '', title: document.title, w: v.videoWidth || 0, h: v.videoHeight || 0 });
      label(OK);
      setTimeout(function () { label(LBL); }, 2000);
    }, true);
    root.appendChild(b);
    v.__vrBtn = b;
    items.push({ v: v, b: b });
    ['play', 'loadedmetadata', 'resize'].forEach(function (ev) { v.addEventListener(ev, schedule); });
    schedule();
  }
  function scanVideos() { try { document.querySelectorAll('video').forEach(attach); } catch (e) {} }

  addEventListener('scroll', schedule, { passive: true, capture: true });
  addEventListener('resize', schedule);
  document.addEventListener('DOMContentLoaded', function () {
    scanVideos();
    var mq = false;   // 요소가 자주 바뀌는 사이트 대비: 0.5초에 한 번만 다시 찾기
    try { new MutationObserver(function () { if (mq) return; mq = true; setTimeout(function () { mq = false; scanVideos(); schedule(); }, 500); }).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  });

  VRBridge.onmessage = function (ev) {
    if (ev.data === 'scan') collect('asked');
    else if (ev.data === 'grab') {                    // 시험용: 이 프레임 첫 버튼 누르기와 같은 동작
      scanVideos(); if (items.length) items[0].b.click();
    }
  };
  send({ t: 'hello', title: document.title });
  addEventListener('load', function () { scanVideos(); schedule(); collect('load'); });
})();
