package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * データベースのuser_profile_imagesという表の1件を、Javaで扱うためのクラス。
 * ユーザーごとのプロフィール画像と表示位置・拡大率を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 * ユーザーIDを画像のIDにも使うため、1人につき1件の画像情報を保存する。
 */
@Entity
@Table(name = "user_profile_images")
@Getter @Setter @NoArgsConstructor
public class UserProfileImage {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    // 画像ファイルの内容をバイト列として保存する。MEDIUMBLOBは画像などを入れるDBの型。
    @Lob
    @Column(name = "image_data", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] imageData;

    // 画像を返すときに使う形式名。例：image/jpegならJPEG画像としてブラウザが表示する。
    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    /** object-position用の百分率。50, 50が画像中央。 */
    @Column(name = "position_x", nullable = false)
    private Integer positionX = 50;

    @Column(name = "position_y", nullable = false)
    private Integer positionY = 50;

    /** 100〜300%。プロフィール画像の拡大率。 */
    @Column(name = "zoom", nullable = false)
    private Integer zoom = 100;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
