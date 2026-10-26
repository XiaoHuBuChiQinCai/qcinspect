package com.qc.inspect.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 备份体积的显示写法。
 *
 * <p>看着像小事，其实是这个功能最容易误导人的地方：空库的备份只有十几 KB，
 * 一律按 MB 显示会变成「0.0 MB」，页面上看着就像「压根没备份成」。
 * 而 1 MB 上下这个换算边界最容易出「1024 KB」这种别扭说法。
 */
class BackupHealthServiceTest {

    @Test
    @DisplayName("没拿到大小就给 null，不要编一个 0")
    void nullWhenUnknown() {
        assertThat(BackupHealthService.sizeText(null)).isNull();
        assertThat(BackupHealthService.sizeText("not a number")).isNull();
    }

    @Test
    @DisplayName("不足 1 KB 报字节")
    void bytes() {
        assertThat(BackupHealthService.sizeText(0L)).isEqualTo("0 B");
        assertThat(BackupHealthService.sizeText(512L)).isEqualTo("512 B");
        assertThat(BackupHealthService.sizeText(1023L)).isEqualTo("1023 B");
    }

    @Test
    @DisplayName("十几 KB 的空库备份要报 KB —— 报成 0.0 MB 会被当成没备份成")
    void kilobytes() {
        assertThat(BackupHealthService.sizeText(1024L)).isEqualTo("1 KB");
        assertThat(BackupHealthService.sizeText(14812L)).isEqualTo("14 KB");
        assertThat(BackupHealthService.sizeText(3072L * 1024)).isEqualTo("3.0 MB");
    }

    @Test
    @DisplayName("1 MB 边界不许出现「1024 KB」")
    void megabyteBoundary() {
        assertThat(BackupHealthService.sizeText(1048575L)).as("差一个字节也该换说法").isEqualTo("1.0 MB");
        assertThat(BackupHealthService.sizeText(1048576L)).isEqualTo("1.0 MB");
        assertThat(BackupHealthService.sizeText(11534336L)).isEqualTo("11.0 MB");
    }
}
