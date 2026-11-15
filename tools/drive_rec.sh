#!/system/bin/sh
# Passive drive recorder: the helper's event stream (timestamped) + logcat (capped), to files on the car.
# Start: touch /data/local/tmp/yunus_drive_rec.on; nohup sh drive_rec.sh &   Stop: rm the .on file, kill the pids.
D=/data/local/tmp
T=$(date +%m%d_%H%M)
EV=$D/drive_${T}_ev.log
LC=$D/drive_${T}_logcat.log
echo "$EV" > $D/yunus_drive_rec.files
echo "$LC" >> $D/yunus_drive_rec.files
# Capped: 4 rotating files of 20 MB (the uncapped one reached 1.4 GB in a day); -T 1 skips the old buffer.
logcat -v threadtime -T 1 -r 20480 -n 4 -f $LC &
echo $! > $D/yunus_drive_rec.pids
echo $$ >> $D/yunus_drive_rec.pids
while [ -f $D/yunus_drive_rec.on ]; do
  TOK=$(ps -A -o ARGS | sed -n 's/^app_process .*ShellHelper 19340 \([^ ]*\)$/\1/p' | head -1)
  if [ -n "$TOK" ]; then
    echo "$EPOCHREALTIME # connect" >> $EV
    ( echo "$TOK"; echo watch; exec sleep 14399 ) | nc 127.0.0.1 19340 | { while IFS= read -r l; do echo "$EPOCHREALTIME $l"; done; pkill -f 'sleep 14399'; } >> $EV
    echo "$EPOCHREALTIME # disconnect" >> $EV
  fi
  sleep 2
done
