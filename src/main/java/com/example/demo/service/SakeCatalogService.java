package com.example.demo.service;

import com.example.demo.dto.SakePageDto;
import com.example.demo.model.Sake;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 地酒検索・詳細取得・推薦（DiagnosisServiceから利用）を担当する暫定Service。
 *
 * 濵田担当のデータ収集・DB登録（sake／sake_tagsテーブルへの投入）が完了していないため、
 * 現時点ではインメモリのダミーデータ（model.Sake）で動作させている。
 * データ収集完了後は、entity.Sake + SakeRepositoryを使ったDB連携実装へ差し替え、
 * このクラス自体は撤去、またはDB版への薄いラッパーに置き換える想定
 * （内部設計書 第8章「今後の課題」参照）。
 */
@Service
public class SakeCatalogService {

    /** S04検索結果一覧の1ページあたりの表示件数 */
    private static final int PAGE_SIZE = 6;

    // ダミーの地酒データ10件。sake(...)ヘルパーで tagScores（タグ名→強さ）を組み立てている。
    private final List<Sake> sakes = List.of(
            sake(1, "獺祭 純米大吟醸45", "純米大吟醸", "山口県", 16.0, 2180, "華やかな香りと、透明感のあるやわらかな甘みが特徴です。", "フルーティー", 5, "甘口", 4, "軽快", 3),
            sake(2, "新政 No.6", "生酒", "秋田県", 13.0, 2500, "爽やかな酸味とみずみずしい口当たりを楽しめる生酒です。", "酸味", 5, "フルーティー", 4, "軽快", 4),
            sake(3, "十四代 本丸", "本醸造", "山形県", 15.0, 3200, "やさしい旨みと上品な甘みが広がる、なめらかな味わいです。", "甘口", 5, "旨口", 4, "フルーティー", 3),
            sake(4, "黒龍 いっちょらい", "吟醸", "福井県", 15.0, 1650, "すっきりとした切れ味と、穏やかな吟醸香のバランスが魅力です。", "辛口", 4, "軽快", 5, "フルーティー", 2),
            sake(5, "而今 特別純米", "特別純米", "三重県", 16.0, 2800, "果実感のある香りに、米のふくらみと心地よい余韻が続きます。", "フルーティー", 4, "旨口", 4, "酸味", 3),
            sake(6, "田酒 特別純米", "特別純米", "青森県", 16.0, 1900, "米の旨みをしっかり感じられる、落ち着いた食中酒です。", "旨口", 5, "辛口", 3, "濃醇", 4),
            sake(7, "出羽桜 桜花吟醸", "吟醸", "山形県", 15.0, 1500, "華やかな香りと軽やかな飲み口で、日本酒入門にもおすすめです。", "フルーティー", 5, "軽快", 4, "甘口", 3),
            sake(8, "八海山 特別本醸造", "特別本醸造", "新潟県", 15.5, 1400, "淡麗でキレが良く、料理に寄り添うすっきりした味わいです。", "辛口", 5, "軽快", 4, "旨口", 2),
            sake(9, "風の森 秋津穂", "純米酒", "奈良県", 17.0, 1600, "微発泡感と鮮やかな酸味を持つ、瑞々しい純米酒です。", "酸味", 5, "フルーティー", 3, "軽快", 4),
            sake(10, "鍋島 特別純米", "特別純米", "佐賀県", 15.0, 1800, "ジューシーな甘みとほどよい酸味を備えた、親しみやすい一本です。", "甘口", 4, "酸味", 4, "旨口", 3)
    );

    /**
     * S04向けの多条件検索＋ページネーション。
     * SakeRepository.search()のインメモリ版に相当し、将来はJPQL版に置き換わる。
     *
     * @param keyword       銘柄名・酒種・産地・説明文いずれかに部分一致するキーワード
     * @param type          酒種による絞り込み（未指定時はnullまたは空文字）
     * @param region        産地による絞り込み（未指定時はnullまたは空文字）
     * @param requestedPage 要求されたページ番号（0始まり）。範囲外の値は自動で補正する
     */
    public SakePageDto search(String keyword, String type, String region, int requestedPage) {
        // キーワードは大文字小文字を無視して比較するため、あらかじめ小文字化しておく
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Sake> filtered = sakes.stream().filter(sake ->
                (normalizedKeyword.isBlank() || contains(sake, normalizedKeyword))
                        && (type == null || type.isBlank() || sake.type().equals(type))
                        && (region == null || region.isBlank() || sake.region().equals(region)))
                .toList();

        // 総ページ数は最低でも1（該当0件でも「1ページ中0件」として扱う）
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / PAGE_SIZE));
        // 要求ページが範囲外（マイナスや最終ページ超え）の場合は、有効な範囲に丸め込む
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        int from = Math.min(page * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        return new SakePageDto(filtered.subList(from, to), page, totalPages, filtered.size());
    }

    /** S05: IDによる単一銘柄取得。該当なしの場合はOptional.empty()を返す。 */
    public Optional<Sake> findById(long id) {
        return sakes.stream().filter(sake -> sake.id() == id).findFirst();
    }

    /** S01: トップ画面のマーキー表示用に、先頭5件を注目銘柄として返す（暫定ロジック）。 */
    public List<Sake> featured() {
        return sakes.subList(0, 5);
    }

    /** DiagnosisService.recommend()が、コサイン類似度計算のために全件を参照する際に使用。 */
    public List<Sake> all() {
        return sakes;
    }

    /** S04検索画面の酒種プルダウン用に、重複なし・五十音順で酒種一覧を返す。 */
    public List<String> types() {
        return sakes.stream().map(Sake::type).distinct().sorted().toList();
    }

    /** S04検索画面の産地プルダウン用に、重複なし・五十音順で産地一覧を返す。 */
    public List<String> regions() {
        return sakes.stream().map(Sake::region).distinct().sorted().toList();
    }

    /** キーワードが銘柄名・酒種・産地・説明文のいずれかに部分一致するかを判定する。 */
    private boolean contains(Sake sake, String keyword) {
        return (sake.name() + " " + sake.type() + " " + sake.region() + " " + sake.description())
                .toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * ダミーデータ構築用のヘルパーメソッド。
     * 可変長引数tagsは「タグ名, スコア, タグ名, スコア, ...」の順で交互に並べて渡す想定
     * （例：sake(1, "獺祭...", ..., "フルーティー", 5, "甘口", 4)）。
     * LinkedHashMapを使うことで、渡した順序をtagScores内でも保持できる。
     */
    private static Sake sake(long id, String name, String type, String region, double abv, int price, String description, Object... tags) {
        Map<String, Integer> scores = new java.util.LinkedHashMap<>();
        for (int index = 0; index < tags.length; index += 2) {
            scores.put((String) tags[index], (Integer) tags[index + 1]);
        }
        return new Sake(id, name, type, region, abv, price, description, Map.copyOf(scores));
    }
}
