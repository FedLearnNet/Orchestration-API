# Kubernetes execution backend

orch-api can run tools as Kubernetes Jobs instead of Docker containers, so a
client can be deployed on a cluster where no Docker socket is available.
This addresses issue #16.

```properties
orch.execution-backend=kubernetes   # default: docker, unchanged
```

## How it fits in

The callers no longer talk to the Docker services directly. They go through
four backend-neutral interfaces in `execution/`, implemented in
`execution/docker/` and `execution/k8s/`:

| Interface | Docker implementation | Kubernetes implementation |
|---|---|---|
| `AppExecutionService` (start, look up, log, clean up runs) | `DockerExecutionAdapter` → `DockerAppService`, `DockerCleanupService`, `DockerNetworkService`, `DockerPullService` | `KubernetesExecutionService` |
| `VolumeExecutionService` (a run's volumes, copy between them) | `DockerVolumeAdapter` → `DockerVolumeService` | `KubernetesVolumeService` |
| `VolumeAdminService` (volume REST API: list, upload, download) | `DockerVolumeAdminAdapter` → `DockerVolumeService` | `KubernetesVolumeAdminService` |
| `OrchestrationInfoService` (`GET /docker/info`) | `DockerOrchestrationInfoAdapter` | `KubernetesOrchestrationInfoService` |

The Docker adapters only delegate, so with `orch.execution-backend=docker`
behaviour is the same as before. The `*Producer` classes pick the
implementation from the property.

`AppRun` and `AppInstance` replace docker-java's `CreateContainerResponse`
and `Container` in the API. They serialize in Docker's wire shape (`Id`,
`Names`, `Image`, `Labels`, `State`, `Status`, `Mounts`), which is what
local-learning-api's `CreateContainerResponseDTO` and `ContainerDTO` read,
so local-learning-api needs no change. `DockerWireContractTest` pins this.

## Mapping

| Docker | Kubernetes |
|---|---|
| Container | `batch/v1` Job, one Pod, `restartPolicy: Never`, `backoffLimit: 0`; `ttlSecondsAfterFinished: 300` when auto-kill is on |
| Reaching the container by name on port 9000 | A ClusterIP Service `app-<job>` on `orch.kubernetes.app-port` (9000), owned by the Job. `Names[0]` is the Service name, so `BaseOrchestrator.getAppUrl` works as is |
| Dedicated network per container | A NetworkPolicy per run, named like the Job and owned by it (see below) |
| Named volume | PersistentVolumeClaim, `ReadWriteOnce`, `orch.kubernetes.volume-size`. Names are mapped with `K8sNames.pvcName` (`_` is not allowed in Kubernetes names) |
| Copying a file between volumes (alpine container) | A short-lived alpine Job with both PVCs mounted, same `cp`/`chown` script and `container.file-transfer.*` settings. Unlike Docker, a failed or timed-out copy fails the call, so the next node never starts on an empty input |
| Volume upload/download for the REST API | A short-lived Pod with the PVC mounted, `exec` + tar through fabric8 (needs the OkHttp client, see `pom.xml`) |
| Container logs | Pod log stream |
| Labels | Same labels from `LabelsHelper`, values sanitized for Kubernetes (`K8sLabels`) |
| `container.memory.limit` | Memory limit, plus `container.memory.request` (without it Kubernetes requests the whole limit) |
| `container.cpu.shares` | Not mapped (a relative weight); `container.cpu.request` instead, no limit |

### Network isolation

Each run gets a NetworkPolicy that selects only its own Pod and allows:

- ingress from, and egress to, the learning API (`container.network.learning`);
- for federated learning, the same for the controller (`container.network.controller`);
- DNS;
- with internet access: `0.0.0.0/0` except `orch.kubernetes.internal-cidrs`
  and, always, `169.254.0.0/16`, so a tool can't reach the cloud instance
  metadata service and its node credentials (internal CIDRs unset: other
  pods may be reachable too, with a warning).

The Job is created suspended and only released once its Service and
NetworkPolicy exist. If either can't be created, the Job is deleted and the
start fails, so a run never executes without its isolation.

The peers come from the same `<host>:<port>` settings the Docker backend
uses. `host` is a Service name, or `<service>.<namespace>[.svc…]` for
another namespace. orch-api reads that Service and uses its pod selector,
and the target port behind `port` for egress. A peer that doesn't resolve
fails the start before anything is created
(`ensureNetworkAccessConfigured`, as `DockerNetworkService.ensureAccessIsConfigured`
does on Docker). Turn it off with `orch.kubernetes.network-isolation.enabled=false`.

### Ownership

Everything orch-api creates carries this deployment's labels
(`LabelsHelper.createLabels()`, sanitized by `K8sLabels.owned()`), and every
operation that takes a name from a caller checks them (`K8sLabels.isOwned`),
as `LabelsHelper.isOwned` does on Docker. A Job or volume orch-api didn't
create, such as a database's PVC in the same namespace, reads as not found;
creating or removing one with that name is refused (403). Listing and bulk
deletes select on the same labels.

### Cleanup

`cleanupContainer(id, removeContainer, removeVolumes, removeDedicatedNetwork)`
follows `DockerCleanupService.cleanupContainer`: a run that isn't labelled
with this deployment's `container.name` is left alone; otherwise the Job is
always suspended (which stops its Pod), and then, as asked, the NetworkPolicy,
the Job (background deletion, so its Pod and Service go too) and the PVCs it
mounted are deleted. `cleanupWorkflow` also deletes PVCs labelled with the
workflow that no Job mounts any more, e.g. from a run that failed to start.

## Configuration

Besides `orch.execution-backend=kubernetes`:

| Property | |
|---|---|
| `container.name` | Required: a stable, lowercase DNS label of at most 40 characters (startup fails otherwise). It becomes part of run names and the ownership label; unset, `NamingService` makes up a new one on every start and earlier runs are no longer recognized as owned |
| `container.network.learning` | `<service>:<port>` of the learning API, e.g. `local-learning-api:8080` |
| `container.network.controller` | `<service>:<port>` of the controller's app-communication API, e.g. `controller:8001` |
| `orch.docker.health-check=false` | There is no Docker daemon to check |
| `orch.kubernetes.namespace` | Default: the client's namespace |
| `orch.kubernetes.storage-class`, `orch.kubernetes.volume-size` | PVCs |
| `orch.kubernetes.image-pull-secret` | If the tool registry needs credentials |
| `orch.kubernetes.internal-cidrs` | See network isolation |
| `orch.kubernetes.transfer-timeout` | How long a file transfer or copy between volumes may take, image pull included. Default `PT30M` |

The Docker event listener (pipeline cleanup, exit codes) does not start under
the Kubernetes backend, and neither does the startup cleanup of orphaned
Docker networks.

orch-api's ServiceAccount needs, in its namespace:

| Resource | Verbs |
|---|---|
| `jobs` (batch) | get, list, watch, create, patch, delete |
| `pods` | get, list, watch, create, delete |
| `pods/log` | get |
| `pods/exec` | get, create |
| `services` | get, create |
| `persistentvolumeclaims` | get, list, create, delete, deletecollection |
| `networkpolicies` (networking.k8s.io) | create, delete |

`get` on `pods/exec` as well as `create`: the client opens exec over a
WebSocket, an HTTP GET upgrade, which the API server authorizes as `get`
(with `create` alone, uploads into a volume fail with 403).

And, for `GET /docker/info` only, `list` on `nodes` (cluster-scoped). Where
the `OwnerReferencesPermissionEnforcement` admission plugin is on (e.g.
OpenShift), also `update` on `jobs/finalizers`, because the Service and
NetworkPolicy block their Job's deletion. The NetworkPolicies need a CNI that
enforces them.

## Not supported

- **Pipelines** (`ContainerPipelineBO`, tool builds): they need a Docker
  daemon inside the build and stay Docker-only.
- **`needsHostAccess`**: logged and ignored. Kubernetes has no equivalent of
  `host.docker.internal` short of `hostNetwork`.
- `container.memory.swap`, `container.enable.oomkill-disable`: no Kubernetes
  equivalent.
- `removeImage`: a no-op; nodes manage their own image cache.
- **Exit codes** (`ContainerRunEntity.exitCode`, `GET run/container/{id}`):
  recorded from Docker `die` events, which Kubernetes doesn't have. Runs keep
  a null exit code until a Job watch reports it.
- **System computing info** (`SystemComputingInfoBO`): reads the Docker
  daemon and falls back to the orch-api container's own CPU/RAM, so on
  Kubernetes it reports orch-api's Pod, not the nodes the runs land on.

## Tests

- `KubernetesExecutionServiceTest`: peer resolution through Services
  (including another namespace and misconfiguration), and cleanup
  (hard, soft, and refusing runs of another deployment), against fabric8's
  in-memory API server.
- `K8sNamesTest`: PVC name mapping.
- `DockerWireContractTest`: the JSON local-learning-api reads.

The backend has run on AKS in a DCsweden fork of this repository, with
local-learning-api reaching it from a training started on
dev.federated-learning.net. That fork predates `NamingService`,
`DockerCleanupService` and the network rework on `develop`; this port onto
`develop` has not yet run end to end on a cluster.
