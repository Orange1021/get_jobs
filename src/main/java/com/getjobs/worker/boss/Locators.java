package com.getjobs.worker.boss;

/**
 * Boss直聘网页元素定位器（消融裁剪版）。
 *
 * <p>仅保留仍有调用方的常量；平台改版时的选择器覆盖统一走
 * {@code SelectorRepository}（selectors.yml），键名见 selectors.yml.example。</p>
 */
public class Locators {
    /** 岗位列表区块（selectors.yml 覆盖键 JOB_LIST_CONTAINER 的默认值） */
    public static final String JOB_LIST_CONTAINER = "//div[@class='job-list-container']";

    /** 岗位卡片标签列表（用于福利文本采集，参与岗位评分） */
    public static final String TAG_LIST = "ul.tag-list li";

    /** 详情页"立即沟通"按钮（多次点击场景） */
    public static final String CHAT_BUTTON = "[class*='btn btn-startchat']";

    /** HR 活跃状态（selectors.yml 覆盖键 HR_ACTIVE_TIME 的默认值） */
    public static final String HR_ACTIVE_TIME = "//span[@class='boss-active-time']";

    /** 登录入口按钮（RiskGuard 风控探测规则④：会话中途登录态失效） */
    public static final String LOGIN_BTNS = "//div[@class='btns']";
}
