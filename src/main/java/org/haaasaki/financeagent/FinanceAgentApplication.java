package org.haaasaki.financeagent;

import java.io.File;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * financeAgent 应用入口。
 *
 * <p>单体应用：一个进程、一个 SQLite 文件。启动前确保数据库所在目录存在，
 * 否则 sqlite-jdbc 无法创建数据库文件。
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("org.haaasaki.financeagent.data.mapper")
public class FinanceAgentApplication {

    public static void main(String[] args) {
        ensureDataDirectory();
        SpringApplication.run(FinanceAgentApplication.class, args);
    }

    private static void ensureDataDirectory() {
        File dir = new File("data");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建数据目录: " + dir.getAbsolutePath());
        }
    }
}
