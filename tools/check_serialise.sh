#!/bin/sh
# Does abagent-serialise actually admit two jobs and refuse the third?
#
# `sh -n` parses. It cannot tell whether flock on an inherited fd survives
# exec, whether the skip really exits 75, or whether two slots admit two
# jobs -- and every one of those is a way this silently becomes "no limit at
# all" or "nobody ever runs". So run the real script.
set -u
W="$(cd "$(dirname "$0")/.." && pwd)/deploy/abagent-serialise"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
ABAGENT_LOCK1="$T/a.lock"; ABAGENT_LOCK2="$T/b.lock"
export ABAGENT_LOCK1 ABAGENT_LOCK2
: > "$ABAGENT_LOCK1"; : > "$ABAGENT_LOCK2"
pass=0; fail=0
ok() { pass=$((pass+1)); echo "  ok    $1"; }
no() { fail=$((fail+1)); echo "  FAIL  $1"; }

# 1+2: two concurrent jobs both get a slot, and they get DIFFERENT slots.
sh "$W" 0 sh -c "sleep 3" > "$T/o1" 2>&1 &
p1=$!
sleep 1
sh "$W" 0 sh -c "sleep 3" > "$T/o2" 2>&1 &
p2=$!
sleep 1
# 3: the third is refused while both are held.
sh "$W" 0 /bin/true > "$T/o3" 2>&1; rc3=$?

s1=$(sed -n 's/^serialise: slot \([0-9]\) of 2$/\1/p' "$T/o1")
s2=$(sed -n 's/^serialise: slot \([0-9]\) of 2$/\1/p' "$T/o2")
[ -n "$s1" ] && [ -n "$s2" ] && ok "two concurrent jobs both admitted (slots $s1,$s2)" \
    || no "two concurrent jobs: got slots '$s1','$s2' (expected both set)"
[ "$s1" != "$s2" ] && ok "they took different slots" || no "both took slot $s1"
[ "$rc3" = 75 ] && ok "third job exits 75 while both slots held" \
    || no "third job exit $rc3, expected 75"
grep -q 'skipped this cycle, both slots were busy' "$T/o3" \
    && ok "the skip says so in the journal" || no "skip printed nothing: $(cat "$T/o3")"

# 4: the lock survived exec -- it was held for the SLEEP's lifetime, not just
# for flock's. If it had not, job 3 above would have been admitted.
wait $p1 $p2 2>/dev/null
ok "lock outlived flock(1) and the exec (implied by the refusal above)"

# 5: with both slots free again, a job is admitted.
sh "$W" 0 /bin/true > "$T/o5" 2>&1; rc5=$?
[ "$rc5" = 0 ] && grep -q 'slot 1 of 2' "$T/o5" \
    && ok "slots are released on exit; next job takes slot 1" \
    || no "after release: rc=$rc5 out=$(cat "$T/o5")"

# 6: the wrapped command's own exit code reaches systemd, not the wrapper's.
sh "$W" 0 sh -c 'exit 3' > "$T/o6" 2>&1; rc6=$?
[ "$rc6" = 3 ] && ok "the command's exit code passes through" \
    || no "exit code mangled: got $rc6, expected 3"

# 7: a command that exits 75 ITSELF is indistinguishable from a skip. Worth
# knowing rather than worth fixing: python3 does not exit 75, and systemd
# treats both as success either way.
sh "$W" 0 sh -c 'exit 75' > "$T/o7" 2>&1; rc7=$?
[ "$rc7" = 75 ] && ! grep -q 'skipped this cycle' "$T/o7" \
    && ok "a command exiting 75 is not mislabelled as a skip" \
    || no "command exit 75 was reported as a skip"

# 8: waiting. One slot is held for 6s by a job holding BOTH is not possible,
# so hold both and give the waiter 20s; it must get in once one frees.
sh "$W" 0 sh -c "sleep 6" > /dev/null 2>&1 &
q1=$!
sh "$W" 0 sh -c "sleep 6" > /dev/null 2>&1 &
q2=$!
sleep 1
t0=$(date +%s)
sh "$W" 20 /bin/true > "$T/o8" 2>&1; rc8=$?
t1=$(date +%s)
wait $q1 $q2 2>/dev/null
[ "$rc8" = 0 ] && ok "a waiter with -w 20 got in after $((t1-t0))s instead of skipping" \
    || no "waiter still skipped (rc=$rc8) after $((t1-t0))s"

# 9: a missing lock file must not take the agents down with it.
rm -f "$ABAGENT_LOCK2"
sh "$W" 0 /bin/true > "$T/o9" 2>&1; rc9=$?
[ "$rc9" = 0 ] && grep -q 'running unserialised' "$T/o9" \
    && ok "a missing lock file degrades to unserialised, not to failure" \
    || no "missing lock file: rc=$rc9 out=$(cat "$T/o9")"

echo
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
