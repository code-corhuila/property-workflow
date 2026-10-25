# property-workflow

> Business process orchestration (saga)

Part of the **Pms_Property** distributed system, Grupo 2. Governance and
documentation live in [`property-docs`](https://github.com/code-corhuila/property-docs).

Orchestrates the processes that cross domains (norm 5.8, Annex E). Its one process
is the Saga **`reservar`** (ADR-009): create the `Reserva` in Booking, charge it in
Payment, confirm the `Reserva`; when a step fails, the completed ones are undone in
reverse order. Java 21 and Spring Boot 3.5, as a three-module Maven build (ADR-011).

```
workflow-core/       domain/saga (the state of a Saga) · application: ports + the orchestrator — no Spring
workflow-adapters/   in/http: the Saga endpoints · out/participants: Booking and Payment clients · out/state: saga store
workflow-app/        Spring Boot application: the composition root
deploy/              Dockerfile and this service's compose file
```

The three rules of a Saga (Annex E):

1. **Every step has its compensation.** The last step, `confirmar-reserva`, has none.
2. **The state is persisted after every step and every compensation.** It lives in
   the schema `workflow` of the shared PostgreSQL instance (ADR-010).
3. **Compensations run in reverse order and are idempotent.** Every call carries
   `Idempotency-Key: <sagaId>:<step>` (or `<sagaId>:<step>:compensar`).

The contract of the Saga is in `07-api/contracts/workflow-endpoint-fiches.md` of
`property-docs`.

## Run

```bash
mvn -B verify                                   # build and every test
mvn -B -DskipTests package
java -jar workflow-app/target/workflow-app-0.1.0.jar
```

Without Maven installed:

```bash
docker run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-21 mvn -B verify
```

The image:

```bash
docker build -f deploy/Dockerfile -t workflow .
```

Inside the network `platform` the workflow is `http://workflow:3006`; from outside
it is reached only through the gateway, at `http://localhost:8080/api/v1/sagas`.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  hu-<story>-qa
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` and `00-governance/git-conventions.md` in
`property-docs`.
