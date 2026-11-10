package com.example.demo.service;

import com.example.demo.entity.DrinkPost;
import com.example.demo.entity.DrinkPostLike;
import com.example.demo.entity.DrinkPostLikeId;
import com.example.demo.entity.DrinkPostReport;
import com.example.demo.repository.DrinkPostLikeRepository;
import com.example.demo.repository.DrinkPostReportRepository;
import com.example.demo.repository.DrinkPostRepository;
import com.example.demo.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 会員向けの飲酒投稿と、管理者による通報対応を扱う。 */
@Service
public class DrinkPostService {
    private static final int PAGE_SIZE = 20;
    private final DrinkPostRepository posts;
    private final DrinkPostLikeRepository likes;
    private final DrinkPostReportRepository reports;
    private final UserRepository users;
    private final PostModerationService moderation;

    public DrinkPostService(DrinkPostRepository posts, DrinkPostLikeRepository likes,
            DrinkPostReportRepository reports, UserRepository users, PostModerationService moderation) {
        this.posts = posts;
        this.likes = likes;
        this.reports = reports;
        this.users = users;
        this.moderation = moderation;
    }

    public enum ReportReason {
        OFF_TOPIC("日本酒と関係のない投稿"),
        ABUSE("攻撃的な表現"),
        SPAM("スパム・宣伝"),
        INAPPROPRIATE("その他の不適切な内容");

        private final String label;

        ReportReason(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        static String labelFor(String code) {
            for (ReportReason reason : values()) {
                if (reason.name().equals(code)) return reason.getLabel();
            }
            return "その他の不適切な内容";
        }
    }

    public record PostView(Long id, String sakeName, String comment, String author, LocalDateTime createdAt,
            boolean owner, long likeCount, boolean liked, boolean reported) {}

    public record ReportView(Long id, String reason, LocalDateTime createdAt, String sakeName,
            String comment, String author) {}

    @Transactional(readOnly = true)
    public Page<PostView> timeline(Long viewer, int page) {
        var results = posts.findAllByOrderByCreatedAtDescIdDesc(pageRequest(page));
        List<Long> ids = results.stream().map(DrinkPost::getId).toList();
        if (ids.isEmpty() && results.getNumber() > 0) {
            results = posts.findAllByOrderByCreatedAtDescIdDesc(lastPage(results));
            ids = results.stream().map(DrinkPost::getId).toList();
        }
        if (ids.isEmpty()) return Page.empty(results.getPageable());

        // 表示中の20件だけを対象に、件数と閲覧者本人の状態をまとめて取得する。
        var counts = likes.countForPosts(ids).stream().collect(Collectors.toMap(
                DrinkPostLikeRepository.LikeCount::getPostId, DrinkPostLikeRepository.LikeCount::getTotal));
        Set<Long> liked = Set.copyOf(likes.findLikedPostIds(viewer, ids));
        Set<Long> reported = Set.copyOf(reports.findReportedPostIds(viewer, ids));
        return results.map(post -> new PostView(post.getId(), post.getSakeName(), post.getComment(),
                post.getUser().getName(), post.getCreatedAt(), viewer.equals(post.getUser().getId()),
                counts.getOrDefault(post.getId(), 0L), liked.contains(post.getId()), reported.contains(post.getId())));
    }

    @Transactional
    public void create(Long userId, String sakeName, String comment) {
        String name = sakeName.strip();
        if (name.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("お酒の名前を入力してください");
        }
        // 同じ会員の同時投稿を直列化し、重複と投稿回数の確認から保存までを一続きに扱う。
        var user = users.findForProfileUpdateById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        String text = comment == null ? "" : comment.strip();
        String error = moderation.validate(userId, name, text);
        if (error != null) throw new IllegalArgumentException(error);

        var post = new DrinkPost();
        post.setUser(user);
        post.setSakeName(name);
        post.setComment(text);
        posts.save(post);
    }

    @Transactional
    public boolean setLiked(Long userId, Long postId, boolean selected) {
        DrinkPost post = posts.findForUpdate(postId).orElse(null);
        if (post == null) return false;
        var id = new DrinkPostLikeId(userId, postId);
        // 反転操作にせず、再送しても画面で選んだ状態になるようにする。
        if (!selected) {
            likes.deleteById(id);
        } else if (!likes.existsById(id)) {
            var like = new DrinkPostLike();
            like.setId(id);
            like.setUser(users.getReferenceById(userId));
            like.setPost(post);
            likes.save(like);
        }
        return true;
    }

    @Transactional
    public boolean report(Long userId, Long postId, ReportReason reason) {
        DrinkPost post = posts.findForUpdate(postId).orElse(null);
        if (post == null) return false;
        if (userId.equals(post.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "自分の投稿は通報できません。");
        }
        if (reports.existsByReporterIdAndPostId(userId, postId)) return true;
        var report = new DrinkPostReport();
        report.setReporter(users.getReferenceById(userId));
        report.setPost(post);
        report.setReason(reason.name());
        reports.save(report);
        return true;
    }

    @Transactional
    public boolean deleteOwnPost(Long userId, Long postId) {
        DrinkPost post = posts.findForUpdate(postId).orElse(null);
        if (post == null) return false;
        if (!userId.equals(post.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本人の投稿だけを削除できます。");
        }
        // いいねと通報は既存の外部キーにより投稿と一緒に削除される。
        posts.deleteWithActivity(post.getId());
        return true;
    }

    @Transactional(readOnly = true)
    public Page<ReportView> reportQueue(int page) {
        var results = reports.findAllByOrderByCreatedAtDescIdDesc(pageRequest(page));
        if (!results.hasContent() && results.getNumber() > 0) {
            results = reports.findAllByOrderByCreatedAtDescIdDesc(lastPage(results));
        }
        return results.map(report -> {
            DrinkPost post = report.getPost();
            return new ReportView(report.getId(), ReportReason.labelFor(report.getReason()), report.getCreatedAt(),
                    post.getSakeName(), post.getComment(), post.getUser().getName());
        });
    }

    @Transactional
    public void resolveReport(Long reportId, boolean deletePost) {
        Long postId = reports.findPostId(reportId).orElseThrow(() -> notFound("通報"));
        // どの操作でも投稿を先にロックする。取得後に通報を読み直して、処理済みなら削除しない。
        DrinkPost post = posts.findForUpdate(postId).orElseThrow(() -> notFound("投稿"));
        DrinkPostReport report = reports.findForUpdate(reportId).orElseThrow(() -> notFound("通報"));
        if (deletePost) {
            posts.deleteWithActivity(post.getId());
        } else {
            reports.delete(report);
        }
    }

    private static ResponseStatusException notFound(String item) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, item + "が見つかりません。");
    }

    // 最終ページの最後の項目を削除した場合は、残っているページへ戻す。
    private static PageRequest lastPage(Page<?> results) {
        return pageRequest(Math.max(0, results.getTotalPages() - 1));
    }

    private static PageRequest pageRequest(int page) {
        if (page > Integer.MAX_VALUE / PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ページ番号が大きすぎます。");
        }
        return PageRequest.of(Math.max(0, page), PAGE_SIZE);
    }
}
