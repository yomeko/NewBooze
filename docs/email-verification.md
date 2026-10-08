# 新規登録時のメール認証

新規登録時にメールを送信し、リンク先の確認ボタンで認証を完了する。
未認証のアカウントはログインできない。認証完了後はログイン画面へ進む。
リンクは24時間有効で一度だけ使用でき、DBにはトークンのSHA-256ハッシュを保存する。
`/verify-email` からメールを再送でき、再送すると古いリンクは無効になる。
同一アカウントへの再送は60秒間隔。送信失敗時はDB更新をロールバックする。
既存アカウントは移行時に認証済みとして扱う。

## 既存DBの移行

起動前に `data/email_verification_schema.sql` を一度実行する。
新規DBの場合は `data/create_issho_no_deai.sql` に認証用のカラムを含む。
`data/reset_newbooze.sql` はデータ削除を伴うため、既存DBの移行には使わない。

## 実送信の設定

現在のデフォルトは `localhost:1025` で、Gmailへ実送信する設定ではない。
送信サービスのSMTP設定を環境変数またはGit対象外の `application-local.properties` に設定する。
公開URLはメール内リンクの生成に使うため、本番ではHTTPSの実URLを指定する。

```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=送信元のGmailアドレス
spring.mail.password=送信元のアプリパスワード
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.starttls.required=true
app.mail.from=送信元のGmailアドレス
app.base-url=http://localhost:8080
```

上記はGmailを送信元に選んだ場合の例。通常のGoogleパスワードは使わない。
アプリパスワードにはGoogleアカウントの2段階認証設定が必要で、利用できないアカウントもある。
秘密情報はチャットやGitに貼らず、ローカルの設定ファイルに保存する。
送信元は送信先と同じアドレスである必要はない。

- [Google公式 SMTP設定](https://support.google.com/mail/answer/7104828?hl=ja)
- [Google公式 アプリパスワード](https://support.google.com/accounts/answer/185833?hl=ja)

## 動作確認

1. SMTP設定とDB移行を済ませて起動する。
2. 新規登録画面で `sotsugyoukenkyuyou2026@gmail.com` を登録する。
3. 認証前のログインが拒否されることを確認する。
4. 認証メールのリンクを開き、認証ボタンを押す。
5. ログインできることを確認する。
6. 再送を確認する場合は認証前に `/verify-email` から再送する。
   認証済みアカウントには再送しない。

自動テストではメール送信をモックし、実際のGmail宛てには送信しない。
