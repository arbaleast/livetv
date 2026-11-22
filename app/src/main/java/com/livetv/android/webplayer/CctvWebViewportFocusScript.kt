package com.livetv.android.webplayer

/**
 * 正向视口聚焦脚本（**静态常量**，不拼接任何外部输入）。
 *
 * 目标：
 * 1. **阶段一（视口聚焦）**：整页加载官方直播页后，把 `#player` 固定为 `100vw x 100vh` 黑底铺满视口，
 *    清除祖先 transform/overflow，仅隐藏 `#player` 外围兄弟节点（页头/横幅/节目单），保持 `#player video/canvas`
 *    的 `object-fit:contain !important`；
 * 2. **阶段二（纯视频）**：通过 `playing`/`timeupdate` 与播放态检查检测起播后置 `window.__livetvPlaying = true`，
 *    从 `#player` 内可见 `video`/`canvas` 向上遍历至 `#player` 边界，对非媒体祖先路径上的控制层兄弟节点添加
 *    `.livetv-pure-control-hidden`，由 `html.livetv-pure-video #player .livetv-pure-control-hidden`
 *    施加 `opacity:0 !important;pointer-events:none !important;`（**严禁**对内部控件用 `display:none`，
 *    避免破坏官方脚本的 `offsetWidth` 布局计算）；
 * 3. **性能守卫**：`MutationObserver` 280ms 节流（`setTimeout`），就绪后 `disconnect()` 并收缩观察范围到
 *    `document.body`（`subtree: false`）；轮询定时器在检测到播放或超 600 次后 `clearInterval`。
 *
 * 严禁：搬移 DOM、模拟点击、强改播放状态、引用任何外部 URL。
 */
object CctvWebViewportFocusScript {

    val FOCUS_SCRIPT: String = """
(function () {
  if (window.__livetvFocusInstalled) { return; }
  window.__livetvFocusInstalled = true;

  var STYLE_ID = 'livetv-focus-style';
  var HIDE_CLASS = 'livetv-focus-hidden';
  var CONTROL_CLASS = 'livetv-pure-control-hidden';
  var PURE_CLASS = 'livetv-pure-video';
  var PLAYER_IDS = ['player', 'myPlayer'];
  var MIN_W = 200;
  var MIN_H = 100;
  var THROTTLE_MS = 280;
  var busy = false;
  var timer = 0;
  var observer = null;
  var narrowed = false;
  var pureScanCount = 0;
  var pruned = false;
  var pruneTimer = 0;

  function findPlayerContainer() {
    for (var i = 0; i < PLAYER_IDS.length; i++) {
      var el = document.getElementById(PLAYER_IDS[i]);
      if (el) { return el; }
    }
    return null;
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) { return true; }
    var css =
      'html,body{background:#000 !important;margin:0 !important;padding:0 !important;' +
      'overflow:hidden !important;width:100vw !important;height:100vh !important;}\n' +
      '.' + HIDE_CLASS + '{display:none !important;visibility:hidden !important;}\n' +
      '#player,#myPlayer{position:fixed !important;left:0 !important;top:0 !important;right:0 !important;bottom:0 !important;' +
      'width:100vw !important;height:100vh !important;max-width:100vw !important;max-height:100vh !important;' +
      'margin:0 !important;padding:0 !important;z-index:2147483647 !important;background:#000 !important;}\n' +
      '#player video,#player canvas,#myPlayer video,#myPlayer canvas{width:100% !important;height:100% !important;' +
      'max-width:100% !important;max-height:100% !important;object-fit:contain !important;background:#000 !important;}\n' +
      'html.livetv-pure-video #player .livetv-pure-control-hidden,html.livetv-pure-video #myPlayer .livetv-pure-control-hidden,html.livetv-pure-video #myPlayer > :not(video):not(canvas){visibility:hidden !important;opacity:0 !important;pointer-events:none !important;}\n' +
      'html.livetv-pure-video #player video::-webkit-media-controls,html.livetv-pure-video #player video::-webkit-media-controls-enclosure,html.livetv-pure-video #myPlayer video::-webkit-media-controls,html.livetv-pure-video #myPlayer video::-webkit-media-controls-enclosure{display:none !important;opacity:0 !important;visibility:hidden !important;pointer-events:none !important;}';
    var head = document.head || document.documentElement;
    if (head) {
      try {
        head.insertAdjacentHTML('beforeend', '<style id="' + STYLE_ID + '">' + css + '</style>');
      } catch (e) { }
    }
    return Boolean(document.getElementById(STYLE_ID));
  }

  function applyBaseStyles() {
    var root = document.documentElement;
    var body = document.body;
    if (root) {
      root.style.setProperty('background', '#000', 'important');
      root.style.setProperty('margin', '0', 'important');
      root.style.setProperty('padding', '0', 'important');
      root.style.setProperty('overflow', 'hidden', 'important');
      root.style.setProperty('width', '100vw', 'important');
      root.style.setProperty('height', '100vh', 'important');
    }
    if (body) {
      body.style.setProperty('background', '#000', 'important');
      body.style.setProperty('margin', '0', 'important');
      body.style.setProperty('padding', '0', 'important');
      body.style.setProperty('overflow', 'hidden', 'important');
      body.style.setProperty('width', '100vw', 'important');
      body.style.setProperty('height', '100vh', 'important');
    }
  }

  function visibleBox(el) {
    if (!el) { return null; }
    var rect = el.getBoundingClientRect();
    if (rect.width < MIN_W || rect.height < MIN_H) { return null; }
    var style = window.getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') { return null; }
    if (Number(style.opacity) < 0.05) { return null; }
    return rect;
  }

  function isVideoPlaying(v) {
    return Boolean(v && !v.paused && v.readyState >= 2 && v.videoWidth > 0 && v.currentTime > 0.05);
  }

  function findActiveMedia(player) {
    var media = player.querySelectorAll('video,canvas');
    for (var i = 0; i < media.length; i++) {
      var el = media[i];
      if (visibleBox(el)) { return el; }
      if (el.tagName === 'CANVAS' && el.width > MIN_W && el.height > MIN_H) { return el; }
    }
    return media.length > 0 ? media[0] : null;
  }

  function markPureControls(player) {
    var media = findActiveMedia(player);
    if (!media) { return; }
    var hasStyle = Boolean(document.getElementById(STYLE_ID));
    var pureActive = Boolean(window.__livetvPlaying && window.__livetvPureVideo !== false);
    var child = media;
    var parent = media.parentElement;
    while (parent) {
      var kids = parent.children;
      for (var i = 0; i < kids.length; i++) {
        if (kids[i] !== child && kids[i].id !== STYLE_ID) {
          kids[i].classList.add(CONTROL_CLASS);
          if (!hasStyle && pureActive) {
            kids[i].style.setProperty('visibility', 'hidden', 'important');
            kids[i].style.setProperty('opacity', '0', 'important');
            kids[i].style.setProperty('pointer-events', 'none', 'important');
          }
        }
      }
      if (parent === player) { break; }
      child = parent;
      parent = parent.parentElement;
    }
  }

  window.__livetvScanPureControls = function () {
    var player = findPlayerContainer();
    if (player) { markPureControls(player); }
  };

  function pruneOuterDom() {
    if (pruned || !window.__livetvPlaying || window.__livetvPureVideo === false) { return; }
    var player = findPlayerContainer();
    if (!player) { return; }
    pruned = true;
    try {
      var child = player;
      var parent = player.parentElement;
      while (parent && parent !== document.documentElement) {
        var kids = parent.children;
        for (var i = kids.length - 1; i >= 0; i--) {
          var sibling = kids[i];
          if (sibling !== child && sibling.id !== STYLE_ID) {
            sibling.remove();
          }
        }
        if (parent === document.body) { break; }
        child = parent;
        parent = parent.parentElement;
      }
      var links = document.querySelectorAll('link[rel="stylesheet"]');
      for (var j = links.length - 1; j >= 0; j--) {
        if (!player.contains(links[j]) && links[j].id !== STYLE_ID) {
          links[j].remove();
        }
      }
      var frames = document.querySelectorAll('iframe');
      for (var k = frames.length - 1; k >= 0; k--) {
        if (!player.contains(frames[k])) {
          frames[k].remove();
        }
      }
    } catch (e) { }
    if (observer) {
      observer.disconnect();
      observer = null;
    }
  }

  function schedulePrune() {
    if (pruned || pruneTimer || !window.__livetvPlaying) { return; }
    pruneTimer = setTimeout(function () {
      pruneTimer = 0;
      pruneOuterDom();
    }, 2000);
  }

  function applyPureState(player) {
    markPureControls(player);
    if (window.__livetvPlaying && window.__livetvPureVideo !== false && document.documentElement) {
      document.documentElement.classList.add(PURE_CLASS);
      schedulePrune();
    }
  }

  function bindVideoEvents(player) {
    var videos = player.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
      var v = videos[i];
      if (v.__livetvBound) { continue; }
      v.__livetvBound = true;
      (function (videoEl) {
        function markPlaying() {
          var firstPlaying = !window.__livetvPlaying;
          window.__livetvEnded = false;
          window.__livetvFocusReady = true;
          window.__livetvPlaying = true;
          if (firstPlaying || pureScanCount < 3) {
            pureScanCount += 1;
            var p = findPlayerContainer();
            if (p) { applyPureState(p); }
          }
        }
        function onPlaying() {
          if (!isVideoPlaying(videoEl)) { return; }
          markPlaying();
        }
        function onProgress() {
          if (!isVideoPlaying(videoEl)) { return; }
          markPlaying();
        }
        function onEnded() {
          window.__livetvEnded = true;
        }
        videoEl.addEventListener('playing', onPlaying);
        videoEl.addEventListener('timeupdate', onProgress);
        videoEl.addEventListener('ended', onEnded);
        if (typeof videoEl.requestVideoFrameCallback === 'function') {
          videoEl.requestVideoFrameCallback(function () {
            if (videoEl && !videoEl.paused && videoEl.videoWidth > 0) {
              markPlaying();
            }
          });
        }
      })(v);
    }
  }

  function checkMediaState(player) {
    var media = player.querySelectorAll('video,canvas');
    var ready = false;
    for (var i = 0; i < media.length; i++) {
      var el = media[i];
      if (el.tagName === 'VIDEO') {
        if (isVideoPlaying(el)) { return 'playing'; }
        if (visibleBox(el)) { ready = true; }
      } else if (el.tagName === 'CANVAS') {
        if (el.width > MIN_W && el.height > MIN_H) { return 'playing'; }
        if (visibleBox(el)) { ready = true; }
      }
    }
    return ready ? 'ready' : 'loading';
  }

  function hideSiblings(node, hasStyle) {
    var parent = node.parentElement;
    var child = node;
    while (parent && parent !== document.documentElement) {
      var kids = parent.children;
      for (var i = 0; i < kids.length; i++) {
        if (kids[i] !== child && kids[i].id !== STYLE_ID) {
          kids[i].classList.add(HIDE_CLASS);
          if (!hasStyle) {
            kids[i].style.setProperty('display', 'none', 'important');
            kids[i].style.setProperty('visibility', 'hidden', 'important');
          }
        }
      }
      if (parent === document.body) { break; }
      child = parent;
      parent = parent.parentElement;
    }
  }

  function settle(node) {
    var el = node;
    while (el && el !== document.documentElement) {
      var s = el.style;
      s.transform = 'none';
      s.filter = 'none';
      s.perspective = 'none';
      s.contain = 'none';
      s.willChange = 'auto';
      s.overflow = 'visible';
      el = el.parentElement;
    }
  }

  function focusPlayer(player, hasStyle) {
    hideSiblings(player, hasStyle);
    settle(player);
    player.style.setProperty('position', 'fixed', 'important');
    player.style.setProperty('left', '0', 'important');
    player.style.setProperty('top', '0', 'important');
    player.style.setProperty('right', '0', 'important');
    player.style.setProperty('bottom', '0', 'important');
    player.style.setProperty('width', '100vw', 'important');
    player.style.setProperty('height', '100vh', 'important');
    player.style.setProperty('margin', '0', 'important');
    player.style.setProperty('padding', '0', 'important');
    player.style.setProperty('z-index', '2147483647', 'important');
    player.style.setProperty('background', '#000', 'important');

    var media = player.querySelectorAll('video,canvas');
    for (var i = 0; i < media.length; i++) {
      media[i].style.setProperty('width', '100%', 'important');
      media[i].style.setProperty('height', '100%', 'important');
      media[i].style.setProperty('max-width', '100%', 'important');
      media[i].style.setProperty('max-height', '100%', 'important');
      media[i].style.setProperty('object-fit', 'contain', 'important');
      media[i].style.setProperty('background', '#000', 'important');
    }
  }

  function ensureObserver() {
    if (observer || !window.MutationObserver || !document.documentElement) { return; }
    try {
      observer = new MutationObserver(schedule);
      observer.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {
      observer = null;
    }
  }

  function run() {
    ensureObserver();
    var hasStyle = ensureStyle();
    applyBaseStyles();
    var player = findPlayerContainer();
    if (!player) { return; }

    bindVideoEvents(player);
    var state = checkMediaState(player);
    if (state !== 'loading') {
      window.__livetvFocusReady = true;
      if (state === 'playing') { window.__livetvPlaying = true; }
    }

    try {
      focusPlayer(player, hasStyle);
      if (window.__livetvPlaying) {
        applyPureState(player);
      }
    } catch (e) { }

    if (!narrowed && window.__livetvFocusReady && document.body && observer) {
      narrowed = true;
      observer.disconnect();
      observer.observe(document.body, { childList: true, subtree: false });
    }
  }

  function schedule() {
    if (busy) { return; }
    busy = true;
    clearTimeout(timer);
    timer = setTimeout(function () {
      busy = false;
      run();
    }, THROTTLE_MS);
  }

  run();
  document.addEventListener('DOMContentLoaded', schedule);
  window.addEventListener('load', schedule);
  ensureObserver();

  var ticks = 0;
  var poll = setInterval(function () {
    ticks += 1;
    schedule();
    if (window.__livetvPlaying || ticks > 600) { clearInterval(poll); }
  }, THROTTLE_MS);
})();
""".trimIndent()

    /** 阶段一就绪探针：只读布尔表达式。 */
    const val CHECK_READY_SCRIPT: String = "Boolean(window.__livetvFocusReady)"

    /** 两阶段状态探针（含点播播完态）：返回 `'ended'` / `'playing'` / `'ready'` / `'loading'`。 */
    const val CHECK_STATE_SCRIPT: String =
        "(window.__livetvEnded?'ended':(window.__livetvPlaying?'playing':(window.__livetvFocusReady?'ready':'loading')))"

    /** 进入纯视频：重扫 `#player` 内控制层并在已起播时开启 `html.livetv-pure-video`。 */
    val SET_PURE_VIDEO_ON_SCRIPT: String = """
(function () {
  window.__livetvPureVideo = true;
  if (typeof window.__livetvScanPureControls === 'function') {
    window.__livetvScanPureControls();
  }
  if (window.__livetvPlaying && document.documentElement) {
    document.documentElement.classList.add('livetv-pure-video');
    if (!document.getElementById('livetv-focus-style')) {
      var nodes = document.querySelectorAll('.livetv-pure-control-hidden');
      for (var i = 0; i < nodes.length; i++) {
        nodes[i].style.setProperty('visibility', 'hidden', 'important');
        nodes[i].style.setProperty('opacity', '0', 'important');
        nodes[i].style.setProperty('pointer-events', 'none', 'important');
      }
    }
  }
})();
""".trimIndent()

    /** 退出纯视频（原控）：移除 `html.livetv-pure-video`，立即恢复官方网页控制层。 */
    val SET_PURE_VIDEO_OFF_SCRIPT: String = """
(function () {
  window.__livetvPureVideo = false;
  if (document.documentElement) {
    document.documentElement.classList.remove('livetv-pure-video');
  }
  if (!document.getElementById('livetv-focus-style')) {
    var nodes = document.querySelectorAll('.livetv-pure-control-hidden');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].style.removeProperty('visibility');
      nodes[i].style.removeProperty('opacity');
      nodes[i].style.removeProperty('pointer-events');
    }
  }
})();
""".trimIndent()
}
