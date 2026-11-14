# Changelog of `property-workflow`

Every release of this repository, newest first. One release per course cut, tagged on `main`
(`git-conventions.md`, "Tags and versioning"; SemVer). Each entry lists the user stories it
brought, with the pull requests of `develop` that built them.

## [3.0.0] - 2026-11-15

### Added

- **HU-SAGA-001 (code-corhuila/property-docs#147) — Reserve a property**
  - chore(workflow): scaffold the three-module spring boot service (#1)
  - feat(workflow): create the workflow schema for the saga state (#2)
  - feat(workflow): add the saga state and the ports of the saga reservar (#3)
  - feat(workflow): orchestrate the saga reservar with its compensations (#4)
  - feat(workflow): add the common http adapters (#5)
  - docs(workflow): publish the versioned openapi contract (#6)
  - fix(workflow): store the correlation id as text of 1 to 128 characters (#7)
  - chore(workflow): run the integration tests against the workflow schema (#8)
  - feat(workflow): keep the state of every saga in the workflow schema (#9)
  - feat(workflow): call booking and payment with the service token (#10)
  - feat(workflow): start a saga reservar and read it over http (#11)
- **HU-SAGA-004 (code-corhuila/property-docs#196) — Recover sagas left running**
  - feat(workflow): settle the sagas left running (#12)
