package com.qc.inspect;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p>{@code @EnableScheduling} 是为「磁盘快满了主动喊一声」开的：
 * 这个告警的全部价值就在于**人没在用系统的时候也能被通知到**，
 * 靠打开页面才看得见的灯，等于没有。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.qc.inspect.mapper")
public class QcApplication {

    public static void main(String[] args) {
        SpringApplication.run(QcApplication.class, args);
        System.out.println("""
                
                  QC Server started
                  API : http://localhost:8080/api
                """);
    }
}
