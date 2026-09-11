# Printed by the login shell that scripts/devbox.sh opens. Installed as
# /etc/profile.d/whf-devbox.sh; `set -e` is not used here because a profile script must never be
# able to end the session.
printf '\n\033[1;32mwhf-dev\033[0m  %s, %s\n' "$(java -version 2>&1 | head -1)" "$(mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3)"
printf '  /work   this repository, bind-mounted: edits here are edits on Windows\n'
printf '  /data   for databases, seeds and exports — keep them out of /work, which is git\n'
printf '  gate    cd /work/server && mvn -B verify        (PostgreSQL tests included)\n'
printf '          cd /work && uv run --python 3.11 --with pytest pytest server/tools/tests\n'
printf '  date    %s  (container clock; set WHF_TZ on the host to change it)\n\n' "$(date '+%Y-%m-%d %H:%M %Z')"
