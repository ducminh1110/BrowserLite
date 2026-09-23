#!/usr/bin/env bash
# Minifies the injected page scripts (app/src/main/js) into app/src/main/assets/web.
# Output stays ES5 so the Android 4.4 WebView (Chromium 30) can parse it. Needs Node.js.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=app/src/main/js
OUT=app/src/main/assets/web
TOOLS=build/web-tools
mkdir -p "$OUT" "$TOOLS"
if [ ! -d "$TOOLS/node_modules/terser" ] || [ ! -d "$TOOLS/node_modules/acorn" ]; then
  npm install --prefix "$TOOLS" --no-save --silent terser@5 acorn@8 >/dev/null
fi
for f in polyfill page reader; do
  "$TOOLS/node_modules/.bin/terser" "$SRC/$f.js" --ecma 5 --compress passes=2 --mangle --comments false \
    --preamble "/* BrowserLite $f.js - generated from app/src/main/js/$f.js by tools/build-web.sh */" -o "$OUT/$f.js"
done
# Fail loudly if anything newer than ES5 slipped in.
node -e '
const acorn = require(process.argv[1]); const fs = require("fs");
for (const f of process.argv.slice(2)) {
  try { acorn.parse(fs.readFileSync(f, "utf8"), {ecmaVersion: 5}); }
  catch (e) { console.error(f + ": not ES5: " + e.message); process.exit(1); }
}' "$PWD/$TOOLS/node_modules/acorn" "$OUT"/polyfill.js "$OUT"/page.js "$OUT"/reader.js "$SRC"/*.js
wc -c "$OUT"/*.js
