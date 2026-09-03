package com.getjobs.worker.liepin;

import lombok.Data;

import java.util.List;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class LiepinConfig {
    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 薪资范围
     */
    private String salary;

    /**
     * 岗位质量评分投递阈值（0~100）。低于该分数的岗位跳过不投；
     * null 或 0 表示不启用评分门控（保持原有全部投递行为）。
     */
    private Integer qualityScoreThreshold;
}
