package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * アプリを起動する入口。実行するとWeb画面とデータベース接続の準備が始まる。
 * {@code @SpringBootApplication}が、このフォルダ以下の画面処理や保存処理を見つけて組み立てる。
 */
@SpringBootApplication
public class IsshoNoDeaiApplication {

	public static void main(String[] args) {
		// Webサーバーを起動し、画面処理やDB接続などアプリ全体の準備を行う。
		SpringApplication.run(IsshoNoDeaiApplication.class, args);
	}

}
