# Reference Service helper scripts

Place these files into `obsinity-reference-service/` alongside the `docker-compose.yml` and `Dockerfile` we created.

- `build.sh`   — mvn build (module only) + docker compose build
- `run.sh`     — docker compose up -d + tail logs
- `runclean.sh`— down + up --build (fresh image)
- `dbshell.sh` — psql into the Postgres container

Usage:
```bash
cd obsinity-reference-service
./build.sh
./run.sh
# or:
./runclean.sh
./dbshell.sh
```
