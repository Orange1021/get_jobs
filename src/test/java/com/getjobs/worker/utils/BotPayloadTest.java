package com.getjobs.worker.utils;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Bot 消息 payload 构建测试。
 *
 * <p>消息内容可能包含来自网页的岗位名、公司名、风控原因等文本，
 * 直接字符串拼接进 JSON 会因引号/反斜杠/换行产生非法 JSON 导致推送失败。
 * {@code Bot.buildTextPayload} 必须对任意文本都产出可解析、可还原的合法 JSON。</p>
 */
class BotPayloadTest {

    @Test
    @DisplayName("普通文本：payload 结构正确且内容原样保留")
    void plainTextRoundTrip() {
        String payload = Bot.buildTextPayload("投递任务完成，共发起3个聊天");

        JSONObject parsed = new JSONObject(payload);
        assertThat(parsed.getString("msgtype")).isEqualTo("text");
        assertThat(parsed.getJSONObject("text").getString("content"))
                .isEqualTo("投递任务完成，共发起3个聊天");
    }

    @Test
    @DisplayName("含双引号的文本：不会破坏 JSON 结构")
    void textWithQuotesStaysValidJson() {
        String message = "岗位提醒：他说\"今天不招人了\"";

        String payload = Bot.buildTextPayload(message);

        JSONObject parsed = new JSONObject(payload);
        assertThat(parsed.getJSONObject("text").getString("content")).isEqualTo(message);
    }

    @Test
    @DisplayName("含反斜杠与路径分隔符的文本：转义正确")
    void textWithBackslashesStaysValidJson() {
        String message = "C:\\Users\\test\\resume.jpg 已发送";

        String payload = Bot.buildTextPayload(message);

        JSONObject parsed = new JSONObject(payload);
        assertThat(parsed.getJSONObject("text").getString("content")).isEqualTo(message);
    }

    @Test
    @DisplayName("含换行与中文标点的文本：转义正确")
    void textWithNewlinesStaysValidJson() {
        String message = "第一行：风控熔断\n第二行：触发「环境异常」提示，请人工检查账号状态！";

        String payload = Bot.buildTextPayload(message);

        JSONObject parsed = new JSONObject(payload);
        assertThat(parsed.getJSONObject("text").getString("content")).isEqualTo(message);
    }

    @Test
    @DisplayName("null 与空字符串：不抛异常，content 归一为空串")
    void nullAndEmptyAreSafe() {
        assertThatCode(() -> Bot.buildTextPayload(null)).doesNotThrowAnyException();
        assertThat(new JSONObject(Bot.buildTextPayload(null)).getJSONObject("text").getString("content"))
                .isEmpty();
        assertThat(new JSONObject(Bot.buildTextPayload("")).getJSONObject("text").getString("content"))
                .isEmpty();
    }
}
