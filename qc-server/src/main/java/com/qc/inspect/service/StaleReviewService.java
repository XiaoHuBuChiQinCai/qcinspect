package com.qc.inspect.service;

import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcStaleReview;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.mapper.QcStaleReviewMapper;
import com.qc.inspect.mapper.QcStandardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 改版遗留的复核记录。
 *
 * <p>stale-usage 算出「哪些作废版本上的结论值得查」，这个服务回答「查过了吗、结论是什么」。
 * 两者加起来才是一条闭环：没有后半截，审核时那条「发现旧版放行风险」的观察永远是未闭环项。
 */
@Service
@RequiredArgsConstructor
public class StaleReviewService {

    /** remark 的下限。低于这个字数写不出「查了什么、凭什么」，等于没记 */
    private static final int MIN_REMARK = 5;

    private final QcStaleReviewMapper reviewMapper;
    private final QcStandardMapper standardMapper;
    private final StandardService standardService;

    /**
     * 给一个作废版本记一次复核。
     *
     * <p>快照（版本号、产品名、判定单数、风险档）当场从 stale-usage 取，不信任前端传值：
     * 复核记录是给审核看的证据，里面的数字必须和系统当时算出来的一致。
     * 顺带解决了口径问题——两边不可能各算一套。
     */
    @Transactional
    public QcStaleReview submit(Long standardId, Map<String, Object> body, String operator) {
        QcStandard std = standardMapper.selectById(standardId);
        if (std == null) {
            throw BizException.of("标准版本不存在：" + standardId);
        }
        // 现行版本没有「旧版结论」可复核。放过去的话，有人会在生效版本上盖章，
        // 而那块内容本来就不出现在待复核清单里
        if ("ACTIVE".equals(std.getStatus())) {
            throw BizException.of("这是现行生效版本，没有需要复核的旧版结论");
        }

        String verdict = text(body.get("verdict"));
        if (!QcStaleReview.KEPT.equals(verdict) && !QcStaleReview.ACTION.equals(verdict)) {
            throw BizException.of("复核结论只能选「维持原判」或「需要处理」");
        }
        String remark = text(body.get("remark"));
        if (remark.length() < MIN_REMARK) {
            throw BizException.of("请写清抽查了哪几张、依据什么、结论怎么来的（至少 " + MIN_REMARK + " 个字）");
        }

        Map<String, Object> row = staleRowOf(std.getProductId(), standardId);
        if (row == null) {
            // 这一版从没开过单，或者单子都已作废——清单里不出现它，就不该被复核
            throw BizException.of("这一版下没有已判定的单据，不需要复核");
        }

        QcStaleReview r = new QcStaleReview();
        r.setStandardId(standardId);
        r.setProductId(std.getProductId());
        r.setVersion(std.getVersion());
        r.setStandardCode(std.getStandardCode());
        r.setProductName(row.get("product_name") == null ? null : String.valueOf(row.get("product_name")));
        r.setRiskLevel(row.get("risk") == null ? null : String.valueOf(row.get("risk")));
        r.setVerdict(verdict);
        r.setInspectionCount(row.get("inspection_count") instanceof Number n ? n.intValue() : null);
        r.setRemark(remark);
        r.setReviewer(operator);
        r.setReviewedAt(LocalDateTime.now());
        r.setCreatedAt(LocalDateTime.now());
        r.setDelFlag(0);
        reviewMapper.insert(r);
        return r;
    }

    /**
     * 某作废版本的全部复核记录，最新在前。
     *
     * <p>只读，登录即可：检验员看到「这批旧版单已经被复核过、结论是维持」是有用的，
     * 他能判断要不要再报一次。记录里没有人事评价，也没有隐藏的必要。
     */
    public List<QcStaleReview> listOf(Long standardId) {
        return reviewMapper.listOfStandard(standardId);
    }

    /**
     * 从 stale-usage 里挑出目标那一行。
     *
     * <p>复用而不是重写一遍聚合：这里要的四个数（单数、合格数、风险档、产品名）
     * 那边已经算好，再写一份必然慢慢走偏，最后「复核记录里的单数」和
     * 「清单上显示的单数」对不上——那种不一致会让人怀疑两个都不准。
     */
    private Map<String, Object> staleRowOf(Long productId, Long standardId) {
        Object rows = standardService.staleUsage(productId).get("rows");
        if (!(rows instanceof List<?> list)) {
            return null;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("standard_id") instanceof Number n
                    && n.longValue() == standardId) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                return cast;
            }
        }
        return null;
    }

    /** 前端传 null 就是没填，不要变成字符串 "null" */
    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
