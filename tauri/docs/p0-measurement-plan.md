# P0 測定計画と隔離 fixture の試験項目

- Status: **計画（未実施）**
- 日付: 2026-11-25
- Tracking: [AWAI-4118](https://linear.app/kotobalabs/issue/AWAI-4118)（Orca Item #302）。測定の既存の追跡は `scalable-sync-design.md` §7 の AWAI-3369。
- **この文書は計画である。測定は 1 件も実施していない。** 数値・しきい値・合否を書かない。結果は Linear の issue に記録し、この文書は項目と記録の形式だけを定める。
- 関連: [scalable-sync-design.md](scalable-sync-design.md) §4.6・§4.8・§7、[drive-host-ipc.md](drive-host-ipc.md) §4・§10、[portable-usb-custody.md](portable-usb-custody.md) §6.3、[encrypted-sync.md](encrypted-sync.md)

## 1. 範囲と反対目的

### 1.1 何のために測るか

- 測った値は**受入の上限（admission bound）と資源の境界**を決める設定値になる。**製品の SLO ではない。**
  `scalable-sync-design.md` §4.8 のとおり、water mark と空き reserve は設定値で、SLO にしない（AWAI-2856）。
- 測れなかった値は `unknown` と書き、測ったことにしない。`unknown` は記録として valid である。
- 数値が無い間は「速い」「足りる」「GB 級が扱える」と主張しない。

### 1.2 反対目的

- SLO、容量の保証、耐久性の証明、ハードウェアの資格認定ではない。
- 実機の USB 媒体への書き込みをしない。実機の電源断・再起動をしない。**破壊試験は捨ててよい fixture だけで行う。**
- 課金・外部サービスへの発注をしない（外部暗号レビューは v1.0 §6 の別 gate）。
- 測定値から pack サイズ・bound・quota を**この PR で確定しない**。確定は測定後（`scalable-sync-design.md` §10 の 7）。

### 1.3 fixture の原則

- 使ってよいのは、loopback の disk image、一時 volume、fake store（loopback の HTTPS か、memory の store）、
  emulator、捨ててよい VM だけである。
- **実機 USB・実機の keychain・実機の media を使わない。** 試験用の鍵はその場かぎりで作り、終了時に消す
  （鍵の生成・ローテーションはこの task の範囲外。fixture の鍵は既存の test vector を使う）。
- 破壊試験（電源断・強制 unmount・fs の破損）は、**fixture であることを記録の先頭に書き**、その fixture の外へ影響させない。
  実機に対しては行わない。

## 2. 記録の形式

すべての結果は次の field を持つ。1 行 1 測定（または 1 表）。

| field | 内容 |
|---|---|
| `id` | 測定の識別子（この文書の節番号 + 連番） |
| `commit` | 測定に使った code の commit SHA（fixture の生成 script を含む） |
| `date` | 測定日（ISO 8601） |
| `os` / `device` | OS の版、機種（VM / emulator はその種類） |
| `fixture` | fixture の id（§3 の一覧の id） |
| `config` | 設定値（pack サイズ、pipeline の深さ、block 数、回線の設定など） |
| `unit` | 単位（ms、MiB/s、MiB、個） |
| `source` | 値の出どころ（code の path、仕様の §、`unknown` の場合はその理由） |
| `coverage` | 何を動かし、何を動かしていないか（例: readback は動かしたが再送は動かしていない） |
| `value` | 数値、または `unknown` |
| `negative` | 同時に走らせた負の対照（§7）の id。無ければ `none` |
| `destructive` | 破壊試験か（`yes` / `no`） |
| `artifact` | 生の log・出力の置き場所（commit しない。Linear の issue に添付するか、その場所を書く） |

- `value` は中央値だけでなく、試行回数とばらつき（min / max か p50 / p95）を書く。試行回数が 1 ならそう書く。
- 測れなかった項目は `value: unknown` とし、**何が測れなかったか**（時間、機材、権限）を `source` に書く。
- 結果の置き場所は **Linear の issue**（`scalable-sync-design.md` §7 の運用）。この文書は計画だけを持ち、結果を含まない。

## 3. fixture の一覧

| id | fixture | 作り方（案） | 用途 |
|---|---|---|---|
| `F-loop-apfs` | APFS の sparse disk image | `hdiutil create -type SPARSE`（macOS） | macOS の FS probe、破壊試験 |
| `F-loop-ext4` | ext4 の loop device | `truncate` + `mkfs.ext4` + `mount -o loop`（Linux） | Linux の FS probe、破壊試験 |
| `F-loop-ntfs` | NTFS の vhdx / image | Windows の `New-VHD` + `Format-Volume` | Windows の FS probe |
| `F-loop-exfat` | exFAT の image | `mkfs.exfat` / `newfs_exfat` | exFAT の probe 条件（Portable は不可） |
| `F-tmp` | 一時ディレクトリ | `mktemp -d`（同じ FS の上） | 規模の測定、archive の作成 |
| `F-smb` | loopback の SMB share | 同じマシンの SMB server を localhost で公開 | network FS の拒否 |
| `F-nfs` | loopback の NFS export | 同じマシンの NFS server を localhost で公開 | network FS の拒否 |
| `F-emu` | Android emulator | AVD（API 35 など） | Android の SAF、`FileDescriptor.sync()` |
| `F-vm-mac` | macOS の VM | `Virtualization.framework` の VM | 電源断の破壊試験 |
| `F-vm-win` | Windows の VM | 捨ててよい VM | 電源断の破壊試験 |
| `F-store` | fake store | loopback の HTTPS（既存の `test/pack-server.mjs` と同種） | 同期・readback・再送の測定 |
| `F-mem-store` | memory の store | test の in-memory store | 同上（network の影響を除く） |

- `F-store` は**本番の endpoint ではない**（`encrypted-sync.md` の「loopback-only synthetic HTTPS VM fixture」）。
  実機の端末に fixture の APK を入れない。
- 破壊試験は `F-loop-*` と `F-vm-*` だけで行う。`F-tmp` は壊してよいが、その外の file を壊さない。

## 4. OS 別の最強の sync primitive

| OS | primitive | fixture | 何を確かめるか | 偽の flush をどう見分けるか |
|---|---|---|---|---|
| macOS | `F_FULLFSYNC`（`fcntl(F_FULLFSYNC)`） | `F-loop-apfs`、`F-vm-mac` | 「呼び出しが成功した」ことと、**破壊試験の後に directory entry と内容が残る**こと | **実行時の時間では区別できない。** `fsync` に落ちる実装でも時間はほとんど変わらない。区別は (a) `dtrace` / `fs_usage` で `F_FULLFSYNC` が実際に発行されたこと、(b) 破壊試験で残ること、の 2 つを別々に記録する。破壊試験は `yes` ラベル |
| Windows | `FlushFileBuffers` | `F-loop-ntfs`、`F-vm-win` | 同上（handle に対して発行されたこと、電源断の後に残ること） | 時間では区別できない。ETW / Process Monitor で呼び出しを確認し、破壊試験は別に記録する。`FILE_FLAG_WRITE_THROUGH` との混同を記録する |
| Linux | `fsync`（file と directory の両方） | `F-loop-ext4`、`F-vm-*`（Linux の VM） | 同上。directory の `fsync` を別に測る（file だけで足りると主張しない） | 時間では区別できない。`strace` で `fsync` が file と directory の両方に出たことを確認し、破壊試験は別に記録する。`dm-flakey` での fault injection は**破壊試験**として扱う |
| Android | `FileDescriptor.sync()` | `F-emu` | 呼び出しが成功したこと。SAF の tree URI では primitive が使えるか | 時間では区別できない。`Perfetto` / `strace`（emulator）で呼び出しを確認する。実機の電源断は行わない |

- **破壊試験のラベル規則:** 「flush を偽装しても実行時の時間では見分けられない」ため、**破壊試験をしなければ durability は
  主張できない**。破壊試験は `F-loop-*` / `F-vm-*` / `F-emu` の捨ててよい fixture だけで行い、記録に `destructive: yes` と
  fixture の id を書く。実機では行わない。**破壊試験をしていない項目は `unknown` とする。**
- 各 primitive について「何を確かめたか」と「何を確かめていないか」を分けて記録する。呼び出しの確認は
  「その syscall が発行された」ことの証明で、**媒体が実際に永続化したことの証明ではない。**

## 5. ファイルシステムの probe

volume open 時に実行し、結果を永続化する probe（v1.0 §4）。測定は probe の実装が正しく判定するかを確かめる。

| # | probe | fixture | 何を確かめるか | 期待（設計） |
|---|---|---|---|---|
| 5.1 | directory entry の生存 | `F-loop-*`、`F-vm-*` | file と directory を `fsync` した後に破壊試験をし、entry が残るか | 残らなければ grade を落とす。**破壊試験** |
| 5.2 | `rename` の原子性 | `F-loop-*` | 同じ directory 内の `rename` が「古いか新しいか」のどちらかになり、両方・どちらでもないにならないか | 中途半端な状態を観測したら grade を落とす |
| 5.3 | 4 GiB 超の file / sparse | `F-loop-*`、`F-loop-exfat` | 5 GiB の file を作れるか。sparse の `st_size` と実 block、seek の振る舞い | exFAT は sparse を前提にしない。4 GiB 超で失敗する FS を Portable にしない |
| 5.4 | 空き容量の下限 | `F-tmp`、`F-loop-*` | 設定した reserve に当たったとき、**ENOSPC の前に**書き込みを拒否するか | 拒否する。ENOSPC を通常の制御に使わない（`awai-disk-format.md` §7.4） |
| 5.5 | network FS の拒否 | `F-smb`、`F-nfs` | probe が SMB / NFS を検出して拒否するか。`flock` と `fsync` の意味が保たれないことを検出できるか | **拒否する。** fallback しない |
| 5.6 | exFAT の probe 条件 | `F-loop-exfat` | exFAT が Local-Meta の条件付きで通り、**Portable では通らない**か | journal が無いので Portable は不可（v1.0 §2） |
| 5.7 | journaled FS の要求 | `F-loop-apfs`、`F-loop-ext4`、`F-loop-ntfs`、`F-loop-exfat` | Portable の作成が journaled FS だけで通り、exFAT で拒否されるか | APFS / NTFS / ext4 は可、exFAT は不可 |
| 5.8 | SAF の制限 | `F-emu` | SAF の tree URI で書けるのが `cache/` だけで、`rename` と `fsync` の保証が無いこと | Android は Local-Meta のみ、`cache/` のみ（v1.0 §2・§3） |

- 5.1 と 5.2 の破壊試験は、`F-loop-*` を強制 unmount するか VM を reset して行う。**実機の USB では行わない。**
- 5.5 の SMB / NFS は localhost の share を使う。外部の server に接続しない。
- probe の判定は「通った / 落ちた」だけでなく、**落ちた理由**（どの検査で落ちたか）を記録する。

## 6. 規模の測定

### 6.1 WNFS の計算

| # | 測るもの | 条件 | 記録 |
|---|---|---|---|
| 6.1.1 | `NameAccumulator` の 1 block あたりの cost（accumulate + hash-to-prime） | 64 GiB 級 = 262,144 block（64 × 1,073,741,824 ÷ 262,144）。browser の BigInt、Android、macOS の 3 つ | ms/block、合計、peak RSS。**3 つを別々に記録し、混ぜない** |
| 6.1.2 | forest（HAMT）の構築・走査・lazy load の時間とメモリ | node / record を 1,000 / 10,000 / 100,000 / 262,144 で | 時間、peak RSS、lazy 走査で読んだ node の数 |
| 6.1.3 | 1 file の block 数の上限を上げたときの往復 | 大きな file を streaming で seal / open | 一致、RSS、短読の拒否 |

### 6.2 IPC と転送

| # | 測るもの | 条件 | 記録 |
|---|---|---|---|
| 6.2.1 | 1 MiB の raw IPC の throughput と latency | `drive-host-ipc.md` §4 の data plane（socket の fd、pipe の chunk、`ParcelFileDescriptor`） | MiB/s、往復の latency。transport ごとに別 |
| 6.2.2 | 65531 byte × base64 の JSON 往復 | 現行の経路（§4.6） | 同上。**6.2.1 と直接比べる**（同じ payload で） |
| 6.2.3 | seal / hash の throughput と peak RSS | pack 32 MiB / 256 MiB、pipeline の深さを変えて | MiB/s、peak RSS。深さごとの上限を記録 |
| 6.2.4 | readback の費用 | `F-store` で GET の回数と bytes を変えて | 1 Pack あたりの時間と bytes。**retention の証拠ではない** |

### 6.3 同期・復元・再送

| # | 測るもの | 条件 | 記録 |
|---|---|---|---|
| 6.3.1 | 1 GiB / 10 GiB の同期と復元の所要時間 | `F-store` / `F-mem-store`。隔離した store | 時間、転送 bytes、peak RSS。**SLO にしない** |
| 6.3.2 | archive 化した tree の作成・展開 | 多数の小さな file（件数は fixture の設定値として記録） | 時間、RSS、block 数、復元の一致 |
| 6.3.3 | pack サイズと再送の cost | 通信断を注入（k MiB ごと、または Pack の途中）し、pack 32 / 256 MiB で | 再送した bytes、完了までの時間、部分 root が公開されないこと |

- 6.3.1 の 1 GiB / 10 GiB は**隔離した store** で行い、本番の endpoint を使わない。
- 6.3.3 の通信断は `F-store` の proxy で注入する。実回線を落とさない。

## 7. 負の対照

各測定と一緒に走らせ、**失敗することを確かめる**。通ってしまう検査は、その検査が効いていない証拠である。

| # | 負の対照 | 期待 |
|---|---|---|
| N1 | block を 1 byte 壊す | CID か AEAD の検証で拒否。復元しない |
| N2 | block を切り詰める（短読） | 拒否。部分的な公開をしない |
| N3 | CID を取り違える（別の block を返す） | 拒否。`error` にして退避しない |
| N4 | directory の entry を 1 つ消す | path の解決が失敗する。**directory node を退避していないこと**の確認にも使う |
| N5 | `rename` を中断する | 古いか新しいかのどちらかになる。中途半端な状態を成功として扱わない |
| N6 | SMB / NFS の volume を開く | probe が拒否する。fallback しない |
| N7 | exFAT で Portable を作る | probe が拒否する |
| N8 | `retentionUntil=0` の受領証で evict する | 拒否する（Stage 2 の条件） |
| N9 | `validUntil` を過ぎた検証役の記録で evict する | 「不明」として拒否する |
| N10 | `storeClass 3` だけの R2 を名乗る | 独立ドメインとして数えないため拒否する |
| N11 | 同じ `requestId` で違う payload を送る | `idempotency-conflict`。2 回目を書かない |

N5〜N7 は `F-loop-*` / `F-emu` で行う。N1〜N4 は `F-mem-store` と `F-tmp` で行える。**実機を使わない。**

## 8. 実施と記録

- 実施は child issue（[awai-4118-child-issues.md](awai-4118-child-issues.md) の 13）で行う。この文書は plan だけを持つ。
- 結果は Linear の issue に、§2 の形式で記録する。**この PR では測定を実行しない。**
- 測定の前に、各 fixture の作成 script と設定を commit する（結果ではなく、再現の手順）。script は fixture の外に影響しない
  ことを確認する。
- 測定の後に、`scalable-sync-design.md` §10 の未決定（pack サイズ、bound、quota、`get-commit-status` の保持数、
  backpressure の credit）を、測った値で決める。**測っていない値を既定にしない。**

## 9. この計画で測らないこと

- 実機の USB 媒体の耐久性・速度・電源断（実機を使わない）。
- ハードウェアの secure element / FBE の資格（`portable-usb-custody.md` §8）。
- 外部暗号レビューの結果（v1.0 §6 の別 gate）。
- 本番の B2 / Yataverse の費用と遅延（`retention-design.md` §15 の未測定と同じ）。
- オフラインの巻き戻しの検出（**検出できない**。測る対象ではない）。
