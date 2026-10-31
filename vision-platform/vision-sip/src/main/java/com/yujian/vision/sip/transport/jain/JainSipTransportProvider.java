package com.yujian.vision.sip.transport.jain;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.sip.config.SipStackProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipEventListener;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipStackDescriptor;
import com.yujian.vision.sip.transport.SipTransportProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.SipURI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.EventHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jain-sip 传输实现（docs/01 §5，决策 D5）。
 *
 * <p>多栈分片：每个 SipStackDescriptor 一个 SipStack 实例；栈回调统一投递 dispatch 线程池，
 * 回调内不做阻塞 IO。CallID/SN/Branch 由 {@link SipIds} 统一生成。</p>
 */
@Slf4j
@Component
public class JainSipTransportProvider implements SipTransportProvider, SipListener {

    private final SipStackProperties properties;
    private final Map<String, StackHolder> stacks = new ConcurrentHashMap<>();
    private final Map<SipProvider, StackHolder> providerIndex = new HashMap<>();
    private volatile SipEventListener listener;
    private final SipFactory sipFactory = SipFactory.getInstance();
    private final javax.sip.address.AddressFactory addressFactory;
    private final javax.sip.header.HeaderFactory headerFactory;
    private final MessageFactory messageFactory;
    private final ExecutorService dispatchPool;
    private final AtomicInteger threadSeq = new AtomicInteger();
    private volatile String cachedLocalIp;
    /** 本平台发出的 INVITE 事务（callId → CT，超时 CANCEL / 终响应后移除） */
    private final Map<String, ClientTransaction> inviteTransactions = new ConcurrentHashMap<>();
    /** INVITE 原始请求（克隆发 CANCEL, 保证同 branch, RFC 3261 §9.1） */
    private final Map<String, Request> inviteRequests = new ConcurrentHashMap<>();
    /** callId → Dialog（INVITE 200 OK 后建立, 对话内 BYE 必须经 Dialog 发送） */
    private final Map<String, Dialog> dialogIndex = new ConcurrentHashMap<>();

    public JainSipTransportProvider(SipStackProperties properties) {
        this.properties = properties;
        sipFactory.setPathName("gov.nist");
        try {
            this.addressFactory = sipFactory.createAddressFactory();
            this.headerFactory = sipFactory.createHeaderFactory();
            this.messageFactory = sipFactory.createMessageFactory();
        } catch (PeerUnavailableException e) {
            throw new IllegalStateException("init jain-sip factories failed", e);
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sip-dispatch-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.dispatchPool = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2), tf);
    }

    // ---------------------------------------------------------------- 栈管理

    private static final class StackHolder {
        final SipStack stack;
        final SipProvider provider;
        final SipStackDescriptor descriptor;

        StackHolder(SipStack stack, SipProvider provider, SipStackDescriptor descriptor) {
            this.stack = stack;
            this.provider = provider;
            this.descriptor = descriptor;
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void setListener(SipEventListener listener) {
        this.listener = listener;
    }

    @PostConstruct
    public void autoStart() {
        // M3-B6 阶段2: HA 备台(autostart=false)不随进程绑口, 由 promote 经 start() 拉起
        if (!properties.isAutostart()) {
            log.info("SIP 传输层 autostart=false, 等待外部拉起(HA 备用身份)");
            return;
        }
        for (SipStackProperties.StackSpec spec : properties.getStacks()) {
            try {
                start(new SipStackDescriptor(spec.getStackId(), spec.getHost(), spec.getPort(), spec.getTransport()));
            } catch (Exception e) {
                log.error("SIP 栈启动失败: stackId={} port={}", spec.getStackId(), spec.getPort(), e);
            }
        }
        log.info("SIP 传输层就绪, 运行栈数: {}", activeStacks());
    }

    @PreDestroy
    public void shutdown() {
        for (String stackId : stacks.keySet().toArray(new String[0])) {
            stop(stackId);
        }
        dispatchPool.shutdownNow();
    }

    @Override
    public void start(SipStackDescriptor descriptor) {
        if (stacks.containsKey(descriptor.stackId())) {
            return;
        }
        try {
            Properties props = new Properties();
            // 内部栈名加随机代号: NIST SipFactory 的栈表只增不删(无 deleteSipStack API),
            // 同 stackId 反复 stop→start(HA 接管/回切)若同名会撞栈表重名——对外 stackId/descriptor 不变
            props.setProperty("javax.sip.STACK_NAME",
                    descriptor.stackId() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
            if (!"0.0.0.0".equals(descriptor.host())) {
                props.setProperty("javax.sip.IP_ADDRESS", descriptor.host());
            }
            // 关闭 jain-sip 自带日志与心跳线程噪音
            props.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "0");
            props.setProperty("gov.nist.javax.sip.LOG_FACTORY", "none");

            SipStack stack = sipFactory.createSipStack(props);
            ListeningPoint lp = stack.createListeningPoint(descriptor.host(), descriptor.port(), descriptor.transport());
            SipProvider provider = stack.createSipProvider(lp);
            provider.addSipListener(this);

            StackHolder holder = new StackHolder(stack, provider, descriptor);
            stacks.put(descriptor.stackId(), holder);
            synchronized (providerIndex) {
                providerIndex.put(provider, holder);
            }
            log.info("SIP 栈已启动: id={} {}:{} {}", descriptor.stackId(), descriptor.host(), descriptor.port(), descriptor.transport());
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SIP 栈启动失败: " + descriptor.stackId() + ", " + e.getMessage());
        }
    }

    @Override
    public void stop(String stackId) {
        StackHolder holder = stacks.remove(stackId);
        if (holder == null) {
            return;
        }
        try {
            holder.provider.removeSipListener(this);
            // jain-sip API 返回裸 Iterator, 通配 + 逐元素下转, 避免 unchecked 警告
            java.util.Iterator<?> lps = holder.stack.getListeningPoints();
            while (lps.hasNext()) {
                holder.stack.deleteListeningPoint((ListeningPoint) lps.next());
            }
            holder.stack.deleteSipProvider(holder.provider);
        } catch (Exception e) {
            log.warn("SIP 栈停止异常: {}", stackId, e);
        }
        synchronized (providerIndex) {
            providerIndex.values().removeIf(h -> h == holder);
        }
        log.info("SIP 栈已停止: {}", stackId);
    }

    @Override
    public boolean isRunning(String stackId) {
        return stacks.containsKey(stackId);
    }

    @Override
    public int activeStacks() {
        return stacks.size();
    }

    @Override
    public String stackIdForTransport(String transport) {
        if (transport == null || transport.isBlank()) {
            return null;
        }
        for (StackHolder holder : stacks.values()) {
            if (holder.descriptor.transport().equalsIgnoreCase(transport.trim())) {
                return holder.descriptor.stackId();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 发送

    @Override
    public void send(SipMessageContext ctx) {
        StackHolder holder = stacks.get(ctx.getStackId());
        if (holder == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SIP 栈不存在: " + ctx.getStackId());
        }
        try {
            String transport = holder.descriptor.transport();
            String localIp = resolveLocalIp(holder);
            int localPort = holder.descriptor.port();

            // Request-URI: 优先用调用方给定的, 否则按 to + 目标地址构造
            javax.sip.address.URI requestUri;
            if (ctx.getRequestUri() != null && !ctx.getRequestUri().isBlank()) {
                requestUri = addressFactory.createAddress(ctx.getRequestUri()).getURI();
            } else {
                String[] to = splitUserHost(ctx.getTo());
                SipURI uri = addressFactory.createSipURI(to[0], ctx.getTargetHost() != null ? ctx.getTargetHost() : to[1]);
                uri.setPort(ctx.getTargetPort() != null ? ctx.getTargetPort() : 5060);
                uri.setTransportParam(transport);
                requestUri = uri;
            }

            // From / To（对话内后续请求 BYE/ACK 复用原 tag, docs/05 §5）
            String[] from = splitUserHost(ctx.getFrom());
            SipURI fromUri = addressFactory.createSipURI(from[0], from[1]);
            FromHeader fromHeader = headerFactory.createFromHeader(addressFactory.createAddress(fromUri),
                    ctx.getFromTag() != null ? ctx.getFromTag() : SipIds.newTag());
            String[] to = splitUserHost(ctx.getTo());
            SipURI toUri = addressFactory.createSipURI(to[0], to[1]);
            ToHeader toHeader = headerFactory.createToHeader(addressFactory.createAddress(toUri), ctx.getToTag());

            // Via / CallID / CSeq / MaxForwards
            ViaHeader via = headerFactory.createViaHeader(localIp, localPort, transport, SipIds.newBranch());
            via.setRPort();
            List<ViaHeader> vias = new ArrayList<>();
            vias.add(via);
            CallIdHeader callId = ctx.getCallId() != null
                    ? headerFactory.createCallIdHeader(ctx.getCallId())
                    : holder.provider.getNewCallId();
            long cseqNum = ctx.getSn() != null ? ctx.getSn() : 1L;
            CSeqHeader cseq = headerFactory.createCSeqHeader(cseqNum, ctx.getMethod());
            MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

            Request request = messageFactory.createRequest(requestUri, ctx.getMethod(), callId, cseq,
                    fromHeader, toHeader, vias, maxForwards);

            // Contact（REGISTER/INVITE 必需, 统一携带）
            SipURI contactUri = addressFactory.createSipURI(from[0], localIp);
            contactUri.setPort(localPort);
            contactUri.setTransportParam(transport);
            request.addHeader(headerFactory.createContactHeader(addressFactory.createAddress(contactUri)));

            // 级联 UAC: REGISTER 的 Digest Authorization 与 Expires（docs/08 §2.1）
            if (ctx.getAuthorization() != null && !ctx.getAuthorization().isBlank()) {
                request.addHeader(headerFactory.createAuthorizationHeader(ctx.getAuthorization()));
            }
            if (ctx.getExpires() != null) {
                request.addHeader(headerFactory.createExpiresHeader(ctx.getExpires()));
            }
            // 目录订阅(SUBSCRIBE)的事件包名, RFC 3265 必带头（M3-B3 阶段4）
            if (ctx.getEvent() != null && !ctx.getEvent().isBlank()) {
                request.addHeader(headerFactory.createEventHeader(ctx.getEvent()));
            }
            // 订阅通知(NOTIFY)的订阅状态头, RFC 3265 必带（M3-B4 阶段2; 原值含参数如 active;expires=1234）
            if (ctx.getSubscriptionState() != null && !ctx.getSubscriptionState().isBlank()) {
                request.addHeader(headerFactory.createHeader("Subscription-State", ctx.getSubscriptionState()));
            }

            // 消息体
            if (ctx.getBody() != null && !ctx.getBody().isBlank()) {
                String ct = ctx.getContentType() != null ? ctx.getContentType() : "Application/MANSCDP+xml";
                int slash = ct.indexOf('/');
                ContentTypeHeader ctHeader = slash > 0
                        ? headerFactory.createContentTypeHeader(ct.substring(0, slash), ct.substring(slash + 1))
                        : headerFactory.createContentTypeHeader("Application", ct);
                // 出站字符集（级联 GB2312 兼容, 2026-09-25）: jain-sip 序列化按 Content-Type
                // charset 参数字节化（SIPMessage.getCharset 优先取头参数）, 缺省回其 UTF-8 静态默认
                if (ctx.getContentCharset() != null && !ctx.getContentCharset().isBlank()) {
                    ctHeader.setParameter("charset", ctx.getContentCharset());
                }
                request.setContent(ctx.getBody(), ctHeader);
            }

            // 对话内 INFO / re-INVITE（B2 回放控制/拖动）: 与对话内 BYE 同机制, 经 Dialog 发送 ——
            // tag/CSeq/Contact/Route 由 Dialog 统一维护（jain-sip 手工拼 CSeq 会与对话状态错位遭设备拒收）。
            // 由 ctx.inDialog 显式标记（新会话 INVITE 的 callId 不在 dialogIndex, 不会误入此路径）
            if (ctx.isInDialog() && ctx.getCallId() != null) {
                Dialog dialog = dialogIndex.get(ctx.getCallId());
                if (dialog != null) {
                    Request inDialogReq = dialog.createRequest(ctx.getMethod());
                    setContent(inDialogReq, ctx.getBody(), ctx.getContentType(), ctx.getContentCharset());
                    ClientTransaction inDialogCt = holder.provider.getNewClientTransaction(inDialogReq);
                    dialog.sendRequest(inDialogCt);
                    log.debug("SIP 发送: {}(Dialog) callId={}", ctx.getMethod(), ctx.getCallId());
                    return;
                }
                // 无 Dialog(对话已结束/栈重启): 落到通用事务路径, 由业务层 tag 守卫尽量避免
            }

            // 事务性发送: INVITE/MESSAGE/BYE/INFO/NOTIFY/SUBSCRIBE 走事务; ACK 无状态直发
            if ("BYE".equals(ctx.getMethod()) && ctx.getCallId() != null) {
                // 对话内 BYE: jain-sip 要求经 Dialog 发送（自动带上正确 tag/CSeq/目标）
                Dialog dialog = dialogIndex.get(ctx.getCallId());
                if (dialog != null) {
                    Request bye = dialog.createRequest("BYE");
                    ClientTransaction byeCt = holder.provider.getNewClientTransaction(bye);
                    dialog.sendRequest(byeCt);
                    log.debug("SIP 发送: BYE(Dialog) callId={}", ctx.getCallId());
                    return;
                }
                // 无 Dialog(如 INVITE 未获 200 即清理): 落到通用无事务路径, 由栈决定是否可发
            }
            if ("ACK".equals(ctx.getMethod())) {
                holder.provider.sendRequest(request);
            } else {
                ClientTransaction ct = holder.provider.getNewClientTransaction(request);
                ct.sendRequest();
                if ("INVITE".equals(ctx.getMethod())) {
                    // 记录 INVITE 事务与原始请求供超时 CANCEL（RFC 3261 要求 CANCEL 与 INVITE 同 branch）
                    inviteTransactions.put(callId.getCallId(), ct);
                    inviteRequests.put(callId.getCallId(), request);
                }
            }
            log.debug("SIP 发送: {} -> {} callId={}", ctx.getMethod(), ctx.getTo(), callId.getCallId());
        } catch (ParseException | javax.sip.SipException | InvalidArgumentException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SIP 发送失败: " + ctx.getMethod() + ", " + e.getMessage());
        }
    }

    @Override
    public void sendResponse(SipEvent inReplyTo, int statusCode, Map<String, String> headers) {
        sendResponse(inReplyTo, statusCode, headers, null, null);
    }

    @Override
    public void sendResponse(SipEvent inReplyTo, int statusCode, Map<String, String> headers, String body,
            String contentType) {
        try {
            javax.sip.message.Request request = (javax.sip.message.Request) inReplyTo.getRequest();
            javax.sip.ServerTransaction tx = (javax.sip.ServerTransaction) inReplyTo.getServerTransaction();
            if (request == null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "无原始请求, 无法应答: " + inReplyTo.getMethod());
            }
            Response response = messageFactory.createResponse(statusCode, request);
            // ToTag 为业务层预留键: 入站 SUBSCRIBE 的对话 tag 由订阅服务生成并持有
            // (对话内 NOTIFY 的 From tag 必须与它一致, M3-B4 阶段2), 传输层不再随机生成
            String explicitToTag = headers != null ? headers.get(SipTransportProvider.TO_TAG_HEADER_KEY) : null;
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (SipTransportProvider.TO_TAG_HEADER_KEY.equals(h.getKey())) {
                        continue;
                    }
                    response.addHeader(headerFactory.createHeader(h.getKey(), h.getValue()));
                }
            }
            if (body != null && !body.isBlank()) {
                // 应答体（级联 INVITE 200 OK 的 SDP 应答, docs/08 §2.3）
                String ct = contentType != null ? contentType : "application/sdp";
                int slash = ct.indexOf('/');
                ContentTypeHeader ctHeader = slash > 0
                        ? headerFactory.createContentTypeHeader(ct.substring(0, slash), ct.substring(slash + 1))
                        : headerFactory.createContentTypeHeader("Application", ct);
                response.setContent(body, ctHeader);
            }
            if (("INVITE".equals(inReplyTo.getMethod()) || "SUBSCRIBE".equals(inReplyTo.getMethod()))
                    && statusCode >= 200) {
                // INVITE/SUBSCRIBE 2xx 必须带 To tag 建 UAS 对话(上级 ACK/BYE 匹配 dialog 用; 本 jain 版本
                // ST.sendResponse 不自动补, 缺 tag 时上级 BYE 会被 stack 判 481, 联调实测;
                // SUBSCRIBE 同理: 缺 tag 订阅方收不到对话内 NOTIFY, M3-B3 阶段4)
                javax.sip.header.ToHeader to =
                        (javax.sip.header.ToHeader) response.getHeader(ToHeader.NAME);
                if (to != null && to.getTag() == null) {
                    to.setTag(explicitToTag != null && !explicitToTag.isBlank()
                            ? explicitToTag : com.yujian.vision.sip.transport.SipIds.newTag());
                }
                // 自动补 Contact（上级后续 BYE 的路由目标）; user 部分留空——平台编码属业务配置, 传输层不猜
                if (response.getHeader(ContactHeader.NAME) == null) {
                    StackHolder holder = stacks.get(inReplyTo.getStackId());
                    if (holder != null) {
                        SipURI contactUri = addressFactory.createSipURI(null,
                                resolveLocalIp(holder) + ":" + holder.descriptor.port());
                        response.addHeader(headerFactory.createContactHeader(
                                addressFactory.createAddress(contactUri)));
                    }
                }
            }
            if (tx != null) {
                if ("INVITE".equals(inReplyTo.getMethod()) && statusCode >= 200) {
                    log.info("INVITE {} 经 ST 应答, dialog={}", statusCode,
                            tx.getDialog() != null ? tx.getDialog().getDialogId() : "null");
                }
                tx.sendResponse(response);
            } else {
                // 无事务(部分 UDP 请求)时按 Via 无状态回发
                log.warn("INVITE 应答无 ST(无状态路径): {} {}", inReplyTo.getMethod(), statusCode);
                StackHolder holder = stacks.get(inReplyTo.getStackId());
                holder.provider.sendResponse(response);
            }
            log.debug("SIP 应答: {} {} callId={}", inReplyTo.getMethod(), statusCode, inReplyTo.getCallId());
        } catch (ParseException | javax.sip.SipException | InvalidArgumentException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SIP 应答失败: " + statusCode + ", " + e.getMessage());
        }
    }

    private String resolveLocalIp(StackHolder holder) {
        // 宣告地址优先级: 全局 public-ip(容器/NAT 部署显式宣告) > 栈 host(非通配时监听地址即宣告地址) > 本机 IP 兜底
        if (properties.getPublicIp() != null && !properties.getPublicIp().isBlank()) {
            return properties.getPublicIp();
        }
        String host = holder.descriptor.host();
        if (host != null && !"0.0.0.0".equals(host)) {
            return host;
        }
        // TODO(M1-3): 按目标地址路由选择出口网卡 IP(多网卡/NAT 场景), 当前取本机地址兜底
        if (cachedLocalIp == null) {
            try {
                cachedLocalIp = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                cachedLocalIp = "127.0.0.1";
            }
        }
        return cachedLocalIp;
    }

    /** "user@host" 拆分; 无 @ 时视为纯用户名, host 兜底空串 */
    private String[] splitUserHost(String userAtHost) {
        if (userAtHost == null) {
            return new String[]{"", ""};
        }
        int at = userAtHost.indexOf('@');
        return at < 0 ? new String[]{userAtHost, ""} : new String[]{userAtHost.substring(0, at), userAtHost.substring(at + 1)};
    }

    /**
     * 消息体填充（Content-Type 缺省 MANSCDP+xml, mirror send() 主路径的构造规则）。
     * charset 非空时同样追加 Content-Type charset 参数（与 send() 主路径口径一致,
     * 2026-09-25 评审补: dialog 分支此前漏 charset, 一旦对话内走 GB 编码 MANSCDP
     * 会产出「声明已改写+字节仍 UTF-8」的更糟组合）。
     */
    private void setContent(Request request, String body, String contentType, String charset)
            throws ParseException, InvalidArgumentException {
        if (body == null || body.isBlank()) {
            return;
        }
        String ct = contentType != null ? contentType : "Application/MANSCDP+xml";
        int slash = ct.indexOf('/');
        ContentTypeHeader ctHeader = slash > 0
                ? headerFactory.createContentTypeHeader(ct.substring(0, slash), ct.substring(slash + 1))
                : headerFactory.createContentTypeHeader("Application", ct);
        if (charset != null && !charset.isBlank()) {
            ctHeader.setParameter("charset", charset);
        }
        request.setContent(body, ctHeader);
    }

    // ---------------------------------------------------------------- 入站回调

    @Override
    public void processRequest(RequestEvent evt) {
        Request req = evt.getRequest();
        StackHolder holder = providerIndex.get(evt.getSource());
        FromHeader from = (FromHeader) req.getHeader(FromHeader.NAME);
        ToHeader to = (ToHeader) req.getHeader(ToHeader.NAME);
        CallIdHeader callId = (CallIdHeader) req.getHeader(CallIdHeader.NAME);
        ContentTypeHeader ct = (ContentTypeHeader) req.getHeader(ContentTypeHeader.NAME);
        AuthorizationHeader auth = (AuthorizationHeader) req.getHeader(AuthorizationHeader.NAME);
        ExpiresHeader expires = (ExpiresHeader) req.getHeader(ExpiresHeader.NAME);
        ContactHeader contact = (ContactHeader) req.getHeader(ContactHeader.NAME);
        EventHeader evtHdr = (EventHeader) req.getHeader(EventHeader.NAME);
        SipURI contactUri = contact != null && contact.getAddress().getURI() instanceof SipURI u ? u : null;
        SipURI reqUri = req.getRequestURI() instanceof SipURI u ? u : null;
        // 无事务 INVITE(本栈 UDP 场景实测 evt.getServerTransaction() 返回 null): 手动补建 ST——
        // 否则 2xx 应答走 provider.sendResponse 无状态路径, UAS dialog 不会创建, 上级随后的
        // BYE 会被 stack 判 481 Call leg does not exist(联调实测)
        javax.sip.ServerTransaction st = evt.getServerTransaction();
        if (st == null && "INVITE".equals(req.getMethod()) && holder != null) {
            try {
                st = holder.provider.getNewServerTransaction(req);
            } catch (Exception e) {
                log.warn("INVITE 手动建事务失败(继续无事务处理): callId={}",
                        callId != null ? callId.getCallId() : null, e);
            }
        }
        SipEvent event = SipEvent.builder()
                .type(SipEvent.Type.REQUEST)
                .stackId(holder != null ? holder.descriptor.stackId() : "unknown")
                .method(req.getMethod())
                .callId(callId != null ? callId.getCallId() : null)
                .fromUser(from != null ? userOf(from.getAddress()) : null)
                .fromTag(from != null ? from.getTag() : null)
                .fromHost(from != null ? hostOf(from.getAddress()) : null)
                .toUser(to != null ? userOf(to.getAddress()) : null)
                .requestUser(reqUri != null ? reqUri.getUser() : null)
                .contentType(ct != null ? ct.getContentType() + "/" + ct.getContentSubType() : null)
                .body(contentOf(req.getContent(), ct))
                // TODO(M1-3): 该版本 RequestEvent 无 getRemoteIpAddress, 注册 NAT 回发地址需从事务对端获取
                .sourceIp(null)
                .sourcePort(null)
                .transport(holder != null ? holder.descriptor.transport() : null)
                .authorization(auth != null ? auth.toString() : null)
                .expires(expires != null ? expires.getExpires() : null)
                .event(evtHdr != null ? evtHdr.getEventType() : null)
                .contactUser(contactUri != null ? contactUri.getUser() : null)
                .contactHost(contactUri != null ? contactUri.getHost() : null)
                .contactPort(contactUri != null ? contactUri.getPort() : null)
                .serverTransaction(st)
                .request(req)
                .build();
        log.debug("SIP 收到: {} from {}:{} callId={}", event.getMethod(), event.getSourceIp(), event.getSourcePort(), event.getCallId());
        dispatch(event);
    }

    @Override
    public void processResponse(ResponseEvent evt) {
        Response resp = evt.getResponse();
        if (resp == null) {
            return;
        }
        StackHolder holder = providerIndex.get(evt.getSource());
        CallIdHeader callId = (CallIdHeader) resp.getHeader(CallIdHeader.NAME);
        CSeqHeader cseq = (CSeqHeader) resp.getHeader(CSeqHeader.NAME);
        ContentTypeHeader ct = (ContentTypeHeader) resp.getHeader(ContentTypeHeader.NAME);
        ToHeader respTo = (ToHeader) resp.getHeader(ToHeader.NAME);
        javax.sip.header.WWWAuthenticateHeader wwwAuth =
                (javax.sip.header.WWWAuthenticateHeader) resp.getHeader(javax.sip.header.WWWAuthenticateHeader.NAME);
        // 应答的 Expires 头(200 OK to SUBSCRIBE 的授予有效期, 目录订阅刷新节奏用, M3-B3 阶段4)
        ExpiresHeader respExpires = (ExpiresHeader) resp.getHeader(ExpiresHeader.NAME);
        if (cseq != null && callId != null) {
            if ("INVITE".equals(cseq.getMethod()) && resp.getStatusCode() >= 200) {
                // INVITE 终响应: CANCEL 不再可用; 200 OK 起对话可用于 BYE
                inviteTransactions.remove(callId.getCallId());
                inviteRequests.remove(callId.getCallId());
                if (resp.getStatusCode() == 200 && evt.getClientTransaction() != null
                        && evt.getClientTransaction().getDialog() != null) {
                    dialogIndex.put(callId.getCallId(), evt.getClientTransaction().getDialog());
                }
            } else if ("BYE".equals(cseq.getMethod()) && resp.getStatusCode() >= 200) {
                dialogIndex.remove(callId.getCallId());
            }
        }
        SipEvent event = SipEvent.builder()
                .type(SipEvent.Type.RESPONSE)
                .stackId(holder != null ? holder.descriptor.stackId() : "unknown")
                .method(cseq != null ? cseq.getMethod() : null)
                .statusCode(resp.getStatusCode())
                .cseq(cseq != null ? cseq.getSeqNumber() : null)
                .callId(callId != null ? callId.getCallId() : null)
                .toTag(respTo != null ? respTo.getTag() : null)
                .wwwAuthenticate(wwwAuth != null ? wwwAuth.toString() : null)
                .expires(respExpires != null ? respExpires.getExpires() : null)
                .body(contentOf(resp.getContent(), ct))
                .sourceIp(null)
                .sourcePort(null)
                .transport(holder != null ? holder.descriptor.transport() : null)
                .build();
        log.debug("SIP 响应: {} {} callId={}", event.getMethod(), event.getStatusCode(), event.getCallId());
        dispatch(event);
    }

    @Override
    public void processIOException(IOExceptionEvent evt) {
        log.error("SIP IO 异常: host={}, port={}", evt.getHost(), evt.getPort());
    }

    @Override
    public void cancel(String stackId, String callId) {
        if (callId == null) {
            return;
        }
        ClientTransaction ct = inviteTransactions.remove(callId);
        Request orig = inviteRequests.remove(callId);
        if (ct == null || orig == null) {
            return;
        }
        StackHolder holder = stacks.get(stackId);
        if (holder == null) {
            return;
        }
        try {
            // CANCEL: 克隆 INVITE 保持 Via/branch 与 CSeq 序号, method 改 CANCEL
            Request cancel = (Request) orig.clone();
            cancel.setMethod("CANCEL");
            CSeqHeader cseq = (CSeqHeader) cancel.getHeader(CSeqHeader.NAME);
            if (cseq != null) {
                cseq.setMethod("CANCEL");
            }
            ClientTransaction cancelCt = holder.provider.getNewClientTransaction(cancel);
            cancelCt.sendRequest();
            log.debug("SIP CANCEL 已发送: callId={}", callId);
        } catch (Exception e) {
            log.warn("CANCEL 发送失败(可能事务已终态): callId={}, {}", callId, e.getMessage());
        }
    }

    @Override
    public void processTimeout(TimeoutEvent evt) {
        String callId = callIdOf(evt);
        if (!evt.isServerTransaction() && callId != null) {
            inviteTransactions.remove(callId);
            inviteRequests.remove(callId);
        }
        log.warn("SIP 事务超时: {} callId={}", evt.isServerTransaction() ? "server" : "client", callId);
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent evt) {
        // 事务终止: 无需处理, 事务由栈回收
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent evt) {
        // 对话终止: 由业务层按会话状态机处理
    }

    // ---------------------------------------------------------------- 工具

    private void dispatch(SipEvent event) {
        SipEventListener l = listener;
        if (l == null) {
            log.warn("无监听器, 丢弃入站消息: {} {}", event.getType(), event.getMethod());
            return;
        }
        dispatchPool.submit(() -> {
            try {
                l.onEvent(event);
            } catch (Exception e) {
                log.error("处理入站消息异常: {} {}", event.getType(), event.getMethod(), e);
            }
        });
    }

    private String callIdOf(TimeoutEvent evt) {
        javax.sip.Transaction tx = evt.isServerTransaction()
                ? evt.getServerTransaction() : evt.getClientTransaction();
        if (tx == null) {
            return null;
        }
        CallIdHeader callId = (CallIdHeader) tx.getRequest().getHeader(CallIdHeader.NAME);
        return callId != null ? callId.getCallId() : null;
    }

    private String userOf(Address address) {
        return address.getURI() instanceof SipURI uri ? uri.getUser() : address.toString();
    }

    private String hostOf(Address address) {
        return address.getURI() instanceof SipURI uri ? uri.getHost() : null;
    }

    private static final Charset GBK_CHARSET = Charset.forName("GBK");

    /** XML 声明里的 encoding 属性（<?xml version="1.0" encoding="GB2312"?>） */
    private static final Pattern XML_ENCODING = Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)[\"']");

    /** XML 声明必须紧贴文档开头, 只嗅探头部若干字节, 避免正文里的同名文本被误命中 */
    private static final int XML_SNIFF_LIMIT = 256;

    /**
     * 报文体解码（包私有便于单测）：① SIP Content-Type 的 charset 参数 → ② 报文体自带的
     * XML encoding 声明 → ③ 字节级探测（UTF-8 严格校验 REPORT, 失败回退 GBK）。
     *
     * <p>②是 M5-B2 联调补的口径：GB28181 设备/平台普遍在报文体里写
     * {@code <?xml version="1.0" encoding="GB2312"?>} 而 SIP 头不带 charset，此前只走③——
     * 而「平台」二字的 GBK 字节 C6 BD CC A8 恰好是合法 UTF-8 序列（解成 U+01BD U+0328
     * = {@code ƽ̨}），严格校验通过就不回退，于是短中文名乱码、含非法字节的长名反而正常。
     * 显式声明与字节探测冲突时以声明为准（GB28181 生态的实际约定）。</p>
     */
    static String contentOf(Object content, ContentTypeHeader contentType) {
        if (content == null) {
            return null;
        }
        if (!(content instanceof byte[] bytes)) {
            return content.toString();
        }
        Charset declared = declaredCharset(contentType);
        if (declared != null) {
            return decode(bytes, declared);
        }
        Charset inBody = xmlDeclaredCharset(bytes);
        if (inBody != null) {
            return decode(bytes, inBody);
        }
        return decode(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 按 charset 解码。UTF-8 走严格校验并在失败时回退 GBK——防「声明/探测为 UTF-8 但实发
     * GBK」的坏设备；其余 charset 直接解（GBK 解码器对任意字节序列都能给出结果, 无需兜底）。
     */
    private static String decode(byte[] bytes, Charset charset) {
        if (!StandardCharsets.UTF_8.equals(charset)) {
            return new String(bytes, charset);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, GBK_CHARSET);
        }
    }

    /** 嗅探报文体头部 XML 声明的 encoding; 声明本身是 ASCII, 用 ISO-8859-1 逐字节映射探测安全 */
    private static Charset xmlDeclaredCharset(byte[] bytes) {
        int limit = Math.min(bytes.length, XML_SNIFF_LIMIT);
        String head = new String(bytes, 0, limit, StandardCharsets.ISO_8859_1);
        int start = head.indexOf("<?xml");
        // 声明必须在文档最开头(容许 BOM 与少量空白); 靠后出现的 <?xml 属于正文, 不认
        if (start < 0 || start > 8) {
            return null;
        }
        int end = head.indexOf("?>", start);
        if (end < 0) {
            return null;
        }
        Matcher m = XML_ENCODING.matcher(head.substring(start, end));
        return m.find() ? charsetOfName(m.group(1)) : null;
    }

    /** 解析 Content-Type 的 charset 参数 */
    private static Charset declaredCharset(ContentTypeHeader contentType) {
        return contentType == null ? null : charsetOfName(contentType.getParameter("charset"));
    }

    /** charset 名称 → Charset: gb2312 按超集 GBK 解码（设备普遍实发 GBK 扩展字）; 未知/非法返回 null */
    private static Charset charsetOfName(String name) {
        if (name == null) {
            return null;
        }
        String n = stripQuotes(name.trim());
        if (n.isEmpty()) {
            return null;
        }
        if ("gb2312".equalsIgnoreCase(n)) {
            return GBK_CHARSET;
        }
        try {
            return Charset.forName(n);
        } catch (Exception e) {
            log.warn("SIP 报文声明了不支持的 charset={}, 回退自动探测", name);
            return null;
        }
    }

    /** 去掉 RFC 3261 允许的参数值引号, NIST 实现的 getParameter 可能保留引号 */
    private static String stripQuotes(String value) {
        return value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                ? value.substring(1, value.length() - 1)
                : value;
    }

}
