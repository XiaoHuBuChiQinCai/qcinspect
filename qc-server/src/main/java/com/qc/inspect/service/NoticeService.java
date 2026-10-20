package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcNotice;
import com.qc.inspect.mapper.QcNoticeMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知。
 *
 * <p>退回到「被退回的人看到」之间，原本隔着一层运气：检验员得自己想起来了才会去翻待办。
 * 这个类把那段运气补上——事件发生时写一条通知，接收人下次进 App 就能看到。
 *
 * <p>两个刻意的取舍：
 * <ul>
 *   <li>只推需要对方动作的事件。审核通过归档不推——纯告知的推送会稀释角标，
 *       吵几次之后没人再看，真正要紧的退回反而被淹没</li>
 *   <li>接收人是自己就不推。自己审自己退回在体系上本来就被禁（管理员例外），
 *       给操作人自己发一条「已被退回」纯属自说自话</li>
 * </ul>
 *
 * <p>写通知不吞异常：宁可让退回失败，也不要出现「单子退回去了但没人收到通知」——
 * 后者在体系上等于异常未通报，比操作失败更难发现。通知表是随主事务一起提交的。
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    public static final String TYPE_REVIEW_REJECT = "REVIEW_REJECT";
    public static final String TYPE_CONCESSION = "CONCESSION";

    private final QcNoticeMapper noticeMapper;

    /**
     * 写一条通知。
     *
     * @param receiver 接收人展示名；为空或 anonymous 时直接丢弃（定时任务等无登录态场景）
     */
    public void push(String receiver, String type, String title, String content,
                     String bizType, Long bizId, String bizNo) {
        if (receiver == null || receiver.isBlank() || UserContext.ANONYMOUS.equals(receiver)) {
            log.debug("接收人为空，跳过通知：type={}, bizNo={}", type, bizNo);
            return;
        }
        if (receiver.equals(UserContext.username())) {
            log.debug("接收人即操作人，跳过通知：type={}, bizNo={}", type, bizNo);
            return;
        }
        QcNotice n = new QcNotice();
        n.setReceiver(receiver);
        n.setNoticeType(type);
        n.setTitle(truncate(title, 200));
        n.setContent(truncate(content, 1000));
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setBizNo(bizNo);
        n.setReadFlag(0);
        n.setCreatedAt(LocalDateTime.now());
        noticeMapper.insert(n);
    }

    /** 我的通知，未读优先、新的在前 */
    public List<QcNotice> list(String operator, boolean unreadOnly, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        return noticeMapper.selectList(new LambdaQueryWrapper<QcNotice>()
                .eq(QcNotice::getReceiver, operator)
                .eq(unreadOnly, QcNotice::getReadFlag, 0)
                .orderByAsc(QcNotice::getReadFlag)
                .orderByDesc(QcNotice::getId)
                .last("limit " + size));
    }

    /** 角标：只回未读数，前端轮询这一个接口就够了 */
    public Map<String, Object> summary(String operator) {
        long unread = noticeMapper.selectCount(new LambdaQueryWrapper<QcNotice>()
                .eq(QcNotice::getReceiver, operator)
                .eq(QcNotice::getReadFlag, 0));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unread", unread);
        return m;
    }

    /** 标记已读。只能读自己的，越权当不存在处理 */
    @Transactional
    public void read(Long id, String operator) {
        QcNotice n = noticeMapper.selectById(id);
        if (n == null || !operator.equals(n.getReceiver())) {
            throw BizException.of("通知不存在");
        }
        if (Integer.valueOf(1).equals(n.getReadFlag())) {
            return;
        }
        noticeMapper.update(null, new LambdaUpdateWrapper<QcNotice>()
                .eq(QcNotice::getId, id)
                .set(QcNotice::getReadFlag, 1)
                .set(QcNotice::getReadAt, LocalDateTime.now()));
    }

    /** 全部标记已读，返回影响行数 */
    @Transactional
    public int readAll(String operator) {
        return noticeMapper.update(null, new LambdaUpdateWrapper<QcNotice>()
                .eq(QcNotice::getReceiver, operator)
                .eq(QcNotice::getReadFlag, 0)
                .set(QcNotice::getReadFlag, 1)
                .set(QcNotice::getReadAt, LocalDateTime.now()));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
