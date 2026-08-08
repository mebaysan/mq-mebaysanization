#!/usr/bin/env bash
#
# Proves the core promise of this project: the shipped fat JAR runs on any machine with only a
# Java 21 JRE. Two independent gates:
#
#   1. jakarta.jms-api must resolve to 3.1.0 and NEVER 2.0.3. Version 2.0.3 under the `jakarta.jms`
#      groupId is still the javax namespace, and `artemis-jms-client` (the wrong Artemis artifact)
#      drags it in. That mistake compiles cleanly and shares every class name with the correct
#      `artemis-jakarta-client`, so it fails only at runtime as NoClassDefFoundError.
#
#   2. No dependency may carry a platform classifier, and no jar bundled in the fat JAR may require a
#      native binary FROM THE HOST.
#
#      That second clause used to read "may contain a native binary", which is a stricter rule than the
#      promise needs. Kafka's compression codecs - zstd-jni, snappy-java and lz4-java, all pulled in at
#      runtime scope by kafka-clients and all non-optional - carry ~48 native files between them, but
#      they carry them for EVERY platform (linux/darwin/windows/freebsd, x86_64 and aarch64) and
#      extract the right one to a temp directory at load time. Nothing has to be installed on the
#      host, which is the thing the promise is actually about.
#
#      That is materially different from what this gate was written to catch: activemq-artemis-native
#      (linux only) and netty's epoll/kqueue transports (linux-x86_64 / osx-x86_64 only), which are
#      excluded in pom.xml precisely because they are single-platform. So the three codecs are
#      allowlisted BY NAME, and each one is then positively checked to prove it really does cover
#      linux, macOS and Windows - a single-platform native slipping into the allowlist would break the
#      promise silently, and that check is the whole point of the exception.
#
#      Excluding them instead was considered and rejected: the app would then throw
#      NoClassDefFoundError on any topic whose batches use snappy, zstd or lz4, which is the norm in
#      production Kafka. Cost of keeping them: about 9.5 MB of JAR.
#
# Usage:  ./scripts/verify-portability.sh          (builds if target/mq-mebaysanization.jar is missing)
#
set -uo pipefail
cd "$(dirname "$0")/.."

JAR="target/mq-mebaysanization.jar"
TREE="target/dependency-tree.txt"
fail=0

# Multi-platform, self-extracting native carriers. Matched against the bundled jar's basename.
NATIVE_ALLOWLIST='^(zstd-jni|snappy-java|lz4-java)-'

echo "==> Gate 1: jakarta.jms-api namespace"
mvn -B -q dependency:tree -DoutputFile="$TREE" >/dev/null 2>&1 || { echo "  FAIL: dependency:tree errored"; exit 1; }
jms_lines=$(grep -i "jakarta.jms-api" "$TREE" || true)
echo "$jms_lines" | sed 's/^/    /'
if echo "$jms_lines" | grep -q "jakarta.jms-api:jar:2\."; then
    echo "  FAIL: jakarta.jms-api 2.x present -> the javax namespace leaked in."
    echo "        Almost certainly org.apache.activemq:artemis-jms-client instead of artemis-jakarta-client."
    fail=1
elif echo "$jms_lines" | grep -q "jakarta.jms-api:jar:3\."; then
    echo "  PASS"
else
    echo "  FAIL: jakarta.jms-api not found at all"
    fail=1
fi

echo
echo "==> Gate 2a: platform-classifier dependencies"
# netty-transport-native-unix-common is deliberately allowed: despite the name it is pure Java
# (the shared io.netty.channel.unix class layer required by netty-handler) and ships zero binaries.
hits=$(grep -Ei 'linux|osx|windows|epoll|kqueue|:x86|aarch|artemis-native' "$TREE" \
       | grep -v 'netty-transport-native-unix-common' || true)
if [ -n "$hits" ]; then
    echo "$hits" | sed 's/^/    /'
    echo "  FAIL: platform-specific artifacts present"
    fail=1
else
    echo "    (none)"
    echo "  PASS"
fi

echo
echo "==> Gate 2b: no native binary is required from the host"
if [ ! -f "$JAR" ]; then
    echo "    $JAR missing - building..."
    mvn -B -q clean package -DskipTests >/dev/null 2>&1 || { echo "  FAIL: build errored"; exit 1; }
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -q -o "$JAR" 'BOOT-INF/lib/*' -d "$work"

libs=$(find "$work/BOOT-INF/lib" -name '*.jar' | wc -l | tr -d ' ')
unexpected=""
allowed_seen=""
incomplete=""

while IFS= read -r lib; do
    name=$(basename "$lib")
    found=$(unzip -l "$lib" 2>/dev/null | grep -Eio '[^ ]+\.(so|dylib|dll|jnilib)$' || true)
    [ -z "$found" ] && continue

    if ! echo "$name" | grep -Eq "$NATIVE_ALLOWLIST"; then
        unexpected="${unexpected}    $name: $(echo "$found" | tr '\n' ' ')\n"
        continue
    fi

    # Allowlisted: prove it is genuinely multi-platform rather than trusting the name.
    #
    # Matched on the PATH, not the file extension. lz4-java names its Windows library
    # net/jpountz/util/win32/amd64/liblz4-java.so - a ".so" that is in fact a DLL, a quirk it
    # inherits from upstream org.lz4:lz4-java. An extension check calls that jar linux-only and
    # fails a build that is actually fine.
    count=$(echo "$found" | wc -l | tr -d ' ')
    missing=""
    echo "$found" | grep -Eqi '(^|/)linux/'            || missing="$missing linux"
    echo "$found" | grep -Eqi '(^|/)(darwin|mac)/'     || missing="$missing macos"
    echo "$found" | grep -Eqi '(^|/)win(32|64|dows)?/' || missing="$missing windows"
    if [ -n "$missing" ]; then
        incomplete="${incomplete}    $name: no native for:$missing\n"
    else
        allowed_seen="${allowed_seen}    $name: $count natives, covering linux + macOS + Windows\n"
    fi
done < <(find "$work/BOOT-INF/lib" -name '*.jar')

echo "    scanned $libs bundled jars"
[ -n "$allowed_seen" ] && printf "%b" "$allowed_seen"

if [ -n "$unexpected" ]; then
    printf "%b" "$unexpected"
    echo "  FAIL: a jar ships native binaries and is not on the allowlist."
    echo "        Either exclude it, or add it to NATIVE_ALLOWLIST only if it carries every platform."
    fail=1
elif [ -n "$incomplete" ]; then
    printf "%b" "$incomplete"
    echo "  FAIL: an allowlisted jar is single-platform, so the JAR would need something from the host."
    fail=1
else
    echo "    every bundled native is multi-platform and self-extracting"
    echo "  PASS"
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "ALL GATES PASS - the JAR needs nothing but a Java 21 JRE."
else
    echo "GATES FAILED"
fi
exit "$fail"
