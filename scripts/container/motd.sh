# Printed by the login shell that scripts/devbox.sh opens. Installed as
# /etc/profile.d/whf-devbox.sh; `set -e` is not used here because a profile script must never be
# able to end the session.
#
# Only for a shell someone is sitting at, and on stderr. A login shell is not always interactive:
# `bash -lc 'java -jar ... narrate ...' > narrative.md` is a login shell too, and a banner on its
# stdout would end up in the file. Capturing a narration is a thing this project actually does.
case $- in
*i*)
    printf '\n\033[1;32mwhf-dev\033[0m  %s, %s\n' "$(java -version 2>&1 | head -1)" "$(mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3)" >&2
    printf '  /work   this repository, bind-mounted: edits here are edits on Windows\n' >&2
    printf '  /data   for databases, seeds and exports — keep them out of /work, which is git\n' >&2
    printf '  gate    cd /work/server && mvn -B verify        (PostgreSQL tests included)\n' >&2
    printf '          cd /work && uv run --python 3.11 --with pytest pytest server/tools/tests\n' >&2
    printf '  date    %s  (container clock; WHF_TZ sets it when the box is created)\n\n' "$(date '+%Y-%m-%d %H:%M %Z')" >&2
    ;;
esac
