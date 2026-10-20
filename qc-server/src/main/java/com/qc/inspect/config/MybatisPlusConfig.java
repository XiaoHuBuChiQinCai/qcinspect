package com.qc.inspect.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 用自定义 handler 顶掉 MyBatis 内置的 LocalDateTimeTypeHandler。
     *
     * <p>内置那个走 {@code rs.getObject(col, LocalDateTime.class)}，
     * 碰到 timestamptz 列会抛转换异常；本项目的时间列全是 timestamptz，所以必须替换。
     */
    @Bean
    public ConfigurationCustomizer timestamptzCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(LocalDateTime.class, new TimestamptzTypeHandler());
    }
}
