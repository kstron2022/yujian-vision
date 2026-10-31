package com.yujian.vision.gb28181.control;

/**
 * PTZCmd 编码器（GB/T 28181-2016 附录A 8字节前端控制指令）。
 * 指令码与 开源 NVR 项目真机验证实现一致：
 * 字节4=指令码 字节5/6=数据1/2 字节7高4位=变倍速度 字节8=校验和(前7字节和低8位)。
 */
public final class PtzCmdBuilder {

    /** 云台控制指令（docs/03 §5 命令契约） */
    public enum PtzCommand {
        UP(0x08), DOWN(0x04), LEFT(0x02), RIGHT(0x01),
        ZOOM_IN(0x10), ZOOM_OUT(0x20),
        FOCUS_NEAR(0x42), FOCUS_FAR(0x41),
        IRIS_OPEN(0x44), IRIS_CLOSE(0x48),
        STOP(0x00);

        final int cmdCode;

        PtzCommand(int cmdCode) {
            this.cmdCode = cmdCode;
        }
    }

    /** 预置位动作（字节4: 0x81 设置 0x82 调用 0x83 删除） */
    public enum PresetAction {
        SET(0x81), GOTO(0x82), DELETE(0x83);

        final int cmdCode;

        PresetAction(int cmdCode) {
            this.cmdCode = cmdCode;
        }
    }

    private static final int HEAD1 = 0xA5;
    private static final int HEAD2 = 0x0F;
    private static final int HEAD3 = 0x01;

    private PtzCmdBuilder() {
    }

    /**
     * 云台控制指令
     * @param command 指令
     * @param speedPercent 速度百分比 0-100（方向=水平/垂直速度, 变倍/聚焦/光圈=对应速度）
     */
    public static String buildPtz(PtzCommand command, int speedPercent) {
        int speed = toSpeedByte(speedPercent);
        return switch (command) {
            case FOCUS_NEAR, FOCUS_FAR -> encode(command.cmdCode, speed, 0, 0);
            case IRIS_OPEN, IRIS_CLOSE -> encode(command.cmdCode, 0, speed, 0);
            case ZOOM_IN, ZOOM_OUT -> encode(command.cmdCode, speed, 0, 0);
            case STOP -> encode(0x00, 0, 0, 0);
            default -> encode(command.cmdCode, speed, speed, 0);
        };
    }

    /** 预置位指令（字节5 固定1, 字节6 预置位号 1-255） */
    public static String buildPreset(PresetAction action, int presetNum) {
        if (presetNum < 1 || presetNum > 255) {
            throw new IllegalArgumentException("预置位号必须为 1-255: " + presetNum);
        }
        return encode(action.cmdCode, 1, presetNum, 0);
    }

    /** 解码结果: 云台指令或预置位动作（互斥, presetAction 为空即云台指令） */
    public record DecodedPtz(PtzCommand command, int speedPercent, PresetAction presetAction, int presetNum) {
    }

    /**
     * 解码 8 字节 PTZCmd hex（B2 级联透传: 上级 DeviceControl 的 PTZCmd 反查指令）。
     * 校验 A5 0F 01 头与校验和; 指令码 0x81/0x82/0x83 识别为预置位动作（字节6=预置位号）,
     * 其余反查 {@link PtzCommand} 并还原速度百分比（字节5=方向/变倍/聚焦速度, 字节6=光圈速度;
     * 与 {@link #buildPtz}/{@link #buildPreset} 对称）。
     * @throws IllegalArgumentException 长度/hex/头/校验和不合法或指令码未知
     */
    public static DecodedPtz decode(String ptzCmdHex) {
        if (ptzCmdHex == null) {
            throw new IllegalArgumentException("PTZCmd 为空");
        }
        String hex = ptzCmdHex.trim();
        if (hex.length() != 16 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new IllegalArgumentException("PTZCmd 须为 8 字节 hex: " + ptzCmdHex);
        }
        int[] b = new int[8];
        for (int i = 0; i < 8; i++) {
            b[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        if (b[0] != HEAD1 || b[1] != HEAD2 || b[2] != HEAD3) {
            throw new IllegalArgumentException("PTZCmd 头部非法: " + ptzCmdHex);
        }
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += b[i];
        }
        if ((sum & 0xFF) != b[7]) {
            throw new IllegalArgumentException("PTZCmd 校验和不符: " + ptzCmdHex);
        }
        for (PresetAction pa : PresetAction.values()) {
            if (pa.cmdCode == b[3]) {
                return new DecodedPtz(null, 0, pa, b[5]);
            }
        }
        PtzCommand command = null;
        for (PtzCommand c : PtzCommand.values()) {
            if (c.cmdCode == b[3]) {
                command = c;
                break;
            }
        }
        if (command == null) {
            throw new IllegalArgumentException(String.format("PTZCmd 指令码未知: 0x%02X", b[3]));
        }
        // 速度字节鲁棒取值（2026-09-26 LiveGBS 联调缺陷）: 各实现对速度字节放位不同——
        // 我方 build 变倍/聚焦在字节4; LiveGBS 变倍/聚焦在字节5(字节4=0, 实证 ZOOM_IN 恒解
        // 0% 致 ONVIF z=0 相机不动), 方向在字节4; 部分平台惯例水平方向与镜头控制在字节5。
        // 非零优先+回退全部兼容: UP/DOWN 垂直速度取字节4, 其余(水平方向+镜头)取字节5。
        // 注意不可 &0x7F 剥标志位——LiveGBS 速度语义=整字节线性(0x80=128→51%), 剥位反致速度归 0。
        int speedByte = switch (command) {
            case UP, DOWN -> b[4] != 0 ? b[4] : b[5];
            default -> b[5] != 0 ? b[5] : b[4];
        };
        int percent = toSpeedPercent(speedByte);
        // LiveGBS 的变倍指令速度字节恒 0(协议语义="设备默认速度"): GB 设备对此自行按默认速
        // 执行(故 GB 通道 zoom 表现正常), 而 ONVIF ContinuousMove z=0 数学上不移动——统一
        // 规范化为 50% 默认速(STOP 无速度语义, 排除)
        if (command != PtzCommand.STOP && percent == 0) {
            percent = 50;
        }
        return new DecodedPtz(command, percent, null, 0);
    }

    /** {@link #toSpeedByte} 的逆变换（四舍五入, 编码→解码 roundtrip 恒等） */
    private static int toSpeedPercent(int speedByte) {
        if (speedByte <= 0) {
            return 0;
        }
        return Math.min(100, (speedByte * 100 + 127) / 255);
    }

    /** 百分比 -> 字节速度（0-255, 至少1, STOP 时由调用方置 0） */
    private static int toSpeedByte(int speedPercent) {
        if (speedPercent < 0 || speedPercent > 100) {
            throw new IllegalArgumentException("速度百分比必须为 0-100: " + speedPercent);
        }
        if (speedPercent == 0) {
            return 0;
        }
        // 整数运算避免浮点误差: 50% -> 128(0x80), 100% -> 255(0xFF)
        return Math.max(1, (speedPercent * 255 + 50) / 100);
    }

    private static String encode(int cmdCode, int p1, int p2, int combineCode2) {
        int check = (HEAD1 + HEAD2 + HEAD3 + cmdCode + p1 + p2 + (combineCode2 << 4)) & 0xFF;
        return String.format("%02X%02X%02X%02X%02X%02X%02X%02X",
                HEAD1, HEAD2, HEAD3, cmdCode, p1, p2, combineCode2 << 4, check);
    }
}
