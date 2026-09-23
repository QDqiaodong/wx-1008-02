package com.px.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.px.base.entity.AdaptLog;
import com.px.base.entity.FlightRoute;
import com.px.base.repository.AdaptLogRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.service.AdaptAuditRecorder;
import com.px.base.service.AdaptService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 适配流水“读取/导出自洽快照”验收（H2 + MockMvc；Redis 深桩）：
 *  1. 稳定排序：分页、全量导出、直连接口同序（createTime desc, id desc），同一时刻由 id 兜底；
 *  2. 快照一致：读取/导出事务进行中新增一条流水，total 与记录行数仍对得上；
 *  3. 空结果语义：筛选存在但无命中 → 200 total=0；筛选不存在 → 400（请求失败）；
 *  4. 导出在服务端生成，文件名/内容都带筛选航线、查询时间、总数；
 *  5. 分页口径与导出口径相同。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("itest")
class AdaptLogSnapshotIntegrationTest {

    @TestConfiguration
    static class RedisStubConfig {
        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> stubRedisTemplate() {
            return mock(RedisTemplate.class, RETURNS_DEEP_STUBS);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired AdaptLogRepository adaptLogRepository;
    @Autowired FlightRouteRepository flightRouteRepository;
    @Autowired AdaptService adaptService;
    @Autowired AdaptAuditRecorder auditRecorder;

    private long routeAId;
    private long routeBId;

    private JsonNode data(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString()).path("data");
    }

    private FlightRoute getOrCreateRoute(String code) {
        return flightRouteRepository.findByRouteCode(code).orElseGet(() ->
                flightRouteRepository.save(FlightRoute.builder()
                        .routeCode(code).routeName(code + "-名").routeGroup("测试区")
                        .windSpeed(new BigDecimal("5.00")).status(1).build()));
    }

    private AdaptLog log(long routeId, String routeCode, String type, LocalDateTime time) {
        AdaptLog l = adaptLogRepository.save(AdaptLog.builder()
                .routeId(routeId).routeCode(routeCode).anchorId(1L).anchorCode("A-1")
                .operationType(type).reason("验收-" + type + "-" + time).operator("tester")
                .build());
        // 显式覆盖发生时间，制造“同一时刻多条”场景验证 id 兜底排序
        l.setCreateTime(time);
        return adaptLogRepository.save(l);
    }

    @BeforeEach
    void clean() {
        // 幂等：路由跨测试复用（唯一编号），只清流水
        adaptLogRepository.deleteAll();
        routeAId = getOrCreateRoute("IT-ROUTE-A").getId();
        routeBId = getOrCreateRoute("IT-ROUTE-B").getId();
    }

    private List<Long> orderedIds(JsonNode snap) {
        List<Long> ids = new ArrayList<>();
        snap.path("records").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    @Test
    void 稳定排序_时间倒序且同一时刻按id倒序() throws Exception {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 20, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 21, 10, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 9, 22, 10, 0, 0);
        AdaptLog l1 = log(routeAId, "IT-ROUTE-A", "BIND", t1);
        AdaptLog l2 = log(routeAId, "IT-ROUTE-A", "REJECT", t2);
        AdaptLog l3a = log(routeAId, "IT-ROUTE-A", "BIND", t3);
        AdaptLog l3b = log(routeAId, "IT-ROUTE-A", "UNBIND", t3);

        MvcResult res = mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAId))
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk()).andReturn();
        JsonNode snap = data(res);

        assertThat(snap.path("sort").asText()).isEqualTo("createTime:desc,id:desc");
        assertThat(snap.path("total").asLong()).isEqualTo(4);
        // 同一时刻 t3 的两条按 id 倒序：后插入(l3b)在前；整体按时间倒序
        assertThat(orderedIds(snap)).containsExactly(l3b.getId(), l3a.getId(), l2.getId(), l1.getId());
    }

    @Test
    void 分页与导出顺序一致且各页总数口径相同() throws Exception {
        for (int i = 0; i < 5; i++) {
            log(routeAId, "IT-ROUTE-A", "BIND",
                    LocalDateTime.of(2026, 9, 20, 9, 0).plusMinutes(i));
        }
        // 航线乙 2 条，不允许混入航线甲筛选
        log(routeBId, "IT-ROUTE-B", "BIND", LocalDateTime.of(2026, 9, 21, 9, 0));
        log(routeBId, "IT-ROUTE-B", "REJECT", LocalDateTime.of(2026, 9, 21, 9, 1));

        MvcResult p1 = mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAId))
                        .param("size", "2"))
                .andExpect(status().isOk()).andReturn();
        JsonNode page1 = data(p1);
        assertThat(page1.path("total").asLong()).isEqualTo(5);
        assertThat(page1.path("page").asInt()).isEqualTo(1);
        assertThat(page1.path("size").asInt()).isEqualTo(2);
        assertThat(page1.path("totalPages").asInt()).isEqualTo(3);
        assertThat(orderedIds(page1)).hasSize(2);

        MvcResult p2 = mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAId)).param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk()).andReturn();
        assertThat(orderedIds(data(p2))).hasSize(2);

        MvcResult p3 = mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAId)).param("page", "3")
                        .param("size", "2"))
                .andExpect(status().isOk()).andReturn();
        JsonNode page3 = data(p3);
        assertThat(orderedIds(page3)).hasSize(1);

        // 全量导出顺序 == 分页拼接顺序（同一筛选、同一稳定排序）
        MvcResult ex = mvc.perform(get("/api/adapt/logs/export")
                        .param("routeId", String.valueOf(routeAId)))
                .andExpect(status().isOk()).andReturn();
        JsonNode exportSnap = om.readTree(ex.getResponse().getContentAsString());
        List<Long> exportIds = new ArrayList<>();
        exportSnap.path("records").forEach(n -> exportIds.add(n.get("id").asLong()));

        List<Long> pagedIds = new ArrayList<>();
        pagedIds.addAll(orderedIds(page1));
        pagedIds.addAll(orderedIds(data(p2)));
        pagedIds.addAll(orderedIds(page3));
        assertThat(exportIds).containsExactlyElementsOf(pagedIds);
        assertThat(exportSnap.path("total").asLong()).isEqualTo(5);
        assertThat(exportSnap.path("records").size()).isEqualTo(5);
    }

    @Test
    void 空结果明确区分没有命中与请求失败() throws Exception {
        // 航线存在但无流水：200 + total=0 + 空数组（没有命中）
        MvcResult empty = mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeBId)))
                .andExpect(status().isOk()).andReturn();
        JsonNode snap = data(empty);
        assertThat(snap.path("total").asLong()).isZero();
        assertThat(snap.path("records").isArray()).isTrue();
        assertThat(snap.path("records").size()).isZero();

        // 导出同样给出 total=0 的快照文件，而不是失败
        MvcResult emptyExport = mvc.perform(get("/api/adapt/logs/export")
                        .param("routeId", String.valueOf(routeBId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("total0")))
                .andReturn();
        JsonNode exportSnap = om.readTree(emptyExport.getResponse().getContentAsString());
        assertThat(exportSnap.path("total").asLong()).isZero();

        // 不存在的航线：请求失败 400，HTTP 错误，不返回空列表
        mvc.perform(get("/api/adapt/logs").param("routeId", "999999"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/adapt/logs/export").param("routeId", "999999"))
                .andExpect(status().isBadRequest());

        // routeId=0 规范为“全部航线”（直连接口同口径），不应该 400
        mvc.perform(get("/api/adapt/logs").param("routeId", "0"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/adapt/logs/export").param("routeId", "0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("ALL")));
    }

    @Test
    void 导出文件带筛选航线查询时间总数且内容为服务端快照() throws Exception {
        log(routeAId, "IT-ROUTE-A", "BIND", LocalDateTime.of(2026, 9, 20, 8, 0));

        MvcResult ex = mvc.perform(get("/api/adapt/logs/export")
                        .param("routeId", String.valueOf(routeAId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("route-" + routeAId)))
                .andExpect(header().string("Content-Disposition", containsString("total1")))
                .andReturn();
        assertThat(ex.getResponse().getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);

        JsonNode snap = om.readTree(ex.getResponse().getContentAsString());
        assertThat(snap.path("routeId").asLong()).isEqualTo(routeAId);
        assertThat(snap.path("routeCode").asText()).isEqualTo("IT-ROUTE-A");
        assertThat(snap.path("total").asLong()).isEqualTo(1);
        assertThat(snap.path("queryTime").asText()).isNotBlank();
        assertThat(snap.path("sort").asText()).isEqualTo("createTime:desc,id:desc");
        assertThat(snap.path("records").size()).isEqualTo(1);

        // 全部航线导出
        MvcResult all = mvc.perform(get("/api/adapt/logs/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("ALL")))
                .andReturn();
        JsonNode allSnap = om.readTree(all.getResponse().getContentAsString());
        assertThat(allSnap.path("routeId").isNull());
        assertThat(allSnap.path("routeCode").asText()).isEqualTo("ALL");
    }

    @Test
    void 全量查询与航线筛选共用同一稳定排序() {
        AdaptLog a1 = log(routeAId, "IT-ROUTE-A", "BIND", LocalDateTime.of(2026, 9, 20, 8, 0));
        AdaptLog b1 = log(routeBId, "IT-ROUTE-B", "BIND", LocalDateTime.of(2026, 9, 21, 8, 0));
        AdaptLog a2 = log(routeAId, "IT-ROUTE-A", "REJECT", LocalDateTime.of(2026, 9, 22, 8, 0));

        var all = adaptService.getLogsSnapshot(null, 1, 50);
        var onlyA = adaptService.getLogsSnapshot(routeAId, 1, 50);

        assertThat(all.getRecords().stream().map(AdaptLog::getId))
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(all.getTotal()).isEqualTo(3);
        assertThat(all.getRecords().stream().map(AdaptLog::getId).toList())
                .containsExactly(a2.getId(), b1.getId(), a1.getId());
        assertThat(onlyA.getRecords().stream().map(AdaptLog::getId).toList())
                .containsExactly(a2.getId(), a1.getId());
        assertThat(onlyA.getRouteCode()).isEqualTo("IT-ROUTE-A");
        assertThat(all.getRouteCode()).isEqualTo("ALL");
    }

    /**
     * 并发验收：一次读取/导出事务先做 count，此时另一个独立事务新增一条
     * 绑定/拒绝流水并提交，随后同一读取事务再取记录行——
     * REPEATABLE_READ 视图固定，count 与 rows 必须同为插入前的 3；
     * 事务结束后重新查询才能看到新增的第 4 条。这正是服务端快照
     * “总数与行数对应同一查询时刻”的底层保证（MySQL/InnoDB 与 H2 同理）。
     */
    @Test
    void 导出期间新增流水快照仍自洽(
            @Autowired EntityManager em,
            @Autowired PlatformTransactionManager txManager) throws Exception {
        for (int i = 0; i < 3; i++) {
            log(routeAId, "IT-ROUTE-A", "BIND",
                    LocalDateTime.of(2026, 9, 20, 8, 0).plusMinutes(i));
        }

        CountDownLatch countDone = new CountDownLatch(1);
        CountDownLatch insertDone = new CountDownLatch(1);
        AtomicReference<RuntimeException> writerError = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                countDone.await();
                FlightRoute route = flightRouteRepository.findById(routeAId).orElseThrow();
                // REQUIRES_NEW 独立事务提交一条 REJECT 流水
                auditRecorder.record(route, null, AdaptAuditRecorder.OP_REJECT,
                        new BigDecimal("9.00"), null, "导出进行中新增的拒绝流水", "tester");
                insertDone.countDown();
            } catch (RuntimeException e) {
                writerError.set(e);
                insertDone.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();

        TransactionTemplate rrTx = new TransactionTemplate(txManager);
        rrTx.setIsolationLevel(Isolation.REPEATABLE_READ.value());

        long totalInTx = rrTx.execute(status -> {
            long total = em.createQuery(
                            "select count(l) from AdaptLog l where l.routeId = :rid", Long.class)
                    .setParameter("rid", routeAId)
                    .getSingleResult();
            countDone.countDown();
            try {
                insertDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 外部已提交插入；同一 REPEATABLE_READ 事务内再读，仍须是旧视图
            @SuppressWarnings("unchecked")
            List<AdaptLog> rows = em.createQuery(
                            "select l from AdaptLog l where l.routeId = :rid "
                                    + "order by l.createTime desc, l.id desc")
                    .setParameter("rid", routeAId)
                    .getResultList();
            assertThat(total).isEqualTo(3);
            assertThat(rows).hasSize(3); // 不会 total=3 却读到 4 行
            return total;
        });

        writer.join(5000);
        assertThat(writerError.get()).isNull();
        assertThat(totalInTx).isEqualTo(3);

        // 服务端导出方法（同样的 REPEATABLE_READ 事务）自身也保持 total==行数
        var export = adaptService.getExportSnapshot(routeAId);
        assertThat(export.getTotal()).isEqualTo(export.getRecords().size());

        // 新事务再查能看到新增的第 4 条
        var after = adaptService.getLogsSnapshot(routeAId, 1, 50);
        assertThat(after.getTotal()).isEqualTo(4);
    }
}
