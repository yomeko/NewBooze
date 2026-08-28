package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Bootの起動確認用の疎通テスト（雛形）。
 * {@code @SpringBootTest} により、テスト実行時にアプリケーションコンテキスト全体
 * （Controller/Service/Repository/DataSource等すべてのBean）を実際に組み立てる。
 * contextLoads() は中身が空でも意味があり、「Beanの依存関係が正しく解決でき、
 * 設定ミス等でコンテキスト構築自体が失敗しないこと」を検証するテストになっている。
 * （実行にはDB接続設定が必要な点に注意。application.propertiesの接続先が
 *  起動していないとこのテストも失敗する）
 */
@SpringBootTest
class NewBoozeApplicationTests {

	@Test
	void contextLoads() {
		// このメソッドは空のままでよい。@SpringBootTestによる
		// コンテキスト起動そのものが検証内容となっている。
	}

}
