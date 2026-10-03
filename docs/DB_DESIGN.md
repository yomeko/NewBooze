# 一升の出会い DB設計書

作成日：2026年10月3日

## 1. 対象と前提

本書は NewBooze（日本酒紹介・好み診断アプリ）のDB設計を、既存の作成SQLと実装に基づいて整理したものです。定義の基準は `data/create_issho_no_deai.sql` です。実稼働DBへの接続・定義照合は行っていません。

- DB：MySQL互換（MySQL / MariaDB）。この設計書では特定のサーバーバージョンを仮定しません。
- 新規作成SQLのDB名：`issho_no_deai`。アプリの既定接続先：`newbooze`（ローカル設定で変更可能）。
- ストレージエンジン：InnoDB。文字コード：utf8mb4。照合順序：utf8mb4_unicode_ci。
- 時刻：新規作成SQLのセッションは日本時間（+09:00）。接続時にも時刻設定を統一します。
- 全18テーブル。単独の `id` は BIGINT UNSIGNED・自動採番。中間テーブルは複合主キーを使用します。
- 既存DBを変更するための資料ではなく、新規作成SQLの構造を説明する設計書です。

## 2. テーブル一覧

| テーブル | 役割 |
|---|---|
| `users` | ユーザー情報と認証 |
| `breweries` | 酒蔵マスタ |
| `sake_types` | 酒種マスタ |
| `tags` | 味わい・香りのタグマスタ |
| `diagnosis_questions` | 好み診断の設問 |
| `sake` | 日本酒カタログ |
| `diagnosis_choices` | 設問ごとの選択肢 |
| `diagnosis_sessions` | ユーザーの診断実施履歴 |
| `drink_posts` | 飲酒投稿 |
| `user_profile_images` | ユーザーのプロフィール画像と表示位置 |
| `choice_tags` | 選択肢とタグの対応・加点重み |
| `diagnosis_answers` | 診断ごとの回答履歴 |
| `drink_post_likes` | 投稿へのいいね |
| `drink_post_reports` | 投稿への通報 |
| `favorites` | 日本酒のお気に入り |
| `sake_tags` | 日本酒とタグの対応・特徴スコア |
| `user_preferences` | ユーザーのタグ別嗜好スコア |
| `sake_reviews` | 日本酒の評価・レビューと公開設定 |

## 3. ER図

実線は識別関係（子の主キーに親のキーを含む）、点線は非識別関係です。任意の酒蔵・プロフィール画像を含めて、多重度を示しています。

```mermaid
erDiagram
    breweries |o..o{ sake : "brewery_id"
    sake_types ||..o{ sake : "sake_type_id"
    diagnosis_questions ||..o{ diagnosis_choices : "question_id"
    users ||..o{ diagnosis_sessions : "user_id"
    users ||..o{ drink_posts : "user_id"
    users ||--o| user_profile_images : "user_id"
    diagnosis_choices ||--o{ choice_tags : "choice_id"
    tags ||--o{ choice_tags : "tag_id"
    diagnosis_sessions ||..o{ diagnosis_answers : "session_id"
    diagnosis_questions ||..o{ diagnosis_answers : "question_id"
    diagnosis_choices ||..o{ diagnosis_answers : "choice_id"
    users ||--o{ drink_post_likes : "user_id"
    drink_posts ||--o{ drink_post_likes : "post_id"
    users ||..o{ drink_post_reports : "reporter_id"
    drink_posts ||..o{ drink_post_reports : "post_id"
    users ||--o{ favorites : "user_id"
    sake ||--o{ favorites : "sake_id"
    sake ||--o{ sake_tags : "sake_id"
    tags ||--o{ sake_tags : "tag_id"
    users ||--o{ user_preferences : "user_id"
    tags ||--o{ user_preferences : "tag_id"
    users ||--o{ sake_reviews : "user_id"
    sake ||--o{ sake_reviews : "sake_id"
```

## 4. テーブル定義

「必須」は NOT NULL を示します。PK＝主キー、FK＝外部キー。BOOLEAN は真偽値です。デフォルトなしの必須項目は登録時に値を指定します。

### 4.1 `users` — ユーザー情報と認証

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `name` | VARCHAR(50) | ○ | — | — |
| `email` | VARCHAR(255) | ○ | — | — |
| `password_hash` | VARCHAR(255) | ○ | — | — |
| `temporary_password` | TINYINT(1) | ○ | 0 | — |
| `created_at` | DATETIME | ○ | CURRENT_TIMESTAMP | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_users_email (email)`

### 4.2 `breweries` — 酒蔵マスタ

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `name` | VARCHAR(100) | ○ | — | — |
| `prefecture` | VARCHAR(50) | — | NULL | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_breweries_name (name)`

### 4.3 `sake_types` — 酒種マスタ

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `name` | VARCHAR(50) | ○ | — | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_sake_types_name (name)`

### 4.4 `tags` — 味わい・香りのタグマスタ

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `name` | VARCHAR(50) | ○ | — | — |
| `category` | VARCHAR(30) | ○ | — | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_tags_name (name)`

### 4.5 `diagnosis_questions` — 好み診断の設問

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `question_text` | VARCHAR(255) | ○ | — | — |
| `sort_order` | INT | ○ | 0 | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_diagnosis_questions_text (question_text)`
- `UNIQUE KEY uk_diagnosis_questions_sort (sort_order)`

### 4.6 `sake` — 日本酒カタログ

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `brewery_id` | BIGINT UNSIGNED | — | NULL | FK → breweries.id |
| `sake_type_id` | BIGINT UNSIGNED | ○ | — | FK → sake_types.id |
| `name` | VARCHAR(100) | ○ | — | — |
| `region` | VARCHAR(50) | — | NULL | — |
| `abv` | DECIMAL(4,1) | — | NULL | — |
| `price` | INT UNSIGNED | — | NULL | — |
| `description` | TEXT | — | NULL | — |
| `image_url` | VARCHAR(255) | — | NULL | — |
| `created_at` | DATETIME | ○ | CURRENT_TIMESTAMP | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_sake_name (name)`
- `KEY idx_sake_brewery (brewery_id)`
- `KEY idx_sake_type (sake_type_id)`

親レコード削除時：

- `breweries.id`（`brewery_id`）：参照カラムをNULLに変更。
- `sake_types.id`（`sake_type_id`）：参照中は親レコードの削除を拒否。

### 4.7 `diagnosis_choices` — 設問ごとの選択肢

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `question_id` | BIGINT UNSIGNED | ○ | — | FK → diagnosis_questions.id |
| `choice_text` | VARCHAR(255) | ○ | — | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_diagnosis_choice (question_id, choice_text)`

親レコード削除時：

- `diagnosis_questions.id`（`question_id`）：子レコードも削除。

### 4.8 `diagnosis_sessions` — ユーザーの診断実施履歴

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `user_id` | BIGINT UNSIGNED | ○ | — | FK → users.id |
| `taken_at` | DATETIME | ○ | CURRENT_TIMESTAMP | — |

一意制約・索引・値制約：

- `KEY idx_diagnosis_sessions_user (user_id)`

親レコード削除時：

- `users.id`（`user_id`）：子レコードも削除。

### 4.9 `drink_posts` — 飲酒投稿

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `user_id` | BIGINT UNSIGNED | ○ | — | FK → users.id |
| `sake_name` | VARCHAR(100) | ○ | — | — |
| `comment` | VARCHAR(500) | — | NULL | — |
| `created_at` | DATETIME | ○ | CURRENT_TIMESTAMP | — |

一意制約・索引・値制約：

- `KEY idx_drink_posts_user (user_id)`

親レコード削除時：

- `users.id`（`user_id`）：子レコードも削除。

### 4.10 `user_profile_images` — ユーザーのプロフィール画像と表示位置

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `user_id` | BIGINT UNSIGNED | ○ | — | PK, FK → users.id |
| `image_data` | MEDIUMBLOB | ○ | — | — |
| `content_type` | VARCHAR(50) | ○ | — | — |
| `updated_at` | DATETIME | ○ | CURRENT_TIMESTAMP / 更新時に現在時刻 | — |
| `position_x` | INT | ○ | 50 | — |
| `position_y` | INT | ○ | 50 | — |
| `zoom` | INT | ○ | 100 | — |

親レコード削除時：

- `users.id`（`user_id`）：子レコードも削除。

### 4.11 `choice_tags` — 選択肢とタグの対応・加点重み

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `choice_id` | BIGINT UNSIGNED | ○ | — | PK, FK → diagnosis_choices.id |
| `tag_id` | BIGINT UNSIGNED | ○ | — | PK, FK → tags.id |
| `weight` | TINYINT UNSIGNED | ○ | 1 | — |

一意制約・索引・値制約：

- `KEY idx_choice_tags_tag (tag_id)`

親レコード削除時：

- `diagnosis_choices.id`（`choice_id`）：子レコードも削除。
- `tags.id`（`tag_id`）：子レコードも削除。

### 4.12 `diagnosis_answers` — 診断ごとの回答履歴

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `session_id` | BIGINT UNSIGNED | ○ | — | FK → diagnosis_sessions.id |
| `question_id` | BIGINT UNSIGNED | ○ | — | FK → diagnosis_questions.id |
| `choice_id` | BIGINT UNSIGNED | ○ | — | FK → diagnosis_choices.id |

一意制約・索引・値制約：

- `UNIQUE KEY uk_diagnosis_answer_session_question (session_id, question_id)`
- `KEY idx_diagnosis_answers_question (question_id)`
- `KEY idx_diagnosis_answers_choice (choice_id)`

親レコード削除時：

- `diagnosis_sessions.id`（`session_id`）：子レコードも削除。
- `diagnosis_questions.id`（`question_id`）：参照中は親レコードの削除を拒否。
- `diagnosis_choices.id`（`choice_id`）：参照中は親レコードの削除を拒否。

### 4.13 `drink_post_likes` — 投稿へのいいね

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `user_id` | BIGINT UNSIGNED | ○ | — | PK, FK → users.id |
| `post_id` | BIGINT UNSIGNED | ○ | — | PK, FK → drink_posts.id |
| `created_at` | DATETIME | ○ | CURRENT_TIMESTAMP | — |

一意制約・索引・値制約：

- `KEY idx_drink_post_likes_post (post_id)`

親レコード削除時：

- `users.id`（`user_id`）：子レコードも削除。
- `drink_posts.id`（`post_id`）：子レコードも削除。

### 4.14 `drink_post_reports` — 投稿への通報

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `id` | BIGINT UNSIGNED | ○ | 自動採番 | PK |
| `reporter_id` | BIGINT UNSIGNED | ○ | — | FK → users.id |
| `post_id` | BIGINT UNSIGNED | ○ | — | FK → drink_posts.id |
| `reason` | VARCHAR(30) | ○ | — | — |
| `created_at` | DATETIME | ○ | CURRENT_TIMESTAMP | — |

一意制約・索引・値制約：

- `UNIQUE KEY uk_drink_post_reporter (reporter_id, post_id)`
- `KEY idx_drink_post_reports_post (post_id)`

親レコード削除時：

- `users.id`（`reporter_id`）：子レコードも削除。
- `drink_posts.id`（`post_id`）：子レコードも削除。

### 4.15 `favorites` — 日本酒のお気に入り

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `user_id` | BIGINT UNSIGNED | ○ | — | PK, FK → users.id |
| `sake_id` | BIGINT UNSIGNED | ○ | — | PK, FK → sake.id |
| `created_at` | DATETIME | ○ | CURRENT_TIMESTAMP | — |

一意制約・索引・値制約：

- `KEY idx_favorites_sake (sake_id)`

親レコード削除時：

- `users.id`（`user_id`）：子レコードも削除。
- `sake.id`（`sake_id`）：子レコードも削除。

### 4.16 `sake_tags` — 日本酒とタグの対応・特徴スコア

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `sake_id` | BIGINT UNSIGNED | ○ | — | PK, FK → sake.id |
| `tag_id` | BIGINT UNSIGNED | ○ | — | PK, FK → tags.id |
| `score` | TINYINT UNSIGNED | ○ | 3 | — |

一意制約・索引・値制約：

- `KEY idx_sake_tags_tag (tag_id)`

親レコード削除時：

- `sake.id`（`sake_id`）：子レコードも削除。
- `tags.id`（`tag_id`）：子レコードも削除。

### 4.17 `user_preferences` — ユーザーのタグ別嗜好スコア

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `user_id` | BIGINT UNSIGNED | ○ | — | PK, FK → users.id |
| `tag_id` | BIGINT UNSIGNED | ○ | — | PK, FK → tags.id |
| `score` | INT | ○ | 0 | — |

一意制約・索引・値制約：

- `KEY idx_user_preferences_tag (tag_id)`

親レコード削除時：

- `users.id`（`user_id`）：子レコードも削除。
- `tags.id`（`tag_id`）：子レコードも削除。

### 4.18 `sake_reviews` — 日本酒の評価・レビューと公開設定

| カラム | 型 | 必須 | 初期値・自動設定 | キー |
|---|---|---|---|---|
| `user_id` | BIGINT UNSIGNED | ○ | — | PK, FK → users.id |
| `sake_id` | BIGINT UNSIGNED | ○ | — | PK, FK → sake.id |
| `published` | BOOLEAN | ○ | FALSE | — |
| `rating` | TINYINT UNSIGNED | ○ | — | — |
| `comment` | VARCHAR(500) | ○ | '' | — |
| `updated_at` | TIMESTAMP | ○ | CURRENT_TIMESTAMP / 更新時に現在時刻 | — |

一意制約・索引・値制約：

- `KEY idx_reviews_public (sake_id, published, updated_at, user_id)`
- `CONSTRAINT chk_sake_reviews_rating CHECK (rating BETWEEN 1 AND 5)`

親レコード削除時：

- `users.id`（`user_id`）：子レコードも削除。
- `sake.id`（`sake_id`）：子レコードも削除。

## 5. 機能と保存ルール

- 会員登録：メールアドレスは一意。パスワードは `password_hash` にハッシュを保存し、仮パスワード状態は `temporary_password` で管理します。
- 日本酒検索：`sake` を中心に酒蔵・酒種・タグを参照します。酒蔵は未設定を許容します。
- 診断：設問 → 選択肢 → タグの重みを集計します。ログイン中の回答は `diagnosis_sessions` と `diagnosis_answers` に保存します。未ログイン時は診断結果をDB保存しません。
- 回答：1回の診断につき同じ設問の回答は1件。選択肢の所属設問との一致はサービスが選択肢から設問を取得して保証しており、現行FKだけでは一致を保証しません。
- 嗜好：集計対象タグの `user_preferences.score` を上書きします。今回の集計に含まれない過去のタグ行は現行実装では残ります。履歴そのものは回答テーブルに保持します。
- お気に入り・いいね：複合主キーで同一ユーザーによる同一対象の重複登録を防止します。
- 通報：同一投稿への同一ユーザーの通報は1件。理由は文字列で保持します。
- レビュー：1ユーザー・1商品につき1件。再登録は更新。評価は1〜5、`published` の初期値は非公開。公開一覧・平均評価は公開レビューのみを集計します。
- 飲酒投稿：商品IDではなく `sake_name` を保存します。カタログ未登録の日本酒についても投稿でき、商品削除や名称変更の影響を受けません。
- プロフィール画像：1ユーザーにつき最大1件。画像本体・MIMEタイプ・表示位置・拡大率を保存します。

## 6. 設計上の注意と改善候補

以下は現行定義に含まれない改善案です。適用する場合は別途マイグレーションと実装変更が必要です。

| 項目 | 現状 | 改善候補 |
|---|---|---|
| 回答の整合性 | 設問と選択肢を個別のFKで参照 | 選択肢側に `(question_id, id)` の一意キーを設け、回答側から複合FKで参照する |
| 嗜好の更新 | 集計に含まれない古いタグのスコアが残る | 最新診断だけを表すなら、トランザクション内で旧嗜好を削除して今回分に置換する |
| 数値の範囲 | 評価のみCHECKあり | 運用範囲を決定後、度数・タグ重み・画像位置・拡大率にも制約を設ける |
| 診断履歴の保持 | 使用済み設問・選択肢はFKにより削除が制限される | 設問を無効化するフラグや版管理を追加し、回答履歴を維持する |
| 商品の識別 | 商品名が全体で一意 | 同名商品を扱う場合は商品コードなどを導入し、名称の一意制約を見直す |
| 一覧性能 | 投稿・診断履歴はユーザーIDの索引のみ | 実際の件数とクエリ計画に応じて `(user_id, created_at, id)` 等を追加する |
| タイムスタンプ | DATETIMEとTIMESTAMPが混在 | 接続タイムゾーンを統一し、必要に応じて保存方式を統一する |

## 7. 作成SQLと運用

新規DB作成用SQLは [create_issho_no_deai.sql](../data/create_issho_no_deai.sql)、手順は [RESET_DATABASE.md](../data/RESET_DATABASE.md) を参照してください。作成SQLには全18テーブルと日本酒10件、診断4問・選択肢12件、酒種・タグの初期データが含まれます。ユーザーや投稿・レビューの初期データはありません。酒蔵マスタは空です。

`CREATE DATABASE` は同名DBがある場合に失敗します。既存DBの削除・変更はせず、未使用DB名で実行します。旧DB向けの差分SQLは型や照合順序が異なるため、本書の新規作成SQLと混在させないでください。アプリは `ddl-auto=validate` を使用し、起動時にエンティティとDB定義の整合性を検証します。
