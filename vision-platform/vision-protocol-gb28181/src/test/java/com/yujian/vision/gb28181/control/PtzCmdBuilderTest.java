package com.yujian.vision.gb28181.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.yujian.vision.gb28181.control.PtzCmdBuilder.PresetAction;
import com.yujian.vision.gb28181.control.PtzCmdBuilder.PtzCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PTZCmd 编码契约: 预期值手工计算(含校验和), 与开源 NVR 项目真机验证指令码对齐。
 * 格式: A5 0F 01 CMD P1 P2 CC2<<4 CHECKSUM
 */
class PtzCmdBuilderTest {

    @Test
    @DisplayName("方向指令: LEFT 50%")
    void left() {
        // speed 50 -> round(127.5)=128=0x80; checksum A5+0F+01+02+80 = 0x137 -> 0x37
        assertEquals("A50F0102808000B7", PtzCmdBuilder.buildPtz(PtzCommand.LEFT, 50));
    }

    @Test
    @DisplayName("方向指令: RIGHT 100% 速度满档 FF")
    void right() {
        // 100 -> 255=0xFF; checksum A5+0F+01+01+FF = 0x1B5 -> 0xB5
        assertEquals("A50F0101FFFF00B4", PtzCmdBuilder.buildPtz(PtzCommand.RIGHT, 100));
    }

    @Test
    @DisplayName("全部方向/变倍指令码与开源方案一致")
    void commandCodes() {
        assertEquals("A50F0108808000BD", PtzCmdBuilder.buildPtz(PtzCommand.UP, 50));
        assertEquals("A50F0104808000B9", PtzCmdBuilder.buildPtz(PtzCommand.DOWN, 50));
        assertEquals("A50F011080000045", PtzCmdBuilder.buildPtz(PtzCommand.ZOOM_IN, 50));
        assertEquals("A50F012080000055", PtzCmdBuilder.buildPtz(PtzCommand.ZOOM_OUT, 50));
    }

    @Test
    @DisplayName("聚焦: 速度在字节5(数据1)")
    void focus() {
        assertEquals("A50F014280000077", PtzCmdBuilder.buildPtz(PtzCommand.FOCUS_NEAR, 50));
        assertEquals("A50F014180000076", PtzCmdBuilder.buildPtz(PtzCommand.FOCUS_FAR, 50));
    }

    @Test
    @DisplayName("光圈: 速度在字节6(数据2)")
    void iris() {
        assertEquals("A50F014400800079", PtzCmdBuilder.buildPtz(PtzCommand.IRIS_OPEN, 50));
        assertEquals("A50F01480080007D", PtzCmdBuilder.buildPtz(PtzCommand.IRIS_CLOSE, 50));
    }

    @Test
    @DisplayName("STOP: 全零指令, 校验和 B5")
    void stop() {
        assertEquals("A50F0100000000B5", PtzCmdBuilder.buildPtz(PtzCommand.STOP, 100));
        assertEquals("A50F0100000000B5", PtzCmdBuilder.buildPtz(PtzCommand.STOP, 0));
    }

    @Test
    @DisplayName("预置位: 设置/调用/删除")
    void presets() {
        assertEquals("A50F018101010038", PtzCmdBuilder.buildPreset(PresetAction.SET, 1));
        assertEquals("A50F018201FF0037", PtzCmdBuilder.buildPreset(PresetAction.GOTO, 255));
        assertEquals("A50F018301100049", PtzCmdBuilder.buildPreset(PresetAction.DELETE, 16));
    }

    @Test
    @DisplayName("参数越界拒绝")
    void invalidParams() {
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.buildPtz(PtzCommand.LEFT, 101));
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.buildPtz(PtzCommand.LEFT, -1));
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.buildPreset(PresetAction.SET, 0));
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.buildPreset(PresetAction.SET, 256));
    }

    @Test
    @DisplayName("输出恒为 16 位大写 HEX")
    void format() {
        for (PtzCommand c : PtzCommand.values()) {
            String hex = PtzCmdBuilder.buildPtz(c, 30);
            assertEquals(16, hex.length());
            assertEquals(hex, hex.toUpperCase());
        }
    }

    @Test
    @DisplayName("decode: 全指令 x 全速度档(1-100 穷举) 编码->解码 roundtrip 恒等")
    void decodeRoundtrip() {
        for (PtzCommand c : PtzCommand.values()) {
            // 速度 0 不在穷举内: 解码侧已把非 STOP 的零速规范化为 50%(LiveGBS 默认速语义)
            for (int p = 1; p <= 100; p++) {
                PtzCmdBuilder.DecodedPtz d = PtzCmdBuilder.decode(PtzCmdBuilder.buildPtz(c, p));
                assertEquals(c, d.command(), c + "@" + p);
                // STOP 编码恒全零(速度无语义), 解码速度恒 0
                assertEquals(c == PtzCommand.STOP ? 0 : p, d.speedPercent(), c + "@" + p);
            }
        }
    }

    @Test
    @DisplayName("decode: 零速变倍(LiveGBS 默认速语义)规范化为 50%, STOP 保持 0")
    void decodeZeroSpeedNormalized() {
        // A5+0F+01+10+00+00+00 = 0x0C5 -> 0xC5; LiveGBS 形态: zoom 指令速度字节全零
        PtzCmdBuilder.DecodedPtz d = PtzCmdBuilder.decode("A50F0110000000C5");
        assertEquals(PtzCommand.ZOOM_IN, d.command());
        assertEquals(50, d.speedPercent());
        PtzCmdBuilder.DecodedPtz stop = PtzCmdBuilder.decode(PtzCmdBuilder.buildPtz(PtzCommand.STOP, 0));
        assertEquals(PtzCommand.STOP, stop.command());
        assertEquals(0, stop.speedPercent());
    }

    @Test
    @DisplayName("decode: 预置位 设置/调用/删除 roundtrip, 预置位号还原")
    void decodePresetRoundtrip() {
        for (PresetAction pa : PresetAction.values()) {
            for (int num : new int[] {1, 16, 128, 255}) {
                PtzCmdBuilder.DecodedPtz d = PtzCmdBuilder.decode(PtzCmdBuilder.buildPreset(pa, num));
                assertEquals(pa, d.presetAction(), pa + "@" + num);
                assertEquals(num, d.presetNum(), pa + "@" + num);
            }
        }
    }

    @Test
    @DisplayName("decode: 手工样例与非法输入")
    void decodeSamples() {
        PtzCmdBuilder.DecodedPtz d = PtzCmdBuilder.decode("A50F0102808000B7");
        assertEquals(PtzCommand.LEFT, d.command());
        assertEquals(50, d.speedPercent());
        // 小写 hex 兼容
        assertEquals(PtzCommand.IRIS_OPEN, PtzCmdBuilder.decode("a50f014400800079").command());
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.decode(null));
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.decode("A50F01")); // 长度
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.decode("ZZ0F0102808000B7")); // 非 hex
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.decode("A50F0202808000B6")); // 头
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.decode("A50F0102808000FF")); // 校验和
        assertThrows(IllegalArgumentException.class, () -> PtzCmdBuilder.decode("A50F019001010047")); // 未知指令码0x90(头/校验和均合法)
    }

    @Test
    @DisplayName("decode: LiveGBS 形态——变倍速度在字节5(字节4=0), 修复前恒解 0%")
    void decodeLiveGbsZoom() {
        // A5+0F+01+10+00+80+00 = 0x145 -> 0x45; 速度 0x80=128 -> 50%
        PtzCmdBuilder.DecodedPtz d = PtzCmdBuilder.decode("A50F011000800045");
        assertEquals(PtzCommand.ZOOM_IN, d.command());
        assertEquals(50, d.speedPercent());
    }

    @Test
    @DisplayName("decode: LiveGBS 形态——方向速度在字节4(0x81), 字节5=0 回退不被破坏")
    void decodeLiveGbsPan() {
        // A5+0F+01+01+81+00+00 = 0x137 -> 0x37; 速度 0x81=129 -> 51%
        PtzCmdBuilder.DecodedPtz d = PtzCmdBuilder.decode("A50F010181000037");
        assertEquals(PtzCommand.RIGHT, d.command());
        assertEquals(51, d.speedPercent());
    }
}
