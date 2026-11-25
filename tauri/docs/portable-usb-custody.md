# Portable-USB の暗号と custody

- Status: **設計（未実装）**
- 日付: 2026-11-25
- Tracking: [AWAI-4118](https://linear.app/kotobalabs/issue/AWAI-4118)（Orca Item #302）
- 根拠: AWAI-4118 確定設計 v1.0 §2・§6、Kagishi P0-4、`network-awai-apex/docs/awai-disk-format.md` D7・D8。Portable-USB の形式（v3 / hosted-file 暗号化単一コンテナ）の改訂は Kura が持つ（v1.0 §7 の 1）。**この文書は形式を確定しない。** 鍵の木・custody・移行・失効の設計だけを持つ。
- 関連: [drive-host-ipc.md](drive-host-ipc.md)、[scalable-sync-design.md](scalable-sync-design.md) §4.8・§4.9、[p0-measurement-plan.md](p0-measurement-plan.md)、[awai-4118-child-issues.md](awai-4118-child-issues.md)
- **未実装。** 鍵の生成・ローテーション・export は行っていない。実機の USB 媒体への書き込みもしない。

## 1. 2 つの鍵の木

| 木 | 何を守るか | 暗号 | 正本 |
|---|---|---|---|
| container | 媒体の bytes（label、keyslot、WAL、地図、checkpoint、Pack） | `AES-256-GCM-SIV` を推奨（v1.0 §2） | Kura の形式改訂（v3） |
| E2EE | 利用者の平文（WNFS の private forest と file content） | WNFS の `XChaCha20-Poly1305`（`scalable-sync-design.md` §2） | `wnfs-cljc` と WNFS の実装 profile |

**WNFS の write capability を container の鍵で包まない。** `awai-disk-format.md` D8 は「ディスク暗号化と E2EE を分ける」と定めており、この分離を保つ。理由:

- container の鍵は「媒体を手にした者が bytes を読めるか」を決める。E2EE の capability は「どの端末が forest に書けるか」を決める。**守る対象も、失効の方法も違う。**
- 包むと、container の復旧鍵（媒体に書く）が forest の書き込み権限を復元してしまう。v1.0 §2 の「container 復旧鍵では E2EE は復号不可」が成り立たなくなる。
- 包むと、媒体の紛失で container 鍵をローテーションしたときに capability も失効したと誤解される（§6 のとおり失効しない）。

**既定は USB に E2EE の秘密を置かない。** 媒体には container の keyslot だけを置き、forest の capability は端末の custody（`custody.cljc` の既存の包み）に残す。別端末へ渡すときだけ §3 / §4 の経路を使う。

## 2. container の keyslot

### 2.1 slot の種類

| slot | 何で開くか | 必須か |
|---|---|---|
| passphrase | Argon2id で導いた鍵 | 任意（user が設定した場合） |
| recovery key | 256 bit の復旧鍵 | **必須**（v1.0 §2） |
| Passkey | WebAuthn の credential | 任意（端末が対応する場合） |

- slot の数と id は **未決定**。Kura の形式（v3）で固定する。
- Passkey から keyslot の鍵を導く方法（PRF の利用可否を含む）は **未決定**。**外部暗号レビュー**（v1.0 §6）で決める。**未決定の方式を実装の既定にしない。**

### 2.2 AAD

- AAD は **canonical DAG-CBOR** の slot header とし、少なくとも次を束縛する: `v`（format 版）、`lineageId`、`slot`、`kdf`（`alg`、`m`、`t`、`p`、`salt`）、`wrap`（`alg`、nonce）。
- **AAD の対象は slot header 全体から、その slot の wrapped key と tag を除いたもの**とする。これで KDF 引数・slot 番号・lineage を後から差し替えられない。
- 長さ・整数の表現・並びは **未決定**。3 OS で同じ bytes になる canonical 形式を 1 つに固定し、**golden corpus** で検証する（v1.0 §6）。corpus が無い間は「cross-OS で読める」と主張しない。

### 2.3 下降の拒否

- slot header に**最小の writer 版**（`minWriterVersion`）を記録する。
- 読み手は、自分の版が `minWriterVersion` より低ければ **read-only** で開く。KDF 引数がその版の最小より弱い slot は**使わない**（拒否であって、弱い方で開くのではない）。
- `minWriterVersion` は AAD に含める。**下げる操作を作らない。** 下げられるなら下降の拒否は意味を持たない。

### 2.4 lease と epoch

- lease record（`host-id`、`boot-id`、`epoch`、`heartbeat`、`expiry`、`state`）は媒体に置き、**volume 鍵由来の MAC** を付ける（v1.0 §4）。
- MAC の対象は lease の canonical bytes 全体。MAC が合わない lease は「無い」として扱い、書き込みを止める（D7: 古い状態へ黙って戻らない）。
- `epoch` は root と lease の両方で照合する。**同じ lineage + 同じ instance + 競合 epoch は fork**（§5）。

### 2.5 OS Keychain の blob を媒体にコピーしない

- 端末の Keychain / Keystore にある blob（unlock、canonical wrapped key record）を USB に複製しない。
- 媒体に置いてよいのは container の keyslot と、MAC 付きの lease / epoch だけ。**端末の custody を媒体へ移したと主張しない。**
- これは `encrypted-sync.md` の「device custody」の境界を弱めないための規則である。

## 3. 可搬 custody (a): 受入端末の公開鍵への out-of-band ペアリング

受入端末の公開鍵へ capability を包んで渡す経路。**container の鍵とは別**である。

### 3.1 前提

- 受入端末に Drive が入っていて、初期化済みの identity と自分の秘密鍵を持っている。
- 受入端末の公開鍵と keyId を、**帯域外の経路**で送り手に渡せる（QR か、画面の短い文字列か。具体は **未決定**）。
- 両方の端末で user が操作している（片方だけの操作で完結しない）。
- 受入端末は、**container を開く手段を別に持つ**（復旧鍵か passphrase）。capability だけでは media の bytes を復号できない。

### 3.2 交換するもの

| 方向 | 内容 |
|---|---|
| 受入 → 送り手 | 公開鍵、keyId、device instance id、nonce |
| 送り手 → 受入 | nonce に束縛した capability の包み（受入端末の公開鍵で暗号化）、capability の id（値ではない）、送り手の device id、期限 |

- capability の**値**は包みの中だけに存在し、画面・ログ・監査記録に出さない。
- 送り手は、包む前に受入端末の公開鍵の fingerprint を user に確認させる（**短い照合文字列**の方式は未決定）。

### 3.3 期限と単回

- nonce は 1 回だけ使う。期限は短くする（**値は未決定**）。
- 同じ nonce の再送、期限切れ、別の受入端末の鍵での開封は拒否する。
- 成功したら、送り手の側に「この capability をこの device に渡した」record を残す（**値は残さない**）。

### 3.4 監査の field

`pairingId`、送り手の device id、受入端末の公開鍵の digest、capability の id、開始時刻、結果（成功 / 拒否の理由）、nonce の digest。**秘密値（capability、秘密鍵、nonce そのもの）を記録しない。**

### 3.5 失敗と rollback

- 包みの生成・送信・受入端末の ack のどれかが失敗したら、**移譲は成立していない**として扱う。送り手の capability はそのまま有効。
- 受入端末は、ack を返す前に capability を使わない。途中までの包みを保存しない。
- **rollback（移譲の取り消し）は capability の失効ではない。** 移譲後に取り消すには forest root のローテーションが要る（§6）。

### 3.6 証明しないもの

- 受入端末が侵害されていないこと。
- 媒体を物理的に手にしていること（capability はネットワーク経由で渡せる）。
- user の意図（OOB 経路と端末の操作を誰がしたかまでは証明しない）。
- 送り手の capability が失効したこと（**しない**。§6）。

## 4. 可搬 custody (b): 明示的な portable custody export

### 4.1 何をするか

- user の**1 回の明示的な確認**で、portable custody（capability と、その復元に要る最小の情報）を 1 つのパッケージとして出力する。
- **オーナー確認対象の鍵操作**である（v1.0 §6）。この文書は設計だけで、実行しない。実行は別 gate（実装 + owner の承認）。
- **append-only の監査 record** を書く。record を書けないなら export しない（記録できない操作を通さない）。

### 4.2 秘密値を記録しない

- 監査 record とログに書くのは: `exportId`、実行者（host の clientId）、対象の capability の id、出力先の種類（removable / file）、時刻、結果。
- **capability の値、秘密鍵、passphrase、復旧鍵を、ログ・監査 record・error message に書かない。** error は code と一般化した文言だけにする。
- 画面にも値を出さない（QR に埋め込む場合も、表示は 1 回だけで、再表示しない。**方式は未決定**）。

### 4.3 user に警告すること

- export したパッケージは **forest への書き込み権限**である。持っている者は、復号できる範囲で読み書きできる。
- パッケージを失えば、その経路での復元はできない。**container の復旧鍵では代替できない**（別の木）。
- パッケージを渡した相手を、後から個別に失効させる仕組みは無い（§6 の全体ローテーションだけ）。
- 端末を手放す前に export した場合、その端末の capability は**自動では失効しない**。

### 4.4 輸送と保存の保護

- パッケージは**必ず暗号化する**。方式は (i) 受入端末の公開鍵へ包む、(ii) user の passphrase から Argon2id で導いた鍵で包む、のいずれか（**既定は未決定**。外部暗号レビューで決める）。
- 平文のパッケージを一時ファイルに書かない。書く必要がある場合は、書いた直後に消し、消えたことを確認する（**方式は未決定**）。
- 出力先が**同じ container の中**、または**平文で共有される領域**なら拒否する。

### 4.5 拒否する状態

| 状態 | 結果 |
|---|---|
| owner の確認が無い / 2 回目の確認が無い | 拒否。何も出力しない |
| 監査 record を書けない | 拒否 |
| 出力先が同じ container の中、または平文の共有領域 | 拒否 |
| passphrase が空、または基準未満 | 拒否（基準は **未決定**） |
| 出力中に失敗 | 途中のファイルを残さない。残ったら削除し、削除できなければ「失敗し、残っている」と報告する |
| capability が既に失効している（root ローテーション後） | 拒否。古い capability を export しない |

## 5. 移動・clone・fork

v1.0 §4 の規則を、媒体の運用に写す。

| 観測 | 判定 | 開けるか |
|---|---|---|
| 同じ `volume-lineage-id` + 同じ `volume-instance-uuid` + 新しい epoch、かつ前の lease が期限切れか明示 release | **正当な移動** | 開ける（RW） |
| 同じ lineage + **違う** instance | **正当な clone** | 開ける。別の volume として扱う |
| 同じ lineage + 同じ instance + 競合する epoch（同時、または lease が生きている） | **fork** | **read-only で停止**。epoch を進めない |
| `dd` で複製した同じ instance の 2 つ | 上の fork の規則に当たる | 片方だけが RW。**運用のヒントであって安全な経路ではない** |

- **`dd` の写しを「バックアップ」として扱わない。** instance が同じなので、両方を開くと fork になる。fork の検出は「同じ lineage + 同じ instance + 競合 epoch」で行い、**自動で片方を選ばない**（v1.0 §4）。
- clone は instance が違うので開ける。ただし clone した媒体を両方書き換えると、後で forest の merge が要る（`scalable-sync-design.md` §4.4 の merge は未実装）。**clone を「同期の代替」と呼ばない。**
- 移動の正当性は lease の期限切れ / release で判定する。lease の MAC が合わない場合は「lease が無い」として read-only（§2.4）。

## 6. 紛失と失効

### 6.1 container 鍵のローテーションでは capability は失効しない

- container の鍵を替えても、WNFS の capability は別の木（§1）なので**有効なまま**である。
- したがって、媒体を失ったときの失効は **forest root のローテーション + copy 移行**でしか行えない（v1.0 §2・§6）。
- **capability が無い媒体は「復号不能」**である。container を開けても平文は読めない。**「読取専用」と書かない**（v1.0 §2）。

### 6.2 手順

1. **新しい forest root を作る。** 現在の root から到達する directory node と forest HAMT node、および file content block を、新しい content key で再暗号化する（WNFS は内容が変わるたびに content key を作り直す。`scalable-sync-design.md` §4.4）。
2. **再 seal した block を新しい Pack として送る。** 既存の CID は内容が変わるので再利用できない。
3. **readback で全 Pack を検証する**（`scalable-sync-design.md` §4.7 の `committed` 条件）。
4. **新しい root を control record に書く。** これで新しい capability が正本になる。
5. **古い root を失効させる。** 古い lease を renew しない → 期限切れ / release。検証役の `policy-satisfied` を確認してから、古い root と古い block を配信から外す。
6. **古い block を回収する。** lease の解放と、同じ CID を指す他の lease が無いことの確認後（`app-itonami-yataverse/docs/retention-design.md` §10 の解放条件）。**回収は別 gate。**

- 1〜4 の間、古い capability は**まだ有効**である。したがって「失効した」と言えるのは 5 の後。**手順の途中で「失効済み」と表示しない。**
- rollback: 4 の後でも、古い root の block が残っていれば古い root に戻せる。5 の後は戻せない（古い lease を切るため）。**5 の前に rollback の判断を確定する。**

### 6.3 費用と所要時間（見積り）

**見積り。測定値ではない。** 前提:

- 対象は v1.0 §5 の 64 GiB 級 profile。
- block は WNFS の暗号文 block 262,144 B（`scalable-sync-design.md` §2）。
- Pack は 32 MiB（§4.5 の目標値）。
- 1 Pack につき PUT 1 回 + readback GET 1 回。
- 回線は上り下りとも同じ実効速度とし、再送・再開・CPU 時間を含めない。

算術:

- 64 GiB = 64 × 1,073,741,824 = **68,719,476,736 B**
- block 数 = 68,719,476,736 ÷ 262,144 = **262,144**
- Pack 数 = 68,719,476,736 ÷ 33,554,432 = **2,048**
- 転送量 = PUT + GET = **137,438,953,472 B**
- 20 Mbit/s（2.5 MB/s）: 137,438,953,472 ÷ 2,500,000 ≈ 54,976 s ≈ **15.3 時間**
- 100 Mbit/s（12.5 MB/s）: 137,438,953,472 ÷ 12,500,000 ≈ 10,995 s ≈ **3.1 時間**

含まないもの:

- 262,144 block の再 seal（XChaCha20-Poly1305）と `NameAccumulator` の再計算。**未測定**（[p0-measurement-plan.md](p0-measurement-plan.md)）。
- client 側の hash と検証。
- 検証役の確認と lease の処理。
- broker の承認回数: 4,096 操作を 1 承認 128 操作の上限で割ると 32 回、bytes で 64 GiB を 1 GiB の上限で割ると 64 回。**64 回が支配的**（[bounded-batch-consent-proposal.md](bounded-batch-consent-proposal.md) の提案値。出荷値ではない）。

### 6.4 gate

- **owner の確認**（鍵操作）。
- 検証役の `policy-satisfied` が **R2 以上**で、判断時点で `observedAt` / `validUntil` が有効（`app-itonami-yataverse/docs/retention-design.md` §6）。期限切れは「不明」として拒否。
- すべての block が lease の inventory 内（同 §4）。
- Object Lock を **version id** で確認し、`COMPLIANCE` で期限が lease の `retentionUntil` 以上（同 §8、§16 の S1-2a）。
- `retentionUntil=0` の受領証では回収しない（同 §5）。
- readback の全数一致（`scalable-sync-design.md` §4.7）。

## 7. copy-only の移行

- プロファイル（Local-Meta / Portable-USB）は volume 作成時に固定する（v1.0 §2）。**その場で切り替えない。**
- 移行は**新しい volume**（新しい `volume-instance-uuid`）を作り、検証済みの複製を書く。
- 移行の間も、元の volume は**そのまま読める**。rollback は「元の volume を使い続ける」で、データを戻す操作を要しない。
- 移行後に元の volume を消すのは、新しい volume の readback と（該当する場合）検証役の確認が済んだ後。**上書き変換をしない**（`awai-disk-format.md` D2）。
- 新しい volume の container 鍵は新しく作る。E2EE の capability は (i) 同じ forest を運ぶ（copy）か、(ii) ローテーションする（§6）。**どちらかを migration の記録に残す。**

## 8. 覆わないこと・証明しないこと

- **hardware-backed custody。** Passkey は user presence の確認で、secure element による鍵の保持を証明しない。Keystore の鍵が hardware-backed かは端末依存で、**未確認**。
- **物理的な FBE / FileVault / LUKS。** 媒体の盗難に対する OS の暗号化は OS が持つ（`awai-disk-format.md` §9）。Awai は持たない。
- **オフライン・媒体全体の巻き戻しの検出。** 検出できない（v1.0 §6）。同一端末は high-water（`volume-lineage-id → 最大 epoch / session id`）で検出するが、**別端末への全体巻き戻しは検出しない**。
- **外部暗号レビュー。** v1.0 §6 の順序（R0 固定 → 外部レビュー → R3 出口 → R2 形式凍結）の前段階であり、レビューは未発注・未課金（この task の範囲外）。
- **Kura の形式（v3）の bytes。** この文書は形式を確定しない。AAD の canonical 形式と golden corpus は Kura の改訂と共通にする。
- **性能。** §6.3 は見積りで、実測ではない。

以上の限界は AWAI-4118 v1.0 §6（鍵・暗号・可搬 custody・巻き戻し）と §2（プロファイル）の範囲を広げない。

## 9. 未決定

1. **Argon2id の引数**（m / t / p、salt の長さ）と、`minWriterVersion` ごとの最小値。外部暗号レビューと P0 で決める。
2. **Passkey の使い方**（PRF の可否、credential から keyslot の鍵を導く方法）。
3. **slot の数と id**、および recovery key の必須の強制方法。
4. **AAD の canonical bytes**（整数・長さの表現）と golden corpus。3 OS で一致するまで「cross-OS」と主張しない。
5. **OOB 経路**（QR か短い文字列か）と、fingerprint の照合方式・期限の値。
6. **export パッケージの暗号化の既定**（受入端末の公開鍵か passphrase か）と、passphrase の最低基準。
7. **監査 record の保持期間と暗号化**（`drive-host-ipc.md` §6.3 と同じ置き場所）。
8. **clone した媒体の merge**（`scalable-sync-design.md` §4.4 の merge は未実装）。clone を同期の代替にできるかは、この実装の後。
