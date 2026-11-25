# Awai Drive — 大容量・多数ファイルでスケールする同期の設計（WNFS 準拠版）

- 状態: **提案（設計のみ）**。実装も、WNFS 準拠・性能・容量・durability の主張も含まない。
- 追跡: [AWAI-3357](https://linear.app/kotobalabs/issue/AWAI-3357)（初版、PR #248）、[AWAI-3372](https://linear.app/kotobalabs/issue/AWAI-3372)（この改訂）。
- 対象: `app-drive/tauri`（Tauri 3.0.0-alpha.6）。Android と macOS で共通の domain/policy（CLJC/CLJS）を使い、
  platform ごとの Rust/Kotlin/Swift は bounded な adapter だけに限る。
- 初版からの変更: 初版は独自の chunk / pack / paged manifest 形式を提案していた。owner 判断
  （2026-11-16、[AWAI-2961](https://linear.app/kotobalabs/issue/AWAI-2961)）は「WNFS に準拠し、`.cljc` で実装する」であり、
  apex に実装 `otehon/wnfs-cljc` がある。この版は、**独自形式を作らず、WNFS の仕様と `wnfs-cljc` の上に組み直した**。
- 範囲外（別 issue）: 共有・失効、独立 node による耐久性、owner 復旧、Native selfhost、WNFS の全体認定。

## 0. 動機

2026-11-18、agent の成果物（`.agent-output`、VM image、ログ）でこの Mac のディスクが 214GB から 24GB まで減った。
成果物を Drive に長期保管し、同期を確認できたものだけを端末から退避できれば、増加を構造的に抑えられる。
しかし現在の Drive は 8 MiB を超えるものを同期も退避もできない（`docs/encrypted-sync.md`）。

## 1. 現状の位置づけ（コードを正とした確認）

「Drive / Vol は WNFS と IPLD がベース」は、部分的にしか当てはまらない。

| 部品 | 現状 | 根拠 |
|---|---|---|
| IPLD（CIDv1、DAG-CBOR、CARv1） | **使っている**。Drive の pack は CARv1、各 block は CIDv1（raw, sha2-256） | [暗号化同期](encrypted-sync.md) |
| Drive の live 経路 | owner-store（`object-v2` の raw frame、Kotoba hybrid envelope、暗号化 `private-manifest-v1`）。**WNFS ではない** | 同上、`AWAI-173-private-sharing-design.md`（「WNFS wire 互換は主張しない」） |
| AwaiVol（legacy） | 独自の forest / pack codec（part ≤ 896 KiB、container ≤ 1 MiB など）。**WNFS PrivateForest とは別の仕組み** | `otehon/legacy-awaivol/README.md`、`docs/otehon/drive-storage.md` |
| WNFS | apex の `otehon/wnfs-cljc` に CLJC 実装がある（rs-wnfs 0.3.0、node 1.0.0 の profile）。**まだ owner-store / AwaiVol の既定の読み書き経路ではない**。live 切替は copy-only 移行として別 issue（AWAI-2798/2799） | `otehon/wnfs-cljc/README.md`、AWAI-2961 |

したがって、この設計は「**Drive の同期形式を、WNFS（wnfs-cljc）に載せ替えるときに、規模の上限をどう外すか**」を扱う。

## 2. 仕様・実装・未実装の対応

仕様は `wnfs-wg/spec` の revision 5229f36。実装 profile は rs-wnfs 0.3.0（revision 80d5428）で、node `1.0.0`。
仕様の node は `0.2.0` で、nonce の長さ、header の鍵、temporal key の文脈、hash-to-prime の counter、skip-ratchet の carry に
差がある（記録と修正案: [wnfs-wg/spec#81](https://github.com/wnfs-wg/spec/pull/81)）。**この設計は実装 profile に従い、
仕様の版へ黙って切り替えない。**

| 仕様が要求すること | wnfs-cljc の実装 | 未実装・要確認 |
|---|---|---|
| PrivateForest ＝ degree 16 の HAMT。label は 2048-bit の `NameAccumulator`、値は raw CID の sorted 集合 | あり（79 label の上流 corpus と全 node の bytes/CID が一致） | 全体を読み込まない **lazy な HAMT 走査**（現行 loader は node 数を 10000 で拘束） |
| 暗号文 block ≤ 256 KiB（平文 ≤ 262,104、IV 24 + tag 16） | あり（XChaCha20-Poly1305、AES-256-KWP） | — |
| file ＝ header + 外部 block（`blockCount`: Uint64、`blockSize` ≤ 262,104）。各 block の label は `baseName` に index から導いた素数を accumulate | あり。ただし **1 file ≤ 4096 block** | 上限の引き上げ、streaming の利用の徹底 |
| 版管理 ＝ skip ratchet、`previous` の暗号化 backlink、revision ごとの label | ratchet、key 導出は実装済み | **latest-revision の探索、履歴の検証、in-place 更新** |
| merge ＝ 可換・冪等な join-semilattice（第三者が平文なしで blind merge できる） | HAMT 側の考え方はある | forest の merge の実装・資格 |
| 書き込み権限 ＝ RSA accumulator の membership / batch proof を、平文を読めない第三者が検証 | accumulator の演算はある | **membership / multi-batch proof、writer 認可、fencing** |
| 転送は CAR（bitswap ではなく 1 往復で新しい block 群を送る） | CARv1 の pack（≤ 10000 block / 256 MiB、BLAKE3/SHA-256 の CID を保持） | **BLAKE3 対応の CAR index**（既存の CARv2 index は SHA-256 のみで流用しない） |
| 公開側は UnixFS | — | 公開 import/export は別の認可境界 |

root の権限（capability）は WNFS の bytes の**外側**で、既存の Kotoba hybrid owner envelope と sealed identity custody が包む
（`custody.cljc`）。新しい identity、unlock の保管、consent broker は作らない。

## 3. 上限の出どころ

仕様自体には file サイズや entry 数の上限はない（`blockCount` は Uint64、HAMT に上限はない）。
**上限は実装の admission bound と、Drive の現行経路にある。** 数値はコードと文書から読んだもので、
資源上限であり性能・容量の保証ではない。

| 上限 | 値 | 場所 | 守っているもの |
|---|---|---|---|
| Drive の snapshot 全体 | 8 MiB / 1000 entry | owner-store 経路（`encrypted.cljc`） | JS heap（snapshot 全体を 1 つの `Uint8Array` に載せる） |
| Drive の pack（サーバ側も） | 12 MiB | `PACK_LIMIT`、Yataverse `DRIVE_MAX_PACK_BYTES` | 1 回の PUT/GET の大きさ |
| 1 file | **4096 外部 block ≈ 1 GiB − 160 KiB**（4096 × 262,104 = 1,073,577,984 byte） | wnfs-cljc `private_node` / `private_tree` | forest の件数、メモリ |
| 取り込む path 数 | 1000 | wnfs-cljc `private_tree` | tree ingestion の入力 |
| directory の entry 数 | 1000 | wnfs-cljc `private_node`、Drive `desktop.rs` | directory node は 1 block に収まる |
| forest | node / record 各 10000 | wnfs-cljc `wire` | 全体をメモリへ読む loader |
| CAR | 10000 block / **256 MiB** | wnfs-cljc `pack` | 実際は byte 上限が先に効く（256 MiB ≈ 約 1000 block） |
| `read-file`（便利 API） | 64 MiB | wnfs-cljc | 全体をメモリへ載せる API。大きな入力には使わない |
| 深さ | ingest 64 / 走査 128 | wnfs-cljc | 再帰 |

問題の核は 3 つ。(1) Drive の live 経路が WNFS ではなく 8 MiB の単一 pack であること、
(2) wnfs-cljc が全体を読み込む前提の bound を持つこと、(3) 仕様自体の制約（§4.3、§4.4）。

## 4. 設計

### 4.1 形式

- 同期の単位の形式は **WNFS の private forest そのもの**（実装 profile）。独自の chunk、manifest、暗号の形式は作らない。
- 既存の Kotoba hybrid envelope は、(a) root capability の包み（`custody.cljc`）と、(b) 既存の v1 snapshot の読み取りにだけ使う。
- v1 の snapshot（owner-store、単一 pack ≤ 12 MiB）は**そのまま復元できる**。v1 を v2 として、v2 を v1 として読まない。
  古い root への黙った fallback はしない。移行は copy-only（既存のデータと鍵は変えない）。

### 4.2 file（大きなファイル）

- file は header + **外部 block の列**。block は平文 ≤ 262,104 byte、暗号文 262,144 byte、label は block ごとの `NameAccumulator`。
  block の label は index から決まるので、**任意の block を単独で取得・検証・再送できる**（range 読み、再開、部分 hydration）。
- 4096 block の上限は仕様由来ではなく実装の admission bound。上げるには、blockCount、forest の件数、CAR の分割に対する
  bound を、streaming の前提で見直す（§8 の W1）。**GB 級でも全体を載せない**: `stream-file` と block source を使い、
  `read-file` は使わない。
- 64 GiB 級を目標にすると、block は約 26 万個、forest の record も約 26 万になる。**per-block の `NameAccumulator`
  （2048-bit の RSA 演算と hash-to-prime）のコストが律速になり得る**ので、測定が先（§7）。

### 4.3 directory（多数のファイル）

- 仕様の private directory は、`entries` の全体を **1 つの node（1 block）** に持つ。**sharded directory は仕様にない。**
  実装の 1000 entry は、この構造の現れでもある。
- したがって「1 directory に数十万 file」（例: 1 つの `.agent-output` に 30 万 file）は、**そのままでは WNFS で表せない**。
- 扱い（推奨）: 退避の単位を「file 単位」ではなく「**directory subtree を 1 つの archive（tar 型の stream）にした 1 file**」にする。
  大量の小さな file は 1 つの大きな外部 block 列になり、仕様の範囲で表せる。利用者が個別に開く必要のある file は、通常の file として別に置く。
- 別案（owner 決定が必要）: directory の sharding（UnixFS の HAMT-sharded directory 型）を Awai の拡張として仕様に足す。
  相互運用性を失うので、既定にはしない。

### 4.4 更新（版）

- 仕様は「内容が変わるたびに外部 content の `key` を**ランダムに作り直す**」と定めている（§3.1.4.1）。
  よって、**大きな file を一部だけ変えると、その file の全 block が新しい key で再暗号化・再送になる**。block 単位の差分更新はできない。
- 影響: 不変の成果物（ログ、ビルド出力、録画）には問題ない。**追記・部分更新される大きな file（VM image など）には向かない。**
  扱い（推奨）: 変更される大きな file は、アプリ側で**固定長の segment に分けて、別々の WNFS file**（それぞれ独立の版）にする。
  これは WNFS の外側の規約であり、P5 以降で扱う。
- 版は skip ratchet と暗号化 `previous` で表す。**latest-revision の探索と in-place 更新は wnfs-cljc で未実装**（§2）。
  それまでの Drive は、「新しい forest root を作って、新しい block だけを送る」full-snapshot 型で動かす。
  同じ内容の block は CID が同じなので、再送は冪等（サーバが既に持っていれば省略できる）。
- forest の merge は可換・冪等なので、**中断した upload の再開は「足りない block をもう一度送る」だけで済む**。

### 4.5 転送（pack）

- 送る単位は **CARv1 の pack**（`pack/write-pack!`、`open-pack`）。pack は暗号文 block の束。block は pack をまたがない。
- 大きさ: 目標 **32 MiB**（約 128 block、提案値）、上限は wnfs-cljc の **256 MiB**。サーバの現状は 12 MiB で、引き上げは別（§4.9）。
- pack の ID は従来どおり pack 全体の SHA-256（`/awai-drive/v1/packs/<64hex>`）。既存の路線と受領証をそのまま使う。
- pack 内の block を後から単独で取得するには index が要る。既存の CARv2 index は SHA-256 のみで、wnfs-cljc は BLAKE3 の ID を保持する。
  **BLAKE3 を扱える index は未実装**で、「既存の index を改変して BLAKE3 を読み替える」ことはしない（W3）。

### 4.6 client のパイプライン

```
source file ─(bounded read)→ block source ─→ seal(XChaCha20-Poly1305) ─→ label(accumulate) ─→ CAR writer ─→ PUT ─→ readback
```

- すべて backpressure 付き。上流は下流が空くまで読まない。最大 buffer の総和がメモリの上限（§7 で測る）。
- 現在の IPC は 65531 byte ずつ base64 の JSON で往復する。GB 級では律速になるので、Tauri の raw byte IPC（Android の IPC byte body を
  受け付ける変更 #247 と同種）に 1 MiB 単位の読み書きを載せる。上限は platform adapter の bounded な操作として宣言する。
- source の変化は block の前後の size / mtime / 内容で検出し、変わったら**その版を破棄**する。未完了の root は公開しない。
- 重い処理（seal、hash、accumulate）は Worker で行う。unlock の秘密はメモリ内のみで、使ったら 0 で埋める。

### 4.7 同期の状態機械（snapshot 単位）

```
planned → sealing → uploading ─┬→ receipt-verified → readback-verified → committed
                               └→ failed-retryable / failed-refused
```

- 状態は device-protected な control record に、**pack ごと**に永続化する（既存の root / pack / origin と同じ置き場所）。
- `committed`（新しい forest root を control record に書く）は、**全 pack が readback-verified** のときだけ。部分的な root は公開しない。
- readback は pack 全体の GET と SHA-256 の一致。受領証（AWAI-3314、ビルドが公開鍵集合を固定しているとき必須）は検証するが、
  **retention や独立複製の証拠ではない**。sampled readback で退避を許す案は採らない。

### 4.8 退避と hydration（block 単位・namespace closure）

**namespace closure は固定である。** 現在の root から到達する WNFS の directory node と forest の HAMT node は、
すべて保護域に属し、退避しない。退避できるのは **file content block だけ**である。directory の entry は 1 つの node（1 block）に
収まる（§4.3）ので、directory node を退避すると path の解決そのものができなくなる。forest の node を退避すると label の探索が
できなくなる。

- 「退避するのは平文の file」という以前の書き方を捨てる。退避の単位は file ではなく block で、保護の単位は tree の node である。
- `packs/` の一部の block だけを退避することはできない（Pack は不変で、indexless CARv2 の 1 ファイル）。部分退避が要る場合は
  **repack / compaction** を行う:
  1. 生きている block から新しい Pack を作る（§4.5 の形式）。
  2. 全 block の CID と Pack 全体の checksum を検証する。
  3. catalog と root を新しい Pack を指すように更新する（WAL の `seal-pack`、`awai-disk-format.md` §5）。
  4. 古い Pack を参照する root が両方の slot から消えた後でだけ、古い Pack を回収する。
- 退避の前に、local の size / mtime / 内容を再確認し、新規・変更・中断中のものがあれば拒否する。
  **同期していないデータは決して消さない。削除と退避を区別する。** water mark と空き reserve は設定値で、SLO にしない（AWAI-2856）。

#### 解禁の段階

`app-itonami-yataverse/docs/retention-design.md` の等級と、ADR 0041 D2（検証役は provider・入口と別の主体・別の process・
別の鍵で動かす）に合わせる。段階は飛ばさない。

| 段階 | 解禁条件 | 退避できる対象 | 参照 |
|---|---|---|---|
| **Stage 0（現行）** | WNFS の head が存在する間は拒否。現行の `op_evict` は流用しない | なし | `encrypted-sync.md` の bounded snapshot |
| **Stage 1** | W1（bound の引き上げ）と W3（BLAKE3 の CAR index）の後 | `cache/` の**再取得可能な file content block** のみ。LRU | §4.5、§8 |
| **Stage 2** | 検証役と lease の連携の後 | `packs/` の file content block。ただし右の条件をすべて満たすときだけ | `retention-design.md` §3.2、§4、§6、§8 |
| **Stage 3** | Stage 2 の運用実績と、P6 の bounded batch の後 | 検証済み object の無人 LRU のみ。P6 の上限つき承認を前提 | §4.9、§8 |

Stage 2 の条件（1 つでも欠けたら退避しない）:

- 検証役の `policy-satisfied` が **R2 以上**である（R2 = `operator-archive` 1 と `provider-node` 1 以上。R3 = 独立した
  障害ドメインの `provider-node` 3 以上。`retention-design.md` §3.2）。
- その記録が**判断時点で有効**である。`observedAt` / `validUntil` を基準にし、`validUntil` を過ぎた記録は「不明」として拒否する。
  `observedAt` が未来過ぎる記録（読む側の時刻より 5 分を超えて先）も拒否する（同 §6）。
- 退避する block がすべて lease の **inventory** に入っている（同 §4）。
- 保持コピーの Object Lock を **version id** で確認する。名前で見ない（同じ名前に lock なしで書き直すと、名前の参照は
  lock のない version を返す。同 §16 の S1-2a）。mode は `COMPLIANCE`、期限は lease の `retentionUntil` 以上。
- **`storeClass 3` を独立した障害ドメインに数えない**（同 §3、ADR 0041 D6 の改定）。R2 の「provider 1 以上」を
  `storeClass 3` で置き換えない。
- **`retentionUntil=0` の受領証では解禁しない。** 期限のない受領証は保持の約束ではない。
- Stage 3 では **G2（2 障害ドメイン）と R3（3 障害ドメイン）を区別する**。G2 は storage architecture の受入の単位で、
  R3 は保持の等級である。無人 LRU は「検証済み」の object に限り、P6 の bounded batch の承認が無い運転では行わない。

ADR 0041 D2 により、判定は検証役が行い、provider・入口・その鍵と分離する。client は判定をやり直さず、署名と期限を検証して使う。

#### 保護する状態

| 状態 | 意味 | 退避 |
|---|---|---|
| `local-only` / `dirty` / `syncing` | 未同期・変更あり・同期中 | 不可 |
| `clean` | 全 block が readback-verified で、local の内容も一致 | Stage 1〜3 の条件を満たす場合のみ可 |
| `pinned` / `open` | 利用者が禁止 / 開いている | 不可 |
| `remote-only` / `fetching` | 平文が local にない / 取得中 | — |
| `error` | 検証失敗・欠損 | 不可（人が見る） |

- pin は client ごとに所有する。**client A の pin を client B は evict できない**（`drive-host-ipc.md` §6.3）。同じ block を
  複数の client が pin してよい。すべての pin が外れるまで退避しない。
- `clean` は「退避してよい」ではない。段階と、上の条件の両方が要る。

#### hydration

- 読むときは path を解決して（snapshot key）、必要な block の label を導き、その block だけを取得する。取った block は
  CID と AEAD で検証する。
- namespace closure の node は常に local にある（退避しない）。したがって path の解決に network は要らない。要るのは
  content block の取得だけである。
- 全体の復元は、各 block を検証してから staging に書き、**すべて検証できてから**公開する。既存のファイルは上書きしない。

#### 巻き戻し

- **オンライン:** サーバは lease と commit の**単調な epoch** を持つ。client の commit が古い `base-epoch` を持つ場合、
  サーバは**型付きで拒否する**。黙って rebase しない（`retention-design.md` §10 の冪等性、`drive-host-ipc.md` §9）。
- **同一端末:** 端末側の high-water（`volume-lineage-id → 最大 epoch / session id`）で検出する。
- **オフライン・媒体全体の別端末への巻き戻しは検出できない。** これは仕様の限界であり、**UI の文言にも「検出できない」と
  書く**。検出できるかのような表示をしない。

### 4.9 外部通信、同意、サーバの認可

- 外部通信は既存の canonical broker だけを通す。native の HTTP 抜け道、app origin の API、redirect、silent fallback は作らない。
  操作ごとの同意で、endpoint・method/path・data category を示す。拒否すれば request は出さない。
- **pack が多いと、操作ごとの同意が成り立たない**: 1 GiB を 32 MiB で送ると PUT と readback の GET で約 64 回。
  **案 B（broker に bounded batch の操作を足し、「この endpoint へ最大 N 個・合計最大 B byte」を 1 回で承認する）は、
  owner 決定（2026-11-25、AWAI-4118 v1.0 のオーナー決定 2）で採用する（P6）。** shared runtime の変更が前提で、
  変更が入るまで製品側は B を使えない。案 A（pack を 256 MiB 級に上げて回数を減らす）は契約を変えずに併用するが、
  64 GiB には足りない。製品側で同意のロジックを複製・拡張しない。P6 が入るまで、無人の大量退避は主張しない。
- **P6 の意味:** 承認 1 回が endpoint・最大要求数 N・最大合計 bytes B を名指す。拒否なら request は **0 件**。
  承認ごとに append-only の監査 record を書く。grant には期限があり、期限が来たら未使用分は失効する（値の案は
  5 分、hard cap 10 分。`bounded-batch-consent-proposal.md`）。N / B は**実際に送った要求**（再試行と readback を
  含む）で消費する。使い切ったら追加の承認が要る。revoke できる。**製品は独自の同意を作らず、grant を超える再試行を
  しない。** ローカルの USB I/O は同意の対象外（ネットワークを使わない）。実装は shared runtime が持ち、製品は消費する
  だけである（既定値の案は [bounded-batch-consent-proposal.md](bounded-batch-consent-proposal.md)）。
- 現状の Yataverse の pack protocol は**認証なし**（上限内の opaque な pack を誰でも保存できる）。pack の上限を上げる前に、
  書き込みの認可と quota が要る。**WNFS はこの課題の答えを持っている**: 書き込み権限を RSA accumulator の batch proof で示し、
  サーバは平文を読まずに検証する（仕様 §4.2〜4.4）。ただし wnfs-cljc は proof を**未実装**。
  実装までの暫定は、受領証設計 §8 の予約（`reserve`）と quota。**認可なしで上限だけを上げない。**

## 5. macOS

- macOS の Tauri build は AWAI-3256 で app 専用 directory の adapter ができている。次の 2 段に分ける。
  1. **選んだ folder の取り込み**: file picker と security-scoped bookmark で、利用者が選んだ folder を **read-only** に同期する。
     元のデータは変更せず、退避は取り込んだ複製の方にだけ行う。§4.3 により、大きな tree は archive 化した file として扱う。
  2. **File Provider**（AWAI-2857）: Finder に統合する最終形。Apple の materialized cache と二重にキャッシュしない。
     署名・entitlement が未確定なら blocked として記録する。
- 「Mac の `~/github/wt` を Drive に退避して空き容量を確保する」運用は、上の 1 と §4.9 の案 B（または運用上の許容）が揃った後に成立する。
  それまでのディスク枯渇の再発防止は、quota 付き APFS volume など、Drive に依存しない手段で別に行う。

## 6. head・鍵・復旧（変えないこと）

- 信頼済み root は引き続き、端末の control record にある。サーバ側の latest-head と fencing は別の gate（主張しない）。
- 鍵は現行のまま（端末ローカルの hybrid recipient。Passkey や Awai account の enrollment ではない）。
  **uninstall / clear-data / 鍵の喪失で remote の snapshot が読めなくなり得る。** GB 級を退避する前に、
  recovery の導線か、明示的な警告と承諾を必須にする。
- 初期化済みの namespace で control record がなく鍵の alias だけがあるときは、新しい identity を作らず拒否する。

## 7. 測定が必要なもの（推測で決めない。AWAI-3369）

| 測るもの | 理由 |
|---|---|
| **block あたりの `NameAccumulator`（accumulate + hash-to-prime）のコスト**（browser の BigInt / pinned provider、Android と macOS） | §4.2。64 GiB 級（約 26 万 block）の可否を決める |
| forest（HAMT）の件数に対する構築・走査・lazy load の時間とメモリ | §3 の bound の引き上げの根拠 |
| WebView ごとの `fetch` の request streaming の可否（Android API 35、macOS の WKWebView） | §4.5。既定は Blob / `Uint8Array` の PUT |
| 1 MiB 単位の raw IPC と、現行の 65531 byte × base64 の往復 throughput | §4.6 |
| seal / hash の throughput と peak RSS（pack 32 / 256 MiB、パイプライン深さ） | メモリの上限の見積り |
| 1 GiB / 10 GiB の同期・復元の所要時間と readback の費用（隔離した store） | 製品の SLO にしない |
| pack サイズと、通信断を入れたときの再送コスト | pack サイズの選定 |
| archive 化した tree（§4.3）の作成・展開の費用 | 大量の小さな file の扱い |

測定値は、設定・単位・source・coverage と一緒に Linear に記録する。測れなかった値は `unknown`。

## 8. 段階

各段階は別の issue と PR。**W は apex（`otehon/wnfs-cljc`）側、D は `network-awai/app-drive` 側。**

| 段階 | 場所 | 内容 | 検証（受入の形） |
|---|---|---|---|
| **P0** | 両方 | 測定（§7） | 測定値と、できなかった測定の一覧 |
| **W1** | apex | 1 file / forest / 取り込みの bound を、streaming・lazy な前提で引き上げる（blockCount、HAMT の lazy 走査、loader） | 1 GiB 超の file の往復一致、RSS の上限、欠損・改変・短読の拒否。上流 corpus が引き続き一致 |
| **W2** | apex | in-place 更新と latest-revision の探索、履歴の検証 | 上流の Rust との相互読み出し、拒否 corpus |
| **W3** | apex | BLAKE3 対応の CAR index（range 取得） | block 単独の取得、不正な range の拒否 |
| **D1** | app-drive | Drive の同期形式を wnfs-cljc に載せ替え（v1 は読み取り専用で残す。copy-only 移行） | v1 の復元が変わらない、v2 の往復一致、負の対照 |
| **D2** | app-drive | 再開できる upload と commit（§4.7）、計画の表示 | 通信断・電源断・app 終了からの再開、部分 root が公開されない |
| **D3（Stage 1）** | app-drive | block 単位の退避と hydration、namespace closure、repack / compaction（§4.8） | `cache/` の file content block だけが消え、directory node と forest node は残る。repack 後の CID が一致する。部分退避を拒否する |
| **Stage 2** | app-drive + shared | 検証役と lease の連携による `packs/` の退避（§4.8） | R2 以上の `policy-satisfied` の `validUntil` 内だけを解禁する。期限切れ・`retentionUntil=0`・inventory 外・Object Lock の不一致・`storeClass 3` だけの R2 を拒否する負の対照 |
| **Stage 3** | app-drive + shared | 検証済み object の無人 LRU（G2 と R3 を区別、P6 の承認前提。§4.8） | G2 と R3 の条件を混ぜない。承認の無い運転で退避しない。承認の失効・消費で止まる |
| **D4** | app-drive | macOS: 選択 folder の取り込み → File Provider | Mac の実機。元データを変更しない |
| **P6** | shared | broker の bounded batch の同意（§4.9 案 B）。**owner 決定済み（2026-11-25）** | shared runtime の変更。製品は消費のみ。grant の期限・N / B の消費（再試行と readback を含む）・revoke を試験する |
| **W4** | apex | 書き込み認可（membership / batch proof、writer 認可、fencing） | サーバが平文なしで検証、拒否 corpus |
| **P7** | — | 共有・失効、独立 node の複製、recovery | 別の gate（この設計の範囲外） |

退避の段階の順序: Stage 1 は D3 の後、Stage 2 は検証役と lease の連携の後、Stage 3 は Stage 2 の運用実績と P6 の後である。
順序を飛ばさない（§4.8）。

## 9. 失敗と攻撃の見方（要点）

- **block の入れ替え・欠落**: label と CID と AEAD で検出する。**サーバが壊す・返さない**: CID / SHA-256 で検出し、`error` にして退避しない。
- **pack を途中まで上げて止まる**: root を公開しない。残った pack は未参照の opaque data（サーバ側の GC は別の gate）。
- **source が同期中に変わる**: 検出して版を破棄する。**メモリ・disk の枯渇**: backpressure と staging の admission、reserve を割らない。
- **誤削除**: 退避は `clean` の file だけ。元の folder の削除は行わない。
- **サーバの乱用（認証なし）**: §4.9。認可と quota の前に上限を上げない。
- **仕様との差**: 実装 profile（node 1.0.0）と仕様（0.2.0）の差は記録した上で、profile に従う。版の切り替えは owner 決定。

## 10. 未決定（owner 決定・調査が必要）

1. **bounded batch の同意**（§4.9 案 B）: owner 決定（2026-11-25）で採用。**未決定なのは shared runtime の実装と、
   既定の N / B・grant の期限・hard cap の値**である。実装が入るまで、無人の大量退避は成立しない。
2. **書き込みの認可**: wnfs-cljc の proof の実装（W4）まで、認証なしの現状のまま pack の上限を上げない。暫定の予約・quota をどうするか。
3. **recovery key**: GB 級を退避する前提として、導線か明示的な承諾を必須にするか。
4. **大きな directory**（§4.3）: archive 化を既定にするか、Awai 拡張の sharding を認めるか。
5. **変更される大きな file**（§4.4）: アプリ側の segment 規約を採るか、対象外にするか。
6. **block の大きさ**: Awai の既存 raw frame（65531 byte）と WNFS の 262,104 byte の関係。WNFS 準拠のため後者を採るが、移行時の整合を確認する。
7. pack / ページ / bound の具体的な値は、P0 の測定後に確定する。

## 11. 初版（PR #248）からの変更

| 初版 | 改訂 |
|---|---|
| 4 MiB の chunk、独自の封、AAD の規則 | WNFS の外部 block（≤ 262,104）。独自の暗号規則を作らない |
| 暗号化 paged manifest（独自の木） | WNFS の private forest（HAMT）と private node |
| 内容の MAC による chunk 単位の差分 | **不可**（仕様は内容が変わると key を作り直す）。§4.4 の扱いに変更 |
| pack 上限 128 MiB | wnfs-cljc の 256 MiB に合わせる（サーバの引き上げは認可が先） |
| サーバの認可は受領証設計の予約と quota | 長期は WNFS の書き込み proof（W4）。暫定は予約と quota |
| 段階 P0〜P7 | W（apex）と D（awai-os）に分けて切り直し |
