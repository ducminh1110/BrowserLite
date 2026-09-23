/*
 * BrowserLite polyfills for the Android 4.4 WebView (Chromium 30 / 33).
 * Strictly ES5: this file itself must parse on the old engine. Everything is feature-detected, so on a
 * newer WebView it adds almost nothing. Globals are assigned on window explicitly because this file may
 * also be evaluated inside a function wrapper.
 */
(function (W) {
  'use strict';
  if (W.__blpoly) return;
  W.__blpoly = 1;
  var D = W.document;
  var OP = Object.prototype, AP = Array.prototype, SP = String.prototype, FP = Function.prototype;
  var hasOwn = OP.hasOwnProperty;
  var toStr = OP.toString;
  function noop() {}

  function def(obj, name, value, force) {
    if (!obj || (!force && name in obj)) return;
    try {
      Object.defineProperty(obj, name, {value: value, writable: true, configurable: true, enumerable: false});
    } catch (e) {
      try { obj[name] = value; } catch (e2) { /* read-only host object */ }
    }
  }
  function getter(obj, name, get, set) {
    if (!obj || name in obj) return;
    try {
      Object.defineProperty(obj, name, {get: get, set: set, configurable: true, enumerable: false});
    } catch (e) { /* ignore */ }
  }
  function isFn(f) { return typeof f === 'function'; }
  function toObj(v) {
    if (v == null) throw new TypeError('Cannot convert undefined or null to object');
    return Object(v);
  }
  function toInt(v) {
    v = +v;
    if (v !== v) return 0;
    if (v === 0 || v === Infinity || v === -Infinity) return v;
    return (v < 0 ? -1 : 1) * Math.floor(Math.abs(v));
  }
  function toLen(v) {
    v = toInt(v);
    return v <= 0 ? 0 : Math.min(v, 9007199254740991);
  }
  function relIndex(i, len) {
    i = toInt(i);
    return i < 0 ? Math.max(len + i, 0) : Math.min(i, len);
  }

  def(W, 'globalThis', W);

  /* ---------------------------------------------------------------- microtasks */
  var asap = (function () {
    if (W.Promise && isFn(W.Promise.resolve) && /\[native code\]/.test(String(W.Promise))) {
      var p = W.Promise.resolve();
      return function (fn) { p.then(fn); };
    }
    var MO = W.MutationObserver || W.WebKitMutationObserver;
    var queue = [];
    var flag = 0;
    var node = D.createTextNode('');
    var scheduled = false;
    function flush() {
      scheduled = false;
      var q = queue;
      queue = [];
      for (var i = 0; i < q.length; i++) {
        try { q[i](); } catch (e) { setTimeout(function () { throw e; }, 0); }
      }
    }
    if (MO) {
      new MO(flush).observe(node, {characterData: true});
      return function (fn) {
        queue.push(fn);
        if (!scheduled) {
          scheduled = true;
          flag = 1 - flag;
          node.data = String(flag);
        }
      };
    }
    return function (fn) { setTimeout(fn, 0); };
  })();
  def(W, 'queueMicrotask', function (fn) {
    if (!isFn(fn)) throw new TypeError('queueMicrotask requires a function');
    asap(fn);
  });

  /* ---------------------------------------------------------------- Symbol */
  var nativeSymbol = isFn(W.Symbol) && typeof W.Symbol() === 'symbol';
  if (!nativeSymbol) {
    var symCount = 0;
    var symRegistry = {};
    var Sym = function Symbol(desc) {
      if (this instanceof Sym) throw new TypeError('Symbol is not a constructor');
      var key = '@@' + (desc === undefined ? '' : String(desc)) + '@' + (++symCount) + Math.random().toString(36).slice(2, 7);
      // Assignments with this key create non-enumerable properties, like real symbols.
      try {
        Object.defineProperty(OP, key, {
          configurable: true,
          enumerable: false,
          set: function (v) {
            Object.defineProperty(this, key, {value: v, writable: true, configurable: true, enumerable: false});
          }
        });
      } catch (e) { /* ignore */ }
      return key;
    };
    var wk = ['iterator', 'asyncIterator', 'hasInstance', 'isConcatSpreadable', 'match', 'matchAll', 'replace',
      'search', 'species', 'split', 'toPrimitive', 'toStringTag', 'unscopables'];
    for (var wi = 0; wi < wk.length; wi++) Sym[wk[wi]] = '@@' + wk[wi];
    Sym['for'] = function (k) {
      k = String(k);
      return hasOwn.call(symRegistry, k) ? symRegistry[k] : (symRegistry[k] = Sym(k));
    };
    Sym.keyFor = function (s) {
      for (var k in symRegistry) if (symRegistry[k] === s) return k;
      return undefined;
    };
    W.Symbol = Sym;
    def(Object, 'getOwnPropertySymbols', function (o) {
      var names = Object.getOwnPropertyNames(o), out = [];
      for (var i = 0; i < names.length; i++) if (names[i].slice(0, 2) === '@@') out.push(names[i]);
      return out;
    });
  }
  var SymIter = W.Symbol.iterator;
  var SymAsyncIter = W.Symbol.asyncIterator || '@@asyncIterator';
  if (!W.Symbol.asyncIterator) try { W.Symbol.asyncIterator = SymAsyncIter; } catch (e) { /* frozen */ }

  /* ---------------------------------------------------------------- iterators */
  function iterResult(v, done) { return {value: v, done: done}; }
  function ArrayIterator(a, kind) { this._a = a; this._i = 0; this._k = kind; }
  ArrayIterator.prototype.next = function () {
    var a = this._a;
    if (!a || this._i >= a.length) { this._a = undefined; return iterResult(undefined, true); }
    var i = this._i++;
    return iterResult(this._k === 1 ? i : this._k === 2 ? [i, a[i]] : a[i], false);
  };
  ArrayIterator.prototype[SymIter] = function () { return this; };
  function values() { return new ArrayIterator(toObj(this), 0); }
  def(AP, 'keys', function () { return new ArrayIterator(toObj(this), 1); });
  def(AP, 'entries', function () { return new ArrayIterator(toObj(this), 2); });
  def(AP, 'values', values);
  def(AP, SymIter, AP.values);
  def(SP, SymIter, function () {
    var s = String(this), i = 0;
    var it = {
      next: function () {
        if (i >= s.length) return iterResult(undefined, true);
        var c = s.charCodeAt(i), n = 1;
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
          var d = s.charCodeAt(i + 1);
          if (d >= 0xDC00 && d <= 0xDFFF) n = 2;
        }
        var ch = s.substr(i, n);
        i += n;
        return iterResult(ch, false);
      }
    };
    it[SymIter] = function () { return this; };
    return it;
  });
  function getIterator(o) {
    if (o == null) throw new TypeError(o + ' is not iterable');
    var fn = o[SymIter];
    if (isFn(fn)) return fn.call(o);
    if (typeof o.length === 'number') return new ArrayIterator(o, 0);
    throw new TypeError('object is not iterable');
  }
  function forOf(o, cb) {
    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) cb(o[i]); return; }
    var it = getIterator(o), r;
    while (!(r = it.next()).done) cb(r.value);
  }
  W.__blForOf = forOf;
  var listLikes = ['NodeList', 'HTMLCollection', 'DOMTokenList', 'FileList', 'CSSRuleList', 'StyleSheetList',
    'HTMLAllCollection', 'HTMLFormControlsCollection', 'HTMLOptionsCollection', 'NamedNodeMap', 'TouchList',
    'DataTransferItemList', 'MediaList', 'CSSStyleDeclaration', 'SVGNumberList', 'SVGLengthList'];
  for (var li = 0; li < listLikes.length; li++) {
    var LC = W[listLikes[li]];
    if (LC && LC.prototype) def(LC.prototype, SymIter, AP.values);
  }
  if (W.NodeList) {
    def(W.NodeList.prototype, 'forEach', AP.forEach);
    def(W.NodeList.prototype, 'keys', AP.keys);
    def(W.NodeList.prototype, 'values', AP.values);
    def(W.NodeList.prototype, 'entries', AP.entries);
  }
  if (W.DOMTokenList) {
    def(W.DOMTokenList.prototype, 'forEach', AP.forEach);
    def(W.DOMTokenList.prototype, 'replace', function (a, b) {
      if (!this.contains(a)) return false;
      this.remove(a);
      this.add(b);
      return true;
    });
    def(W.DOMTokenList.prototype, 'supports', function () { return true; });
  }

  /* ---------------------------------------------------------------- Object */
  def(Object, 'assign', function (target) {
    var to = toObj(target);
    for (var i = 1; i < arguments.length; i++) {
      var src = arguments[i];
      if (src == null) continue;
      src = Object(src);
      var keys = Object.keys(src);
      for (var k = 0; k < keys.length; k++) to[keys[k]] = src[keys[k]];
      if (!nativeSymbol) {
        var syms = Object.getOwnPropertySymbols(src);
        for (var s = 0; s < syms.length; s++) to[syms[s]] = src[syms[s]];
      }
    }
    return to;
  });
  def(Object, 'is', function (a, b) {
    if (a === b) return a !== 0 || 1 / a === 1 / b;
    return a !== a && b !== b;
  });
  def(Object, 'setPrototypeOf', function (o, proto) {
    o.__proto__ = proto;
    return o;
  });
  def(Object, 'values', function (o) {
    o = toObj(o);
    var k = Object.keys(o), out = [];
    for (var i = 0; i < k.length; i++) out.push(o[k[i]]);
    return out;
  });
  def(Object, 'entries', function (o) {
    o = toObj(o);
    var k = Object.keys(o), out = [];
    for (var i = 0; i < k.length; i++) out.push([k[i], o[k[i]]]);
    return out;
  });
  def(Object, 'fromEntries', function (it) {
    var o = {};
    forOf(it, function (e) { o[e[0]] = e[1]; });
    return o;
  });
  def(Object, 'getOwnPropertyDescriptors', function (o) {
    var out = {}, names = Object.getOwnPropertyNames(o);
    for (var i = 0; i < names.length; i++) out[names[i]] = Object.getOwnPropertyDescriptor(o, names[i]);
    return out;
  });
  def(Object, 'hasOwn', function (o, k) { return hasOwn.call(toObj(o), k); });
  def(Object, 'groupBy', function (items, fn) {
    var out = Object.create(null), i = 0;
    forOf(items, function (v) {
      var k = fn(v, i++);
      (out[k] || (out[k] = [])).push(v);
    });
    return out;
  });

  /* ---------------------------------------------------------------- Array */
  def(Array, 'from', function (items, mapFn, thisArg) {
    if (items == null) throw new TypeError('Array.from requires an array-like object');
    var C = isFn(this) && this !== Array ? this : null;
    var out = [], i = 0;
    var map = isFn(mapFn);
    var useIter = items[SymIter] !== undefined && !(typeof items === 'string' && nativeSymbol === false && false);
    if (useIter && (typeof items !== 'object' || !('length' in items) || items[SymIter] !== AP.values)) {
      forOf(items, function (v) { out.push(map ? mapFn.call(thisArg, v, i) : v); i++; });
    } else {
      var o = Object(items), len = toLen(o.length);
      for (; i < len; i++) out.push(map ? mapFn.call(thisArg, o[i], i) : o[i]);
    }
    if (C) {
      var r = new C(out.length);
      for (var j = 0; j < out.length; j++) r[j] = out[j];
      r.length = out.length;
      return r;
    }
    return out;
  });
  def(Array, 'of', function () { return AP.slice.call(arguments); });
  function findImpl(fromEnd, wantIndex) {
    return function (pred, thisArg) {
      var o = toObj(this), len = toLen(o.length);
      if (!isFn(pred)) throw new TypeError('predicate must be a function');
      for (var n = 0; n < len; n++) {
        var i = fromEnd ? len - 1 - n : n;
        if (pred.call(thisArg, o[i], i, o)) return wantIndex ? i : o[i];
      }
      return wantIndex ? -1 : undefined;
    };
  }
  def(AP, 'find', findImpl(false, false));
  def(AP, 'findIndex', findImpl(false, true));
  def(AP, 'findLast', findImpl(true, false));
  def(AP, 'findLastIndex', findImpl(true, true));
  def(AP, 'includes', function (x, from) {
    var o = toObj(this), len = toLen(o.length);
    for (var i = relIndex(from || 0, len); i < len; i++) {
      var v = o[i];
      if (v === x || (x !== x && v !== v)) return true;
    }
    return false;
  });
  def(AP, 'fill', function (v, start, end) {
    var o = toObj(this), len = toLen(o.length);
    var s = relIndex(start || 0, len), e = end === undefined ? len : relIndex(end, len);
    for (; s < e; s++) o[s] = v;
    return o;
  });
  def(AP, 'copyWithin', function (target, start, end) {
    var o = toObj(this), len = toLen(o.length);
    var to = relIndex(target, len), from = relIndex(start || 0, len);
    var fin = end === undefined ? len : relIndex(end, len);
    var count = Math.min(fin - from, len - to);
    var copy = AP.slice.call(o, from, from + count);
    for (var i = 0; i < copy.length; i++) o[to + i] = copy[i];
    return o;
  });
  function flatten(out, arr, depth) {
    for (var i = 0; i < arr.length; i++) {
      if (!(i in arr)) continue;
      var v = arr[i];
      if (depth > 0 && Array.isArray(v)) flatten(out, v, depth - 1);
      else out.push(v);
    }
    return out;
  }
  def(AP, 'flat', function (depth) {
    return flatten([], toObj(this), depth === undefined ? 1 : toInt(depth));
  });
  def(AP, 'flatMap', function (fn, thisArg) {
    return flatten([], AP.map.call(toObj(this), fn, thisArg), 1);
  });
  def(AP, 'at', function (i) {
    var o = toObj(this), len = toLen(o.length);
    i = toInt(i);
    if (i < 0) i += len;
    return i < 0 || i >= len ? undefined : o[i];
  });
  def(AP, 'toReversed', function () { return AP.slice.call(this).reverse(); });
  def(AP, 'toSorted', function (cmp) { return AP.slice.call(this).sort(cmp); });
  def(AP, 'toSpliced', function () {
    var c = AP.slice.call(this);
    AP.splice.apply(c, arguments);
    return c;
  });
  def(AP, 'with', function (i, v) {
    var c = AP.slice.call(this), len = c.length;
    i = toInt(i);
    if (i < 0) i += len;
    if (i < 0 || i >= len) throw new RangeError('Invalid index');
    c[i] = v;
    return c;
  });

  /* ---------------------------------------------------------------- String */
  def(SP, 'includes', function (s, pos) { return String(this).indexOf(s, pos) !== -1; });
  def(SP, 'startsWith', function (s, pos) {
    pos = toInt(pos);
    s = String(s);
    return String(this).substr(pos, s.length) === s;
  });
  def(SP, 'endsWith', function (s, end) {
    var str = String(this);
    s = String(s);
    end = end === undefined ? str.length : Math.min(Math.max(toInt(end), 0), str.length);
    return str.slice(end - s.length, end) === s && end - s.length >= 0;
  });
  def(SP, 'repeat', function (n) {
    var s = String(this), out = '';
    n = toInt(n);
    if (n < 0 || n === Infinity) throw new RangeError('Invalid count value');
    while (n > 0) {
      if (n & 1) out += s;
      n >>= 1;
      if (n) s += s;
    }
    return out;
  });
  function pad(atStart) {
    return function (len, fill) {
      var s = String(this);
      len = toLen(len);
      fill = fill === undefined ? ' ' : String(fill);
      if (len <= s.length || fill === '') return s;
      var need = len - s.length, f = '';
      while (f.length < need) f += fill;
      f = f.slice(0, need);
      return atStart ? f + s : s + f;
    };
  }
  def(SP, 'padStart', pad(true));
  def(SP, 'padEnd', pad(false));
  def(SP, 'trimStart', SP.trimLeft || function () { return String(this).replace(/^[\s﻿\xA0]+/, ''); });
  def(SP, 'trimEnd', SP.trimRight || function () { return String(this).replace(/[\s﻿\xA0]+$/, ''); });
  def(SP, 'trimLeft', SP.trimStart);
  def(SP, 'trimRight', SP.trimEnd);
  def(SP, 'replaceAll', function (search, rep) {
    var s = String(this);
    if (toStr.call(search) === '[object RegExp]') {
      if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
      return s.replace(search, rep);
    }
    search = String(search);
    if (search === '') {
      var r = '';
      for (var i = 0; i <= s.length; i++) r += (isFn(rep) ? rep('', i, s) : rep) + (i < s.length ? s.charAt(i) : '');
      return r;
    }
    var out = '', pos = 0, idx;
    while ((idx = s.indexOf(search, pos)) !== -1) {
      out += s.slice(pos, idx) + (isFn(rep) ? rep(search, idx, s) : String(rep).replace(/\$&/g, search));
      pos = idx + search.length;
    }
    return out + s.slice(pos);
  });
  def(SP, 'at', function (i) {
    var s = String(this);
    i = toInt(i);
    if (i < 0) i += s.length;
    return i < 0 || i >= s.length ? undefined : s.charAt(i);
  });
  def(SP, 'codePointAt', function (pos) {
    var s = String(this), i = toInt(pos);
    if (i < 0 || i >= s.length) return undefined;
    var a = s.charCodeAt(i);
    if (a >= 0xD800 && a <= 0xDBFF && i + 1 < s.length) {
      var b = s.charCodeAt(i + 1);
      if (b >= 0xDC00 && b <= 0xDFFF) return (a - 0xD800) * 0x400 + b - 0xDC00 + 0x10000;
    }
    return a;
  });
  def(String, 'fromCodePoint', function () {
    var out = '';
    for (var i = 0; i < arguments.length; i++) {
      var c = +arguments[i];
      if (c !== toInt(c) || c < 0 || c > 0x10FFFF) throw new RangeError('Invalid code point ' + c);
      if (c < 0x10000) out += String.fromCharCode(c);
      else {
        c -= 0x10000;
        out += String.fromCharCode((c >> 10) + 0xD800, (c % 0x400) + 0xDC00);
      }
    }
    return out;
  });
  def(String, 'raw', function (strings) {
    var raw = strings.raw, out = '';
    for (var i = 0; i < raw.length; i++) {
      out += raw[i];
      if (i + 1 < raw.length && i + 1 < arguments.length) out += arguments[i + 1];
    }
    return out;
  });
  def(SP, 'normalize', function () { return String(this); });
  def(SP, 'isWellFormed', function () { return !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(^|[^\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(this); });
  def(SP, 'matchAll', function (re) {
    var s = String(this);
    var r = toStr.call(re) === '[object RegExp]' ? new RegExp(re.source, re.global ? re.flags || (re.ignoreCase ? 'gi' : 'g') + (re.multiline ? 'm' : '') : 'g') : new RegExp(re, 'g');
    var all = [], m;
    while ((m = r.exec(s)) !== null) {
      all.push(m);
      if (m[0] === '') r.lastIndex++;
    }
    return new ArrayIterator(all, 0);
  });
  if (!('flags' in RegExp.prototype)) {
    getter(RegExp.prototype, 'flags', function () {
      return (this.global ? 'g' : '') + (this.ignoreCase ? 'i' : '') + (this.multiline ? 'm' : '');
    });
  }

  /* ---------------------------------------------------------------- Number / Math */
  def(Number, 'isNaN', function (v) { return typeof v === 'number' && v !== v; });
  def(Number, 'isFinite', function (v) { return typeof v === 'number' && isFinite(v); });
  def(Number, 'isInteger', function (v) { return typeof v === 'number' && isFinite(v) && Math.floor(v) === v; });
  def(Number, 'isSafeInteger', function (v) { return Number.isInteger(v) && Math.abs(v) <= 9007199254740991; });
  def(Number, 'parseFloat', parseFloat);
  def(Number, 'parseInt', parseInt);
  def(Number, 'EPSILON', Math.pow(2, -52));
  def(Number, 'MAX_SAFE_INTEGER', 9007199254740991);
  def(Number, 'MIN_SAFE_INTEGER', -9007199254740991);
  def(Math, 'trunc', function (v) { v = +v; return v < 0 ? Math.ceil(v) : Math.floor(v); });
  def(Math, 'sign', function (v) { v = +v; return v === 0 || v !== v ? v : v > 0 ? 1 : -1; });
  def(Math, 'cbrt', function (v) { var y = Math.pow(Math.abs(v), 1 / 3); return v < 0 ? -y : y; });
  def(Math, 'log10', function (v) { return Math.log(v) / Math.LN10; });
  def(Math, 'log2', function (v) { return Math.log(v) / Math.LN2; });
  def(Math, 'log1p', function (v) { v = +v; return Math.abs(v) < 1e-8 ? v : Math.log(1 + v); });
  def(Math, 'expm1', function (v) { v = +v; return Math.abs(v) < 1e-8 ? v : Math.exp(v) - 1; });
  def(Math, 'hypot', function () {
    var s = 0;
    for (var i = 0; i < arguments.length; i++) s += arguments[i] * arguments[i];
    return Math.sqrt(s);
  });
  def(Math, 'sinh', function (v) { return (Math.exp(v) - Math.exp(-v)) / 2; });
  def(Math, 'cosh', function (v) { return (Math.exp(v) + Math.exp(-v)) / 2; });
  def(Math, 'tanh', function (v) { var a = Math.exp(v), b = Math.exp(-v); return a === Infinity ? 1 : b === Infinity ? -1 : (a - b) / (a + b); });
  def(Math, 'asinh', function (v) { return v === -Infinity ? v : Math.log(v + Math.sqrt(v * v + 1)); });
  def(Math, 'acosh', function (v) { return Math.log(v + Math.sqrt(v * v - 1)); });
  def(Math, 'atanh', function (v) { return Math.log((1 + v) / (1 - v)) / 2; });
  def(Math, 'clz32', function (v) {
    v = v >>> 0;
    return v ? 31 - Math.floor(Math.log(v + 0.5) / Math.LN2) : 32;
  });
  def(Math, 'imul', function (a, b) {
    var ah = (a >>> 16) & 0xffff, al = a & 0xffff, bh = (b >>> 16) & 0xffff, bl = b & 0xffff;
    return ((al * bl) + (((ah * bl + al * bh) << 16) >>> 0) | 0);
  });
  if (W.Float32Array) {
    var f32 = new W.Float32Array(1);
    def(Math, 'fround', function (v) { f32[0] = v; return f32[0]; });
  }

  /* ---------------------------------------------------------------- typed arrays */
  var typed = ['Int8Array', 'Uint8Array', 'Uint8ClampedArray', 'Int16Array', 'Uint16Array', 'Int32Array',
    'Uint32Array', 'Float32Array', 'Float64Array'];
  for (var ti = 0; ti < typed.length; ti++) {
    var TA = W[typed[ti]];
    if (!TA) continue;
    var tp = TA.prototype;
    (function (TA, tp) {
      def(tp, 'slice', function (s, e) {
        var len = this.length;
        s = relIndex(s || 0, len);
        e = e === undefined ? len : relIndex(e, len);
        var out = new TA(Math.max(e - s, 0));
        for (var i = 0; i < out.length; i++) out[i] = this[s + i];
        return out;
      });
      var names = ['fill', 'indexOf', 'lastIndexOf', 'includes', 'forEach', 'join', 'every', 'some', 'reduce',
        'reduceRight', 'find', 'findIndex', 'reverse', 'at', 'keys', 'values', 'entries'];
      for (var k = 0; k < names.length; k++) if (AP[names[k]]) def(tp, names[k], AP[names[k]]);
      def(tp, SymIter, AP.values);
      def(tp, 'map', function (fn, thisArg) {
        var out = new TA(this.length);
        for (var i = 0; i < this.length; i++) out[i] = fn.call(thisArg, this[i], i, this);
        return out;
      });
      def(tp, 'filter', function (fn, thisArg) {
        var r = [];
        for (var i = 0; i < this.length; i++) if (fn.call(thisArg, this[i], i, this)) r.push(this[i]);
        return new TA(r);
      });
      def(tp, 'sort', function (cmp) {
        var a = AP.slice.call(this).sort(cmp || function (x, y) { return x - y; });
        for (var i = 0; i < a.length; i++) this[i] = a[i];
        return this;
      });
      def(TA, 'from', function (src, fn, thisArg) { return new TA(Array.from(src, fn, thisArg)); });
      def(TA, 'of', function () { return new TA(AP.slice.call(arguments)); });
    })(TA, tp);
  }
  if (W.ArrayBuffer) def(W.ArrayBuffer, 'isView', function (v) { return !!(v && v.buffer instanceof W.ArrayBuffer && 'byteLength' in v); });

  /* ---------------------------------------------------------------- Map / Set / WeakMap / WeakSet */
  var nativeMapOk = (function () {
    try {
      var m = new W.Map([[1, 2]]);
      return m.get(1) === 2 && isFn(m.forEach) && isFn(m[SymIter]) && m.size === 1;
    } catch (e) { return false; }
  })();
  var UID = '__bl_uid';
  var uidCount = 0;
  var DELETED = {};
  function hashOf(k) {
    var t = typeof k;
    if (t === 'string') return 's' + k;
    if (t === 'number') return k !== k ? 'NaN' : 'n' + (k === 0 ? 0 : k);
    if (t === 'boolean') return 'b' + k;
    if (k === undefined) return 'u';
    if (k === null) return 'l';
    if (t === 'object' || t === 'function') {
      if (!hasOwn.call(k, UID)) {
        if (!Object.isExtensible(k)) return null;
        Object.defineProperty(k, UID, {value: ++uidCount, enumerable: false});
      }
      return 'o' + k[UID];
    }
    return 's' + String(k);
  }
  function CollIterator(c, kind) { this._c = c; this._i = 0; this._k = kind; }
  CollIterator.prototype.next = function () {
    var c = this._c;
    if (!c) return iterResult(undefined, true);
    var keys = c._k;
    while (this._i < keys.length && keys[this._i] === DELETED) this._i++;
    if (this._i >= keys.length) { this._c = undefined; return iterResult(undefined, true); }
    var i = this._i++;
    return iterResult(this._k === 0 ? keys[i] : this._k === 1 ? c._v[i] : [keys[i], c._v[i]], false);
  };
  CollIterator.prototype[SymIter] = function () { return this; };

  function MapPoly(iterable) {
    if (!(this instanceof MapPoly)) throw new TypeError("Constructor Map requires 'new'");
    this._k = [];
    this._v = [];
    this._h = Object.create(null);
    this._n = 0;
    this._d = 0;
    if (iterable != null) {
      var self = this;
      forOf(iterable, function (e) {
        if (e !== Object(e)) throw new TypeError('Iterator value ' + e + ' is not an entry object');
        self.set(e[0], e[1]);
      });
    }
  }
  MapPoly.prototype._find = function (k) {
    var h = hashOf(k);
    if (h !== null) {
      var i = this._h[h];
      return i === undefined ? -1 : i;
    }
    for (var j = 0; j < this._k.length; j++) if (this._k[j] === k) return j;
    return -1;
  };
  MapPoly.prototype.get = function (k) { var i = this._find(k); return i < 0 ? undefined : this._v[i]; };
  MapPoly.prototype.has = function (k) { return this._find(k) >= 0; };
  MapPoly.prototype.set = function (k, v) {
    var i = this._find(k);
    if (i >= 0) { this._v[i] = v; return this; }
    if (k === 0) k = 0;
    i = this._k.length;
    this._k.push(k);
    this._v.push(v);
    var h = hashOf(k);
    if (h !== null) this._h[h] = i;
    this._n++;
    return this;
  };
  MapPoly.prototype['delete'] = function (k) {
    var i = this._find(k);
    if (i < 0) return false;
    var h = hashOf(this._k[i]);
    if (h !== null) delete this._h[h];
    this._k[i] = DELETED;
    this._v[i] = undefined;
    this._n--;
    if (++this._d > 64 && this._d > this._k.length / 2) this._compact();
    return true;
  };
  MapPoly.prototype._compact = function () {
    var k = [], v = [], h = Object.create(null);
    for (var i = 0; i < this._k.length; i++) {
      if (this._k[i] === DELETED) continue;
      var hk = hashOf(this._k[i]);
      if (hk !== null) h[hk] = k.length;
      k.push(this._k[i]);
      v.push(this._v[i]);
    }
    this._k = k;
    this._v = v;
    this._h = h;
    this._d = 0;
  };
  MapPoly.prototype.clear = function () {
    for (var i = 0; i < this._k.length; i++) { this._k[i] = DELETED; this._v[i] = undefined; }
    this._h = Object.create(null);
    this._n = 0;
  };
  MapPoly.prototype.forEach = function (cb, thisArg) {
    for (var i = 0; i < this._k.length; i++) if (this._k[i] !== DELETED) cb.call(thisArg, this._v[i], this._k[i], this);
  };
  MapPoly.prototype.keys = function () { return new CollIterator(this, 0); };
  MapPoly.prototype.values = function () { return new CollIterator(this, 1); };
  MapPoly.prototype.entries = function () { return new CollIterator(this, 2); };
  MapPoly.prototype[SymIter] = MapPoly.prototype.entries;
  Object.defineProperty(MapPoly.prototype, 'size', {get: function () { return this._n; }, configurable: true});
  MapPoly.groupBy = function (items, fn) {
    var m = new MapPoly(), i = 0;
    forOf(items, function (v) {
      var k = fn(v, i++);
      if (!m.has(k)) m.set(k, []);
      m.get(k).push(v);
    });
    return m;
  };

  function SetPoly(iterable) {
    if (!(this instanceof SetPoly)) throw new TypeError("Constructor Set requires 'new'");
    MapPoly.call(this);
    if (iterable != null) {
      var self = this;
      forOf(iterable, function (v) { self.add(v); });
    }
  }
  SetPoly.prototype = Object.create(MapPoly.prototype);
  SetPoly.prototype.constructor = SetPoly;
  SetPoly.prototype.add = function (v) { return MapPoly.prototype.set.call(this, v, v); };
  SetPoly.prototype.set = undefined;
  SetPoly.prototype.get = undefined;
  SetPoly.prototype.forEach = function (cb, thisArg) {
    for (var i = 0; i < this._k.length; i++) if (this._k[i] !== DELETED) cb.call(thisArg, this._k[i], this._k[i], this);
  };
  SetPoly.prototype.values = SetPoly.prototype.keys = function () { return new CollIterator(this, 0); };
  SetPoly.prototype.entries = function () {
    var it = new CollIterator(this, 0), next = it.next;
    it.next = function () {
      var r = next.call(it);
      if (!r.done) r.value = [r.value, r.value];
      return r;
    };
    return it;
  };
  SetPoly.prototype[SymIter] = SetPoly.prototype.values;

  if (!nativeMapOk) {
    W.Map = MapPoly;
    W.Set = SetPoly;
  }
  (function () {
    var S = W.Set, P = S.prototype;
    function other(o) { var s = new S(); forOf(o.keys ? o.keys() : o, function (v) { s.add(v); }); return s; }
    def(P, 'union', function (o) { var r = new S(this); forOf(other(o), function (v) { r.add(v); }); return r; });
    def(P, 'intersection', function (o) { var b = other(o), r = new S(); this.forEach(function (v) { if (b.has(v)) r.add(v); }); return r; });
    def(P, 'difference', function (o) { var b = other(o), r = new S(); this.forEach(function (v) { if (!b.has(v)) r.add(v); }); return r; });
    def(P, 'isSubsetOf', function (o) { var b = other(o), ok = true; this.forEach(function (v) { if (!b.has(v)) ok = false; }); return ok; });
    if (!W.Map.groupBy) W.Map.groupBy = MapPoly.groupBy;
  })();

  var weakOk = (function () {
    try { var k = {}, m = new W.WeakMap([[k, 1]]); return m.get(k) === 1; } catch (e) { return false; }
  })();
  if (!weakOk) {
    var wmCount = 0;
    var WeakMapPoly = function WeakMap(iterable) {
      if (!(this instanceof WeakMapPoly)) throw new TypeError("Constructor WeakMap requires 'new'");
      this._id = '__bl_wm' + (++wmCount) + Math.random().toString(36).slice(2, 6);
      this._frozen = null;
      if (iterable != null) {
        var self = this;
        forOf(iterable, function (e) { self.set(e[0], e[1]); });
      }
    };
    var checkKey = function (k) {
      if (k !== Object(k)) throw new TypeError('Invalid value used as weak map key');
    };
    WeakMapPoly.prototype.set = function (k, v) {
      checkKey(k);
      if (hasOwn.call(k, this._id)) k[this._id][0] = v;
      else if (Object.isExtensible(k)) Object.defineProperty(k, this._id, {value: [v], enumerable: false, configurable: true});
      else (this._frozen || (this._frozen = new MapPoly())).set(k, v);
      return this;
    };
    WeakMapPoly.prototype.get = function (k) {
      if (k !== Object(k)) return undefined;
      if (hasOwn.call(k, this._id)) return k[this._id][0];
      return this._frozen ? this._frozen.get(k) : undefined;
    };
    WeakMapPoly.prototype.has = function (k) {
      if (k !== Object(k)) return false;
      return hasOwn.call(k, this._id) || (!!this._frozen && this._frozen.has(k));
    };
    WeakMapPoly.prototype['delete'] = function (k) {
      if (k !== Object(k)) return false;
      if (hasOwn.call(k, this._id)) { delete k[this._id]; return true; }
      return this._frozen ? this._frozen['delete'](k) : false;
    };
    W.WeakMap = WeakMapPoly;
  }
  if (!W.WeakSet) {
    var WeakSetPoly = function WeakSet(iterable) {
      if (!(this instanceof WeakSetPoly)) throw new TypeError("Constructor WeakSet requires 'new'");
      this._m = new W.WeakMap();
      if (iterable != null) {
        var self = this;
        forOf(iterable, function (v) { self.add(v); });
      }
    };
    WeakSetPoly.prototype.add = function (v) { this._m.set(v, true); return this; };
    WeakSetPoly.prototype.has = function (v) { return this._m.has(v); };
    WeakSetPoly.prototype['delete'] = function (v) { return this._m['delete'](v); };
    W.WeakSet = WeakSetPoly;
  }
  if (!W.WeakRef) {
    W.WeakRef = function WeakRef(t) { this._t = t; };
    W.WeakRef.prototype.deref = function () { return this._t; };
  }
  if (!W.FinalizationRegistry) {
    W.FinalizationRegistry = function FinalizationRegistry() {};
    W.FinalizationRegistry.prototype.register = noop;
    W.FinalizationRegistry.prototype.unregister = function () { return false; };
  }

  /* ---------------------------------------------------------------- Promise */
  if (!isFn(W.Promise)) {
    var PENDING = 0, FULFILLED = 1, REJECTED = 2;
    var PromisePoly = function Promise(executor) {
      if (!(this instanceof PromisePoly)) throw new TypeError("Promise constructor cannot be invoked without 'new'");
      if (!isFn(executor)) throw new TypeError('Promise resolver ' + executor + ' is not a function');
      this._s = PENDING;
      this._v = undefined;
      this._q = [];
      this._h = false;
      var self = this, done = false;
      try {
        executor(function (v) {
          if (done) return;
          done = true;
          resolvePromise(self, v);
        }, function (r) {
          if (done) return;
          done = true;
          settle(self, REJECTED, r);
        });
      } catch (e) {
        if (!done) {
          done = true;
          settle(self, REJECTED, e);
        }
      }
    };
    var settle = function (p, state, value) {
      if (p._s !== PENDING) return;
      p._s = state;
      p._v = value;
      var q = p._q;
      p._q = null;
      for (var i = 0; i < q.length; i++) schedule(p, q[i]);
      if (state === REJECTED && !p._h) {
        setTimeout(function () {
          if (!p._h && W.console && W.console.warn) W.console.warn('Uncaught (in promise)', value);
        }, 0);
      }
    };
    var resolvePromise = function (p, x) {
      if (x === p) return settle(p, REJECTED, new TypeError('Chaining cycle detected for promise'));
      if (x !== null && (typeof x === 'object' || typeof x === 'function')) {
        var then;
        try {
          then = x.then;
        } catch (e) {
          return settle(p, REJECTED, e);
        }
        if (isFn(then)) {
          var called = false;
          try {
            then.call(x, function (y) {
              if (called) return;
              called = true;
              resolvePromise(p, y);
            }, function (r) {
              if (called) return;
              called = true;
              settle(p, REJECTED, r);
            });
          } catch (e) {
            if (!called) {
              called = true;
              settle(p, REJECTED, e);
            }
          }
          return;
        }
      }
      settle(p, FULFILLED, x);
    };
    var schedule = function (p, h) {
      asap(function () {
        var cb = p._s === FULFILLED ? h.f : h.r;
        if (!isFn(cb)) {
          if (p._s === FULFILLED) resolvePromise(h.p, p._v);
          else settle(h.p, REJECTED, p._v);
          return;
        }
        var v;
        try {
          v = cb(p._v);
        } catch (e) {
          settle(h.p, REJECTED, e);
          return;
        }
        resolvePromise(h.p, v);
      });
    };
    PromisePoly.prototype.then = function (onF, onR) {
      var C = this.constructor;
      var next = new (isFn(C) && C !== Object ? C : PromisePoly)(noop);
      var h = {f: onF, r: onR, p: next};
      this._h = true;
      if (this._s === PENDING) this._q.push(h);
      else schedule(this, h);
      return next;
    };
    PromisePoly.prototype['catch'] = function (onR) { return this.then(undefined, onR); };
    PromisePoly.resolve = function (v) {
      if (v instanceof PromisePoly && v.constructor === this) return v;
      return new PromisePoly(function (res) { res(v); });
    };
    PromisePoly.reject = function (r) { return new PromisePoly(function (res, rej) { rej(r); }); };
    PromisePoly.all = function (items) {
      return new PromisePoly(function (res, rej) {
        var out = [], n = 0, i = 0;
        forOf(items, function (item) {
          var idx = i++;
          n++;
          PromisePoly.resolve(item).then(function (v) {
            out[idx] = v;
            if (--n === 0) res(out);
          }, rej);
        });
        if (n === 0) res(out);
      });
    };
    PromisePoly.race = function (items) {
      return new PromisePoly(function (res, rej) {
        forOf(items, function (item) { PromisePoly.resolve(item).then(res, rej); });
      });
    };
    W.Promise = PromisePoly;
  }
  (function (P) {
    def(P.prototype, 'finally', function (fn) {
      var C = this.constructor && isFn(this.constructor.resolve) ? this.constructor : P;
      if (!isFn(fn)) return this.then(fn, fn);
      return this.then(function (v) {
        return C.resolve(fn()).then(function () { return v; });
      }, function (e) {
        return C.resolve(fn()).then(function () { throw e; });
      });
    });
    def(P, 'allSettled', function (items) {
      var C = this;
      return new C(function (res) {
        var out = [], n = 0, i = 0;
        forOf(items, function (item) {
          var idx = i++;
          n++;
          C.resolve(item).then(function (v) {
            out[idx] = {status: 'fulfilled', value: v};
            if (--n === 0) res(out);
          }, function (r) {
            out[idx] = {status: 'rejected', reason: r};
            if (--n === 0) res(out);
          });
        });
        if (n === 0) res(out);
      });
    });
    if (!W.AggregateError) {
      W.AggregateError = function AggregateError(errors, msg) {
        var e = new Error(msg);
        e.name = 'AggregateError';
        e.errors = Array.from(errors);
        return e;
      };
    }
    def(P, 'any', function (items) {
      var C = this;
      return new C(function (res, rej) {
        var errs = [], n = 0, i = 0;
        forOf(items, function (item) {
          var idx = i++;
          n++;
          C.resolve(item).then(res, function (r) {
            errs[idx] = r;
            if (--n === 0) rej(new W.AggregateError(errs, 'All promises were rejected'));
          });
        });
        if (n === 0) rej(new W.AggregateError([], 'All promises were rejected'));
      });
    });
    def(P, 'withResolvers', function () {
      var out = {};
      out.promise = new this(function (res, rej) { out.resolve = res; out.reject = rej; });
      return out;
    });
    def(P, 'try', function (fn) {
      var args = AP.slice.call(arguments, 1);
      return new this(function (res) { res(fn.apply(undefined, args)); });
    });
  })(W.Promise);

  /* ---------------------------------------------------------------- Reflect */
  if (!W.Reflect) {
    var R = {
      apply: function (f, t, a) { return FP.apply.call(f, t, a); },
      construct: function (C, args, NT) {
        var proto = (NT || C).prototype;
        var obj = Object.create(proto && typeof proto === 'object' ? proto : OP);
        var res = FP.apply.call(C, obj, args);
        return res !== null && (typeof res === 'object' || typeof res === 'function') ? res : obj;
      },
      defineProperty: function (o, k, d) { try { Object.defineProperty(o, k, d); return true; } catch (e) { return false; } },
      deleteProperty: function (o, k) { return delete o[k]; },
      get: function (o, k, r) {
        var d = Object.getOwnPropertyDescriptor(o, k);
        if (!d) { var p = Object.getPrototypeOf(o); return p === null ? undefined : R.get(p, k, arguments.length > 2 ? r : o); }
        if ('value' in d) return d.value;
        return d.get ? d.get.call(arguments.length > 2 ? r : o) : undefined;
      },
      getOwnPropertyDescriptor: Object.getOwnPropertyDescriptor,
      getPrototypeOf: Object.getPrototypeOf,
      has: function (o, k) { return k in o; },
      isExtensible: Object.isExtensible,
      ownKeys: function (o) { return Object.getOwnPropertyNames(o).concat(Object.getOwnPropertySymbols ? Object.getOwnPropertySymbols(o) : []); },
      preventExtensions: function (o) { Object.preventExtensions(o); return true; },
      set: function (o, k, v) { try { o[k] = v; return true; } catch (e) { return false; } },
      setPrototypeOf: function (o, p) { try { Object.setPrototypeOf(o, p); return true; } catch (e) { return false; } }
    };
    // Tells Babel/TypeScript helpers that construct() cannot create real subclasses of natives.
    R.construct.sham = true;
    W.Reflect = R;
  }

  /* ---------------------------------------------------------------- misc globals */
  def(W, 'requestIdleCallback', function (cb, opts) {
    var start = Date.now();
    return setTimeout(function () {
      cb({didTimeout: false, timeRemaining: function () { return Math.max(0, 50 - (Date.now() - start)); }});
    }, opts && opts.timeout ? Math.min(opts.timeout, 50) : 1);
  });
  def(W, 'cancelIdleCallback', function (id) { clearTimeout(id); });
  if (W.crypto && W.crypto.getRandomValues) {
    def(W.crypto, 'randomUUID', function () {
      var b = W.crypto.getRandomValues(new Uint8Array(16));
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      var h = [];
      for (var i = 0; i < 16; i++) h.push((b[i] + 0x100).toString(16).slice(1));
      return h.slice(0, 4).join('') + '-' + h.slice(4, 6).join('') + '-' + h.slice(6, 8).join('') + '-'
        + h.slice(8, 10).join('') + '-' + h.slice(10).join('');
    });
  }
  def(W, 'structuredClone', function (v) {
    var seen = new W.Map();
    function clone(x) {
      if (x === null || typeof x !== 'object') {
        if (typeof x === 'function') throw new Error('DataCloneError: function could not be cloned');
        return x;
      }
      if (seen.has(x)) return seen.get(x);
      var out, i;
      if (x instanceof Date) out = new Date(x.getTime());
      else if (x instanceof RegExp) out = new RegExp(x.source, x.flags);
      else if (W.Blob && x instanceof W.Blob) out = x;
      else if (W.ArrayBuffer && x instanceof W.ArrayBuffer) out = x.slice(0);
      else if (W.ArrayBuffer && W.ArrayBuffer.isView && W.ArrayBuffer.isView(x) && x.constructor.from) out = x.slice();
      else if (x instanceof W.Map) {
        out = new W.Map();
        seen.set(x, out);
        x.forEach(function (val, key) { out.set(clone(key), clone(val)); });
        return out;
      } else if (x instanceof W.Set) {
        out = new W.Set();
        seen.set(x, out);
        x.forEach(function (val) { out.add(clone(val)); });
        return out;
      } else if (Array.isArray(x)) {
        out = [];
        seen.set(x, out);
        for (i = 0; i < x.length; i++) out[i] = clone(x[i]);
        return out;
      } else {
        out = {};
        seen.set(x, out);
        var keys = Object.keys(x);
        for (i = 0; i < keys.length; i++) out[keys[i]] = clone(x[keys[i]]);
        return out;
      }
      seen.set(x, out);
      return out;
    }
    return clone(v);
  });

  /* ---------------------------------------------------------------- TextEncoder / TextDecoder */
  if (!W.TextEncoder) {
    W.TextEncoder = function TextEncoder() { this.encoding = 'utf-8'; };
    W.TextEncoder.prototype.encode = function (s) {
      s = s === undefined ? '' : String(s);
      var bin = unescape(encodeURIComponent(s));
      var out = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    };
    W.TextEncoder.prototype.encodeInto = function (s, dest) {
      var b = this.encode(s), n = Math.min(b.length, dest.length);
      for (var i = 0; i < n; i++) dest[i] = b[i];
      return {read: s.length, written: n};
    };
  }
  if (!W.TextDecoder) {
    W.TextDecoder = function TextDecoder(label) {
      this.encoding = (label || 'utf-8').toLowerCase();
      this.fatal = false;
      this.ignoreBOM = false;
    };
    W.TextDecoder.prototype.decode = function (buf) {
      if (!buf) return '';
      var bytes = buf instanceof Uint8Array ? buf : buf.buffer ? new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength) : new Uint8Array(buf);
      var out = '', i = 0, n = bytes.length;
      if (this.encoding === 'utf-16le' || this.encoding === 'utf-16') {
        for (; i + 1 < n; i += 2) out += String.fromCharCode(bytes[i] | (bytes[i + 1] << 8));
        return out;
      }
      if (this.encoding !== 'utf-8' && this.encoding !== 'utf8') {
        for (; i < n; i++) out += String.fromCharCode(bytes[i]);
        return out;
      }
      if (n >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) i = 3;
      var chunk = [];
      while (i < n) {
        var c = bytes[i++], cp;
        if (c < 0x80) cp = c;
        else if (c >= 0xC0 && c < 0xE0 && i < n) cp = ((c & 0x1F) << 6) | (bytes[i++] & 0x3F);
        else if (c >= 0xE0 && c < 0xF0 && i + 1 < n) { cp = ((c & 0x0F) << 12) | ((bytes[i] & 0x3F) << 6) | (bytes[i + 1] & 0x3F); i += 2; }
        else if (c >= 0xF0 && i + 2 < n) {
          cp = ((c & 0x07) << 18) | ((bytes[i] & 0x3F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
          i += 3;
        } else cp = 0xFFFD;
        if (cp > 0xFFFF) {
          cp -= 0x10000;
          chunk.push(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
        } else chunk.push(cp);
        if (chunk.length > 8000) { out += String.fromCharCode.apply(null, chunk); chunk = []; }
      }
      return out + String.fromCharCode.apply(null, chunk);
    };
  }

  /* ---------------------------------------------------------------- Blob helpers */
  function readBlob(blob, how) {
    return new W.Promise(function (res, rej) {
      var r = new FileReader();
      r.onload = function () { res(r.result); };
      r.onerror = function () { rej(r.error); };
      if (how === 'text') r.readAsText(blob);
      else r.readAsArrayBuffer(blob);
    });
  }
  if (W.Blob) {
    def(W.Blob.prototype, 'text', function () { return readBlob(this, 'text'); });
    def(W.Blob.prototype, 'arrayBuffer', function () { return readBlob(this, 'buffer'); });
  }

  /* ---------------------------------------------------------------- EventTarget & listener options */
  var ctorOk = (function () { try { new W.EventTarget(); return true; } catch (e) { return false; } })();
  if (!ctorOk) {
    var ET = function EventTarget() { Object.defineProperty(this, '_l', {value: {}, enumerable: false}); };
    ET.prototype.addEventListener = function (t, fn, opts) {
      if (!fn) return;
      var l = this._l[t] || (this._l[t] = []);
      for (var i = 0; i < l.length; i++) if (l[i].fn === fn) return;
      l.push({fn: fn, once: !!(opts && typeof opts === 'object' && opts.once)});
    };
    ET.prototype.removeEventListener = function (t, fn) {
      var l = this._l[t];
      if (!l) return;
      for (var i = 0; i < l.length; i++) if (l[i].fn === fn) { l.splice(i, 1); return; }
    };
    ET.prototype.dispatchEvent = function (e) {
      var l = (this._l[e.type] || []).slice();
      try { Object.defineProperty(e, 'target', {value: this, configurable: true}); } catch (x) { /* ignore */ }
      try { Object.defineProperty(e, 'currentTarget', {value: this, configurable: true}); } catch (x) { /* ignore */ }
      for (var i = 0; i < l.length; i++) {
        if (l[i].once) this.removeEventListener(e.type, l[i].fn);
        var f = l[i].fn;
        if (isFn(f)) f.call(this, e);
        else if (f && isFn(f.handleEvent)) f.handleEvent(e);
      }
      var on = this['on' + e.type];
      if (isFn(on)) on.call(this, e);
      return !e.defaultPrevented;
    };
    // Keep instanceof working for DOM nodes where the old engine has a native EventTarget prototype.
    var nativeET = W.EventTarget;
    if (nativeET && nativeET.prototype) {
      try {
        Object.defineProperty(ET, Symbol.hasInstance, {value: function (o) { return o instanceof nativeET || ET.prototype.isPrototypeOf(o); }});
      } catch (e) { /* ignore */ }
    }
    W.EventTarget = ET;
  }
  (function () {
    var supported = false;
    try {
      var o = Object.defineProperty({}, 'passive', {get: function () { supported = true; return false; }});
      W.addEventListener('bl-test', noop, o);
      W.removeEventListener('bl-test', noop, o);
    } catch (e) { /* ignore */ }
    if (supported) return;
    var KEY = '__bl_once';
    function patch(proto) {
      if (!proto || !hasOwn.call(proto, 'addEventListener') || proto.addEventListener.__bl) return;
      var add = proto.addEventListener, remove = proto.removeEventListener;
      var wrappedAdd = function (type, fn, opts) {
        if (opts === null || typeof opts !== 'object') return add.call(this, type, fn, opts);
        var capture = !!opts.capture;
        if (!fn) return undefined;
        var self = this;
        if (opts.signal && opts.signal.aborted) return undefined;
        var real = fn;
        if (opts.once) {
          var map = fn[KEY] || (fn !== Object(fn) ? null : (function () {
            try { Object.defineProperty(fn, KEY, {value: [], enumerable: false}); } catch (e) { return null; }
            return fn[KEY];
          })());
          real = function (e) {
            remove.call(self, type, real, capture);
            return isFn(fn) ? fn.call(this, e) : fn.handleEvent(e);
          };
          if (map) map.push({t: this, type: type, c: capture, w: real});
        }
        add.call(this, type, real, capture);
        if (opts.signal && opts.signal.addEventListener) {
          opts.signal.addEventListener('abort', function () { remove.call(self, type, real, capture); });
        }
        return undefined;
      };
      var wrappedRemove = function (type, fn, opts) {
        var capture = opts !== null && typeof opts === 'object' ? !!opts.capture : !!opts;
        var map = fn && fn[KEY];
        if (map) {
          for (var i = map.length - 1; i >= 0; i--) {
            if (map[i].t === this && map[i].type === type && map[i].c === capture) {
              remove.call(this, type, map[i].w, capture);
              map.splice(i, 1);
            }
          }
        }
        return remove.call(this, type, fn, capture);
      };
      wrappedAdd.__bl = true;
      try {
        Object.defineProperty(proto, 'addEventListener', {value: wrappedAdd, writable: true, configurable: true});
        Object.defineProperty(proto, 'removeEventListener', {value: wrappedRemove, writable: true, configurable: true});
      } catch (e) {
        proto.addEventListener = wrappedAdd;
        proto.removeEventListener = wrappedRemove;
      }
    }
    // Old Blink keeps addEventListener on a hidden EventTarget prototype: find it from real instances.
    function owner(obj) {
      var p = obj;
      while (p) {
        if (hasOwn.call(p, 'addEventListener')) return p;
        p = Object.getPrototypeOf(p);
      }
      return null;
    }
    var samples = [W, D, D.createElement('div'), D.createTextNode(''), D.createDocumentFragment()];
    try { samples.push(new XMLHttpRequest()); } catch (e) { /* ignore */ }
    try { samples.push(new FileReader()); } catch (e) { /* ignore */ }
    try { samples.push(D.createElementNS('http://www.w3.org/2000/svg', 'svg')); } catch (e) { /* ignore */ }
    for (var si = 0; si < samples.length; si++) patch(owner(samples[si]));
    var targets = [W, D, W.Window && W.Window.prototype, W.Node && W.Node.prototype, W.Element && W.Element.prototype,
      W.HTMLElement && W.HTMLElement.prototype, W.Document && W.Document.prototype,
      W.HTMLDocument && W.HTMLDocument.prototype, W.EventTarget && W.EventTarget.prototype,
      W.XMLHttpRequest && W.XMLHttpRequest.prototype, W.XMLHttpRequestUpload && W.XMLHttpRequestUpload.prototype,
      W.XMLHttpRequestEventTarget && W.XMLHttpRequestEventTarget.prototype, W.FileReader && W.FileReader.prototype,
      W.WebSocket && W.WebSocket.prototype, W.Worker && W.Worker.prototype, W.MessagePort && W.MessagePort.prototype,
      W.SVGElement && W.SVGElement.prototype, W.HTMLMediaElement && W.HTMLMediaElement.prototype,
      W.TextTrack && W.TextTrack.prototype, W.EventSource && W.EventSource.prototype,
      W.IDBRequest && W.IDBRequest.prototype, W.IDBDatabase && W.IDBDatabase.prototype,
      W.IDBTransaction && W.IDBTransaction.prototype, W.Notification && W.Notification.prototype];
    for (var i = 0; i < targets.length; i++) patch(targets[i]);
  })();

  /* ---------------------------------------------------------------- AbortController */
  if (!W.AbortController) {
    var makeAbortError = function (msg) {
      var e;
      try { e = new W.DOMException(msg || 'signal is aborted without reason', 'AbortError'); } catch (x) { e = new Error(msg || 'The operation was aborted'); }
      try { e.name = 'AbortError'; } catch (x) { /* read-only */ }
      return e;
    };
    var AbortSignal = function AbortSignal() {
      this.aborted = false;
      this.reason = undefined;
      this.onabort = null;
      this._l = [];
    };
    AbortSignal.prototype.addEventListener = function (t, fn) { if (t === 'abort' && fn) this._l.push(fn); };
    AbortSignal.prototype.removeEventListener = function (t, fn) {
      var i = this._l.indexOf(fn);
      if (i >= 0) this._l.splice(i, 1);
    };
    AbortSignal.prototype.dispatchEvent = function (e) {
      var l = this._l.slice();
      for (var i = 0; i < l.length; i++) {
        if (isFn(l[i])) l[i].call(this, e);
        else if (l[i] && isFn(l[i].handleEvent)) l[i].handleEvent(e);
      }
      if (isFn(this.onabort)) this.onabort(e);
      return true;
    };
    AbortSignal.prototype.throwIfAborted = function () { if (this.aborted) throw this.reason; };
    var AbortController = function AbortController() { this.signal = new AbortSignal(); };
    AbortController.prototype.abort = function (reason) {
      var s = this.signal;
      if (s.aborted) return;
      s.aborted = true;
      s.reason = reason !== undefined ? reason : makeAbortError();
      s.dispatchEvent({type: 'abort', target: s});
    };
    AbortSignal.abort = function (reason) { var c = new AbortController(); c.abort(reason); return c.signal; };
    AbortSignal.timeout = function (ms) {
      var c = new AbortController();
      setTimeout(function () { c.abort(makeAbortError('signal timed out')); }, ms);
      return c.signal;
    };
    AbortSignal.any = function (signals) {
      var c = new AbortController();
      forOf(signals, function (s) {
        if (s.aborted) c.abort(s.reason);
        else s.addEventListener('abort', function () { c.abort(s.reason); });
      });
      return c.signal;
    };
    W.AbortController = AbortController;
    W.AbortSignal = AbortSignal;
    W.__blAbortError = makeAbortError;
  }

  /* ---------------------------------------------------------------- URLSearchParams / URL */
  function encodeForm(s) {
    return encodeURIComponent(s).replace(/[!'()~]/g, function (c) {
      return '%' + c.charCodeAt(0).toString(16).toUpperCase();
    }).replace(/%20/g, '+');
  }
  function decodeForm(s) {
    s = s.replace(/\+/g, ' ');
    try { return decodeURIComponent(s); } catch (e) { return s; }
  }
  var uspOk = (function () {
    try { return new W.URLSearchParams('a=1&b=2').get('b') === '2' && isFn(W.URLSearchParams.prototype.forEach); } catch (e) { return false; }
  })();
  var USP = W.URLSearchParams;
  if (!uspOk) {
    USP = function URLSearchParams(init) {
      if (!(this instanceof USP)) throw new TypeError("Failed to construct 'URLSearchParams'");
      this._p = [];
      this._url = null;
      if (init == null || init === '') return;
      if (init instanceof USP) { this._p = init._p.slice(); return; }
      if (typeof init === 'object') {
        var self = this;
        if (isFn(init[SymIter]) || Array.isArray(init)) {
          forOf(init, function (pair) {
            var a = Array.from(pair);
            if (a.length !== 2) throw new TypeError('Each query pair must be an iterable [name, value] tuple');
            self._p.push([String(a[0]), String(a[1])]);
          });
        } else {
          for (var k in init) if (hasOwn.call(init, k)) this._p.push([k, String(init[k])]);
        }
        return;
      }
      this._parse(String(init));
    };
    USP.prototype._parse = function (s) {
      this._p = [];
      if (s.charAt(0) === '?') s = s.slice(1);
      var parts = s.split('&');
      for (var i = 0; i < parts.length; i++) {
        if (!parts[i]) continue;
        var eq = parts[i].indexOf('=');
        this._p.push(eq < 0 ? [decodeForm(parts[i]), ''] : [decodeForm(parts[i].slice(0, eq)), decodeForm(parts[i].slice(eq + 1))]);
      }
    };
    USP.prototype._update = function () {
      if (this._url) this._url._setSearch(this.toString());
    };
    USP.prototype.append = function (n, v) { this._p.push([String(n), String(v)]); this._update(); };
    USP.prototype['delete'] = function (n, v) {
      n = String(n);
      for (var i = this._p.length - 1; i >= 0; i--) {
        if (this._p[i][0] === n && (v === undefined || this._p[i][1] === String(v))) this._p.splice(i, 1);
      }
      this._update();
    };
    USP.prototype.get = function (n) {
      n = String(n);
      for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === n) return this._p[i][1];
      return null;
    };
    USP.prototype.getAll = function (n) {
      n = String(n);
      var out = [];
      for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === n) out.push(this._p[i][1]);
      return out;
    };
    USP.prototype.has = function (n, v) {
      n = String(n);
      for (var i = 0; i < this._p.length; i++) {
        if (this._p[i][0] === n && (v === undefined || this._p[i][1] === String(v))) return true;
      }
      return false;
    };
    USP.prototype.set = function (n, v) {
      n = String(n);
      v = String(v);
      var found = false;
      for (var i = 0; i < this._p.length; i++) {
        if (this._p[i][0] !== n) continue;
        if (found) { this._p.splice(i--, 1); } else { this._p[i][1] = v; found = true; }
      }
      if (!found) this._p.push([n, v]);
      this._update();
    };
    USP.prototype.sort = function () {
      var p = this._p.map(function (x, i) { return [x, i]; });
      p.sort(function (a, b) { return a[0][0] < b[0][0] ? -1 : a[0][0] > b[0][0] ? 1 : a[1] - b[1]; });
      this._p = p.map(function (x) { return x[0]; });
      this._update();
    };
    USP.prototype.forEach = function (cb, thisArg) {
      for (var i = 0; i < this._p.length; i++) cb.call(thisArg, this._p[i][1], this._p[i][0], this);
    };
    USP.prototype.toString = function () {
      var out = [];
      for (var i = 0; i < this._p.length; i++) out.push(encodeForm(this._p[i][0]) + '=' + encodeForm(this._p[i][1]));
      return out.join('&');
    };
    USP.prototype.keys = function () { return new ArrayIterator(this._p.map(function (p) { return p[0]; }), 0); };
    USP.prototype.values = function () { return new ArrayIterator(this._p.map(function (p) { return p[1]; }), 0); };
    USP.prototype.entries = function () { return new ArrayIterator(this._p.map(function (p) { return [p[0], p[1]]; }), 0); };
    USP.prototype[SymIter] = USP.prototype.entries;
    Object.defineProperty(USP.prototype, 'size', {get: function () { return this._p.length; }, configurable: true});
    W.URLSearchParams = USP;
  }

  var urlOk = (function () {
    try {
      var u = new W.URL('b?x=1', 'http://a.com/p/');
      return u.href === 'http://a.com/p/b?x=1' && !!u.searchParams && u.searchParams.get('x') === '1';
    } catch (e) { return false; }
  })();
  if (!urlOk) {
    var NativeURL = W.URL || W.webkitURL;
    var parser = D.implementation.createHTMLDocument('');
    var baseEl = parser.createElement('base');
    parser.head.appendChild(baseEl);
    var anchor = parser.createElement('a');
    parser.body.appendChild(anchor);
    var absRe = /^[a-zA-Z][a-zA-Z0-9+.\-]*:/;
    var defaultPorts = {'http:': '80', 'https:': '443', 'ws:': '80', 'wss:': '443', 'ftp:': '21'};
    var URLPoly = function URL(url, base) {
      if (!(this instanceof URLPoly)) throw new TypeError("Failed to construct 'URL'");
      url = String(url);
      if (base !== undefined) {
        base = String(base instanceof URLPoly ? base.href : base);
        if (!absRe.test(base)) throw new TypeError("Failed to construct 'URL': Invalid base URL");
      } else if (!absRe.test(url)) {
        throw new TypeError("Failed to construct 'URL': Invalid URL");
      }
      baseEl.href = base !== undefined ? base : url;
      anchor.href = url;
      var href = anchor.href;
      if (!href || !absRe.test(href)) throw new TypeError("Failed to construct 'URL': Invalid URL");
      this._set(href);
      var sp = new USP(this._search);
      sp._url = this;
      Object.defineProperty(this, '_sp', {value: sp, enumerable: false, writable: true});
    };
    URLPoly.prototype._set = function (href) {
      var m = /^([a-zA-Z][a-zA-Z0-9+.\-]*:)(\/\/(?:([^:@\/]*)(?::([^@\/]*))?@)?(\[[^\]]*\]|[^:\/?#]*)(?::(\d*))?)?([^?#]*)(\?[^#]*)?(#.*)?$/.exec(href);
      if (!m) throw new TypeError('Invalid URL');
      this._protocol = m[1].toLowerCase();
      this._slashes = !!m[2];
      this._username = m[3] || '';
      this._password = m[4] || '';
      this._hostname = (m[5] || '').toLowerCase();
      this._port = m[6] && m[6] !== defaultPorts[this._protocol] ? m[6] : '';
      this._pathname = m[7] || (this._slashes ? '/' : '');
      this._search = m[8] && m[8] !== '?' ? m[8] : '';
      this._hash = m[9] && m[9] !== '#' ? m[9] : '';
    };
    URLPoly.prototype._setSearch = function (s) { this._search = s ? '?' + s : ''; };
    URLPoly.prototype.toString = function () { return this.href; };
    URLPoly.prototype.toJSON = function () { return this.href; };
    var props = {
      href: {
        get: function () {
          var auth = this._username ? this._username + (this._password ? ':' + this._password : '') + '@' : '';
          return this._protocol + (this._slashes ? '//' + auth + this.host : '') + this._pathname + this._search + this._hash;
        },
        set: function (v) {
          this._set(new URLPoly(v).href);
          this._sp._parse(this._search);
        }
      },
      origin: {get: function () { return this._slashes && this._hostname ? this._protocol + '//' + this.host : 'null'; }},
      protocol: {get: function () { return this._protocol; }, set: function (v) { v = String(v); this._protocol = (v.slice(-1) === ':' ? v : v + ':').toLowerCase(); }},
      username: {get: function () { return this._username; }, set: function (v) { this._username = encodeURIComponent(v); }},
      password: {get: function () { return this._password; }, set: function (v) { this._password = encodeURIComponent(v); }},
      host: {
        get: function () { return this._hostname + (this._port ? ':' + this._port : ''); },
        set: function (v) {
          var p = String(v).split(':');
          this._hostname = p[0].toLowerCase();
          this._port = p[1] && p[1] !== defaultPorts[this._protocol] ? p[1] : '';
        }
      },
      hostname: {get: function () { return this._hostname; }, set: function (v) { this._hostname = String(v).toLowerCase(); }},
      port: {get: function () { return this._port; }, set: function (v) { v = String(v); this._port = v === defaultPorts[this._protocol] ? '' : v; }},
      pathname: {
        get: function () { return this._pathname; },
        set: function (v) {
          v = String(v);
          this._pathname = (v.charAt(0) === '/' || !this._slashes ? '' : '/') + v.replace(/[ "<>`#?{}]/g, function (c) { return encodeURIComponent(c); });
        }
      },
      search: {
        get: function () { return this._search; },
        set: function (v) {
          v = String(v);
          if (v.charAt(0) === '?') v = v.slice(1);
          this._search = v ? '?' + v : '';
          this._sp._parse(this._search);
        }
      },
      searchParams: {get: function () { return this._sp; }},
      hash: {
        get: function () { return this._hash; },
        set: function (v) {
          v = String(v);
          if (v.charAt(0) === '#') v = v.slice(1);
          this._hash = v ? '#' + v : '';
        }
      }
    };
    for (var pk in props) {
      props[pk].configurable = true;
      props[pk].enumerable = true;
    }
    Object.defineProperties(URLPoly.prototype, props);
    if (NativeURL) {
      if (NativeURL.createObjectURL) URLPoly.createObjectURL = function (b) { return NativeURL.createObjectURL(b); };
      if (NativeURL.revokeObjectURL) URLPoly.revokeObjectURL = function (u) { return NativeURL.revokeObjectURL(u); };
    }
    URLPoly.canParse = function (u, b) { try { new URLPoly(u, b); return true; } catch (e) { return false; } };
    URLPoly.parse = function (u, b) { try { return new URLPoly(u, b); } catch (e) { return null; } };
    W.URL = URLPoly;
  } else {
    def(W.URL, 'canParse', function (u, b) { try { new W.URL(u, b); return true; } catch (e) { return false; } });
  }

  /* ---------------------------------------------------------------- FormData */
  if (W.FormData && !W.FormData.prototype.get) {
    var NativeFD = W.FormData;
    var fdValue = function (v, filename) {
      if (W.Blob && v instanceof W.Blob) {
        if (filename !== undefined || !v.name) {
          try { v = new File([v], filename !== undefined ? String(filename) : 'blob', {type: v.type}); } catch (e) { try { v.name = filename || 'blob'; } catch (e2) { /* ignore */ } }
        }
        return v;
      }
      return String(v);
    };
    var FDPoly = function FormData(form) {
      this._e = [];
      if (!form) return;
      var els = form.elements || [];
      for (var i = 0; i < els.length; i++) {
        var el = els[i], name = el.name, type = (el.type || '').toLowerCase();
        if (!name || el.disabled || type === 'submit' || type === 'button' || type === 'reset' || type === 'image') continue;
        if (el.closest && el.closest('fieldset[disabled]')) continue;
        if ((type === 'checkbox' || type === 'radio') && !el.checked) continue;
        if (type === 'file') {
          if (el.files && el.files.length) for (var f = 0; f < el.files.length; f++) this._e.push([name, el.files[f]]);
          else this._e.push([name, new File([], '', {type: 'application/octet-stream'})]);
          continue;
        }
        if (el.tagName === 'SELECT') {
          for (var o = 0; o < el.options.length; o++) if (el.options[o].selected && !el.options[o].disabled) this._e.push([name, el.options[o].value]);
          continue;
        }
        this._e.push([name, el.value]);
      }
    };
    FDPoly.prototype.append = function (n, v, fn) { this._e.push([String(n), fdValue(v, fn)]); };
    FDPoly.prototype['delete'] = function (n) {
      n = String(n);
      for (var i = this._e.length - 1; i >= 0; i--) if (this._e[i][0] === n) this._e.splice(i, 1);
    };
    FDPoly.prototype.get = function (n) {
      n = String(n);
      for (var i = 0; i < this._e.length; i++) if (this._e[i][0] === n) return this._e[i][1];
      return null;
    };
    FDPoly.prototype.getAll = function (n) {
      n = String(n);
      var out = [];
      for (var i = 0; i < this._e.length; i++) if (this._e[i][0] === n) out.push(this._e[i][1]);
      return out;
    };
    FDPoly.prototype.has = function (n) { return this.get(n) !== null; };
    FDPoly.prototype.set = function (n, v, fn) {
      n = String(n);
      var val = fdValue(v, fn), found = false;
      for (var i = 0; i < this._e.length; i++) {
        if (this._e[i][0] !== n) continue;
        if (found) this._e.splice(i--, 1);
        else { this._e[i][1] = val; found = true; }
      }
      if (!found) this._e.push([n, val]);
    };
    FDPoly.prototype.forEach = function (cb, thisArg) {
      for (var i = 0; i < this._e.length; i++) cb.call(thisArg, this._e[i][1], this._e[i][0], this);
    };
    FDPoly.prototype.keys = function () { return new ArrayIterator(this._e.map(function (e) { return e[0]; }), 0); };
    FDPoly.prototype.values = function () { return new ArrayIterator(this._e.map(function (e) { return e[1]; }), 0); };
    FDPoly.prototype.entries = function () { return new ArrayIterator(this._e.slice(), 0); };
    FDPoly.prototype[SymIter] = FDPoly.prototype.entries;
    FDPoly.prototype._native = function () {
      var fd = new NativeFD();
      for (var i = 0; i < this._e.length; i++) {
        var v = this._e[i][1];
        if (W.Blob && v instanceof W.Blob) fd.append(this._e[i][0], v, v.name);
        else fd.append(this._e[i][0], v);
      }
      return fd;
    };
    W.FormData = FDPoly;
    var xhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function (body) {
      return xhrSend.call(this, body instanceof FDPoly ? body._native() : body);
    };
  }

  /* ---------------------------------------------------------------- fetch */
  if (!W.fetch) {
    var normName = function (n) {
      n = String(n);
      if (/[^a-z0-9\-#$%&'*+.^_`|~!]/i.test(n) || n === '') throw new TypeError('Invalid character in header field name: "' + n + '"');
      return n.toLowerCase();
    };
    var Headers = function Headers(init) {
      this._m = {};
      var self = this;
      if (init instanceof Headers) init.forEach(function (v, n) { self.append(n, v); });
      else if (Array.isArray(init)) init.forEach(function (h) { self.append(h[0], h[1]); });
      else if (init) Object.getOwnPropertyNames(init).forEach(function (n) { self.append(n, init[n]); });
    };
    Headers.prototype.append = function (n, v) {
      n = normName(n);
      v = String(v);
      this._m[n] = hasOwn.call(this._m, n) ? this._m[n] + ', ' + v : v;
    };
    Headers.prototype['delete'] = function (n) { delete this._m[normName(n)]; };
    Headers.prototype.get = function (n) { n = normName(n); return hasOwn.call(this._m, n) ? this._m[n] : null; };
    Headers.prototype.has = function (n) { return hasOwn.call(this._m, normName(n)); };
    Headers.prototype.set = function (n, v) { this._m[normName(n)] = String(v); };
    Headers.prototype.forEach = function (cb, thisArg) {
      var keys = Object.keys(this._m).sort();
      for (var i = 0; i < keys.length; i++) cb.call(thisArg, this._m[keys[i]], keys[i], this);
    };
    Headers.prototype.keys = function () { var a = []; this.forEach(function (v, n) { a.push(n); }); return new ArrayIterator(a, 0); };
    Headers.prototype.values = function () { var a = []; this.forEach(function (v) { a.push(v); }); return new ArrayIterator(a, 0); };
    Headers.prototype.entries = function () { var a = []; this.forEach(function (v, n) { a.push([n, v]); }); return new ArrayIterator(a, 0); };
    Headers.prototype[SymIter] = Headers.prototype.entries;
    Headers.prototype.getSetCookie = function () { return []; };

    var Body = function () {};
    Body.prototype._init = function (body) {
      this.bodyUsed = false;
      this._body = body;
      if (body == null) this._text = '';
      else if (typeof body === 'string') this._text = body;
      else if (W.Blob && body instanceof W.Blob) this._blob = body;
      else if (W.FormData && body instanceof W.FormData) this._form = body;
      else if (body instanceof USP) this._text = body.toString();
      else if (W.ArrayBuffer && (body instanceof W.ArrayBuffer || W.ArrayBuffer.isView(body))) this._buf = body;
      else this._text = String(body);
      if (!this.headers.get('content-type')) {
        if (typeof body === 'string') this.headers.set('content-type', 'text/plain;charset=UTF-8');
        else if (this._blob && this._blob.type) this.headers.set('content-type', this._blob.type);
        else if (body instanceof USP) this.headers.set('content-type', 'application/x-www-form-urlencoded;charset=UTF-8');
      }
    };
    Body.prototype._consume = function () {
      if (this.bodyUsed) return W.Promise.reject(new TypeError('Already read'));
      this.bodyUsed = true;
      return null;
    };
    Body.prototype.blob = function () {
      var r = this._consume();
      if (r) return r;
      if (this._blob) return W.Promise.resolve(this._blob);
      if (this._buf) return W.Promise.resolve(new Blob([this._buf]));
      return W.Promise.resolve(new Blob([this._text || '']));
    };
    Body.prototype.arrayBuffer = function () {
      var r = this._consume();
      if (r) return r;
      if (this._buf) return W.Promise.resolve(this._buf.buffer ? this._buf.buffer.slice(this._buf.byteOffset, this._buf.byteOffset + this._buf.byteLength) : this._buf);
      if (this._blob) return readBlob(this._blob, 'buffer');
      return W.Promise.resolve(new W.TextEncoder().encode(this._text || '').buffer);
    };
    Body.prototype.bytes = function () { return this.arrayBuffer().then(function (b) { return new Uint8Array(b); }); };
    Body.prototype.text = function () {
      var r = this._consume();
      if (r) return r;
      if (this._blob) return readBlob(this._blob, 'text');
      if (this._buf) return W.Promise.resolve(new W.TextDecoder().decode(this._buf));
      if (this._form) return W.Promise.reject(new TypeError('could not read FormData body as text'));
      return W.Promise.resolve(this._text);
    };
    Body.prototype.json = function () { return this.text().then(JSON.parse); };
    Body.prototype.formData = function () {
      return this.text().then(function (t) {
        var fd = new W.FormData();
        new USP(t).forEach(function (v, k) { fd.append(k, v); });
        return fd;
      });
    };

    var Request = function Request(input, init) {
      if (!(this instanceof Request)) throw new TypeError("Failed to construct 'Request'");
      init = init || {};
      var body = init.body;
      if (input instanceof Request) {
        this.url = input.url;
        this.credentials = input.credentials;
        this.headers = new Headers(input.headers);
        this.method = input.method;
        this.mode = input.mode;
        this.signal = input.signal;
        if (body == null && input._body != null) body = input._body;
      } else {
        this.url = String(input);
      }
      try { this.url = new W.URL(this.url, location.href).href; } catch (e) { /* keep */ }
      this.credentials = init.credentials || this.credentials || 'same-origin';
      if (init.headers || !this.headers) this.headers = new Headers(init.headers);
      this.method = String(init.method || this.method || 'GET').toUpperCase();
      this.mode = init.mode || this.mode || 'cors';
      this.signal = init.signal || this.signal || null;
      this.cache = init.cache || 'default';
      this.redirect = init.redirect || 'follow';
      this.referrer = 'about:client';
      if ((this.method === 'GET' || this.method === 'HEAD') && body != null) throw new TypeError('Body not allowed for GET or HEAD requests');
      this._init(body);
    };
    Request.prototype = Object.create(Body.prototype);
    Request.prototype.constructor = Request;
    Request.prototype.clone = function () { return new Request(this, {body: this._body}); };

    var Response = function Response(body, init) {
      if (!(this instanceof Response)) throw new TypeError("Failed to construct 'Response'");
      init = init || {};
      this.type = 'default';
      this.status = init.status === undefined ? 200 : init.status;
      this.ok = this.status >= 200 && this.status < 300;
      this.statusText = init.statusText === undefined ? '' : String(init.statusText);
      this.headers = new Headers(init.headers);
      this.url = init.url || '';
      this.redirected = false;
      this._init(body);
    };
    Response.prototype = Object.create(Body.prototype);
    Response.prototype.constructor = Response;
    Response.prototype.clone = function () {
      return new Response(this._body, {status: this.status, statusText: this.statusText, headers: new Headers(this.headers), url: this.url});
    };
    Response.error = function () {
      var r = new Response(null, {status: 0, statusText: ''});
      r.type = 'error';
      r.ok = false;
      return r;
    };
    Response.redirect = function (url, status) {
      return new Response(null, {status: status || 302, headers: {location: url}});
    };
    Response.json = function (data, init) {
      init = init || {};
      var h = new Headers(init.headers);
      if (!h.has('content-type')) h.set('content-type', 'application/json');
      return new Response(JSON.stringify(data), {status: init.status, statusText: init.statusText, headers: h});
    };

    var parseHeaders = function (raw) {
      var h = new Headers();
      String(raw || '').replace(/\r?\n[\t ]+/g, ' ').split(/\r?\n/).forEach(function (line) {
        var i = line.indexOf(':');
        if (i > 0) {
          try { h.append(line.slice(0, i).trim(), line.slice(i + 1).trim()); } catch (e) { /* skip bad header */ }
        }
      });
      return h;
    };

    W.fetch = function fetch(input, init) {
      return new W.Promise(function (resolve, reject) {
        var req = new Request(input, init);
        var abortErr = function () {
          return req.signal && req.signal.reason !== undefined ? req.signal.reason : (W.__blAbortError ? W.__blAbortError('The user aborted a request.') : new Error('Aborted'));
        };
        if (req.signal && req.signal.aborted) return reject(abortErr());
        var xhr = new XMLHttpRequest();
        var onAbort = function () { xhr.abort(); };
        xhr.onload = function () {
          var opts = {status: xhr.status === 1223 ? 204 : xhr.status, statusText: xhr.statusText,
            headers: parseHeaders(xhr.getAllResponseHeaders()), url: xhr.responseURL || req.url};
          var body = 'response' in xhr && xhr.responseType === 'blob' ? xhr.response : xhr.responseText;
          setTimeout(function () {
            var r = new Response(body, opts);
            r.redirected = !!(xhr.responseURL && xhr.responseURL !== req.url);
            resolve(r);
          }, 0);
        };
        xhr.onerror = function () { setTimeout(function () { reject(new TypeError('Failed to fetch')); }, 0); };
        xhr.ontimeout = function () { setTimeout(function () { reject(new TypeError('Failed to fetch')); }, 0); };
        xhr.onabort = function () { setTimeout(function () { reject(abortErr()); }, 0); };
        xhr.onloadend = function () { if (req.signal) req.signal.removeEventListener('abort', onAbort); };
        xhr.open(req.method, req.url, true);
        if (req.credentials === 'include') xhr.withCredentials = true;
        else if (req.credentials === 'omit') xhr.withCredentials = false;
        if ('responseType' in xhr && W.Blob) {
          try { xhr.responseType = 'blob'; } catch (e) { /* old engines */ }
        }
        req.headers.forEach(function (v, n) {
          try { xhr.setRequestHeader(n, v); } catch (e) { /* forbidden header */ }
        });
        if (req.signal) req.signal.addEventListener('abort', onAbort);
        var b = req._form || req._blob || req._buf || (req._text === '' && (req.method === 'GET' || req.method === 'HEAD') ? null : req._text);
        xhr.send(b === undefined ? null : b);
      });
    };
    W.fetch.polyfill = true;
    W.Headers = Headers;
    W.Request = Request;
    W.Response = Response;
  }

  if (W.navigator && !W.navigator.sendBeacon) {
    W.navigator.sendBeacon = function (url, data) {
      try {
        var x = new XMLHttpRequest();
        x.open('POST', url, true);
        x.withCredentials = true;
        x.send(data == null ? null : data);
        return true;
      } catch (e) { return false; }
    };
  }

  /* ---------------------------------------------------------------- DOM */
  var EP = W.Element && W.Element.prototype;
  if (EP) {
    def(EP, 'matches', EP.webkitMatchesSelector || EP.msMatchesSelector || function (sel) {
      var all = (this.ownerDocument || D).querySelectorAll(sel);
      for (var i = 0; i < all.length; i++) if (all[i] === this) return true;
      return false;
    });
    def(EP, 'closest', function (sel) {
      var el = this;
      while (el && el.nodeType === 1) {
        if (el.matches(sel)) return el;
        el = el.parentNode;
      }
      return null;
    });
    def(EP, 'getAttributeNames', function () {
      var out = [];
      for (var i = 0; i < this.attributes.length; i++) out.push(this.attributes[i].name);
      return out;
    });
    def(EP, 'toggleAttribute', function (name, force) {
      var has = this.hasAttribute(name);
      if (force === undefined ? has : !force) { this.removeAttribute(name); return false; }
      if (!has) this.setAttribute(name, '');
      return true;
    });
    def(EP, 'setPointerCapture', noop);
    def(EP, 'releasePointerCapture', noop);
    def(EP, 'hasPointerCapture', function () { return false; });
    def(EP, 'getAnimations', function () { return []; });
    def(EP, 'checkVisibility', function () { return !!(this.offsetWidth || this.offsetHeight || this.getClientRects().length); });
    def(EP, 'scrollTo', function (x, y) {
      if (x && typeof x === 'object') { y = x.top; x = x.left; }
      if (x !== undefined) this.scrollLeft = +x || 0;
      if (y !== undefined) this.scrollTop = +y || 0;
    });
    def(EP, 'scrollBy', function (x, y) {
      if (x && typeof x === 'object') { y = x.top; x = x.left; }
      this.scrollLeft += +x || 0;
      this.scrollTop += +y || 0;
    });
    def(EP, 'scroll', EP.scrollTo);
    def(EP, 'insertAdjacentElement', function (where, el) {
      where = String(where).toLowerCase();
      if (where === 'beforebegin') this.parentNode.insertBefore(el, this);
      else if (where === 'afterbegin') this.insertBefore(el, this.firstChild);
      else if (where === 'beforeend') this.appendChild(el);
      else if (where === 'afterend') this.parentNode.insertBefore(el, this.nextSibling);
      return el;
    });
  }
  function toNode(args) {
    if (args.length === 1) return typeof args[0] === 'string' || typeof args[0] === 'number' ? D.createTextNode(String(args[0])) : args[0];
    var frag = D.createDocumentFragment();
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      frag.appendChild(a instanceof W.Node ? a : D.createTextNode(String(a)));
    }
    return frag;
  }
  var childNodeTargets = [W.Element, W.CharacterData, W.DocumentType];
  for (var ci = 0; ci < childNodeTargets.length; ci++) {
    var CP = childNodeTargets[ci] && childNodeTargets[ci].prototype;
    if (!CP) continue;
    def(CP, 'remove', function () { if (this.parentNode) this.parentNode.removeChild(this); });
    def(CP, 'before', function () { if (this.parentNode) this.parentNode.insertBefore(toNode(arguments), this); });
    def(CP, 'after', function () { if (this.parentNode) this.parentNode.insertBefore(toNode(arguments), this.nextSibling); });
    def(CP, 'replaceWith', function () { if (this.parentNode) this.parentNode.replaceChild(toNode(arguments), this); });
  }
  var parentTargets = [W.Element, W.Document, W.DocumentFragment];
  for (var pi = 0; pi < parentTargets.length; pi++) {
    var PP = parentTargets[pi] && parentTargets[pi].prototype;
    if (!PP) continue;
    def(PP, 'append', function () { this.appendChild(toNode(arguments)); });
    def(PP, 'prepend', function () { this.insertBefore(toNode(arguments), this.firstChild); });
    def(PP, 'replaceChildren', function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      if (arguments.length) this.appendChild(toNode(arguments));
    });
  }
  if (W.Node) {
    getter(W.Node.prototype, 'isConnected', function () {
      var d = this.ownerDocument || this;
      return d.documentElement ? d.documentElement.contains(this) || this === d : false;
    });
    def(W.Node.prototype, 'getRootNode', function () {
      var n = this;
      while (n.parentNode) n = n.parentNode;
      return n;
    });
  }
  getter(D, 'scrollingElement', function () { return D.body || D.documentElement; });
  if (!('hidden' in D) && 'webkitHidden' in D) {
    getter(D, 'hidden', function () { return D.webkitHidden; });
    getter(D, 'visibilityState', function () { return D.webkitVisibilityState; });
    D.addEventListener('webkitvisibilitychange', function () {
      var e = D.createEvent('Event');
      e.initEvent('visibilitychange', false, false);
      D.dispatchEvent(e);
    }, false);
  }
  if (W.HTMLInputElement) def(W.HTMLInputElement.prototype, 'reportValidity', function () { return this.checkValidity(); });
  if (W.HTMLFormElement) {
    def(W.HTMLFormElement.prototype, 'reportValidity', function () { return this.checkValidity(); });
    def(W.HTMLFormElement.prototype, 'requestSubmit', function (submitter) {
      if (submitter) { submitter.click(); return; }
      var b = D.createElement('input');
      b.type = 'submit';
      b.style.display = 'none';
      this.appendChild(b);
      b.click();
      this.removeChild(b);
    });
  }
  if (W.HTMLImageElement) {
    def(W.HTMLImageElement.prototype, 'decode', function () {
      var img = this;
      return new W.Promise(function (res, rej) {
        if (img.complete && img.naturalWidth) return res();
        img.addEventListener('load', function () { res(); }, false);
        img.addEventListener('error', function () { rej(new Error('EncodingError')); }, false);
      });
    });
  }
  if (W.HTMLCanvasElement) {
    def(W.HTMLCanvasElement.prototype, 'toBlob', function (cb, type, q) {
      var url = this.toDataURL(type, q), bin = atob(url.split(',')[1]), arr = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
      var blob = new Blob([arr], {type: type || 'image/png'});
      setTimeout(function () { cb(blob); }, 0);
    });
  }
  if (W.MediaQueryList || W.matchMedia) {
    try {
      var mql = W.matchMedia('all');
      var MQP = W.MediaQueryList ? W.MediaQueryList.prototype : Object.getPrototypeOf(mql);
      if (MQP && !MQP.addEventListener) {
        MQP.addEventListener = function (t, fn) { if (t === 'change') this.addListener(fn); };
        MQP.removeEventListener = function (t, fn) { if (t === 'change') this.removeListener(fn); };
      }
    } catch (e) { /* ignore */ }
  }
  if (W.CSS) {
    def(W.CSS, 'escape', function (value) {
      var s = String(value), out = '';
      for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i);
        if (c === 0) out += '�';
        else if ((c >= 1 && c <= 31) || c === 127 || (i === 0 && c >= 48 && c <= 57) || (i === 1 && c >= 48 && c <= 57 && s.charCodeAt(0) === 45)) out += '\\' + c.toString(16) + ' ';
        else if (i === 0 && c === 45 && s.length === 1) out += '\\' + s.charAt(i);
        else if (c >= 128 || c === 45 || c === 95 || (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122)) out += s.charAt(i);
        else out += '\\' + s.charAt(i);
      }
      return out;
    });
  } else {
    W.CSS = {supports: function () { return false; }, escape: function (s) { return String(s).replace(/([^\w-])/g, '\\$1'); }};
  }

  /* window.scrollTo({top, left}) */
  if (!('scrollBehavior' in D.documentElement.style)) {
    var wrapScroll = function (orig, relative) {
      return function (x, y) {
        if (x && typeof x === 'object') {
          var o = x;
          x = o.left !== undefined ? o.left : (relative ? 0 : W.pageXOffset);
          y = o.top !== undefined ? o.top : (relative ? 0 : W.pageYOffset);
        }
        return orig.call(W, +x || 0, +y || 0);
      };
    };
    var st = W.scrollTo, sb = W.scrollBy;
    W.scrollTo = wrapScroll(st, false);
    W.scroll = W.scrollTo;
    W.scrollBy = wrapScroll(sb, true);
  }

  /* Unprefixed CSS properties for element.style (Chromium 30 only knows -webkit-transform etc.) */
  (function () {
    var style = D.documentElement.style;
    var CSD = W.CSSStyleDeclaration && W.CSSStyleDeclaration.prototype;
    if (!CSD) return;
    var names = ['transform', 'transformOrigin', 'transformStyle', 'perspective', 'perspectiveOrigin',
      'backfaceVisibility', 'userSelect', 'animation', 'animationName', 'animationDuration', 'animationDelay',
      'animationTimingFunction', 'animationIterationCount', 'animationFillMode', 'animationDirection',
      'animationPlayState', 'clipPath', 'filter', 'columnCount', 'columns', 'columnGap', 'columnWidth',
      'appearance', 'maskImage', 'hyphens', 'writingMode', 'textSizeAdjust', 'fontFeatureSettings', 'boxDecorationBreak'];
    var map = {};
    for (var i = 0; i < names.length; i++) {
      var n = names[i], pre = 'webkit' + n.charAt(0).toUpperCase() + n.slice(1);
      if (n in style || !(pre in style)) continue;
      map[n.replace(/[A-Z]/g, function (c) { return '-' + c.toLowerCase(); })] = '-webkit-' + n.replace(/[A-Z]/g, function (c) { return '-' + c.toLowerCase(); });
      (function (n, pre) {
        try {
          Object.defineProperty(CSD, n, {
            get: function () { return this[pre]; },
            set: function (v) { this[pre] = v; },
            configurable: true
          });
        } catch (e) { /* ignore */ }
      })(n, pre);
    }
    var sp = CSD.setProperty, gp = CSD.getPropertyValue, rp = CSD.removeProperty;
    CSD.setProperty = function (name, value, prio) {
      name = String(name);
      if (map[name]) name = map[name];
      return sp.call(this, name, value, prio || '');
    };
    CSD.getPropertyValue = function (name) { return gp.call(this, map[name] || name); };
    CSD.removeProperty = function (name) { return rp.call(this, map[name] || name); };
  })();

  /* KeyboardEvent.key / code */
  if (W.KeyboardEvent && !('key' in W.KeyboardEvent.prototype)) {
    var keyNames = {8: 'Backspace', 9: 'Tab', 13: 'Enter', 16: 'Shift', 17: 'Control', 18: 'Alt', 19: 'Pause',
      20: 'CapsLock', 27: 'Escape', 32: ' ', 33: 'PageUp', 34: 'PageDown', 35: 'End', 36: 'Home', 37: 'ArrowLeft',
      38: 'ArrowUp', 39: 'ArrowRight', 40: 'ArrowDown', 45: 'Insert', 46: 'Delete', 91: 'Meta', 93: 'ContextMenu',
      112: 'F1', 113: 'F2', 114: 'F3', 115: 'F4', 116: 'F5', 117: 'F6', 118: 'F7', 119: 'F8', 120: 'F9',
      121: 'F10', 122: 'F11', 123: 'F12', 144: 'NumLock', 229: 'Process'};
    getter(W.KeyboardEvent.prototype, 'key', function () {
      var c = this.keyCode || this.which;
      if (keyNames[c]) return keyNames[c];
      if (this.type === 'keypress' && this.charCode) return String.fromCharCode(this.charCode);
      var id = this.keyIdentifier;
      if (id && id.indexOf('U+') === 0) {
        var ch = String.fromCharCode(parseInt(id.slice(2), 16));
        return this.shiftKey ? ch : ch.toLowerCase();
      }
      if (c >= 48 && c <= 90) {
        var s = String.fromCharCode(c);
        return this.shiftKey ? s : s.toLowerCase();
      }
      return id || 'Unidentified';
    });
    getter(W.KeyboardEvent.prototype, 'code', function () {
      var c = this.keyCode || this.which;
      if (c >= 65 && c <= 90) return 'Key' + String.fromCharCode(c);
      if (c >= 48 && c <= 57) return 'Digit' + String.fromCharCode(c);
      return keyNames[c] === ' ' ? 'Space' : (keyNames[c] || '');
    });
  }

  /* Event.composedPath */
  if (W.Event) {
    def(W.Event.prototype, 'composedPath', function () {
      var path = [], n = this.target;
      while (n) {
        path.push(n);
        n = n.parentNode;
      }
      if (path.length && path[path.length - 1] === D) path.push(W);
      return path;
    });
  }

  /* ---------------------------------------------------------------- PointerEvent (from touch and mouse) */
  if (!W.PointerEvent) {
    var PointerEventPoly = function PointerEvent(type, init) {
      init = init || {};
      var e = D.createEvent('MouseEvents');
      e.initMouseEvent(type, init.bubbles !== false, init.cancelable !== false, W, 0, init.screenX || 0,
        init.screenY || 0, init.clientX || 0, init.clientY || 0, !!init.ctrlKey, !!init.altKey,
        !!init.shiftKey, !!init.metaKey, init.button || 0, init.relatedTarget || null);
      e.pointerId = init.pointerId || 1;
      e.pointerType = init.pointerType || 'mouse';
      e.isPrimary = init.isPrimary !== false;
      e.width = init.width || 1;
      e.height = init.height || 1;
      e.pressure = init.pressure === undefined ? 0.5 : init.pressure;
      e.tiltX = 0;
      e.tiltY = 0;
      e.twist = 0;
      e.tangentialPressure = 0;
      e.getCoalescedEvents = function () { return [e]; };
      e.getPredictedEvents = function () { return []; };
      return e;
    };
    PointerEventPoly.prototype = W.MouseEvent.prototype;
    W.PointerEvent = PointerEventPoly;
    var downTarget = {};
    var lastTouch = 0;
    var firePointer = function (type, target, src, touch, id, ptype, buttons) {
      if (!target || !target.dispatchEvent) return true;
      var e = new PointerEventPoly(type, {
        bubbles: type !== 'pointerenter' && type !== 'pointerleave',
        cancelable: type !== 'pointerenter' && type !== 'pointerleave' && type !== 'pointercancel',
        clientX: touch.clientX, clientY: touch.clientY, screenX: touch.screenX, screenY: touch.screenY,
        ctrlKey: src.ctrlKey, altKey: src.altKey, shiftKey: src.shiftKey, metaKey: src.metaKey,
        pointerId: id, pointerType: ptype, isPrimary: id === 1 || ptype === 'mouse',
        button: type === 'pointermove' ? -1 : 0, pressure: buttons ? 0.5 : 0,
        width: touch.radiusX ? touch.radiusX * 2 : 1, height: touch.radiusY ? touch.radiusY * 2 : 1
      });
      e.buttons = buttons;
      return target.dispatchEvent(e);
    };
    var touchHandler = function (type) {
      return function (ev) {
        lastTouch = Date.now();
        var list = ev.changedTouches;
        for (var i = 0; i < list.length; i++) {
          var t = list[i], id = t.identifier + 2;
          var target;
          if (type === 'pointerdown') {
            target = t.target;
            downTarget[id] = target;
            firePointer('pointerover', target, ev, t, id, 'touch', 1);
            firePointer('pointerenter', target, ev, t, id, 'touch', 1);
          } else {
            target = downTarget[id] || t.target;
          }
          var ok = firePointer(type, target, ev, t, id, 'touch', type === 'pointerup' || type === 'pointercancel' ? 0 : 1);
          if (!ok && type === 'pointerdown' && ev.cancelable) ev.preventDefault();
          if (type === 'pointerup' || type === 'pointercancel') {
            firePointer('pointerout', target, ev, t, id, 'touch', 0);
            firePointer('pointerleave', target, ev, t, id, 'touch', 0);
            delete downTarget[id];
          }
        }
      };
    };
    D.addEventListener('touchstart', touchHandler('pointerdown'), true);
    D.addEventListener('touchmove', touchHandler('pointermove'), true);
    D.addEventListener('touchend', touchHandler('pointerup'), true);
    D.addEventListener('touchcancel', touchHandler('pointercancel'), true);
    var mouseHandler = function (type) {
      return function (ev) {
        // Ignore the compatibility mouse events that follow a touch.
        if (Date.now() - lastTouch < 800) return;
        firePointer(type, ev.target, ev, ev, 1, 'mouse', type === 'pointerup' ? 0 : ev.which ? 1 : 0);
      };
    };
    D.addEventListener('mousedown', mouseHandler('pointerdown'), true);
    D.addEventListener('mousemove', mouseHandler('pointermove'), true);
    D.addEventListener('mouseup', mouseHandler('pointerup'), true);
    D.addEventListener('mouseover', mouseHandler('pointerover'), true);
    D.addEventListener('mouseout', mouseHandler('pointerout'), true);
  }

  /* ---------------------------------------------------------------- Web Animations (instant) */
  if (EP && !EP.animate) {
    var camel = function (p) { return p.replace(/-([a-z])/g, function (m, c) { return c.toUpperCase(); }); };
    EP.animate = function (keyframes, options) {
      var el = this;
      var fill = options && typeof options === 'object' ? options.fill : undefined;
      var last = null;
      if (Array.isArray(keyframes)) last = keyframes[keyframes.length - 1];
      else if (keyframes && typeof keyframes === 'object') {
        last = {};
        for (var k in keyframes) {
          if (!hasOwn.call(keyframes, k)) continue;
          var v = keyframes[k];
          last[k] = Array.isArray(v) ? v[v.length - 1] : v;
        }
      }
      var apply = function () {
        if (!last) return;
        for (var p in last) {
          if (!hasOwn.call(last, p) || p === 'offset' || p === 'easing' || p === 'composite') continue;
          try { el.style[camel(p)] = last[p]; } catch (e) { /* ignore */ }
        }
      };
      if (fill === 'forwards' || fill === 'both') apply();
      var anim = {
        playState: 'finished', currentTime: 0, startTime: 0, playbackRate: 1, pending: false, id: '',
        effect: {target: el, getComputedTiming: function () { return {progress: 1}; }},
        onfinish: null, oncancel: null, onremove: null,
        play: noop, pause: noop, reverse: noop, finish: noop, persist: noop, updatePlaybackRate: noop,
        commitStyles: apply,
        cancel: function () { if (isFn(anim.oncancel)) anim.oncancel({type: 'cancel', target: anim}); },
        addEventListener: function (t, fn) { if (t === 'finish') setTimeout(function () { fn({type: 'finish', target: anim}); }, 0); },
        removeEventListener: noop
      };
      anim.finished = W.Promise.resolve(anim);
      anim.ready = W.Promise.resolve(anim);
      setTimeout(function () { if (isFn(anim.onfinish)) anim.onfinish({type: 'finish', target: anim}); }, 0);
      return anim;
    };
    def(D, 'getAnimations', function () { return []; });
  }

  /* ---------------------------------------------------------------- Font loading API */
  if (!D.fonts) {
    var faces = [];
    var fontsReady = null;
    var FontFacePoly = function FontFace(family, source, descriptors) {
      this.family = family;
      this.source = source;
      this.status = 'unloaded';
      var d = descriptors || {};
      this.style = d.style || 'normal';
      this.weight = d.weight || 'normal';
      this.display = d.display || 'auto';
      this.unicodeRange = d.unicodeRange || 'U+0-10FFFF';
      var self = this;
      this.loaded = new W.Promise(function (res) { self._res = res; });
    };
    FontFacePoly.prototype.load = function () {
      if (this.status !== 'loaded') {
        this.status = 'loaded';
        if (typeof this.source === 'string') {
          var st = D.createElement('style');
          st.textContent = '@font-face{font-family:"' + String(this.family).replace(/"/g, '') + '";src:' + this.source
            + ';font-style:' + this.style + ';font-weight:' + this.weight + '}';
          (D.head || D.documentElement).appendChild(st);
        }
        this._res(this);
      }
      return this.loaded;
    };
    W.FontFace = FontFacePoly;
    var fontSet = {
      status: 'loaded', size: 0, onloading: null, onloadingdone: null, onloadingerror: null,
      add: function (f) { faces.push(f); fontSet.size = faces.length; if (f.load) f.load(); return fontSet; },
      'delete': function (f) { var i = faces.indexOf(f); if (i >= 0) faces.splice(i, 1); fontSet.size = faces.length; return i >= 0; },
      clear: function () { faces = []; fontSet.size = 0; },
      has: function (f) { return faces.indexOf(f) >= 0; },
      check: function () { return true; },
      load: function () { return W.Promise.resolve([]); },
      forEach: function (cb, t) { faces.forEach(function (f) { cb.call(t, f, f, fontSet); }); },
      values: function () { return new ArrayIterator(faces.slice(), 0); },
      addEventListener: noop, removeEventListener: noop
    };
    fontSet[SymIter] = fontSet.values;
    fontsReady = W.Promise.resolve(fontSet);
    fontSet.ready = fontsReady;
    try { Object.defineProperty(D, 'fonts', {value: fontSet, configurable: true}); } catch (e) { /* ignore */ }
  }

  /* ---------------------------------------------------------------- observers */
  function viewportRect() {
    var w = W.innerWidth || D.documentElement.clientWidth, h = W.innerHeight || D.documentElement.clientHeight;
    return {top: 0, left: 0, right: w, bottom: h, width: w, height: h, x: 0, y: 0};
  }
  function rectOf(r) {
    return {top: r.top, left: r.left, right: r.right, bottom: r.bottom, width: r.right - r.left,
      height: r.bottom - r.top, x: r.left, y: r.top};
  }
  var observers = [];
  var checkTimer = 0, pollTimer = 0;
  function scheduleCheck(delay) {
    if (checkTimer) return;
    checkTimer = setTimeout(function () {
      checkTimer = 0;
      for (var i = 0; i < observers.length; i++) {
        try { observers[i]._check(); } catch (e) { /* ignore */ }
      }
    }, delay === undefined ? 120 : delay);
  }
  function startPolling() {
    if (pollTimer) return;
    var listen = function (t, el) { el.addEventListener(t, function () { scheduleCheck(); }, true); };
    listen('scroll', D);
    listen('resize', W);
    listen('load', W);
    listen('transitionend', D);
    listen('webkitTransitionEnd', D);
    listen('animationend', D);
    listen('webkitAnimationEnd', D);
    listen('touchend', D);
    listen('click', D);
    D.addEventListener('DOMContentLoaded', function () { scheduleCheck(0); }, false);
    pollTimer = setInterval(function () {
      var any = false;
      for (var i = 0; i < observers.length; i++) if (observers[i]._t.length) { any = true; break; }
      if (any && !D.webkitHidden && !D.hidden) scheduleCheck(0);
    }, 1000);
  }

  if (!W.IntersectionObserver || !('isIntersecting' in (W.IntersectionObserverEntry || {prototype: {}}).prototype)) {
    var parseMargin = function (m) {
      var parts = String(m || '0px').trim().split(/\s+/);
      var vals = parts.map(function (p) {
        var n = parseFloat(p) || 0;
        return {v: n, pct: /%$/.test(p)};
      });
      while (vals.length < 4) vals.push(vals[vals.length === 3 ? 1 : vals.length === 2 ? 0 : 0]);
      if (parts.length === 2) vals = [vals[0], vals[1], vals[0], vals[1]];
      else if (parts.length === 3) vals = [vals[0], vals[1], vals[2], vals[1]];
      return vals;
    };
    var IOEntry = function IntersectionObserverEntry(d) {
      for (var k in d) this[k] = d[k];
    };
    IOEntry.prototype.isIntersecting = false;
    var IO = function IntersectionObserver(cb, opts) {
      if (!isFn(cb)) throw new TypeError('callback must be a function');
      opts = opts || {};
      this._cb = cb;
      this.root = opts.root || null;
      this.rootMargin = opts.rootMargin || '0px 0px 0px 0px';
      var th = opts.threshold === undefined ? [0] : [].concat(opts.threshold);
      this.thresholds = th.sort();
      this._m = parseMargin(opts.rootMargin);
      this._t = [];
      this._s = [];
      this._q = [];
      observers.push(this);
      startPolling();
    };
    IO.prototype.observe = function (el) {
      if (!el || el.nodeType !== 1) throw new TypeError('target must be an Element');
      if (this._t.indexOf(el) >= 0) return;
      this._t.push(el);
      this._s.push(-1);
      scheduleCheck(20);
    };
    IO.prototype.unobserve = function (el) {
      var i = this._t.indexOf(el);
      if (i >= 0) { this._t.splice(i, 1); this._s.splice(i, 1); }
    };
    IO.prototype.disconnect = function () { this._t = []; this._s = []; };
    IO.prototype.takeRecords = function () { var q = this._q; this._q = []; return q; };
    IO.prototype._check = function () {
      if (!this._t.length) return;
      var root = this.root && this.root.nodeType === 1 ? rectOf(this.root.getBoundingClientRect()) : viewportRect();
      var m = this._m;
      var rw = root.width, rh = root.height;
      var rb = {
        top: root.top - (m[0].pct ? rh * m[0].v / 100 : m[0].v),
        right: root.right + (m[1].pct ? rw * m[1].v / 100 : m[1].v),
        bottom: root.bottom + (m[2].pct ? rh * m[2].v / 100 : m[2].v),
        left: root.left - (m[3].pct ? rw * m[3].v / 100 : m[3].v)
      };
      rb.width = rb.right - rb.left;
      rb.height = rb.bottom - rb.top;
      var entries = [], now = W.performance && performance.now ? performance.now() : Date.now();
      for (var i = 0; i < this._t.length; i++) {
        var el = this._t[i];
        var connected = D.documentElement.contains(el);
        var br = connected ? rectOf(el.getBoundingClientRect()) : {top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0};
        var top = Math.max(br.top, rb.top), bottom = Math.min(br.bottom, rb.bottom);
        var left = Math.max(br.left, rb.left), right = Math.min(br.right, rb.right);
        var visible = connected && (br.width || br.height || el.offsetParent !== null) && bottom >= top && right >= left
          && !(br.width === 0 && br.height === 0 && el.offsetParent === null);
        var ir = visible ? {top: top, left: left, bottom: bottom, right: right, width: right - left, height: bottom - top, x: left, y: top}
          : {top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0, x: 0, y: 0};
        var area = br.width * br.height;
        var ratio = visible ? (area ? (ir.width * ir.height) / area : 1) : 0;
        var idx = -1;
        for (var t = 0; t < this.thresholds.length; t++) {
          var th = this.thresholds[t];
          if (visible && (ratio > th || (ratio === th && (th !== 0 || visible)) || (th === 0 && visible))) idx = t;
        }
        var state = visible ? idx : -2;
        if (state !== this._s[i]) {
          var first = this._s[i] === -1;
          this._s[i] = state;
          if (first && !visible) {
            // Initial notification: report as not intersecting, like browsers do.
          }
          entries.push(new IOEntry({
            time: now, target: el, rootBounds: rb, boundingClientRect: br, intersectionRect: ir,
            intersectionRatio: ratio, isIntersecting: !!visible
          }));
        }
      }
      if (entries.length) this._cb(entries, this);
    };
    W.IntersectionObserver = IO;
    W.IntersectionObserverEntry = IOEntry;
  }

  if (!W.ResizeObserver) {
    var RO = function ResizeObserver(cb) {
      if (!isFn(cb)) throw new TypeError('callback must be a function');
      this._cb = cb;
      this._t = [];
      this._s = [];
      observers.push(this);
      startPolling();
    };
    RO.prototype.observe = function (el) {
      if (this._t.indexOf(el) >= 0) return;
      this._t.push(el);
      this._s.push(null);
      scheduleCheck(0);
    };
    RO.prototype.unobserve = function (el) {
      var i = this._t.indexOf(el);
      if (i >= 0) { this._t.splice(i, 1); this._s.splice(i, 1); }
    };
    RO.prototype.disconnect = function () { this._t = []; this._s = []; };
    RO.prototype._check = function () {
      var entries = [];
      for (var i = 0; i < this._t.length; i++) {
        var el = this._t[i];
        var cs = W.getComputedStyle(el);
        var pl = parseFloat(cs.paddingLeft) || 0, pr = parseFloat(cs.paddingRight) || 0;
        var pt = parseFloat(cs.paddingTop) || 0, pb = parseFloat(cs.paddingBottom) || 0;
        var bw = el.offsetWidth, bh = el.offsetHeight;
        var w = Math.max(0, el.clientWidth - pl - pr), h = Math.max(0, el.clientHeight - pt - pb);
        if (el instanceof W.SVGElement && el.getBBox) {
          try { var bb = el.getBBox(); w = bb.width; h = bb.height; } catch (e) { /* not rendered */ }
        }
        var key = w + 'x' + h + ':' + bw + 'x' + bh;
        if (key === this._s[i]) continue;
        this._s[i] = key;
        entries.push({
          target: el,
          contentRect: {x: pl, y: pt, width: w, height: h, top: pt, left: pl, right: pl + w, bottom: pt + h},
          contentBoxSize: [{inlineSize: w, blockSize: h}],
          borderBoxSize: [{inlineSize: bw, blockSize: bh}],
          devicePixelContentBoxSize: [{inlineSize: w * (W.devicePixelRatio || 1), blockSize: h * (W.devicePixelRatio || 1)}]
        });
      }
      if (entries.length) this._cb(entries, this);
    };
    W.ResizeObserver = RO;
  }

  if (!W.PerformanceObserver) {
    W.PerformanceObserver = function PerformanceObserver() {};
    W.PerformanceObserver.prototype.observe = noop;
    W.PerformanceObserver.prototype.disconnect = noop;
    W.PerformanceObserver.prototype.takeRecords = function () { return []; };
    W.PerformanceObserver.supportedEntryTypes = [];
  }
  if (W.performance) {
    def(W.performance, 'mark', noop);
    def(W.performance, 'measure', noop);
    def(W.performance, 'getEntriesByType', function () { return []; });
    def(W.performance, 'getEntriesByName', function () { return []; });
    def(W.performance, 'clearMarks', noop);
    def(W.performance, 'clearMeasures', noop);
    def(W.performance, 'now', function () { return Date.now(); });
  }

  /* ---------------------------------------------------------------- Intl stubs */
  if (W.Intl) {
    if (!W.Intl.PluralRules) {
      W.Intl.PluralRules = function PluralRules(locale, opts) { this._o = opts || {}; };
      W.Intl.PluralRules.prototype.select = function (n) {
        if (this._o.type === 'ordinal') {
          var m10 = n % 10, m100 = n % 100;
          return m10 === 1 && m100 !== 11 ? 'one' : m10 === 2 && m100 !== 12 ? 'two' : m10 === 3 && m100 !== 13 ? 'few' : 'other';
        }
        return n === 1 ? 'one' : 'other';
      };
      W.Intl.PluralRules.prototype.resolvedOptions = function () { return {locale: 'en', pluralCategories: ['one', 'other']}; };
      W.Intl.PluralRules.supportedLocalesOf = function (l) { return [].concat(l || []); };
    }
    if (!W.Intl.RelativeTimeFormat) {
      W.Intl.RelativeTimeFormat = function RelativeTimeFormat() {};
      W.Intl.RelativeTimeFormat.prototype.format = function (v, unit) {
        unit = String(unit).replace(/s$/, '');
        var a = Math.abs(v), u = a === 1 ? unit : unit + 's';
        return v < 0 ? a + ' ' + u + ' ago' : 'in ' + a + ' ' + u;
      };
      W.Intl.RelativeTimeFormat.prototype.formatToParts = function (v, u) { return [{type: 'literal', value: this.format(v, u)}]; };
      W.Intl.RelativeTimeFormat.prototype.resolvedOptions = function () { return {locale: 'en', style: 'long', numeric: 'always'}; };
    }
    if (!W.Intl.ListFormat) {
      W.Intl.ListFormat = function ListFormat(l, o) { this._o = o || {}; };
      W.Intl.ListFormat.prototype.format = function (list) {
        var a = Array.from(list);
        if (a.length < 2) return a.join('');
        var word = this._o.type === 'disjunction' ? ' or ' : ' and ';
        return a.slice(0, -1).join(', ') + word + a[a.length - 1];
      };
    }
    if (W.Intl.DateTimeFormat && !W.Intl.DateTimeFormat.prototype.formatToParts) {
      W.Intl.DateTimeFormat.prototype.formatToParts = function (d) { return [{type: 'literal', value: this.format(d)}]; };
    }
  }
})(window);
