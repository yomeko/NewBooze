package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NEWBOOZE（地酒サーチ）アプリケーションのエントリポイント。
 *
 * {@code @SpringBootApplication} は以下3つのアノテーションを内包した複合アノテーション。
 * ・{@code @SpringBootConfiguration}：このクラス自体をSpring設定クラスとして扱う
 * ・{@code @EnableAutoConfiguration}：クラスパス上の依存関係（Spring Data JPA等）から
 *   必要な設定を自動で組み立てる
 * ・{@code @ComponentScan}：このクラスと同じパッケージ以下（com.example.demo配下）から
 *   @Controller、@Service、@Repository等を自動検出する
 * （出典：Spring Boot公式リファレンス「Using the @SpringBootApplication Annotation」）
 */
@SpringBootApplication
public class NewBoozeApplication {

	public static void main(String[] args) {
		// 組み込みTomcatの起動、DIコンテナの構築、DataSource等の自動設定を一括で行う
		SpringApplication.run(NewBoozeApplication.class, args);
	}

}
