# AWAI-4118 子 issue の提案

- **提案。Linear には未作成（Owner 承認後に起票）。** この文書は起票の案で、issue の作成・更新・状態変更を行っていない。
- 日付: 2026-11-25（改訂: レビュー指摘の反映）
- Tracking: [AWAI-4118](https://linear.app/kotobalabs/issue/AWAI-4118)（この作業は Orca Item #302。v1.0 の Item #295 は Organizer の item）
- 根拠: AWAI-4118 確定設計 v1.0 §7、[drive-host-ipc.md](drive-host-ipc.md)、[portable-usb-custody.md](portable-usb-custody.md)、[scalable-sync-design.md](scalable-sync-design.md) §4.8・§4.9・§8、[p0-measurement-plan.md](p0-measurement-plan.md)、`network-awai/network-awai-apex` PR #381 の `docs/awai-disk-format.md` **改訂案**（未マージ）
- 起票の単位は「別の PR にできる作業」。依存は実装の順序で、番号は優先順位ではない。**gate が通るまで、それぞれの「主張しないこと」を主張しない。**
- 前版からの変更: #1・#3・#4・#6・#7・#9・#10・#12・#14 の受入と依存を修正し、#15〜#23 を追加した（計 23 件）。#6↔#7 の循環と #9 の自己依存 D3 を解消した。

## 一覧

| # | 提案する title | repo | gate | 依存 |
|---|---|---|---|---|
| 1 | Drive host の骨格: lineage 別の単一インスタンスと `awai-disk-core` の独占 link | app-drive | none | — |
| 2 | IPC schema v1 と OS 別 transport | app-drive | none | 1 |
| 3 | peer 認証、client の namespace / quota、cross-client の pin 保護 | app-drive | none | 2 |
| 4 | `request-id` の冪等性と `get-commit-status` | app-drive | none | 2, 3 |
| 5 | OS 統合アダプタ（File Provider / DocumentsProvider / WinFsp / FUSE） | app-drive | none | 2, 3 |
| 6 | Portable-USB の container v3（D8）の実装 | apex（Kura の形式）+ app-drive | external crypto review | Kura の形式 PR（#381） |
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
| 19 | namespace closure の置き場（Local-Meta は内蔵 meta、Portable は container） | app-drive | none | Kura の形式 PR |
| 20 | device floor の `no-space` と quota 台帳 | app-drive | none | 1 |
| 21 | OS の backup / 索引からの除外 | app-drive | none | 1 |
| 22 | P6 の大規模退避 grant と Stage 3 の無人 LRU | shared runtime + app-drive | owner decision（既定の N / B） | 10, 11 |
| 23 | quota と 12 MiB transport 上限の引き上げ | app-drive + app-itonami-yataverse | owner decision（引き上げ） | 20, W4 |

計 23 件。

## 各項目

### 1. Drive host の骨格

**scope:** daemon / sidecar / bound service の骨格を OS ごとに作り、**`volume-lineage-id` を含む endpoint 名**で単一インスタンスを
選出する（macOS の `MachServices`、Windows の named mutex + pipe、Linux の socket path）。同じ lineage の 2 つ目は client になり、
**違う lineage は別 host** として起動してよい。`awai-disk-core` の link を 1 つに保ち、`flock` が取れないときは writer にならない
（`drive-host-ipc.md` §1・§2）。Windows の排他の primitive は Kura の形式改訂に合わせて決める。

**受入の証拠:** 同じ lineage で 2 回起動して 1 つが client になること。違う lineage の 2 つが同時に動けること。2 番目のプロセスが
volume を開けないこと。UI を閉じても host が動き続けること（OS ごと）。host の crash 後に volume が open からやり直せること。
**Android は `exported="true"` + signature 権限で bind でき、`exported="false"` では別 app から bind できない例外を記録する。**

**依存:** なし。

**gate まで主張しないこと:** 「全 OS で排他が取れる」。Windows の primitive が未決定の間は、Windows で排他を主張しない。

### 2. IPC schema v1 と OS 別 transport

**scope:** `schemaVersion` / `minSupported` / 1 MiB の制御メッセージ / `dataHandle` の契約を実装し、macOS の XPC + App Group socket、
Windows の named pipe、Linux の Unix socket、Android の AIDL + `ParcelFileDescriptor` を対応させる。`get-volume-status` と
`inspect` の op を足す（`drive-host-ipc.md` §4）。

**受入の証拠:** 未知の版を `version-unsupported` で拒否すること。1 MiB を超える制御メッセージを `too-large` で拒否すること。
bytes が inline の base64 で運ばれないこと。transport ごとに同じ schema で往復できること。

**依存:** 1。

**gate まで主張しないこと:** 「performance」。throughput と latency は 13 の測定の後。

### 3. peer 認証と namespace / quota

**scope:** OS ごとの peer 認証（署名 requirement、pipe の ACL と token、`SO_PEERCRED`、**`exported="true"` + signature 権限 + UID**）を
実装し、clientId の割り当て、`clients/<clientId>` の namespace、quota の予約と実使用、pin の所有と cross-client の拒否
（`drive-host-ipc.md` §3・§6）を実装する。**client から host の認証**（署名 / package identity、または pin した host 公開鍵との
challenge-response）と endpoint squat の拒否も含む。監査 record は append-only で、秘密値を含めない。

**受入の証拠:** 未認証の peer が `unauthorized` で閉じられること。namespace の外の読み書きが拒否されること。
client A の pin を client B が evict できず、拒否が監査 record に残ること。予約が取れない `put` が bytes を書かないこと。
**pin が quota を二重に消費しないこと。** 偽 host（pin した鍵と違う host）に繋がないこと。

**依存:** 2。

**gate まで主張しないこと:** 「cross-client / product の分離が暗号学的に証明された」。**Linux と非 MSIX Windows では同じ uid / SID の
別プロセスを区別できず、分離は暗号学的でない**（`drive-host-ipc.md` §3.6）。Android も同じ署名の app を区別しない。

### 4. 冪等性と commit の状態

**scope:** `(clientId, requestId)` の不変 record、二重 commit の拒否、`get-commit-status` の `committed` / `failed` / `unknown`、
record の寿命（`drive-host-ipc.md` §5）を実装する。

**受入の証拠:** ack を落としても `get-commit-status` が答えられること。同じ `requestId` で違う payload が
`idempotency-conflict` になること。record の期限切れが `unknown` になること。

**依存:** 2, 3。**WAL frame への `requestId` の搭載は Kura 側の別 issue（または PR #381 §11 の未決定）に依存する。**

**gate まで主張しないこと:** 「crash 時に `unknown` が無い」。WAL に `requestId` が入るまでは `unknown` が残る
（`drive-host-ipc.md` §5.4）。

### 5. OS 統合アダプタ

**scope:** File Provider（macOS）、DocumentsProvider（Android）、WinFsp（Windows）、FUSE（Linux）を host の client として実装する。
各アダプタは自分の `clientId` を持ち、volume を開かない（`drive-host-ipc.md` §7）。最初の mode は File Provider が read-only、
DocumentsProvider は現行どおり read-only、WinFsp / FUSE は未決定。

**受入の証拠:** アダプタが `awai-disk-core` を link していないこと。write を許さない mode で write が `read-only` になること。
File Provider の cache と `cache/` が二重にならないこと。

**依存:** 2, 3。

**gate まで主張しないこと:** 「Finder / Explorer から書き込める」。書き込み mode は別 gate。

### 6. Portable-USB の container v3（D8）

**scope:** Kura の形式改訂（PR #381 の §21）に従い、hosted-file の暗号化単一コンテナ + sidecar を実装する。
`AES-256-GCM-SIV`、Argon2id + 256 bit の復旧鍵 + Passkey、AAD の canonical 形式と golden corpus、下降拒否、MAC 付き
lease / epoch（`portable-usb-custody.md` §2）。**生の復旧鍵は媒体に置かず、媒体外で別保管する。**

**受入の証拠:** 3 OS で同じ golden corpus の AAD が一致すること。弱い KDF 引数と `minWriterVersion` の下降を拒否すること。
lease の MAC の不一致で書き込みが止まること。**鍵の生成・ローテーションはこの issue では行わない。**

**依存:** Kura の形式 PR（#381）。**7 には依存しない**（custody の export が無くても container は実装できる）。

**gate まで主張しないこと:** 「Portable-USB が使える」。**外部暗号レビュー**（v1.0 §6）と Kura の形式 PR の後。
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
失効の定義（新 root が正本、旧 capability は以後の書込みに使えない、過去のコピーは消せない）、旧 revision と remote-only block の
扱い、retention と削除順序、残存端末の再ペアリング、device revocation list を含む。

**受入の証拠:** drill を捨ててよい fixture で通すこと。新 root の正本化が検証役の停止中でも行えること。旧 root の配信停止と
lease release の順序。**実機の USB と実鍵では行わない。**

**依存:** 6, 10。

**gate まで主張しないこと:** 「紛失しても復旧できる」。所要時間は見積り（§6.3）で測定ではない。owner の確認（鍵操作）と、
検証役の R2 以上の確認が前提。

### 9. §4.8 の namespace closure の退避と Stage 1

**scope:** 退避の単位を file content block にし、directory node と forest node を保護する。repack / compaction を実装し、
Stage 1（`cache/` の LRU）を実装する（`scalable-sync-design.md` §4.8）。closure は Local-Meta で内蔵 meta、Portable で
container 内に置く（19）。

**受入の証拠:** `cache/` の block だけが消え、directory / forest node が残ること。部分退避を拒否すること。repack の後に
CID が一致すること。N4（directory entry の欠落）が検出されること。**USB 不在でも一覧と path 解決ができること（Local-Meta）。**

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
**型付きで拒否**する。黙って rebase しない（`scalable-sync-design.md` §4.8）。

**受入の証拠:** 古い `base-epoch` の commit を送らないこと。拒否の理由を UI に出すこと。

**依存:** 15（サーバの commit / `base-epoch` の設計）。**15 が入るまで、オンラインの巻き戻し検出を主張しない。**

**gate まで主張しないこと:** 「巻き戻しを検出できる」。**オフライン・媒体全体の別端末への巻き戻しは検出できない**（仕様と UI の両方に明記）。

### 13. P0 の測定

**scope:** [p0-measurement-plan.md](p0-measurement-plan.md) の §4〜§7 を 3 OS で実施する。fixture は loopback の image、一時 volume、
fake store、emulator、捨ててよい VM だけを使う。破壊試験は fixture だけで行い、ラベルを付ける。

**受入の証拠:** §2 の形式の記録。`unknown` の項目とその理由。負の対照 N1〜N11 の結果。**実機の USB を使っていないこと。**

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

### 15. サーバ commit の単調 epoch と `base-epoch` の設計

**scope:** Yataverse の入口に、lease と commit の**単調 epoch**、`base-epoch` の記録、古い `base-epoch` の commit の拒否を設計する。
クライアント側のピン留め、または transparency log が前提であることを明記する（改訂案 §20 の 16）。実装はこの設計の後。

**受入の証拠:** 設計文書（`app-itonami-yataverse` の docs）。古い `base-epoch` の拒否の型と、同じ内容の再送の冪等性。
**実装と受入は別 issue。**

**依存:** なし。

**gate まで主張しないこと:** 「オンラインの巻き戻しを検出できる」。設計が入るまで、Drive も Yataverse も主張しない。

### 16. FS probe と FLUSH 等級 A〜C

**scope:** volume open 時の FS probe（最強の sync primitive、filesystem 種別と journal、directory entry の生存、rename の原子性、
4 GiB 超）と、FLUSH 等級 A / B / C の実装・降格（A→C で即時に書き込み停止）（改訂案 §4.9）。**抜け分類 (1)。**

**受入の証拠:** fsync だけの弱い probe を通さないこと。SMB / NFS を拒否すること。A→C の降格で書き込みが止まること。
primitive の名前と結果が device ごとに永続化されること。**破壊試験は fixture だけで、`destructive: yes` を記録する。**

**依存:** 1。

**gate まで主張しないこと:** 「durability を証明した」。破壊試験をしていない項目は `unknown`。

### 17. USB の抜挿・safe-eject・`degraded-pack-absent`・交換

**scope:** Local-Meta の `optional` の pack device の不在（`degraded-pack-absent`）と、Portable の `volume-absent`、
safe-eject（dirty を無くす → FLUSH → lease release → clean。必須は等級 A / B）、USB の交換（旧 device UUID の retire、
新 UUID の追加、remote からの再取得）（改訂案 §4.4・§21.8）。**抜け分類 (2)。**

**受入の証拠:** 抜去中に読みが CID 検証済みの cache / remote だけになること。書きが拒否されること。内蔵に新しい volume を
作らないこと。`degraded-pack-absent` と `stopped` が混ざらないこと。safe-eject の後に `volume-absent` になること。

**依存:** 1, 16。

**gate まで主張しないこと:** 「どの OS でも安全に抜ける」。実機の抜挿は別の確認。

### 18. 端末 high-water の永続化

**scope:** 同一端末の high-water（`(volume-lineage-id, volume-instance-uuid) → writer epoch の最大値`）を永続化し、
古い epoch の媒体を停止する（改訂案 §21.6）。**session id を high-water に使わない。** **抜け分類 (3)。**

**受入の証拠:** 同じ instance の古い epoch を停止すること。別 instance（正当な clone）を誤検出しないこと。
high-water の置き場を記録すること（置き場は未決定のまま）。

**依存:** 1。

**gate まで主張しないこと:** 「別端末への巻き戻しを検出する」。検出しない。

### 19. namespace closure の置き場

**scope:** Local-Meta は内蔵 meta、Portable-USB は container 内に closure を固定する（`drive-host-ipc.md` §6.1）。
改訂案 §11 の「closure の置き場」の未決定を、Drive の側でこの 2 つに決める。**抜け分類 (4)。**

**受入の証拠:** Local-Meta で USB 不在でも一覧と path 解決ができること。Portable で container を開けば closure が得られること。
closure を `cache/` に置かないこと。

**依存:** Kura の形式 PR（container の内部配置）。

**gate まで主張しないこと:** 「closure が 3 OS で同じ配置」。Kura の形式の確定後。

### 20. device floor の `no-space` と quota 台帳

**scope:** device ごとの空き容量の下限（device floor）を実装し、当たったら `no-space` で拒否する。client の quota 超過
（`quota-exceeded`）と分ける。`pin` は保持のフラグで、bytes の quota を二重に消費しない（`drive-host-ipc.md` §6.2）。
**抜け分類 (5)。**

**受入の証拠:** floor で `no-space` になり、何も書かないこと。quota 超過が `quota-exceeded` になること。pin の作成・削除で
実使用が変わらないこと。証跡や未退避の data を消して空けないこと。

**依存:** 1。

**gate まで主張しないこと:** 「空き容量を保証する」。値は P0 と改訂案 §4.7 の仮置きの後。

### 21. OS の backup / 索引からの除外

**scope:** volume を OS の backup（Time Machine、Windows のバックアップ、Android の backup）と索引（Spotlight、Windows Search、
MediaStore）から除外する。除外できない backup は、quiesce しない backup を不整合として扱う（改訂案 §18.3 #17）。**抜け分類 (6)。**

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
