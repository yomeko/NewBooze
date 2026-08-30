package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    @Lob
    @Column(name = "image_data", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] imageData;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    /** object-position用の百分率。50, 50が画像中央。 */
    @Column(name = "position_x", nullable = false)
    private Integer positionX = 50;

    @Column(name = "position_y", nullable = false)
    private Integer positionY = 50;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
