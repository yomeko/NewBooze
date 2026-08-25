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
 * 酒種マスタ（sake_types）。
 * newbooze.sql では name に UNIQUE 制約があるため、Java 側でも一意性を意識する。
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
