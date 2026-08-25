package com.example.demo.repository;

import com.example.demo.entity.SakeType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SakeTypeRepository extends JpaRepository<SakeType, Long> {
    // name は UNIQUE 制約があるため、種別名からの検索用メソッドを用意しておく
    SakeType findByName(String name);
}
