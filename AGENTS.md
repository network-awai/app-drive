# etzhayyim-project-drive — Google Drive v3 + Microsoft Graph (OneDrive) compatible drive API

**drive.etzhayyim.com** — a drive/files API whose external surface is shaped like **both**
Google Drive v3 and Microsoft Graph (OneDrive), over **one** canonical store on **kotoba
datomic** (graph `drive-v1`). Second vertical of the workspace-compat program; same pattern
as calendar.

SSoT: `90-docs/adr/2606010500-workspace-compat-datomic-schema.md`

## 一行定義

Drive ≝ CanonicalFile[:drive/*] × KotobaDatomic[drive-v1] × {GoogleSkin[/drive/v3], MicrosoftSkin[/v1.0/me/drive]}

## アーキテクチャ

```
Google SDK  → drive.etzhayyim.com/drive/v3/files          ┐
MS Graph SDK→ drive.etzhayyim.com/v1.0/me/drive/root/children ┘ (two external skins)
  CF Worker drive-compat (50-infra/cloudflare/workers/drive-compat)
   ├─ /xrpc/ai.etzhayyim.apps.drive.*  → pod (actor-worker passthrough)
   └─ provider REST → canonical XRPC → pod → reshape to provider JSON
  lg-drive pod (60-apps/etzhayyim-project-drive/lg, FastAPI server.py)
   → kotoba datomic transact/q/pull on graph drive-v1 (:drive/* namespace)
```

Same edge/routing/persistence rules as calendar (ADR-2605111200 edge-only;
atproto catch-all actor:true → pipethroughActorWorker → drive.etzhayyim.com/xrpc →
`DRIVE_POD_URL` tunnel → pod; dedicated graph `drive-v1`).

## Canonical XRPC methods (ai.etzhayyim.apps.drive.*)

| method | Google Drive v3 | Microsoft Graph |
|---|---|---|
| `filesList` | files.list | GET /me/drive/{root,items/{id}}/children |
| `filesGet` | files.get | GET /me/drive/items/{id} |
| `filesCreate` (retired: 410) | files.create | POST /me/drive/items/{parent}/children |
| `filesUpdate` (retired: 410) | files.update (patch) | PATCH /me/drive/items/{id} |
| `filesDelete` (retired: 410) | files.delete | DELETE /me/drive/items/{id} |
| `about` | about.get | GET /me/drive |
| `changes` | changes.list | GET /me/drive/root/delta |

Retired: with a valid `x-api-key`, the lg-drive server answers `filesCreate` / `filesUpdate` /
`filesDelete` with 410 and does not call the handler. Every xrpc method requires `x-api-key`
(no configured key = 401). `GET /health` and `GET /ok` take no key.

Lexicons: `00-contracts/lexicons/ai/etzhayyim/apps/drive/` (`defs.json` file/about/change superset).
Join key `:drive/sha256` (Google `sha256Checksum` == MS `file.hashes.sha256Hash`).
**Binary content** is NOT a datom — PDS content-addressed blob layer (`blobs/{repo}/{sha256hex}`);
`:drive/sha256` links metadata→blob. ETag/If-Match ← `:drive/version`.

## ファイル構成

```
lg/  (bb.edn, run_tests.clj)   # canonical = the clj twin (ADR-2606280030); DEV-stage py deleted
└── clj/lg_drive/{server,handlers,store,mapping,kotoba_datomic,edn,ids}.cljc + graphs/health.cljc
    clj/lg_drive/{test_handlers,test_graph}.cljc   # 13 tests (CRUD/list/version/changes/about + graph/server)
(edge) 50-infra/cloudflare/workers/drive-compat/   # Google + MS skins (12 tests)
(infra) 50-infra/vultr/lg-drive-pool/              # Helm (mirror lg-calendar-pool)
```

## Test
```bash
cd 60-apps/etzhayyim-project-drive/lg && kbb run_tests.cljk
cd 50-infra/cloudflare/workers/drive-compat && node --test test/*.test.ts
```

## Awai Drive Tauri client

`tauri/` owns the Android and macOS Tauri app `network.awai.drive`.
Follow the workspace-level client runtime and consent contract. The client uses
its local adapters and digest-pinned product dependencies. The existing
`lg/`, `appview/`, and `otehon/` components have their own boundaries.

Use [the Tauri runbook](tauri/README.md) for build and test instructions and
[the WNFS design](tauri/docs/scalable-sync-design.md) for sync changes.
Keep existing owner-store data readable during the proposed copy-only migration.
