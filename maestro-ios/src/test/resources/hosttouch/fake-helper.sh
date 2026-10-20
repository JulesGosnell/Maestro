#!/bin/sh
# smithr#1313 test stand-in for ios-host-touch: fake-helper.sh <mode> <record-file> [marker]
# Speaks the helper's line protocol for a 390x844 screen, appends every command it gets to
# <record-file>, and exits on stdin EOF like the real one. <mode>:
#   ok       answers every gesture "ok 100.0 0.2"
#   refuse   answers "refused <why>"
#   fail     answers "err <why>"
#   silent   never answers
#   die      exits once it has a command
#   noready  says "fail no simulator" and exits
#   hang     prints nothing at all
mode=$1; record=$2
case $mode in
  noready) echo "fail no simulator"; exit 4 ;;
  hang) exec cat > /dev/null ;;
esac
echo "ready 390 844 3 1051.17.7 fake [phone]"
while IFS= read -r line; do
  echo "$line" >> "$record"
  case $mode in
    ok) echo "ok 100.0 0.2" ;;
    refuse) echo "refused tap outside the screen" ;;
    fail) echo "err 1 of 2 messages unacknowledged, 0 failed" ;;
    silent) : ;;
    die) exit 0 ;;
  esac
done
