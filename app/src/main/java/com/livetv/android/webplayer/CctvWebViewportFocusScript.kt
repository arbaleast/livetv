package com.livetv.android.webplayer

/**
 * 正向视口聚焦脚本（**静态常量**，不拼接任何外部输入）。
 *
 * 目标：整页加载官方直播页后，把 `#player` 铺满视口、隐藏其**兄弟节点**（页头/横幅/节目单），
 * 不动 `#player` 本身、不搬移任何 DOM、不模拟点击、不修改播放状态。
 *
 * 幂等：脚本内部用 `__livetvFocusInstalled` 防重复注入；style 用固定 id 防重复插入。
 */
object CctvWebViewportFocusScript {

    val FOCUS_SCRIPT: String = """
(function () {
  if (window.__livetvFocusInstalled) { return; }
  window.__livetvFocusInstalled = true;

  var STYLE_ID = 'livetv-focus-style';
  var HIDE_CLASS = 'livetv-focus-hidden';
  var PLAYER_ID = 'player';
  var MIN_W = 200;
  var MIN_H = 100;
  var busy = false;

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) { return; }
    var css =
      'html,body{background:#000 !important;margin:0 !important;padding:0 !important;' +
      'overflow:hidden !important;width:100vw !important;height:100vh !important;}' +
      '.' + HIDE_CLASS + '{display:none !important;visibility:hidden !important;}' +
      '#player{position:fixed !important;left:0 !important;top:0 !important;right:0 !important;bottom:0 !important;' +
      'width:100vw !important;height:100vh !important;max-width:100vw !important;max-height:100vh !important;' +
      'margin:0 !important;padding:0 !important;z-index:2147483647 !important;background:#000 !important;}' +
      '#player video,#player canvas{max-width:100% !important;max-height:100% !important;' +
      'object-fit:contain !important;background:#000 !important;}';
    var head = document.head || document.documentElement;
    head.insertAdjacentHTML('beforeend', '<style id="' + STYLE_ID + '">' + css + '</style>');
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

  function mediaReady(player) {
    var media = player.querySelectorAll('video,canvas');
    for (var i = 0; i < media.length; i++) {
      if (visibleBox(media[i])) { return true; }
      if (media[i].tagName === 'CANVAS' && media[i].width > MIN_W && media[i].height > MIN_H) { return true; }
    }
    return false;
  }

  function hideSiblings(node) {
    var parent = node.parentElement;
    var child = node;
    while (parent && parent !== document.body) {
      var kids = parent.children;
      for (var i = 0; i < kids.length; i++) {
        if (kids[i] !== child && kids[i].id !== STYLE_ID) {
          kids[i].classList.add(HIDE_CLASS);
        }
      }
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

  function focusPlayer() {
    ensureStyle();
    var player = document.getElementById(PLAYER_ID);
    if (!player) { return; }
    hideSiblings(player);
    settle(player);
    player.style.setProperty('position', 'fixed', 'important');
    player.style.setProperty('left', '0', 'important');
    player.style.setProperty('top', '0', 'important');
    player.style.setProperty('width', '100vw', 'important');
    player.style.setProperty('height', '100vh', 'important');
    player.style.setProperty('background', '#000', 'important');
    if (!window.__livetvFocusReady && mediaReady(player)) {
      window.__livetvFocusReady = true;
    }
  }

  function run() {
    if (busy) { return; }
    busy = true;
    try { focusPlayer(); } catch (e) { } finally { busy = false; }
  }

  run();
  document.addEventListener('DOMContentLoaded', run);
  window.addEventListener('load', run);

  if (window.MutationObserver) {
    var observer = new MutationObserver(function () { run(); });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  var ticks = 0;
  var timer = setInterval(function () {
    ticks += 1;
    run();
    if (window.__livetvFocusReady || ticks > 600) { clearInterval(timer); }
  }, 250);
})();
""".trimIndent()

    /** 轮询就绪标记：只读布尔表达式，不含任何输入拼接。 */
    const val CHECK_READY_SCRIPT: String = "Boolean(window.__livetvFocusReady)"
}
