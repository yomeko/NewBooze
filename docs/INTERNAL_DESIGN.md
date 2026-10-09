# 一升の出会い 内部設計書

版数：第1.0版　更新日：2026年10月9日

本書はNewBoozeの現行コードを根拠に、DBアクセス、画面、クラス、エンドポイント、推薦処理を説明する。初期の暫定設計を置き換え、DB接続・JPA Entity・会員・レビュー・管理画面の実装を反映する。現行DDLは19テーブルで、従来18テーブルにsake_page_detailsを追加している。

## 1 文書の位置づけ

### 1.1 目的と対象

要件定義書 第1.4版の実装を開発チームが把握し、保守と発表の根拠に使えるようにする。対象はcom.example.demo配下、テンプレート、静的資産、dataのSQL、JSON設定。外部AIを使った推薦、準備中の案内ページの本機能、飲酒投稿の操作画面は完成済みとして扱わない。

### 1.2 更新方針と開発体制

新野が実装、原が発表資料、濵田が資料・収集・調査、池田が進捗管理、出田がデザインを担当する。開発期間は9月から1月末。変更時はコード、DDL、要件定義書、DB設計書を同時に見直す。実DBの過去照合結果と今回のソース確認を区別する。

## 2 システム構成

### 2.1 技術スタック

| 区分 | 採用技術と用途 |
|---|---|
| 言語 | Java 21、HTML、CSS、JavaScript |
| Web | Spring Boot 4.1.1、Spring MVC、Thymeleaf |
| 認証 | Spring Security、BCrypt、HTTPセッション |
| データアクセス | Spring Data JPA、Hibernate、JdbcTemplate |
| DB | MySQL互換DB、MariaDB、InnoDB、utf8mb4_unicode_ci |
| ビルド | Gradle Wrapper 9.5.1、Groovy DSL |
| メール | Spring Mail、設定したSMTPサーバー |
| AI依存 | Spring AI Anthropic 2.0.0、現行推薦では未使用 |
| 検証 | JUnit、Mockito、MockMvc、Node.js、ブラウザ |

### 2.2 アーキテクチャ

Spring SecurityがURL権限とCSRFを確認し、Controllerが入力を受け付け、ServiceとRepositoryでDBから取得・保存した情報をThymeleafへ渡す。主要な関連データはServiceのトランザクション内で表示用recordへ変換する。open-in-view=falseにより、Viewでの暗黙の遅延取得に頼らない。

JPAは基本情報と会員等を扱い、JdbcTemplateはレビューの登録・公開集計や詳細紹介JSONを扱う。管理銘柄登録はJPAとJDBCの処理を同じトランザクションに含める。静的ガイドの本文と出典はguide.htmlで管理する。

### 2.3 パッケージ構成

| パッケージ | 主なクラスと役割 |
|---|---|
| com.example.demo | IsshoNoDeaiApplication |
| controller | Home、SakeSearch、Diagnosis、Auth、MyPage、Admin等のController |
| service | SakeCatalog、SakeDiscovery、SakePage、SakeInteraction、TemporaryPassword等のService |
| repository | Spring Data JPA Repository 17クラス |
| entity | JPA Entity 17クラスと複合IDクラス |
| model | 表示用Sake、詳細紹介SakePage、旧診断モデル |
| dto | SakePageDto、診断表示、入力フォーム、TastePresentation |
| security | CustomUserDetailsとCustomUserDetailsService |
| config | SecurityConfig、AdminAccountInitializer |

## 3 データベース設計

### 3.1 方針とテーブル一覧

DDLをdata/create_issho_no_deai.sqlで管理し、Hibernateのddl-auto=validateは構造を検証する。起動時にテーブルを作成・変更しない。SQLの19テーブルは、過去のDB設計書の18テーブルに詳細紹介を加えたもの。19テーブルにJPA Entityが19クラス必要という意味ではなく、レビューと詳細紹介はJDBCで扱う。

| テーブル | 主キー | 主な役割 |
|---|---|---|
| users | id | name、email、password_hash、temporary_password、admin |
| breweries | id | 酒蔵名と都道府県 |
| sake_types | id | 酒種名 |
| tags | id | 特徴名と分類 |
| sake | id | brewery_id、sake_type_id、名称・地域・度数・価格・画像 |
| sake_page_details | sake_id | 詳細紹介のdetail_json |
| diagnosis_questions | id | 設問と表示順 |
| diagnosis_choices | id | question_idと選択肢文 |
| choice_tags | choice_idとtag_id | 診断のweight |
| diagnosis_sessions | id | user_idと診断日時 |
| diagnosis_answers | id | session_id、question_id、choice_id |
| sake_tags | sake_idとtag_id | 特徴のscore |
| user_preferences | user_idとtag_id | 嗜好のscore |
| favorites | user_idとsake_id | お気に入り日時 |
| sake_reviews | user_idとsake_id | rating、comment、published、updated_at |
| drink_posts | id | user_id、sake_name、comment、created_at |
| drink_post_likes | user_idとpost_id | 投稿のいいね |
| drink_post_reports | id | 通報者、対象投稿、理由 |
| user_profile_images | user_id | 画像本体・MIME・位置・拡大率 |

### 3.2 関係と制約

sakeは任意のbreweriesと必須のsake_typesを参照する。sake_tagsとchoice_tagsが共通tagsを介して銘柄と回答を比較する。会員の診断はsessionsからanswersへ、嗜好はuser_preferencesへ保存する。レビューは星1〜5のCHECKと複合主キーを使う。同じ投稿への同じユーザーの通報は一意制約を持つ。

sake_page_detailsはsakeと最大1対1で、sake_idはBIGINT UNSIGNEDの主キー・外部キー、detail_jsonはLONGTEXT NOT NULL。銘柄削除時はCASCADE。全DDLの外部キーは24本。カラム・索引・削除規則の詳細はDB_DESIGN.mdを参照する。

### 3.3 保存ルール

匿名の診断はDBに保存しない。会員の診断ではセッションと回答を作成し、今回集計したタグの嗜好スコアを上書きする。今回登場しない過去のタグは残る。今回の推薦は保存済み嗜好ではなく、送信された回答を集計したMapを入力にする。

商品詳細はsake_page_detailsに保存されていれば優先する。なければsake-pages.json、その銘柄も未登録ならDBの基本情報で補完する。レビューは1人・1銘柄で更新し、公開平均・件数・一覧はpublished=1だけを対象とする。

drink_posts、drink_post_likes、drink_post_reportsにはEntity・Repositoryがある。PostModerationServiceも存在するが、現行Controllerと画面に投稿・いいね・通報の操作経路はない。診断履歴は保存されるが、マイページは現在の嗜好スコアとお気に入りを表示し、診断履歴一覧を提供していない。

## 4 画面設計

### 4.1 実装済み画面

| 画面とテンプレート | 主要内容 |
|---|---|
| ホーム home.html | 診断・検索、3トピック、ガイド導線、ランダム1件と類似メジャー1件 |
| ガイド guide.html | 6段階の目次・本文・次の段階・出典 |
| 検索 search.html | 複合検索、並べ替え、6件のページング |
| 商品 detail.html | 紹介、タグ、購入先、公開レビュー、本人の評価とお気に入り |
| 診断 diagnosis.html | 初期4問、戻る・次へ、回答保持、離脱確認 |
| 結果 diagnosis-result.html | 傾向、回答説明、最大5件のおすすめと共通タグ |
| 認証 auth配下 | ログイン、会員登録、仮パスワード発行 |
| マイページ mypage.html | 嗜好スコア、お気に入り、プロフィール概要 |
| 設定 account-settings.html | プロフィール、画像、パスワード、退会 |
| 管理 admin配下 | 管理トップ、ユーザー検索、銘柄登録 |
| 案内 collection.html | 各準備中ページの共通表示 |

### 4.2 共通UIと導線

site-headerの検索、横スクロールナビ、標準dialogのその他メニューを全画面で使う。初心者ガイドはホームと共通メニューから開き、商品詳細からbuy・store・openへ直接進める。ガイドは通常のアンカーリンクで、JSなしでも読める。

home.jsは3記事を左右・ドット・キー・スワイプで切り替える。初期は手動、再生で7秒間隔。動きを減らす設定では自動を停止する。初期トピックのランダム化は未実装。銘柄比較の写真と3項目の5段階表示はsake-comparisonを共通利用する。タグ未登録の項目は未登録と表示する。

## 5 クラスと保存処理

### 5.1 日本酒情報

entity.Sakeはsakeに対応するJPA Entity。酒蔵・酒種はManyToOneで、公開レビューの平均・件数・有無はHibernate Formulaにより読み取り時に計算する。model.Sakeは基本情報、タグMap、公開評価を持つ読み取り用record。SakePageは飲み方・料理・リンク等を持つ紹介用recordで、空項目、重複、不正なURL等を整理する。

SakeCatalogService.searchは条件の空白と#タグ入力を整え、RepositoryのPageをSakePageDtoへ変換する。ページ番号は0始まり、負の指定は0、範囲超過は最終ページ。SakePageDtoは独自recordでありPageインターフェースの実装ではない。Repositoryでは実際のSpring Data Pageを使用する。

### 5.2 認証と本人の情報

AuthControllerは入力検証・email重複確認・BCrypt保存後にSecurityContextをHTTPセッションに保存し、診断へ移動する。ログインはSecurityConfigとCustomUserDetailsServiceで処理する。仮パスワード使用中は変更案内付きマイページ、管理者は管理トップ、一般会員はホームへ移動する。現行の会員登録にはメール認証・再送機能を含めない。

MyPageControllerは更新対象のuser_idをログイン情報から取得する。プロフィールと画像、パスワード、退会を扱う。パスワード変更と退会では現在のパスワードを照合する。画像はJPEG・PNG・GIFで最大20MBを受信し、900KBを超える場合にJPEGへ圧縮する。保存画像は900KB以下、初期長辺1600px以下を目標にし、位置と拡大率を別フィールドに保存する。

TemporaryPasswordServiceはSecureRandomで14文字の仮パスワードを生成し、ハッシュとtemporary_password=trueを保存してSMTPで送信する。未登録emailにも同じ完了案内を表示する。SMTP例外時はDBトランザクションをロールバックする。メール配信の実運用は設定・確認が必要。

### 5.3 レビューと管理登録

SakeInteractionServiceはJdbcTemplateで本人のレビューと公開一覧を扱う。入力は星1〜5・コメント最大500文字で、登録済みなら更新する。公開一覧は10件、新しい順・評価高い順・低い順で表示する。フォームの公開初期値はtrue、DBの初期値はfalseである。

AdminControllerは酒種・タグ・URL・画像を検証し、銘柄をsaveAndFlush、詳細JSONを保存、選択タグをscore=3で登録する。酒蔵名が新規なら作成する。DB処理はトランザクション化し、保存失敗時は保存済み画像の削除も試みる。商品画像はJPEG・PNG、最大5MB・2000万画素でuploads/sakeへ保存する。

## 6 エンドポイントと権限

Thymeleafの画面遷移を伴うHTTPエンドポイントとして定義する。保存操作はPOSTとCSRFトークンを使う。公開URL、認証必須URL、ADMIN必須URLはSecurityConfigで区別する。

### 6.1 公開と認証

| メソッド | URL | 処理 |
|---|---|---|
| GET | / | HomeControllerのランダム比較 |
| GET | /guide | 初心者ガイド |
| GET | /search | keyword、name、type、taste、priceRange、sort、page |
| GET | /sake/{id} | 商品と公開レビュー、reviewSort、reviewPage |
| GET | /sake/images/{filename} | 商品画像 |
| GET | /sake/reviewers/{userId}/profile-image | 公開レビュー投稿者のプロフィール画像 |
| GET | /diagnosis | 診断設問 |
| POST | /diagnosis/result | choiceの一覧から集計・推薦、会員のみ保存 |
| GETとPOST | /login | フォームとSpring Security認証 |
| GETとPOST | /signup | 会員登録 |
| GETとPOST | /forgot-password | 仮パスワード案内と発行 |
| POST | /logout | セッションからログアウト |

商品が存在しなければ404。診断の回答が空なら/diagnosisへ戻る。結果表示はPOST /diagnosis/resultであり、旧案のPOST /diagnosis/answerとGET /diagnosis/resultは存在しない。

### 6.2 本人の操作

| メソッド | URL | 処理 |
|---|---|---|
| GET | /mypage | 本人の嗜好とお気に入り |
| GET | /mypage/account | アカウント設定 |
| GETとPOST | /mypage/profile-image | 本人画像の取得・保存 |
| POST | /mypage/profile-image/delete | 本人画像削除 |
| POST | /mypage/profile-image/position | 表示位置と拡大率 |
| POST | /mypage/favorites | カタログからお気に入り追加 |
| POST | /mypage/favorites/{sakeId}/delete | お気に入り削除 |
| POST | /mypage/sake/{id}/favorite | selectedで登録・解除 |
| POST | /mypage/sake/{id}/review | rating、comment、publishedを保存 |
| POST | /mypage/sake/{id}/review/delete | 本人レビュー削除 |
| POST | /mypage/profile | プロフィール変更 |
| POST | /mypage/password | パスワード変更 |
| POST | /mypage/account/delete | 本人確認後に退会 |

### 6.3 管理と案内

| メソッド | URL | 権限と処理 |
|---|---|---|
| GET | /admin | ADMIN、管理トップ |
| GET | /admin/users | ADMIN、keywordとpage、30件ずつ |
| GETとPOST | /admin/sake/new | ADMIN、銘柄登録 |
| GET | /ranking、/pairings、/goods | 認証必須、準備中の案内 |
| GET | /brewery-map、/contact、/categories、/tags | 公開、準備中の案内 |
| GET | /about、/privacy、/external-transmission | 公開、準備中の案内 |

## 7 推薦とランダム比較

### 7.1 診断のデータフロー

DiagnosisServiceはchoice_tags.weightをタグ別に加算する。会員の回答をdiagnosis_sessionsとdiagnosis_answersへ保存し、user_preferencesの該当タグを更新する。recommendは今回の集計とcatalog.allのsake_tags.scoreを比較し、コサイン類似度の降順で5件を返す。TastePresentationが正の共通タグをおすすめ理由に使う。全0のベクトルは類似度0となる。

### 7.2 ホームの抽選とメジャー候補

SakeDiscoveryService.discoverはcatalog.allで登録済みの基本情報とタグを取得し、ThreadLocalRandom.nextIntで1銘柄を等確率に選ぶ。価格やレビューは抽選・類似選定に使用しない。選んだ銘柄と、similarMajorで選ぶ1件をDiscovery recordで渡す。

メジャー候補はmajor-sake.jsonで名称・根拠・商品URL・購入先URL・確認日を持つ。公式商品情報と購入先の案内を確認し、定番として比較しやすいという編集基準で指定する。DBの銘柄名との完全一致で候補にし、IDへの依存を避ける。名称変更時は設定を更新する。重複名称、空の必須情報、不正なHTTPS URLは起動時に失敗する。外部設定app.major-sakeも使える。

### 7.3 類似度と例外

選んだ銘柄のベクトルをa、候補をbとする。タグ未登録・0・負の値を0に置き換え、全タグの二乗和で正規化して、cosine = Σ(a_t × b_t) / (√Σa_t² × √Σb_t²) を計算する。同じ銘柄IDは除き、正の共通タグがあり類似度が0より大きい候補だけを採用する。最大値を1件、同点はIDの小さい順で選ぶ。

理由は正の共通タグのうち積の大きい順、同点はタグ名順で最大2件。画面では実際の味を保証する数値として表示しない。カタログ0件は登録準備中、候補なし・タグ不足・1件のみは比較準備中。商品詳細のsimilarIdsは従来の編集者指定で、ホームの計算とは独立する。

## 8 設定と残課題

### 8.1 運用設定

application-local.propertiesで接続設定を上書きできる。DB_USERNAME、DB_PASSWORD等を環境に設定し、接続URLのAsia/Tokyoも確認する。既存DBには必要な差分SQLを適用し、未使用DBへの新規作成はRESET_DATABASE.mdに従う。詳細JSONを保存する既存環境にはsake_page_details_schema.sqlが必要。今回のガイド・比較機能はスキーマ変更不要。

起動時はAdminAccountInitializerが既存でなければ初期管理者を作る。運用時は既知の初期資格情報を変更する。DBとuploads/sake、利用する外部JSONをバックアップする。メール接続情報は環境設定で管理する。

### 8.2 検証と未提供の機能

Gradleの全テストでDB接続・表示・権限・保存・画像等を検証する。追加テストはメジャー候補限定、本人除外、タグ比率、同点、タグ不足、空・1件カタログ、匿名ガイドと比較画面を扱う。JSテストは診断、トピック、プロフィール画像を確認する。PC・スマートフォン幅で目次と比較表示を確認する。

残課題は準備中ページ、飲酒投稿・いいね・通報の操作経路、診断履歴一覧、トピック初期表示のランダム化、商品情報の精査、SMTPと公開環境の運用確認。全カタログを読む推薦・比較は現行規模向けで、件数増加時は取得件数・キャッシュ・索引を検討する。今回はこれらの未提供機能を追加したと記載しない。

参照資料：REQUIREMENTS.md、DB_DESIGN.md、BEGINNER_DISCOVERY.md、data/create_issho_no_deai.sql、data/SAKE_PAGES.md、docs/admin.md。
