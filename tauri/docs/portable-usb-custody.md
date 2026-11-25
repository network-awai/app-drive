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
| container | 媒体の bytes（label、keyslot、WAL、地図、checkpoint、Pack） | `AES-256-GCM-SIV` を推奨（v1.0 §2） | `awai-disk-format.md` §21（v3、main） |
| E2EE | 利用者の平文（WNFS の private forest と file content） | WNFS の `XChaCha20-Poly1305`（`scalable-sync-design.md` §2） | `wnfs-cljc` と WNFS の実装 profile |

**WNFS の write capability を container の鍵で包まない。** `awai-disk-format.md` D8 は「ディスク暗号化と E2EE を分ける」と定めており、この分離を保つ。理由:

- container の鍵は「媒体を手にした者が bytes を読めるか」を決める。E2EE の capability は「どの端末が forest に書けるか」を決める。**守る対象も、失効の方法も違う。**
- 包むと、媒体の keyslot を開けた者が forest の書き込み権限まで得てしまう。v1.0 §2 の「container 復旧鍵では E2EE は復号不可」が成り立たなくなる。
- 包むと、媒体の紛失で container 鍵をローテーションしたときに capability も失効したと誤解される（§6 のとおり失効しない）。

**既定は USB に E2EE の秘密を置かない。** 媒体には container の keyslot だけを置き、forest の capability は端末の custody（`custody.cljc` の既存の包み）に残す。別端末へ渡すときだけ §3 / §4 の経路を使う。

**媒体に置くのは、復旧鍵で開く wrapped keyslot のみ。生の 256 bit 復旧鍵は媒体外（別保管）に置く。** この区別を崩さない（§2.1・§2.5）。

## 2. container の keyslot

### 2.1 slot の種類

| slot | 何で開くか | 必須か |
|---|---|---|
| passphrase | Argon2id で導いた鍵 | 任意（user が設定した場合） |
| recovery key | 256 bit の復旧鍵（**媒体には置かず、媒体外で別保管**。媒体に置くのは、この鍵で開く wrapped keyslot だけ） | **必須**（v1.0 §2） |
| Passkey | WebAuthn の credential | 任意（端末が対応する場合） |

- slot の数と id は **未決定**。`awai-disk-format.md` §21（main）で固定する。
- Passkey から keyslot の鍵を導く方法（PRF の利用可否を含む）は **未決定**。**外部暗号レビュー**（`awai-disk-format.md` §17.2）で決める。**未決定の方式を実装の既定にしない。**

### 2.2 AAD

- AAD は **canonical DAG-CBOR** の slot header とし、少なくとも次を束縛する: `format`（format 文字列）、`profile`（`portable-usb`）、`v`（format 版）、`volume-lineage-id`、`slot`、`kdf`（`alg`、`m`、`t`、`p`、`salt`）、`wrap`（`alg`、nonce）。**`awai-disk-format.md` §21.4 の keyslot の AAD と同じ分類子を使う**（raw の keyslot とは format 文字列も AAD も共有しない）。
- **AAD の対象は slot header 全体から、その slot の wrapped key と tag を除いたもの**とする。これで KDF 引数・slot 番号・lineage・profile を後から差し替えられない。
- **sidecar は untrusted hint である。** 全欄に MAC を付けるが（`awai-disk-format.md` §21.2）、値は container の認証済み領域と、アプリに埋め込んだ下限に照合する。sidecar の値だけで下降の可否を決めない。
- 長さ・整数の表現・並びは **未決定**。3 OS で同じ bytes になる canonical 形式を 1 つに固定し、**golden corpus** で検証する（`awai-disk-format.md` §21.4・§17.2）。corpus が無い間は「cross-OS で読める」と主張しない。

### 2.3 下降の拒否

- slot header に**最小の writer 版**（`minWriterVersion`）を記録する。
- 読み手は、自分の版が `minWriterVersion` より低ければ **read-only** で開く（v3 の外側を読める場合）。KDF 引数がその版の最小より弱い slot は**使わない**（拒否であって、弱い方で開くのではない）。
- 旧いアプリが v3 の外側（sidecar・container 外殻）または形式を読めない場合は、**`stopped`** に固定する（`version-unsupported` を返し、読まない・書かない）。**Drive host の契約として `stopped` を採る**（`awai-disk-format.md` §21.4 は R0 固定待ちだが、外側を読めない媒体は `read-only` にできないため）。**`read-only` は「v3 を読めるが、`minWriterVersion` が自分の版より新しくて書けない」場合だけ**に使う。**形式を読めない旧アプリを `read-only` の例にしない。**
- `minWriterVersion` は AAD に含める。**下げる操作を作らない。** 下げられるなら下降の拒否は意味を持たない。

### 2.4 lease と epoch

- lease record（`host-id`、`boot-id`、`epoch`、`heartbeat`、`expiry`、`state`）は媒体に置き、**volume 鍵由来の MAC** を付ける（v1.0 §4）。
- MAC の対象は lease の canonical bytes 全体。MAC が合わない lease は「無い」として扱い、書き込みを止める（D7: 古い状態へ黙って戻らない）。
- `epoch` は root と lease の両方で照合する。**同じ lineage + 同じ instance + 競合 epoch は fork**（§5）。

### 2.5 OS Keychain の blob を媒体にコピーしない

- 端末の Keychain / Keystore にある blob（unlock、canonical wrapped key record）を、**暗号化 container 本体にも sidecar にも、平文でコピーしない。** sidecar は MAC 付きだが欄は平文であり、鍵素材の置き場所ではない。
- 媒体に置いてよいのは、復旧鍵や passphrase で開く **wrapped keyslot** と、MAC 付きの lease / epoch だけ。**生の復旧鍵を置かず、端末の custody を媒体へ移したとも主張しない。**
- これは `encrypted-sync.md` の「device custody」の境界を弱めないための規則である。

### 2.6 復旧鍵の生命周期

`awai-disk-format.md` §15.4 が R0 で固定する項目に合わせる。

- **生成:** 256 bit。**媒体の外**（別保管）で生成し、**1 回だけ表示**する。表示の後に、利用者が正しく記録できたかを検証する。
- **差し替え:** 新しい復旧鍵を作り、**新しい keyslot を追加してから**古い slot を無効化する（順序を逆にしない）。
- **失効:** 古い keyslot を無効化し、AAD の版を上げる。**媒体の外の鍵を消しても、既に export されたコピーは失効しない**（§4）。
- **下降拒否:** 版・KDF 引数の下降を拒否する（§2.3）。
- **媒体外保管:** 生の鍵を媒体に置かない（§1・§2.5）。OS Keychain の blob も媒体にコピーしない。
- 工場出荷リセットで keyslot を壊すかは `awai-disk-format.md` §15.4 のとおり**未決定**。**「リセット後は誰も読めない」と主張しない。**

### 2.7 Passkey の同期（iCloud / Google）の脅威モデル

- 同期される Passkey は、**同じアカウントの他の端末でも使える**。「この端末だけ」の custody ではない。
- 許可 / 警告 / 禁止のどれにするか、および PRF を使って keyslot の鍵を導けるかは **未決定**で、**外部暗号レビュー**の対象（`awai-disk-format.md` §17.2）。
- 同期 Passkey を使う場合も、**復旧鍵を必須**にし、Passkey だけに依存しない。
- 端末の紛失時に同期 Passkey を失効させる経路（アカウント側の操作）を、**この文書は保証しない**。

## 3. 可搬 custody (a): 受入端末の公開鍵への out-of-band ペアリング

受入端末の公開鍵へ capability を包んで渡す経路。**container の鍵とは別**である。

### 3.1 前提

- 受入端末に Drive が入っていて、初期化済みの identity と自分の秘密鍵を持っている。
- 受入端末の公開鍵と keyId を、**帯域外の経路**で送り手に渡せる。QR に載せてよいのは **one-time の公開鍵か短命の secret だけ**で、capability を載せない。
- 両方の端末で user が操作している（片方だけの操作で完結しない）。
- 受入端末は、**container を開く手段を別に持つ**（復旧鍵か passphrase。生の復旧鍵は媒体外で別保管）。capability だけでは media の bytes を復号できない。

### 3.2 交換するもの

| 方向 | 内容 |
|---|---|
| 受入 → 送り手 | 公開鍵、keyId、device instance id、nonce、**nonce への署名（possession proof）** |
| 送り手 → 受入 | nonce に束縛した capability の包み（受入端末の公開鍵で暗号化）、capability の id（値ではない）、送り手の device id、期限、pairingId |

- capability の**値**は包みの中だけに存在し、画面・ログ・監査記録に出さない。
- 包みの AAD は、`pairingId`、送り手の device id、受入端末の keyId と公開鍵の digest、nonce、期限、capability の id と版、`profile` を束縛する。**AAD に無い欄を後から足さない。**
- 送り手は、包む前に受入端末の公開鍵の fingerprint を user に確認させる（**短い照合文字列**の方式は未決定）。

### 3.3 期限と単回

- nonce は 1 回だけ使う。期限は短くする（**値は未決定**）。
- **nonce への署名を検証できない相手には包まない**（公開鍵の提示だけでは possession を証明しない）。
- used nonce は、端末の **device-protected な領域**に期限まで保存する。同じ nonce の再送、期限切れ、別の受入端末の鍵での開封は拒否する。
- 成功したら、送り手の側に「この capability をこの device に渡した」record を残す（**値は残さない**）。
- 成功時に **copy か move か**を user に明示する。copy は送り手の capability を残す。move は送り手側で以後使わないことを記録するが、**送り手が既に持つコピーは消せない**。

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
- **append-only の監査 record** を書く。record を書けないなら export しない（記録できない操作を通さない）。監査 record は **hash chain + keyed MAC** で完全性（追記・削除の検出）を保つ。Portable-USB では**媒体に置かない**（端末の control record）。媒体と一緒に運ぶ必要がある場合は、別の暗号化された記録にする（形式は未決定）。

### 4.2 秘密値を記録しない

- 監査 record とログに書くのは: `exportId`、実行者（host の clientId）、対象の capability の id、出力先の種類（removable / file）、時刻、結果。
- **capability の値、秘密鍵、passphrase、復旧鍵を、ログ・監査 record・error message に書かない。** error は code と一般化した文言だけにする。
- QR に載せてよいのは **one-time の公開鍵か短命の secret だけ**で、capability を載せない。passphrase は QR と同じ経路で渡さず、別の経路にする。画面にも秘密値を再表示しない。

### 4.3 user に警告すること

- export したパッケージは **forest への書き込み権限**である。持っている者は、復号できる範囲で読み書きできる。
- パッケージを失えば、その経路での復元はできない。**container の復旧鍵では代替できない**（別の木）。
- パッケージを渡した相手を、後から個別に失効させる仕組みは無い（§6 の全体ローテーションだけ）。
- 端末を手放す前に export した場合、その端末の capability は**自動では失効しない**。

### 4.4 輸送と保存の保護

- パッケージは**必ず暗号化する**。方式は (i) 受入端末の公開鍵へ包む、(ii) user の passphrase から Argon2id で導いた鍵で包む、のいずれか（**既定は未決定**。外部暗号レビューで決める）。
- **平文のパッケージを一時ファイルに書かない（原則禁止）。** 例外は、swap の無い `memfd` または `tmpfs` だけとする。`unlink` は「消去」ではない（bytes が媒体に残りうる）ので、消したと主張しない。
- 出力先は **device UUID / mount 単位**で判定する。同じ container の中、同期 folder、network share（SMB / NFS）は拒否する。

### 4.5 拒否する状態

| 状態 | 結果 |
|---|---|
| owner の確認が無い / 2 回目の確認が無い | 拒否。何も出力しない |
| 監査 record を書けない | 拒否 |
| 出力先が同じ container の中、同期 folder、network share、または device UUID を確認できない mount | 拒否 |
| passphrase が空、または基準未満 | 拒否（基準は **未決定**） |
| 出力中に失敗 | 途中のファイルを残さない。残ったら削除し、削除できなければ「失敗し、残っている」と報告する |
| capability が既に失効している（root ローテーション後） | 拒否。古い capability を export しない |

## 5. 移動・clone・fork

v1.0 §4 の規則を、媒体の運用に写す。

| 観測 | 判定 | 開けるか |
|---|---|---|
| 同じ `volume-lineage-id` + 同じ `volume-instance-uuid` + 新しい epoch、かつ前の lease が期限切れか明示 release | **正当な移動** | 開ける（RW） |
| 同じ lineage + **違う** instance | **正当な clone** | 開ける。別の volume として扱う |
| 同じ lineage + 同じ instance + 競合する epoch（同時、または lease が生きている） | **fork** | **停止**（`awai-disk-format.md` §14。既定では読みも拒否。`inspect` だけ許す）。epoch を進めない |
| `dd` で複製した同じ instance の 2 つ | 上の fork の規則に当たる | **停止**。片方を自動で選ばない。**運用のヒントであって安全な経路ではない** |

**「停止」の語義（`awai-disk-format.md` §14）:** 書き込みと回復を拒否し、**読み出しも拒否する**。診断用の読み取り専用 open（`inspect`）だけが、明示の操作で許される。**`read-only`（grade C、または v3 を読めるが `minWriterVersion` が新しくて書けない場合）と混ぜない。** デバイス集合の不一致・WAL の鎖切れ・root slot が成立しない場合・fork は、すべて「停止」である。

- **`dd` の写しを「バックアップ」として扱わない。** instance が同じなので、両方を開くと fork になる。fork の検出は「同じ lineage + 同じ instance + 競合 epoch」で行い、**自動で片方を選ばない**（v1.0 §4）。
- clone は instance が違うので開ける。ただし clone した媒体を両方書き換えると、後で forest の merge が要る（`scalable-sync-design.md` §4.4 の merge は未実装）。**clone を「同期の代替」と呼ばない。**
- 移動の正当性は lease の期限切れ / release で判定する。lease の MAC が合わない場合は「lease が無い」として**停止**する（`awai-disk-format.md` §18.3 K-1）。

## 6. 紛失と失効

### 6.1 container 鍵のローテーションでは capability は失効しない

- container の鍵を替えても、WNFS の capability は別の木（§1）なので**有効なまま**である。
- したがって、媒体を失ったときの失効は **forest root のローテーション + copy 移行**でしか行えない（v1.0 §2・§6）。
- **capability が無い媒体は「復号不能」**である。container を開けても平文は読めない。**「読取専用」と書かない**（v1.0 §2）。
- **「失効」の定義（3 段階）:**
  1. **step 4（新 root の正本化）:** 旧 capability は**新 root のデータを復号できず、新 root への書込みもできない**（以後の新データのカットオフ）。
  2. **旧 root への書込み:** 旧 root が配信され lease が生きている間は可能。**server 強制は W4 以降**。それまでは fork / 放棄 branch として扱い、「旧 capability はもう書けない」と主張しない。
  3. **step 5（旧 root の配信停止・lease release）:** 旧 root を配信から外す。**暗号学的無効化ではない**（既に取得されたコピーは読める）。
- 要約: **以後の新 root のデータは読めない。過去のコピーは消せない。旧 root への書込みは W4 まで防げない。** 「失効 = すべてのコピーを回収した」ではない。

### 6.2 手順

1. **新しい forest root を作る。** 現在の root から到達する directory node と forest HAMT node、および file content block を、新しい content key で再暗号化する（WNFS は内容が変わるたびに content key を作り直す。`scalable-sync-design.md` §4.4）。
2. **再 seal した block を新しい Pack として送る。** 既存の CID は内容が変わるので再利用できない。
3. **readback で全 Pack を検証する**（`scalable-sync-design.md` §4.7 の `committed` 条件）。
4. **新しい root を control record に書く。** これで新しい capability が正本になる。**以後の新 root のデータのカットオフ**（旧 capability は新 root を復号できず、新 root へ書けない）。**旧 root への書込みは、旧 root が配信され lease が生きている間は可能**（server 強制は W4 以降）。
5. **旧 root を配信から外す。** 旧 lease を renew しない → 期限切れ / release。**新 root と新 Pack の lease** の検証役 `policy-satisfied`（R2 以上、`validUntil` 内）を確認する。**旧 root の `policy-satisfied` は失効の条件ではない。** これは**暗号学的無効化ではない**（既に取得されたコピーは読める）。
   - **失効の表明は 5 の後に行う。** 5 の前に表明すると、旧 root がまだ配信されている間に巻き戻せる（`awai-disk-format.md` §21.7 の (5)(6) と同じ順序）。
6. **旧 block を回収する。** lease の解放と、同じ CID を指す他の lease が無いことの確認後（`app-itonami-yataverse/docs/retention-design.md` §10 の解放条件）。順序は「配信から外す → lease を release → 期限 / lock の制約を待つ → 削除」。**回収は別 gate。**

- 1〜3 の間、旧 capability は**新 root を読めない**（新 root への書込みもできない）。**手順の途中で「すべて失効した」と表示しない。**
- **旧 revision（`previous` / skip ratchet）の履歴は旧 root の下に残る。** 新 root は旧 root を参照しない。旧 root を配信から外し、旧 lease を release して初めて、新しい client は旧 root に到達できない。**既に取得されたコピーは残る。**
- **remote-only の block も対象**である。手元に無い block は取得して再 seal する（再暗号化の対象は closure 全体で、手元の有無に依らない）。
- 残った端末は、新 capability を **OOB ペアリング（§3）で明示的に再ペアリング**する。再ペアリングしない端末を **device revocation list**（device id / capability id）に載せ、control record に保持する。**server 側の強制は W4（write proof）まで無い**ので、「一覧に載せた = サーバが拒否する」と主張しない。
- rollback: 新旧の root が併存する間は旧 root に戻せる。5 の後は旧 root を配信から外しているので、戻すには配信を戻す操作が要る（**「戻せない」ではなく「別の操作が要る」**）。既に取得されたコピーは、どの段階でも回収できない。**6 の回収の後は戻せない。**
- **失効は検証役の可用性に依存しない。** 新 root への切替（4）は即時で、検証役が止まっていても行える。検証役が要るのは、新 root / 新 Pack の保持の確認と、旧 block の回収（R2 の範囲）である。

### 6.3 費用と所要時間（見積り）

**見積り。測定値ではない。** 前提:

- 対象は v1.0 §5 の 64 GiB 級 profile。
- block は WNFS の暗号文 block 262,144 B（`scalable-sync-design.md` §2）。
- **転送の Pack は 12 MiB**（Yataverse の transport 上限。`encrypted-sync.md`、`awai-disk-format.md` §18.3 #10）。ローカルの Pack 目標 32 MiB（`scalable-sync-design.md` §4.5）とは別で、再送の単位は transport の上限で決まる。
- 1 Pack につき PUT 1 回 + readback GET 1 回。
- 回線は上り下りとも同じ実効速度とし、再送・再開・CPU 時間を含めない。

算術:

- 64 GiB = 64 × 1,073,741,824 = **68,719,476,736 B**
- block 数 = 68,719,476,736 ÷ 262,144 = **262,144**
- Pack 数 = 68,719,476,736 ÷ 12,582,912 = 5,461.33 → **5,462**
- 操作数 = PUT のみ **5,462**、PUT + readback GET **10,924**
- 転送量（readback 込み） = **137,438,953,472 B**
- 20 Mbit/s（2.5 MB/s）: 137,438,953,472 ÷ 2,500,000 ≈ 54,976 s ≈ **15.3 時間**
- 100 Mbit/s（12.5 MB/s）: 137,438,953,472 ÷ 12,500,000 ≈ 10,995 s ≈ **3.1 時間**

broker の承認回数（B の定義で変わる。数値は [bounded-batch-consent-proposal.md](bounded-batch-consent-proposal.md) の**提案値**で、出荷値ではない）:

- **B = PUT の bytes のみ**、1 GiB の hard cap: 68,719,476,736 ÷ 1,073,741,824 = **64 grants**。これは **readback を数えない下限**。
- **既定 48 MiB（PUT bytes）**: 68,719,476,736 ÷ 50,331,648 = 1,365.33 → **約 1,365 grants**。
- **B = PUT + readback の合計**、1 GiB の hard cap: 137,438,953,472 ÷ 1,073,741,824 = **128 grants**。
- 操作の上限 128 / grant: 10,924 ÷ 128 = 85.34 → **86 grants**。
- 上の中で最も厳しいのは既定 48 MiB の **約 1,365 grants** である。

含まないもの:

- 262,144 block の再 seal（XChaCha20-Poly1305）と `NameAccumulator` の再計算。**未測定**（[p0-measurement-plan.md](p0-measurement-plan.md)）。
- client 側の hash と検証。
- 検証役の確認と lease の処理。
- 12 MiB の transport 上限を引き上げた場合の再計算（引き上げは認可の後。`scalable-sync-design.md` §4.9）。

### 6.4 gate

- **owner の確認**（鍵操作）。
- **新 root / 新 Pack の lease** の `policy-satisfied` が **R2 以上**で、判断時点で `observedAt` / `validUntil` が有効（`app-itonami-yataverse/docs/retention-design.md` §6）。期限切れは「不明」として拒否。**失効の切替（新 root の正本化）自体は検証役に依存しない。**
- すべての block が lease の inventory 内（同 §4）。
- Object Lock を **version id** で確認し、`COMPLIANCE` で期限が lease の `retentionUntil` 以上（同 §8、§16 の **S1-2b**）。
- `retentionUntil=0` の受領証では回収しない（同 §5）。
- readback の全数一致（`scalable-sync-design.md` §4.7）。
- **device revocation list の更新**（残った端末の再ペアリングの記録、再ペアリングしない端末の id）。

## 7. copy-only の移行

- プロファイル（Local-Meta / Portable-USB）は volume 作成時に固定する（v1.0 §2）。**その場で切り替えない。**
- 移行は**新しい volume**（新しい `volume-instance-uuid`）を作り、検証済みの複製を書く。
- 移行の間も、元の volume は**そのまま読める**。rollback は「元の volume を使い続ける」で、データを戻す操作を要しない。
- 移行後に元の volume を消すのは、新しい volume の readback と（該当する場合）検証役の確認が済んだ後。**上書き変換をしない**（`awai-disk-format.md` D2）。
- 新しい volume の container 鍵は新しく作る。E2EE の capability は (i) 同じ forest を運ぶ（copy）か、(ii) ローテーションする（§6）。**どちらかを migration の記録に残す。**

## 8. 覆わないこと・証明しないこと

- **hardware-backed custody。** Passkey は user presence の確認で、secure element による鍵の保持を証明しない。Keystore の鍵が hardware-backed かは端末依存で、**未確認**。
- **物理的な FBE / FileVault / LUKS。** 媒体の盗難に対する OS の暗号化は OS が持つ（`awai-disk-format.md` §9）。Awai は持たない。
- **オフライン・媒体全体の巻き戻しの検出。** 検出できない（v1.0 §6）。同一端末の high-water は、キーを **`(volume-lineage-id, volume-instance-uuid)`**、値を **「見た最大の writer epoch + 最大の open 回数（単調）」**とする（`awai-disk-format.md` §4.5・§21.6）。**同一端末で、その端末が新しい epoch を見た後に限り検出する。別端末での `dd` 写しは検出しない。** キーに instance を入れるので、同じ端末の `dd` 写しの巻き戻しは検出でき、正当な clone（別の instance）は誤検出しない。`session id` は入れない（乱数で大小に意味がない。重複の検出は WAL・地図の session id の集合との照合で別に行う）。high-water は **volume / media の外**の device-protected store に置き、**端末 backup の復元で戻らない**前提（置き場は未決定・子 #18）。**別端末への全体巻き戻しは検出しない。**
- **外部暗号レビュー。** `awai-disk-format.md` §17.2 の順序（`R0 固定 → 外部暗号レビュー（R3 実装着手の前提）→ R3 実装 → R3 出口（指摘の反映確認）→ R2 形式凍結`）に従う。**「出口 = レビュー」とは書かない。** レビューは未発注・未課金（この task の範囲外）。
- **JS / WebView のメモリ 0 埋めは保証しない。** 平文と鍵の扱いは **Rust / secure buffer を前提**にする（WebView の GC とコピーを制御できない）。
- **WNFS の `XChaCha20-Poly1305` の nonce の作り方**（session id からの導出を含む）は、**外部暗号レビューの対象**である（`awai-disk-format.md` §15.5・§15.6・§17.2）。
- **`NameAccumulator` による候補ファイル名の確認可能性**（ラベルからファイル名を推測・辞書攻撃できるか）も、**外部暗号レビューの確認項目**に含める。ラベルは 2048-bit の accumulator で、平文なしに候補を検証できる性質が、名前の秘匿に与える影響を評価する。
- **`awai-disk-format.md` §21（v3）の bytes。** この文書は形式を確定しない。AAD の canonical 形式と golden corpus は `awai-disk-format.md`（main）と共通にする。
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
9. **high-water の永続化の置き場**（端末のどこに置くか。`awai-disk-format.md` §21.6 は「未決定」としている。**volume / media の外**の device-protected store に置き、端末 backup の復元で戻らない前提。置き場そのものは未決定・子 #18）。
10. **device revocation list の server 側の強制**。W4（write proof）まで無い。一覧は control record の記録で、サーバの拒否ではない。**旧 root への書込みは W4 まで防げない。**
11. **12 MiB の transport 上限の引き上げ時期**（§6.3 の見積りは 12 MiB を前提にした）。引き上げは認可の後（`scalable-sync-design.md` §4.9）。
12. **sidecar に置く欄**（label の写し、keyslot の外側の欄、最小 writer 版の候補。`awai-disk-format.md` §21.2 は未決定）。
