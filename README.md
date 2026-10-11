# WorldStateSync

## 1.1.0 ユーザーポータル連携

`minecraft-user-portal`向けに、読み取り専用のJSON投影テーブル
`<table-prefix>portal_snapshot`を追加しました。所持品の個数・名前・説明・耐久値・
エンチャント、プレイヤー状態、全Bukkit統計を保存します。元の同期ペイロードは変更しません。
従来の保存済みインベントリは起動時に表示用へ変換します。

設置ブロック数はこの版の導入後から数えます。キャンセルされた設置は除外し、
複数ブロック設置を数えます。WorldEdit等、BlockPlaceEventを発行しない操作は対象外です。
カウンターはサーバー別のPersistentDataContainerに保存します。

配布JAR: `target/WorldStateSync-1.1.0.jar`（Paper上の名前: WorldStateSync 1.1.0）。
古いJARと新しいJARを同時にpluginsへ置かないでください。

Paper 26.2 / Java 25向けの、MySQLを使用するワールド別プレイヤー状態同期プラグインです。
Velocity配下の複数Paperサーバー間で、次の状態を共有します。

- インベントリ（防具・オフハンドを含むPlayerInventory全体）
- エンダーチェスト全スロット
- レベル、経験値進捗、累計経験値
- ポーションエフェクト
- 空腹度、満腹度、消耗度
- 現在の体力、吸収体力（最大体力を超える値は読み込み先で調整）

## 同期単位

データの主キーは「プレイヤーUUID + ワールド同期キー」です。デフォルトの同期キーは
ワールド名です。異なるPaperサーバーでワールド名が違う場合は、各サーバーの
`world-mappings` で同じ同期キーに割り当てます。

```yaml
# server-a の config.yml
world-mappings:
  world: "survival-main"

# server-b の config.yml
world-mappings:
  survival: "survival-main"
```

`world` と `survival` で同じプレイヤー状態が使われます。マッピングされていない
ワールドは、そのワールド名で全サーバー間同期されます。

## 導入

1. MySQL 8.xまたは互換性のあるMariaDBにデータベースとユーザーを作成します。
2. `WorldStateSync-1.1.0.jar` を、Velocity本体ではなく全Paperバックエンドの
   `plugins` フォルダーへ入れます。
3. 一度起動して生成された `plugins/WorldStateSync/config.yml` のMySQL接続情報を
   全バックエンドで設定します。
4. サーバーごとに `server-id` を一意な名前へ変更します（空欄でもポートから自動生成されます）。
5. 同じ論理ワールドの名前がサーバー間で異なる場合、`world-mappings` を設定します。
6. 全バックエンドを再起動します。

テーブルは起動時に自動作成されます。MySQLユーザーには対象データベースへの
`SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX` 権限が必要です。

`sync.import-local-on-first-login: true`（デフォルト）の場合、DBに現在ワールドの行が
まだ存在しない最初の接続だけ、既存のPaperプレイヤーデータを初期値として取り込みます。
別ワールドへゲーム内で初めて移動した場合は、独立した空の状態から始まります。

## Velocity切替時の保護

プレイヤーUUIDごとにMySQL上で期限付きセッションを確保します。サーバー移動時、
新しいPaperサーバーは旧サーバーが最終保存してセッションを解放するまで待ちます。
待機中はインベントリを空にし、移動・操作・ダメージを一時停止します。バックエンドが
異常終了した場合も、リース期限後に別サーバーから再取得できます。

## コマンド

- `/worldsync status` - セッション数、server-id、自分の同期キーを表示
- `/worldsync saveall` - 全オンラインプレイヤーの非同期保存を開始

権限は `worldstatesync.admin`（デフォルトOP）です。

## ビルド

```sh
mvn clean verify
```

生成物: `target/WorldStateSync-1.1.0.jar`

## 運用上の注意

- 全バックエンドは同じMinecraft/Paperバージョンと、アイテムを追加する同じプラグイン構成にしてください。
- 別のインベントリ分離・同期プラグインとは併用しないでください。
- 初回本番導入前に、既存プレイヤーデータとMySQLをバックアップしてテストしてください。
- MySQL障害中はデータ複製を防ぐため、読み込み不能なプレイヤーを接続させません。
