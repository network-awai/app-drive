# AWAI-4118 子 issue の提案

- **提案。Linear には未作成（Owner 承認後に起票）。** この文書は起票の案で、issue の作成・更新・状態変更を行っていない。
- 日付: 2026-11-25（改訂: レビュー指摘の反映）
- Tracking: [AWAI-4118](https://linear.app/kotobalabs/issue/AWAI-4118)（この作業は Orca Item #302。v1.0 の Item #295 は Organizer の item）
- 根拠: AWAI-4118 確定設計 v1.0 §7、[drive-host-ipc.md](drive-host-ipc.md)、[portable-usb-custody.md](portable-usb-custody.md)、[scalable-sync-design.md](scalable-sync-design.md) §4.8・§4.9・§8、[p0-measurement-plan.md](p0-measurement-plan.md)、`network-awai/network-awai-apex/docs/awai-disk-format.md`（**main**。PR #381 `b2a82b5d` と PR #382 `1ef51688` を含む）。
- 起票の単位は「別の PR にできる作業」。依存は実装の順序で、番号は優先順位ではない。**gate が通るまで、それぞれの「主張しないこと」を主張しない。**
- 前版からの変更: #1・#3・#4・#6・#7・#9・#10・#12・#14 の受入と依存を修正し、#15〜#23 を追加した（計 23 件）。#6↔#7 の循環と #9 の自己依存 D3 を解消した。

## 一覧

| # | 提案する title | repo | gate | 依存 |
|---|---|---|---|---|
| 1 | Drive host の骨格: `(lineage, instance)` 別の単一インスタンスと `awai-disk-core` の独占 link | app-drive | none | — |
| 2 | IPC schema v1 と OS 別 transport | app-drive | none | 1 |
| 3 | peer 認証、client の namespace / quota、cross-client の pin 保護 | app-drive | none | 2 |
| 4 | `request-id` の冪等性と `get-commit-status` | app-drive | none | 2, 3 |
| 5 | OS 統合アダプタ（File Provider / DocumentsProvider / WinFsp / FUSE） | app-drive | none | 2, 3 |
| 6 | Portable-USB の container v3（D8）の実装 | apex（`awai-disk-format.md`）+ app-drive | external crypto review | `awai-disk-format.md`（main、#381・#382） |
| 7 | OOB ペアリング、portable custody export、監査 record | app-drive | owner decision（鍵操作） | 6 |
| 8 | forest root のローテーション、copy 移行、drill | app-drive + app-itonami-yataverse | owner decision（鍵操作） | 6, 10 |
| 9 | §4.8 の namespace closure の退避、repack、Stage 1 | app-drive | none | P0, W1, W2, W3, D1, D2, 19 |
| 10 | 検証役の連携と Stage 2 の受入（R2 以上、`observedAt` / `validUntil`、version 指定の Object Lock） | app-drive + app-itonami-yataverse | owner decision（検証役の運用主体） | 9, 23, 受領証 S1、lease |
| 11 | P6 の bounded batch 同意（shared runtime の canonical broker） | shared runtime | owner decision（既定の N / B・期限） | — |
| 12 | client 側の古い `base-epoch` の commit 拒否 | app-drive | none | 15 |
| 13 | P0 の測定を 3 OS で実施 | app-drive + apex | none | — |
| 14 | 2 製品構成の UI/UX（Gamen が所有。ここは cross-ref のみ） | app-drive（UI） | none | 1, 2, 3 |
| 15 | サーバ commit の単調 epoch と `base-epoch` の設計 | app-itonami-yataverse | owner decision | — |
| 16 | FS probe と FLUSH 等級 A〜C の実装 | app-drive | none | 1 |
| 17 | USB の抜挿・safe-eject・`degraded-pack-absent`・交換 | app-drive | none | 1, 16 |
| 18 | 端末 high-water の永続化 | app-drive | none | 1 |
| 19 | namespace closure の置き場（Local-Meta は内蔵 meta、Portable は container） | app-drive | none | `awai-disk-format.md`（main） |
| 20 | device floor の `no-space` と quota 台帳 | app-drive | none | 1 |
| 21 | OS の backup / 索引からの除外 | app-drive | none | 1 |
| 22 | P6 の大規模退避 grant と Stage 3 の無人 LRU | shared runtime + app-drive | owner decision（既定の N / B） | 10, 11 |
| 23 | quota と 12 MiB transport 上限の引き上げ | app-drive + app-itonami-yataverse | owner decision（引き上げ） | 20, W4 |

計 23 件。

## 各項目

### 1. Drive host の骨格

**scope:** daemon / sidecar / bound service の骨格を OS ごとに作り、**`(volume-lineage-id, volume-instance-uuid)` を含む
endpoint 名**で単一インスタンスを選出する（macOS の `MachServices`、Windows の named mutex + pipe、Linux の socket path）。
同じ `(lineage, instance)` の 2 つ目は client になり、**別 instance の正当な clone は別 volume host** として起動してよい。
`awai-disk-core` の link を 1 つに保ち、`flock` が取れないときは writer にならない（`drive-host-ipc.md` §1・§2）。
Windows の排他の primitive は `awai-disk-format.md`（main）に合わせて決める。

**受入の証拠:** 同じ `(lineage, instance)` で 2 回起動して 1 つが client になること。別 instance（clone）の 2 つが同時に
別 volume host として動けること。2 番目のプロセスが同じ volume を開けないこと。**macOS / Windows / Linux は UI を閉じても
継続する。Android は実行中の同期 / 退避 / Pack 封がある間だけ foreground service で継続し、なければ止まる**（`drive-host-ipc.md` §2.5）。
host の crash 後に volume が open からやり直せること。**Android は `exported="true"` + signature 権限で bind でき、
`exported="false"` では別 app から bind できない例外を記録する。**

**依存:** なし。

**gate まで主張しないこと:** 「全 OS で排他が取れる」。Windows の primitive が未決定の間は、Windows で排他を主張しない。

### 2. IPC schema v1 と OS 別 transport

**scope:** `schemaVersion` / `minSupported` / 1 MiB の制御メッセージ / `dataHandle` の契約を実装し、macOS の XPC + App Group socket、
Windows の named pipe、Linux の Unix socket、Android の AIDL + `ParcelFileDescriptor` を対応させる。`get-volume-status` と
`inspect` の op を足す（`drive-host-ipc.md` §4）。

**受入の証拠:** 未知の版を `version-unsupported` で拒否すること。1 MiB を超える制御メッセージを `too-large` で拒否すること。
bytes が inline の base64 で運ばれないこと。transport ごとに同じ schema で往復できること。**`set-closure` と `get-volume-status` が
往復すること。** **`set-closure` の paging**: >1 MiB の closure が `chunked` で運ばれ、最終 digest / 署名の検証後に
原子的に swap されること。signature 欠落と `rootCid` 不一致が拒否されること。** **負の対照: false CID は `cid-mismatch`、
short read は拒否、262,144 B を超える block は `too-large`、directory / block / char / symlink の `dataHandle` は拒否。**

**依存:** 1。

**gate まで主張しないこと:** 「performance」。throughput と latency は 13 の測定の後。

### 3. peer 認証と namespace / quota

**scope:** OS ごとの peer 認証（署名 requirement、pipe の ACL と token、`SO_PEERCRED`、**`exported="true"` + signature 権限 + UID**）を
実装し、clientId の割り当て、`clients/<clientId>` の namespace、quota の予約と実使用、pin の所有と cross-client の拒否
（`drive-host-ipc.md` §3・§6）を実装する。**client から host の認証**（署名 / package identity、または pin した host 公開鍵との
challenge-response）と endpoint squat の拒否も含む。**署名済み namespace closure と namespace capability**（opaque handle、
または `(clientId, namespace root CID, closure version)` への MAC）を control record に保持し、`set-closure` の検証・
原子的更新・`closure-unknown` の拒否を実装する。**pin は自分の membership 内に限定する。** 監査 record は append-only で、
hash chain + keyed MAC を持ち、秘密値を含めない。

**受入の証拠:** 未認証の peer が `unauthorized` で閉じられること。namespace の外の読み書きが拒否されること。
client A の pin を client B が evict できず、拒否が監査 record に残ること。予約が取れない `put` が bytes を書かないこと。
**pin が quota を二重に消費せず、membership 外の pin が `closure-unknown` になること。** 偽 host（pin した鍵と違う host）に
繋がないこと。**未登録・古い・署名不一致の closure で `put` / `get` / `evict` / `pin` が拒否され、退避と membership 外の読みが
止まること。** **新規 content CID の `put` が `set-closure` の事前登録なしで成立し、membership に追加されること。
別 client が所有する CID の claim が `closure-conflict` で拒否されること。Portable の別端末移動後、container の signed
manifest から membership が復元されること。** **Portable では、`put` 後の signed manifest 更新が完了するまで `manifest-stale` を返し、
safe-eject と移動を完了しないこと。owner 公開鍵の pin が無い manifest を拒否すること（manifest 内の鍵一覧を信頼根拠にしない）。**
**負の対照: false CID（`cid-mismatch`）・short read・oversize block（`too-large`）を
2 と共有して確認する。**

**依存:** 2。

**gate まで主張しないこと:** 「cross-client / product の分離が暗号学的に証明された」。**Linux と非 MSIX Windows では同じ uid / SID の
別プロセスを区別できず、分離は暗号学的でない**（`drive-host-ipc.md` §3.6）。Android も同じ署名の app を区別しない。

### 4. 冪等性と commit の状態

**scope:** `(clientId, requestId)` の不変 record、二重 commit の拒否、`get-commit-status` の `committed` / `failed` / `unknown`、
record の寿命（`drive-host-ipc.md` §5）を実装する。

**受入の証拠:** ack を落としても `get-commit-status` が答えられること。同じ `requestId` で違う payload が
`idempotency-conflict` になること。record の期限切れが `unknown` になること。**`requestId` が CSPRNG で、host の再起動を
跨いで再利用されないこと。** **負の対照: false CID・short read・oversize block の拒否が、ack 喪失後の照会でも二重 commit に
ならないこと。**

**依存:** 2, 3。**WAL frame への `requestId` の搭載は Kura 側の別 issue（または `awai-disk-format.md` §11 の未決定）に依存する。**

**gate まで主張しないこと:** 「crash 時に `unknown` が無い」。WAL に `requestId` が入るまでは `unknown` が残る
（`drive-host-ipc.md` §5.4）。

### 5. OS 統合アダプタ

**scope:** File Provider（macOS）、DocumentsProvider（Android）、WinFsp（Windows）、FUSE（Linux）を host の client として実装する。
各アダプタは自分の `clientId` を持ち、volume を開かない（`drive-host-ipc.md` §7）。最初の mode は File Provider が read-only、
DocumentsProvider は現行どおり read-only、WinFsp / FUSE は未決定。

**受入の証拠:** アダプタが `awai-disk-core` を link していないこと。write を許さない mode で write が `read-only` になること。
File Provider の cache と `cache/` が二重にならないこと。**endpoint は `(volume-lineage-id, volume-instance-uuid)` を含み、
clone（別 instance）は別 host として開けること。**

**依存:** 2, 3。

**gate まで主張しないこと:** 「Finder / Explorer から書き込める」。書き込み mode は別 gate。

### 6. Portable-USB の container v3（D8）

**scope:** `awai-disk-format.md` §21（**main**、#381・#382 反映済み）に従い、hosted-file の暗号化単一コンテナ + sidecar を実装する。
`AES-256-GCM-SIV`、Argon2id + 256 bit の復旧鍵 + Passkey、AAD の canonical 形式と golden corpus、下降拒否、MAC 付き
lease / epoch（`portable-usb-custody.md` §2）。**生の復旧鍵は媒体に置かず、媒体外で別保管する。**

**受入の証拠:** 3 OS で同じ golden corpus の AAD が一致すること。弱い KDF 引数と `minWriterVersion` の下降を拒否すること。
lease の MAC の不一致で書き込みが止まること。**鍵の生成・ローテーションはこの issue では行わない。**
**signed manifest の署名対象に `membershipDigest` / `mode` / `chunkCount` が入り、closure と membership の両方を覆うこと。
owner 公開鍵 / root trust anchor で検証し、別端末移動後に membership を復元できること。**

**依存:** `awai-disk-format.md`（main、#381・#382）。**7 には依存しない**（custody の export が無くても container は実装できる）。

**gate まで主張しないこと:** 「Portable-USB が使える」。**外部暗号レビュー**（`awai-disk-format.md` §17.2）と、main の形式の後。
レビューの発注・課金は owner の確認。

### 7. OOB ペアリングと custody export

**scope:** 受入端末の公開鍵への out-of-band ペアリング（`portable-usb-custody.md` §3）と、明示的な export（§4）を実装する。
1 回の確認、append-only の監査 record、秘密値を記録しないこと、AAD の束縛、nonce の possession proof、used nonce の保存、
輸送・保存の暗号化、拒否の状態を実装する。

**受入の証拠:** nonce の再送と期限切れが拒否されること。AAD の欄が欠けた包みを拒否すること。監査 record を書けないときに
export しないこと。error message と log に秘密値が出ないこと（試験で検査する）。QR に capability が載らないこと。

**依存:** 6。

**gate まで主張しないこと:** 「失効できる」。export も pairing も capability を失効させない（§6）。
**owner 確認対象の鍵操作**であり、実行はこの issue の実装後、別の承認で行う。

### 8. forest root のローテーションと copy 移行

**scope:** 紛失時の forest root のローテーション、再 seal、copy 移行、drill（`portable-usb-custody.md` §6・§7）を実装する。
**失効の 3 段階**（新 root の正本化で旧 capability は新 root を読めず書けない、旧 root への書込みは W4 まで防げない、
配信停止は暗号学的無効化ではない）、旧 revision と remote-only block の扱い、retention と削除順序、残存端末の再ペアリング、
device revocation list を含む。

**受入の証拠:** drill を捨ててよい fixture で通すこと。新 root の正本化が検証役の停止中でも行えること。旧 root の配信停止と
lease release の順序。**実機の USB と実鍵では行わない。**

**依存:** 6, 10。

**gate まで主張しないこと:** 「紛失しても復旧できる」。所要時間は見積り（§6.3）で測定ではない。owner の確認（鍵操作）と、
検証役の R2 以上の確認が前提。

### 9. §4.8 の namespace closure の退避と Stage 1

**scope:** 退避の単位を file content block にし、directory node と forest node を保護する。**署名済み closure set と membership**
（`set-closure` と `put`、`drive-host-ipc.md` §4.7）を退避の判定に使い、repack / compaction を実装し、
Stage 1（`cache/` の LRU）を実装する（`scalable-sync-design.md` §4.8）。closure は Local-Meta で内蔵 meta の `closure/`、
Portable で container 内に置く（19）。**closure 内の CID は退避しない。退避対象は closure 外の content block で、
どの client からも pin されていないもの。**

**受入の証拠:** `cache/` の block だけが消え、directory / forest node が残ること。部分退避を拒否すること。repack の後に
CID が一致すること。N4（directory entry の欠落）が検出されること。**USB 不在でも一覧と path 解決ができること（Local-Meta）。**
**closure set の CID が退避されないこと。closure 外でも pin された content は退避されないこと。membership 外の pin が
`closure-unknown` で拒否されること。**

**依存:** P0, W1, W2, W3, D1, D2, 19。**D3 には依存しない**（この issue が D3 / Stage 1 である）。

**gate まで主張しないこと:** 「`packs/` を退避できる」。それは Stage 2（10 の後）。

### 10. 検証役の連携と Stage 2 の受入

**scope:** Drive が検証役の記録を使う経路を実装する。R2 以上の `policy-satisfied`、`observedAt` / `validUntil` の検査、
version id による Object Lock の確認、`storeClass 3` を独立ドメインに数えないこと、`retentionUntil=0` の拒否
（`scalable-sync-design.md` §4.8 の Stage 2）。**Stage 2 の受入は 9 の repack 実装の後**に置く。

**受入の証拠:** 期限切れ・`retentionUntil=0`・inventory 外・Object Lock の不一致・`storeClass 3` だけの R2 を拒否する
負の対照（N8〜N10）。検証役を止めると状態が「不明」になり、退避が止まること。`packs/` の部分退避が repack を経ること。

**依存:** 9, 23, 受領証 S1（`app-itonami-yataverse` の lease / 受領証）、lease。**Stage 2 の gate は quota と 12 MiB の引き上げ（23）。**

**gate まで主張しないこと:** 「受領証が保持を証明する」。受領証は保持の約束ではなく、`policy-satisfied` は検証役が出す
（`app-itonami-yataverse/docs/retention-design.md` §5・§6）。検証役の運用主体は owner が決める（同 §16）。

### 11. P6 の bounded batch 同意

**scope:** canonical broker（shared runtime）に bounded batch を実装する。grant は endpoint・**method / path prefix（または
operation 集合）**・**data category**・**client / device の binding**・**grant id / nonce**・最大 N・最大 B・期限を名指し、
拒否は 0 件、append-only の監査 record（grant id を含む）、**送信前の N / B の予約**、消費（失敗・再試行・readback を含む）、
revoke の強制（`scalable-sync-design.md` §4.9）。

**受入の証拠:** 拒否で request が 0 件であること。N / B を使い切った後に追加の承認が要ること。revoke の後に送らないこと。
製品側に同意の実装が無いこと。grant id が監査 record に残ること。

**依存:** なし。

**gate まで主張しないこと:** 「無人の大量退避ができる」。既定の N / B・期限は owner の決定で、実装が入るまで製品は使えない。

### 12. client 側の古い `base-epoch` の commit 拒否

**scope:** `sync client` が、サーバが返す単調 epoch と自分の `base-epoch` を照合し、古い `base-epoch` の commit を
**型付きで拒否**する。黙って rebase しない（`scalable-sync-design.md` §4.8）。**信頼点（クライアント側のピン留め、または
transparency log）の実装もこの issue に含める**（分割する場合は #24 を追加する）。

**受入の証拠:** 古い `base-epoch` の commit を送らないこと。拒否の理由を UI に出すこと。**信頼点が無い場合、サーバ（または
経路）が古い状態を示しても気づけないことを記録し、検出を主張しないこと。**

**依存:** 15（サーバの commit / `base-epoch` の設計）。**15 が入るまで、オンラインの巻き戻し検出を主張しない。**

**gate まで主張しないこと:** 「巻き戻しを検出できる」。**オフライン・媒体全体の別端末への巻き戻しは検出できない**（仕様と UI の両方に明記）。

### 13. P0 の測定

**scope:** [p0-measurement-plan.md](p0-measurement-plan.md) の §4〜§7 を 3 OS で実施する。fixture は loopback の image、一時 volume、
fake store、emulator、捨ててよい VM だけを使う。破壊試験は fixture だけで行い、ラベルを付ける。

**受入の証拠:** §2 の形式の記録。`unknown` の項目とその理由。負の対照 **N1〜N12** の結果。**実機の USB を使っていないこと。
loopback の結果を実機 USB の資格認定として扱わないこと。**

**依存:** なし。

**gate まで主張しないこと:** 「測定済み」。この PR では 1 件も実行していない。

### 14. 2 製品構成の UI/UX

**scope:** Drive host の状態（host が無い、`busy`、`volume-absent`、**`degraded-pack-absent`**、`read-only`、`stopped`、
`no-space`、fork、オフライン巻き戻しの「検出できない」）、Portable の unlock、custody の確認、bounded batch の同意を UI にする。
**所有者は Gamen**（AWAI-4118 v1.0 §7 の 3）。ここは cross-ref だけで、UI を設計しない。

**受入の証拠:** `drive-host-ipc.md` §4.6 の 5 つの `state` と `no-space` を区別して表示すること。オフライン巻き戻しを
「検出できない」と表示すること。`stopped` では `inspect` の案内を出し、通常の読み取りを案内しないこと。

**依存:** 1, 2, 3。

**gate まで主張しないこと:** 「UI が完成した」。この issue は Gamen の設計を前提にし、UI の正本を持たない。

### 15. サーバ commit の単調 epoch と `base-epoch` の設計・実装

**scope:** Yataverse の入口に、lease と commit の**単調 epoch**、`base-epoch` の記録、古い `base-epoch` の commit の拒否を
**設計・実装する**。クライアント側のピン留め、または transparency log が前提であることを明記する
（`awai-disk-format.md` §20 #16）。**サーバ単調 epoch の実装と受入まで含める**（分割する場合は子 #24 を新設し、
#12 から依存させる。その場合 #15 は設計のままにしない）。

**受入の証拠:** 設計文書（`app-itonami-yataverse` の docs）に加えて、**実装**: 古い `base-epoch` の commit を型付きで
拒否し、同じ内容の再送が冪等で、host の再起動後も epoch が単調であること。**実装と受入を #15 から外さない。**

**依存:** なし。

**gate まで主張しないこと:** 「オンラインの巻き戻しを検出できる」。設計が入るまで、Drive も Yataverse も主張しない。

### 16. FS probe と FLUSH 等級 A〜C

**scope:** volume open 時の FS probe（最強の sync primitive、filesystem 種別と journal、directory entry の生存、rename の原子性、
4 GiB 超）と、FLUSH 等級 A / B / C の実装・降格（A→C で即時に書き込み停止）（`awai-disk-format.md` §4.9）。**抜け分類 (1)。**

**受入の証拠:** fsync だけの弱い probe を通さないこと。SMB / NFS を拒否すること。A→C の降格で書き込みが止まること。
primitive の名前と結果が device ごとに永続化されること。**破壊試験は fixture だけで、`destructive: yes` を記録する。**

**依存:** 1。

**gate まで主張しないこと:** 「durability を証明した」。破壊試験をしていない項目は `unknown`。

### 17. USB の抜挿・safe-eject・`degraded-pack-absent`・交換

**scope:** Local-Meta の `optional` の pack device の不在（`degraded-pack-absent`）と、Portable の `volume-absent`、
safe-eject（dirty を無くす → FLUSH → lease release → clean。必須は等級 A / B）、USB の交換（旧 device UUID の retire、
新 UUID の追加、remote からの再取得）（`awai-disk-format.md` §4.4・§21.8）。**抜け分類 (2)。**

**受入の証拠:** 抜去中に読みが CID 検証済みの cache / remote だけになること。書きが拒否されること。内蔵に新しい volume を
作らないこと。`degraded-pack-absent` と `stopped` が混ざらないこと。safe-eject の後に `volume-absent` になること。

**依存:** 1, 16。

**gate まで主張しないこと:** 「どの OS でも安全に抜ける」。実機の抜挿は別の確認。

### 18. 端末 high-water の永続化

**scope:** 同一端末の high-water（キー `(volume-lineage-id, volume-instance-uuid)`、値「見た最大の writer epoch + 最大の
open 回数（単調）」）を永続化し、古い epoch の媒体を停止する（`awai-disk-format.md` §21.6）。**session id を high-water に
使わない。** high-water は **volume / media の外**の device-protected store に置き、**端末 backup の復元で戻らない**前提。
**抜け分類 (3)。**

**受入の証拠:** 同じ instance の古い epoch を停止すること。別 instance（正当な clone）を誤検出しないこと。
**同一端末で新しい epoch を見た後の巻き戻しだけを検出し、別端末の `dd` 写しは検出しないこと。** high-water の置き場を
記録すること（置き場は未決定のまま）。

**依存:** 1。

**gate まで主張しないこと:** 「別端末への巻き戻しを検出する」。検出しない。

### 19. namespace closure の置き場

**scope:** Local-Meta は内蔵 meta の `closure/`、Portable-USB は container 内に closure を固定する（`drive-host-ipc.md` §6.1）。
`awai-disk-format.md` §4.8（**main**）の固定に合わせる。**抜け分類 (4)。**

**受入の証拠:** Local-Meta で USB 不在でも一覧と path 解決ができること。Portable で container を開けば closure が得られること。
closure を `cache/` に置かないこと。

**依存:** `awai-disk-format.md`（main、container の内部配置）。

**gate まで主張しないこと:** 「closure が 3 OS で同じ配置」。`awai-disk-format.md`（main）の形式の確定後。

### 20. device floor の `no-space` と quota 台帳

**scope:** device ごとの空き容量の下限（device floor）を実装し、当たったら `no-space` で拒否する。client の quota 超過
（`quota-exceeded`）と分ける。`pin` は保持のフラグで、bytes の quota を二重に消費しない（`drive-host-ipc.md` §6.2）。
**抜け分類 (5)。**

**受入の証拠:** floor で `no-space` になり、何も書かないこと。quota 超過が `quota-exceeded` になること。pin の作成・削除で
実使用が変わらないこと。証跡や未退避の data を消して空けないこと。

**依存:** 1。

**gate まで主張しないこと:** 「空き容量を保証する」。値は P0 と `awai-disk-format.md` §4.7 の仮置きの後。

### 21. OS の backup / 索引からの除外

**scope:** volume を OS の backup（Time Machine、Windows のバックアップ、Android の backup）と索引（Spotlight、Windows Search、
MediaStore）から除外する。除外できない backup は、quiesce しない backup を不整合として扱う（`awai-disk-format.md` §18.3 #17）。**抜け分類 (6)。**

**受入の証拠:** 各 OS で除外の設定が入ること。除外できない場合に「不整合として扱う」ことが記録されること。

**依存:** 1。

**gate まで主張しないこと:** 「すべての backup から除外できる」。OS ごとの制約を記録する。

### 22. P6 の大規模退避 grant と Stage 3 の無人 LRU

**scope:** 退避専用の grant（endpoint・N・B・期限・operation 集合・対象）を定義し、Stage 3 の無人 LRU をその grant の下でだけ
動かす（`scalable-sync-design.md` §4.8・§4.9）。**抜け分類 (7)。**

**受入の証拠:** grant の N / B を超える退避をしないこと。grant の失効・revoke で止まること。Stage 2 の手動の退避も同じ
grant の消費として数えること。

**依存:** 10, 11。

**gate まで主張しないこと:** 「無人で大量に退避できる」。既定の N / B は owner の決定。

### 23. quota と 12 MiB transport 上限の引き上げ

**scope:** client / device の quota の値を決めて実装し、Yataverse の transport 上限（12 MiB）を引き上げる。**引き上げは
書き込みの認可（W4）と quota の後**（`scalable-sync-design.md` §4.9、`encrypted-sync.md`）。

**受入の証拠:** quota の値が記録され、超過が `quota-exceeded` になること。12 MiB の引き上げ後も pack の検証と readback が
通ること。認可なしに上限だけを上げていないこと。

**依存:** 20, W4。

**gate まで主張しないこと:** 「64 GiB を少ない回数で送れる」。引き上げの前は 12 MiB が上限で、見積りはその前提
（`portable-usb-custody.md` §6.3）。
