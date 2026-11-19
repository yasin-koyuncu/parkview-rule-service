#!/bin/bash
# tool-guardian.sh - PreToolUse hook on Bash (and PowerShell).
# Blocks shell commands that an agent never runs on its own in the Park & View repos:
#   - force push (--force, --mirror, -f in a flag group, +refspec); --force-with-lease is allowed
#   - push to master, main or release/*, and deleting master/develop/main on a remote
#     (pushing develop and feature branches is allowed: solo flow, commits and pushes are asked for in settings.json)
#   - commands that throw away uncommitted work (reset --hard, checkout -- ., restore ., clean -f)
#   - staging everything (git add -A / --all / -u / ., git commit -a); `git add -A -- <paths>` is fine
#   - staging or committing .env files (not .env.example), .idea/, .claude/settings.local.json, CLAUDE.local.md,
#     and in the web repo anything under .claude/ and docs/
#   - printing key or secret files (.env*, *.p12 *.pfx *.jks *.keystore *.key *.pem, secrets.json, ~/.m2/settings.xml, .pgpass)
#   - printing the database password (echo $DB_PASS, printenv DB_PASS, docker exec ... env)
# Exit 0 = allow. Exit 2 = block; Claude Code shows stderr to Claude, which then stops or asks the user.
# The checks are regexes over the command text: a command that only quotes such text (an echo, a heredoc)
# can be blocked too. Put that text in a file instead. Values of -m/--message and push options are ignored.
# Adapted from hm-pos .claude/hooks/tool-guardian.sh (which adapts extenda/hiiretail-ai-marketplace); no jq.
# Test it by piping {"tool_input":{"command":"..."},"cwd":"..."} into it: exit 2 means blocked.
# Disable for one session: CLAUDE_TOOL_GUARDIAN=0.

[ "${CLAUDE_TOOL_GUARDIAN:-1}" = "0" ] && exit 0
. "$(dirname "$0")/hook-input.sh"

payload=$(cat)
command=$(printf '%s' "$payload" | json_str command)
[ -n "$command" ] || exit 0

# Every check below needs "git", a secret-file name or a password variable: skip the greps otherwise
# (each process start costs time on Windows).
case "$command" in
  *git*|*.env*|*.p12*|*.pfx*|*.jks*|*.keystore*|*.key*|*.pem*|*secrets.json*|*PASS*|*settings.xml*|*pgpass*|*docker*) ;;
  *) exit 0 ;;
esac

block() {
  echo "Blocked by .claude/hooks/tool-guardian.sh: $1 (switch off for one session: CLAUDE_TOOL_GUARDIAN=0)" >&2
  exit 2
}

cwd=$(printf '%s' "$payload" | json_str cwd | head -1)
cwd=${cwd:-${CLAUDE_PROJECT_DIR:-.}}

# Join backslash-continued lines; drop push-option values and commit-message values (free text).
norm=${command//$'\\\n'/ }
norm=$(printf '%s\n' "$norm" | sed -E "s/(-o|--push-option)(=|[[:space:]]+)([^[:space:]\"']|\"[^\"]*\"|'[^']*')+/ /g")
norm=$(printf '%s\n' "$norm" | sed -E "s/(-[a-zA-Z]*m|--message)(=|[[:space:]]+)(\"[^\"]*\"|'[^']*'|[^[:space:]\"']+)/\1 /g")

# git, optionally with -C <path> / -c <k=v> / --long-opt before the subcommand.
git_cmd='(^|[;&|("'"'"'[:space:]])git([[:space:]]+-[Cc][[:space:]]+[^[:space:]]+|[[:space:]]+--[a-z-]+(=[^[:space:]]+)?)*[[:space:]]+'

# The web repo (folder smart-parking-guide, also reached as the junction parkview-web): .claude/ and docs/ stay local there.
is_web_repo() {
  top=$(git -C "$1" rev-parse --show-toplevel 2>/dev/null) || return 1
  case "${top##*/}" in parkview-web|smart-parking-guide) return 0 ;; esac
  [ -d "$top/apps/driver" ] && [ -d "$top/apps/operator" ] && [ -f "$top/vite.config.ts" ]
}

# A path given to git add / git commit.
check_path() {
  p=${1//\\//}
  p=${p#./}
  b=${p##*/}
  case "$b" in
    .env.example) ;;
    .env|.env.*|*.env) block "$1 is an env file with credentials; it is never staged or committed" ;;
  esac
  case "/$p/" in */.idea/*) block "$1 is IDE state (.idea/); it is never staged or committed" ;; esac
  case "$p" in .claude/settings.local.json|*/.claude/settings.local.json) block "$1 is personal Claude settings; it is never staged or committed" ;; esac
  [ "$b" = "CLAUDE.local.md" ] && block "$1 is personal Claude notes; it is never staged or committed"
  if [ "$web" = 1 ]; then
    case "$p" in
      docs|docs/*|.claude|.claude/*|../docs*|../.claude*|*/parkview-web/docs*|*/parkview-web/.claude*|*/smart-parking-guide/docs*|*/smart-parking-guide/.claude*)
        block "$1: in the web repo .claude/ and docs/ are not committed" ;;
    esac
  fi
}

# args_after <segment> <subcommand>: the text after "git ... <subcommand>".
args_after() {
  printf '%s\n' "$1" | grep -oE "${git_cmd}$2([[:space:]].*)?\$" | head -1 | sed -E "s/^${git_cmd}$2//"
}

web=0
is_web_repo "$cwd" && web=1

segments=$(printf '%s\n' "$norm" | tr ';|&' '\n\n\n')

force_push="${git_cmd}push([[:space:]]+[^;&|]*)?[[:space:]]((--force|--mirror|-[a-zA-Z]*f[a-zA-Z]*)([[:space:]]|\$|[\"')])|[\"']?\+[^[:space:]]+)"
protected='(master|main|release/[^[:space:]]+)'
key_file='\.(p12|pfx|jks|keystore|key|pem)([[:space:]"'"'"');|&>*]|$)'
secret_name='(^|[/\\:[:space:]"'"'"'=<])([A-Za-z0-9_-]*\.env(\.[A-Za-z0-9_.-]+)?|secrets\.json|\.pgpass|\.m2[/\\]settings\.xml)([[:space:]"'"'"');|&>*]|$)'
reader='(^|[("'"'"'[:space:]])(cat|head|tail|less|more|grep|egrep|rg|strings|xxd|od|hexdump|base64|openssl|keytool|type|sed|awk|bat|Get-Content|gc|Select-String|sls)([[:space:]]|$)'
dbvar='(DB_PASS(WORD)?|PGPASSWORD|POSTGRES_PASSWORD|SPRING_DATASOURCE_PASSWORD|[A-Z0-9]+_DB_PASSWORD|DATABASE_URL)'

while IFS= read -r seg; do
  [ -n "${seg//[[:space:]]/}" ] || continue

  # --- git push -------------------------------------------------------------------------------
  if printf '%s\n' "$seg" | grep -qE "${git_cmd}push([[:space:]]|\$)"; then
    printf '%s\n' "$seg" | grep -qE "$force_push" \
      && block "force push is not permitted (--force-with-lease is). Push a new commit, or ask the user to do it"
    push_args=$(printf '%s\n' "$seg" | grep -oE "${git_cmd}push[^;&|]*" | head -1 | sed -E 's/.*[[:space:]]push//' | tr -d "\"'")
    if printf '%s\n' "$push_args" | grep -qE '(^|[[:space:]])(--delete|-d)([[:space:]]|$)|(^|[[:space:]])\+?:[^[:space:]]' \
      && printf '%s\n' "$push_args" | grep -qE "(^|[[:space:]:]|refs/heads/)(master|develop|main)([[:space:]]|\$)"; then
      block "deleting master, develop or main on a remote is not permitted. Ask the user to do it"
    fi
    if printf '%s\n' "$push_args" | grep -qE "(^|[[:space:]:]|refs/heads/)${protected}([[:space:]]|\$)"; then
      block "pushing to master, main or release/* is not permitted (develop and feature branches are fine). Ask the user to do it"
    fi
    # A bare "git push" (no refspec) or a HEAD refspec pushes the current branch.
    if ! printf '%s\n' "$push_args" | grep -qE '[[:space:]][^-[:space:]][^[:space:]]*[[:space:]]+[^-[:space:]]' \
      || printf '%s\n' "$push_args" | grep -qE '(^|[[:space:]])HEAD([[:space:]]|$)'; then
      dir=$(printf '%s\n' "$seg" | sed -nE 's/.*git[[:space:]]+-C[[:space:]]+([^[:space:]]+).*/\1/p' | head -1 | tr -d "\"'")
      case "$dir" in "") dir=$cwd ;; /*|[A-Za-z]:*) ;; *) dir="$cwd/$dir" ;; esac
      branch=$(git -C "$dir" rev-parse --abbrev-ref HEAD 2>/dev/null)
      if printf '%s\n' "$branch" | grep -qE "^${protected}\$"; then
        block "the current branch is '$branch'; pushing it is not permitted. Ask the user to do it"
      fi
    fi
  fi

  # --- discarding uncommitted work --------------------------------------------------------------
  if printf '%s\n' "$seg" | grep -qE "${git_cmd}(reset[^;&|]*--hard|checkout[[:space:]]+(--[[:space:]]+)?\.([[:space:]]|\$)|restore([[:space:]]+--[a-z-]+)*[[:space:]]+\.([[:space:]]|\$)|clean([[:space:]]+[^;&|]*)?[[:space:]]-[a-zA-Z]*f)"; then
    block "this command discards uncommitted work. Ask the user to run it"
  fi

  # --- staging ------------------------------------------------------------------------------------
  if printf '%s\n' "$seg" | grep -qE "${git_cmd}(add|stage)([[:space:]]|\$)"; then
    args=$(args_after "$seg" '(add|stage)')
    all=0; named=0; dot=0
    set -f
    for t in $args; do
      t=${t//[\"\']/}
      case "$t" in
        --) ;;
        --all|--update) all=1 ;;
        --*) ;;
        -?*) case "${t#-}" in *[Au]*) all=1 ;; esac ;;
        .|./|.\\|\*) dot=1 ;;
        *) named=$((named + 1)); check_path "$t" ;;
      esac
    done
    set +f
    [ "$dot" = 1 ] && block "staging everything (git add .) is not permitted: stage files by path"
    [ "$all" = 1 ] && [ "$named" = 0 ] && block "git add -A / --all / -u without a path stages everything: name the paths (git add -A -- <paths> is fine)"
  fi
  if printf '%s\n' "$seg" | grep -qE "${git_cmd}commit([[:space:]]|\$)"; then
    printf '%s\n' "$seg" | grep -qE "${git_cmd}commit([[:space:]]+[^;&|]*)?[[:space:]](-[b-zA-Z]*a[a-zA-Z]*|--all)([[:space:]]|\$)" \
      && block "git commit -a stages everything tracked: stage files by path"
    args=$(args_after "$seg" commit)
    set -f
    for t in $args; do
      t=${t//[\"\']/}
      case "$t" in -*|"") ;; *) check_path "$t" ;; esac
    done
    set +f
  fi

  # --- printing secret files -----------------------------------------------------------------------
  # Per pipeline segment, so "git ls-files '*.pem' | head" (names only) stays allowed.
  if printf '%s\n' "$seg" | grep -qE "$key_file" \
    || printf '%s\n' "$seg" | sed 's/\.env\.example//g' | grep -qE "$secret_name"; then
    if printf '%s\n' "$seg" | grep -qE "$reader"; then
      block "this command would print a key or secret file (.env*, *.pem *.key *.p12 *.pfx *.jks *.keystore, secrets.json, ~/.m2/settings.xml)"
    fi
    if printf '%s\n' "$seg" | grep -qE "${git_cmd}(show|cat-file|diff|log[^;&|]*[[:space:]]-p)" \
      && ! printf '%s\n' "$seg" | grep -qE '[[:space:]]--(stat|shortstat|numstat|name-only|name-status)'; then
      block "this git command would print the content of a key or secret file"
    fi
  fi

  # --- printing the database password --------------------------------------------------------------
  if printf '%s\n' "$seg" | grep -qE "(^|[(\"'[:space:]])(echo|printf|cat|Write-Output|Write-Host)[[:space:]].*(\\\$\{?|\\\$env:)${dbvar}" \
    || printf '%s\n' "$seg" | grep -qE "(^|[[:space:]])printenv[[:space:]]+${dbvar}" \
    || printf '%s\n' "$seg" | grep -qE "docker[[:space:]]+(compose[[:space:]]+)?exec[^;&|]*[[:space:]](printenv|env)([[:space:]]|\$)"; then
    block "this command would print a database password"
  fi
done <<EOF
$segments
EOF

exit 0
