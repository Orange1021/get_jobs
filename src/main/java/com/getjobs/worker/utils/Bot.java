package com.getjobs.worker.utils;

import com.getjobs.application.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Slf4j
@Service
public class Bot {

    private static volatile Bot INSTANCE;

    private final ConfigService configService;
    private String hookUrl;
    private boolean isSend;

    @Autowired
    public Bot(ConfigService configService) {
        this.configService = configService;
        INSTANCE = this;
        reloadConfig();
    }

    /**
     * 从数据库配置表加载所需配置
     */
    public void reloadConfig() {
        try {
            this.hookUrl = configService.getConfigValue("HOOK_URL");
            String sendFlag = configService.getConfigValue("BOT_IS_SEND");
            this.isSend = ("true".equalsIgnoreCase(sendFlag) || "1".equals(sendFlag));

            if (this.hookUrl == null || this.hookUrl.isBlank()) {
                log.warn("HOOK_URL 未配置，Bot 将不发送消息。");
                this.isSend = false;
            }
        } catch (Exception e) {
            log.error("加载Bot配置失败: {}", e.getMessage());
            this.isSend = false;
        }
    }

    public static void sendMessageByTime(String message) {
        Bot inst = INSTANCE;
        if (inst == null) {
            log.error("Bot 尚未初始化为 Spring Bean，忽略发送。");
            return;
        }
        inst.sendMessageByTimeInstance(message);
    }

    public void sendMessageByTimeInstance(String message) {
        if (!isSend) {
            return;
        }
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String formattedMessage = String.format("%s %s", currentTime, message);
        sendMessageInstance(formattedMessage);
    }

    public static void sendMessage(String message) {
        Bot inst = INSTANCE;
        if (inst == null) {
            log.warn("Bot 尚未初始化为 Spring Bean，忽略发送。");
            return;
        }
        inst.sendMessageInstance(message);
    }

    public void sendMessageInstance(String message) {
        if (!isSend) {
            return;
        }
        if (hookUrl == null || hookUrl.isBlank()) {
            log.warn("HOOK_URL 未设置，无法推送消息。");
            return;
        }
        try {
            String response = Request.post(hookUrl)
                    .bodyString(buildTextPayload(message),
                            org.apache.hc.core5.http.ContentType.APPLICATION_JSON)
                    .execute()
                    .returnContent()
                    .asString();
            log.info("消息推送成功: {}", response);
        } catch (Exception e) {
            log.error("消息推送失败: {}", e.getMessage());
        }
    }

    /**
     * 构建企业微信文本消息 JSON payload。
     *
     * <p>消息内容可能包含来自网页的岗位名、公司名、风控原因等文本，
     * 其中可能出现双引号、反斜杠、换行等字符；直接字符串拼接会产生非法 JSON
     * 导致推送失败，因此统一用 {@link JSONObject} 序列化保证转义正确。</p>
     *
     * @param message 消息文本（null 视为空字符串）
     * @return 形如 {"msgtype":"text","text":{"content":"..."}} 的 JSON 字符串
     */
    static String buildTextPayload(String message) {
        JSONObject text = new JSONObject();
        text.put("content", message == null ? "" : message);
        JSONObject body = new JSONObject();
        body.put("msgtype", "text");
        body.put("text", text);
        return body.toString();
    }

    public static void main(String[] args) {
        // 本地测试请确保 Spring 容器已初始化并注入 ConfigService。
    }

}
