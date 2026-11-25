# Drive 単一 volume host の IPC 契約（2 製品構成）

- Status: **設計（未実装）**
- 日付: 2026-11-25
- Owner 決定: 3（2026-11-25、AWAI-4118 確定設計 v1.0 §1。Drive が単一 volume host、Yataverse・他 dapp・OS 統合は IPC client）
- Tracking: [AWAI-4118](https://linear.app/kotobalabs/issue/AWAI-4118)（Orca Item #302）
- **これは性能契約ではない。** IPC の実装、実機での認可の証明、遅延・throughput の数値を含まない。数値は P0 の測定後に決める（[p0-measurement-plan.md](p0-measurement-plan.md)）。
- 関連: [scalable-sync-design.md](scalable-sync-design.md) §4.8・§4.9・§8、[encrypted-sync.md](encrypted-sync.md)、[portable-usb-custody.md](portable-usb-custody.md)、[p0-measurement-plan.md](p0-measurement-plan.md)、[awai-4118-child-issues.md](awai-4118-child-issues.md)
- 形式の参照: `network-awai/network-awai-apex` PR #381 の `docs/awai-disk-format.md` **改訂案**（未マージ）。§4.5（writer epoch / lease）、§4.6（移動・clone・fork）、§14（「停止」の語義）、§21.8（safe-eject）。

## 1. 製品構成と `awai-disk-core` の独占

### 1.1 製品の分割

Awai Drive と Yataverse の 2 製品構成とする（AWAI-4118 v1.0 §1）。

| プロセス | volume への link | 役割 |
|---|---|---|
| Drive host（daemon / sidecar / bound service） | **`awai-disk-core` への唯一の link を持つ** | volume の open / close、WAL と checkpoint、Pack の封、退避の判断、client の認可 |
| Drive UI（同一 app の WebView または native view） | 持たない。host の client | 表示と操作。host が落ちていれば操作できない |
| Yataverse（と Drive の同期機能）＝ `sync client` | 持たない。host の client | ネットワーク同期と保持を canonical broker 経由で行う。host はネットワークを開かない。単独時は network-only（§2.7） |
| 他の itonami dapp | 持たない。host の client | 自分の namespace だけを読む |
| OS 統合（File Provider / DocumentsProvider / WinFsp / FUSE） | 持たない。host の client | OS の API を IPC に翻訳するだけ（§7） |

`awai-disk-core` は Disk Format の実装で、形式の正本は `network-awai-apex/docs/awai-disk-format.md`。同 §1 の 4 層のうち 3（Disk Format）と 4（Disk System）を扱う。

**host の境界:** host は**外部ネットワークを一切開かない**。受け取るのは、`sync client` が持ってきた server receipt と検証役の `policy-satisfied` 記録で、host はその**検証だけ**を行う。ネットワーク同期と P6 の同意は `sync client` が canonical broker 経由で行う（§8）。host は E2EE の鍵と平文を保持しない（§9）。

**この分割の理由:** 1 volume 1 writer を OS のプロセス境界で保証するため（`awai-disk-format.md` §8）。writer が 1 つなら、fencing の epoch と WAL の鎖は 1 つのプロセスの中で閉じる。複数プロセスが同じ volume を開く設計にすると、`flock` の意味が「同一マシンの別プロセス」に広がり、network FS や VM をまたいだ時点で保証が消える。

### 1.2 2 番目のプロセスが volume を開こうとしたとき

| 場合 | 結果 |
|---|---|
| 2 つ目の Drive host の起動（同じ user・**同じ `volume-lineage-id`**） | endpoint 名に lineage を含める（§2.2〜§2.4）ので、単一インスタンス選出で負け、**client として 1 つ目に接続する**。volume は開かない |
| 別 build の host、または host 以外のプロセスが `awai-disk-core` を直接開く | `awai-disk-core` の open が `busy` で失敗する。**強制 open の経路は作らない** |
| 2 つ目の Drive host（同じ user・**違う lineage**） | **許可する。** endpoint 名が違うので別の host として起動し、自分の volume を開く（1 user が 2 つの volume を持つ場合） |
| 同じ volume を別マシンから開く | 対象外。共有は Awai FS の分散 CAS の層で行う（`awai-disk-format.md` §8） |

- `flock` はプロセスの終了で OS が解放する。したがって「lock が取れない」は「生きているプロセスが持っている」を意味する。**古い lock を消して奪う処理は書かない。**
- `awai-disk-format.md` §8 の v1 の保証は POSIX の `flock` で、network FS をまたぐ fencing ではない。host は FS probe（v1.0 §4、`awai-disk-format.md` §7）で network FS を拒否する。
- Windows には `flock` がない。同等の排他（`FILE_SHARE_NONE` の lock file か `LockFileEx` か）は **未決定**で、Kura の format 改訂（v1.0 §7 の 1）に合わせて決める。それまで Windows の host は「排他が取れたと主張しない」。

### 1.3 host 自身の link が独占できないとき

host は次の順で判断し、**どれにも当てはまらなければ writer にならない**。

1. endpoint（lineage を含む）の単一インスタンス選出に勝つ（§2）。
2. volume の `flock` を取る。取れなければ `busy`。
3. 両方の root slot を読み、回復（`awai-disk-format.md` §6）を通す。通らなければ **停止（`stopped`）**。
4. lease を CAS で取り、writer epoch を進める。CAS に負けたら `busy`。

- 2 で失敗した場合、host は**読取専用の observer としても開かない**。`busy` を client 全員に返し、volume を open しない。理由: `flock` を持たないプロセスが WAL を読むと、checkpoint の作成と競合する。
- 3 で失敗した場合（fork 検出、デバイス集合の不一致、WAL の鎖の切れ、root slot が成立しない）、host は **`stopped`** で起動する。`stopped` は**既定では読みも拒否**し、診断用の `inspect`（読み取り専用 open）だけを明示の操作で許す（改訂案 §14）。**`read-only`（読みを許す）と混ぜない。**
- grade C（v1.0 §4）の volume は **`read-only`**（読みを許す。書き込みだけを止める）。`stopped` とは別の状態で、`get-volume-status` の `state` で区別する（§4.6）。
- どの失敗でも **zero 埋め・古い世代への後退・別ソースへの切替をしない**（`awai-disk-format.md` D7）。

## 2. OS 別のプロセスライフサイクル

### 2.1 共通の性質

| 性質 | 設計 |
|---|---|
| UI を閉じた後 | host は動き続ける（Android は §2.5 の例外） |
| 自動起動 | 初回の volume 登録時に有効化を提案し、user が切れる。既定は「その volume を使った最後のログインで有効」 |
| 単一インスタンス | endpoint 名（ソケット / pipe / service）の登録が排他。負けたプロセスは client になる |
| crash / restart | OS の supervisor が再起動する。volume は open からやり直す（回復は `awai-disk-format.md` §6） |
| volume の open / close | open は host の起動時または最初の client 要求時。close は safe-eject か終了時 |
| safe-eject | `evict` とは別の操作。**dirty を無くす → FLUSH → lease を明示 release（`state=released`）→ clean を記す**、の順で行う（改訂案 §21.8）。**必須なのは等級 A / B だけ**で、等級 C は書き込みも dirty も無いので release を書かず expiry に任せる。volume を close してから「取り外してよい」と言う。**close に失敗したら「取り外してよい」と言わない** |
| open 中の upgrade | 旧 host は volume を close してから終了する。新 host は open からやり直す。**開いた volume の fd を新旧の間で渡さない**（版をまたぐ fd の互換を主張しない）。client は `host-restarting` を見て request-id を保ったまま再試行する |
| Yataverse 単独 | §2.7 |

**endpoint squat:** 同名の endpoint が既に居る場合、host はそれが自分の host か（§3.5 の host 認証）を確かめてから client になる。確かめられない endpoint には繋がない（偽 host に繋がない）。

### 2.2 macOS

- 形: **app に埋め込まない sidecar**を第一候補にする。`~/Library/LaunchAgents` の per-user LaunchAgent が sidecar を起動し、`KeepAlive` と `RunAtLoad` を設定する。Mac App Store の sandbox 配布は対象外（未確認）。
- endpoint: **App Group の Unix domain socket**（`group.network.awai.drive` の container 内、`host-<volume-lineage-id>.sock`、mode 0600）を data plane に、XPC（`MachServices` 名 `network.awai.drive.host.<volume-lineage-id>`）を control plane に使う。XPC は fd を渡せないため、bytes は socket の `SCM_RIGHTS` で渡す。
- 単一インスタンス: **lineage を含む** `MachServices` 名の登録が排他。登録に失敗した 2 つ目は client になる。socket は lock file（lineage を含む）を `flock` してから `unlink` → `bind` する（起動競合で他人の socket を消さない）。
- 自動起動: LaunchAgent の plist。macOS 13 以降の `SMAppService` を使うかは**未決定**（署名と notarization の条件で変わる）。
- 制約: LaunchAgent は user のログイン中だけ動く。ログイン前・画面ロック中の USB 挿入は扱わない。

### 2.3 Windows

- 形: per-user の sidecar（`awai-drive-host.exe`）。ログオン時の自動起動（`HKCU\...\Run` か Task Scheduler）で起動する。**Windows サービス（`LocalSystem`）にはしない** — ユーザーの volume と鍵に届かないため。
- endpoint: **named pipe** `\\.\pipe\awai-drive-host-<user SID>-<volume-lineage-id>`。control と data を同じ pipe の frame で運ぶ（§4.3）。
- 単一インスタンス: `Local\AwaiDriveHost-<user SID>-<volume-lineage-id>` の named mutex。負けたプロセスは client になる。
- UI を閉じた後: sidecar は独立プロセスなので動き続ける。app の Job object に `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` を付けない（付けると UI の終了で host が死ぬ）。
- volume の排他: **未決定**（§1.2）。`awai-disk-core` が Windows で何を排他に使うかは Kura の format 改訂に依存する。
- 制約: ドライブレターで volume を同定しない（v1.0 §3）。volume UUID で同定する。

### 2.4 Linux

- 形: **systemd user unit** `awai-drive-host.service`。`Restart=on-failure`、`KillMode=mixed`。socket activation を使うかは**未決定**。
- endpoint: `$XDG_RUNTIME_DIR/awai-drive/host-<volume-lineage-id>.sock`（tmpfs、directory 0700、socket 0600）。data は同じ socket の `SCM_RIGHTS`。
- 単一インスタンス: lineage を含む lock file の `flock` + socket の bind。
- 自動起動: `systemctl --user enable`。user の linger を有効にするかは**未決定**（ログアウト後も動かすかの trade-off）。
- 制約: `$XDG_RUNTIME_DIR` が無い環境（cron など）では起動しない。

### 2.5 Android

- 形: **bound service + 必要な間だけ foreground service**。**`android:exported="true"` + signature 権限** `network.awai.drive.permission.HOST_IPC`（§3.4）。`exported="false"` では別 app（Yataverse）から bind できないため、`false` にしない。UI を閉じると bound service は unbind される。
- volume と lineage: Android の component 名は静的なので、lineage を endpoint 名に含められない。`bind` の引数で lineage を渡し、**1 host は 1 volume を持つ**（複数 volume は v1 では未対応）。
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
| peer の証明 | service は **`exported="true"`**、signature 権限 `network.awai.drive.permission.HOST_IPC` を要求する。呼び出しごとに `Binder.getCallingUid()` を取り、許可した signing certificate の digest の集合と `PackageManager.checkSignatures` で照合する。許可集合は host の control record に固定する |
| 証明しないもの | 同じ署名の app 同士の区別（同一署名は互いに信頼する）、`sharedUserId` を使わない前提の外側、端末の root 化 |
| 失敗 | `SecurityException` を投げずに **型付きの `unauthorized` を返して接続を閉じる**（呼び出し元に内部情報を返さない）。監査記録を残す |

- bytes は `ParcelFileDescriptor`（pipe）で渡す。Binder の transaction に bytes を詰めない。
- AIDL の interface には `interfaceVersion` を持たせ、host は未知の版を `version-unsupported` で拒否する。

### 3.5 client 側から host を認証する

- client は、接続する前に**host を認証する**。方法は (a) code signature / package identity の確認（OS の API）、または (b) install 時に control record へ pin した host 公開鍵との challenge-response。
- **pin した鍵と違う host には繋がない。** endpoint が先に居るだけでは信頼しない（endpoint squat、§2.1）。
- host の鍵が変わった場合（再 install、upgrade）は、user の明示の操作で pin を更新する。**黙って更新しない。**
- 証明しないもの: host の image の完全性。(a) は署名の一致まで、(b) は鍵の possession までで、**host が改変されていないことは証明しない。**

### 3.6 認証のまとめ

| OS | 証明するもの | 証明しないもの | cross-client / product 分離 |
|---|---|---|---|
| macOS XPC | 署名 requirement を満たす code | image の完全性、user の意図 | **分離する**（署名 requirement の identifier まで） |
| macOS App Group socket | 同じ uid、token の一致 | product の同一性（単独では） | uid まで。product 分離は XPC の token と併用したときだけ |
| Windows pipe（MSIX） | user SID と package identity | image の完全性、同じ package 内の区別 | package identity まで分離する |
| Windows pipe（非 MSIX） | user SID | 同じ SID の別プロセス、image の完全性 | **uid / SID まで。暗号学的な分離ではない** |
| Linux socket | uid、pid + starttime（hint） | 同じ uid の別プロセス、image | **uid まで。暗号学的な分離ではない** |
| Android Binder | 署名 digest の集合、呼び出し UID | 同じ署名の app の区別、root 化 | 署名まで。**同じ署名の app 同士は区別しない** |

- **Linux と非 MSIX Windows では、同じ uid / SID の別プロセスを区別できない。** したがって **cross-client の namespace / product 分離は暗号学的でない**（同じ user のプロセスは互いに信頼する）。この前提で quota と pin の保護を設計し、分離の証明を主張しない。
- Android は署名単位でしか分離できない。同じ署名の app は互いに信頼する（`sharedUserId` は使わない）。

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
  op: string,                   // "put" | "pin" | "evict" | "get-commit-status" | "get-volume-status" | "inspect" | ...
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
- `namespace` を client が名乗る欄は持たない。host は `clientId` から namespace を決める（§6.1）。
- 制御メッセージの最大は **1 MiB**。超えたら `too-large` で拒否し、**部分実行しない**。
- bytes は制御メッセージに埋め込まない。`dataHandle`（fd / `ParcelFileDescriptor` / pipe 上の `data-open` の id）で渡す（§4.3）。

### 4.3 bytes の運び方

| transport | bytes の運び方 |
|---|---|
| macOS App Group socket | `SCM_RIGHTS` で fd を渡す。受信側は宣言された長さだけ読む |
| Windows named pipe | pipe 上の `data-open` / `data-chunk` / `data-end` frame。chunk の長さは frame header に持ち、**base64 にしない** |
| Linux socket | `SCM_RIGHTS` で fd を渡す |
| Android Binder | `ParcelFileDescriptor`（pipe） |

- `put` は `{ path, size, cid, sha256, dataHandle }` を送る。**bytes は seal 済みの暗号文 block** で、`cid` と `sha256` は**暗号文**を対象とする。host は平文も E2EE 鍵も持たないので、復号も再暗号化もしない。`size` と `sha256` を検証してから WAL に書く。宣言より多く送られたら `too-large` で中止する。
- `path` は**監査用の opaque label** に留める。host は `..` を解決しない（path の解決は WNFS の closure を持つ client が行う）。host は label の文字列を認可に使わない。
- namespace の認可は、`clientId` に結び付いた **namespace capability**（将来は W4 の write proof。`scalable-sync-design.md` §4.9）で行う。path や CID の自己申告では認可しない。
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
| `too-large` | 制御メッセージ、または宣言された bytes の上限超え | 要求を分割するか中止する |
| `busy` | volume が別の writer に握られている、または期限切れ | 待って再試行してよい（同じ requestId） |
| `volume-absent` | volume、または書込み先の device が無い（未接続、取り外し済み、`degraded-pack-absent`） | UI に「媒体が無い」を出す。書き込みを再試行しない |
| `no-space` | device の空き容量の下限（device floor）に当たった（改訂案 §4.7） | 退避か削除を user に求める。**host は証跡や未退避の data を消さない** |
| `read-only` | grade C、旧アプリの media downgrade など、読みを許す状態 | 読み取りを続ける。UI に理由を出す |
| `stopped` | 停止中（fork、回復の失敗、デバイス集合の不一致）。既定では読みも拒否 | 再試行しない。`inspect` の案内と理由を出す |
| `quota-exceeded` | client の quota（予約 + 実使用）を超える | 退避か削除を user に求める。**host が勝手に他人の領域を削らない** |
| `idempotency-conflict` | 同じ `requestId` で違う内容 | 再試行しない。新しい `requestId` を使う |
| `commit-unknown` | 結果を確定できない（host の crash、記録の期限切れ） | **盲目的に再送しない。** §5 の手順に従う |
| `internal-error` | 上記以外の host 内部の失敗 | 同じ `requestId` で 1 回だけ再試行してよい。繰り返すなら報告 |

- 型付きの拒否に対して、host は**部分的な成功を返さない**（`ok` と `error` の混在を作らない）。
- transport の失敗（接続断、pipe の消失）は IPC のエラーコードではない。client は「host が動いていない」として扱い、再接続して `get-commit-status` で確認する。

### 4.6 `get-volume-status` と状態

`get-volume-status` は `profile`、`grade`、`state`、`reason` を返す。`state` は次の 5 つ。

| state | 意味 | 許す操作 |
|---|---|---|
| `ok` | 通常 | すべて |
| `degraded-pack-absent` | Local-Meta で `optional` の pack device（USB）が見えない（改訂案 §4.4） | 読み（内蔵 cache / remote の CID 検証済み block のみ）、`get-volume-status`。書きは `volume-absent` で拒否 |
| `volume-absent` | Portable-USB で媒体が無い、または Local-Meta で volume が未接続 | `get-volume-status` だけ。読み書きは `volume-absent` |
| `read-only` | grade C、旧アプリの media downgrade など。読みは許す | 読み、`inspect`、`get-volume-status`。変更系は `read-only` |
| `stopped` | fork、回復の失敗、デバイス集合の不一致 | `get-volume-status` と、明示の `inspect` だけ。読み書きは `stopped` |

| 遷移 | 契機 |
|---|---|
| `ok` → `degraded-pack-absent` | pack device の抜去・不在（Local-Meta） |
| `ok` → `volume-absent` | safe-eject の完了、または媒体の不在（Portable-USB） |
| `ok` / `degraded-pack-absent` → `read-only` | grade が A から C に下がった（sync の失敗、read-only remount、再 probe の失敗） |
| 任意 → `stopped` | fork の検出、回復の失敗、デバイス集合の不一致 |
| `degraded-pack-absent` → `ok` | 同じ device UUID の媒体が戻り、回復が通った |
| `volume-absent` → `ok` | 媒体が戻り、open と回復が通った |
| `read-only` → `ok` | 再 probe が A に戻った（**自動で戻さない。再 probe の結果で判断する**） |
| `stopped` → `ok` | 人が原因を除いた後の再 open（**自動で戻さない**） |

- `profile` は `local-meta` / `portable-usb`、`grade` は `A` / `B` / `C`。
- `degraded-pack-absent` は「壊れた」ではなく「`optional` の role が無い」状態である（改訂案 §4.4）。`stopped` と混ぜない。
- `stopped` の `inspect` は**状態を変えない**。診断の読み取りだけで、書込みも回復もしない。

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
- **限界:** 現行の Disk Format は WAL frame に `requestId` を持たない。改訂案 §4.3 の frame は `writer epoch` と `session id` を足すが、`requestId` は無い。したがって host の crash 時に「WAL に書いたが記録を書く前」だった要求は `unknown` になる。これを `committed` / `failed` に縮めるには、Kura 側の別 issue（または改訂案 §11 の未決定）で WAL frame に `requestId`（または host の記録の digest）を載せる必要がある。**それまでは `unknown` を正しい答えとして扱う。**

### 5.5 前後関係

- `put` の結果を返す前に、host は記録を永続化する（write-ahead）。ack を失っても `get-commit-status` が答えられる。
- `pin` / `evict` も同じ。`evict` は「対象の block を消した」ではなく「退避の判断を確定した」を記録する。実際の回収は catalog と root の更新後（§8 の順序）。

## 6. client の namespace と quota

### 6.1 clientId と namespace

- `clientId` は「認証した peer の product identity + 署名 identity の digest + その install の instance id」から host が作る。**client は名乗らない。**
- namespace は、host が `clientId` に割り当てる capability の範囲で、owner の WNFS 名前空間の `clients/<clientId>` に対応する。**path の解決は client が行い、host は opaque label と CID だけを扱う**（§4.3）。host は E2EE の鍵を持たないので、directory の中身を復号して path を解決しない。
- **namespace closure の置き場:** Local-Meta は**内蔵 meta に固定**（USB 不在でも一覧と path 解決ができる）。Portable-USB は**container 内に固定**する。改訂案 §11 の未決定を、Drive の側でこのように決める。
- `put` の `path` は **opaque label** で、host は `..` を解決しない（§4.3）。label と CID は namespace capability の中でだけ意味を持つ。他の client の namespace を指す参照と、closure の外の CID の読み取りは `unauthorized`。
- 読み取り専用の client（`view: read-only`）は、変更系の op すべてで `read-only`。

### 6.2 quota

- client ごとに `reserve` / `release` を持つ。quota は「生きた予約の合計 + 実使用」で数える。
- `put` は書く前に予約を取る。予約が取れなければ `quota-exceeded` で、**bytes を 1 つも書かない**（`awai-disk-format.md` §7.4 の予約台帳と同じ規則）。
- 確定時に実使用を記録し、予約との差を返す。予約の失効（client の切断、期限）は**実使用を減らさない**。
- device の空き容量の下限（device floor）に当たったら **`no-space`**。client の quota 超過（`quota-exceeded`）と区別する（改訂案 §4.7）。
- **`pin` は保持のフラグで、bytes の quota を二重に消費しない。** 実使用は `put` の確定で 1 回だけ数え、pin は保護の状態と監査に記録する（実使用からの振替会計はしない）。
- quota の値は **未決定**（P0 と、Yataverse の保持設計の予約に合わせる）。host は値を持たずに「無制限」を既定にしない。

### 6.3 pin の所有と cross-client の保護

- `pin` は `(target, owner: clientId)` の record を持つ。
- client B の `evict` が client A の pin を指したら、**`unauthorized` で拒否する**。判断は「target のすべての pin の owner が呼び出し元と一致するか」。
- 拒否と許可のどちらも、**append-only の監査記録**に残す: `requestId`、`clientId`、op、対象、判断、理由、時刻。**秘密値（鍵、capability、平文）を記録しない。**
- 監査記録は host の control record に置き、client からは読めない（host の UI と、owner の明示的な操作だけが読む）。
- 同じ target を複数の client が pin してよい。すべての pin が外れるまで evict できない。`pin` は**保持のフラグ**で、bytes の quota を追加で消費しない（二重計上しない。§6.2）。pin の作成・削除は監査記録に残す。

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
- host は外部へ通信しない。同期は `sync client`（Yataverse または Drive の同期機能）が canonical broker 経由で行う。host は `sync client` から receipt / `policy-satisfied` を受け取り、**検証だけ**を行う（§1.1）。host が bytes を渡すのは local の fd だけ。
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
| grade C（読みは許す） | `read-only`。書き込みを停止 | 理由（grade C）と、読み取りを続けられること |
| fork / 回復失敗 / デバイス集合の不一致 | **`stopped`**。既定では読みも拒否し、`inspect` だけ許す。epoch を進めない | 「停止している」。理由と `inspect` の案内。**自動で片方を選ばない** |
| オンラインの巻き戻し（古い `base-epoch`） | **設計中（子 issue 15）。それまでは検出を主張しない。** host は黙って rebase しない | 「未実装」。検出したかのような表示をしない |
| オフライン・媒体全体の巻き戻し | **検出できない** | UI に「検出できない」と明記する（`scalable-sync-design.md` §4.8） |
| device の空き容量の下限 | `no-space`。何も書かない | 空きを作る案内。**未退避の data を消さない** |
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
10. **読み取り専用 client が `pin` できるか**。現案は不可（pin は保持のフラグで quota を追加で消費しないが、監査を伴う書き込み）。
11. **host の公開鍵 pin（§3.5）の初期配布と更新**。install 時にどう pin するか、鍵の変更をどう user に確認させるか。
12. **サーバ commit の単調 epoch と、古い `base-epoch` の拒否**（子 issue 15）。設計が入るまで、オンライン巻き戻し検出を主張しない（§9）。
13. **Android の複数 volume**。v1 は 1 host 1 volume（§2.5）。
14. **`stopped` からの `inspect` の許し方**（誰が、どの操作で）。状態を変えないことだけ決めている（§4.6）。
15. **`no-space` の下限の値**（device ごと。改訂案 §4.7 は仮置きで、測定の前に gate にしない）。
16. **`degraded-pack-absent` の書き込み拒否の code**。現案は `volume-absent`（§4.6）。
