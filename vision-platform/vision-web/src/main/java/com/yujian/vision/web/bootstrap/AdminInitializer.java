package com.yujian.vision.web.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始管理员引导：sys_user 为空时创建 admin 账号（密码策略与修改强制流程在 M1 任务9 完善）。
 * <p>默认账号 admin / admin123 —— 首次登录后必须修改（docs/10 §1 安全基线）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        Long userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
        if (userCount != null && userCount > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO sys_role(role_code, role_name, remark) VALUES ('admin','系统管理员','内置角色') "
                + "ON DUPLICATE KEY UPDATE role_name=VALUES(role_name)");
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE role_code='admin'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO sys_user(username, password, nickname, status, must_change_pwd) VALUES (?,?,?,1,1)",
                "admin", passwordEncoder.encode("admin123"), "系统管理员");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        jdbcTemplate.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?,?)", userId, roleId);
        log.warn("已创建初始管理员 admin/admin123，请首次登录后立即修改密码");
    }
}
