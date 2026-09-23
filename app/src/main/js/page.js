/*
 * BrowserLite page helpers (ES5). Runs at the top of <head> on every page, after the polyfills.
 * Exposes window.__bl for the app: paging, sticky-bar removal, low-memory hooks.
 */
(function (W, D) {
  'use strict';
  if (W.__bl) return;
  var cfg = W.__blcfg || {};
  var bridge = W.__blBridge;
  var bl = W.__bl = {v: 1};

  function call(method) {
    var args = Array.prototype.slice.call(arguments, 1);
    try {
      if (bridge && typeof bridge[method] === 'function') return bridge[method].apply(bridge, args);
    } catch (e) { /* bridge gone */ }
    return undefined;
  }
  function ready(fn) {
    if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', fn, false);
    else setTimeout(fn, 0);
  }
  function each(list, fn) {
    for (var i = 0; i < list.length; i++) fn(list[i], i);
  }

  /* ------------------------------------------------------------ form POST: let the WebView send it */
  function notifyPost(form) {
    var m = (form.getAttribute('method') || form.method || '').toLowerCase();
    if (m !== 'post') return;
    var action = form.action || location.href;
    call('formPost', String(action));
  }
  D.addEventListener('submit', function (e) {
    if (e.target && e.target.tagName === 'FORM') notifyPost(e.target);
  }, true);
  if (W.HTMLFormElement) {
    var nativeSubmit = HTMLFormElement.prototype.submit;
    HTMLFormElement.prototype.submit = function () {
      notifyPost(this);
      return nativeSubmit.apply(this, arguments);
    };
  }

  /* ------------------------------------------------------------ popups become tabs */
  var lastGesture = 0;
  each(['click', 'touchend', 'keydown'], function (t) {
    D.addEventListener(t, function () { lastGesture = Date.now(); }, true);
  });
  var nativeOpen = W.open;
  W.open = function (url, name, features) {
    if (!url || url === 'about:blank') {
      return nativeOpen ? nativeOpen.apply(W, arguments) : null;
    }
    var abs;
    try { abs = new W.URL(String(url), location.href).href; } catch (e) { abs = String(url); }
    if (Date.now() - lastGesture > 1500) return null; // popup without a tap: blocked
    if (name === '_self' || name === '_top' || name === '_parent') {
      location.href = abs;
      return W;
    }
    call('openTab', abs);
    return {closed: false, close: function () { this.closed = true; }, focus: function () {}, blur: function () {},
      postMessage: function () {}, location: {href: abs}, document: null};
  };

  /* ------------------------------------------------------------ images: deferred lazy, srcset fallback */
  var viewportW = function () { return W.innerWidth || D.documentElement.clientWidth || 360; };
  var viewportH = function () { return W.innerHeight || D.documentElement.clientHeight || 640; };
  var dpr = W.devicePixelRatio || 1;
  var maxPx = Math.min(cfg.maxImg || 1600, viewportW() * dpr * 1.25);

  function pickSrcset(set) {
    if (!set) return null;
    var best = null, bestW = 0, fallback = null;
    var parts = set.split(/,\s+(?=[^,\s])/);
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i].replace(/^\s+|\s+$/g, '').split(/\s+/);
      var url = p[0];
      if (!url) continue;
      var d = p[1] || '1x';
      var w = /w$/.test(d) ? parseFloat(d) : /x$/.test(d) ? parseFloat(d) * viewportW() : viewportW();
      if (!fallback) fallback = url;
      if (w >= maxPx * 0.8) {
        if (!best || w < bestW || bestW < maxPx * 0.8) { best = url; bestW = w; }
      } else if (!best || (bestW < maxPx * 0.8 && w > bestW)) {
        best = url;
        bestW = w;
      }
    }
    return best || fallback;
  }
  function isPlaceholder(src) {
    return !src || /^data:image\/(gif|svg|png);base64,.{0,200}$/.test(src) || /(^|\/)(blank|spacer|pixel|placeholder|lazy)[^\/]*\.(gif|png|svg)/i.test(src);
  }
  var LAZY_ATTRS = ['data-src', 'data-lazy-src', 'data-original', 'data-lazy', 'data-url', 'data-hi-res-src', 'data-actualsrc'];
  function supportedType(t) {
    return !t || /^image\/(jpe?g|png|gif|webp|svg\+xml|bmp)$/i.test(t);
  }
  function fixImage(img) {
    if (img.__blFixed) return;
    var src = img.getAttribute('src');
    if (!isPlaceholder(src)) { img.__blFixed = 1; return; }
    var cand = null;
    var pic = img.parentNode && img.parentNode.tagName === 'PICTURE' ? img.parentNode : null;
    if (pic) {
      var sources = pic.getElementsByTagName('source');
      for (var i = 0; i < sources.length && !cand; i++) {
        var s = sources[i];
        if (!supportedType(s.getAttribute('type'))) continue;
        var media = s.getAttribute('media');
        if (media && W.matchMedia && !W.matchMedia(media).matches) continue;
        cand = pickSrcset(s.getAttribute('srcset') || s.getAttribute('data-srcset'));
      }
    }
    if (!cand) cand = pickSrcset(img.getAttribute('srcset'));
    if (!cand) {
      for (var a = 0; a < LAZY_ATTRS.length && !cand; a++) cand = img.getAttribute(LAZY_ATTRS[a]);
    }
    if (!cand) cand = pickSrcset(img.getAttribute('data-srcset'));
    if (cand && cand !== src) {
      img.__blFixed = 1;
      img.src = cand;
    }
  }

  // <img loading=lazy> / <iframe loading=lazy> arrive with src renamed to data-bl-src by the app, because the
  // old engine would otherwise load every one of them immediately.
  var deferred = [];
  function restore(el) {
    var s = el.getAttribute('data-bl-src'), ss = el.getAttribute('data-bl-srcset');
    if (ss !== null) {
      el.removeAttribute('data-bl-srcset');
      el.setAttribute('srcset', ss);
    }
    if (s !== null) {
      el.removeAttribute('data-bl-src');
      el.setAttribute('src', s);
    }
    if (el.tagName === 'IMG') fixImage(el);
  }
  function scanDeferred() {
    var found = D.querySelectorAll('[data-bl-src],[data-bl-srcset]');
    for (var i = 0; i < found.length; i++) {
      if (!found[i].__blQueued) {
        found[i].__blQueued = 1;
        deferred.push(found[i]);
      }
    }
  }
  function checkDeferred() {
    if (!deferred.length) return;
    var h = viewportH(), margin = h * 1.5;
    var keep = [];
    for (var i = 0; i < deferred.length; i++) {
      var el = deferred[i];
      var r = el.getBoundingClientRect();
      var hidden = !r.width && !r.height && el.offsetParent === null;
      if (!hidden && r.bottom >= -margin && r.top <= h + margin) restore(el);
      else if (hidden && el.tagName === 'IMG' && !D.documentElement.contains(el)) { /* detached: drop */ }
      else keep.push(el);
    }
    deferred = keep;
  }
  var pending = 0;
  function schedule() {
    if (pending) return;
    pending = setTimeout(function () {
      pending = 0;
      scanDeferred();
      checkDeferred();
      each(D.images, fixImage);
    }, 150);
  }
  bl.refreshImages = function () { schedule(); };
  D.addEventListener('scroll', schedule, true);
  W.addEventListener('resize', schedule, false);
  ready(function () {
    schedule();
    var MO = W.MutationObserver || W.WebKitMutationObserver;
    if (MO) {
      var mo = new MO(function () { schedule(); });
      mo.observe(D.documentElement, {childList: true, subtree: true});
      // Stop watching after a while: long-lived pages with constant DOM churn would pay forever.
      setTimeout(function () { mo.disconnect(); }, 30000);
    }
  });
  W.addEventListener('load', function () {
    schedule();
    setTimeout(schedule, 1500);
  }, false);

  /* ------------------------------------------------------------ cookie banners: unlock scrolling */
  if (cfg.cookie) {
    var cmp = '#onetrust-consent-sdk,#CybotCookiebotDialog,.qc-cmp2-container,.fc-consent-root,#didomi-host,'
      + '#usercentrics-root,.cmplz-cookiebanner,.cky-consent-container,#cookie-law-info-bar,.cc-window,'
      + '[id^="sp_message_container"],.truste_overlay,.truste_box_overlay,#BorlabsCookieBox,.iubenda-cs-container';
    var unlock = function () {
      var found = D.querySelectorAll(cmp);
      if (!found.length) return;
      each(found, function (el) { if (el.parentNode) el.parentNode.removeChild(el); });
      each([D.documentElement, D.body], function (el) {
        if (!el) return;
        var cs = W.getComputedStyle(el);
        if (cs.overflow === 'hidden' || cs.overflowY === 'hidden') el.style.setProperty('overflow', 'auto', 'important');
        if (cs.position === 'fixed') el.style.setProperty('position', 'static', 'important');
        el.className = String(el.className).replace(/\b(sp-message-open|didomi-popup-open|cmp-open|modal-open|no-scroll|noscroll|overflow-hidden)\b/g, '');
      });
    };
    ready(function () {
      unlock();
      setTimeout(unlock, 1500);
      setTimeout(unlock, 4000);
    });
  }

  /* ------------------------------------------------------------ flexbox min-size (Chromium < 44) */
  // Modern flex items never shrink below their content (min-width:auto). Old Chromium lets them collapse to
  // zero, so nav bars and cards turn into overlapping text. Emulate it with -webkit-min-content.
  var needsFlexFix = W.CSS && W.CSS.supports && !W.CSS.supports('min-width', 'auto');
  var fixFlex = function () {
    if (!D.body) return;
    var all = D.body.getElementsByTagName('*');
    for (var i = 0; i < all.length && i < 5000; i++) {
      var el = all[i];
      var cs = W.getComputedStyle(el);
      var d = cs.display;
      if (d !== 'flex' && d !== 'inline-flex' && d !== '-webkit-flex' && d !== '-webkit-inline-flex') continue;
      var dir = cs.webkitFlexDirection || cs.flexDirection || 'row';
      var row = dir.indexOf('column') < 0;
      var kids = el.children;
      for (var k = 0; k < kids.length; k++) {
        var c = kids[k];
        if (c.__blFlex) continue;
        c.__blFlex = 1;
        var ks = W.getComputedStyle(c);
        if (ks.position === 'absolute' || ks.position === 'fixed') continue;
        if ((row ? ks.overflowX : ks.overflowY) !== 'visible') continue;
        if (row) {
          if (ks.minWidth === '0px' || ks.minWidth === 'auto') c.style.minWidth = '-webkit-min-content';
        } else if (ks.minHeight === '0px' || ks.minHeight === 'auto') {
          c.style.minHeight = '-webkit-min-content';
        }
      }
    }
  };
  if (needsFlexFix) {
    ready(fixFlex);
    W.addEventListener('load', function () { fixFlex(); setTimeout(fixFlex, 2000); }, false);
  }
  bl.fixLayout = fixFlex;

  /* ------------------------------------------------------------ empty ad slots that still reserve space */
  if (cfg.ads) {
    var AD_TOKEN = /(^|[-_\s])(ads?|adv|advert|advertisement|banner|qc|sponsor|sponsored|quangcao|dfp|gpt)([-_\s\d]|$)/i;
    var collapseAds = function () {
      var list = D.querySelectorAll('[id*="ad"],[class*="ad"],[id*="banner"],[class*="banner"],[id*="qc"],[class*="qc"],[id*="sponsor"],[class*="sponsor"]');
      for (var i = 0; i < list.length && i < 3000; i++) {
        var el = list[i];
        if (!AD_TOKEN.test((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : ''))) continue;
        if ((el.textContent || '').replace(/\s+/g, '').length > 0) continue;
        if (el.querySelector('img:not([width="1"]),video,canvas,svg,picture,input,textarea,select')) continue;
        if (el.offsetHeight > 8) el.style.setProperty('display', 'none', 'important');
      }
    };
    ready(function () { collapseAds(); setTimeout(collapseAds, 2500); });
    W.addEventListener('load', function () { setTimeout(collapseAds, 500); }, false);
  }

  /* ------------------------------------------------------------ mask-image icons under high contrast */
  if (cfg.hc) {
    // Icons drawn with mask-image take their color from background-color, which high contrast clears.
    var fixMasks = function () {
      var list = D.querySelectorAll('span:empty,i:empty,div:empty,a:empty,button:empty,em:empty,b:empty');
      for (var i = 0; i < list.length && i < 2500; i++) {
        var cs = W.getComputedStyle(list[i]);
        var m = cs.webkitMaskImage || cs.maskImage;
        if (m && m !== 'none') list[i].style.setProperty('background-color', '#000', 'important');
      }
    };
    W.addEventListener('load', function () { fixMasks(); setTimeout(fixMasks, 2000); }, false);
  }

  /* ------------------------------------------------------------ sticky headers/footers */
  bl.unstick = function () {
    var all = D.body ? D.body.getElementsByTagName('*') : [];
    var vh = viewportH(), n = 0;
    for (var i = 0; i < all.length && i < 6000; i++) {
      var el = all[i];
      var cs = W.getComputedStyle(el);
      var pos = cs.position;
      if (pos !== 'fixed' && pos !== 'sticky' && pos !== '-webkit-sticky') continue;
      var r = el.getBoundingClientRect();
      if (r.height > vh * 0.6 && r.width > viewportW() * 0.9) {
        // A full-screen overlay (modal, paywall): hide it.
        el.style.setProperty('display', 'none', 'important');
      } else {
        el.style.setProperty('position', 'static', 'important');
      }
      n++;
    }
    return n;
  };
  if (cfg.unstick) {
    W.addEventListener('load', function () { bl.unstick(); setTimeout(bl.unstick, 2500); }, false);
  }

  /* ------------------------------------------------------------ paging fallback for inner scrollers */
  function scrollable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.scrollHeight <= el.clientHeight + 4) return false;
    var o = W.getComputedStyle(el).overflowY;
    return o === 'auto' || o === 'scroll' || o === 'overlay';
  }
  function mainScroller() {
    var best = null, bestArea = 0;
    var el = D.elementFromPoint(viewportW() / 2, viewportH() / 2);
    while (el && el !== D.body && el !== D.documentElement) {
      if (scrollable(el)) return el;
      el = el.parentNode;
    }
    var all = D.body ? D.body.getElementsByTagName('*') : [];
    for (var i = 0; i < all.length && i < 4000; i++) {
      var c = all[i];
      if (c.clientHeight < viewportH() * 0.4 || !scrollable(c)) continue;
      var area = c.clientWidth * c.clientHeight;
      if (area > bestArea) { best = c; bestArea = area; }
    }
    return best;
  }
  /** dir: 1 down, -1 up. Returns 1 if something scrolled. */
  bl.page = function (dir, overlap) {
    var s = mainScroller();
    if (!s) return 0;
    var before = s.scrollTop;
    s.scrollTop = before + dir * Math.max(40, s.clientHeight * (1 - (overlap || 0.1)));
    return s.scrollTop !== before ? 1 : 0;
  };

  /* ------------------------------------------------------------ memory */
  bl.lowMemory = function () {
    each(D.querySelectorAll('video,audio'), function (m) {
      try { m.pause(); } catch (e) { /* ignore */ }
    });
  };
  if (W.performance && performance.memory && cfg.memMb) {
    var warned = false;
    setInterval(function () {
      if (warned || D.webkitHidden) return;
      var used = performance.memory.usedJSHeapSize / 1048576;
      if (used > cfg.memMb) {
        warned = true;
        call('memory', Math.round(used));
      }
    }, 15000);
  }
})(window, document);
