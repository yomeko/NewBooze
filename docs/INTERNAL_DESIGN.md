# 一升の出会い 内部設計書

版数：第1.4版　更新日：2026年10月21日

本書はNewBoozeの現行コードを根拠に、DBアクセス、画面、クラス、エンドポイント、推薦処理を説明する。初期の暫定設計を置き換え、DB接続・JPA Entity・会員・レビュー・管理画面の実装を反映する。現行DDLは19テーブルで、従来18テーブルにsake_page_detailsを追加している。

## 1 文書の位置づけ

### 1.1 目的と対象

要件定義書 第1.7版の実装を開発チームが把握し、保守と発表の根拠に使えるようにする。対象はcom.example.demo配下、テンプレート、静的資産、dataのSQL、JSON設定。外部AIを使った推薦、準備中の案内ページの本機能、飲酒投稿の操作画面は完成済みとして扱わない。

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
| service | SakeCatalog、SakeDiscovery、SakePage、SakeInteraction、会員登録・メール認証、PasswordReset等のService |
| repository | Spring Data JPA Repository 17クラス |
| entity | JPA Entity 17クラスと複合IDクラス |
| model | 表示用Sake、詳細紹介SakePage、旧診断モデル |
| dto | SakePageDto、診断表示、入力フォーム、TastePresentation |
| security | CustomUserDetails、CustomUserDetailsService、AccountSessionValidationFilter、AuthenticationRateLimiterとFilter |
| validation | Utf8InputLength、Utf8ByteLength、BoundedImageDecoder等の入力検証 |
| config | SecurityConfig、AdminAccountInitializer |

## 3 データベース設計

### 3.1 方針とテーブル一覧

DDLをdata/create_issho_no_deai.sqlで管理し、Hibernateのddl-auto=validateは構造を検証する。起動時にテーブルを作成・変更しない。SQLの19テーブルは、過去のDB設計書の18テーブルに詳細紹介を加えたもの。19テーブルにJPA Entityが19クラス必要という意味ではなく、レビューと詳細紹介はJDBCで扱う。

| テーブル | 主キー | 主な役割 |
|---|---|---|
| users | id | name、email、password_hash、temporary_password、admin、メール認証状態・認証と再設定の各トークンハッシュ・期限・送信日時 |
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

匿名の診断はDBに保存しない。会員の診断ではセッションと回答を作成し、今回集計したタグの嗜好スコアを上書きする。今回登場しない過去のタグは残る。今回の推薦は保存済み嗜好ではなく、送信された回答を集計したMapを入力にする。保存方法は変更せず、診断前・結果・マイページで過去タグが残ることを説明する。匿名の結果には非保存と表示する。

商品詳細はsake_page_detailsに保存されていれば優先する。なければsake-pages.json、その銘柄も未登録ならDBの基本情報で補完する。レビューは1人・1銘柄で更新し、公開平均・件数・一覧はpublished=1だけを対象とする。

drink_posts、drink_post_likes、drink_post_reportsにはEntity・Repositoryがある。PostModerationServiceも存在するが、現行Controllerと画面に投稿・いいね・通報の操作経路はない。診断履歴は保存されるが、マイページはこれまでに保存された嗜好スコアとお気に入りを表示し、診断履歴一覧を提供していない。

## 4 画面設計

### 4.1 実装済み画面

| 画面とテンプレート | 主要内容 |
|---|---|
| ホーム home.html | 診断・検索、3トピック、NEWS（お知らせがない場合は空表示）、ガイド導線、ランダム1件と類似メジャー1件 |
| ガイド guide.html | 6段階の目次・本文・次の段階・出典 |
| 検索 search.html | 現在の検索条件の表示・保持、条件解除、6件のページング |
| 商品 detail.html | 基本情報と容量別価格、紹介、タグ、購入先、レビューとお気に入り |
| 診断 diagnosis.html | 共通4問＋回答別2問、戻る・次へ、分岐変更時の不要回答破棄、離脱確認 |
| 結果 diagnosis-result.html | 今回の傾向・回答説明・最大5件のおすすめ、保存方法の説明 |
| 認証 auth配下 | ログイン、会員登録、メール認証の確認・完了・再送、パスワード再設定の要求・確定 |
| マイページ mypage.html | 保存された嗜好と過去タグ保持の説明、お気に入り、プロフィール |
| 設定 account-settings.html | プロフィール、画像、パスワード、退会 |
| 管理 admin配下 | 管理トップ、ユーザー検索、銘柄登録 |
| 案内 collection.html | 各準備中ページの共通表示とメニューの準備中ラベル |
| プライバシー privacy.html | 登録前にも読める情報の利用目的と公開範囲 |

### 4.2 共通UIと導線

site-headerの検索、横スクロールナビ、標準dialogのその他メニューを全画面で使う。メニューは枠・文字のない2本線とし、マウスホバーで非モーダル表示する。ボタンからメニューへ移動中は180msの猶予を設け、外へ移動すると閉じる。タッチ・クリック・Enter・下矢印でも開き、閉じるボタン・Escape・外側クリックで閉じる。キーボードで閉じるとボタンへフォーカスを戻す。ナビ上部の罫線とヘッダー・メニュー・ホームカードの装飾影は表示しない。ホームのTOPICSとNEWSの見出しはゴシック体とし、NEWSの内容がない場合は「現在、お知らせはありません。」を表示する。初心者ガイドはホームと共通メニューから開き、商品詳細からbuy・store・openへ直接進める。ガイドは通常のアンカーリンクで、JSなしでも読める。

スマートフォン用の非表示・ドロップダウン指定は.site-header .main-navに限定する。検索のページ送りは全幅で表示して必要に応じ折り返し、現在ページにaria-currentを付ける。設定画面のマイページに戻るリンクも全幅で表示する。

home.jsは3記事を左右・ドット・キー・スワイプで切り替える。初期は手動、再生で7秒間隔。動きを減らす設定では自動を停止する。初期トピックのランダム化は未実装。銘柄比較の写真と3項目の5段階表示はsake-comparisonを共通利用する。タグ未登録の項目は未登録と表示する。

## 5 クラスと保存処理

### 5.1 日本酒情報

entity.Sakeはsakeに対応するJPA Entity。酒蔵・酒種はManyToOneで、公開レビューの平均・件数・有無はHibernate Formulaにより読み取り時に計算する。model.Sakeは基本情報、タグMap、公開評価を持つ読み取り用record。SakePageは飲み方・料理・リンク等を持つ紹介用recordで、空項目、重複、不正なURL等を整理する。

SakeCatalogService.searchは条件の空白と#タグ入力を整え、RepositoryのPageをSakePageDtoへ変換する。ページ番号は0始まり、負の指定は0、範囲超過は最終ページ。SakePageDtoは独自recordでありPageインターフェースの実装ではない。Repositoryでは実際のSpring Data Pageを使用する。search.htmlのキーワード入力を含む検索欄に現在値を戻し、条件の再送信・並べ替え・価格帯追加で維持する。すべての条件を解除するリンクも設ける。価格帯は参考価格と表示し、検索・価格帯・価格順にはDBの価格を使う。

詳細の基本情報はmodel.Sakeの酒種・産地・度数を使う。度数が0以下なら未確認とし、登録された参考値である旨を付記する。SakePage.pricesに容量別の税込価格があれば表示し、容量または金額が空なら未確認と表示する。pricesが空ならDBの参考価格を対象容量未確認として表示する。価格の変動や一覧との相違、購入前の公式情報確認を案内する。今回の変更ではDB値やスキーマを変更しない。

### 5.2 認証と本人の情報

AuthControllerは入力検証後に会員登録Serviceへ処理を委譲する。Serviceはemail重複確認、BCrypt保存、未認証状態と認証トークンの保存、SMTP送信を同じトランザクションで行い、送信失敗時は登録をロールバックする。登録後は確認メールの案内を表示し、SecurityContextを作成する自動ログインは行わない。SignupFormの@Size・@Utf8ByteLengthと共通Utf8InputLengthで、ハッシュ化前に8文字以上・UTF-8で72バイト以内を検証する。上限超過時は登録画面をHTTP 200で再表示し、入力欄に理由を示す。

認証トークンはSecureRandomの32バイトからURL-safe Base64の43文字を作る。DBにはSHA-256の64文字ハッシュ、有効期限、送信日時を保存し、生のトークンは保持しない。有効期間は24時間で、GET /verify-email?token=は確認画面だけを表示する。CSRF付きPOST /verify-emailで行ロック下に未使用・期限内を検証し、認証済みにしてトークン情報を消去する。認証後も自動ログインせずログイン画面へ案内する。

POST /verify-email/resendは前後の空白を除いたemailの形式と255文字の上限を検証し、未入力は入力エラーを案内する。255文字以内の入力値と結果を一度限りのFlash属性で保持し、URLには含めない。すべての再送POST後はGET /verify-emailへ移動する（PRG）。ページ更新では再送しない。

Serviceは行ロック下で未認証かつ前回送信から60秒以上経過した場合だけ送る。未登録・認証済み・間隔未満・送信成功には同じ条件付きの受付案内を表示し、送信例外は共通のメールエラーを案内する。再送成功時だけトークンの置換が確定して旧リンクが無効になり、失敗時はハッシュ・期限・送信日時を戻す。リンクは受信したHostヘッダーに依存させず、固定設定app.base-urlから構築する。

ログインはSecurityConfigとCustomUserDetailsServiceで処理し、CustomUserDetails.isEnabledはemailVerifiedを返す。未認証会員はパスワードが一致してもログインできない。旧既定パスワードの管理者も拒否する。既存ユーザーはメール認証移行SQLで認証済みとして保持し、明示的な初回設定で作る管理者も認証済みとする。旧機能で発行済みの仮パスワード使用中は変更案内付きマイページ、管理者は管理トップ、一般会員はホームへ移動する。新規の仮パスワード発行は行わない。

CustomUserDetailsはログイン時点のユーザーID・メールアドレス・ハッシュ・認証状態・権限を保持する。AccountSessionValidationFilterは静的ファイルを除く認証済みリクエストでDBを参照し、会員の削除、パスワードハッシュ・メールアドレス・認証状態・権限の変更を確認するとセッションとSecurityContextを破棄し、/login?sessionExpiredへ移す。旧既定パスワードの管理者セッションも拒否する。別ブラウザに残ったセッションを変更前の権限で使い続けることを防ぐ。

MyPageControllerは更新対象のuser_idをログイン情報から取得する。プロフィールと画像、パスワード、退会を扱う。メールアドレス変更はServiceのトランザクション内で未認証化と新しい宛先への送信を行い、未使用のパスワード再設定トークンを無効にする。送信成功後はセッションを終了して確認メールを案内し、SMTP失敗時は変更前のemailと認証状態へロールバックする。パスワード変更と退会では現在のパスワードを照合する。変更するパスワードにも同じUtf8InputLengthの72バイト検証を使い、入力不正は設定画面に説明する。パスワード変更時にも未使用の再設定トークンを無効にする。

画像はJPEG・PNG・GIFで最大20MBを受信し、BoundedImageDecoderが実際の画像形式と2000万画素以下をメモリー展開前に確認する。GIFは論理画面と全フレームの寸法も検証し、最初のフレームだけ展開する。壊れた画像は入力エラーにし、保存MIMEは実形式から決める。900KBを超える場合は初期長辺1600px以下を目標にJPEGへ圧縮し、画質と寸法を段階的に下げて900KB以下で保存する。900KB以下のGIFは元のバイト列を保持してアニメーションを維持する。画像変更時は位置・拡大率を中央・等倍へ戻し、表示調整は別フィールドに保存する。

PasswordResetServiceは認証済み会員にだけSecureRandomの32バイトから43文字のURL-safeトークンを発行する。DBにはSHA-256の64文字ハッシュ・30分の期限・送信日時を保存し、会員行のロック下で前回送信から60秒以上あいた場合だけ送る。要求時に既存のpassword_hashを変更しない。再送成功時に旧トークンを置換し、SMTP例外時はトランザクションを戻す。送信先・トークンを含まない共通の受付案内を未登録・未認証・間隔未満・SMTP失敗にも表示し、GETへリダイレクトする。

GET /reset-password?token=は形式を確認してトークンをセッションへ置き、/reset-passwordへ移動してURLから除く。このGETはパスワード変更・トークン消費を行わない。フォームにはトークンを埋め込まず、CSRF保護付きPOST /reset-passwordでセッションのトークン、新パスワードと確認入力を使う。Serviceは行ロック下で未使用・期限内・認証済みを確認してBCryptハッシュを保存し、temporary_password=falseとする。成功時は再設定ハッシュと期限を消去し、60秒制限のため送信日時は残す。セッションのトークンを除いて/login?passwordResetへ移動し、自動ログインしない。再設定関連ページはCache-Control: no-storeとReferrer-Policy: no-referrerを返す。リンクは固定app.base-urlから作り、HTTPSまたはlocalhostのHTTPだけを許可する。

### 5.3 レビューと管理登録

SakeInteractionServiceはJdbcTemplateで本人のレビューと公開一覧を扱う。入力は星1〜5・コメント最大500文字で、登録済みなら更新する。公開一覧は10件、新しい順・評価高い順・低い順で表示する。フォームの公開初期値はtrue、DBの初期値はfalseである。

AdminControllerは酒種・タグ・URL・画像を検証し、銘柄をsaveAndFlush、詳細JSONを保存、選択タグを選択順に応じたscoreで登録する。酒蔵名が新規なら作成する。DB処理はトランザクション化し、保存失敗時は保存済み画像の削除も試みる。商品画像はJPEG・PNG、最大5MB・2000万画素でuploads/sakeへ保存する。

登録フォームはクリックした選択順のtagIdsを送信する。N個選択時は選択順にN、N−1、…、1をsake_tags.scoreへ保存する。解除時は残りの順序で重みを付け直し、再選択時は末尾に追加する。入力エラーでの再表示にも送信された順序を保持する。

### 5.4 認証操作の回数制限

AuthenticationRateLimitFilterはCSRF確認後、認証・SMTP送信より前に次のPOSTを制限する。ログイン成功・失敗にかかわらず、許可された要求を数える。

| POSTのURL | 制限単位と回数 |
|---|---|
| /login | IPごと20回／5分、ログインIDごと10回／5分の両方 |
| /signup、/forgot-password | URL・IPごと5回／15分 |
| /reset-password、/verify-email/resend | URL・IPごと10回／15分 |
| /verify-email | IPごと20回／15分 |

超過はHTTP 429、待ち時間のRetry-After、no-storeと日本語案内を返す。AuthenticationRateLimiterはIPやログインIDの原文を保持せずSHA-256で管理キーを作り、最大20000件をプロセス内メモリーに保持する。満杯時は有効な制限を追い出さず、新しい要求を待たせる。再起動で記録は消え、複数インスタンスでは共有の制限機構が必要となる。IPはrequest.getRemoteAddr()を使い、X-Forwarded-For等を直接信用しない。単一プロキシのIPに全利用者が集約される構成では正当な利用も同じ制限になるため、公開前に信頼するプロキシと利用者IPの取得方法を決めて検証する。

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
| GETとPOST | /signup | 会員登録と確認メール案内 |
| GET | /verify-email | tokenを検証し確認画面を表示、未消費 |
| POST | /verify-email | CSRF付き認証確定、一度限り |
| POST | /verify-email/resend | 再送受付、60秒間隔、GETへ移動 |
| GETとPOST | /forgot-password | 再設定リンクの要求と共通の受付案内 |
| GET | /reset-password | 初回tokenをセッションへ移してURLから除き、再設定フォームを表示 |
| POST | /reset-password | CSRF付き新パスワード確定、一度限り |
| POST | /logout | セッションからログアウト |

商品が存在しなければ404。診断の回答が空・重複・未回答・別経路の混入なら保存せず説明付き質問画面を表示する。結果表示はPOST /diagnosis/resultであり、旧案のPOST /diagnosis/answerとGET /diagnosis/resultは存在しない。

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
| POST | /mypage/profile | プロフィール変更、メール変更時は未認証化・送信後ログアウト |
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
| GET | /about、/external-transmission | 公開、準備中の案内 |
| GET | /privacy | 公開、現行機能の情報利用と公開範囲の説明 |

## 7 推薦とランダム比較

### 7.1 診断のデータフロー

DiagnosisService.questionsは設問の表示順と分岐元選択肢IDを返す。main.jsは先行回答で経路を組み立て、不要な分岐の選択を消して送信対象から外す。validAnswersでサーバー側でも有効経路の各問1回答を検証する。DiagnosisServiceはchoice_tags.weightをタグ別に加算する。会員の回答をdiagnosis_sessionsとdiagnosis_answersへ保存し、user_preferencesの該当タグを更新する。recommendは今回の集計とcatalog.allのsake_tags.scoreを比較し、コサイン類似度の降順で5件を返す。TastePresentationが正の共通タグをおすすめ理由に使う。全0のベクトルは類似度0となる。TastePresentation.quadrantは甘口と辛口、軽快と濃醇の得点比を各軸10〜90%に変換する。香り・うまみ・酸味は推薦には使うが4象限の軸には混ぜない。設計根拠・計算式・追加SQLはDIAGNOSIS_DESIGN.mdを参照する。

### 7.2 ホームの抽選とメジャー候補

SakeDiscoveryService.discoverはcatalog.allで登録済みの基本情報とタグを取得し、ThreadLocalRandom.nextIntで1銘柄を等確率に選ぶ。価格やレビューは抽選・類似選定に使用しない。選んだ銘柄と、similarMajorで選ぶ1件をDiscovery recordで渡す。

メジャー候補はmajor-sake.jsonで名称・根拠・商品URL・購入先URL・確認日を持つ。公式商品情報と購入先の案内を確認し、定番として比較しやすいという編集基準で指定する。DBの銘柄名との完全一致で候補にし、IDへの依存を避ける。名称変更時は設定を更新する。重複名称、空の必須情報、不正なHTTPS URLは起動時に失敗する。外部設定app.major-sakeも使える。

### 7.3 類似度と例外

選んだ銘柄のベクトルをa、候補をbとする。タグ未登録・0・負の値を0に置き換え、全タグの二乗和で正規化して、cosine = Σ(a_t × b_t) / (√Σa_t² × √Σb_t²) を計算する。同じ銘柄IDは除き、正の共通タグがあり類似度が0より大きい候補だけを採用する。最大値を1件、同点はIDの小さい順で選ぶ。

理由は正の共通タグのうち積の大きい順、同点はタグ名順で最大2件。画面では実際の味を保証する数値として表示しない。カタログ0件は登録準備中、候補なし・タグ不足・1件のみは比較準備中。商品詳細のsimilarIdsは従来の編集者指定で、ホームの計算とは独立する。

## 8 設定と残課題

### 8.1 運用設定

application-local.propertiesで接続設定を上書きできる。DB_USERNAME、DB_PASSWORD等を環境に設定し、接続URLのAsia/Tokyoも確認する。既存DBには必要な差分SQLを適用し、未使用DBへの新規作成はRESET_DATABASE.mdに従う。詳細JSONを保存する既存環境にはsake_page_details_schema.sqlが必要。メール認証では起動前にdata/email_verification_schema.sqlでusersの4列とトークンハッシュの一意索引を追加する。既存ユーザーは認証済みとして保持し、ddl-auto=validateを維持する。

パスワード再設定では起動前にdata/password_reset_token_schema.sqlでusersのNULL許容3列とハッシュの一意索引を追加する。既存ユーザー・パスワードは変更しない。対象DBとバックアップを確認し、未適用のDBに一度だけ実行する。今回の文書更新自体は実DB適用・再照合の完了を意味しない。

Gmail SMTPはsmtp.gmail.comの587番、認証有効、STARTTLS必須とし、接続・読み取り・書き込みのタイムアウトを各5000ミリ秒にする。送信元とアプリパスワードはGit管理外のapplication-local.propertiesへローカル入力し、scripts/configure-gmail.pyを使える。通常のGoogleログイン用パスワードは使わない。固定app.base-urlは開発環境でhttp://localhost:8080、公開環境では利用者が到達できるHTTPS URLを設定する。手順と実送信の確認項目はemail-verification.mdを参照する。

AdminAccountInitializerは既定で無効。初回だけADMIN_BOOTSTRAP_ENABLED=trueとADMIN_EMAIL・ADMIN_PASSWORD・ADMIN_NAMEを設定する。メールアドレスと表示名を検証し、パスワードは12文字以上・UTF-8で72バイト以内、英大文字・英小文字・数字・記号のうち3種類以上とし、既知の既定値や単純な繰り返しを拒否する。同じメールアドレスが存在すればパスワード・権限を変更しない。制限した環境で一意のメールアドレスを持つ新管理者を作成し、動作確認後に初期作成を無効にして資格情報設定を除く。旧既定パスワードの管理者はログイン・セッションを拒否するがレコードは自動変更しないため、公開前に旧権限の解除などを明示的に行う。手順はadmin.mdとSECURITY.mdを参照する。

DBとuploads/sake、利用する外部JSONをバックアップする。接続情報・SMTP情報・管理者初期設定は秘密として管理する。HTTPS、Cookie、信頼するプロキシ、複数インスタンスの制限共有は公開先の構成で確認する。

### 8.2 検証と未提供の機能

Gradleの全テストでDB接続・表示・権限・保存・画像等を検証する。追加テストはメジャー候補限定、本人除外、タグ比率、同点、タグ不足、空・1件カタログ、匿名ガイドと比較画面を扱う。JSテストは診断、トピック、プロフィール画像を確認する。PC・スマートフォン幅で目次と比較表示を確認する。

残課題は準備中ページ、プライバシーポリシーの運用情報（連絡先・保存期間・委託先）の確認、飲酒投稿・いいね・通報の操作経路、診断履歴一覧、トピック初期表示のランダム化、参考価格・度数等のデモ商品情報の精査、SMTPと公開環境の運用確認。全カタログを読む推薦・比較は現行規模向けで、件数増加時は取得件数・キャッシュ・索引を検討する。今回はこれらの未提供機能を追加したと記載しない。

参照資料：REQUIREMENTS.md、DB_DESIGN.md、BEGINNER_DISCOVERY.md、data/create_issho_no_deai.sql、data/SAKE_PAGES.md、docs/admin.md、docs/SECURITY.md。
