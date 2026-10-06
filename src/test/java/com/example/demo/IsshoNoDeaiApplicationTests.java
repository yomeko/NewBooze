package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * アプリ全体が起動できるかを確認するテスト。
 * {@code @SpringBootTest}で画面処理・保存処理・DB接続などを実際に準備する。
 * テストの中身が空でも、準備の途中で設定の問題があれば失敗する。
 * 実行には、application.propertiesで指定したDBが起動している必要がある。
 */
@SpringBootTest
class IsshoNoDeaiApplicationTests {

	@Test
	void contextLoads() {
		// このメソッドは空のままでよい。@SpringBootTestによる
		// コンテキスト起動そのものが検証内容となっている。
	}

}
