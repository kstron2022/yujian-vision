package com.yujian.vision.web.audit;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.web.security.ClientIps;
import com.yujian.vision.web.security.LoginUser;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 操作审计切面（A3）：@AuditLog 标注的接口异步写 sys_oper_log（docs/03 §2 oper-logs）。
 * <p>单线程串行落库（保序），队列满丢弃（审计允许有损、不阻塞业务）；
 * 调用者身份取 SecurityContext（决策 D14）。</p>
 */
@Slf4j
@Aspect
@Component
public class AuditAspect {

    private final com.yujian.vision.service.system.service.OperLogService operLogService;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /** 单线程保序; 队列满即丢（有损审计, 业务优先） */
    private final ExecutorService auditExecutor = new ThreadPoolExecutor(1, 1,
            60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "audit-log");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());

    public AuditAspect(com.yujian.vision.service.system.service.OperLogService operLogService) {
        this.operLogService = operLogService;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            submit(pjp, auditLog, (int) (System.currentTimeMillis() - start), true, null);
            return result;
        } catch (Throwable t) {
            submit(pjp, auditLog, (int) (System.currentTimeMillis() - start), false, t.getMessage());
            throw t;
        }
    }

    private void submit(ProceedingJoinPoint pjp, AuditLog ann, int costMs, boolean success, String error) {
        LoginUser user = currentUser();
        Long userId = user == null ? null : user.userId();
        String username = user == null ? "" : user.username();
        String ip = currentIp();
        String bizId = evalBizId(pjp, ann);
        String detail = success ? "成功" : ("失败: " + error);
        String module = ann.module(), operation = ann.operation();
        String bizType = ann.bizType();
        auditExecutor.execute(() -> {
            try {
                operLogService.record(userId, username, module, operation, bizType, bizId,
                        detail, ip, costMs, success);
            } catch (Exception e) {
                log.warn("操作日志落库失败: op={}", operation, e);
            }
        });
    }

    private LoginUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser lu ? lu : null;
    }

    private String currentIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            HttpServletRequest req = sra.getRequest();
            return ClientIps.of(req);
        }
        return "";
    }

    private String evalBizId(ProceedingJoinPoint pjp, AuditLog ann) {
        if (!StringUtils.hasText(ann.bizIdExpr())) {
            return "";
        }
        try {
            var sig = (MethodSignature) pjp.getSignature();
            // root 取切点目标对象（Spring 约定, 同 CacheEvaluationContext）; bizIdExpr 一律用 #参数名 取参,
            // root 仅兜底无 # 前缀的引用, 传 null 既违反 @NonNull 契约也无实际语义
            var ctx = new MethodBasedEvaluationContext(pjp.getTarget(), sig.getMethod(), pjp.getArgs(),
                    nameDiscoverer);
            Object v = parser.parseExpression(ann.bizIdExpr()).getValue(ctx);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            log.warn("审计 bizId 表达式求值失败: {}", ann.bizIdExpr());
            return "";
        }
    }
}
