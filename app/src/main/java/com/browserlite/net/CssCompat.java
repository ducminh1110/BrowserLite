package com.browserlite.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites modern CSS into something the Android 4.4 WebView (Chromium 30/33) understands.
 *
 * <p>Chromium 30 silently drops whole declarations or rules it cannot parse, so a single
 * {@code var(--x)}, {@code :is()} or {@code @layer} makes large parts of today's sites unstyled. This is a
 * single-pass, allocation-light transformer (no regex on the hot path) that:
 * <ul>
 *   <li>resolves custom properties statically (rule-local, then {@code :root}-like globals, then the first
 *       definition seen anywhere, then the var() fallback);</li>
 *   <li>unwraps {@code @layer}, flattens CSS nesting, drops {@code @container}/{@code @property};</li>
 *   <li>expands {@code :is()/:where()}, splits {@code :not(a,b)}, maps {@code :focus-visible} etc.;</li>
 *   <li>converts {@code oklch()/oklab()/color-mix()}, space-separated {@code rgb()}, {@code #rrggbbaa};</li>
 *   <li>replaces {@code clamp()/min()/max()} and new viewport units, rewrites media range syntax;</li>
 *   <li>maps logical properties, {@code inset}, {@code place-*}, individual transforms, and adds the
 *       {@code -webkit-} prefixes Chromium 30 still needs (transform, keyframes, animation, ...).</li>
 * </ul>
 * Values are only ever made simpler, so the output also stays valid for modern engines.
 */
public final class CssCompat {

    public static final class Options {
        public boolean blockFonts;
        /** Inputs larger than this are returned untouched to bound CPU and heap use. */
        public int maxLength = 1_500_000;
    }

    /** Custom property definitions shared by all stylesheets of one site. Thread safe. */
    public static final class VarRegistry {
        private final HashMap<String, String> globals = new HashMap<>();
        private final HashMap<String, String> firstSeen = new HashMap<>();

        synchronized void putGlobal(String k, String v) {
            globals.put(k, v);
        }

        synchronized void putInitial(String k, String v) {
            if (!globals.containsKey(k)) globals.put(k, v);
        }

        synchronized void putFirstSeen(String k, String v) {
            if (!firstSeen.containsKey(k)) firstSeen.put(k, v);
        }

        synchronized String get(String k) {
            String v = globals.get(k);
            return v != null ? v : firstSeen.get(k);
        }

        public synchronized int size() {
            return globals.size() + firstSeen.size();
        }
    }

    public static String transform(String css, VarRegistry registry, Options options) {
        if (css == null || css.isEmpty()) return css;
        if (options == null) options = new Options();
        if (css.length() > options.maxLength) return css;
        if (registry == null) registry = new VarRegistry();
        try {
            CssCompat c = new CssCompat(css, registry, options);
            List<Object> rules = c.parseRules(0, css.length());
            c.collect(rules, null, false);
            StringBuilder out = new StringBuilder(css.length());
            c.emitRules(rules, out);
            return out.toString();
        } catch (RuntimeException e) {
            // Never let a parser bug break a page: fall back to the original stylesheet.
            return css;
        }
    }

    // ------------------------------------------------------------------ model

    static final class Decl {
        final String name;
        final String value;
        final boolean important;

        Decl(String name, String value, boolean important) {
            this.name = name;
            this.value = value;
            this.important = important;
        }
    }

    static final class Rule {
        final String selector;
        final ArrayList<Decl> decls = new ArrayList<>();
        /** Nested {@link Rule}s and {@link AtRule}s (CSS nesting). */
        ArrayList<Object> nested;

        Rule(String selector) {
            this.selector = selector;
        }
    }

    static final class AtRule {
        final String name;
        final String prelude;
        /** Child rules for group rules (media, supports, layer, keyframes, ...). */
        List<Object> rules;
        /** Declarations for descriptor rules (font-face, page, property) or nested-in-rule at-rules. */
        Rule body;
        /** Custom properties defined inside this conditional block. */
        HashMap<String, String> scoped;

        AtRule(String name, String prelude) {
            this.name = name;
            this.prelude = prelude;
        }
    }

    private final String s;
    private final VarRegistry reg;
    private final Options opt;
    private final ArrayList<Map<String, String>> scopes = new ArrayList<>();
    /** var name -> declarations of simple "component" rules (e.g. {@code .btn}) that consume it. */
    private final HashMap<String, ArrayList<Decl>> consumers = new HashMap<>();

    private CssCompat(String s, VarRegistry reg, Options opt) {
        this.s = s;
        this.reg = reg;
        this.opt = opt;
    }

    // ------------------------------------------------------------------ parsing

    private static boolean isIdentChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                || c > 127;
    }

    private int skipWsAndComments(int i, int to) {
        while (i < to) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f') {
                i++;
            } else if (c == '/' && i + 1 < to && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 || end >= to ? to : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private int skipString(int i, int to) {
        char q = s.charAt(i);
        i++;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q || c == '\n') return i + 1;
            i++;
        }
        return to;
    }

    /** Index of the first '{' (or ';' when asked) at nesting depth 0, or of a closing '}', or {@code to}. */
    private int scanUntil(int i, int to, boolean semicolon) {
        int paren = 0;
        while (i < to) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                case '\'':
                    i = skipString(i, to);
                    continue;
                case '\\':
                    i += 2;
                    continue;
                case '/':
                    if (i + 1 < to && s.charAt(i + 1) == '*') {
                        int end = s.indexOf("*/", i + 2);
                        i = end < 0 || end >= to ? to : end + 2;
                        continue;
                    }
                    break;
                case '(':
                case '[':
                    paren++;
                    break;
                case ')':
                case ']':
                    if (paren > 0) paren--;
                    break;
                case '{':
                    if (paren == 0) return i;
                    break;
                case '}':
                    return i;
                case ';':
                    if (semicolon && paren == 0) return i;
                    break;
                default:
                    break;
            }
            i++;
        }
        return to;
    }

    /** Index of the '}' matching the '{' at {@code open}, or {@code to}. */
    private int matchBrace(int open, int to) {
        int depth = 0;
        int i = open;
        while (i < to) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                case '\'':
                    i = skipString(i, to);
                    continue;
                case '\\':
                    i += 2;
                    continue;
                case '/':
                    if (i + 1 < to && s.charAt(i + 1) == '*') {
                        int end = s.indexOf("*/", i + 2);
                        i = end < 0 || end >= to ? to : end + 2;
                        continue;
                    }
                    break;
                case '{':
                    depth++;
                    break;
                case '}':
                    depth--;
                    if (depth == 0) return i;
                    break;
                default:
                    break;
            }
            i++;
        }
        return to;
    }

    private static boolean isGroupRule(String name) {
        switch (name) {
            case "media":
            case "supports":
            case "layer":
            case "container":
            case "document":
            case "-moz-document":
            case "scope":
            case "starting-style":
                return true;
            default:
                return false;
        }
    }

    private static boolean isKeyframes(String name) {
        return name.equals("keyframes") || name.equals("-webkit-keyframes") || name.equals("-moz-keyframes")
                || name.equals("-o-keyframes");
    }

    List<Object> parseRules(int from, int to) {
        ArrayList<Object> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            i = skipWsAndComments(i, to);
            if (i >= to) break;
            char c = s.charAt(i);
            if (c == ';' || c == '}') {
                i++;
                continue;
            }
            if (c == '<' && s.startsWith("<!--", i)) {
                i += 4;
                continue;
            }
            if (c == '-' && s.startsWith("-->", i)) {
                i += 3;
                continue;
            }
            if (c == '@') {
                int nameEnd = i + 1;
                while (nameEnd < to && isIdentChar(s.charAt(nameEnd))) nameEnd++;
                String name = s.substring(i + 1, nameEnd).toLowerCase(Locale.US);
                int stop = scanUntil(nameEnd, to, true);
                AtRule at = new AtRule(name, s.substring(nameEnd, Math.min(stop, to)).trim());
                if (stop < to && s.charAt(stop) == '{') {
                    int close = matchBrace(stop, to);
                    if (isGroupRule(name) || isKeyframes(name)) {
                        at.rules = parseRules(stop + 1, close);
                    } else {
                        at.body = parseRuleBody("", stop + 1, close);
                    }
                    i = close + 1;
                } else {
                    i = stop + 1;
                }
                out.add(at);
            } else {
                int stop = scanUntil(i, to, false);
                if (stop >= to) break;
                if (s.charAt(stop) == '}') {
                    i = stop + 1;
                    continue;
                }
                String sel = s.substring(i, stop).trim();
                int close = matchBrace(stop, to);
                out.add(parseRuleBody(sel, stop + 1, close));
                i = close + 1;
            }
        }
        return out;
    }

    Rule parseRuleBody(String selector, int from, int to) {
        Rule r = new Rule(selector);
        int i = from;
        while (i < to) {
            i = skipWsAndComments(i, to);
            if (i >= to) break;
            char c = s.charAt(i);
            if (c == ';' || c == '}') {
                i++;
                continue;
            }
            int stop = scanUntil(i, to, true);
            if (stop < to && s.charAt(stop) == '{') {
                String prelude = s.substring(i, stop).trim();
                int close = matchBrace(stop, to);
                if (r.nested == null) r.nested = new ArrayList<>();
                if (prelude.startsWith("@")) {
                    int nameEnd = 1;
                    while (nameEnd < prelude.length() && isIdentChar(prelude.charAt(nameEnd))) nameEnd++;
                    AtRule at = new AtRule(prelude.substring(1, nameEnd).toLowerCase(Locale.US),
                            prelude.substring(nameEnd).trim());
                    at.body = parseRuleBody("&", stop + 1, close);
                    r.nested.add(at);
                } else {
                    r.nested.add(parseRuleBody(prelude, stop + 1, close));
                }
                i = close + 1;
            } else {
                addDecl(r.decls, i, Math.min(stop, to));
                i = stop + 1;
            }
        }
        return r;
    }

    private void addDecl(List<Decl> list, int from, int to) {
        int colon = -1;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == ':') {
                colon = i;
                break;
            }
            if (c == '"' || c == '\'' || c == '(' || c == '{') return;
        }
        if (colon < 0) return;
        String name = s.substring(from, colon).trim();
        if (name.isEmpty()) return;
        if (!name.startsWith("--")) name = name.toLowerCase(Locale.US);
        String value = stripComments(s.substring(colon + 1, to)).trim();
        boolean important = false;
        int bang = value.lastIndexOf('!');
        if (bang >= 0 && value.substring(bang + 1).trim().equalsIgnoreCase("important")) {
            important = true;
            value = value.substring(0, bang).trim();
        }
        list.add(new Decl(name, value, important));
    }

    private static String stripComments(String v) {
        int c = v.indexOf("/*");
        if (c < 0) return v;
        StringBuilder sb = new StringBuilder(v.length());
        int i = 0;
        while (i < v.length()) {
            char ch = v.charAt(i);
            if (ch == '"' || ch == '\'') {
                int j = i + 1;
                while (j < v.length() && v.charAt(j) != ch) {
                    if (v.charAt(j) == '\\') j++;
                    j++;
                }
                j = Math.min(j + 1, v.length());
                sb.append(v, i, j);
                i = j;
            } else if (ch == '/' && i + 1 < v.length() && v.charAt(i + 1) == '*') {
                int end = v.indexOf("*/", i + 2);
                i = end < 0 ? v.length() : end + 2;
                sb.append(' ');
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ custom property collection

    private static boolean isDarkSelector(String sel) {
        List<String> parts = splitTopLevel(sel, ',');
        if (parts.isEmpty()) return false;
        for (String p : parts) {
            if (p.toLowerCase(Locale.US).indexOf("dark") < 0) return false;
        }
        return true;
    }

    private static boolean isGlobalSelector(String sel) {
        for (String raw : splitTopLevel(sel, ',')) {
            String p = raw.trim().toLowerCase(Locale.US);
            if (p.indexOf("dark") >= 0) continue;
            while (p.startsWith("*")) p = p.substring(1);
            if (p.isEmpty() || p.equals(":root") || p.equals("html") || p.equals("body") || p.equals(":host")
                    || p.equals("::before") || p.equals("::after") || p.equals(":before") || p.equals(":after")
                    || p.equals("::backdrop") || p.equals("html:root") || p.equals(":root:root")) {
                return true;
            }
            if ((p.startsWith(":root") || p.startsWith("html")) && p.indexOf(' ') < 0 && p.indexOf('>') < 0) {
                return true;
            }
            if (p.startsWith(":where(:root") || p.startsWith(":is(:root")) return true;
        }
        return false;
    }

    void collect(List<Object> items, HashMap<String, String> scope, boolean skip) {
        for (Object o : items) {
            if (o instanceof Rule) {
                Rule r = (Rule) o;
                if (skip || r.decls.isEmpty()) continue;
                if (isSimpleSelector(r.selector)) collectConsumers(r);
                boolean any = false;
                for (Decl d : r.decls) {
                    if (d.name.startsWith("--")) {
                        any = true;
                        break;
                    }
                }
                if (!any || isDarkSelector(r.selector)) continue;
                boolean global = isGlobalSelector(r.selector);
                for (Decl d : r.decls) {
                    if (!d.name.startsWith("--")) continue;
                    if (global) {
                        if (scope == null) reg.putGlobal(d.name, d.value);
                        else scope.put(d.name, d.value);
                    } else {
                        reg.putFirstSeen(d.name, d.value);
                    }
                }
            } else {
                AtRule at = (AtRule) o;
                switch (at.name) {
                    case "media": {
                        String p = at.prelude.toLowerCase(Locale.US);
                        boolean ignore = (p.contains("prefers-color-scheme") && p.contains("dark"))
                                || p.equals("print") || p.startsWith("print ") || p.startsWith("only print");
                        at.scoped = new HashMap<>();
                        if (at.rules != null) collect(at.rules, at.scoped, skip || ignore);
                        break;
                    }
                    case "supports":
                        at.scoped = new HashMap<>();
                        if (at.rules != null) collect(at.rules, at.scoped, skip);
                        break;
                    case "layer":
                        if (at.rules != null) collect(at.rules, scope, skip);
                        break;
                    case "property":
                        if (!skip && at.body != null && at.prelude.startsWith("--")) {
                            for (Decl d : at.body.decls) {
                                if (d.name.equals("initial-value")) reg.putInitial(at.prelude.trim(), d.value);
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static boolean isSimpleSelector(String sel) {
        if (sel.isEmpty() || sel.length() > 40) return false;
        for (int i = 0; i < sel.length(); i++) {
            char c = sel.charAt(i);
            if (!(isIdentChar(c) || c == '.')) return false;
        }
        return true;
    }

    private void collectConsumers(Rule r) {
        for (Decl d : r.decls) {
            if (d.name.startsWith("--")) continue;
            int idx = d.value.indexOf("var(");
            while (idx >= 0) {
                int end = idx + 4;
                while (end < d.value.length() && d.value.charAt(end) != ',' && d.value.charAt(end) != ')') end++;
                String name = d.value.substring(idx + 4, end).trim();
                if (name.startsWith("--")) {
                    ArrayList<Decl> list = consumers.get(name);
                    if (list == null) {
                        list = new ArrayList<>(2);
                        consumers.put(name, list);
                    }
                    if (list.size() < 8 && !list.contains(d)) list.add(d);
                }
                idx = d.value.indexOf("var(", end);
            }
        }
    }

    // ------------------------------------------------------------------ emission

    void emitRules(List<Object> items, StringBuilder out) {
        for (Object o : items) {
            if (o instanceof Rule) {
                Rule r = (Rule) o;
                emitRule(r, fixSelectorList(r.selector), null, out);
            } else {
                emitAt((AtRule) o, out);
            }
        }
    }

    private void emitAt(AtRule at, StringBuilder out) {
        switch (at.name) {
            case "media":
            case "supports": {
                if (at.rules == null) return;
                String prelude = at.name.equals("media") ? fixMedia(at.prelude) : at.prelude;
                if (at.scoped != null) scopes.add(at.scoped);
                StringBuilder inner = new StringBuilder();
                emitRules(at.rules, inner);
                if (at.scoped != null) scopes.remove(scopes.size() - 1);
                if (inner.length() > 0) {
                    out.append('@').append(at.name).append(' ').append(prelude).append('{').append(inner).append('}');
                }
                return;
            }
            case "layer":
                if (at.rules != null) emitRules(at.rules, out);
                return;
            case "import":
                out.append("@import ").append(fixImport(at.prelude)).append(';');
                return;
            case "charset":
            case "namespace":
                out.append('@').append(at.name).append(' ').append(at.prelude).append(';');
                return;
            case "font-face":
                if (opt.blockFonts || at.body == null) return;
                out.append("@font-face{");
                appendRawDecls(at.body, out);
                out.append('}');
                return;
            case "page":
                if (at.body == null) return;
                out.append("@page ").append(at.prelude).append('{');
                appendRawDecls(at.body, out);
                out.append('}');
                return;
            case "keyframes":
            case "-webkit-keyframes": {
                if (at.rules == null) return;
                out.append("@-webkit-keyframes ").append(at.prelude).append('{');
                for (Object o : at.rules) {
                    if (!(o instanceof Rule)) continue;
                    Rule kr = (Rule) o;
                    out.append(kr.selector).append('{');
                    StringBuilder body = new StringBuilder();
                    emitDecls(kr, new HashMap<String, String>(), body);
                    out.append(body).append('}');
                }
                out.append('}');
                return;
            }
            default:
                // container, property, scope, starting-style, counter-style, -moz-*, unknown: Chromium 30
                // would drop these anyway, so drop them here and save the bytes.
                return;
        }
    }

    private static void appendRawDecls(Rule body, StringBuilder out) {
        for (Decl d : body.decls) {
            out.append(d.name).append(':').append(d.value);
            if (d.important) out.append("!important");
            out.append(';');
        }
    }

    private void emitRule(Rule r, String selector, HashMap<String, String> parentLocal, StringBuilder out) {
        if (selector == null || selector.isEmpty()) return;
        HashMap<String, String> local = parentLocal == null ? new HashMap<String, String>()
                : new HashMap<>(parentLocal);
        for (Decl d : r.decls) {
            if (d.name.startsWith("--")) local.put(d.name, d.value);
        }
        StringBuilder body = new StringBuilder();
        emitDecls(r, local, body);
        if (!consumers.isEmpty() && parentLocal == null) propagateConsumers(r, local, body);
        if (body.length() > 0) out.append(selector).append('{').append(body).append('}');
        if (r.nested == null) return;
        for (Object o : r.nested) {
            if (o instanceof Rule) {
                Rule nr = (Rule) o;
                emitRule(nr, combineSelectors(selector, fixSelectorList(nr.selector)), local, out);
            } else {
                AtRule at = (AtRule) o;
                if (at.body == null) continue;
                if (at.name.equals("media") || at.name.equals("supports")) {
                    StringBuilder inner = new StringBuilder();
                    emitRule(at.body, selector, local, inner);
                    if (inner.length() > 0) {
                        String prelude = at.name.equals("media") ? fixMedia(at.prelude) : at.prelude;
                        out.append('@').append(at.name).append(' ').append(prelude).append('{').append(inner)
                                .append('}');
                    }
                } else if (at.name.equals("layer") || at.name.equals("nest")) {
                    emitRule(at.body, selector, local, out);
                }
            }
        }
    }

    /**
     * Bootstrap-style modifiers ({@code .btn-primary{--bs-btn-bg:#0d6efd}}) only redefine variables that a base
     * rule ({@code .btn{background-color:var(--bs-btn-bg)}}) consumes. Without custom property support those
     * modifiers would do nothing, so copy the consuming declarations into the modifier with its own values.
     */
    private void propagateConsumers(Rule r, HashMap<String, String> local, StringBuilder body) {
        boolean defines = false;
        for (Decl d : r.decls) {
            if (d.name.startsWith("--")) {
                defines = true;
                break;
            }
        }
        if (!defines || isGlobalSelector(r.selector)) return;
        HashSet<String> emitted = null;
        for (Decl d : r.decls) {
            if (!d.name.startsWith("--")) continue;
            ArrayList<Decl> list = consumers.get(d.name);
            if (list == null) continue;
            for (Decl c : list) {
                if (hasProperty(r, c.name)) continue;
                if (emitted == null) emitted = new HashSet<>();
                if (!emitted.add(c.name)) continue;
                Rule tmp = new Rule(r.selector);
                tmp.decls.add(c);
                emitDecls(tmp, local, body);
            }
        }
    }

    private static boolean hasProperty(Rule r, String name) {
        for (Decl d : r.decls) {
            if (d.name.equals(name)) return true;
        }
        return false;
    }

    private static final HashSet<String> PREFIXED = new HashSet<>();
    private static final HashSet<String> DROPPED = new HashSet<>();
    private static final HashMap<String, String> LOGICAL = new HashMap<>();

    static {
        String[] prefixed = {"transform", "transform-origin", "transform-style", "perspective", "perspective-origin",
                "backface-visibility", "user-select", "appearance", "animation", "animation-name",
                "animation-duration", "animation-timing-function", "animation-delay", "animation-iteration-count",
                "animation-direction", "animation-fill-mode", "animation-play-state", "clip-path", "mask",
                "mask-image", "mask-size", "mask-position", "mask-repeat", "column-count", "columns",
                "column-width", "column-rule", "column-span", "column-fill", "font-feature-settings",
                "text-emphasis", "text-emphasis-color", "text-emphasis-style", "text-emphasis-position", "hyphens",
                "box-decoration-break", "text-size-adjust", "writing-mode", "print-color-adjust", "background-clip",
                "filter", "text-decoration-skip", "font-kerning", "text-combine-upright", "ruby-position"};
        for (String p : prefixed) PREFIXED.add(p);
        String[] dropped = {"aspect-ratio", "will-change", "backdrop-filter", "-webkit-backdrop-filter",
                "content-visibility", "contain-intrinsic-size", "scroll-snap-type", "scroll-snap-align",
                "scroll-snap-stop", "overscroll-behavior", "overscroll-behavior-x", "overscroll-behavior-y",
                "scrollbar-gutter", "scrollbar-width", "scrollbar-color", "accent-color", "view-transition-name",
                "container", "container-type", "container-name", "anchor-name", "position-anchor",
                "interpolate-size", "field-sizing", "color-scheme", "scroll-behavior", "scroll-timeline",
                "view-timeline", "animation-timeline", "animation-range", "text-wrap-style", "forced-color-adjust"};
        for (String d : dropped) DROPPED.add(d);
        String[][] logical = {
                {"margin-inline-start", "margin-left"}, {"margin-inline-end", "margin-right"},
                {"margin-block-start", "margin-top"}, {"margin-block-end", "margin-bottom"},
                {"padding-inline-start", "padding-left"}, {"padding-inline-end", "padding-right"},
                {"padding-block-start", "padding-top"}, {"padding-block-end", "padding-bottom"},
                {"border-inline-start", "border-left"}, {"border-inline-end", "border-right"},
                {"border-block-start", "border-top"}, {"border-block-end", "border-bottom"},
                {"border-inline-start-width", "border-left-width"}, {"border-inline-end-width", "border-right-width"},
                {"border-block-start-width", "border-top-width"}, {"border-block-end-width", "border-bottom-width"},
                {"border-inline-start-color", "border-left-color"}, {"border-inline-end-color", "border-right-color"},
                {"border-block-start-color", "border-top-color"}, {"border-block-end-color", "border-bottom-color"},
                {"border-inline-start-style", "border-left-style"}, {"border-inline-end-style", "border-right-style"},
                {"border-block-start-style", "border-top-style"}, {"border-block-end-style", "border-bottom-style"},
                {"inset-inline-start", "left"}, {"inset-inline-end", "right"},
                {"inset-block-start", "top"}, {"inset-block-end", "bottom"},
                {"inline-size", "width"}, {"block-size", "height"},
                {"min-inline-size", "min-width"}, {"max-inline-size", "max-width"},
                {"min-block-size", "min-height"}, {"max-block-size", "max-height"},
                {"border-start-start-radius", "border-top-left-radius"},
                {"border-start-end-radius", "border-top-right-radius"},
                {"border-end-start-radius", "border-bottom-left-radius"},
                {"border-end-end-radius", "border-bottom-right-radius"},
                {"overflow-inline", "overflow-x"}, {"overflow-block", "overflow-y"}};
        for (String[] l : logical) LOGICAL.put(l[0], l[1]);
    }

    private void emitDecls(Rule r, HashMap<String, String> local, StringBuilder body) {
        String translate = null, rotate = null, scale = null;
        boolean hasTransform = false;
        for (Decl d : r.decls) {
            String name = d.name;
            if (name.startsWith("--")) continue;
            String value = d.value;
            if (value.indexOf("var(") >= 0 || value.indexOf("VAR(") >= 0) {
                value = resolveVars(value, local, 0);
                if (value == null) continue;
                value = value.trim();
            }
            value = fixValue(name, value);
            if (value == null || value.isEmpty()) continue;
            String imp = d.important ? "!important" : "";
            if (DROPPED.contains(name)) continue;
            String mapped = LOGICAL.get(name);
            if (mapped != null) {
                decl(body, mapped, value, imp);
                continue;
            }
            switch (name) {
                case "transform":
                case "-webkit-transform":
                    hasTransform = true;
                    break;
                case "translate":
                    translate = value;
                    continue;
                case "rotate":
                    rotate = value;
                    continue;
                case "scale":
                    scale = value;
                    continue;
                case "inset":
                    box(body, "top", "right", "bottom", "left", value, imp);
                    continue;
                case "inset-inline":
                    pair(body, "left", "right", value, imp);
                    continue;
                case "inset-block":
                    pair(body, "top", "bottom", value, imp);
                    continue;
                case "margin-inline":
                    pair(body, "margin-left", "margin-right", value, imp);
                    continue;
                case "margin-block":
                    pair(body, "margin-top", "margin-bottom", value, imp);
                    continue;
                case "padding-inline":
                    pair(body, "padding-left", "padding-right", value, imp);
                    continue;
                case "padding-block":
                    pair(body, "padding-top", "padding-bottom", value, imp);
                    continue;
                case "border-inline":
                    decl(body, "border-left", value, imp);
                    decl(body, "border-right", value, imp);
                    continue;
                case "border-block":
                    decl(body, "border-top", value, imp);
                    decl(body, "border-bottom", value, imp);
                    continue;
                case "place-items":
                    pair(body, "align-items", "justify-items", value, imp);
                    continue;
                case "place-content":
                    pair(body, "align-content", "justify-content", value, imp);
                    continue;
                case "place-self":
                    pair(body, "align-self", "justify-self", value, imp);
                    continue;
                case "overflow":
                case "overflow-x":
                case "overflow-y":
                    if (value.indexOf("clip") >= 0) value = value.replace("clip", "hidden");
                    break;
                case "display":
                    if (value.equals("flow-root")) decl(body, "display", "block", imp);
                    break;
                case "filter":
                    if (value.indexOf("blur") >= 0 || value.indexOf("drop-shadow") >= 0) continue;
                    break;
                default:
                    break;
            }
            if (PREFIXED.contains(name)) {
                String v = value;
                if (name.equals("background-clip") && !v.equals("text")) {
                    decl(body, name, v, imp);
                    continue;
                }
                if (!hasProperty(r, "-webkit-" + name)) {
                    body.append("-webkit-").append(name).append(':').append(v).append(imp).append(';');
                }
            }
            decl(body, name, value, imp);
        }
        if (!hasTransform && (translate != null || rotate != null || scale != null)) {
            StringBuilder t = new StringBuilder();
            if (translate != null && !translate.equals("none")) {
                List<String> p = splitSpaces(translate);
                t.append("translate(").append(p.get(0)).append(',').append(p.size() > 1 ? p.get(1) : "0").append(')');
            }
            if (rotate != null && !rotate.equals("none")) {
                List<String> p = splitSpaces(rotate);
                if (t.length() > 0) t.append(' ');
                t.append("rotate(").append(p.get(p.size() - 1)).append(')');
            }
            if (scale != null && !scale.equals("none")) {
                List<String> p = splitSpaces(scale);
                if (t.length() > 0) t.append(' ');
                t.append("scale(").append(p.get(0)).append(',').append(p.size() > 1 ? p.get(1) : p.get(0)).append(')');
            }
            if (t.length() > 0) {
                body.append("-webkit-transform:").append(t).append(';');
                body.append("transform:").append(t).append(';');
            }
        }
    }

    private static void decl(StringBuilder b, String name, String value, String imp) {
        b.append(name).append(':').append(value).append(imp).append(';');
    }

    private static void pair(StringBuilder b, String a, String c, String value, String imp) {
        List<String> p = splitSpaces(value);
        if (p.isEmpty()) return;
        decl(b, a, p.get(0), imp);
        decl(b, c, p.size() > 1 ? p.get(1) : p.get(0), imp);
    }

    private static void box(StringBuilder b, String t, String r, String bo, String l, String value, String imp) {
        List<String> p = splitSpaces(value);
        if (p.isEmpty()) return;
        String top = p.get(0);
        String right = p.size() > 1 ? p.get(1) : top;
        String bottom = p.size() > 2 ? p.get(2) : top;
        String left = p.size() > 3 ? p.get(3) : right;
        decl(b, t, top, imp);
        decl(b, r, right, imp);
        decl(b, bo, bottom, imp);
        decl(b, l, left, imp);
    }

    // ------------------------------------------------------------------ var() resolution

    private String lookup(String name, HashMap<String, String> local) {
        String v = local.get(name);
        if (v != null) return v;
        for (int i = scopes.size() - 1; i >= 0; i--) {
            v = scopes.get(i).get(name);
            if (v != null) return v;
        }
        return reg.get(name);
    }

    /** Returns the value with every var() substituted, or null when a reference cannot be resolved. */
    String resolveVars(String value, HashMap<String, String> local, int depth) {
        if (depth > 12) return null;
        int idx = indexOfIgnoreCase(value, "var(", 0);
        if (idx < 0) return value;
        StringBuilder sb = new StringBuilder(value.length() + 32);
        int pos = 0;
        while (idx >= 0) {
            if (idx > 0 && isIdentChar(value.charAt(idx - 1))) {
                // e.g. "somevar(" is not a var() call
                sb.append(value, pos, idx + 4);
                pos = idx + 4;
                idx = indexOfIgnoreCase(value, "var(", pos);
                continue;
            }
            sb.append(value, pos, idx);
            int close = matchParen(value, idx + 3);
            if (close < 0) return null;
            String inner = value.substring(idx + 4, close);
            int comma = indexOfTopLevel(inner, ',');
            String name = (comma < 0 ? inner : inner.substring(0, comma)).trim();
            String fallback = comma < 0 ? null : inner.substring(comma + 1).trim();
            String v = lookup(name, local);
            String resolved = null;
            if (v != null && !v.trim().equalsIgnoreCase("initial")) {
                resolved = resolveVars(v, local, depth + 1);
            }
            if (resolved == null && fallback != null) resolved = resolveVars(fallback, local, depth + 1);
            if (resolved == null) return null;
            sb.append(resolved);
            pos = close + 1;
            idx = indexOfIgnoreCase(value, "var(", pos);
        }
        sb.append(value, pos, value.length());
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int i = s.indexOf(needle, from);
        if (i >= 0) return i;
        return s.toLowerCase(Locale.US).indexOf(needle, from);
    }

    /** Index of the ')' matching the '(' at {@code open} in v, or -1. */
    static int matchParen(String v, int open) {
        int depth = 0;
        for (int i = open; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < v.length() && v.charAt(j) != c) {
                    if (v.charAt(j) == '\\') j++;
                    j++;
                }
                i = j;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static int indexOfTopLevel(String v, char target) {
        int depth = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < v.length() && v.charAt(j) != c) {
                    if (v.charAt(j) == '\\') j++;
                    j++;
                }
                i = j;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == target && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    static List<String> splitTopLevel(String v, char sep) {
        ArrayList<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < v.length() && v.charAt(j) != c) {
                    if (v.charAt(j) == '\\') j++;
                    j++;
                }
                i = j;
            } else if (c == '\\') {
                i++;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == sep && depth == 0) {
                out.add(v.substring(start, i));
                start = i + 1;
            }
        }
        out.add(v.substring(start));
        return out;
    }

    /** Splits on whitespace outside of parentheses and strings. */
    static List<String> splitSpaces(String v) {
        ArrayList<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ws = c == ' ' || c == '\t' || c == '\n' || c == '\r';
            if (c == '"' || c == '\'') {
                if (start < 0) start = i;
                int j = i + 1;
                while (j < v.length() && v.charAt(j) != c) {
                    if (v.charAt(j) == '\\') j++;
                    j++;
                }
                i = j;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (ws && depth == 0) {
                if (start >= 0) {
                    out.add(v.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) out.add(v.substring(start));
        return out;
    }

    // ------------------------------------------------------------------ values

    private static boolean needsValueFix(String v) {
        if (v.indexOf('(') >= 0 || v.indexOf('#') >= 0) return true;
        if (v.indexOf("content") >= 0) return true; // fit-content, min-content, max-content
        int n = v.length();
        for (int i = 1; i < n; i++) {
            char c = v.charAt(i);
            if ((c == 'v' || c == 'q') && i + 1 < n) {
                char p = v.charAt(i - 1);
                if (p == 'd' || p == 's' || p == 'l' || p == 'c') return true;
            }
        }
        return false;
    }

    static String fixValue(String prop, String value) {
        if (value.isEmpty() || !needsValueFix(value)) return value;
        return new ValueFixer(prop, value).fix(value, false);
    }

    private static final class ValueFixer {
        private final boolean sizeProp;

        ValueFixer(String prop, String value) {
            sizeProp = prop.endsWith("width") || prop.endsWith("height") || prop.equals("flex-basis")
                    || prop.endsWith("-size") || prop.equals("grid-template-columns");
        }

        String fix(String v, boolean inCalc) {
            StringBuilder sb = new StringBuilder(v.length() + 8);
            int n = v.length();
            int i = 0;
            while (i < n) {
                char c = v.charAt(i);
                if (c == '"' || c == '\'') {
                    int j = i + 1;
                    while (j < n && v.charAt(j) != c) {
                        if (v.charAt(j) == '\\') j++;
                        j++;
                    }
                    j = Math.min(j + 1, n);
                    sb.append(v, i, j);
                    i = j;
                } else if (c == '#') {
                    int j = i + 1;
                    while (j < n && isHex(v.charAt(j))) j++;
                    int len = j - i - 1;
                    boolean boundary = j >= n || !isIdentChar(v.charAt(j));
                    if (boundary && (len == 4 || len == 8)) {
                        sb.append(hexToRgba(v.substring(i + 1, j)));
                    } else {
                        sb.append(v, i, j);
                    }
                    i = j;
                } else if ((c >= '0' && c <= '9') || (c == '.' && i + 1 < n && Character.isDigit(v.charAt(i + 1)))) {
                    int j = i;
                    while (j < n && (Character.isDigit(v.charAt(j)) || v.charAt(j) == '.')) j++;
                    if (j < n && (v.charAt(j) == 'e' || v.charAt(j) == 'E') && j + 1 < n
                            && (Character.isDigit(v.charAt(j + 1)) || v.charAt(j + 1) == '-')) {
                        j += 2;
                        while (j < n && Character.isDigit(v.charAt(j))) j++;
                    }
                    int k = j;
                    while (k < n && Character.isLetter(v.charAt(k))) k++;
                    sb.append(v, i, j);
                    if (k > j) sb.append(mapUnit(v.substring(j, k)));
                    i = k;
                } else if (isIdentStart(c)) {
                    int j = i;
                    while (j < n && isIdentChar(v.charAt(j))) j++;
                    String ident = v.substring(i, j);
                    if (j < n && v.charAt(j) == '(') {
                        int close = matchParen(v, j);
                        if (close < 0) {
                            sb.append(v, i, n);
                            return sb.toString();
                        }
                        String lname = ident.toLowerCase(Locale.US);
                        if (lname.equals("url")) {
                            sb.append(v, i, close + 1);
                        } else {
                            String inner = v.substring(j + 1, close);
                            sb.append(function(lname, ident, inner, inCalc));
                        }
                        i = close + 1;
                    } else {
                        sb.append(keyword(ident));
                        i = j;
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        private String keyword(String ident) {
            if (!sizeProp) return ident;
            switch (ident) {
                case "fit-content":
                case "min-content":
                case "max-content":
                    return "-webkit-" + ident;
                default:
                    return ident;
            }
        }

        private static String mapUnit(String unit) {
            switch (unit) {
                case "dvh": case "svh": case "lvh": case "vb": case "dvb": case "svb": case "lvb": case "cqh":
                case "cqb":
                    return "vh";
                case "dvw": case "svw": case "lvw": case "vi": case "dvi": case "svi": case "lvi": case "cqw":
                case "cqi":
                    return "vw";
                case "dvmin": case "svmin": case "lvmin": case "cqmin":
                    return "vmin";
                case "dvmax": case "svmax": case "lvmax": case "cqmax":
                    return "vmax";
                default:
                    return unit;
            }
        }

        private String function(String lname, String ident, String inner, boolean inCalc) {
            boolean calcLike = lname.equals("calc") || lname.equals("-webkit-calc");
            String fixedInner = fix(inner, inCalc || calcLike);
            switch (lname) {
                case "calc":
                case "-webkit-calc":
                    return inCalc ? "(" + fixedInner + ")" : "calc(" + fixedInner + ")";
                case "clamp": {
                    List<String> a = splitTopLevel(fixedInner, ',');
                    if (a.size() != 3) return ident + "(" + fixedInner + ")";
                    return wrapExpr(a.get(0).trim(), inCalc);
                }
                case "min":
                case "max": {
                    List<String> a = splitTopLevel(fixedInner, ',');
                    if (a.isEmpty()) return ident + "(" + fixedInner + ")";
                    return wrapExpr(pickMinMax(a, lname.equals("min")), inCalc);
                }
                case "rgb":
                case "rgba":
                case "hsl":
                case "hsla":
                    return legacyColor(lname.startsWith("rgb") ? "rgb" : "hsl", fixedInner);
                case "oklch":
                case "oklab":
                case "lab":
                case "lch":
                case "color":
                case "hwb": {
                    double[] rgba = parseColor(lname + "(" + fixedInner + ")");
                    return rgba == null ? ident + "(" + fixedInner + ")" : rgbaString(rgba);
                }
                case "color-mix":
                    return colorMix(fixedInner);
                case "light-dark": {
                    List<String> a = splitTopLevel(fixedInner, ',');
                    return a.get(0).trim();
                }
                case "env": {
                    List<String> a = splitTopLevel(fixedInner, ',');
                    return a.size() > 1 ? a.get(1).trim() : "0px";
                }
                case "image-set":
                    return "-webkit-image-set(" + fixedInner + ")";
                case "linear-gradient":
                case "radial-gradient":
                case "repeating-linear-gradient":
                case "repeating-radial-gradient":
                case "conic-gradient":
                    return ident + "(" + stripInterpolation(fixedInner) + ")";
                default:
                    return ident + "(" + fixedInner + ")";
            }
        }

        private static String wrapExpr(String e, boolean inCalc) {
            if (e.startsWith("calc(") && matchParen(e, 4) == e.length() - 1) return inCalc ? e.substring(4) : e;
            if (e.startsWith("(") && matchParen(e, 0) == e.length() - 1) return inCalc ? e : "calc" + e;
            boolean expr = e.indexOf(" + ") >= 0 || e.indexOf(" - ") >= 0 || e.indexOf('*') >= 0
                    || (e.indexOf('/') >= 0 && !e.startsWith("url"));
            if (!expr) return e;
            return inCalc ? "(" + e + ")" : "calc(" + e + ")";
        }

        private static String pickMinMax(List<String> args, boolean isMin) {
            String first = args.get(0).trim();
            for (String a : args) {
                String t = a.trim();
                if (t.indexOf('%') >= 0) return t;
            }
            if (isMin) {
                for (String a : args) {
                    String t = a.trim();
                    if (t.indexOf("vw") >= 0 || t.indexOf("vh") >= 0) return t;
                }
                return first;
            }
            for (String a : args) {
                String t = a.trim();
                if (t.indexOf("vw") < 0 && t.indexOf("vh") < 0) return t;
            }
            return first;
        }
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '_' || c > 127;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static final Pattern INTERP = Pattern.compile(
            "\\s*\\bin\\s+(?:oklab|oklch|srgb|srgb-linear|lab|lch|hsl|hwb|xyz|xyz-d50|xyz-d65|display-p3)"
                    + "(?:\\s+(?:shorter|longer|increasing|decreasing)\\s+hue)?\\s*",
            Pattern.CASE_INSENSITIVE);

    private static String stripInterpolation(String inner) {
        if (inner.indexOf(" in ") < 0 && !inner.startsWith("in ")) return inner;
        List<String> args = splitTopLevel(inner, ',');
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (k == 0) {
                a = INTERP.matcher(a).replaceAll(" ").trim();
                if (a.isEmpty()) continue;
            }
            if (sb.length() > 0) sb.append(',');
            sb.append(a);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ colors

    static String hexToRgba(String hex) {
        int r, g, b, a;
        if (hex.length() == 4) {
            r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
            g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
            b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
            a = Integer.parseInt(hex.substring(3, 4), 16) * 17;
        } else {
            r = Integer.parseInt(hex.substring(0, 2), 16);
            g = Integer.parseInt(hex.substring(2, 4), 16);
            b = Integer.parseInt(hex.substring(4, 6), 16);
            a = Integer.parseInt(hex.substring(6, 8), 16);
        }
        return rgbaString(new double[] {r, g, b, a / 255.0});
    }

    static String rgbaString(double[] c) {
        int r = clamp255(c[0]), g = clamp255(c[1]), b = clamp255(c[2]);
        double a = Math.max(0, Math.min(1, c[3]));
        if (a >= 0.999) return "rgb(" + r + "," + g + "," + b + ")";
        return "rgba(" + r + "," + g + "," + b + "," + trimNum(a) + ")";
    }

    private static int clamp255(double v) {
        return (int) Math.round(Math.max(0, Math.min(255, v)));
    }

    private static String trimNum(double v) {
        String s = String.format(Locale.US, "%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** "r g b / a" or "r,g,b,a" into legacy comma syntax. */
    static String legacyColor(String kind, String inner) {
        String t = inner.trim();
        List<String> parts;
        String alpha = null;
        if (t.indexOf(',') >= 0 && t.indexOf('/') < 0) {
            parts = splitTopLevel(t, ',');
        } else {
            int slash = indexOfTopLevel(t, '/');
            if (slash >= 0) {
                alpha = t.substring(slash + 1).trim();
                t = t.substring(0, slash);
            }
            parts = splitSpaces(t);
        }
        if (parts.size() == 4 && alpha == null) {
            alpha = parts.get(3).trim();
            parts = parts.subList(0, 3);
        }
        if (parts.size() != 3) return kind + "(" + inner + ")";
        StringBuilder sb = new StringBuilder();
        sb.append(kind);
        if (alpha != null) sb.append('a');
        sb.append('(');
        for (int k = 0; k < 3; k++) {
            String p = parts.get(k).trim();
            if (p.equals("none")) p = "0";
            if (kind.equals("hsl") && k == 0 && p.endsWith("deg")) p = p.substring(0, p.length() - 3);
            if (kind.equals("hsl") && k > 0 && !p.endsWith("%")) p = p + "%";
            if (k > 0) sb.append(',');
            sb.append(p);
        }
        if (alpha != null) {
            String a = alpha;
            if (a.endsWith("%")) {
                try {
                    a = trimNum(Double.parseDouble(a.substring(0, a.length() - 1)) / 100.0);
                } catch (NumberFormatException e) {
                    a = "1";
                }
            }
            if (a.equals("none")) a = "0";
            sb.append(',').append(a);
        }
        sb.append(')');
        return sb.toString();
    }

    private static double num(String t, double percentScale) {
        t = t.trim().toLowerCase(Locale.US);
        if (t.equals("none") || t.isEmpty()) return 0;
        try {
            if (t.endsWith("%")) return Double.parseDouble(t.substring(0, t.length() - 1)) / 100.0 * percentScale;
            if (t.endsWith("deg")) return Double.parseDouble(t.substring(0, t.length() - 3));
            if (t.endsWith("grad")) return Double.parseDouble(t.substring(0, t.length() - 4)) * 0.9;
            if (t.endsWith("rad")) return Math.toDegrees(Double.parseDouble(t.substring(0, t.length() - 3)));
            if (t.endsWith("turn")) return Double.parseDouble(t.substring(0, t.length() - 4)) * 360;
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double gamma(double x) {
        double ax = Math.abs(x);
        double v = ax <= 0.0031308 ? 12.92 * ax : 1.055 * Math.pow(ax, 1 / 2.4) - 0.055;
        return (x < 0 ? -v : v) * 255;
    }

    private static double[] oklabToRgb(double L, double a, double b) {
        double l_ = L + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = L - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = L - 0.0894841775 * a - 1.2914855480 * b;
        double l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
        return new double[] {
                gamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                gamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)};
    }

    private static double[] labToRgb(double L, double a, double b) {
        // CIE Lab (D50) -> XYZ -> linear sRGB (Bradford adapted to D65).
        double fy = (L + 16) / 116, fx = fy + a / 500, fz = fy - b / 200;
        double e = 216.0 / 24389, k = 24389.0 / 27;
        double x = (fx * fx * fx > e ? fx * fx * fx : (116 * fx - 16) / k) * 0.96422;
        double y = L > k * e ? fy * fy * fy : L / k;
        double z = (fz * fz * fz > e ? fz * fz * fz : (116 * fz - 16) / k) * 0.82521;
        double r = 3.1338561 * x - 1.6168667 * y - 0.4906146 * z;
        double g = -0.9787684 * x + 1.9161415 * y + 0.0334540 * z;
        double bl = 0.0719453 * x - 0.2289914 * y + 1.4052427 * z;
        return new double[] {gamma(r), gamma(g), gamma(bl)};
    }

    private static double[] hslToRgb(double h, double s, double l) {
        h = ((h % 360) + 360) % 360 / 360;
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        return new double[] {hue(p, q, h + 1 / 3.0) * 255, hue(p, q, h) * 255, hue(p, q, h - 1 / 3.0) * 255};
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6.0) return p + (q - p) * 6 * t;
        if (t < 1 / 2.0) return q;
        if (t < 2 / 3.0) return p + (q - p) * (2 / 3.0 - t) * 6;
        return p;
    }

    /** Parses a color into {r,g,b (0-255), alpha (0-1)} or null when unknown. */
    static double[] parseColor(String c) {
        String t = c.trim().toLowerCase(Locale.US);
        if (t.isEmpty()) return null;
        if (t.charAt(0) == '#') {
            String h = t.substring(1);
            try {
                if (h.length() == 3 || h.length() == 4) {
                    double a = h.length() == 4 ? Integer.parseInt(h.substring(3, 4), 16) * 17 / 255.0 : 1;
                    return new double[] {Integer.parseInt(h.substring(0, 1), 16) * 17,
                            Integer.parseInt(h.substring(1, 2), 16) * 17, Integer.parseInt(h.substring(2, 3), 16) * 17, a};
                }
                if (h.length() == 6 || h.length() == 8) {
                    double a = h.length() == 8 ? Integer.parseInt(h.substring(6, 8), 16) / 255.0 : 1;
                    return new double[] {Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16),
                            Integer.parseInt(h.substring(4, 6), 16), a};
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return null;
        }
        switch (t) {
            case "transparent": return new double[] {0, 0, 0, 0};
            case "white": return new double[] {255, 255, 255, 1};
            case "black": return new double[] {0, 0, 0, 1};
            case "red": return new double[] {255, 0, 0, 1};
            case "green": return new double[] {0, 128, 0, 1};
            case "blue": return new double[] {0, 0, 255, 1};
            case "gray": case "grey": return new double[] {128, 128, 128, 1};
            default: break;
        }
        int open = t.indexOf('(');
        if (open < 0 || !t.endsWith(")")) return null;
        String fn = t.substring(0, open);
        String inner = t.substring(open + 1, t.length() - 1).trim();
        double alpha = 1;
        List<String> parts;
        int slash = indexOfTopLevel(inner, '/');
        if (slash >= 0) {
            alpha = num(inner.substring(slash + 1), 1);
            inner = inner.substring(0, slash);
        }
        parts = inner.indexOf(',') >= 0 ? splitTopLevel(inner, ',') : splitSpaces(inner);
        if (fn.equals("color")) {
            if (parts.size() < 4) return null;
            parts = parts.subList(1, 4);
            double[] r = {num(parts.get(0), 1) * 255, num(parts.get(1), 1) * 255, num(parts.get(2), 1) * 255, alpha};
            return valid(r);
        }
        if (parts.size() == 4 && slash < 0) {
            alpha = num(parts.get(3), 1);
            parts = parts.subList(0, 3);
        }
        if (parts.size() != 3) return null;
        double[] rgb;
        switch (fn) {
            case "rgb":
            case "rgba":
                rgb = new double[] {num(parts.get(0), 255), num(parts.get(1), 255), num(parts.get(2), 255)};
                break;
            case "hsl":
            case "hsla":
                rgb = hslToRgb(num(parts.get(0), 360), num(parts.get(1), 1) / (parts.get(1).trim().endsWith("%") ? 1 : 100),
                        num(parts.get(2), 1) / (parts.get(2).trim().endsWith("%") ? 1 : 100));
                break;
            case "oklab":
                rgb = oklabToRgb(num(parts.get(0), 1), num(parts.get(1), 0.4), num(parts.get(2), 0.4));
                break;
            case "oklch": {
                double L = num(parts.get(0), 1), C = num(parts.get(1), 0.4), H = Math.toRadians(num(parts.get(2), 360));
                rgb = oklabToRgb(L, C * Math.cos(H), C * Math.sin(H));
                break;
            }
            case "lab":
                rgb = labToRgb(num(parts.get(0), 100), num(parts.get(1), 125), num(parts.get(2), 125));
                break;
            case "lch": {
                double L = num(parts.get(0), 100), C = num(parts.get(1), 150), H = Math.toRadians(num(parts.get(2), 360));
                rgb = labToRgb(L, C * Math.cos(H), C * Math.sin(H));
                break;
            }
            case "hwb": {
                double h = num(parts.get(0), 360), w = num(parts.get(1), 1), b = num(parts.get(2), 1);
                if (w + b >= 1) {
                    double gray = w / (w + b) * 255;
                    rgb = new double[] {gray, gray, gray};
                } else {
                    double[] base = hslToRgb(h, 1, 0.5);
                    for (int k = 0; k < 3; k++) base[k] = (base[k] / 255 * (1 - w - b) + w) * 255;
                    rgb = base;
                }
                break;
            }
            default:
                return null;
        }
        return valid(new double[] {rgb[0], rgb[1], rgb[2], Double.isNaN(alpha) ? 1 : alpha});
    }

    private static double[] valid(double[] c) {
        for (double v : c) if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return c;
    }

    static String colorMix(String inner) {
        List<String> args = splitTopLevel(inner, ',');
        if (args.size() < 3) return args.size() > 1 ? args.get(1).trim() : "transparent";
        String[] colors = new String[2];
        double[] pct = new double[] {Double.NaN, Double.NaN};
        for (int k = 0; k < 2; k++) {
            List<String> p = splitSpaces(args.get(k + 1).trim());
            StringBuilder color = new StringBuilder();
            for (String tok : p) {
                if (tok.endsWith("%") && tok.length() > 1 && (Character.isDigit(tok.charAt(0)) || tok.charAt(0) == '.')) {
                    pct[k] = num(tok, 1);
                } else {
                    if (color.length() > 0) color.append(' ');
                    color.append(tok);
                }
            }
            colors[k] = color.toString();
        }
        double p1 = pct[0], p2 = pct[1];
        if (Double.isNaN(p1) && Double.isNaN(p2)) { p1 = 0.5; p2 = 0.5; }
        else if (Double.isNaN(p1)) p1 = 1 - p2;
        else if (Double.isNaN(p2)) p2 = 1 - p1;
        double[] a = parseColor(colors[0]);
        double[] b = parseColor(colors[1]);
        if (a == null || b == null) return colors[0].isEmpty() ? colors[1] : colors[0];
        double sum = p1 + p2;
        if (sum <= 0) return rgbaString(a);
        double w1 = p1 / sum, w2 = p2 / sum;
        double alphaScale = Math.min(1, sum);
        return rgbaString(new double[] {a[0] * w1 + b[0] * w2, a[1] * w1 + b[1] * w2, a[2] * w1 + b[2] * w2,
                (a[3] * w1 + b[3] * w2) * alphaScale});
    }

    // ------------------------------------------------------------------ selectors

    private static final String[] UNSUPPORTED_SELECTORS = {":has(", ":focus-within", "::marker", ":defined",
            "::part(", "::slotted(", ":host", ":dir(", ":modal", ":popover-open", "::backdrop", ":user-invalid",
            ":user-valid", "::-moz-", ":-moz-", "::-ms-", ":-ms-", "::view-transition", ":state(", "::highlight(",
            " of ", ":placeholder-shown", "::cue", ":picture-in-picture", ":open", ":closed", "::spelling-error",
            "::grammar-error", "::target-text", ":blank", ":local-link", ":current", ":past", ":future",
            ":playing", ":paused", ":has-slotted", "::details-content", "::scroll-"};

    /** Returns a selector list Chromium 30 accepts, or null when nothing survives. */
    static String fixSelectorList(String sel) {
        if (sel == null) return null;
        if (sel.indexOf(':') < 0 && sel.indexOf('&') < 0) return sel;
        List<String> parts = splitTopLevel(sel, ',');
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) expandSelector(p.trim(), out, 0);
        if (out.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String p : out) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }

    private static void expandSelector(String p, List<String> out, int depth) {
        if (p.isEmpty() || out.size() > 64) return;
        if (p.indexOf(':') < 0) {
            out.add(p);
            return;
        }
        String lower = p.toLowerCase(Locale.US);
        int is = findPseudoFunction(lower, new String[] {":is(", ":where(", ":matches(", ":-webkit-any(", ":any("});
        if (is >= 0 && depth < 4) {
            int open = lower.indexOf('(', is);
            int close = matchParen(p, open);
            if (close < 0) return;
            String prefix = p.substring(0, is);
            String suffix = p.substring(close + 1);
            for (String alt : splitTopLevel(p.substring(open + 1, close), ',')) {
                String a = alt.trim();
                if (a.isEmpty()) continue;
                expandSelector(prefix + a + suffix, out, depth + 1);
            }
            return;
        }
        String q = p;
        if (lower.indexOf(":not(") >= 0) q = splitNot(q);
        if (q == null) return;
        q = q.replace(":focus-visible", ":focus")
                .replace("::placeholder", "::-webkit-input-placeholder")
                .replace(":any-link", ":link")
                .replace(":fullscreen", ":-webkit-full-screen")
                .replace("::file-selector-button", "::-webkit-file-upload-button")
                .replace(":autofill", ":-webkit-autofill")
                .replace(":-webkit-autofill", ":-webkit-autofill");
        String ql = q.toLowerCase(Locale.US);
        for (String bad : UNSUPPORTED_SELECTORS) {
            if (ql.indexOf(bad) >= 0) return;
        }
        out.add(q);
    }

    private static int findPseudoFunction(String lower, String[] names) {
        int best = -1;
        for (String n : names) {
            int i = lower.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** :not(a, b) -> :not(a):not(b). Returns null when an argument is complex (unsupported in Chromium 30). */
    private static String splitNot(String p) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        String lower = p.toLowerCase(Locale.US);
        while (true) {
            int idx = lower.indexOf(":not(", i);
            if (idx < 0) {
                sb.append(p, i, p.length());
                return sb.toString();
            }
            int open = idx + 4;
            int close = matchParen(p, open);
            if (close < 0) return null;
            sb.append(p, i, idx);
            for (String arg : splitTopLevel(p.substring(open + 1, close), ',')) {
                String a = arg.trim();
                if (a.isEmpty()) continue;
                if (a.indexOf(' ') >= 0 || a.indexOf('>') >= 0 || a.indexOf('+') >= 0 || a.indexOf('~') >= 0) {
                    return null;
                }
                sb.append(":not(").append(a).append(')');
            }
            i = close + 1;
        }
    }

    /** Flattens a nested selector list against its parent list (CSS nesting). */
    static String combineSelectors(String parent, String child) {
        if (parent == null || child == null) return null;
        List<String> ps = splitTopLevel(parent, ',');
        List<String> cs = splitTopLevel(child, ',');
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String c : cs) {
            String ct = c.trim();
            for (String p : ps) {
                String pt = p.trim();
                String combined = ct.indexOf('&') >= 0 ? ct.replace("&", pt) : pt + " " + ct;
                if (sb.length() > 0) sb.append(',');
                sb.append(combined);
                if (++count >= 64) return sb.toString();
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ media / import preludes

    private static final Pattern RANGE_DOUBLE = Pattern.compile(
            "\\(\\s*([^()<>=]+?)\\s*(<=?)\\s*(width|height|device-width|device-height|aspect-ratio|resolution)\\s*(<=?)\\s*([^()<>=]+?)\\s*\\)");
    private static final Pattern RANGE_NAME_FIRST = Pattern.compile(
            "\\(\\s*(width|height|device-width|device-height|aspect-ratio|resolution)\\s*(>=|<=|>|<|=)\\s*([^()<>=]+?)\\s*\\)");
    private static final Pattern RANGE_VALUE_FIRST = Pattern.compile(
            "\\(\\s*([^()<>=]+?)\\s*(>=|<=|>|<)\\s*(width|height|device-width|device-height|aspect-ratio|resolution)\\s*\\)");

    static String fixMedia(String prelude) {
        if (prelude.indexOf('<') < 0 && prelude.indexOf('>') < 0 && prelude.indexOf('=') < 0) return prelude;
        String p = prelude;
        Matcher m = RANGE_DOUBLE.matcher(p);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "(min-" + m.group(3) + ":" + m.group(1).trim() + ") and (max-" + m.group(3) + ":" + m.group(5).trim() + ")"));
        }
        m.appendTail(sb);
        p = sb.toString();
        m = RANGE_NAME_FIRST.matcher(p);
        sb = new StringBuffer();
        while (m.find()) {
            String op = m.group(2);
            String feature = m.group(1);
            String prefix = op.startsWith(">") ? "min-" : op.startsWith("<") ? "max-" : "";
            m.appendReplacement(sb, Matcher.quoteReplacement("(" + prefix + feature + ":" + m.group(3).trim() + ")"));
        }
        m.appendTail(sb);
        p = sb.toString();
        m = RANGE_VALUE_FIRST.matcher(p);
        sb = new StringBuffer();
        while (m.find()) {
            String op = m.group(2);
            String prefix = op.startsWith("<") ? "min-" : "max-";
            m.appendReplacement(sb, Matcher.quoteReplacement("(" + prefix + m.group(3) + ":" + m.group(1).trim() + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final Pattern IMPORT_EXTRAS = Pattern.compile(
            "\\s+(?:layer\\([^)]*\\)|layer|supports\\((?:[^()]|\\([^()]*\\))*\\))(?=\\s|$)", Pattern.CASE_INSENSITIVE);

    static String fixImport(String prelude) {
        return IMPORT_EXTRAS.matcher(prelude).replaceAll("");
    }
}
