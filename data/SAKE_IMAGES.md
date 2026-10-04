# 商品写真の登録

`data/sake_images.sql` を、phpMyAdminでアプリの接続先DB（`newbooze` または `issho_no_deai`）を選択して実行します。テーブルや銘柄を作り直さず、`sake.image_url` だけを更新します。画像本体はDBに保存しません。

初期データのIDと銘柄名が両方一致する7銘柄が対象です。IDや名前を変更している場合はSQLの対応表も修正してください。指定済みURLは既存URLを上書きするため、変更前のSELECT結果を保存しておくと戻せます。NULL・空文字・255文字超のURLは更新しません。同じ内容で再実行できます。

## 確認した公式商品ページ（2026年10月1日）

| ID | 銘柄 | 採用した写真 | 公式出典 |
| --- | --- | --- | --- |
| 1 | 獺祭 純米大吟醸45 | 純米大吟醸45 | https://dassai.com/product/main/45.html |
| 4 | 黒龍 いっちょらい | いっちょらい 吟醸（純吟とは別商品） | https://www.kokuryu.co.jp/ja/brew/kokuryu |
| 5 | 而今 特別純米 | 特別純米 火入 | https://kiyashow.com/sake/ |
| 7 | 出羽桜 桜花吟醸 | 桜花吟醸酒 | https://www.dewazakura.co.jp/item/cat03/oka.html |
| 8 | 八海山 特別本醸造 | 特別本醸造 720ml | https://www.hakkaisan.co.jp/sake/honjyozo/ |
| 9 | 風の森 秋津穂 | 秋津穂657（初期DBの商品名には規格の記載なし） | https://yucho-sake.jp/product/kazenomori/ |
| 10 | 鍋島 特別純米 | 特別純米酒（生酒とは別商品） | https://nabeshima.biz/sake.html |

7件とも画像URLがHTTP 200を返し、画像形式であることを確認しました。黒龍の画像は公式サイトが利用するmicroCMSの配信URLです。外部URLの変更・配信制限により表示できなくなる場合があります。

## 未指定の3銘柄

- 新政 No.6：公式商品ページ `https://www.aramasa.jp/collection/no.6.html` のHTTPS証明書がホスト名と一致せず、画像の到達確認ができませんでした。
- 十四代 本丸：山形県の高木酒造の商品に対応する公式商品写真URLを確認できませんでした。
- 田酒 特別純米：青森県酒造組合が案内する公式サイト `www.densyu.co.jp` への接続が切断され、写真を確認できませんでした。

これらの変数はNULLのため、既存写真URLを消しません。公式写真を用意できたら対応する変数にURLを設定して再実行してください。

## ローカル写真に差し替える場合

写真を `src/main/resources/static/images/sake/` に配置し、SQLの変数に `/images/sake/ファイル名.jpg` を指定します。画像を追加した場合はアプリを再ビルド・再起動してください。

SQL実行後は対象銘柄の商品詳細ページ（`/sake/{id}`）を再読み込みしてください。写真は共通フォーマットの商品紹介の直後に表示されます。写真未指定の銘柄では写真枠を表示しません。トップ・検索・診断結果の銘柄カードにも商品写真を表示します。未登録または読み込み失敗時は「商品写真準備中」を表示します。
