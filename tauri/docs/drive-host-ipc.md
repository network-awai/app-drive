# Drive 単一 volume host の IPC 契約（2 製品構成）

- Status: **設計（未実装）**
- 日付: 2026-11-25
- Owner 決定: 3（2026-11-25、AWAI-4118 確定設計 v1.0 §1。Drive が単一 volume host、Yataverse・他 dapp・OS 統合は IPC client）
- Tracking: [AWAI-4118](https://linear.app/kotobalabs/issue/AWAI-4118)（Orca Item #302）
- **これは性能契約ではない。** IPC の実装、実機での認可の証明、遅延・throughput の数値を含まない。数値は P0 の測定後に決める（[p0-measurement-plan.md](p0-measurement-plan.md)）。
- 関連: [scalable-sync-design.md](scalable-sync-design.md) §4.8・§4.9・§8、[encrypted-sync.md](encrypted-sync.md)、[portable-usb-custody.md](portable-usb-custody.md)、[awai-4118-child-issues.md](awai-4118-child-issues.md)

## 1. 製品構成と `awai-disk-core` の独占

### 1.1 製品の分割

Awai Drive と Yataverse の 2 製品構成とする（AWAI-4118 v1.0 §1）。

| プロセス | volume への link | 役割 |
|---|---|---|
| Drive host（daemon / sidecar / bound service） | **`awai-disk-core` への唯一の link を持つ** | volume の open / close、WAL と checkpoint、Pack の封、退避の判断、client の認可 |
| Drive UI（同一 app の WebView または native view） | 持たない。host の client | 表示と操作。host が落ちていれば操作できない |
| Yataverse | 持たない。host の client | ネットワーク同期と保持。単独時は network-only（§2.7） |
| 他の itonami dapp | 持たない。host の client | 自分の namespace だけを読む |
| OS 統合（File Provider / DocumentsProvider / WinFsp / FUSE） | 持たない。host の client | OS の API を IPC に翻訳するだけ（§7） |

`awai-disk-core` は Disk Format の実装で、形式の正本は `network-awai-apex/docs/awai-disk-format.md`。同 §1 の 4 層のうち 3（Disk Format）と 4（Disk System）を扱う。

**この分割の理由:** 1 volume 1 writer を OS のプロセス境界で保証するため（`awai-disk-format.md` §8）。writer が 1 つなら、fencing の epoch と WAL の鎖は 1 つのプロセスの中で閉じる。複数プロセスが同じ volume を開く設計にすると、`flock` の意味が「同一マシンの別プロセス」に広がり、network FS や VM をまたいだ時点で保証が消える。

### 1.2 2 番目のプロセスが volume を開こうとしたとき

| 場合 | 結果 |
|---|---|
| 2 つ目の Drive host の起動（同じ user） | 単一インスタンス選出（§2）で負け、**client として 1 つ目に接続する**。volume は開かない |
| 別 build の host、または host 以外のプロセスが `awai-disk-core` を直接開く | `awai-disk-core` の open が `busy` で失敗する。**強制 open の経路は作らない** |
| 別の volume を指す 2 つ目の host | 許可する。単一インスタンスは volume ではなく **host の endpoint 名**に対して取る（§2.3） |
| 同じ volume を別マシンから開く | 対象外。共有は Awai FS の分散 CAS の層で行う（`awai-disk-format.md` §8） |

- `flock` はプロセスの終了で OS が解放する。したがって「lock が取れない」は「生きているプロセスが持っている」を意味する。**古い lock を消して奪う処理は書かない。**
- `awai-disk-format.md` §8 の v1 の保証は POSIX の `flock` で、network FS をまたぐ fencing ではない。host は FS probe（v1.0 §4、`awai-disk-format.md` §7）で network FS を拒否する。
- Windows には `flock` がない。同等の排他（`FILE_SHARE_NONE` の lock file か `LockFileEx` か）は **未決定**で、Kura の format 改訂（v1.0 §7 の 1）に合わせて決める。それまで Windows の host は「排他が取れたと主張しない」。

### 1.3 host 自身の link が独占できないとき

host は次の順で判断し、**どれにも当てはまらなければ writer にならない**。

1. endpoint の単一インスタンス選出に勝つ（§2.3）。
2. volume の `flock` を取る。取れなければ `busy`。
3. 両方の root slot を読み、回復（`awai-disk-format.md` §6）を通す。通らなければ `read-only`（書き込み停止）。
4. root の writer epoch を進め、自分の `host-id` / `boot-id` を書く。

- 2 で失敗した場合、host は**読取専用の observer としても開かない**。`busy` を client 全員に返し、volume を open しない。理由: `flock` を持たないプロセスが WAL を読むと、checkpoint の作成と競合する。
- 3 で失敗した場合（fork 検出、デバイス集合の不一致、WAL の鎖の切れ）、host は `read-only` で起動し、`put` / `pin` / `evict` を型付きで拒否する。**zero 埋め・古い世代への後退・別ソースへの切替をしない**（`awai-disk-format.md` D7）。
- grade C（v1.0 §4）の volume では、Local-Meta は実質読取専用、Portable-USB は完全 read-only とする。host は grade を client に伝え、`read-only` を返す。

## 2. OS 別のプロセスライフサイクル

### 2.1 共通の性質

| 性質 | 設計 |
|---|---|
| UI を閉じた後 | host は動き続ける（Android は §2.5 の例外） |
| 自動起動 | 初回の volume 登録時に有効化を提案し、user が切れる。既定は「その volume を使った最後のログインで有効」 |
| 単一インスタンス | endpoint 名（ソケット / pipe / service）の登録が排他。負けたプロセスは client になる |
| crash / restart | OS の supervisor が再起動する。volume は open からやり直す（回復は `awai-disk-format.md` §6） |
| volume の open / close | open は host の起動時または最初の client 要求時。close は safe-eject か終了時 |
| safe-eject | `evict` とは別の操作。未確定の WAL を checkpoint に畳み、root を両 slot に書き、lease を release してから close する。**close に失敗したら「取り外してよい」と言わない** |
| open 中の upgrade | 旧 host は volume を close してから終了する。新 host は open からやり直す。**開いた volume の fd を新旧の間で渡さない**（版をまたぐ fd の互換を主張しない）。client は `host-restarting` を見て request-id を保ったまま再試行する |
| Yataverse 単独 | §2.7 |

### 2.2 macOS

- 形: **app に埋め込まない sidecar**を第一候補にする。`~/Library/LaunchAgents` の per-user LaunchAgent が sidecar を起動し、`KeepAlive` と `RunAtLoad` を設定する。Mac App Store の sandbox 配布は対象外（未確認）。
- endpoint: **App Group の Unix domain socket**（`group.network.awai.drive` の container 内、mode 0600）を data plane に、XPC（`MachServices`）を control plane に使う。XPC は fd を渡せないため、bytes は socket の `SCM_RIGHTS` で渡す。
- 単一インスタンス: XPC の `MachServices` 名の登録が排他。登録に失敗した 2 つ目は client になる。socket は lock file を `flock` してから `unlink` → `bind` する（起動競合で他人の socket を消さない）。
- 自動起動: LaunchAgent の plist。macOS 13 以降の `SMAppService` を使うかは**未決定**（署名と notarization の条件で変わる）。
- 制約: LaunchAgent は user のログイン中だけ動く。ログイン前・画面ロック中の USB 挿入は扱わない。

### 2.3 Windows

- 形: per-user の sidecar（`awai-drive-host.exe`）。ログオン時の自動起動（`HKCU\...\Run` か Task Scheduler）で起動する。**Windows サービス（`LocalSystem`）にはしない** — ユーザーの volume と鍵に届かないため。
- endpoint: **named pipe** `\\.\pipe\awai-drive-host-<user SID>`。control と data を同じ pipe の frame で運ぶ（§4.3）。
- 単一インスタンス: `Local\AwaiDriveHost-<user SID>` の named mutex。負けたプロセスは client になる。
- UI を閉じた後: sidecar は独立プロセスなので動き続ける。app の Job object に `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` を付けない（付けると UI の終了で host が死ぬ）。
- volume の排他: **未決定**（§1.2）。`awai-disk-core` が Windows で何を排他に使うかは Kura の format 改訂に依存する。
- 制約: ドライブレターで volume を同定しない（v1.0 §3）。volume UUID で同定する。

### 2.4 Linux

- 形: **systemd user unit** `awai-drive-host.service`。`Restart=on-failure`、`KillMode=mixed`。socket activation を使うかは**未決定**。
- endpoint: `$XDG_RUNTIME_DIR/awai-drive/host.sock`（tmpfs、directory 0700、socket 0600）。data は同じ socket の `SCM_RIGHTS`。
- 単一インスタンス: `flock` した lock file + socket の bind。
- 自動起動: `systemctl --user enable`。user の linger を有効にするかは**未決定**（ログアウト後も動かすかの trade-off）。
- 制約: `$XDG_RUNTIME_DIR` が無い環境（cron など）では起動しない。

### 2.5 Android

- 形: **bound service + 必要な間だけ foreground service**。`android:exported="false"`、signature 権限（§3.5）。UI を閉じると bound service は unbind される。
- UI を閉じた後の挙動: 実行中の同期・退避・Pack の封がなければ host は止まる。あれば foreground service（`foregroundServiceType` は**未決定**。`dataSync` 候補）として完了まで続け、完了後に止まる。常駐は既定にしない（通知が残るため）。
- 自動起動: user が有効にした場合だけ `BOOT_COMPLETED` で起動する。Android 12 以降の起動制限の下で、起動できない場合は「未起動」を UI に出す。
- volume: **Local-Meta のみ**（v1.0 §2・§3）。Portable-USB は Android では対象外。SAF の tree URI は `cache/` のみ（v1.0 §2）。
- crash: OS がプロセスを殺す。再起動時に volume を open し直し、回復を通す。client は `busy` / transport failure を見て再試行する。

### 2.6 iOS

**非対応。** 理由:

- 単一 volume host が UI の終了後も動き続けることを OS が保証しない（background execution は用途ごとの期限つきで、常駐 daemon の model がない）。
- 外部の USB 媒体を volume として開く経路がない（Files app と document picker はファイル単位）。
- File Provider extension は存在するが、host の代わりにはならない（extension の寿命は OS が決め、`awai-disk-core` の link を保持できない）。

したがって iOS では Files app / document picker + ネットワーク閲覧のみ（v1.0 §3）。

### 2.7 Yataverse が単独で動くとき

- 既定は **network-only**。canonical broker 経由で Yataverse の endpoint だけを使い、volume を開かない。
- ローカルのファイルが必要な操作では、**Drive host の導入を案内する**。案内を出したうえで、host が無い間はその操作を型付きで拒否する。
- **Yataverse が自分で volume を開く経路は作らない。** `awai-disk-core` への link を持つのは Drive host だけ（§1.1）。
- host が無いことを IPC のエラーコードにしない（IPC が成立しないため）。client は「host が見つからない」を transport failure として扱い、UI は「Drive host が動いていない」と「volume が無い」（`volume-absent`）を区別する。

## 3. OS 別の認証付き IPC

すべての transport で、host は**接続時に peer を認証し、clientId を割り当てる**。client は自分で `clientId` を名乗らない（§4.2）。認証に失敗したら接続を閉じ、型付きの `unauthorized` を返し、監査記録を残す。**弱い transport への fallback をしない。**

### 3.1 macOS: XPC（control）と App Group Unix socket（data）

| 項目 | 内容 |
|---|---|
| peer の証明 | XPC は接続の audit token から code signing の requirement を評価する（`NSXPCConnection` の `auditToken` → `SecCodeCopyGuestWithAttributes` → `SecRequirement`）。requirement は「同じ team の署名済みで、期待する identifier のいずれか」に固定する |
| App Group socket が証明するもの | `getpeereid` で **同じ uid** であること、および socket の mode 0600 を満たすこと。**product の同一性は証明しない**（同じ uid の別プロセスは token を読める） |
| 証明しないもの | プロセスの image が改変されていないこと、user の意図、sandbox の外の振る舞い |
| 失敗 | `unauthorized`。socket を閉じる。XPC は接続を invalidate する |

- 2 製品（Drive / Yataverse）を同じ uid で分ける必要があるため、**product の同一性は XPC の署名 requirement で判定する**。App Group socket だけを使う構成では、product 分離を主張しない（uid 分離まで）。
- data plane の socket 接続にも、control plane で発行した短命の token（メモリ内、single-use）を要求する。token は uid の外へ漏らさない。

### 3.2 Windows: named pipe + ACL

| 項目 | 内容 |
|---|---|
| peer の証明 | pipe の security descriptor を user SID（と、配布形態が MSIX なら package SID）に限定する。接続後に `GetNamedPipeClientProcessId` → プロセスの token の user SID を確認し、MSIX なら `GetPackageFullName` / `GetApplicationUserModelId` を確認する。`ImpersonateNamedPipeClient` で file 操作時の権限も確認する |
| SDDL の意図 | `D:P(A;;GA;;;<user SID>)(A;;GA;;;SY)` を基本に、client ごとの SID を足す。**Everyone / Authenticated Users を含めない**。実際の SDDL 文字列は実装時に固定する（**未決定**） |
| 証明しないもの | 同じ user SID の非パッケージ・プロセスの区別（SID が同じなら区別しない）、プロセスの完全性 |
| 失敗 | `unauthorized`。pipe を切断する |

- pid は補助情報としてのみ使う（再利用がある）。認可の判断は token の SID と package identity で行う。

### 3.3 Linux: Unix socket + `SO_PEERCRED`

| 項目 | 内容 |
|---|---|
| peer の証明 | `SO_PEERCRED` で pid / uid / gid を取る。uid が host の uid と一致することを必須にする。pid は `/proc/<pid>/stat` の starttime と併記して**同一性の hint** にする（pid の再利用に備える） |
| 追加の確認（任意） | systemd の unit / cgroup で「どの unit から来たか」を確認できるが、これは認可の根拠にしない（**未決定**） |
| 証明しないもの | 同じ uid の別プロセスの区別、pid の再利用を跨いだ同一性、プロセスの image |
| 失敗 | `unauthorized`。socket を閉じる |

- **pid の再利用の注意:** 接続時に pid を読み、`/proc/<pid>/stat` の starttime を記録する。後から同じ pid を別プロセスが使っていても、starttime が違えば別の peer として扱う。監査記録には pid と starttime を残し、**pid だけを同一性の鍵にしない**。

### 3.4 Android: bound service / AIDL

| 項目 | 内容 |
|---|---|
| peer の証明 | service は `exported="false"`、signature 権限 `network.awai.drive.permission.HOST_IPC` を要求する。呼び出しごとに `Binder.getCallingUid()` を取り、許可した signing certificate の digest の集合と `PackageManager.checkSignatures` で照合する。許可集合は host の control record に固定する |
| 証明しないもの | 同じ署名の app 同士の区別（同一署名は互いに信頼する）、`sharedUserId` を使わない前提の外側、端末の root 化 |
| 失敗 | `SecurityException` を投げずに **型付きの `unauthorized` を返して接続を閉じる**（呼び出し元に内部情報を返さない）。監査記録を残す |

- bytes は `ParcelFileDescriptor`（pipe）で渡す。Binder の transaction に bytes を詰めない。
- AIDL の interface には `interfaceVersion` を持たせ、host は未知の版を `version-unsupported` で拒否する。

### 3.5 認証のまとめ

| OS | 証明するもの | 証明しないもの |
|---|---|---|
| macOS XPC | 署名 requirement を満たす code | image の完全性、user の意図 |
| macOS App Group socket | 同じ uid、token の一致 | product の同一性（単独では） |
| Windows pipe | user SID（と package identity） | 同じ SID の別プロセス、image の完全性 |
| Linux socket | uid、pid + starttime（hint） | 同じ uid の別プロセス、image |
| Android Binder | 署名 digest の集合、呼び出し UID | 同じ署名の app の区別、root 化 |

## 4. Wire 契約

### 4.1 版

- すべての制御メッセージに `schemaVersion`（uint16）を必須とする。v1 は `1`。
- host は `minSupported`（自分が読める最小版）を持ち、`schemaVersion < minSupported` または `schemaVersion > 自分の版` を **`version-unsupported`** で拒否する。**未知の版を「近い版」として読まない。**
- 版の交渉は接続時の 1 回だけ。以後のメッセージで版を変えない。
- AIDL は `interfaceVersion` を別に持つ（Binder の interface descriptor の版）。

### 4.2 封筒

```text
Request {
  schemaVersion: uint16,        // 1
  op: string,                   // "put" | "pin" | "evict" | "get-commit-status" | ...
  requestId: bytes[16..32],     // client が生成。op ごとに一意
  deadlineMs: uint32,           // client が要求する期限（0 = host の既定）
  body: <op ごと>
}

Response {
  schemaVersion: uint16,
  requestId: bytes[16..32],
  status: "ok" | "error",
  body: <op ごと> | { code: <error code>, message: string }  // message は秘密値を含まない
}
```

- `clientId` は host が接続時に割り当て、応答にだけ載せる。**client の自己申告を受け付けない。**
- 制御メッセージの最大は **1 MiB**。超えたら `too-large` で拒否し、**部分実行しない**。
- bytes は制御メッセージに埋め込まない。`dataHandle`（fd / `ParcelFileDescriptor` / pipe 上の `data-open` の id）で渡す（§4.3）。

### 4.3 bytes の運び方

| transport | bytes の運び方 |
|---|---|
| macOS App Group socket | `SCM_RIGHTS` で fd を渡す。受信側は宣言された長さだけ読む |
| Windows named pipe | pipe 上の `data-open` / `data-chunk` / `data-end` frame。chunk の長さは frame header に持ち、**base64 にしない** |
| Linux socket | `SCM_RIGHTS` で fd を渡す |
| Android Binder | `ParcelFileDescriptor`（pipe） |

- `put` は `{ namespace, path, size, sha256, dataHandle }` を送り、host は `size` と `sha256` を検証してから WAL に書く。宣言より多く送られたら `too-large` で中止する。
- `get` / hydration は host が fd を渡し、client が読む。host は読んだ量を検証する。
- **inline の base64 を作らない。** 現行の 65531 byte × base64 の経路（`encrypted-sync.md`、`scalable-sync-design.md` §4.6）はこの契約の外にある。

### 4.4 backpressure・timeout・cancellation・順序

- **backpressure:** host は client ごとに「未完了の data frame の credit」を持つ。credit が 0 のとき client は送らない。credit の初期値と上限は **未決定**（P0 で測る）。host は credit を返す前に staging の空きを確認する。
- **timeout:** `deadlineMs` は client の希望で、host は自分の上限（**未決定**）で切り詰める。期限を超えたら `busy` を返し、**途中まで書いた data を確定しない**（WAL に書いていなければ何も残らない）。
- **cancellation:** client は同じ `requestId` の `cancel` を送れる。host は「まだ WAL に書いていない」場合だけ中止できる。書いた後は取り消さず、結果を `get-commit-status` で返す（**取消と未確定を混同しない**）。
- **順序:** 同じ client の要求は受信順に処理する。`put` とその `get-commit-status` の順序は保証する。**client をまたぐ順序は保証しない**（A の `put` の完了は B の `put` の前後を決めない）。

### 4.5 エラーの分類

| code | 意味 | client の扱い |
|---|---|---|
| `unauthorized` | peer 認証の失敗、namespace 外、pin の所有者違い | 再試行しない。UI に権限が無いことを出す |
| `version-unsupported` | 版が範囲外 | 再試行しない。app の更新を促す |
| `too-large` | 制御メッセージ、宣言された bytes、または quota の上限超え（quota は `quota-exceeded`） | 要求を分割するか中止する |
| `busy` | volume が別の writer に握られている、または期限切れ | 待って再試行してよい（同じ requestId） |
| `volume-absent` | volume が無い（未接続、取り外し済み） | UI に「媒体が無い」を出す。書き込みを再試行しない |
| `read-only` | grade C、fork 検出、回復の失敗で書き込み停止中 | 読み取りだけを続ける。UI に理由を出す |
| `quota-exceeded` | client の quota（予約 + 実使用）を超える | 退避か削除を user に求める。**host が勝手に他人の領域を削らない** |
| `idempotency-conflict` | 同じ `requestId` で違う内容 | 再試行しない。新しい `requestId` を使う |
| `commit-unknown` | 結果を確定できない（host の crash、記録の期限切れ） | **盲目的に再送しない。** §5 の手順に従う |
| `internal-error` | 上記以外の host 内部の失敗 | 同じ `requestId` で 1 回だけ再試行してよい。繰り返すなら報告 |

- 型付きの拒否に対して、host は**部分的な成功を返さない**（`ok` と `error` の混在を作らない）。
- transport の失敗（接続断、pipe の消失）は IPC のエラーコードではない。client は「host が動いていない」として扱い、再接続して `get-commit-status` で確認する。

## 5. 冪等性と commit の状態

### 5.1 `request-id`

- `put` / `pin` / `evict` は `requestId` を必須とする。
- host は `(clientId, requestId)` を鍵に**不変の記録**を持つ。同じ鍵の再送は、保存も書き込みもやり直さず、**同じ結果**を返す。
- 記録に含めるもの: `clientId`、`requestId`、`op`、対象（namespace / path / CID）、payload の digest、結果（`committed` / `failed` + code）、`commit` の根拠（volume lineage、generation、Pack id または WAL の seq）、受付時刻。

### 5.2 二重 commit の拒否

- 同じ `(clientId, requestId)` で **payload の digest か対象が違う**要求は `idempotency-conflict`。**2 回目を書かない。** 監査記録を残す。
- 同じ内容の再送は冪等（同じ結果）。

### 5.3 `get-commit-status`

`get-commit-status` は `requestId` だけを受け取り、次を返す。

| 結果 | 意味 | client の扱い |
|---|---|---|
| `committed` | volume に確定した。根拠（generation と WAL の seq）を返す | 完了として扱う |
| `failed` | 確定していない。理由の code を返す | 新しい `requestId` で再試行してよい |
| `unknown` | host が確定を判断できない（crash 時に in-flight、記録が期限切れ） | **再送しない。** volume を読み直して内容を確認するか、user に確認を求める |

### 5.4 記録の寿命と置き場所

- 置き場所: host の**device-protected な control record**（volume の外）。`encrypted-sync.md` の control record と同じ扱いで、退避の対象にしない。
- 寿命: 最初の受付から **30 日**、または client の `get-commit-status` が `committed` / `failed` を返した後で **24 時間**のどちらか長い方。期限を過ぎたら `unknown`（`commit-unknown`）を返す。
- 保持数の上限は **未決定**（P0 で件数と大きさを測る）。上限に達した場合は**古い記録から `unknown` にする**（結果を捏造しない）。
- **限界:** 現行の Disk Format は WAL frame に `requestId` を持たない。したがって host の crash 時に「WAL に書いたが記録を書く前」だった要求は `unknown` になる。これを `committed` / `failed` に縮めるには、Kura の format 改訂で WAL frame に `requestId`（または host の記録の digest）を載せる必要がある（v1.0 §7 の 1）。**それまでは `unknown` を正しい答えとして扱う。**

### 5.5 前後関係

- `put` の結果を返す前に、host は記録を永続化する（write-ahead）。ack を失っても `get-commit-status` が答えられる。
- `pin` / `evict` も同じ。`evict` は「対象の block を消した」ではなく「退避の判断を確定した」を記録する。実際の回収は catalog と root の更新後（§8 の順序）。

## 6. client の namespace と quota

### 6.1 clientId と namespace

- `clientId` は「認証した peer の product identity + 署名 identity の digest + その install の instance id」から host が作る。**client は名乗らない。**
- namespace は owner の WNFS 名前空間の下の `clients/<clientId>` directory node。client の path はこの node からの相対で解決する。
- `..`、絶対 path、他の client の namespace を指す参照は `unauthorized`。**CID を直接指定する読み取りも、自分の namespace の closure の外なら拒否する。**
- 読み取り専用の client（`view: read-only`）は、変更系の op すべてで `read-only`。

### 6.2 quota

- client ごとに `reserve` / `release` を持つ。quota は「生きた予約の合計 + 実使用」で数える。
- `put` は書く前に予約を取る。予約が取れなければ `quota-exceeded` で、**bytes を 1 つも書かない**（`awai-disk-format.md` §7.4 の予約台帳と同じ規則）。
- 確定時に実使用を記録し、予約との差を返す。予約の失効（client の切断、期限）は**実使用を減らさない**。
- quota の値は **未決定**（P0 と、Yataverse の保持設計の予約に合わせる）。host は値を持たずに「無制限」を既定にしない。

### 6.3 pin の所有と cross-client の保護

- `pin` は `(target, owner: clientId)` の record を持つ。
- client B の `evict` が client A の pin を指したら、**`unauthorized` で拒否する**。判断は「target のすべての pin の owner が呼び出し元と一致するか」。
- 拒否と許可のどちらも、**append-only の監査記録**に残す: `requestId`、`clientId`、op、対象、判断、理由、時刻。**秘密値（鍵、capability、平文）を記録しない。**
- 監査記録は host の control record に置き、client からは読めない（host の UI と、owner の明示的な操作だけが読む）。
- 同じ target を複数の client が pin してよい。すべての pin が外れるまで evict できない。`pin` 自体は quota を消費する（保持の予約として数える）。

## 7. OS 統合アダプタ（host の client）

いずれも `awai-disk-core` を link せず、volume を開かない。IPC の client として振る舞う。

| アダプタ | OS | 状態 | 読み書き |
|---|---|---|---|
| File Provider | macOS | **未実装**（`scalable-sync-design.md` §5 の第 2 段） | 最初は read-only（取り込んだ複製を公開する）。書き込みは別 gate |
| DocumentsProvider | Android | 現行は **read-only**（`tauri/README.md` の Safety and integration） | read-only。host 経由の書き込みは未実装 |
| WinFsp | Windows | **未実装** | 未決定 |
| FUSE | Linux | **未実装**（NBD は別 volume、v1.0 §1） | 未決定 |

- アダプタは OS の API の意味を IPC に写すだけで、認可・quota・退避の判断を持たない。判断は host。
- アダプタの `clientId` は product ではなく**アダプタの種類**で分ける（同じ Drive でも File Provider と UI は別の client）。これにより「UI が pin したものを File Provider が evict する」経路を塞ぐ。
- 二重 cache を作らない: File Provider の materialized cache と `cache/` を同じ内容で二重に持たない（`scalable-sync-design.md` §5）。

## 8. 同意の境界

- **canonical broker が外部ネットワークの唯一の同意経路**である（`scalable-sync-design.md` §4.9、`awai-os/docs/otehon/app-storage.md` の「アプリとホストの境界」）。host IPC は**ローカルの I/O**であり、ネットワーク同意の迂回ではない。
- host は外部へ通信しない。同期は Yataverse（または Drive の同期 client）が broker 経由で行う。host が bytes を渡すのは local の fd だけ。
- **ローカル USB I/O は同意の対象外**（v1.0 §5、P6 の定義）。同意が要るのはネットワークの大量同期と無人退避で、それは broker の bounded batch（P6）が持つ。
- P6 は shared runtime で実装され、製品は消費するだけ。**製品側で同意のロジックを複製・拡張しない**（§4.9、[bounded-batch-consent-proposal.md](bounded-batch-consent-proposal.md)）。
- host の IPC に「同意」の概念を持ち込まない。IPC の認可（client の namespace / quota / pin）は**資源の保護**で、ネットワークの同意とは別の層。

## 9. 失敗とセキュリティの場合

| 場合 | host の振る舞い | client / UI が見せるもの |
|---|---|---|
| host が動いていない | IPC が成立しない | 「Drive host が動いていない」。`volume-absent` と区別する |
| 版のずれ（client が新しい / 古い） | `version-unsupported` | 更新の案内。**古い版で書き込ませない** |
| 偽の peer・未知の peer | 接続を閉じ、`unauthorized`、監査記録 | 権限が無いこと。再試行の案内を出さない |
| 大きすぎる要求 | `too-large`。何も書かない | 要求を分割する案内 |
| 同じ `requestId` の重複 | 同じ結果、または `idempotency-conflict` | 何もしない（成功済みなら完了表示） |
| volume が無い | `volume-absent` | 「媒体が無い」。書き込みの再試行を促さない |
| grade C / fork 検出 | `read-only`。書き込みを停止 | 理由（grade C、fork、回復失敗）と、読み取りのみ続けられること |
| fork（同じ lineage + 同じ instance + 競合 epoch） | **読取専用で停止**。epoch を進めない | 「この volume は別の writer と競合している」。**自動で片方を選ばない** |
| オンラインの巻き戻し（古い `base-epoch`） | server が commit を拒否（§4.8）。host は黙って rebase しない | 拒否の理由。**「同期した」と言わない** |
| オフライン・媒体全体の巻き戻し | **検出できない** | UI に「検出できない」と明記する（`scalable-sync-design.md` §4.8） |
| 監査記録の書き込み失敗 | その操作を**拒否する**（記録できない操作を通さない） | 一時的な失敗として案内 |

- UI の文言・状態遷移・figma は Gamen の UI 設計（AWAI-4118 v1.0 §7 の 3、`gamen-sekkei`）が持つ。ここでは「UI が区別しなければならない状態」だけを列挙し、UI を設計しない。
- host は「どの client が何をしたか」を監査記録に残すが、**平文・鍵・capability を残さない**。記録の暗号化と保持期間は **未決定**。

## 10. 未決定・未測定

1. **Windows の volume 排他**（`FILE_SHARE_NONE` か `LockFileEx` か）。Kura の format 改訂（v1.0 §7 の 1）で決まる。
2. **macOS App Group socket の product 分離**。XPC の署名 requirement を data plane にも要求できるか（socket 接続に token を必須にする実装）。
3. **Android の foreground service type**と、常駐を既定にしない場合の同期の完了保証。
4. **backpressure の credit の初期値・上限、`deadlineMs` の host 側の上限、`get-commit-status` の記録の保持数**。すべて P0（[p0-measurement-plan.md](p0-measurement-plan.md)）で測る。
5. **WAL frame への `requestId` の追加**。これが無い間は crash 時の `unknown` が残る（§5.4）。
6. **quota の値**。Yataverse の保持設計の予約（`app-itonami-yataverse/docs/retention-design.md` §10 の冪等性）と合わせる。
7. **監査記録の保持期間と暗号化**。device-protected な control record に置くことだけ決めている。
8. **File Provider の書き込み mode**、WinFsp / FUSE の読み書き mode と着手順。
9. **`host-absent` を型付きの code にするか**。現案は transport failure として扱い、IPC の code にしない。
10. **読み取り専用 client が `pin` できるか**。現案は不可（pin は保持の予約で、quota と監査を伴う書き込み）。
