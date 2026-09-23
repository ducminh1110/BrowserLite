package com.browserlite.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CssCompatTest {
    private static String t(String css) {
        return CssCompat.transform(css, new CssCompat.VarRegistry(), new CssCompat.Options());
    }

    @Test public void resolvesRootVariables() {
        String out = t(":root{--c:#123456;--pad:4px}a{color:var(--c);padding:var(--pad) var(--missing,2px)}");
        assertEquals("a{color:#123456;padding:4px 2px;}", out);
    }

    @Test public void ruleLocalVariablesWin() {
        String out = t(":root{--x:1px}.a{--x:2px;margin:var(--x)}.b{margin:var(--x)}");
        assertEquals(".a{margin:2px;}.b{margin:1px;}", out);
    }

    @Test public void unresolvedWithoutFallbackIsDropped() {
        assertEquals("a{color:red;}", t("a{color:red;background:var(--nope)}"));
    }

    @Test public void nestedFallbacks() {
        assertEquals("a{color:blue;}", t("a{color:var(--a, var(--b, blue))}"));
    }

    @Test public void darkModeVarsIgnored() {
        String out = t(":root{--bg:white}@media (prefers-color-scheme: dark){:root{--bg:black}}.dark{--bg:#111}body{background:var(--bg)}");
        assertTrue(out, out.contains("body{background:white;}"));
    }

    @Test public void propertyInitialValue() {
        String out = t("@property --tw-x{syntax:'*';inherits:false;initial-value:0}.a{translate:var(--tw-x) 2px}");
        assertEquals(".a{-webkit-transform:translate(0,2px);transform:translate(0,2px);}", out);
    }

    @Test public void unwrapsLayers() {
        assertEquals("a{color:red;}b{color:blue;}", t("@layer base, utilities;@layer base{a{color:red}}@layer utilities{b{color:blue}}"));
    }

    @Test public void dropsContainerAndProperty() {
        assertEquals("a{top:0;}", t("@container (min-width:1px){a{color:red}}@property --x{syntax:'*'}a{top:0}"));
    }

    @Test public void expandsIsWhere() {
        assertEquals(".a h1 span,.a h2 span{color:red;}", t(".a :is(h1,h2) span{color:red}"));
        assertEquals("p{margin:0;}", t(":where(p){margin:0}"));
    }

    @Test public void splitsNotLists() {
        assertEquals("a:not(.x):not(.y){color:red;}", t("a:not(.x, .y){color:red}"));
    }

    @Test public void dropsUnsupportedSelectorPartsOnly() {
        assertEquals("a:hover{color:red;}", t("a:hover, a:has(b){color:red}"));
        assertEquals("", t("a:has(b){color:red}"));
        assertEquals("a:focus{outline:0;}", t("a:focus-visible{outline:0}"));
    }

    @Test public void flattensNesting() {
        assertEquals(".card{padding:1px;}.card:hover{color:red;}.card .t{margin:0;}",
                t(".card{padding:1px;&:hover{color:red}.t{margin:0}}"));
        assertEquals(".a{color:red;}@media (min-width:10px){.a{color:blue;}}",
                t(".a{color:red;@media (min-width:10px){color:blue}}"));
    }

    @Test public void mediaRangeSyntax() {
        assertEquals("(min-width:40rem)", CssCompat.fixMedia("(width >= 40rem)"));
        assertEquals("(max-width:600px)", CssCompat.fixMedia("(width < 600px)"));
        assertEquals("(min-width:400px) and (max-width:700px)", CssCompat.fixMedia("(400px <= width <= 700px)"));
        assertEquals("screen and (min-width:10px)", CssCompat.fixMedia("screen and (10px <= width)"));
    }

    @Test public void colors() {
        assertEquals("a{color:rgba(0,0,0,0);}", t("a{color:#0000}"));
        assertEquals("a{color:rgba(255,0,0,0.502);}", t("a{color:#ff000080}"));
        assertEquals("a{color:rgba(0,0,0,0.5);}", t("a{color:rgb(0 0 0 / 50%)}"));
        assertEquals("a{color:rgba(1,2,3,.4);}", t("a{color:rgb(1,2,3,.4)}"));
        String ok = t("a{color:oklch(62.8% 0.2577 29.23)}");
        assertTrue(ok, ok.startsWith("a{color:rgb(255,0,0)") || ok.startsWith("a{color:rgb(254,0,0)") || ok.startsWith("a{color:rgb(255,0,1)"));
        assertEquals("a{color:rgb(255,255,255);}", t("a{color:oklch(1 0 0)}"));
        assertEquals("a{color:rgb(128,128,128);}", t("a{color:color-mix(in srgb, white, black)}"));
        assertEquals("a{color:rgb(255,255,255);}", t("a{color:light-dark(rgb(255,255,255), black)}"));
    }

    @Test public void urlsUntouched() {
        assertEquals("a{background:url(data:image/png;base64,#abcd);}", t("a{background:url(data:image/png;base64,#abcd)}"));
        assertEquals("a{background:url('x.png');color:#abc;}", t("a{background:url('x.png');color:#abc}"));
    }

    @Test public void mathFunctions() {
        assertEquals("h1{font-size:1.5rem;}", t("h1{font-size:clamp(1.5rem, 1.5rem + ((1vw - 0.2rem) * 1.5), 2.5rem)}"));
        assertEquals("a{width:100%;}", t("a{width:min(100%, 1200px)}"));
        assertEquals("a{width:calc(100% - 2rem);}", t("a{width:min(calc(100% - 2rem), 1200px)}"));
        assertEquals("a{padding:1rem;}", t("a{padding:max(1rem, 3vw)}"));
        assertEquals("a{width:calc(10px + (1px + 2px));}", t("a{width:calc(10px + calc(1px + 2px))}"));
        assertEquals("a{width:calc(1px + (2rem + 1px));}", t("a{width:calc(1px + clamp(2rem + 1px, 3vw, 4rem))}"));
    }

    @Test public void units() {
        assertEquals("a{height:100vh;min-height:50vh;}", t("a{height:100dvh;min-height:50svh}"));
        assertEquals("a{width:-webkit-fit-content;}", t("a{width:fit-content}"));
    }

    @Test public void prefixes() {
        assertEquals("a{-webkit-transform:translate(1px);transform:translate(1px);}", t("a{transform:translate(1px)}"));
        assertEquals("@-webkit-keyframes f{from{opacity:0;}to{-webkit-transform:none;transform:none;}}",
                t("@keyframes f{from{opacity:0}to{transform:none}}"));
        assertEquals("a{-webkit-user-select:none;user-select:none;}", t("a{user-select:none}"));
    }

    @Test public void logicalAndShorthands() {
        assertEquals("a{top:0;right:0;bottom:0;left:0;}", t("a{inset:0}"));
        assertEquals("a{margin-left:1px;margin-right:2px;}", t("a{margin-inline:1px 2px}"));
        assertEquals("a{padding-left:3px;}", t("a{padding-inline-start:3px}"));
        assertEquals("a{align-items:center;justify-items:center;}", t("a{place-items:center}"));
        assertEquals("a{width:1px;}", t("a{inline-size:1px;aspect-ratio:1}"));
    }

    @Test public void importantKept() {
        assertEquals("a{color:red!important;}", t(":root{--r:red}a{color:var(--r) !important}"));
    }

    @Test public void fontsBlocked() {
        CssCompat.Options o = new CssCompat.Options();
        o.blockFonts = true;
        assertEquals("a{top:0;}", CssCompat.transform("@font-face{font-family:x;src:url(x.woff2)}a{top:0}", null, o));
    }

    @Test public void importLayerStripped() {
        assertEquals("@import url(a.css);", t("@import url(a.css) layer(base);"));
    }

    @Test public void registrySharedAcrossSheets() {
        CssCompat.VarRegistry reg = new CssCompat.VarRegistry();
        CssCompat.transform(":root{--brand:#f00}", reg, null);
        assertEquals("a{color:#f00;}", CssCompat.transform("a{color:var(--brand)}", reg, null));
    }

    @Test public void survivesGarbage() {
        String out = t("a{color:red;;}}}b{{c:d}@media{");
        assertFalse(out.isEmpty());
        t("");
        t("@media screen{");
        t("a{b:url(");
        t("/* unterminated");
    }

    @Test public void tailwindV4Like() {
        String css = "@layer theme{:root,:host{--color-red-500:oklch(63.7% 0.237 25.331);--spacing:0.25rem}}"
                + "@layer utilities{.p-4{padding:calc(var(--spacing) * 4)}.text-red-500{color:var(--color-red-500)}"
                + ".sm\\:flex{@media (width >= 40rem){display:flex}}}";
        String out = t(css);
        assertTrue(out, out.contains(".p-4{padding:calc(0.25rem * 4);}"));
        assertTrue(out, out.contains(".text-red-500{color:rgb("));
        assertTrue(out, out.contains("@media (min-width:40rem){.sm\\:flex{display:flex;}}"));
    }

    @Test public void modifierRulesGetConsumerDeclarations() {
        String out = t(".btn{--b:transparent;background-color:var(--b)}.btn-primary{--b:#0d6efd}");
        assertEquals(".btn{background-color:transparent;}.btn-primary{background-color:#0d6efd;}", out);
    }
}
