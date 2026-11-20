#!/usr/bin/env bash
# Three headless clients for the first-use hint: EN first and second hold; RU after clearing the record; a re-join.
set -u
cd /mnt/data/projects/incoming-wt-hint
RUN=mod/run/scenario; OUT=mod/run/hint
GIVE='item replace entity @s weapon.mainhand with incoming:strike_designator'
DROP='item replace entity @s weapon.mainhand with air'
lang() { mkdir -p $RUN; touch $RUN/options.txt; if grep -q '^lang:' $RUN/options.txt; then sed -i "s/^lang:.*/lang:$1/" $RUN/options.txt; else echo "lang:$1" >> $RUN/options.txt; fi; }
one() { # name lang commands
  lang "$2"
  INCOMING_COMMANDS="$3" tools/client_scenario.sh commands > $OUT/$1.log 2>&1
  echo "run $1 exit $?"
  mkdir -p $OUT/$1; cp -r $RUN/screenshots/. $OUT/$1/ 2>/dev/null
  cp $RUN/logs/latest.log $OUT/$1/latest.log 2>/dev/null
  grep -n 'shown_hints' $RUN/config/incoming-client.toml > $OUT/$1/shown_hints.txt 2>/dev/null
  cat $OUT/$1/shown_hints.txt
}
# fresh record for run 1
[ -f $RUN/config/incoming-client.toml ] && sed -i 's/^\(\s*shown_hints\s*=\).*/\1 []/' $RUN/config/incoming-client.toml
one en en_us "wait:40;$GIVE;shot:en_first_a;shot:en_first_b;shot:en_first_c;wait:200;$DROP;shot:en_empty;$GIVE;shot:en_second_a;shot:en_second_b"
sed -i 's/^\(\s*shown_hints\s*=\).*/\1 []/' $RUN/config/incoming-client.toml
one ru ru_ru "wait:40;$GIVE;shot:ru_first_a;shot:ru_first_b"
one rejoin ru_ru "wait:40;$GIVE;shot:rejoin_a;shot:rejoin_b"
lang en_us
