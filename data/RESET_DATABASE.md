# 既存DBを残して作り直す

`create_issho_no_deai.sql` は、新しい `issho_no_deai` DBに全19テーブルと初期データを作成します。既存の `newbooze` DBは削除・変更しません。

XAMPP付属のMySQLクライアントでこのファイルを読み込んでください。`--force` は指定しないでください。同名DBがすでにある場合は停止するため、別の未使用DB名に `CREATE DATABASE` と `USE` の両方を変更してから実行します。

初期データは商品10件、診断4問・選択肢12件、酒種・タグなどです。レビューの公開設定と索引も含みます。ユーザー・投稿・レビューは空の状態です。酒蔵マスタは空で、商品の酒蔵IDは未設定です。旧DBのユーザーや投稿は移行しません。追加の差分SQLや旧シードは実行不要です。

## このPCの接続設定

リポジトリ直下の `application-local.properties` に次を設定し、リポジトリ直下を作業ディレクトリとしてアプリを起動してください。このファイルはGit管理対象外です。

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/issho_no_deai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Tokyo
```

アプリはこのファイルを任意で読み込みます。ファイルがない環境の既定接続先は引き続き `newbooze` です。別の作業ディレクトリで起動する場合は `SPRING_DATASOURCE_URL` 環境変数で同じ接続先を指定してください。既に起動中のアプリには再起動が必要です。

接続設定後、`./gradlew test bootJar` で起動と動作を確認できます。元の接続先に戻す場合はローカル設定ファイルを退避して再起動してください（旧DBの不整合自体は修復されません）。

## 削除を伴う旧手順

`reset_newbooze.sql` は既存の `newbooze` DBと全データを削除します。**既存データを残す今回の手順では実行しません。**
