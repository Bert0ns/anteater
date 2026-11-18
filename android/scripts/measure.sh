#!/usr/bin/env bash
# Measures the benchmark build on a phone over adb, one line per number (android/README.md): cold
# start, home ready, category open and scroll jank, and the same for the Compress page, the trash
# and the storage map. It starts the app, opens those screens one after the other, scrolls three
# of them and goes back to the home.
#
# Usage: android/scripts/measure.sh [-h] [CATEGORY NAME...]
#        android/scripts/measure.sh --by-hand SECONDS
#        android/scripts/measure.sh --videos [VIDEO SECTION NAME...]
#
# Every tap it makes, and nothing else:
#   1. the title of one category on the home (CATEGORY NAME, "Similar copies" by default);
#   2. the "Compress" tab of the navigation bar, then the "Clean" tab to come back to the home;
#   3. the trash icon in the top bar of the home, which opens the trash (not a Trash button);
#   4. the "Settings" tab, then the "Storage map" row in it, which opens the map, a screen that
#      only reads (not a Settings action), then the "Clean" tab again.
# It also scrolls the category, Compress, trash and Settings screens with swipes and presses Back.
# It never taps a tile, a checkbox, Continue, Trash, Keep, Skip, Undo, Exclude, a Settings action
# or Scan now. The full size view and the Compress summary need a tile, so it leaves them out:
# android/README.md says how to time them by hand.
#
# --by-hand SECONDS makes no tap and no swipe at all. It starts the app cold and records a trace for
# that long, while you use it, and prints the times of the full size view, its tile decodes and the
# summary's previews from the trace.
#
# --videos measures one section of the Videos tab instead ("Largest videos" by default, or a video
# category), with its tiles moving (docs/design.md §8). Every tap it makes, and nothing else:
#   1. the "Videos" tab of Clean, then the title of the section;
#   2. a long press on the first tile, which opens the full size view and selects or marks nothing;
#   3. in the full size view, the centre of the video, which pauses it and plays it again, and two
#      frames of the strip, which move the video there.
# It also swipes to scroll the grid and to page to the next videos, swipes up once to bring back the
# bar, which hides while a video plays (#473), and with it the strip, and presses Back to close the
# view and to leave the section. It never taps a checkbox, the heart, "Exclude folder", Trash, Keep
# or Continue. Its trace is larger (the scheduler, the frames and the codecs), and it keeps it in a
# folder under /tmp, with the app's memory and the codecs in use at each step.
#
# ADB overrides the adb command, e.g. a Windows adb.exe under WSL. The files it pushes and pulls
# go through a temporary folder under the current directory, so such an adb can reach them.
set -euo pipefail

# Before anything touches the phone, so asking for help needs no adb.
case ${1:-} in
-h | --help)
    sed -n '2,/^set -/p' "$0" | sed -e '/^set -/d' -e 's/^# \{0,1\}//'
    exit 0
    ;;
esac
by_hand=
if [[ ${1:-} == --by-hand ]]; then
    by_hand=${2:-}
    [[ $by_hand =~ ^[0-9]+$ ]] || { echo "measure: --by-hand needs a number of seconds" >&2; exit 1; }
    shift 2
fi
videos=false
if [[ ${1:-} == --videos ]]; then
    videos=true
    shift
fi
[[ -z $by_hand ]] || ! $videos || { echo "measure: --by-hand and --videos do not go together" >&2; exit 1; }

read -ra adb <<<"${ADB:-adb}"
here=$(cd "$(dirname "$0")" && pwd)
res=$here/../app/src/main/res
package=io.github.bert0ns.anteater
remote=/data/local/tmp/anteater-measure
# --videos: perfetto may write its trace to a file in its own folder, and only there.
video_trace=/data/misc/perfetto-traces/anteater-video.pftrace
# The home's name of the category to open, in English and Italian.
categories=("$@")
if $videos; then
    [[ ${#categories[@]} -gt 0 ]] || categories=("Largest videos" "Video più grandi")
else
    [[ ${#categories[@]} -gt 0 ]] || categories=("Similar copies" "Copie simili")
fi
# The trace covers the whole run: the cold start, then the category, Compress, the trash and the map.
trace_ms=150000
[[ -z $by_hand ]] || trace_ms=$((by_hand * 1000))
! $videos || trace_ms=200000
# How long a screen gets to load its first tiles before the script reads it or scrolls it.
settle=6

local_dir=$(mktemp -d ./anteater-measure.XXXXXX)
local_dir=${local_dir#./}
cleanup() {
    if [[ $1 != 0 ]]; then
        [[ -z ${tracing:-} ]] || kill "$tracing" 2>/dev/null || true
        # --videos: perfetto writes to a file on the phone, so killing adb leaves it tracing there.
        [[ -z ${tracing:-} ]] || ! $videos || "${adb[@]}" shell pkill -x perfetto >/dev/null 2>&1 || true
        [[ -z ${started:-} ]] || "${adb[@]}" shell am force-stop "$package" >/dev/null 2>&1 || true
    fi
    "${adb[@]}" shell rm -rf "$remote" >/dev/null 2>&1 || true
    ! $videos || "${adb[@]}" shell rm -f "$video_trace" >/dev/null 2>&1 || true
    rm -rf "$local_dir"
}
trap 'cleanup $?' EXIT

die() {
    echo "measure: $*" >&2
    exit 1
}

# A Windows adb ends lines with \r.
shell() { "${adb[@]}" shell "$@" | tr -d '\r'; }

# The strings with these names, in English and Italian.
strings() {
    local key
    for key; do sed -n "s/.*name=\"$key\">\([^<]*\)<.*/\1/p" "$res/values/strings.xml" "$res/values-it/strings.xml"; done
}

# Only a category title may be tapped, never a button such as Trash or Skip.
mapfile -t titles < <(strings category_exact_duplicate category_near_duplicate category_burst category_blurry \
    category_bad_exposure category_screenshot category_document category_forwarded category_sticker category_short_clips)
# The Videos tab lists "Largest videos" and the categories with a video detector.
! $videos || mapfile -t titles < <(strings videos_largest category_exact_duplicate category_near_duplicate category_blurry \
    category_bad_exposure category_forwarded_videos category_takes category_short_clips)
for name in "${categories[@]}"; do
    known=false
    for title in "${titles[@]}"; do [[ $name == "$title" ]] && known=true; done
    $known || die "not a category title: $name"
done
mapfile -t back < <(strings back)
# The labels of the other things it taps, in English and Italian.
mapfile -t nav_clean < <(strings nav_clean)
mapfile -t nav_compress < <(strings nav_compress)
mapfile -t nav_settings < <(strings nav_settings)
mapfile -t trash_icon < <(strings trash)
# The storage map row: its title, up to the apostrophe of the Italian one that the strings file escapes. A dump may
# join the row's texts into one node, so the row is found by the start of its text.
mapfile -t map_row < <(strings storage_map | sed 's/\\.*//')
mapfile -t compress_none < <(strings compress_none)
mapfile -t trash_empty < <(strings trash_empty)
mapfile -t nav_videos < <(strings tab_videos)
# A frame of the strip under a video: its label up to the time, "Go to ".
mapfile -t go_to < <(strings video_go_to | sed 's/%1\$s.*//')
mapfile -t share < <(strings share)
app_name=$(strings app_name | head -n1)

[[ $(shell dumpsys window) != *isKeyguardShowing=true* ]] || die "unlock the phone first"
flags=$(shell dumpsys package "$package" | grep -m1 'pkgFlags=' || true)
[[ -n $flags ]] || die "$package is not installed"
[[ $flags != *DEBUGGABLE* ]] || die "install the benchmark build first (android/README.md)"
# The scan is the app's only WorkManager work, and the force-stop below would kill it.
services=$(shell dumpsys activity services "$package")
[[ $services != *SystemJobService* && $services != *SystemForegroundService* ]] ||
    die "a scan is running: wait for it to finish, since a cold start would stop it"

# The display that is on and its size. A folded Fold moves display 0 to its cover screen, where
# uiautomator dump also looks (it ignores --display).
viewport=$(shell dumpsys display | grep -o 'DisplayViewport{[^}]*isActive=true[^}]*' | head -n1 || true)
[[ $viewport =~ displayId=([0-9]+) ]] || die "turn the screen on"
display=${BASH_REMATCH[1]}
[[ $viewport =~ logicalFrame=Rect\(0,\ 0\ -\ ([0-9]+),\ ([0-9]+)\) ]] || die "unknown display size: $viewport"
width=${BASH_REMATCH[1]} height=${BASH_REMATCH[2]}

# perfetto reads its config and writes its trace through pipes: it may not open files under /data/local/tmp.
cat >"$local_dir/trace.sh" <<EOF
cat <<CFG | perfetto --txt -c - -o - 2>/dev/null | cat >$remote/trace.pftrace
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  atrace_apps: "$package"
  atrace_categories: "am"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
} } }
data_sources { config { name: "linux.process_stats" } }
duration_ms: $trace_ms
CFG
EOF
# --videos: the scheduler, the frames and the codecs too, for over three minutes, too large to come back through a pipe.
if $videos; then
    cat >"$local_dir/trace.sh" <<EOF
cat <<CFG | perfetto --txt -c - -o $video_trace 2>/dev/null
buffers { size_kb: 131072 fill_policy: DISCARD }
buffers { size_kb: 8192 fill_policy: DISCARD }
write_into_file: true
file_write_period_ms: 1000
data_sources { config { name: "linux.ftrace" target_buffer: 0 ftrace_config {
  atrace_apps: "$package"
  atrace_categories: "am"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "video"
  atrace_categories: "dalvik"
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "power/cpu_frequency"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  buffer_size_kb: 16384
  drain_period_ms: 250
} } }
data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
duration_ms: $trace_ms
CFG
EOF
    shell rm -f "$video_trace" >/dev/null 2>&1 || true
fi
shell mkdir -p "$remote"
"${adb[@]}" push -q "$local_dir/trace.sh" "$remote/trace.sh" >/dev/null

# Dumps the screen into ui.xml, and fails rather than leave an older dump there.
dump() {
    rm -f "$local_dir/ui.xml"
    shell rm -f "$remote/ui.xml"
    shell uiautomator dump "$remote/ui.xml" >/dev/null &&
        "${adb[@]}" pull -q "$remote/ui.xml" "$local_dir/ui.xml" >/dev/null &&
        [[ -s $local_dir/ui.xml ]]
}

# The first node of the last dump whose attribute $1 is exactly $2, or starts with $2 when $3 is "prefix".
node() {
    local end='"'
    [[ ${3:-} != prefix ]] || end=
    grep -o '<node [^>]*' "$local_dir/ui.xml" | grep -F -- "$1=\"$2$end" | head -n1 || true
}

# Taps the centre of the first node whose attribute $1 is one of the arguments after $2, which is "exact" or "prefix"
# (the argument may stop short of the end of the attribute), and sets tapped to that argument.
tap_node() {
    local attr=$1 mode=$2 value
    shift 2
    dump || return 1
    for value in "$@"; do
        if [[ $(node "$attr" "$value" "$mode") =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
            shell input -d "$display" tap $(((BASH_REMATCH[1] + BASH_REMATCH[3]) / 2)) $(((BASH_REMATCH[2] + BASH_REMATCH[4]) / 2))
            tapped=$value
            return 0
        fi
    done
    return 1
}

# Taps the centre of the first node whose text is one of the arguments, and sets tapped to that text.
tap_text() { tap_node text exact "$@"; }

# Whether the last dump shows one of the arguments as a text.
shows() {
    local text
    for text; do [[ -z $(node text "$text") ]] || return 0; done
    return 1
}

# Whether the home is on screen: only its top bar is titled with the app's name. Taps are made from there.
on_home() { dump && shows "$app_name"; }

# The category screen has the category's name as its title and a back button, which the home lacks.
category_open() {
    dump || return 1
    [[ -n $(node text "$tapped") ]] || return 1
    local label
    for label in "${back[@]}"; do [[ -n $(node content-desc "$label") ]] && return 0; done
    return 1
}

# Prints the frames that gfxinfo counted since its last reset, under the label $1.
frames() {
    local stats
    stats=$(shell dumpsys gfxinfo "$package" framestats)
    sed -n -e "s/^Total frames rendered: /$1 frames: /p" -e "s/^Janky frames: [0-9]* (\(.*\))/$1 janky frames: \1/p" \
        -e "s/^90th percentile: /$1 p90: /p" -e "s/^99th percentile: /$1 p99: /p" <<<"$stats" | head -n4
}

# Swipes long and fast enough to be flings, never taps on a tile: five down the screen and five back up. Prints the
# frames that gfxinfo counted meanwhile, under the label $1.
scroll() {
    shell dumpsys gfxinfo "$package" reset >/dev/null
    for _ in 1 2 3 4 5; do
        shell input -d "$display" swipe $((width / 2)) $((height * 3 / 4)) $((width / 2)) $((height / 4)) 150
        sleep 1
    done
    for _ in 1 2 3 4 5; do
        shell input -d "$display" swipe $((width / 2)) $((height / 4)) $((width / 2)) $((height * 3 / 4)) 150
        sleep 1
    done
    frames "$1"
}

# Waits for perfetto to end its trace, and pulls it. Not in a subshell, since only this shell can wait for perfetto.
pull_trace() {
    wait "$tracing"
    tracing=
    local from=$remote/trace.pftrace
    ! $videos || from=$video_trace
    "${adb[@]}" pull -q "$from" "$local_dir/trace.pftrace" >/dev/null
    [[ -s $local_dir/trace.pftrace ]] || die "perfetto wrote no trace"
}

# Waits for the home, which a tab or Back has just brought back, and fails if it does not come.
wait_home() {
    for _ in 1 2 3 4 5; do
        on_home && return 0
        sleep 1
    done
    die "the home did not come back"
}

# Taps one of the navigation bar's tabs ($1 is its labels' array name), and fails if the bar has none.
tap_tab() {
    local -n labels=$1
    tap_text "${labels[@]}" || die "no tab named ${labels[*]}"
}

# --videos: saves the app's memory (dumpsys meminfo) and the codecs in use (dumpsys media.resource_manager) at the step
# that $1 names into the kept folder, and prints the memory summary, PSS in KB.
snapshot() {
    local mem
    mem=$(shell dumpsys meminfo "$package")
    printf '%s\n' "$mem" >"$keep/meminfo-$1.txt"
    shell dumpsys media.resource_manager >"$keep/codecs-$1.txt" 2>/dev/null || true
    sed -n '/App Summary/,/TOTAL PSS/p' <<<"$mem" |
        sed -n "s/^ *\(Java Heap\|Native Heap\|Graphics\|Code\|Private Other\|TOTAL PSS\): *\([0-9]*\).*/$1 \1: \2 KB/p"
}

# --videos: whether the full size view is open. Only it has a Share button, which stays when the bar hides.
video_open() {
    dump || return 1
    local label
    for label in "${share[@]}"; do [[ -z $(node content-desc "$label") ]] || return 0; done
    return 1
}

# --videos: taps frame $1 (from 1) of the strip, whose labels start with "Go to".
tap_strip() {
    dump || return 1
    local label frame
    for label in "${go_to[@]}"; do
        frame=$(grep -o '<node [^>]*' "$local_dir/ui.xml" | grep -F -- "content-desc=\"$label" | sed -n "${1}p" || true)
        if [[ $frame =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
            shell input -d "$display" tap $(((BASH_REMATCH[1] + BASH_REMATCH[3]) / 2)) $(((BASH_REMATCH[2] + BASH_REMATCH[4]) / 2))
            return 0
        fi
    done
    return 1
}

# --videos: the run after the cold start, from the Videos tab to the section, its grid, the full size view and back.
video_run() {
    echo "display: ${width}x${height}"
    local since label opened=false tile x y
    since=$(shell "date '+%m-%d %H:%M:%S.000'")
    wait_home
    tap_text "${nav_videos[@]}" || die "no Videos tab"
    sleep 2
    tap_text "${categories[@]}" || die "no section named ${categories[*]} on the Videos tab"
    sleep 8
    dump || die "could not read the section"
    for label in "${back[@]}"; do [[ -z $(node content-desc "$label") ]] || opened=true; done
    $opened || die "$tapped did not open: it may have nothing in it"
    snapshot grid
    # The tiles move while nothing else does: how many frames that draws, and how smoothly.
    shell dumpsys gfxinfo "$package" reset >/dev/null
    sleep 10
    frames "grid idle"
    scroll "grid scroll"
    sleep 6
    snapshot grid-after-scroll

    # A long press on the first tile opens it full size. A tap would select or mark it, so a view that does not open stops the run.
    dump || die "could not read the grid"
    tile=$(grep -o '<node [^>]*' "$local_dir/ui.xml" | grep -F 'long-clickable="true"' | head -n1 || true)
    [[ $tile =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]] || die "no tile to open"
    x=$(((BASH_REMATCH[1] + BASH_REMATCH[3]) / 2)) y=$(((BASH_REMATCH[2] + BASH_REMATCH[4]) / 2))
    shell dumpsys gfxinfo "$package" reset >/dev/null
    shell input -d "$display" swipe "$x" "$y" "$x" "$y" 1000
    sleep 6
    video_open || die "the full size view did not open: check that the first tile is not selected or marked"
    snapshot full-size-playing
    # The next three videos, a swipe each, then two frames of the strip, a pause and a play.
    for _ in 1 2 3; do
        shell input -d "$display" swipe $((width * 17 / 20)) $((height / 2)) $((width * 3 / 20)) $((height / 2)) 200
        sleep 6
    done
    # The bar, with the strip, hides while a video plays. A swipe up brings it back and keeps it until the pause.
    shell input -d "$display" swipe $((width / 2)) $((height * 3 / 5)) $((width / 2)) $((height * 2 / 5)) 200
    sleep 1
    tap_strip 3 || die "no strip of frames"
    sleep 4
    tap_strip 5 || die "no strip of frames"
    sleep 4
    shell input -d "$display" tap $((width / 2)) $((height / 2))
    sleep 2
    shell input -d "$display" tap $((width / 2)) $((height / 2))
    sleep 4
    snapshot full-size-after-paging
    shell input -d "$display" keyevent KEYCODE_BACK
    sleep 8
    frames "full size and back"
    ! video_open || die "Back did not close the full size view"
    snapshot after-full-size
    shell input -d "$display" keyevent KEYCODE_BACK
    sleep 5
    snapshot left-section

    (( SECONDS - traced_from < trace_ms / 1000 - 5 )) || die "the trace ended before the run did: raise trace_ms"
    pull_trace
    cp "$local_dir/trace.pftrace" "$keep/trace.pftrace"
    uv run --quiet "$here/trace_times.py" --videos "$local_dir/trace.pftrace" "$package"
    # The codec and memory troubles of #438, by kind. Their lines stay in the kept folder, since one may hold a path.
    shell "logcat -d -T '$since'" >"$keep/logcat.txt" || true
    for label in 'reclaim' 'NO_MEMORY' 'CodecException' 'lowmemorykiller|lmkd' 'wrong thread' 'abandoned' 'MetadataRetriever|FrameDecoder' 'ExoPlaybackException'; do
        echo "logcat $label: $(grep -c -E -- "$label" "$keep/logcat.txt" || true)"
    done
    echo "kept: $keep"
}

! $videos || keep=$(mktemp -d /tmp/anteater-video.XXXXXX)
shell am force-stop "$package"
started=true
"${adb[@]}" shell sh "$remote/trace.sh" &
tracing=$!
traced_from=$SECONDS
# An app reads whether to trace when it starts: start it only once perfetto has turned tracing on.
for _ in $(seq 20); do
    [[ $(shell getprop debug.atrace.tags.enableflags) =~ ^(0x)?0*$ ]] || break
    sleep 0.5
done
[[ ! $(shell getprop debug.atrace.tags.enableflags) =~ ^(0x)?0*$ ]] || die "perfetto did not start tracing"
cold=$(shell am start -W -n "$package/.MainActivity" | sed -n 's/^TotalTime: //p')
echo "cold start: ${cold:-unknown} ms"

if $videos; then
    video_run
    exit 0
fi

if [[ -n $by_hand ]]; then
    echo "by hand: the trace ends in $by_hand s, and the script touches nothing"
    pull_trace
    uv run --quiet "$here/trace_times.py" --by-hand "$local_dir/trace.pftrace" "$package"
    exit 0
fi

tapped=
for _ in 1 2 3 4 5 6 7 8 9 10; do
    tap_text "${categories[@]}" && break
    sleep 1
done
[[ -n $tapped ]] || die "no category named ${categories[*]} on the home"
sleep 2
category_open || die "$tapped did not open: it may have nothing to review"
scrolls=$(scroll scroll)
shell input -d "$display" keyevent KEYCODE_BACK

# The Compress page, opened right after the category: its first tiles load from a cold image cache. A page with nothing
# to compress has no tiles, so it is not scrolled and the trace has no number for it.
wait_home
tap_tab nav_compress
sleep "$settle"
dump || die "could not read the Compress page"
if shows "${compress_none[@]}"; then
    echo "compress: nothing to show, so nothing to time"
else
    scrolls+=$'\n'$(scroll "compress scroll")
fi
tap_tab nav_clean

# The trash, from the icon in the top bar of the home. Back leaves it, for the home.
wait_home
tap_node content-desc exact "${trash_icon[@]}" || die "no trash icon on the home"
sleep "$settle"
dump || die "could not read the trash"
if shows "${trash_empty[@]}"; then
    echo "trash: empty, so nothing to time"
else
    scrolls+=$'\n'$(scroll "trash scroll")
fi
shell input -d "$display" keyevent KEYCODE_BACK

# The storage map is a row of the Settings screen, which may be below the fold: swipe up to find it. The row is
# the only thing tapped there, and the map only reads.
wait_home
tap_tab nav_settings
sleep 2
tapped=
for _ in 1 2 3 4 5 6 7 8; do
    tap_node text prefix "${map_row[@]}" && break
    shell input -d "$display" swipe $((width / 2)) $((height * 3 / 4)) $((width / 2)) $((height / 4)) 150
    sleep 1
done
[[ -n $tapped ]] || die "no storage map row in Settings"
sleep "$settle"
dump || die "could not read the storage map"
map_open=false
for label in "${back[@]}"; do [[ -z $(node content-desc "$label") ]] || map_open=true; done
$map_open || die "the storage map did not open"
shell input -d "$display" keyevent KEYCODE_BACK
sleep 1
dump || die "could not read Settings"
shows "${nav_settings[@]}" || die "Back did not return to Settings"
tap_tab nav_clean

(( SECONDS - traced_from < trace_ms / 1000 - 5 )) || die "the trace ended before the last screen did: raise trace_ms"
pull_trace
times=$(uv run --quiet "$here/trace_times.py" "$local_dir/trace.pftrace" "$package")
echo "$times"
# A screen that was empty has no number, but the home and the category always do.
[[ $times != *"home ready: unknown"* && $times != *"category open: unknown"* ]] || die "the trace lacks the app's sections: run the script again"
echo "$scrolls"

# A scan logs "images N" first, then its phase times: keep the lines from the last "images" on.
scan=$(shell logcat -d -s scan-time | sed -n 's/.* scan-time: //p' | awk '/^images /{last=""} {last=last $0 "\n"} END{printf "%s", last}')
if [[ -n $scan ]]; then
    sed -n 's/^images /indexed images: /p' <<<"$scan"
    grep -v '^images ' <<<"$scan" | sed 's/^/last scan /'
else
    echo "indexed images: unknown (no scan in the log)"
fi
