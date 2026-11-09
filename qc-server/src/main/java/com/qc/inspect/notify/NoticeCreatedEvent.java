package com.qc.inspect.notify;

/**
 * 一条站内通知刚落库。
 *
 * <p>只带 id，不带内容：监听方要什么自己回库查。
 * 把整行塞进事件看着省一次查询，代价是「事件里的数据一定是当时的样子」——
 * 推送用的却可能是几秒后的、更准确的版本（比如管理员刚把账号修好）。
 */
public record NoticeCreatedEvent(Long noticeId) {
}
