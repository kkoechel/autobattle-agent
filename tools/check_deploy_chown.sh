#!/usr/bin/env bash
# Does a multi-file deploy build the right remote chown command?
#
# MEASURED 2026-11-11. `tools/deploy_agent.sh agent` was asked to send three
# files and died with:
#
#     chown: cannot access 'cli.py/novelty.py': No such file or directory
#
# printf REUSES its format string until its arguments run out, so the
# one-shot form spent $rdir on the first filename and then paired the
# remaining filenames with each other. The command had therefore never
# worked for more than one file -- every previous deploy of this repo had
# sent exactly one.
#
# What made it worth a check rather than just a fix: under `set -e` the abort
# landed AFTER the scp and BEFORE the per-file md5 verification, the step
# whose own comment reads "The copy reporting success is not evidence that
# the right bytes are there." So the failure mode was files on the host,
# non-zero exit, and the verification silently skipped.
#
# The construction is lifted out of the real script rather than copied here,
# so reverting the fix fails this test.
#
# Run: bash tools/check_deploy_chown.sh
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/deploy_agent.sh"
[ -r "$SCRIPT" ] || { echo "no deploy_agent.sh beside this check"; exit 1; }

SNIPPET=$(awk '/local chownargs=""/{p=1}
               p && /ssh -n .*chown \$owner \$chownargs/{exit}
               p{print}' "$SCRIPT")
if [ -z "$SNIPPET" ]; then
    echo "FAIL: could not find the chown construction in deploy_agent.sh."
    echo "      Either it was renamed, or the one-shot printf form is back."
    exit 1
fi

# The real thing, with the ssh call left off.
real() {  # real <rdir> <files...>
    local rdir="$1"; shift
    local f
    eval "$SNIPPET"
    printf '%s' "$chownargs"
}

# The broken form, kept so this test demonstrates the bug it was written for.
broken() {
    local rdir="$1"; shift
    printf "'%s/%s' " "$rdir" "$@"
}

fails=0
case_() {  # case_ <label> <got> <want>
    if [ "$2" = "$3" ]; then
        printf '  ok    %s\n' "$1"
    else
        printf '  FAIL  %s\n          got  %s\n          want %s\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

echo "MEASURED — the three files that broke it:"
case_ "three files each get the directory" \
      "$(real /opt/abagent/abagent archetype.py cli.py novelty.py)" \
      "'/opt/abagent/abagent/archetype.py' '/opt/abagent/abagent/cli.py' '/opt/abagent/abagent/novelty.py' "
case_ "the old form paired the filenames with each other" \
      "$(broken /opt/abagent/abagent archetype.py cli.py novelty.py)" \
      "'/opt/abagent/abagent/archetype.py' 'cli.py/novelty.py' "

echo "the case that always worked, and hid it:"
case_ "one file" "$(real /opt/abagent/abagent cli.py)" \
      "'/opt/abagent/abagent/cli.py' "
case_ "one file: both forms agree, which is why nobody noticed" \
      "$(real /x a.py)" "$(broken /x a.py)"

echo "edges:"
case_ "two files" "$(real /x a.py b.py)" "'/x/a.py' '/x/b.py' "
case_ "no files at all" "$(real /x)" ""
case_ "five files" "$(real /x a b c d e)" "'/x/a' '/x/b' '/x/c' '/x/d' '/x/e' "
case_ "a remote dir containing a percent sign" \
      "$(real '/opt/100%' a.py b.py)" "'/opt/100%/a.py' '/opt/100%/b.py' "

echo
if [ "$fails" != 0 ]; then
    echo "$fails failure(s)"
    exit 1
fi
echo "0 failure(s)"
