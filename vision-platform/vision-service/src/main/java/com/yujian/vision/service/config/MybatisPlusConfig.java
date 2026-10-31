package com.yujian.vision.service.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（决策 D12：全业务表预留 tenant_id，租户插件统一注入）
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 租户隔离：v1 单域固定 0，保留 SaaS 后路；Flyway 自身表不注入租户条件
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            /** 无 tenant_id 列的表（纯关联表），租户插件跳过 */
            private final Set<String> NO_TENANT_TABLES = Set.of(
                    "sys_user_role", "sys_role_menu", "sys_user_message",
                    "sys_role_group", "sys_role_channel");

            @Override
            public Expression getTenantId() {
                // v1 单域固定 0
                return new LongValue(0L);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                String t = tableName.toLowerCase();
                return t.startsWith("flyway") || NO_TENANT_TABLES.contains(t);
            }
        }));
        // 分页（需 mybatis-plus-jsqlparser 适配包）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
