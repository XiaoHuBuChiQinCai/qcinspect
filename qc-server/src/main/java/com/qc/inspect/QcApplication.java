package com.qc.inspect;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
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
