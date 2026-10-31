package com.yujian.vision.web.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Redis 固定窗口计数存储单测：Lua 原子 INCR+首次 PEXPIRE, 窗口毫秒经 ARGV 传入。
 */
class RedissonRateLimitStoreTest {

    private RedissonClient redisson;
    private RScript script;
    private RedissonRateLimitStore store;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        when(redisson.getScript(same(StringCodec.INSTANCE))).thenReturn(script);
        store = new RedissonRateLimitStore(redisson);
    }

    @Test
    void incrAndPexpireRunAsSingleLuaScript() {
        when(script.eval(any(), anyString(), any(), anyList(), any())).thenReturn(1L);

        assertEquals(1L, store.increment("vision:rl:login:ip:1.2.3.4", 60));

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> argv = ArgumentCaptor.forClass(Object.class);
        verify(script).eval(same(RScript.Mode.READ_WRITE), lua.capture(), same(RScript.ReturnType.INTEGER),
                anyList(), argv.capture());
        assertEquals(RedissonRateLimitStore.INCR_PEXPIRE_LUA, lua.getValue());
        assertEquals("60000", argv.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void keysArePassedAsSingleElementRawKeyList() {
        when(script.eval(any(), anyString(), any(), anyList(), any())).thenReturn(3L);

        assertEquals(3L, store.increment("vision:rl:play:u:7", 60));

        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass(List.class);
        verify(script).eval(same(RScript.Mode.READ_WRITE), anyString(), same(RScript.ReturnType.INTEGER),
                keys.capture(), any());
        assertEquals(List.of("vision:rl:play:u:7"), keys.getValue());
    }

    @Test
    void windowSecondsConvertToMillisInArgv() {
        when(script.eval(any(), anyString(), any(), anyList(), any())).thenReturn(7L);

        assertEquals(7L, store.increment("k", 30));

        ArgumentCaptor<Object> argv = ArgumentCaptor.forClass(Object.class);
        verify(script).eval(same(RScript.Mode.READ_WRITE), anyString(),
                same(RScript.ReturnType.INTEGER), anyList(), argv.capture());
        assertEquals("30000", argv.getValue());
    }

    @Test
    void nullScriptResultTreatedAsZero() {
        when(script.eval(any(), anyString(), any(), anyList(), any())).thenReturn(null);

        assertEquals(0L, store.increment("k", 60));
    }
}
