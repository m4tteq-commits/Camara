#!/bin/sh
set -eu

APP_DIR=/DATA/AppData/camara
export DOCKER_CONFIG="$APP_DIR/.docker"
COMPOSE=/usr/lib/docker/cli-plugins/docker-compose
if [ ! -x "$COMPOSE" ]; then
    COMPOSE=docker-compose
fi
cd "$APP_DIR/source"
"$COMPOSE" -p camara config --quiet
"$COMPOSE" -p camara build api
"$COMPOSE" -p camara run --rm --no-deps --user 0 --entrypoint python api -c "import os; os.chown('/backups',10001,10001); os.chmod('/backups',0o700)"
"$COMPOSE" -p camara up -d
"$COMPOSE" -p camara exec -T ollama ollama pull qwen3.5:2b
"$COMPOSE" -p camara ps
