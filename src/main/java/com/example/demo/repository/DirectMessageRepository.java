package com.example.demo.repository;

import com.example.demo.entity.DirectMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, Long> {
    @Query("""
        select m from DirectMessage m
        join fetch m.sender join fetch m.recipient
        where (m.sender.id = :me and m.recipient.id = :other)
           or (m.sender.id = :other and m.recipient.id = :me)
        order by m.createdAt asc
        """)
    List<DirectMessage> conversation(@Param("me") Long me, @Param("other") Long other);
}
