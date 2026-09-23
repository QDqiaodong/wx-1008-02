package com.px.base.repository;

import com.px.base.entity.AdaptLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AdaptLogRepository extends JpaRepository<AdaptLog, Long> {

    /**
     * 流水读取的唯一查询口径：航线筛选（routeId 非空）与全量查询（routeId 为空）
     * 共用同一条 JPQL，排序由 {@link Pageable} 强制为 create_time 倒序 + id 倒序。
     *
     * <p>分页查询时 Spring Data 在同一条 SQL 内完成 count 与数据取数；
     * 导出（首页、超大页）同样走这里，保证总数与记录行对应同一个查询时刻，
     * 导出进行中新增绑定/拒绝流水也不会出现总数与行数对不上。
     */
    @Query(value = "select l from AdaptLog l where (:routeId is null or l.routeId = :routeId)",
           countQuery = "select count(l) from AdaptLog l where (:routeId is null or l.routeId = :routeId)")
    Page<AdaptLog> queryPage(@Param("routeId") Long routeId, Pageable pageable);
}
