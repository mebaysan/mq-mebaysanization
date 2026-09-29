#!/usr/bin/env bash
# Rebuild the portable Windows package for a NEW version WITHOUT jpackage, by reusing the launch4j .exe
# and the bundled Windows JRE from an existing -win.zip and swapping in the freshly built app.
#
# WHY THIS EXISTS
#   jpackage only builds for the OS it runs on, so a Windows package cannot be produced on macOS/Linux.
#   But the launch4j .exe and the bundled jre/ in a prior -win.zip are version-independent: only the app
#   (the extracted fat JAR: a thin launcher jar + lib/) changes between releases. So we take a known-good
#   -win.zip as a TEMPLATE, drop the new app/ in, repoint the launcher at the new jar, and re-zip.
#
# USAGE
#   scripts/repackage-win.sh [FAT_JAR] [TEMPLATE_WIN_ZIP] [OUT_ZIP]
#   Defaults:
#     FAT_JAR          = target/mq-mebaysanization-<version>.jar   (newest match)
#     TEMPLATE_WIN_ZIP = ~/Desktop/MQ-mebaysanization-1.1.5-win.zip
#     OUT_ZIP          = ~/Desktop/MQ-mebaysanization-<version>-win.zip
#
# REQUIRES: a local JDK 21 `java` (for `jarmode=tools extract`), `unzip`, `zip`, `perl`.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_NAME="MQ mebaysanization"

FAT_JAR="${1:-}"
TEMPLATE_ZIP="${2:-$HOME/Desktop/MQ-mebaysanization-1.1.5-win.zip}"
OUT_ZIP="${3:-}"

# Locate the fat JAR (skip the *-original.jar Maven leaves behind).
if [[ -z "$FAT_JAR" ]]; then
  FAT_JAR="$(ls -1 "$PROJECT_DIR"/target/mq-mebaysanization-*.jar 2>/dev/null | grep -v original | sort | tail -1 || true)"
fi
[[ -f "$FAT_JAR" ]] || { echo "ERROR: fat JAR not found. Build it first: mvn clean package"; exit 1; }
[[ -f "$TEMPLATE_ZIP" ]] || { echo "ERROR: template win.zip not found: $TEMPLATE_ZIP"; exit 1; }

# Derive the new version from the fat JAR filename.
NEW_JAR="$(basename "$FAT_JAR")"                       # mq-mebaysanization-1.1.8.jar
NEW_VER="$(echo "$NEW_JAR" | sed -E 's/^mq-mebaysanization-(.+)\.jar$/\1/')"
[[ -n "$OUT_ZIP" ]] || OUT_ZIP="$HOME/Desktop/MQ-mebaysanization-${NEW_VER}-win.zip"

echo "Fat JAR : $FAT_JAR"
echo "Template: $TEMPLATE_ZIP"
echo "Output  : $OUT_ZIP"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Unpacking template..."
unzip -q "$TEMPLATE_ZIP" -d "$WORK/template"
APPIMG="$WORK/template/$APP_NAME"
[[ -d "$APPIMG" ]] || { echo "ERROR: template has no '$APP_NAME' folder"; exit 1; }

# What jar name does the template currently reference (e.g. mq-mebaysanization-1.1.5.jar)?
OLD_JAR="$(basename "$(ls -1 "$APPIMG"/app/mq-mebaysanization-*.jar | head -1)")"
echo "==> Template app jar: $OLD_JAR  ->  new: $NEW_JAR"

echo "==> Extracting fat JAR into thin jar + lib/ ..."
rm -rf "$WORK/extract"
java -Djarmode=tools -jar "$FAT_JAR" extract --destination "$WORK/extract" >/dev/null

echo "==> Swapping app/ ..."
rm -rf "$APPIMG/app"
mkdir -p "$APPIMG/app"
cp "$WORK/extract/$NEW_JAR" "$APPIMG/app/"
cp -R "$WORK/extract/lib" "$APPIMG/app/lib"

echo "==> Repointing launchers ($OLD_JAR -> $NEW_JAR) ..."
# The .bat is text: a plain substitution.
find "$APPIMG" -maxdepth 1 -iname "*.bat" -print0 | while IFS= read -r -d '' bat; do
  perl -0777 -pi -e "s/\Q$OLD_JAR\E/$NEW_JAR/g" "$bat"
done
# The .exe (launch4j) embeds the jar path as a fixed-length ASCII string. An in-place byte replacement is
# safe ONLY when the two names are the same length; otherwise the PE layout would shift. If they differ,
# leave the .exe alone and rely on Baslat-yedek.bat (still repointed above), and say so loudly.
EXE="$APPIMG/$APP_NAME.exe"
if [[ -f "$EXE" ]]; then
  if [[ ${#OLD_JAR} -eq ${#NEW_JAR} ]]; then
    perl -0777 -pi -e "s/\Q$OLD_JAR\E/$NEW_JAR/g" "$EXE"
    echo "    .exe patched (same-length name)."
  else
    echo "    WARNING: '$OLD_JAR' and '$NEW_JAR' differ in length; .exe NOT patched."
    echo "             Use Baslat-yedek.bat to launch, or regenerate the .exe with launch4j."
  fi
fi

echo "==> Zipping -> $OUT_ZIP"
find "$WORK/template" -name ".DS_Store" -delete 2>/dev/null || true
rm -f "$OUT_ZIP"
( cd "$WORK/template" && zip -q -r -X "$OUT_ZIP" "$APP_NAME" -x "*.DS_Store" )

echo "Done. $(du -h "$OUT_ZIP" | cut -f1)  $OUT_ZIP"
