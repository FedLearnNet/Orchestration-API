# FL-Net Orchestration API

This API runs FL-Net Tools as Docker containers. It pulls Tool images, creates an isolated network and file
volume per run, starts [app-build-pipeline](https://github.com/FedLearnNet/Tool-Build-Pipeline) builds, and
tracks the run lifecycle for the local and global
[Learning APIs](https://github.com/FedLearnNet/Learning-APIs). It is part of FL-Net, the federated learning
platform also behind PosyMed.

Built with [Quarkus](https://quarkus.io/), Java 25 and PostgreSQL.

## Documentation

- [FL-Net documentation](https://federated-learning.net/documentation/)

## Quick start

Requirements: Java 25 and a running Docker daemon (orch-api talks to `/var/run/docker.sock`).

```bash
cp .env.example .env          # fill in the secrets below
./mvnw compile quarkus:dev    # http://localhost:8082 · Dev UI /q/dev/ · Swagger UI /q/swagger-ui
```

Or run the API together with PostgreSQL (API on port 8093):

```bash
docker compose up --build
```


## Secrets

Each module reads its secrets from environment variables or a module-local `.env` file (never commit it —
use the `.env.example` templates):
- For more detail information which secrets are relevant and how to use them vist the documentation.

## Deployment settings

| Variable                        | Purpose                                                                                     |
|---------------------------------|---------------------------------------------------------------------------------------------|
| `CONTAINER_NAME`                | Stable, unique system name per clinic, e.g. `clinic1` (see below)                           |
| `CONTAINER_NETWORK_LEARNING`    | `<container>:<port>` of the Learning API joined to every app network, e.g. `local-learning-api:8080` |
| `CONTAINER_NETWORK_CONTROLLER`  | `<container>:<port>` of the FeatureCloud controller, e.g. `controller:8001` — **required before federated apps can start** |
| `QUARKUS_HTTP_CORS_ORIGINS`     | Allowed CORS origins (`staging`/`prod`)                                                     |
| `CONTAINER_HOST_OVERRIDE`       | Hostname apps use to reach the host (dev: `host.docker.internal`)                           |

### System naming and Docker ownership

`CONTAINER_NAME` namespaces all Docker resources (names plus the `system_name` label), so several clinics can
share one Docker daemon without touching each other's containers or volumes:

- unset → a random 16-character ID that **changes on every restart**
- up to 16 characters → used as-is (use Docker-safe names such as `clinic1`)
- longer → first 16 hex characters of its SHA-256 hash

Changing the name creates a new namespace. Finish active workflows before changing it; resources without a
`system_name` label are never cleaned up automatically and must be removed manually.


## License

[Apache License 2.0](LICENSE) © Institute for Computational Systems Biomedicine and contributors.
