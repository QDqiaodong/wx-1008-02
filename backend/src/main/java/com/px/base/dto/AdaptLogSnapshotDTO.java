package com.px.base.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.px.base.entity.AdaptLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 适配流水快照：页面分页读取与服务端导出共用同一份结构。
 *
 * <p>{@code queryTime} 是服务端真正执行查询的时刻；{@code total} 与 {@code records}
 * 在同一只读事务内取数，对应同一查询时刻——导出进行中即使新增一条绑定/拒绝流水，
 * 已生成快照里的总数与行数仍然对得上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptLogSnapshotDTO {
    /** 筛选航线ID；null/0 表示全部航线 */
    private Long routeId;

    /** 筛选航线编号；全部航线为 "ALL"，仅用于文件/页面展示，不参与过滤 */
    private String routeCode;

    /** 筛选锚点ID；null 表示不按锚点过滤 */
    private Long anchorId;

    /** 服务端执行查询的时刻（同一查询时刻口径） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime queryTime;

    /** 命中记录总数；0 表示“没有命中”，与请求失败由 HTTP 状态码区分 */
    private long total;

    /** 当前页码（从 1 起）；导出整份快照时为 1 */
    private int page;

    /** 每页条数；导出时等于 total */
    private int size;

    /** 总页数 */
    private int totalPages;

    /** 固定排序口径说明，供复核 */
    private String sort;

    /** 稳定排序后的当前页/全量记录 */
    private List<AdaptLog> records;
}
