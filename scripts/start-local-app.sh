#!/bin/sh
# リポジトリのDB設定を使い、メールだけをローカル受信箱に固定する。
set -eu
cd "$(dirname "$0")/.."
if [ ! -f build/libs/issho-no-deai-0.0.1-SNAPSHOT.jar ]; then
  echo '先に ./gradlew bootJar を実行してください。' >&2
  exit 1
fi
exec java -jar build/libs/issho-no-deai-0.0.1-SNAPSHOT.jar \
  --spring.mail.host=127.0.0.1 --spring.mail.port=1025 \
  --spring.mail.username= --spring.mail.password= \
  --spring.mail.properties.mail.smtp.auth=false \
  --spring.mail.properties.mail.smtp.starttls.enable=false \
  --spring.mail.properties.mail.smtp.starttls.required=false \
  --app.mail.from=no-reply@issho-no-deai.local \
  --app.base-url=http://localhost:8080
