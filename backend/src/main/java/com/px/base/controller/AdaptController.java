package com.px.base.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.px.base.dto.AdaptLogSnapshotDTO;
import com.px.base.dto.AdaptResultDTO;
import com.px.base.dto.BindDTO;
import com.px.base.dto.ResponseDTO;
import com.px.base.entity.RouteAnchor;
import com.px.base.service.AdaptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/adapt")
@RequiredArgsConstructor
public class AdaptController {
    private final AdaptService adaptService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @PostMapping("/bind")
    public ResponseDTO<AdaptResultDTO> bind(@RequestBody BindDTO dto) {
        try {
            AdaptResultDTO result = adaptService.bindAnchor(dto.getRouteId(), dto.getAnchorId());
            if (result.isValid()) {
                return ResponseDTO.success(result);
            } else {
                return ResponseDTO.error(400, result.getReason());
            }
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    @PostMapping("/unbind")
    public ResponseDTO<AdaptResultDTO> unbind(@RequestBody BindDTO dto) {
        try {
            AdaptResultDTO result = adaptService.unbindAnchor(dto.getRouteId(), dto.getAnchorId());
            if (result.isValid()) {
                return ResponseDTO.success(result);
            } else {
                return ResponseDTO.error(400, result.getReason());
            }
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    @GetMapping("/check")
    public ResponseDTO<AdaptResultDTO> check(
            @RequestParam Long routeId,
            @RequestParam Long anchorId) {
        try {
            AdaptResultDTO result = adaptService.checkAdapt(routeId, anchorId);
            return ResponseDTO.success(result);
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    @PostMapping("/recheck/{routeId}")
    public ResponseDTO<AdaptResultDTO> recheck(@PathVariable Long routeId) {
        try {
            AdaptResultDTO result = adaptService.recheckRouteAnchors(routeId, null, null);
            return ResponseDTO.success(result);
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    /**
     * 流水分页读取（流水页唯一读取口径）。
     *
     * <p>筛选、排序、总数、页码、记录顺序由服务端在同一查询时刻给出快照：
     * <ul>
     *   <li>不传 routeId = 全部航线；传了不存在的航线返回 400（请求失败），而不是空列表；</li>
     *   <li>筛选存在但没有命中 → 200 且 total=0，明确区分“没有命中”和“请求失败”；</li>
     *   <li>固定按发生时间倒序 + 自增编号倒序，拒绝记录刷新后不再跳动。</li>
     * </ul>
     */
    @GetMapping("/logs")
    public ResponseDTO<AdaptLogSnapshotDTO> getLogs(
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        Long filterId = normalizeRouteId(routeId);
        adaptService.validateRouteFilter(filterId);
        return ResponseDTO.success(adaptService.getLogsSnapshot(filterId, page, size));
    }

    /**
     * 服务端导出快照：接收当前筛选条件（routeId），在服务端按与读取完全一致的
     * 稳定排序生成整份 JSON，前端不允许拿页面旧数组拼 JSON。
     *
     * <p>文件名与内容都带筛选航线、查询时间、记录总数；即使 total=0 也下载一份
     * total=0 的快照（“没有命中”）；参数非法时返回 4xx（“请求失败”，不下载）。
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs(@RequestParam(required = false) Long routeId)
            throws JsonProcessingException {
        Long filterId = normalizeRouteId(routeId);
        adaptService.validateRouteFilter(filterId);
        AdaptLogSnapshotDTO snapshot = adaptService.getExportSnapshot(filterId);

        byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);

        String routePart = filterId == null ? "全部航线" : snapshot.getRouteCode();
        String fileBaseName = "adapt-logs_%s_%s_共%d条".formatted(
                routePart, snapshot.getQueryTime().format(FILE_TIME), snapshot.getTotal());
        // RFC 5987：中文文件名用 filename* 传递，ASCII 兜底名保留同样信息
        String asciiFallback = "adapt-logs_%s_%s_total%d.json".formatted(
                filterId == null ? "ALL" : ("route-" + filterId),
                snapshot.getQueryTime().format(FILE_TIME), snapshot.getTotal());
        String encoded = URLEncoder.encode(fileBaseName + ".json", StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }

    /** routeId 非正数（0/负数）一律视为“全部航线”，直连接口语义也保持一致。 */
    private Long normalizeRouteId(Long routeId) {
        return routeId != null && routeId <= 0 ? null : routeId;
    }

    @GetMapping("/bound/{routeId}")
    public ResponseDTO<List<RouteAnchor>> getBoundAnchors(@PathVariable Long routeId) {
        List<RouteAnchor> anchors = adaptService.getBoundAnchors(routeId);
        return ResponseDTO.success(anchors);
    }
}
