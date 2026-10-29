package com.qc.inspect.service;

import com.qc.inspect.common.BizException;
import com.qc.inspect.mapper.QcNoSeqMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/**
 * 单据号分配。
 *
 * <p>原来是各自写「count(*) + 1」：两个人同时开单会拿到同一个号，撞唯一索引后
 * 抛 PG 原始报错（duplicate key value violates unique constraint），用户看到 500
 * 加一段英文。唯一索引只挡住了「写进库」，挡不住「号已经重复分配」本身。
 *
 * <p>现在取号走 {@code qc_no_seq} 的一行计数器 + {@code UPDATE ... RETURNING}，
 * 靠行锁串行化。取号与插入在同一个事务里，回滚时计数一起回滚——不留空号，也不重复。
 *
 * <p>三种单据（QC 检验单 / SH 出货单 / TS 客诉）共用这一套，各自只提供自己的
 * 「已有号数量」。**别在调用方再加一层先查后插**：那层判断在并发下必然是错的，
 * 而且会让这里唯一正确的入口变成摆设。
 */
@Service
@RequiredArgsConstructor
public class DocNoService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final QcNoSeqMapper seqMapper;

    /**
     * 取下一个单号。
     *
     * @param kind  前缀，QC / SH / TS
     * @param width 流水号位数（QC 4 位、SH 3 位、TS 3 位）
     * @param seed  该前缀「已有多少号」的取数方式。只有第一次取号会用到，
     *              之后走计数器；由调用方给是因为不同表的算法不一样，这里不替它们猜
     */
    public String next(String kind, int width, LongSupplier seed) {
        String prefix = kind + LocalDate.now().format(DAY);
        seqMapper.seed(prefix, Math.max(seed.getAsLong(), 0));
        Long no = seqMapper.bump(prefix);
        if (no == null) {
            // 走不到：seed 刚插过或本来就在。真出现说明有人把计数器行删了，
            // 这里明说「重试」而不是抛 NPE 让人猜
            throw BizException.of("单号分配失败，请重试；若持续失败请联系管理员检查编号计数器");
        }
        return prefix + String.format("%0" + width + "d", no);
    }
}
