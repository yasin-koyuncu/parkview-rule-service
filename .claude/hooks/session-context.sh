#!/bin/bash
# session-context.sh - SessionStart hook. Prints the orientation a session otherwise spends its first tool calls on:
# repository, branch and what it means, the working-tree state (first 10 paths) and, for the Java repositories, whether the
# shared library parkview-platform is installed in the local Maven repository (the services build against it) and how to
# compile. Offline and fast (< 1 s).
# Adapted from hm-pos .claude/hooks/session-context.sh.

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

top=$(git rev-parse --show-toplevel 2>/dev/null)
repo=${top##*/}
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
case "$branch" in
  master) meaning="master = release (the last verified state; only merged from develop after the e2e suite is green)" ;;
  develop) meaning="develop = development" ;;
  *) meaning="feature branch" ;;
esac
echo "$repo: branch $branch ($meaning)"

if [ -f pom.xml ]; then
  lib="$HOME/.m2/repository/com/parkview/platform/parkview-parent/1.0.0-SNAPSHOT"
  if [ -d "$lib" ]; then
    echo "parkview-platform 1.0.0-SNAPSHOT: installed in ~/.m2 (host builds resolve it; Docker builds need the maven_settings secret)"
  else
    echo "parkview-platform 1.0.0-SNAPSHOT: NOT in ~/.m2 - run mvn install in the parkview-platform repo first, or the build needs a GitHub Packages token"
  fi
  echo "Compile with the mvn helper, never plain mvn (the default JDK is 8; the services need 21, the gateway 24)."
fi

changes=$(git status --porcelain 2>/dev/null | grep -c .)
if [ "$changes" -gt 0 ]; then
  echo "Working tree: $changes changed path(s)"
  git status --porcelain 2>/dev/null | head -10 | sed 's/^/  /'
  [ "$changes" -gt 10 ] && echo "  ..."
else
  echo "Working tree: clean"
fi
exit 0
