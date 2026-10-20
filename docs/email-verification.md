# メール認証の起動とGmail SMTP設定

更新日: 2026-10-21（各確認結果は確認日を分けて記載）

## 起動

Java 21、設定先のMySQL/MariaDB、Mailpitが必要です。macOSでは `brew install mailpit` で受信箱を導入できます。
リポジトリ直下で、ターミナルを2つ開いて実行します。

```sh
# ターミナル1：受信箱
./scripts/start-local-mail.sh
```

```sh
# ターミナル2：アプリ（8080番の既存アプリは先に終了）
./gradlew bootJar
./scripts/start-local-app.sh
```

- アプリ: http://localhost:8080/
- 新規登録: http://localhost:8080/signup
- メール受信箱: http://localhost:8025/

Mailpitは127.0.0.1だけで待ち受け、受信メールを外部へ配信しません。
実在する宛先は不要です。`demo@example.test` のようなテスト用アドレスで登録してください。
受信箱内のメールは同じPCの利用者が閲覧できるため、実サービスの秘密情報を送らないでください。
両ターミナルともCtrl+Cで終了できます。Macの再起動後は再度起動してください。

## 初回のDB準備

`application-local.properties` / 環境変数の実際の接続先を確認し、usersをバックアップしてから
`data/email_verification_schema.sql` を一度だけ適用します。適用済みのDBへ再実行しないでください。
既存会員は認証済みの初期値を保ち、新規登録では未認証に設定します。
`ddl-auto=validate` は維持します。

このPCでは2026-10-19に接続先 `issho_no_deai` を確認し、usersを
`output/email-verification/users-before.sql` にバックアップして4列を追加しました。
バックアップには会員データがあるためGitへ追加しないでください。
メール設定はGit管理外の `application-local.properties` に保存しています。

## 操作確認

1. `/signup` で新しいテスト用アドレスとパスワードを登録する。
2. 認証前はログインできないことを確認する。
3. 受信箱を開き、確認メール内のリンクを同じPCで開く。
4. 「メールアドレスを認証する」を押す。
5. 登録したアドレス・パスワードでログインする。

リンクは24時間有効で一度だけ使えます。メールのリンクを開くだけでは認証せず、
ボタンで確定するため、メール検査ソフトの自動アクセスで消費されません。
再送は `/verify-email` から60秒以上あけて行い、再送後は新しいメールのリンクを使います。
localhostのリンクはこのPC向けです。

## Gmailで実メールを送信する

Googleアカウントの2段階認証を有効にし、アプリパスワードを作成します。
通常のGoogleログイン用パスワードは使いません。Google側の本人確認とパスワード作成は本人が行います。

- [Google公式：アプリパスワード](https://support.google.com/accounts/answer/185833?hl=ja)
- [Google公式：SMTP設定](https://support.google.com/mail/answer/7104828?hl=ja)

リポジトリ直下で設定用スクリプトを実行します。Python 3とGitが必要です。

```sh
python3 scripts/configure-gmail.py --email YOUR_ADDRESS@gmail.com
```

アプリパスワードは非表示の入力欄へ入力します。チャットやコマンド引数には書きません。
スクリプトは `smtp.gmail.com:587` へTLS・証明書検証・認証を行い、成功後に
Git管理外の `application-local.properties` へ保存します。設定ファイルは所有者だけが読める権限600にします。
設定スクリプト自体からメールは送信しません。

2つのプロジェクトへ同じ送信設定を保存する場合は、`--config` を繰り返して指定します。
それぞれのDB接続先などメール以外の設定は保持します。

```sh
python3 scripts/configure-gmail.py --email YOUR_ADDRESS@gmail.com \
  --config /path/to/project-A/application-local.properties \
  --config /path/to/project-B/application-local.properties
```

保存する内容はSMTPホスト・ポート、アカウント名・アプリパスワード、送信元、認証有効、
STARTTLS有効かつ必須、証明書のホスト名検証有効です。
入力先がGit管理対象、Gitの除外対象でない、シンボリックリンク等の場合は保存しません。
認証に失敗した場合は設定を書き換えません。Gmailのアプリパスワードに表示上の空白があっても入力できます。

設定後、Eclipseではプロジェクトをリフレッシュしてアプリを再起動します。
ターミナルからは次のように起動できます。

```sh
./gradlew bootJar
java -jar build/libs/issho-no-deai-0.0.1-SNAPSHOT.jar
```

`start-local-app.sh` はMailpitに固定するため、Gmailで送信する起動には使いません。
`application-local.properties` の直接指定は、既定のpropertiesにある環境変数参照を上書きします。
送信設定を環境変数へ移す場合は、ローカルファイル内の該当SMTP設定も合わせて整理してください。

`app.base-url` が `http://localhost:8080` の場合、受信メールのリンクは同じPCで開きます。
公開サイトでは利用者が到達できるHTTPS URLに変更します。

## 実メールで確認する項目

1. SMTP接続・認証とローカル設定保存の成功を確認する。
2. 許可されたテスト宛先を使い、アプリの新規登録または未認証会員の再送を実行する。
3. 実際の受信箱で受信を確認する。迷惑メールも確認する。
4. メールのリンクを同じPCで開き、認証ボタンを押す。
5. 認証後にログインできることを確認する。再送は60秒以上あける。

SMTPが送信を受け付けた状態、実際に受信した状態、Webアプリで認証した状態を分けて記録します。
SMTPの資格情報、受信メール全文、生の認証トークン、会員DBバックアップはコミットしません。

## 2026-10-19のローカル受信箱での確認結果

- `./gradlew test bootJar`: 全101テスト成功、ビルド成功。
- 実DB `issho_no_deai`: usersバックアップ、認証用4列の追加、アプリ起動成功。
- Playwrightの実ブラウザ: テスト会員の登録、Mailpitへのメール受信、本文リンクからの認証確定、認証済み会員でのログイン成功。
- 未認証ログイン拒否、CSRF、リンクの一度限りの使用、送信失敗時のロールバック等はJUnitで確認。
- 実メール配信および公開HTTPS環境は未確認。

## 2026-10-21のSMTP準備・検証

- `smtp.gmail.com:587`: STARTTLS接続と証明書検証に成功。
- 設定スクリプト: 外部送信なしのPythonテスト9件成功。
- `./gradlew test bootJar`: 既存MariaDBを起動後、全101テスト成功・ビルド成功。
- 本人によるアプリパスワード入力後、Gmail SMTPのTLS接続・認証に成功し、2プロジェクトのGit管理外設定へ権限600で保存した。送信元の個人アドレス・認証情報は本書に記載しない。
- Eclipse側の `newbooze` DBでアプリを起動し、許可されたテスト宛先への新規登録確認メールを実SMTPで送信した。アプリの送信処理成功と、認証前のログイン拒否を確認した。
- 実メール受信・本文リンクからの認証確定・認証後ログインは確認中。SMTPの送信処理成功だけで受信・認証を確認済みとは扱わない。
- このPCの既存MariaDBデータは `~/mariadb-data` を使用する。Homebrewの既定 `var/mysql` は別形式のMySQLデータであり、MariaDBの起動先として使わない。データの初期化・復元は実施していない。
