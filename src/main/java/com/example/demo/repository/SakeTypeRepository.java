package com.example.demo.repository;

import com.example.demo.entity.SakeType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 酒の種類を保存・検索するためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface SakeTypeRepository extends JpaRepository<SakeType, Long> {
    // name は UNIQUE 制約があるため、種別名からの検索用メソッドを用意しておく
    SakeType findByName(String name);
}
