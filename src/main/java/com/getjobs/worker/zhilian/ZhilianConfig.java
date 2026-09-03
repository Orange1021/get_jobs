package com.getjobs.worker.zhilian;

import com.getjobs.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Objects;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class ZhilianConfig {
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


    // 注意：已改为在 ZhilianJobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static ZhilianConfig init() {
        throw new UnsupportedOperationException("请在 ZhilianJobService 中通过 ConfigService 构建配置");
    }

    /**
     * 岗位质量评分投递阈值（0~100）。低于该分数的岗位跳过不投；
     * null 或 0 表示不启用评分门控（保持原有全部投递行为）。
     */
    private Integer qualityScoreThreshold;
}
