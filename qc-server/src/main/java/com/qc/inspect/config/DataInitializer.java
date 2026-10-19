package com.qc.inspect.config;

import com.qc.inspect.entity.SysUser;
import com.qc.inspect.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 首次启动时若 sys_user 表为空，自动创建管理员账号。
 * <p>
 * 之所以不在 SQL 脚本里写死密码哈希：哈希随 BCrypt 实现和随机盐变化，
 * 写死的哈希既无法在文档里说清含义，也容易被当成固定后门。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${qc.default-admin.username:admin}")
    private String adminUsername;

    @Value("${qc.default-admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (userMapper.selectCount(null) > 0) {
                return;
            }
            SysUser admin = new SysUser();
            admin.setUsername(adminUsername);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setRealName("系统管理员");
            admin.setRole("ADMIN");
            admin.setEnabled(true);
            admin.setDelFlag(0);
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            userMapper.insert(admin);

            log.warn("已创建默认管理员账号 [{}]，初始密码取自 qc.default-admin.password。"
                    + "登录后请立刻修改，生产环境务必通过环境变量 ADMIN_PASSWORD 覆盖。", adminUsername);
        } catch (Exception e) {
            // 表还没建时不让应用起不来，只提示
            log.warn("初始化管理员账号失败（可能尚未执行 db 下的建表脚本）：{}", e.getMessage());
        }
    }
}
