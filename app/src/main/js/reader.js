/*
 * BrowserLite reader mode (ES5). Evaluated on demand followed by __blReader("nonce"). Finds the main
 * article, cleans it and hands plain semantic HTML back to the app, which renders it in an e-ink layout.
 */
window.__blReader = function (nonce) {
  'use strict';
  var D = document, W = window;
  var NEG = /comment|meta|footer|footnote|foot|sidebar|side-bar|widget|share|social|sharing|related|recommend|promo|sponsor|advert|ads?[-_]|[-_]ads?\b|banner|popup|modal|newsletter|subscribe|signup|cookie|breadcrumb|pagination|pager|nav|menu|masthead|header|toolbar|tags|rating|author-bio|hidden/i;
  var POS = /article|body|content|entry|main|page|post|text|blog|story|detail|chapter|reader|prose|news|noidung|bai-viet|baiviet|chitiet/i;
  var KEEP = {P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, UL: 1, OL: 1, LI: 1, BLOCKQUOTE: 1, PRE: 1, CODE: 1,
    TABLE: 1, THEAD: 1, TBODY: 1, TFOOT: 1, TR: 1, TD: 1, TH: 1, CAPTION: 1, IMG: 1, FIGURE: 1, FIGCAPTION: 1, A: 1,
    EM: 1, STRONG: 1, B: 1, I: 1, U: 1, S: 1, BR: 1, HR: 1, SUP: 1, SUB: 1, SMALL: 1, MARK: 1, DL: 1, DT: 1, DD: 1,
    Q: 1, CITE: 1, ABBR: 1, TIME: 1, KBD: 1, VAR: 1, DEL: 1, INS: 1, SPAN: 1, DIV: 1, SECTION: 1, ARTICLE: 1,
    PICTURE: 1};
  var DROP = {SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, IFRAME: 1, FORM: 1, BUTTON: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1,
    NAV: 1, ASIDE: 1, FOOTER: 1, OBJECT: 1, EMBED: 1, SVG: 1, CANVAS: 1, VIDEO: 1, AUDIO: 1, TEMPLATE: 1,
    LINK: 1, META: 1, DIALOG: 1, MENU: 1, LABEL: 1};

  function text(el) { return (el.textContent || '').replace(/\s+/g, ' '); }
  function tag(el) { return (el.tagName || '').toUpperCase(); }
  function cls(el) { return ((typeof el.className === 'string' ? el.className : '') + ' ' + (el.id || '')); }
  function linkDensity(el) {
    var total = text(el).length || 1, links = el.getElementsByTagName('a'), l = 0;
    for (var i = 0; i < links.length; i++) l += text(links[i]).length;
    return l / total;
  }
  function meta(names) {
    for (var i = 0; i < names.length; i++) {
      var m = D.querySelector('meta[property="' + names[i] + '"],meta[name="' + names[i] + '"]');
      if (m && m.getAttribute('content')) return m.getAttribute('content');
    }
    return '';
  }

  // Score block containers by the paragraphs they hold (a compact version of Readability's heuristic).
  var scores = [], nodes = [];
  function add(el, s) {
    if (!el || el.nodeType !== 1 || el === D.documentElement) return;
    var i = nodes.indexOf(el);
    if (i < 0) {
      nodes.push(el);
      var base = 0, c = cls(el);
      if (NEG.test(c) && !POS.test(c)) base -= 25;
      if (POS.test(c)) base += 25;
      var t = tag(el);
      if (t === 'ARTICLE' || t === 'MAIN') base += 20;
      if (t === 'DIV' || t === 'SECTION') base += 5;
      scores.push(base + s);
    } else {
      scores[i] += s;
    }
  }
  var paras = D.body ? D.body.querySelectorAll('p,pre,blockquote,td,li,h2,h3,div') : [];
  for (var i = 0; i < paras.length && i < 8000; i++) {
    var p = paras[i];
    if (tag(p) === 'DIV') {
      // Divs used as paragraphs: count only when they hold text directly.
      var direct = 0;
      for (var c = p.firstChild; c; c = c.nextSibling) if (c.nodeType === 3) direct += c.nodeValue.replace(/\s+/g, '').length;
      if (direct < 80) continue;
    }
    var len = text(p).length;
    if (len < 25) continue;
    var s = 1 + (text(p).split(/[,，、]/).length - 1) + Math.min(3, Math.floor(len / 100));
    add(p.parentNode, s);
    if (p.parentNode) add(p.parentNode.parentNode, s / 2);
  }
  var best = null, bestScore = 0;
  for (var j = 0; j < nodes.length; j++) {
    var sc = scores[j] * (1 - linkDensity(nodes[j]));
    if (sc > bestScore) { bestScore = sc; best = nodes[j]; }
  }
  var articles = D.getElementsByTagName('article');
  if (articles.length === 1 && text(articles[0]).length > 600 && (!best || !articles[0].contains(best) || text(best).length < text(articles[0]).length * 0.5)) {
    best = articles[0];
  }
  if (best && text(best).length < 400 && best.parentNode && best.parentNode !== D.body) {
    best = best.parentNode;
  }
  if (!best || text(best).length < 250) {
    W.__blBridge && W.__blBridge.readerResult(nonce, '');
    return;
  }

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return u; }
  }
  function bestImage(img) {
    var src = img.getAttribute('data-bl-src') || img.getAttribute('src') || '';
    var lazy = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('data-original');
    if (lazy && (!src || /^data:/.test(src))) src = lazy;
    var set = img.getAttribute('data-bl-srcset') || img.getAttribute('srcset') || img.getAttribute('data-srcset');
    if ((!src || /^data:/.test(src)) && set) src = set.split(',')[0].replace(/^\s+/, '').split(/\s+/)[0];
    return src && !/^data:/.test(src) ? abs(src) : '';
  }

  function clean(src, depth) {
    var out = D.createDocumentFragment();
    for (var n = src.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3) {
        out.appendChild(D.createTextNode(n.nodeValue));
        continue;
      }
      if (n.nodeType !== 1) continue;
      var t = tag(n);
      if (DROP[t]) continue;
      if (n.getAttribute('aria-hidden') === 'true' || n.hidden) continue;
      var c = cls(n);
      var tl = text(n).length;
      if (t !== 'IMG' && t !== 'FIGURE' && t !== 'PICTURE' && NEG.test(c) && !POS.test(c) && tl < 400) continue;
      if ((t === 'DIV' || t === 'SECTION' || t === 'UL') && tl > 0 && tl < 300 && linkDensity(n) > 0.5) continue;
      if (t === 'H1' && depth < 3 && text(n).replace(/^\s+|\s+$/g, '') === title) continue;
      if (t === 'IMG') {
        var s = bestImage(n);
        if (!s) continue;
        var w = parseInt(n.getAttribute('width'), 10);
        if (w && w < 40) continue;
        var img = D.createElement('img');
        img.setAttribute('src', s);
        if (n.getAttribute('alt')) img.setAttribute('alt', n.getAttribute('alt'));
        out.appendChild(img);
        continue;
      }
      if (!KEEP[t]) {
        out.appendChild(clean(n, depth + 1));
        continue;
      }
      var el;
      if (t === 'DIV' || t === 'SECTION' || t === 'ARTICLE' || t === 'SPAN' || t === 'PICTURE') {
        var inner = clean(n, depth + 1);
        if (t === 'SPAN' || t === 'PICTURE') { out.appendChild(inner); continue; }
        el = D.createElement('div');
        el.appendChild(inner);
        if (!el.firstChild || (!text(el).replace(/\s/g, '') && !el.getElementsByTagName('img').length)) continue;
        out.appendChild(el);
        continue;
      }
      el = D.createElement(t.toLowerCase());
      if (t === 'A') {
        var href = n.getAttribute('href');
        if (href && !/^\s*javascript:/i.test(href)) el.setAttribute('href', abs(href));
      }
      if (t === 'TD' || t === 'TH') {
        if (n.getAttribute('colspan')) el.setAttribute('colspan', n.getAttribute('colspan'));
        if (n.getAttribute('rowspan')) el.setAttribute('rowspan', n.getAttribute('rowspan'));
      }
      el.appendChild(clean(n, depth + 1));
      if ((t === 'P' || t === 'LI' || t === 'FIGURE') && !text(el).replace(/\s/g, '') && !el.getElementsByTagName('img').length) continue;
      out.appendChild(el);
    }
    return out;
  }

  var title = meta(['og:title', 'twitter:title']) || '';
  var h1 = best.getElementsByTagName('h1')[0] || D.getElementsByTagName('h1')[0];
  if (!title && h1) title = text(h1);
  if (!title) title = D.title || '';
  title = title.replace(/^\s+|\s+$/g, '');
  var h1text = h1 ? text(h1).replace(/^\s+|\s+$/g, '') : '';
  // "Headline - Site name" / "Headline | Site": the page's own heading is cleaner.
  if (h1text && h1text.length > 10 && title.indexOf(h1text) === 0) title = h1text;
  var byline = meta(['author', 'article:author', 'byl', 'dc.creator']);
  if (!byline) {
    var by = D.querySelector('[rel="author"],.byline,.author,[itemprop="author"]');
    if (by && text(by).length < 100) byline = text(by);
  }
  var site = meta(['og:site_name', 'application-name']) || location.hostname.replace(/^www\./, '');

  var container = D.createElement('div');
  var lead = meta(['og:image']);
  container.appendChild(clean(best, 0));
  if (lead && !container.getElementsByTagName('img').length) {
    var li = D.createElement('img');
    li.setAttribute('src', abs(lead));
    container.insertBefore(li, container.firstChild);
  }
  var words = text(container).split(' ').length;
  var html = container.innerHTML;
  var result = JSON.stringify({title: title, byline: byline.replace(/^\s+|\s+$/g, ''), site: site, html: html,
    minutes: Math.max(1, Math.round(words / 200))});
  if (W.__blBridge) W.__blBridge.readerResult(nonce, result);
};
