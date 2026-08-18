#!/usr/bin/env bash
#
# Two anonymous listeners, one database: does RLS actually hold?
#
# Everything here goes through the PostgREST and Auth REST APIs with the
# publishable key - exactly what the app has, and exactly what an attacker who
# unzips the APK has. That is the point: the key is public, so the only thing
# standing between one listener and another's rows is the policy set in
# supabase/migrations/0001_reaction_foundation.sql.
#
# Usage:
#   SUPABASE_URL=https://ref.supabase.co \
#   SUPABASE_PUBLISHABLE_KEY=sb_publishable_... \
#   tools/supabase/rls-check.sh
#
# Requires: curl, python3. Creates two throwaway anonymous users and one track.
# Anonymous sign-ins must be enabled for the project, and the project's IP rate
# limit (30/hour by default) applies to the sign-ins this makes.

set -euo pipefail

URL="${SUPABASE_URL:?set SUPABASE_URL}"
KEY="${SUPABASE_PUBLISHABLE_KEY:?set SUPABASE_PUBLISHABLE_KEY}"

pass=0
fail=0

check() { # check <description> <expected: allow|deny> <http status>
    local what="$1" expected="$2" status="$3"
    local got="deny"
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ]; then got="allow"; fi

    if [ "$got" = "$expected" ]; then
        printf '  PASS  %-58s (%s)\n' "$what" "$status"
        pass=$((pass + 1))
    else
        printf '  FAIL  %-58s (%s, expected %s)\n' "$what" "$status" "$expected"
        fail=$((fail + 1))
    fi
}

json_field() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

signin_anon() {
    curl -s -X POST "$URL/auth/v1/signup" \
        -H "apikey: $KEY" -H "Content-Type: application/json" \
        -d '{}' | json_field access_token
}

# Anonymous sign-in endpoint differs by gateway version; try the explicit one first.
signin() {
    local token
    token=$(curl -s -X POST "$URL/auth/v1/signup" \
        -H "apikey: $KEY" -H "Content-Type: application/json" \
        -d '{"data":{}}' | json_field access_token)
    if [ -z "$token" ]; then
        token=$(signin_anon)
    fi
    echo "$token"
}

uid_of() { # decode the sub claim without verifying - this is a test script
    python3 -c "
import sys, base64, json
t = sys.argv[1].split('.')[1]
t += '=' * (-len(t) % 4)
print(json.loads(base64.urlsafe_b64decode(t))['sub'])
" "$1"
}

post() { # post <table> <token> <body> [prefer]
    curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/rest/v1/$1" \
        -H "apikey: $KEY" -H "Authorization: Bearer $2" \
        -H "Content-Type: application/json" \
        -H "Prefer: ${4:-return=minimal}" \
        -d "$3"
}

get() { # get <path> <token>
    curl -s -o /dev/null -w '%{http_code}' "$URL/rest/v1/$1" \
        -H "apikey: $KEY" -H "Authorization: Bearer $2"
}

patch() { # patch <path> <token> <body>
    curl -s -o /dev/null -w '%{http_code}' -X PATCH "$URL/rest/v1/$1" \
        -H "apikey: $KEY" -H "Authorization: Bearer $2" \
        -H "Content-Type: application/json" -d "$3"
}

echo "== signing in two anonymous listeners"
TOKEN_A=$(signin); [ -n "$TOKEN_A" ] || { echo "anonymous sign-in failed - is it enabled for this project?"; exit 1; }
TOKEN_B=$(signin); [ -n "$TOKEN_B" ] || { echo "second anonymous sign-in failed"; exit 1; }
UID_A=$(uid_of "$TOKEN_A")
UID_B=$(uid_of "$TOKEN_B")
echo "   A = $UID_A"
echo "   B = $UID_B"
[ "$UID_A" != "$UID_B" ] || { echo "both sign-ins returned the same uid - not two listeners"; exit 1; }

KEY_1="rlscheck$(date +%s)"
EVENT_A=$(python3 -c "import uuid; print(uuid.uuid4())")
EVENT_B=$(python3 -c "import uuid; print(uuid.uuid4())")

echo
echo "== A operates on its own rows"
check "A adds a track"                    allow "$(post tracks "$TOKEN_A" "{\"track_key\":\"$KEY_1\",\"artist\":\"RLS\",\"title\":\"Check\"}")"
check "A creates its own reaction"        allow "$(post reactions "$TOKEN_A" "{\"listener_id\":\"$UID_A\",\"track_key\":\"$KEY_1\",\"reaction\":\"LIKED\",\"stream\":\"myata\"}")"
check "A reads its own reaction"          allow "$(get "reactions?listener_id=eq.$UID_A" "$TOKEN_A")"
check "A updates its own reaction"        allow "$(patch "reactions?listener_id=eq.$UID_A&track_key=eq.$KEY_1" "$TOKEN_A" '{"reaction":"DISLIKED"}')"
check "A appends its own event"           allow "$(post reaction_events "$TOKEN_A" "{\"event_id\":\"$EVENT_A\",\"listener_id\":\"$UID_A\",\"track_key\":\"$KEY_1\",\"event_type\":\"DISLIKE\",\"stream\":\"myata\",\"occurred_at\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}")"

echo
echo "== A cannot act as B"
check "A writes a reaction owned by B"    deny  "$(post reactions "$TOKEN_A" "{\"listener_id\":\"$UID_B\",\"track_key\":\"$KEY_1\",\"reaction\":\"LIKED\"}")"
check "A appends an event owned by B"     deny  "$(post reaction_events "$TOKEN_A" "{\"event_id\":\"$EVENT_B\",\"listener_id\":\"$UID_B\",\"track_key\":\"$KEY_1\",\"event_type\":\"LIKE\",\"occurred_at\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}")"

echo
echo "== B cannot touch A's rows"
# A read that returns no rows is 200 with an empty body: RLS filters rather than
# refuses, so the check is on the body, not the status.
ROWS=$(curl -s "$URL/rest/v1/reactions?listener_id=eq.$UID_A" -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_B")
if [ "$ROWS" = "[]" ]; then
    printf '  PASS  %-58s (empty)\n' "B reading A's reactions sees nothing"
    pass=$((pass + 1))
else
    printf '  FAIL  %-58s (%s)\n' "B reading A's reactions sees nothing" "$ROWS"
    fail=$((fail + 1))
fi
# NOTE ON STATUS CODES. When RLS filters rows away, PostgREST reports 204 - the
# statement matched nothing - not 403. So for a write that policy is meant to
# stop, the status says nothing useful, and the only real question is whether the
# data moved. These three checks read the row back instead of trusting a code.
patch "reactions?listener_id=eq.$UID_A&track_key=eq.$KEY_1" "$TOKEN_B" '{"reaction":"LIKED"}' > /dev/null
STILL=$(curl -s "$URL/rest/v1/reactions?listener_id=eq.$UID_A&select=reaction" -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_A")
if echo "$STILL" | grep -q DISLIKED; then
    printf '  PASS  %-58s\n' "B cannot change A's reaction (row unchanged)"
    pass=$((pass + 1))
else
    printf '  FAIL  %-58s (%s)\n' "B cannot change A's reaction (row unchanged)" "$STILL"
    fail=$((fail + 1))
fi

echo
echo "== history is append-only, and analytics are not for clients"
patch "reaction_events?event_id=eq.$EVENT_A" "$TOKEN_A" '{"event_type":"LIKE"}' > /dev/null
AFTER_PATCH=$(curl -s "$URL/rest/v1/reaction_events?event_id=eq.$EVENT_A&select=event_type" -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_A")
if echo "$AFTER_PATCH" | grep -q DISLIKE; then
    printf '  PASS  %-58s\n' 'an event cannot be edited (still DISLIKE)'
    pass=$((pass + 1))
else
    printf '  FAIL  %-58s (%s)\n' 'an event cannot be edited (still DISLIKE)' "$AFTER_PATCH"
    fail=$((fail + 1))
fi

curl -s -o /dev/null -X DELETE "$URL/rest/v1/reaction_events?event_id=eq.$EVENT_A" -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_A"
AFTER_DELETE=$(curl -s "$URL/rest/v1/reaction_events?event_id=eq.$EVENT_A&select=event_type" -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN_A")
if [ "$AFTER_DELETE" != "[]" ] && [ -n "$AFTER_DELETE" ]; then
    printf '  PASS  %-58s\n' 'an event cannot be deleted (row still there)'
    pass=$((pass + 1))
else
    printf '  FAIL  %-58s (%s)\n' 'an event cannot be deleted (row still there)' "$AFTER_DELETE"
    fail=$((fail + 1))
fi
check "A reads station-wide totals"       deny  "$(get "track_reaction_totals" "$TOKEN_A")"
check "an unauthenticated caller reads reactions" deny "$(curl -s -o /dev/null -w '%{http_code}' "$URL/rest/v1/reactions" -H "apikey: $KEY")"

echo
echo "== $pass passed, $fail failed"
[ "$fail" -eq 0 ]
