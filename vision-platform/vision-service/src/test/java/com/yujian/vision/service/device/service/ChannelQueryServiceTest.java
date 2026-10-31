package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.media.service.SnapshotService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 通道查询分页的目录节点标记测试（M5-B2 联调）:
 * directory 判定不信对端上报的 parental——LiveGBS 对目录节点报 parental=0 却挂
 * children（BUG-2 同根源）, 以「是其它存活通道的 parentId」这一结构事实为准。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelQueryServiceTest {

    @Mock
    private GbDeviceChannelMapper channelMapper;
    @Mock
    private SnapshotService snapshots;

    private ChannelQueryService service;

    @BeforeAll
    static void initMpMetadata() {
        // LambdaQueryWrapper 解析方法引用需要实体的列缓存（纯单测无 MyBatis 上下文）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceChannelEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChannelQueryService(channelMapper, snapshots);
    }

    private static GbDeviceChannelEntity ch(long id, String channelId) {
        GbDeviceChannelEntity c = new GbDeviceChannelEntity();
        c.setId(id);
        c.setDeviceDbId(3L);
        c.setChannelId(channelId);
        return c;
    }

    @Test
    @DisplayName("page 目录标记: 上报 parental=1 / 结构命中 parent / 普通通道 三行各得其所")
    void pageMarksDirectories() {
        GbDeviceChannelEntity reported = ch(11L, "420202");
        reported.setParental(1); // 上报式目录（行政区划节点常报 1）
        GbDeviceChannelEntity structural = ch(12L, "13010100002000000110"); // LiveGBS 式漏报: parental 不报, 但有子
        GbDeviceChannelEntity normal = ch(13L, "34020000001310000001");
        normal.setParentId("13010100002000000110");

        Page<GbDeviceChannelEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(reported, structural, normal));
        page.setTotal(3);
        when(channelMapper.selectPage(any(), any())).thenReturn(page);
        // markDirectories 的父编码集合查询: structural 是 normal 的 parent
        GbDeviceChannelEntity ref = new GbDeviceChannelEntity();
        ref.setParentId("13010100002000000110");
        when(channelMapper.selectList(any())).thenReturn(List.of(ref));

        IPage<GbDeviceChannelEntity> out = service.page(3L, null, null, null, 1, 10);

        assertTrue(out.getRecords().get(0).getDirectory(), "parental=1 → 目录");
        assertTrue(out.getRecords().get(1).getDirectory(), "是其它通道的 parent → 目录(与上报无关)");
        assertFalse(out.getRecords().get(2).getDirectory(), "普通通道非目录");
    }

    @Test
    @DisplayName("page 空页不触发目录标记查询")
    void pageEmptySkipsDirectoryMarking() {
        Page<GbDeviceChannelEntity> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(channelMapper.selectPage(any(), any())).thenReturn(page);

        service.page(3L, null, null, null, 1, 10);

        org.mockito.Mockito.verify(channelMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    @DisplayName("updateChannel: ptzType 合法值写入 patch, 名称/开关照旧")
    void updateChannelSetsPtzType() {
        when(channelMapper.selectOne(any())).thenReturn(ch(11L, "42020200001310041756"));

        service.updateChannel(11L, "K787+000-散花所入口-内广场", 1, 1);

        org.mockito.Mockito.verify(channelMapper).update(org.mockito.Mockito.isNull(),
                org.mockito.ArgumentMatchers.argThat(w -> {
                    // update 第二参数静态类型是 Wrapper, SET 片段在 Update 接口上, pattern-match 取出
                    String sqlSet = (w instanceof com.baomidou.mybatisplus.core.conditions.update.Update<?, ?> u)
                            ? u.getSqlSet()
                            : null;
                    return sqlSet != null
                            && sqlSet.contains("ptz_type")
                            && sqlSet.contains("ptz_enabled")
                            && sqlSet.contains("custom_name");
                }));
    }

    @Test
    @DisplayName("updateChannel: ptzType 越界(9) → 400 且不触库")
    void updateChannelRejectsInvalidPtzType() {
        when(channelMapper.selectOne(any())).thenReturn(ch(11L, "42020200001310041756"));

        com.yujian.vision.common.exception.BizException e =
                org.junit.jupiter.api.Assertions.assertThrows(com.yujian.vision.common.exception.BizException.class,
                        () -> service.updateChannel(11L, null, null, 9));
        org.junit.jupiter.api.Assertions.assertEquals(com.yujian.vision.common.api.ErrorCode.BAD_REQUEST,
                e.getErrorCode());
        org.mockito.Mockito.verify(channelMapper, org.mockito.Mockito.never()).update(any(), any());
    }
}
