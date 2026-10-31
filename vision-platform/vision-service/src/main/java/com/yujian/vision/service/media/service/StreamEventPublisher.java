package com.yujian.vision.service.media.service;

import java.util.Map;

/**
 * 平台事件推送 SPI（WS 实现在 vision-web, docs/03 §10）
 */
public interface StreamEventPublisher {

    /** STREAM_READY: {streamId, channelId, urls, videoCodec}（videoCodec: H264/H265, 空=未知, B5 多协议） */
    void streamReady(String streamId, String channelId, Map<String, String> urls, String videoCodec);

    /** STREAM_CLOSED: {streamId, reason} */
    void streamClosed(String streamId, String reason);

    /** STREAM_FALLBACK: {streamId, channelId}（UDP 收流超时自动降级 TCP re-INVITE） */
    default void streamFallback(String streamId, String channelId) {
    }

    /** DEVICE_ONLINE: {deviceId, name}（注册成功） */
    default void deviceOnline(String deviceId, String name) {
    }

    /** DEVICE_OFFLINE: {deviceId, name}（心跳超时/注销） */
    default void deviceOffline(String deviceId, String name) {
    }

    /** CATALOG_SYNC_PROGRESS: {deviceId, total, synced} */
    default void catalogSyncProgress(String deviceId, int total, int synced) {
    }

    /** CHANNEL_CHANGE: {deviceId}（目录收敛完成） */
    default void channelChange(String deviceId) {
    }

    /** NOTICE_NEW: {id, type, title, level, createTime}（站内新通知, 消息中心 B1-c） */
    default void noticeNew(Long noticeId, String type, String title, int level, String createTime) {
    }

    /** ALARM: {deviceId, channelId, alarmType, alarmPriority, alarmTime}（设备报警入库, M3-B4, docs/03 §WS） */
    default void alarmEvent(String deviceId, String channelId, Integer alarmType, Integer alarmPriority,
            String alarmTime) {
    }

    /** TALK: {talkId, channelId, status, reason}（对讲状态事件, M3-B5; status=TALK_STARTED/TALK_STOPPED） */
    default void talkEvent(String talkId, String channelId, String status, String reason) {
    }

    /** PLAYBACK_CONTROL_FAILED: {streamId, code}（回放控制 INFO 被设备拒绝, M5-B2 联调:
     *  部分设备不支持 MANSRTSP 对话内控制回 400, 旧逻辑无监听致前端静默无效反复点击） */
    default void playbackControlFailed(String streamId, int code) {
    }
}
