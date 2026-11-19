# Sourced by the hooks in this folder. Reads the hook payload (JSON on stdin) without jq,
# which is not installed with Git for Windows.
#
#   payload=$(cat)
#   printf '%s' "$payload" | json_str command     # every string value of "command", decoded
#
# json_str prints each string value stored under KEY anywhere in the payload, one per line
# (MultiEdit carries several "new_string" values). Escapes \n \t \r \" \\ \/ are decoded; \uXXXX
# is left as-is, which is enough for the pattern checks the hooks do.
json_str() {
  awk -v key="$1" '
    BEGIN { RS = "\001" }
    {
      s = $0
      pat = "\"" key "\"[ \t\r\n]*:[ \t\r\n]*\""
      while (match(s, pat)) {
        s = substr(s, RSTART + RLENGTH)
        match(s, /^([^"\\]|\\.)*/)
        v = substr(s, 1, RLENGTH)
        s = substr(s, RLENGTH + 2)
        gsub(/\\\\/, "\002", v)
        gsub(/\\n/, "\n", v); gsub(/\\t/, "\t", v); gsub(/\\r/, "\r", v)
        gsub(/\\"/, "\"", v); gsub(/\\\//, "/", v)
        gsub(/\002/, "\\", v)
        print v
      }
    }'
}

# json_true KEY: succeeds when the payload holds "KEY": true
json_true() {
  grep -Eq "\"$1\"[[:space:]]*:[[:space:]]*true"
}
