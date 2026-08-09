#!/usr/bin/env bash
#
# Regenerates the gallery in README.md (docs/screenshots/*.png).
#
# Run it after any change that alters the UI, so the README shows the app as it is rather than as it
# was. Everything it touches is disposable: a throwaway in-memory broker, a scratch data directory
# under target/, and a port that is not 8080. It never reads or writes the real ./data.
#
#   ./scripts/capture-screenshots.sh
#
# Requires: a built JAR (mvn clean package), a JDK, and Google Chrome. macOS/Linux.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/docs/screenshots"
WORK="$ROOT/target/screenshot-run"
PORT=18080
BROKER_PORT=61616
WIDTH=1600

CHROME="${CHROME:-}"
if [ -z "$CHROME" ]; then
  for c in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
           "$(command -v google-chrome || true)" "$(command -v chromium || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && CHROME="$c" && break
  done
fi
[ -n "$CHROME" ] || { echo "No Chrome found. Set CHROME=/path/to/chrome" >&2; exit 1; }

JAR=$(ls "$ROOT"/target/mq-mebaysanization-*.jar 2>/dev/null | head -1 || true)
[ -n "$JAR" ] || { echo "No JAR in target/. Run: mvn clean package" >&2; exit 1; }

for p in "$PORT" "$BROKER_PORT"; do
  if lsof -ti :"$p" >/dev/null 2>&1; then echo "Port $p is busy." >&2; exit 1; fi
done

rm -rf "$WORK"; mkdir -p "$WORK" "$OUT"

BROKER_PID=""; APP_PID=""
cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  [ -n "$BROKER_PID" ] && kill "$BROKER_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT

# LOAD-BEARING, and the whole reason this is a script rather than a note.
#
# ActiveMQ derives JMS message IDs from the machine's hostname, so a browsed message renders as
# `ID:someones-MacBook-Pro.local-61159-...`. These screenshots go into a PUBLIC readme; without this
# override every regeneration would publish the name of the machine that made it. `demo` is the
# hostname the reader sees instead. The ports are pinned for the same reason - they are part of the id.
NEUTRAL_ID=(-Dactivemq.idgenerator.hostname=mq-demo
            -Dactivemq.idgenerator.port=1
            -Dactivemq.idgenerator.localport=1)

echo "==> starting throwaway broker on $BROKER_PORT"
mvn -q dependency:build-classpath -Dmdep.outputFile=target/test-cp.txt -Dmdep.includeScope=test
java "${NEUTRAL_ID[@]}" \
     -cp "target/classes:target/test-classes:$(cat target/test-cp.txt)" \
     com.baysansoft.mqmanager.support.LocalBrokerLauncher "$BROKER_PORT" > "$WORK/broker.log" 2>&1 &
BROKER_PID=$!

echo "==> starting the app on $PORT (data dir: target/screenshot-run/data)"
MQMANAGER_PORT="$PORT" MQMANAGER_DATA_DIR="$WORK/data" \
  java "${NEUTRAL_ID[@]}" -jar "$JAR" > "$WORK/app.log" 2>&1 &
APP_PID=$!

for _ in $(seq 1 90); do
  curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null || { echo "app did not start"; tail -20 "$WORK/app.log"; exit 1; }

API="http://127.0.0.1:$PORT/api"
post() { curl -sf -X POST "$1" -H 'Content-Type: application/json' -d "$2" -o /dev/null; }

echo "==> seeding demo connections"
# Only the first points at anything real. The other three exist so the list and the manual show all
# four providers; their hosts are RFC 2606 .example names and must stay that way.
post "$API/connections" '{"name":"Local ActiveMQ Classic","provider":"ACTIVE_MQ","host":"localhost","port":61616}'
post "$API/connections" '{"name":"Artemis (staging)","provider":"ARTEMIS","host":"artemis.internal.example","port":61616,"username":"appuser","password":"demo"}'
post "$API/connections" '{"name":"IBM MQ (QM1)","provider":"IBM_MQ","host":"mq.internal.example","port":1414,"username":"app","password":"demo","queueManagerName":"QM1","channel":"DEV.APP.SVRCONN"}'
post "$API/connections" '{"name":"Kafka (dev cluster)","provider":"KAFKA","bootstrapServers":"kafka-1.example:9092,kafka-2.example:9092"}'

echo "==> seeding demo messages"
send() { post "$API/connections/1/queue/messages?queueName=$1" "{\"payload\":$2,\"properties\":$3,\"messageType\":\"TEXT\"}"; }
send ORDERS.INBOUND '"{\"orderId\":\"ORD-10041\",\"customer\":\"ACME Ltd\",\"total\":149.90,\"currency\":\"EUR\",\"items\":3}"' '{"orderType":"standard","priority":"normal","source":"web"}'
send ORDERS.INBOUND '"{\"orderId\":\"ORD-10042\",\"customer\":\"Globex\",\"total\":2450.00,\"currency\":\"EUR\",\"items\":17}"' '{"orderType":"bulk","priority":"high","source":"api"}'
send ORDERS.INBOUND '"{\"orderId\":\"ORD-10043\",\"customer\":\"Initech\",\"total\":39.99,\"currency\":\"USD\",\"items\":1}"' '{"orderType":"standard","priority":"normal","source":"mobile"}'
send ORDERS.INBOUND '"{\"orderId\":\"ORD-10044\",\"customer\":\"Umbrella\",\"total\":880.50,\"currency\":\"GBP\",\"items\":6}"' '{"orderType":"standard","priority":"low","source":"web"}'
send ORDERS.INBOUND '"{\"orderId\":\"ORD-10045\",\"customer\":\"Soylent\",\"total\":12.00,\"currency\":\"EUR\",\"items\":1}"' '{"orderType":"standard","priority":"normal","source":"web"}'
send ORDERS.DLQ '"{\"orderId\":\"ORD-09987\",\"error\":\"PAYMENT_DECLINED\",\"attempts\":3}"' '{"deadLetterReason":"PAYMENT_DECLINED","JMSXDeliveryCount":"3"}'
send ORDERS.DLQ '"{\"orderId\":\"ORD-09991\",\"error\":\"SCHEMA_VALIDATION_FAILED\",\"attempts\":1}"' '{"deadLetterReason":"SCHEMA_VALIDATION_FAILED","JMSXDeliveryCount":"1"}'
for i in 1 2 3 4 5 6; do
  send PAYMENTS.EVENTS "\"{\\\"event\\\":\\\"payment.captured\\\",\\\"paymentId\\\":\\\"PAY-88$i\\\",\\\"amount\\\":$((i * 25)).00}\"" '{"eventType":"payment.captured","version":"2"}'
done
send NOTIFICATIONS.EMAIL '"{\"template\":\"order-confirmation\",\"orderId\":\"ORD-10041\"}"' '{"template":"order-confirmation","locale":"en-GB"}'

# The "remembered" chips come from the saved-destinations table, not from having browsed - the UI
# records an open as a separate call. Browsing over the API alone would leave the chip row empty, so
# seed it explicitly. Order matters: the list is pinned-first then most-recently-opened, and the shot
# is meant to show ORDERS.INBOUND leading.
post "$API/connections/1/saved-destinations" '{"name":"ORDERS.DLQ","kind":"QUEUE","pinned":false}'
post "$API/connections/1/saved-destinations" '{"name":"ORDERS.INBOUND","kind":"QUEUE","pinned":false}'
sleep 2   # advisory topics need a moment before Browse… can see the new destinations

echo "==> capturing"
# --virtual-time-budget is what makes this work at all: without it Chrome shoots the first paint, which
# for this SPA is an empty shell. It fast-forwards timers until spent, so React has mounted and React
# Query has resolved. Heights are tuned per page to end near the content instead of trailing blank
# background; re-tune them if the layout changes.
shot() {
  "$CHROME" --headless --disable-gpu --no-sandbox --hide-scrollbars \
    --force-device-scale-factor=2 --virtual-time-budget=9000 \
    --window-size="${WIDTH},${3}" --screenshot="$WORK/$1.png" \
    "http://127.0.0.1:${PORT}${2}" >/dev/null 2>&1
  # Captured at 2x for sharpness, then halved: 1600px wide is ample for GitHub and a third of the bytes.
  if command -v sips >/dev/null 2>&1; then
    sips -Z "$WIDTH" -s format png "$WORK/$1.png" --out "$OUT/$1.png" >/dev/null
  else
    cp "$WORK/$1.png" "$OUT/$1.png"
  fi
  printf '    %-18s %s\n' "$1" "$(du -h "$OUT/$1.png" | cut -f1)"
}

shot connections     "/connections"                               545
shot queue-explorer  "/connections/1/queue?queue=ORDERS.INBOUND"  930
shot connection-form "/connections/3/edit"                        830
shot logs            "/logs"                                      955
shot manual          "/manual"                                   1030

echo "==> done. Review docs/screenshots/ before committing:"
echo "    no real hostnames, no real broker names, no credentials."
