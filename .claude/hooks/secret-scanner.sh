#!/bin/bash
# secret-scanner.sh - PreToolUse hook on Write|Edit|MultiEdit.
# Blocks a write whose NEW content carries a credential, so Claude removes it before it can be committed:
# private-key PEM blocks, GitHub / Atlassian / Anthropic / AWS / Jenkins tokens, long values assigned to
# api_key / secret_key / access_token / auth_token / client_secret, and a JWT assigned to a secret-looking name
# (service_role, secret, private, password, token, api_key). JWTs are not flagged when the name starts with E2E_
# (the mock keys of the e2e stack) or the file is a test or fixture (tests/, fixtures/, build/, src/test/, *.test.*, *.spec.*).
# Only the new text is scanned (content, new_string), so removing a secret is never blocked.
# Deployment values belong in ${ENV_VAR} placeholders or the CI secrets, never in tracked files.
# Exit 0 = allow. Exit 2 = block; Claude Code shows stderr to Claude.
# Adapted from hm-pos .claude/hooks/secret-scanner.sh (which adapts extenda/engineering-platform-common); no jq.
# Test it by piping {"tool_input":{"file_path":"x","content":"..."}} into it: exit 2 means blocked.
# Disable for one session: CLAUDE_SECRET_SCANNER=0.

[ "${CLAUDE_SECRET_SCANNER:-1}" = "0" ] && exit 0
. "$(dirname "$0")/hook-input.sh"

payload=$(cat)
content=$( { printf '%s' "$payload" | json_str content; printf '%s' "$payload" | json_str new_string; } )
[ -n "$content" ] || exit 0
file=$(printf '%s' "$payload" | json_str file_path | head -1)
file=${file//\\//}

found() {
  echo "Blocked by .claude/hooks/secret-scanner.sh: $1 in the content being written. Remove it and use a placeholder (\${ENV_VAR}) or a credential store. If this is a deliberate test fixture, stop and ask the user (switch off for one session: CLAUDE_SECRET_SCANNER=0)." >&2
  exit 2
}

has() { printf '%s\n' "$content" | grep -qE -- "$1"; }

has '-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----'              && found "a private key (PEM block)"
has 'gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,}' && found "a GitHub token"
has 'ATATT[A-Za-z0-9_=-]{30,}'                                && found "an Atlassian API token"
has 'sk-ant-[A-Za-z0-9_-]{20,}'                               && found "an Anthropic API key"
has 'AKIA[0-9A-Z]{16}'                                        && found "an AWS access key"
has '(^|[^0-9a-f])11[0-9a-f]{32}([^0-9a-f]|$)'                && found "a Jenkins API token"
printf '%s\n' "$content" | grep -qiE '(api[_-]?key|api[_-]?secret|secret[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9_+/-]{20,}' \
  && found "a long value assigned to a key/secret/token field"

case "$file" in
  */tests/*|*/test/*|*/fixtures/*|*/build/*|*/src/test/*|*.test.*|*.spec.*) ;;
  *)
    printf '%s\n' "$content" | grep -vE '(^|[^A-Za-z0-9_])E2E_[A-Za-z0-9_]*[[:space:]]*[:=]' \
      | grep -qiE '(secret|service[_-]?role|private|password|token|api[_-]?key)[A-Za-z0-9_-]*["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"']?eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}' \
      && found "a JWT assigned to a secret field"
    ;;
esac

exit 0
