# AWAI-4118 子 issue の提案

- **提案。Linear には未作成（Owner 承認後に起票）。** この文書は起票の案で、issue の作成・更新・状態変更を行っていない。
- 日付: 2026-11-25
- Tracking: [AWAI-4118](https://linear.app/kotobalabs/issue/AWAI-4118)（この作業は Orca Item #302。v1.0 の Item #295 は Organizer の item）
- 根拠: AWAI-4118 確定設計 v1.0 §7（次段の担当と成果物）、[drive-host-ipc.md](drive-host-ipc.md)、[portable-usb-custody.md](portable-usb-custody.md)、[scalable-sync-design.md](scalable-sync-design.md) §4.8・§4.9・§8、[p0-measurement-plan.md](p0-measurement-plan.md)
- 起票の単位は「別の PR にできる作業」。依存は実装の順序で、番号は優先順位ではない。**gate が通るまで、それぞれの「主張しないこと」を主張しない。**

## 一覧

| # | 提案する title | repo | gate | 依存 |
|---|---|---|---|---|
| 1 | Drive host の骨格: 単一インスタンスと `awai-disk-core` の独占 link | app-drive | none | — |
| 2 | IPC schema v1 と OS 別 transport | app-drive | none | 1 |
| 3 | peer 認証、client の namespace / quota、cross-client の pin 保護 | app-drive | none | 2 |
| 4 | `request-id` の冪等性と `get-commit-status` | app-drive | none | 2, 3 |
| 5 | OS 統合アダプタ（File Provider / DocumentsProvider / WinFsp / FUSE） | app-drive | none | 2, 3 |
| 6 | Portable-USB の container v3（D8）の実装 | apex（Kura の形式）+ app-drive | external crypto review | Kura の形式 PR、7 |
| 7 | OOB ペアリング、portable custody export、監査 record | app-drive | owner decision（鍵操作） | 6 |
| 8 | forest root のローテーション、copy 移行、drill | app-drive + app-itonami-yataverse | owner decision（鍵操作） | 6, 10 |
| 9 | §4.8 の namespace closure の退避、repack、Stage 1 | app-drive | none | D3, W1, W3 |
| 10 | 検証役の連携: R2 以上の `policy-satisfied`、`observedAt` / `validUntil`、version 指定の Object Lock、`storeClass 3` の数え方 | app-drive + app-itonami-yataverse | owner decision（検証役の運用主体） | 受領証 S1、lease |
| 11 | P6 の bounded batch 同意（shared runtime の canonical broker） | shared runtime | owner decision（既定の N / B・期限） | — |
| 12 | サーバの単調 epoch と、古い `base-epoch` の commit 拒否 | app-itonami-yataverse | none | 4, D2 |
| 13 | P0 の測定を 3 OS で実施 | app-drive + apex | none | — |
| 14 | 2 製品構成の UI/UX（Gamen が所有。ここは cross-ref のみ） | app-drive（UI） | none | 1, 2, 3 |

計 14 件。

## 各項目

### 1. Drive host の骨格

**scope:** daemon / sidecar / bound service の骨格を OS ごとに作り、endpoint 名（XPC の `MachServices`、named pipe、Unix socket、
Android の service）で単一インスタンスを選出する。`awai-disk-core` の link を 1 つに保ち、`flock` が取れないときは writer に
ならない（`drive-host-ipc.md` §1・§2）。Windows の排他の primitive は Kura の形式改訂に合わせて決める。

**受入の証拠:** 2 回起動して 1 つが client になること。2 番目のプロセスが volume を開けないこと。UI を閉じても host が
動き続けること（OS ごと）。host の crash 後に volume が open からやり直せること。

**gate まで主張しないこと:** 「全 OS で排他が取れる」。Windows の primitive が未決定の間は、Windows で排他を主張しない。

### 2. IPC schema v1 と OS 別 transport

**scope:** `schemaVersion` / `minSupported` / 1 MiB の制御メッセージ / `dataHandle` の契約を実装し、macOS の XPC + App Group socket、
Windows の named pipe、Linux の Unix socket、Android の AIDL + `ParcelFileDescriptor` を対応させる（`drive-host-ipc.md` §4）。

**受入の証拠:** 未知の版を `version-unsupported` で拒否すること。1 MiB を超える制御メッセージを `too-large` で拒否すること。
bytes が inline の base64 で運ばれないこと。transport ごとに同じ schema で往復できること。

**gate まで主張しないこと:** 「performance」。throughput と latency は 13 の測定の後。

### 3. peer 認証と namespace / quota

**scope:** OS ごとの peer 認証（署名 requirement、pipe の ACL と token、`SO_PEERCRED`、signature 権限と UID）を実装し、
clientId の割り当て、`clients/<clientId>` の namespace、quota の予約と実使用、pin の所有と cross-client の拒否
（`drive-host-ipc.md` §3・§6）を実装する。監査 record は append-only で、秘密値を含めない。

**受入の証拠:** 未認証の peer が `unauthorized` で閉じられること。namespace の外の読み書きが拒否されること。
client A の pin を client B が evict できず、拒否が監査 record に残ること。予約が取れない `put` が bytes を書かないこと。

**gate まで主張しないこと:** 「product の分離が暗号学的に証明された」。OS ごとに証明しないもの（`drive-host-ipc.md` §3.5）が残る。

### 4. 冪等性と commit の状態

**scope:** `(clientId, requestId)` の不変 record、二重 commit の拒否、`get-commit-status` の `committed` / `failed` / `unknown`、
record の寿命（`drive-host-ipc.md` §5）を実装する。WAL frame への `requestId` の追加は Kura の形式改訂に依存する。

**受入の証拠:** ack を落としても `get-commit-status` が答えられること。同じ `requestId` で違う payload が
`idempotency-conflict` になること。record の期限切れが `unknown` になること。

**gate まで主張しないこと:** 「crash 時に `unknown` が無い」。WAL に `requestId` が入るまでは `unknown` が残る（§5.4）。

### 5. OS 統合アダプタ

**scope:** File Provider（macOS）、DocumentsProvider（Android）、WinFsp（Windows）、FUSE（Linux）を host の client として実装する。
各アダプタは自分の `clientId` を持ち、volume を開かない（`drive-host-ipc.md` §7）。最初の mode は File Provider が read-only、
DocumentsProvider は現行どおり read-only、WinFsp / FUSE は未決定。

**受入の証拠:** アダプタが `awai-disk-core` を link していないこと。write を許さない mode で write が `read-only` になること。
File Provider の cache と `cache/` が二重にならないこと。

**gate まで主張しないこと:** 「Finder / Explorer から書き込める」。書き込み mode は別 gate。

### 6. Portable-USB の container v3（D8）

**scope:** Kura の形式改訂（v1.0 §7 の 1）に従い、hosted-file の暗号化単一コンテナ + sidecar を実装する。`AES-256-GCM-SIV`、
Argon2id + 256 bit の復旧鍵 + Passkey、AAD の canonical 形式と golden corpus、下降拒否、MAC 付き lease / epoch
（`portable-usb-custody.md` §2）。

**受入の証拠:** 3 OS で同じ golden corpus の AAD が一致すること。弱い KDF 引数と `minWriterVersion` の下降を拒否すること。
lease の MAC の不一致で書き込みが止まること。**鍵の生成・ローテーションはこの issue では行わない。**

**gate まで主張しないこと:** 「Portable-USB が使える」。**外部暗号レビュー**（v1.0 §6）と Kura の形式 PR の後。
レビューの発注・課金は owner の確認。

### 7. OOB ペアリングと custody export

**scope:** 受入端末の公開鍵への out-of-band ペアリング（`portable-usb-custody.md` §3）と、明示的な export（§4）を実装する。
1 回の確認、append-only の監査 record、秘密値を記録しないこと、輸送・保存の暗号化、拒否の状態を実装する。

**受入の証拠:** nonce の再送と期限切れが拒否されること。監査 record を書けないときに export しないこと。
error message と log に秘密値が出ないこと（試験で検査する）。

**gate まで主張しないこと:** 「失効できる」。export も pairing も capability を失効させない（§6）。
**owner 確認対象の鍵操作**であり、実行はこの issue の実装後、別の承認で行う。

### 8. forest root のローテーションと copy 移行

**scope:** 紛失時の forest root のローテーション、再 seal、copy 移行、drill（`portable-usb-custody.md` §6・§7）を実装する。
手順 1〜6 と、rollback の境界（5 の前後）を code と手順書にする。

**受入の証拠:** drill を捨ててよい fixture で通すこと。古い root の失効が 5 の後であること。rollback が 5 の前後で
変わること。**実機の USB と実鍵では行わない。**

**gate まで主張しないこと:** 「紛失しても復旧できる」。所要時間は見積り（§6.3）で、測定ではない。
owner の確認（鍵操作）と、検証役の R2 以上の確認が前提。

### 9. §4.8 の namespace closure の退避と Stage 1

**scope:** 退避の単位を file content block にし、directory node と forest node を保護する。repack / compaction を実装し、
Stage 1（`cache/` の LRU、W1 + W3 の後）を実装する（`scalable-sync-design.md` §4.8）。

**受入の証拠:** `cache/` の block だけが消え、directory / forest node が残ること。部分退避を拒否すること。
repack の後に CID が一致すること。N4（directory entry の欠落）が検出されること。

**gate まで主張しないこと:** 「`packs/` を退避できる」。それは Stage 2（10 の後）。

### 10. 検証役の連携

**scope:** Drive が検証役の記録を使う経路を実装する。R2 以上の `policy-satisfied`、`observedAt` / `validUntil` の検査、
version id による Object Lock の確認、`storeClass 3` を独立ドメインに数えないこと、`retentionUntil=0` の拒否
（`scalable-sync-design.md` §4.8 の Stage 2）。

**受入の証拠:** 期限切れ・`retentionUntil=0`・inventory 外・Object Lock の不一致・`storeClass 3` だけの R2 を拒否する
負の対照（N8〜N10）。検証役を止めると状態が「不明」になり、退避が止まること。

**gate まで主張しないこと:** 「受領証が保持を証明する」。受領証は保持の約束ではなく、`policy-satisfied` は検証役が出す
（`app-itonami-yataverse/docs/retention-design.md` §5・§6）。検証役の運用主体は owner が決める（同 §16）。

### 11. P6 の bounded batch 同意

**scope:** canonical broker（shared runtime）に bounded batch を実装する。1 回の承認が endpoint・最大 N・最大 B を名指し、
拒否は 0 件、append-only の監査 record、期限、N / B の消費（再試行と readback を含む）、revoke
（`scalable-sync-design.md` §4.9）。

**受入の証拠:** 拒否で request が 0 件であること。N / B を使い切った後に追加の承認が要ること。revoke の後に送らないこと。
製品側に同意の実装が無いこと。

**gate まで主張しないこと:** 「無人の大量退避ができる」。既定の N / B・期限は owner の決定で、実装が入るまで製品は使えない。

### 12. サーバの単調 epoch

**scope:** Yataverse の入口に、lease と commit の単調 epoch を持たせ、古い `base-epoch` の commit を型付きで拒否する
（`scalable-sync-design.md` §4.8 の巻き戻し）。

**受入の証拠:** 古い `base-epoch` の commit が拒否され、黙って rebase しないこと。同じ内容の再送が冪等であること。

**gate まで主張しないこと:** 「巻き戻しを検出できる」。**オフライン・媒体全体の別端末への巻き戻しは検出できない**（仕様と UI の両方に明記）。

### 13. P0 の測定

**scope:** [p0-measurement-plan.md](p0-measurement-plan.md) の §4〜§7 を 3 OS で実施する。fixture は loopback の image、一時 volume、
fake store、emulator、捨ててよい VM だけを使う。破壊試験は fixture だけで行い、ラベルを付ける。

**受入の証拠:** §2 の形式の記録。`unknown` の項目とその理由。負の対照 N1〜N11 の結果。**実機の USB を使っていないこと。**

**gate まで主張しないこと:** 「測定済み」。この PR では 1 件も実行していない。

### 14. 2 製品構成の UI/UX

**scope:** Drive host の状態（host が無い、`busy`、`volume-absent`、`read-only`、fork、オフライン巻き戻しの「検出できない」）、
Portable の unlock、custody の確認、bounded batch の同意を UI にする。**所有者は Gamen**（AWAI-4118 v1.0 §7 の 3）。ここは
cross-ref だけで、UI を設計しない。

**受入の証拠:** `drive-host-ipc.md` §9 の状態を区別して表示すること。オフライン巻き戻しを「検出できない」と表示すること。

**gate まで主張しないこと:** 「UI が完成した」。この issue は Gamen の設計を前提にし、UI の正本を持たない。
