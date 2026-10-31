package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.service.system.entity.SysDictDataEntity;
import com.yujian.vision.service.system.service.SysDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公共字典（docs/03 §2, 决策 D16; 登录即可读, 前端启动 Pinia 缓存）
 */
@Tag(name = "公共字典")
@RestController
@RequestMapping("/api/dicts")
@RequiredArgsConstructor
public class DictController {

    private final SysDictService dictService;

    @Operation(summary = "字典数据（按类型）")
    @GetMapping("/{dictType}")
    public R<List<SysDictDataEntity>> data(@PathVariable String dictType) {
        return R.ok(dictService.data(dictType));
    }

    @Operation(summary = "全量字典")
    @GetMapping("/all")
    public R<Map<String, List<SysDictDataEntity>>> all() {
        return R.ok(dictService.all());
    }
}
