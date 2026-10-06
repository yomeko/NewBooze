package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * データベースのsake_typesという表の1件を、Javaで扱うためのクラス。
 * 「純米」「吟醸」などの酒の種類を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 */
@Entity
@Table(name = "sake_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SakeType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // MariaDBのAUTO_INCREMENTに追従させる
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;
}
