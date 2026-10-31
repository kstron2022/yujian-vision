package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录条目 PTZType 防覆盖测试（M5-B2）: LiveGBS 类平台目录不报 PTZType 或恒报 0,
 * 而云台类型支持人工修正（通道信息 patch）。规则=仅真实上报 >0 才采用, 缺省/0 保留现值,
 * 防止人工补录的「球机」被下一次目录收敛/增量 NOTIFY 冲掉（摇杆入口随之消失）。
 */
class CatalogSyncPtzTypeGuardTest {

    private static GbDeviceChannelEntity chWithPtz(Integer ptzType) {
        GbDeviceChannelEntity ch = new GbDeviceChannelEntity();
        ch.setChannelId("42020200001310041756");
        ch.setPtzType(ptzType);
        return ch;
    }

    @Test
    @DisplayName("上报 PTZType=0 → 保留人工补录的球机(1), 不冲掉")
    void zeroReportKeepsManualValue() {
        GbDeviceChannelEntity ch = chWithPtz(1);
        Map<String, String> item = new HashMap<>();
        item.put("PTZType", "0");

        CatalogSyncService.applyItemFields(ch, item);

        assertEquals(1, ch.getPtzType(), "0 不得覆盖人工值");
    }

    @Test
    @DisplayName("上报缺省 PTZType → 保留现值(含 null)")
    void missingReportKeepsExisting() {
        GbDeviceChannelEntity ch = chWithPtz(1);
        CatalogSyncService.applyItemFields(ch, new HashMap<>());
        assertEquals(1, ch.getPtzType(), "缺省保留现值");

        GbDeviceChannelEntity fresh = chWithPtz(null);
        CatalogSyncService.applyItemFields(fresh, new HashMap<>());
        assertNull(fresh.getPtzType(), "新通道缺省仍为 null");
    }

    @Test
    @DisplayName("设备真实上报 >0 → 采用上报值(设备修正类型时自动跟随)")
    void positiveReportWins() {
        GbDeviceChannelEntity ch = chWithPtz(1);
        Map<String, String> item = new HashMap<>();
        item.put("PTZType", "3");

        CatalogSyncService.applyItemFields(ch, item);

        assertEquals(3, ch.getPtzType(), "真实上报 >0 优先于现值");
    }

    @Test
    @DisplayName("其余字段语义不变: Name 缺省回落 channelId, Status 缺失按 OFF")
    void otherFieldsUnchanged() {
        GbDeviceChannelEntity ch = chWithPtz(null);
        ch.setChannelId("42020200001310041756");
        Map<String, String> item = new HashMap<>();
        item.put("Name", "K787+000-散花所入口-内广场");
        item.put("Status", "ON");
        ch.setName("旧名");
        ch.setStatus(1);

        CatalogSyncService.applyItemFields(ch, item);

        assertEquals("K787+000-散花所入口-内广场", ch.getName());
        assertEquals(1, ch.getStatus());
    }
}
