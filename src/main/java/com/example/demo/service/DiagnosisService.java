package com.example.demo.service;

import com.example.demo.dto.DiagnosisChoiceView;
import com.example.demo.dto.DiagnosisQuestionView;
import com.example.demo.entity.ChoiceTag;
import com.example.demo.entity.DiagnosisAnswer;
import com.example.demo.entity.DiagnosisChoice;
import com.example.demo.entity.DiagnosisQuestion;
import com.example.demo.entity.DiagnosisSession;
import com.example.demo.entity.Tag;
import com.example.demo.entity.UserPreference;
import com.example.demo.model.Sake;
import com.example.demo.repository.ChoiceTagRepository;
import com.example.demo.repository.DiagnosisAnswerRepository;
import com.example.demo.repository.DiagnosisChoiceRepository;
import com.example.demo.repository.DiagnosisQuestionRepository;
import com.example.demo.repository.DiagnosisSessionRepository;
import com.example.demo.repository.UserPreferenceRepository;
import com.example.demo.repository.UserRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回答を「甘口」「軽快」などの特徴ごとの点数に変え、好みに近い日本酒を選ぶ。
 * 質問・選択肢・特徴の対応をデータベースから読み、ログイン中なら回答と点数も保存する。
 * Serviceは画面から独立した処理をまとめる場所で、Controllerから呼び出される。
 * {@code @Transactional}は複数の保存をひとまとまりにし、実行時エラーで失敗したら保存を取り消す指定。
 */
@Service
public class DiagnosisService {

    private final DiagnosisQuestionRepository questionRepository;
    private final DiagnosisChoiceRepository choiceRepository;
    private final ChoiceTagRepository choiceTagRepository;
    private final DiagnosisSessionRepository sessionRepository;
    private final DiagnosisAnswerRepository answerRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final SakeCatalogService catalogService;

    public DiagnosisService(DiagnosisQuestionRepository questionRepository,
                             DiagnosisChoiceRepository choiceRepository,
                             ChoiceTagRepository choiceTagRepository,
                             DiagnosisSessionRepository sessionRepository,
                             DiagnosisAnswerRepository answerRepository,
                             UserPreferenceRepository preferenceRepository,
                             UserRepository userRepository,
                             SakeCatalogService catalogService) {
        this.questionRepository = questionRepository;
        this.choiceRepository = choiceRepository;
        this.choiceTagRepository = choiceTagRepository;
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.catalogService = catalogService;
    }

    /**
     * DBの表示順に質問を読み、各質問と選択肢を画面用のデータにまとめる。
     */
    @Transactional(readOnly = true)
    public List<DiagnosisQuestionView> questions() {
        return questionRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 1つの質問に属する選択肢を読み、質問文と選択肢一覧をまとめて返す。
     */
    private DiagnosisQuestionView toView(DiagnosisQuestion question) {
        List<DiagnosisChoiceView> choices = choiceRepository.findByQuestionId(question.getId()).stream()
                .map(choice -> new DiagnosisChoiceView(choice.getId(), choice.getChoiceText()))
                .toList();
        return new DiagnosisQuestionView(question.getId(), question.getQuestionText(), choices);
    }

    /**
     * 選んだ各選択肢に対応する特徴へ、登録されたweight（加点）を足す。
     * 画面表示用に特徴名で、DB保存用に特徴IDで集計する。
     * ログイン中で回答があれば、診断の記録と特徴ごとの点数をひとまとまりで保存する。
     */
    @Transactional
    public Map<String, Integer> aggregateAndPersist(List<Long> choiceIds, Long loginUserId) {
        List<Long> selected = choiceIds == null ? List.of() : choiceIds;

        // タグ名ベースの集計（画面表示・レコメンド計算用）
        Map<String, Integer> scoreByTagName = new LinkedHashMap<>();
        // タグIDベースの集計（DB保存用）。
        // Tagエンティティ自体をMapのキーにはしない方針とした。
        // Tagクラスはequals/hashCodeを独自定義しておらずObject標準の同一性比較になるため、
        // 複数クエリを跨いで取得したTagインスタンスが「同じ行でも別オブジェクト」と
        // 判定されてしまう可能性があり、集計漏れのバグを生みやすいためである。
        Map<Long, Integer> scoreByTagId = new LinkedHashMap<>();
        Map<Long, Tag> tagById = new LinkedHashMap<>();

        for (Long choiceId : selected) {
            for (ChoiceTag choiceTag : choiceTagRepository.findByIdChoiceId(choiceId)) {
                Tag tag = choiceTag.getTag();
                int weight = choiceTag.getWeight();
                // 同じ特徴が別の回答にもあれば合計する。mergeは、初回は値を入れ、2回目以降は足す。
                scoreByTagName.merge(tag.getName(), weight, Integer::sum);
                scoreByTagId.merge(tag.getId(), weight, Integer::sum);
                tagById.putIfAbsent(tag.getId(), tag);
            }
        }

        if (loginUserId != null && !selected.isEmpty()) {
            saveSession(loginUserId, selected);
            savePreferences(loginUserId, scoreByTagId, tagById);
        }

        return scoreByTagName;
    }

    /**
     * 結果画面で回答理由を見られるよう、質問文・選んだ回答・加点された特徴をまとめる。
     * 0点以下の特徴は理由に含めず、同じ特徴名は1回だけ載せる。
     */
    @Transactional(readOnly = true)
    public List<AnswerSummary> answerSummaries(List<Long> choiceIds) {
        if (choiceIds == null) return List.of();
        return choiceIds.stream().map(id -> choiceRepository.findById(id).orElseThrow())
                .map(choice -> new AnswerSummary(choice.getQuestion().getQuestionText(), choice.getChoiceText(),
                        choiceTagRepository.findByIdChoiceId(choice.getId()).stream()
                                .filter(tag -> tag.getWeight() > 0)
                                .map(tag -> tag.getTag().getName()).distinct().toList()))
                .toList();
    }

    // 質問・回答・特徴の一覧を、結果画面の説明欄へ渡すためのデータ。
    public record AnswerSummary(String question, String answer, List<String> tags) {}

    /**
     * 診断1回分の記録を作り、その記録へ質問ごとの回答を結び付けて保存する。
     * 届くのは選択肢IDだけなので、対応する質問は選択肢の情報から取得する。
     */
    private DiagnosisSession saveSession(Long userId, List<Long> selectedChoiceIds) {
        DiagnosisSession session = new DiagnosisSession();
        // getReferenceById: 実体をSELECTで取得せず、IDのみを持つ参照(プロキシ)を作る。
        // 外部キーとして紐付けるだけであれば、Userの中身(name等)は不要なため、
        // 無駄なSELECTを避けられる（本テーブルのようなFK専用の関連付けでよく使う書き方）。
        session.setUser(userRepository.getReferenceById(userId));
        session = sessionRepository.save(session);

        for (Long choiceId : selectedChoiceIds) {
            // 質問(question)はDiagnosisChoiceに紐づく情報から取得する。
            // フォームでは「選択肢ID」しか送られてこないため、ここで選択肢経由で設問を辿る。
            DiagnosisChoice choice = choiceRepository.findById(choiceId)
                    .orElseThrow(() -> new IllegalArgumentException("存在しない選択肢IDです: " + choiceId));

            DiagnosisAnswer answer = new DiagnosisAnswer();
            answer.setSession(session);
            answer.setQuestion(choice.getQuestion());
            answer.setChoice(choice);
            answerRepository.save(answer);
        }
        return session;
    }

    /**
     * 今回集計した特徴について、本人の点数があれば上書きし、なければ新規保存する。
     * 過去の点数へ加算はしない。今回の集計に登場しない特徴の保存済み点数はここでは変更しない。
     */
    private void savePreferences(Long userId, Map<Long, Integer> scoreByTagId, Map<Long, Tag> tagById) {
        for (Map.Entry<Long, Integer> entry : scoreByTagId.entrySet()) {
            Long tagId = entry.getKey();
            Integer score = entry.getValue();

            UserPreference preference = preferenceRepository
                    .findByIdUserIdAndIdTagId(userId, tagId)
                    .orElseGet(() -> {
                        UserPreference newPreference = new UserPreference();
                        newPreference.setUser(userRepository.getReferenceById(userId));
                        newPreference.setTag(tagById.get(tagId));
                        return newPreference;
                    });
            preference.setScore(score);
            preferenceRepository.save(preference);
        }
    }

    /**
     * 全銘柄の特徴を好みの点数と比べ、似ている順に最大5件を返す。
     * 各銘柄には、好みと共通する特徴から選んだおすすめ理由も付ける。
     */
    public List<Recommendation> recommend(Map<String, Integer> preferencesByTagName) {
        return catalogService.all().stream()
                .map(sake -> new Recommendation(sake, cosine(preferencesByTagName, sake.tagScores()),
                        com.example.demo.dto.TastePresentation.matchingTags(preferencesByTagName, sake.tagScores())))
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed())
                .limit(5)
                .toList();
    }

    /**
     * 好みと日本酒の「特徴ごとの点数の比率」がどれくらい似ているかを計算する。
     * コサイン類似度という計算方法で、共通する特徴の点数を掛けて合計し、
     * 双方の点数の大きさで割る。点数がすべて0なら、0で割らないよう類似度0を返す。
     */
    private static double cosine(Map<String, Integer> preferences, Map<String, Integer> features) {
        if (preferences.isEmpty()) return 0;
        // dotは共通特徴の点数の積の合計。2つのNormは、それぞれの点数を二乗した合計。
        double dot = 0, preferenceNorm = 0, featureNorm = 0;
        for (int value : preferences.values()) preferenceNorm += value * value;
        for (int value : features.values()) featureNorm += value * value;
        for (Map.Entry<String, Integer> entry : preferences.entrySet()) {
            dot += entry.getValue() * features.getOrDefault(entry.getKey(), 0);
        }
        if (preferenceNorm == 0 || featureNorm == 0) return 0;
        return dot / (Math.sqrt(preferenceNorm) * Math.sqrt(featureNorm));
    }

    // 1件のおすすめ銘柄、計算した類似度、画面に出す一致理由をまとめる。
    public record Recommendation(Sake sake, double score, List<String> matchingTags) {
    }
}
