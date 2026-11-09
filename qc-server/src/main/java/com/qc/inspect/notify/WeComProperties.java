package com.qc.inspect.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企业微信应用消息的凭证与开关。
 *
 * <p>全部来自环境变量，仓库里只有占位符：secret 一旦进了 git 历史，
 * 删掉那次提交也没用，得去企业微信后台重置。这是「配在代码里」最容易犯、
 * 也最难补救的错。
 *
 * <p>默认关闭。没配齐 corpId / agentId / secret 时通道视为未启用，
 * 站内通知照常工作——推送是增强，不该成为主链路的启动前提。
 */
@Data
@Component
@ConfigurationProperties(prefix = "qc.wecom")
public class WeComProperties {

    /** 总开关。配了凭证但想临时停推，把它关掉即可，不用改环境变量 */
    private boolean enabled = false;

    /** 企业 ID，企业微信后台「我的企业」页 */
    private String corpId;

    /** 应用 AgentId，自建应用详情页 */
    private Integer agentId;

    /** 应用 Secret */
    private String secret;

    /**
     * API 根地址。留默认值即可；
     * 测试时指向本地假服务器，避免用例真的去敲企业微信。
     */
    private String apiBase = "https://qyapi.weixin.qq.com";

    /** 单次 HTTP 超时（秒）。推送在业务提交之后异步做，超时短一点不拖队列 */
    private int timeoutSeconds = 5;

    /**
     * 手机端入口，例如 http://154.8.209.29/h5/ 。
     * 留空则消息末尾不带链接——没配就别给人一个点不开的地址。
     */
    private String appUrl;

    /** 凭证是否齐全。不齐全时 {@link WeComClient#isEnabled()} 为 false */
    public boolean configured() {
        return corpId != null && !corpId.isBlank()
                && secret != null && !secret.isBlank()
                && agentId != null && agentId > 0;
    }

    /** 给管理员看的一句话：缺什么。别让人对着「未启用」猜 */
    public String missingHint() {
        StringBuilder sb = new StringBuilder();
        if (corpId == null || corpId.isBlank()) {
            sb.append("企业ID（WECOM_CORP_ID）");
        }
        if (agentId == null || agentId <= 0) {
            sb.append(sb.isEmpty() ? "" : "、").append("AgentId（WECOM_AGENT_ID）");
        }
        if (secret == null || secret.isBlank()) {
            sb.append(sb.isEmpty() ? "" : "、").append("应用Secret（WECOM_SECRET）");
        }
        return sb.isEmpty() ? "" : "未配置：" + sb;
    }
}
