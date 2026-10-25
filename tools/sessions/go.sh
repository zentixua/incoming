#!/usr/bin/env bash
# Фоновая сессия claude2 на ноутбуке — запуск, продолжение, ожидание, сводка:
#
#   tools/sessions/go.sh <S>                          — новая сессия по <S>.txt
#   tools/sessions/go.sh <S> <session_id> <сообщение> — продолжить сессию
#   tools/sessions/go.sh --wait <S>                   — ждать конца (запускать в фоне: конец разбудит)
#   tools/sessions/go.sh --status                     — что идёт и последние строки sessions.log
#
# Файлы — в $SESSIONS_DIR (по умолчанию mod/run/incoming-sessions основной копии): <S>.txt задание, context.md бриф;
# итог каждого хода — <S>.json (первый), <S>.r1.json, <S>.r2.json …, строка в
# sessions.log. Сессия идёт своей службой systemd incoming-claude-<S>: обрыв того, кто её запустил, её не трогает
# (22.10 01:16 S5 и S8 погибли с сессией-родителем — дочерние процессы Claude гаснут с его группой, setsid не спасает).
# Сам скрипт службы — копия (supervise.sh → .supervise-<S>.sh): правка файла на ходу её не ломает (21.10 правка run.sh
# посреди прогона затёрла итоги P и S3 — bash дочитывает изменённый файл со старого места).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)
D=${SESSIONS_DIR:-$root/mod/run/incoming-sessions}

case "${1:-}" in
  --wait)
    [ -n "${2:-}" ] || { echo "go.sh --wait <S>" >&2; exit 2; }
    while systemctl --user is-active --quiet "incoming-claude-$2"; do sleep 60; done
    grep -P "^\S+ \S+ \Q$2\E[ :]" "$D/sessions.log" 2>/dev/null | tail -1; exit 0 ;;
  --status)
    systemctl --user list-units 'incoming-claude-*' --state=active --no-legend --plain | awk '{print "идёт: " $1}'
    tail -n 15 "$D/sessions.log" 2>/dev/null; exit 0 ;;
  ''|-*) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
S=$1; shift
[ $# -eq 0 ] || [ $# -eq 2 ] || { echo "продолжение — двумя аргументами: <session_id> <сообщение>" >&2; exit 2; }
[ $# -eq 0 ] && [ ! -s "$D/$S.txt" ] && { echo "нет задания $D/$S.txt" >&2; exit 2; }
install -m 755 "$here/supervise.sh" "$D/.supervise-$S.sh"
git -C "$root" fetch origin -q || true
systemd-run --user --unit="incoming-claude-$S" --collect -p WorkingDirectory="$root" \
  --setenv=PATH="$PATH" --setenv=HOME="$HOME" --setenv=SESSIONS_DIR="$D" --setenv=REPO="$root" \
  ${SESSION_TREES:+--setenv=SESSION_TREES="$SESSION_TREES"} \
  -p StandardOutput="append:$D/$S.supervise.log" -p StandardError="append:$D/$S.supervise.log" \
  "$D/.supervise-$S.sh" "$S" "$@"
echo "запущена incoming-claude-$S; ждать: $0 --wait $S"
