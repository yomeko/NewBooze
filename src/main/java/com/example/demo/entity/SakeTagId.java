package com.example.demo.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * sake_tags の複合主キー（sake_id, tag_id）。
 * @EmbeddedId を使う場合、Serializable の実装と equals/hashCode の適切な定義が必須
 * （JPA仕様上、複合キーの同一性判定に使われるため）。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SakeTagId implements Serializable {

    private Long sakeId;
    private Long tagId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SakeTagId that)) return false;
        return Objects.equals(sakeId, that.sakeId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sakeId, tagId);
    }
}
