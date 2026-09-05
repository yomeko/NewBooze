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
 * 好み診断機能。
 * 内部設計書 第7章のデータフローに沿って、以下を行う。
 * ① 設問・選択肢をDBから取得して画面表示する
 * ② 回答から choice_tags を経由してタグ別スコアを集計する
 * ③ ログイン中であれば diagnosis_sessions / diagnosis_answers / user_preferences へ保存する
 * ④ 集計結果をもとに、地酒との類似度（コサイン類似度）を計算して上位N件を推薦する
 *
 * 地酒の特徴ベクトルは sake / sake_tags / tags から取得する。
 * diagnosis_seed.sql と sake_catalog_seed.sql は同じ tags.name を共通軸として使う。
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

    /** S02: 設問一覧をsort_order順に取得する。設問ごとの選択肢も合わせて取得する。 */
    @Transactional(readOnly = true)
    public List<DiagnosisQuestionView> questions() {
        return questionRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toView)
                .toList();
    }

    private DiagnosisQuestionView toView(DiagnosisQuestion question) {
        List<DiagnosisChoiceView> choices = choiceRepository.findByQuestionId(question.getId()).stream()
                .map(choice -> new DiagnosisChoiceView(choice.getId(), choice.getChoiceText()))
                .toList();
        return new DiagnosisQuestionView(question.getId(), question.getQuestionText(), choices);
    }

    /**
     * 選択された選択肢からタグ別スコアを集計し、ログイン中であればDBへ保存する。
     *
     * @param choiceIds   選択された diagnosis_choices.id の一覧（各設問1つずつ）
     * @param loginUserId ログイン中のユーザーID。未ログインの場合はnull（この場合はDB保存を行わない）
     * @return タグ名をキーとした嗜好スコア（画面表示・レコメンド計算の両方に使用）
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

    /** diagnosis_sessions と diagnosis_answers への保存。 */
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
     * user_preferences への保存（upsert）。
     * 設計判断：診断のたびに加算し続けると値が際限なく増え続けてしまうため、
     * 「直近の診断結果 = 現在の嗜好傾向」として毎回スコアを上書きする方針とした。
     * （内部設計書 第7章で「未確定」としていた集計方針の暫定決定。要チーム確認）
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

    /** S03: タグ別嗜好スコアをもとに、コサイン類似度で上位5件の地酒を推薦する。 */
    public List<Recommendation> recommend(Map<String, Integer> preferencesByTagName) {
        return catalogService.all().stream()
                .map(sake -> new Recommendation(sake, cosine(preferencesByTagName, sake.tagScores())))
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed())
                .limit(5)
                .toList();
    }

    private static double cosine(Map<String, Integer> preferences, Map<String, Integer> features) {
        if (preferences.isEmpty()) return 0;
        double dot = 0, preferenceNorm = 0, featureNorm = 0;
        for (int value : preferences.values()) preferenceNorm += value * value;
        for (int value : features.values()) featureNorm += value * value;
        for (Map.Entry<String, Integer> entry : preferences.entrySet()) {
            dot += entry.getValue() * features.getOrDefault(entry.getKey(), 0);
        }
        if (preferenceNorm == 0 || featureNorm == 0) return 0;
        return dot / (Math.sqrt(preferenceNorm) * Math.sqrt(featureNorm));
    }

    public record Recommendation(Sake sake, double score) {
    }
}
